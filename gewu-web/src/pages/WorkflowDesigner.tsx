import { useState, useEffect, useRef, useCallback, useMemo } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { Button, Input, Modal, Form, message, Tag, Spin, Space, Typography, Badge, Tooltip } from 'antd'
import {
  ArrowLeftOutlined,
  SaveOutlined,
  SendOutlined,
  DeleteOutlined,
  PlayCircleOutlined,
  StopOutlined,
  CheckCircleOutlined,
  AuditOutlined,
  BranchesOutlined,
  ApartmentOutlined,
  SafetyCertificateOutlined,
  LayoutOutlined,
  AppstoreOutlined,
  CloseCircleOutlined,
  ExclamationCircleOutlined,
  LeftOutlined,
  RightOutlined,
} from '@ant-design/icons'
import ReactFlow, {
  Background,
  Controls,
  MiniMap,
  Handle,
  Position,
  useNodesState,
  useEdgesState,
  addEdge,
  MarkerType,
  BaseEdge,
  EdgeLabelRenderer,
  getSmoothStepPath,
  type Node,
  type Edge,
  type Connection,
  type NodeProps,
  type EdgeProps,
  type ReactFlowInstance,
} from 'reactflow'
import 'reactflow/dist/style.css'
import { get, post, put, WorkflowDTO, WorkflowGraphDTO, SaveWorkflowGraphCommand, CreateWorkflowRequest } from '../services/api'

const { Text } = Typography

const NODE_TYPES = ['start', 'end', 'task', 'approval', 'condition', 'subprocess'] as const
type NodeType = typeof NODE_TYPES[number]

const nodeTypeMeta: Record<NodeType, { label: string; color: string; hex: string; icon: React.ReactNode; defaultName: string }> = {
  start: { label: '开始', color: 'green', hex: '#52c41a', icon: <PlayCircleOutlined />, defaultName: '开始' },
  end: { label: '结束', color: 'red', hex: '#ff4d4f', icon: <StopOutlined />, defaultName: '结束' },
  task: { label: '任务', color: 'blue', hex: '#1677ff', icon: <CheckCircleOutlined />, defaultName: '任务节点' },
  approval: { label: '审批', color: 'orange', hex: '#fa8c16', icon: <AuditOutlined />, defaultName: '审批节点' },
  condition: { label: '条件', color: 'purple', hex: '#722ed1', icon: <BranchesOutlined />, defaultName: '条件节点' },
  subprocess: { label: '子流程', color: 'cyan', hex: '#13c2c2', icon: <ApartmentOutlined />, defaultName: '子流程节点' },
}

interface WFNodeData {
  nodeType: NodeType
  label: string
  conditionExpr?: string
  [key: string]: unknown
}

interface ValidationItem {
  severity: 'error' | 'warning'
  message: string
}

function CustomNode({ id, data, selected }: NodeProps<WFNodeData>) {
  const meta = nodeTypeMeta[data.nodeType]
  return (
    <div
      style={{
        padding: '10px 16px',
        borderRadius: 8,
        borderWidth: 2,
        borderStyle: 'solid',
        borderColor: selected ? '#1677ff' : meta.color,
        background: `${meta.color}15`,
        minWidth: 120,
        textAlign: 'center',
        position: 'relative',
      }}
    >
      <Handle type="target" position={Position.Top} />
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, justifyContent: 'center' }}>
        <span style={{ fontSize: 18, color: meta.color }}>{meta.icon}</span>
        <span style={{ fontWeight: 500 }}>{data.label}</span>
      </div>
      {selected && (
        <DeleteOutlined
          style={{ position: 'absolute', top: -8, right: -8, color: '#ff4d4f', cursor: 'pointer', background: '#fff', borderRadius: '50%' }}
          onClick={(e) => {
            e.stopPropagation()
            window.dispatchEvent(new CustomEvent('delete-node', { detail: id }))
          }}
        />
      )}
      <Handle type="source" position={Position.Bottom} />
    </div>
  )
}

function CustomEdge({
  id,
  sourceX,
  sourceY,
  targetX,
  targetY,
  sourcePosition,
  targetPosition,
  data,
  label,
  markerEnd,
  selected,
}: EdgeProps) {
  const [edgePath, labelX, labelY] = getSmoothStepPath({ sourceX, sourceY, sourcePosition, targetX, targetY, targetPosition })
  const edgeData = (data as { color?: string; animated?: boolean; editing?: boolean } | undefined) || {}
  const color = edgeData.color || '#b1b1b7'
  const animated = edgeData.animated
  const editing = edgeData.editing
  return (
    <>
      <BaseEdge id={id} path={edgePath} markerEnd={markerEnd} style={{ stroke: color, strokeWidth: selected ? 3 : 2 }} />
      {animated && <path d={edgePath} fill="none" stroke={color} strokeWidth={4} className="wf-pulse" style={{ pointerEvents: 'none' }} />}
      <EdgeLabelRenderer>
        <div
          style={{ position: 'absolute', transform: `translate(-50%, -50%) translate(${labelX}px, ${labelY}px)`, pointerEvents: 'all' }}
          className="nodrag nopan"
        >
          {editing ? (
            <input
              className="wf-edge-input"
              autoFocus
              value={(label as string) || ''}
              onChange={(e) => window.dispatchEvent(new CustomEvent('update-edge-label', { detail: { id, value: e.target.value } }))}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === 'Escape') {
                  e.stopPropagation()
                  window.dispatchEvent(new CustomEvent('finish-edge-edit', { detail: id }))
                }
              }}
              onBlur={() => window.dispatchEvent(new CustomEvent('finish-edge-edit', { detail: id }))}
            />
          ) : label ? (
            <div className="wf-edge-label" style={{ borderColor: color }}>{label as string}</div>
          ) : null}
        </div>
      </EdgeLabelRenderer>
    </>
  )
}

const nodeTypes = {
  start: CustomNode,
  end: CustomNode,
  task: CustomNode,
  approval: CustomNode,
  condition: CustomNode,
  subprocess: CustomNode,
}

const edgeTypes = { wf: CustomEdge }

const paletteItems: NodeType[] = [...NODE_TYPES]

function validateGraph(nodes: Node<WFNodeData>[], edges: Edge[]): ValidationItem[] {
  const items: ValidationItem[] = []
  const starts = nodes.filter((n) => n.data.nodeType === 'start')
  const ends = nodes.filter((n) => n.data.nodeType === 'end')
  if (starts.length !== 1) items.push({ severity: 'error', message: `必须有且仅有一个开始节点（当前 ${starts.length} 个）` })
  if (ends.length !== 1) items.push({ severity: 'error', message: `必须有且仅有一个结束节点（当前 ${ends.length} 个）` })

  for (const n of nodes) {
    if ((n.data.nodeType === 'task' || n.data.nodeType === 'approval') && !n.data.label?.trim()) {
      items.push({ severity: 'error', message: `节点「${n.data.label || n.id}」缺少名称` })
    }
    if (n.data.nodeType === 'condition' && !n.data.conditionExpr?.trim()) {
      items.push({ severity: 'error', message: `条件节点「${n.data.label || n.id}」缺少条件表达式` })
    }
  }

  for (const n of nodes) {
    const hasOut = edges.some((ed) => ed.source === n.id)
    const hasIn = edges.some((ed) => ed.target === n.id)
    if (n.data.nodeType !== 'end' && !hasOut) items.push({ severity: 'warning', message: `节点「${n.data.label || n.id}」没有出口连接` })
    if (n.data.nodeType !== 'start' && !hasIn) items.push({ severity: 'warning', message: `节点「${n.data.label || n.id}」没有入口连接` })
  }

  if (starts.length === 1 && ends.length === 1) {
    const adj = new Map<string, string[]>()
    for (const n of nodes) adj.set(n.id, [])
    for (const e of edges) adj.get(e.source)?.push(e.target)

    const WHITE = 0, GRAY = 1, BLACK = 2
    const color = new Map<string, number>()
    for (const n of nodes) color.set(n.id, WHITE)
    let hasCycle = false
    const dfs = (u: string) => {
      color.set(u, GRAY)
      for (const v of adj.get(u) || []) {
        const c = color.get(v)
        if (c === GRAY) hasCycle = true
        else if (c === WHITE) dfs(v)
      }
      color.set(u, BLACK)
    }
    dfs(starts[0].id)
    for (const n of nodes) if (color.get(n.id) === WHITE) dfs(n.id)
    if (hasCycle) items.push({ severity: 'error', message: '图中存在环' })

    const reachable = new Set<string>([starts[0].id])
    const queue = [starts[0].id]
    while (queue.length) {
      const u = queue.shift()!
      for (const v of adj.get(u) || []) {
        if (!reachable.has(v)) { reachable.add(v); queue.push(v) }
      }
    }
    if (!reachable.has(ends[0].id)) items.push({ severity: 'error', message: '开始节点到结束节点不存在路径' })
  }

  return items
}

function autoLayout(nodes: Node<WFNodeData>[], edges: Edge[]): Node<WFNodeData>[] {
  const startNode = nodes.find((n) => n.data.nodeType === 'start')
  const endNode = nodes.find((n) => n.data.nodeType === 'end')
  const adj = new Map<string, string[]>()
  for (const n of nodes) adj.set(n.id, [])
  for (const e of edges) adj.get(e.source)?.push(e.target)

  const level = new Map<string, number>()
  if (startNode) {
    level.set(startNode.id, 0)
    const queue = [startNode.id]
    while (queue.length) {
      const u = queue.shift()!
      for (const v of adj.get(u) || []) {
        if (!level.has(v)) { level.set(v, (level.get(u) || 0) + 1); queue.push(v) }
      }
    }
  }

  let maxLevel = 0
  for (const l of level.values()) if (l > maxLevel) maxLevel = l
  for (const n of nodes) {
    if (!level.has(n.id)) { maxLevel += 1; level.set(n.id, maxLevel) }
  }
  if (endNode) { maxLevel += 1; level.set(endNode.id, maxLevel) }

  const byLevel = new Map<number, string[]>()
  for (const [id, l] of level.entries()) {
    if (!byLevel.has(l)) byLevel.set(l, [])
    byLevel.get(l)!.push(id)
  }

  const positions = new Map<string, { x: number; y: number }>()
  for (const [l, ids] of byLevel.entries()) {
    const startX = -(ids.length - 1) * 100
    ids.forEach((id, i) => positions.set(id, { x: startX + i * 200, y: l * 120 }))
  }

  return nodes.map((n) => ({ ...n, position: positions.get(n.id) || n.position }))
}

export default function WorkflowDesigner() {
  const params = useParams()
  const navigate = useNavigate()
  const isNew = params.workflowId === 'new' || !params.workflowId
  const [workflowId, setWorkflowId] = useState<string | null>(isNew ? null : params.workflowId!)
  const [workflow, setWorkflow] = useState<WorkflowDTO | null>(null)
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [publishing, setPublishing] = useState(false)
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null)

  const [nodes, setNodes, onNodesChange] = useNodesState<WFNodeData>([])
  const [edges, setEdges, onEdgesChange] = useEdgesState([])
  const [edgeLabelModal, setEdgeLabelModal] = useState<{ open: boolean; connection?: Connection }>({ open: false })
  const [pendingLabel, setPendingLabel] = useState('')
  const [nameModal, setNameModal] = useState<{ open: boolean; nodeId?: string }>({ open: false })
  const [pendingName, setPendingName] = useState('')
  const [createModal, setCreateModal] = useState(false)
  const [createForm] = Form.useForm<CreateWorkflowRequest>()

  const [showValidation, setShowValidation] = useState(false)
  const [snapToGridEnabled, setSnapToGridEnabled] = useState(false)
  const [panelCollapsed, setPanelCollapsed] = useState(false)
  const [editingEdgeId, setEditingEdgeId] = useState<string | null>(null)
  const [edgeMenu, setEdgeMenu] = useState<{ x: number; y: number; edgeId: string } | null>(null)

  const reactFlowWrapper = useRef<HTMLDivElement>(null)
  const [rfInstance, setRfInstance] = useState<ReactFlowInstance<Node<WFNodeData>, Edge> | null>(null)

  const loadGraph = useCallback(async (id: string) => {
    setLoading(true)
    try {
      const [wfResult, graphResult] = await Promise.all([
        get<WorkflowDTO>(`/workflows/${id}`),
        get<WorkflowGraphDTO>(`/workflows/${id}/graph`),
      ])
      if (wfResult.success) setWorkflow(wfResult.data)
      if (graphResult.success) {
        const loadedNodes: Node<WFNodeData>[] = graphResult.data.nodes.map((n, i) => ({
          id: n.nodeId!,
          type: n.nodeType as NodeType,
          position: { x: n.positionX ?? 0, y: n.positionY ?? 0 },
          data: { nodeType: n.nodeType as NodeType, label: n.nodeName },
          sortOrder: i,
        } as Node<WFNodeData> & { sortOrder: number }))
        const loadedEdges: Edge[] = graphResult.data.transitions.map((t, i) => ({
          id: `e-${t.fromNodeId}-${t.toNodeId}`,
          source: t.fromNodeId,
          target: t.toNodeId,
          label: t.label || '',
          type: 'wf',
          labelBgPadding: [8, 4] as [number, number],
          labelBgBorderRadius: 4,
          labelBgStyle: { fill: '#fff' },
          sortOrder: i,
        } as Edge & { sortOrder: number }))
        setNodes(loadedNodes)
        setEdges(loadedEdges)
      }
    } catch (error: any) {
      message.error(error.message || '加载工作流失败')
    } finally {
      setLoading(false)
    }
  }, [setNodes, setEdges])

  useEffect(() => {
    if (!isNew && workflowId) {
      loadGraph(workflowId)
    } else if (isNew) {
      setCreateModal(true)
    }
  }, [isNew, workflowId, loadGraph])

  useEffect(() => {
    const handler = (e: Event) => {
      const nodeId = (e as CustomEvent).detail as string
      setNodes((nds) => nds.filter((n) => n.id !== nodeId))
      setEdges((eds) => eds.filter((ed) => ed.source !== nodeId && ed.target !== nodeId))
      setSelectedNodeId(null)
    }
    window.addEventListener('delete-node', handler)
    return () => window.removeEventListener('delete-node', handler)
  }, [setNodes, setEdges])

  useEffect(() => {
    const update = (e: Event) => {
      const detail = (e as CustomEvent).detail as { id: string; value: string }
      setEdges((eds) => eds.map((ed) => (ed.id === detail.id ? { ...ed, label: detail.value } : ed)))
    }
    const finish = () => setEditingEdgeId(null)
    window.addEventListener('update-edge-label', update)
    window.addEventListener('finish-edge-edit', finish)
    return () => {
      window.removeEventListener('update-edge-label', update)
      window.removeEventListener('finish-edge-edit', finish)
    }
  }, [setEdges])

  const onConnect = useCallback((connection: Connection) => {
    setPendingLabel('')
    setEdgeLabelModal({ open: true, connection })
  }, [])

  const confirmEdgeLabel = () => {
    const { connection } = edgeLabelModal
    if (!connection) return
    const newEdge: Edge = {
      id: `e-${connection.source}-${connection.target}`,
      source: connection.source!,
      target: connection.target!,
      label: pendingLabel || '',
      type: 'wf',
      labelBgPadding: [8, 4],
      labelBgBorderRadius: 4,
      labelBgStyle: { fill: '#fff' },
    }
    setEdges((eds) => addEdge(newEdge, eds))
    setEdgeLabelModal({ open: false })
    setPendingLabel('')
  }

  const onDrop = useCallback((event: React.DragEvent) => {
    event.preventDefault()
    const type = event.dataTransfer.getData('application/reactflow') as NodeType
    if (!type) return
    if (!rfInstance) return
    const position = rfInstance.screenToFlowPosition({ x: event.clientX, y: event.clientY })
    if (snapToGridEnabled) {
      position.x = Math.round(position.x / 20) * 20
      position.y = Math.round(position.y / 20) * 20
    }
    const id = crypto.randomUUID()
    const meta = nodeTypeMeta[type]
    const newNode: Node<WFNodeData> = {
      id,
      type,
      position,
      data: { nodeType: type, label: meta.defaultName },
    }
    setNodes((nds) => [...nds, newNode])
    setPendingName(meta.defaultName)
    setNameModal({ open: true, nodeId: id })
  }, [rfInstance, setNodes, snapToGridEnabled])

  const onDragOver = useCallback((event: React.DragEvent) => {
    event.preventDefault()
    event.dataTransfer.dropEffect = 'move'
  }, [])

  const onNodeClick = useCallback((_: React.MouseEvent, node: Node<WFNodeData>) => {
    setSelectedNodeId(node.id)
    setEditingEdgeId(null)
    setEdgeMenu(null)
  }, [])

  const onPaneClick = useCallback(() => {
    setSelectedNodeId(null)
    setEditingEdgeId(null)
    setEdgeMenu(null)
  }, [])

  const onEdgeDoubleClick = useCallback((_: React.MouseEvent, edge: Edge) => {
    setEditingEdgeId(edge.id)
    setEdgeMenu(null)
  }, [])

  const onEdgeContextMenu = useCallback((event: React.MouseEvent, edge: Edge) => {
    event.preventDefault()
    setEdgeMenu({ x: event.clientX, y: event.clientY, edgeId: edge.id })
  }, [])

  const confirmNodeName = () => {
    const { nodeId } = nameModal
    if (!nodeId) return
    setNodes((nds) => nds.map((n) => n.id === nodeId ? { ...n, data: { ...n.data, label: pendingName || n.data.label } } : n))
    setNameModal({ open: false })
    setPendingName('')
  }

  const selectedNode = nodes.find((n) => n.id === selectedNodeId) || null
  const outgoingEdges = edges.filter((ed) => ed.source === selectedNodeId)

  const updateSelectedNode = (patch: Partial<WFNodeData>) => {
    if (!selectedNodeId) return
    setNodes((nds) => nds.map((n) => n.id === selectedNodeId ? { ...n, data: { ...n.data, ...patch } } : n))
  }

  const validation = useMemo(() => validateGraph(nodes, edges), [nodes, edges])
  const hasErrors = validation.some((v) => v.severity === 'error')

  const styledEdges = useMemo(() => edges.map((ed) => {
    const src = nodes.find((n) => n.id === ed.source)
    const tgt = nodes.find((n) => n.id === ed.target)
    const srcType = src?.data.nodeType
    const tgtType = tgt?.data.nodeType
    const isCondition = srcType === 'condition' || tgtType === 'condition'
    const color = (srcType && nodeTypeMeta[srcType].hex) || '#b1b1b7'
    return {
      ...ed,
      type: 'wf',
      animated: isCondition,
      style: { stroke: color, strokeWidth: 2 },
      markerEnd: { type: MarkerType.ArrowClosed, color },
      data: { ...(ed.data || {}), color, animated: isCondition, editing: editingEdgeId === ed.id },
    }
  }), [edges, nodes, editingEdgeId])

  const saveGraph = useCallback(async (id: string) => {
    setSaving(true)
    try {
      const command: SaveWorkflowGraphCommand = {
        nodes: nodes.map((n, i) => ({
          nodeId: n.id,
          nodeName: n.data.label,
          nodeType: n.data.nodeType,
          positionX: n.position.x,
          positionY: n.position.y,
          config: n.data.nodeType === 'condition' ? JSON.stringify({ conditionExpr: n.data.conditionExpr || '' }) : undefined,
          sortOrder: i,
        })),
        transitions: edges.map((ed, i) => ({
          fromNodeId: ed.source,
          toNodeId: ed.target,
          label: (ed.label as string) || '',
          sortOrder: i,
        })),
      }
      const result = await put<void>(`/workflows/${id}/graph`, command)
      if (result.success) {
        message.success('保存成功')
      } else {
        message.error(result.message)
      }
    } catch (error: any) {
      message.error(error.message || '保存失败')
    } finally {
      setSaving(false)
    }
  }, [nodes, edges])

  const handleSave = async () => {
    if (!workflowId) {
      setCreateModal(true)
      return
    }
    saveGraph(workflowId)
  }

  const handlePublish = async () => {
    if (!workflowId) return
    if (hasErrors) {
      message.warning('存在校验错误，无法发布')
      setShowValidation(true)
      return
    }
    setPublishing(true)
    try {
      const result = await post<void>(`/workflows/${workflowId}/publish`)
      if (result.success) {
        message.success('发布成功')
        loadGraph(workflowId)
      } else {
        message.error(result.message)
      }
    } catch (error: any) {
      message.error(error.message || '发布失败')
    } finally {
      setPublishing(false)
    }
  }

  const handleCreateWorkflow = async (values: CreateWorkflowRequest) => {
    try {
      const result = await post<WorkflowDTO>('/workflows', values)
      if (result.success) {
        const newId = result.data.workflowId
        setWorkflowId(newId)
        setWorkflow(result.data)
        setCreateModal(false)
        createForm.resetFields()
        saveGraph(newId)
        window.history.replaceState(null, '', `/workflows/${newId}/design`)
      } else {
        message.error(result.message)
      }
    } catch (error: any) {
      message.error(error.message || '创建工作流失败')
    }
  }

  const handleAutoLayout = () => {
    setNodes((nds) => autoLayout(nds, edges))
    setTimeout(() => rfInstance?.fitView({ padding: 0.2 }), 60)
  }

  const deleteSelectedNode = () => {
    if (!selectedNode) return
    setNodes((nds) => nds.filter((n) => n.id !== selectedNode.id))
    setEdges((eds) => eds.filter((ed) => ed.source !== selectedNode.id && ed.target !== selectedNode.id))
    setSelectedNodeId(null)
  }

  const latestRef = useRef<{ save: () => void; publish: () => void; selectedNodeId: string | null }>({ save: () => {}, publish: () => {}, selectedNodeId: null })
  latestRef.current = { save: handleSave, publish: handlePublish, selectedNodeId }

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement | null
      const typing = !!target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable)
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
        e.preventDefault()
        latestRef.current.save()
        return
      }
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'p') {
        e.preventDefault()
        latestRef.current.publish()
        return
      }
      if (typing) return
      if (e.key === 'Delete' || e.key === 'Backspace') {
        e.preventDefault()
        const sid = latestRef.current.selectedNodeId
        if (sid) {
          setNodes((nds) => nds.filter((n) => n.id !== sid))
          setEdges((eds) => eds.filter((ed) => ed.source !== sid && ed.target !== sid))
          setSelectedNodeId(null)
        } else {
          setEdges((eds) => eds.filter((ed) => !ed.selected))
        }
      } else if (e.key === 'Escape') {
        setSelectedNodeId(null)
        setEditingEdgeId(null)
        setEdgeMenu(null)
        setNodes((nds) => nds.map((n) => ({ ...n, selected: false })))
        setEdges((eds) => eds.map((ed) => ({ ...ed, selected: false })))
      }
    }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [setNodes, setEdges, setSelectedNodeId, setEditingEdgeId, setEdgeMenu])

  return (
    <div style={{ height: '100vh', display: 'flex', flexDirection: 'column', background: '#f5f5f5' }}>
      <style>{`
        @keyframes wf-pulse { 0%, 100% { opacity: .15 } 50% { opacity: .6 } }
        .wf-pulse { animation: wf-pulse 1.6s ease-in-out infinite; }
        .wf-edge-label { padding: 2px 6px; background: #fff; border: 1px solid #d9d9d9; border-radius: 4px; font-size: 12px; pointer-events: none; white-space: nowrap; }
        .wf-edge-input { padding: 2px 6px; font-size: 12px; border: 1px solid #1677ff; border-radius: 4px; outline: none; width: 100px; box-shadow: 0 0 0 2px rgba(22,119,255,.15); }
      `}</style>

      <div style={{ height: 60, background: '#fff', display: 'flex', alignItems: 'center', padding: '0 16px', borderBottom: '1px solid #e8e8e8', justifyContent: 'space-between', flexShrink: 0 }}>
        <Space>
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/workflows')}>返回</Button>
          <Text strong style={{ fontSize: 16 }}>{workflow?.workflowName || '新建工作流'}</Text>
          {workflow && <Tag color={nodeTypeMeta.start.color}>{workflow.status === 0 ? '草稿' : workflow.status === 1 ? '已发布' : '已归档'}</Tag>}
        </Space>
        <Space size={12}>
          <Space size={4}>
            <Badge count={nodes.length} overflowCount={9999} style={{ backgroundColor: '#52c41a' }} />
            <Text type="secondary" style={{ fontSize: 13 }}>节点</Text>
          </Space>
          <Space size={4}>
            <Badge count={edges.length} overflowCount={9999} style={{ backgroundColor: '#1677ff' }} />
            <Text type="secondary" style={{ fontSize: 13 }}>连线</Text>
          </Space>
          <Button size="small" icon={<SafetyCertificateOutlined />} type={showValidation ? 'primary' : 'default'} onClick={() => setShowValidation((v) => !v)}>校验</Button>
          <Button size="small" icon={<LayoutOutlined />} onClick={handleAutoLayout}>自动布局</Button>
          <Button size="small" icon={<AppstoreOutlined />} type={snapToGridEnabled ? 'primary' : 'default'} onClick={() => setSnapToGridEnabled((v) => !v)}>网格对齐</Button>
          <Button icon={<SaveOutlined />} loading={saving} onClick={handleSave}>保存</Button>
          <Tooltip title={hasErrors ? '存在校验错误，无法发布' : ''}>
            <span>
              <Button type="primary" icon={<SendOutlined />} loading={publishing} onClick={handlePublish} disabled={!workflowId || hasErrors}>发布</Button>
            </span>
          </Tooltip>
        </Space>
      </div>

      <div style={{ flex: 1, display: 'flex', minHeight: 0 }}>
        <div style={{ width: 200, background: '#fff', borderRight: '1px solid #e8e8e8', padding: 16, overflowY: 'auto', flexShrink: 0 }}>
          <Text strong style={{ display: 'block', marginBottom: 12 }}>节点面板</Text>
          <Space direction="vertical" style={{ width: '100%' }} size={8}>
            {paletteItems.map((type) => {
              const meta = nodeTypeMeta[type]
              return (
                <div
                  key={type}
                  draggable
                  onDragStart={(e) => e.dataTransfer.setData('application/reactflow', type)}
                  style={{
                    border: `2px solid ${meta.color}`,
                    borderRadius: 8,
                    padding: '10px 12px',
                    cursor: 'grab',
                    display: 'flex',
                    alignItems: 'center',
                    gap: 8,
                    background: `${meta.color}08`,
                  }}
                >
                  <span style={{ fontSize: 18, color: meta.color }}>{meta.icon}</span>
                  <span style={{ fontWeight: 500 }}>{meta.label}</span>
                </div>
              )
            })}
          </Space>
          <div style={{ marginTop: 16, borderTop: '1px solid #f0f0f0', paddingTop: 12 }}>
            <Text type="secondary" style={{ display: 'block', marginBottom: 8 }}>图例</Text>
            {NODE_TYPES.map((t) => {
              const m = nodeTypeMeta[t]
              return (
                <div key={t} style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6, fontSize: 12 }}>
                  <span style={{ width: 12, height: 12, borderRadius: 3, background: m.hex, border: `1px solid ${m.hex}` }} />
                  <span>{m.label}</span>
                </div>
              )
            })}
          </div>
        </div>

        <div ref={reactFlowWrapper} style={{ flex: 1, position: 'relative', minWidth: 0 }}>
          {loading ? (
            <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100%' }}>
              <Spin size="large" />
            </div>
          ) : (
            <ReactFlow
              nodes={nodes}
              edges={styledEdges}
              onNodesChange={onNodesChange}
              onEdgesChange={onEdgesChange}
              onConnect={onConnect}
              onDrop={onDrop}
              onDragOver={onDragOver}
              onInit={setRfInstance}
              onNodeClick={onNodeClick}
              onPaneClick={onPaneClick}
              onEdgeDoubleClick={onEdgeDoubleClick}
              onEdgeContextMenu={onEdgeContextMenu}
              nodeTypes={nodeTypes}
              edgeTypes={edgeTypes}
              snapToGrid={snapToGridEnabled}
              snapGrid={[20, 20]}
              deleteKeyCode={null}
              fitView
              defaultEdgeOptions={{ type: 'wf' }}
            >
              <Background variant={'dots' as any} gap={20} size={2} color={snapToGridEnabled ? '#8ca0b8' : '#c8c8c8'} />
              <Controls />
              <MiniMap
                pannable
                zoomable
                nodeColor={(node) => nodeTypeMeta[(node.data as WFNodeData).nodeType]?.hex || '#ddd'}
                nodeStrokeColor={(node) => nodeTypeMeta[(node.data as WFNodeData).nodeType]?.hex || '#999'}
              />
            </ReactFlow>
          )}

          {showValidation && (
            <div style={{ position: 'absolute', top: 12, right: 12, width: 280, maxHeight: '60%', overflowY: 'auto', background: '#fff', border: '1px solid #e8e8e8', borderRadius: 8, boxShadow: '0 2px 8px rgba(0,0,0,.12)', padding: 12, zIndex: 10 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
                <Text strong>校验结果</Text>
                <span style={{ cursor: 'pointer', color: '#999' }} onClick={() => setShowValidation(false)}>✕</span>
              </div>
              {validation.length === 0 ? (
                <div style={{ color: '#52c41a', display: 'flex', alignItems: 'center', gap: 8 }}>
                  <CheckCircleOutlined /> 校验通过
                </div>
              ) : (
                validation.map((v, i) => (
                  <div key={i} style={{ display: 'flex', gap: 8, alignItems: 'flex-start', marginBottom: 6, fontSize: 13 }}>
                    {v.severity === 'error'
                      ? <CloseCircleOutlined style={{ color: '#ff4d4f', marginTop: 2, flexShrink: 0 }} />
                      : <ExclamationCircleOutlined style={{ color: '#faad14', marginTop: 2, flexShrink: 0 } } />}
                    <span style={{ color: v.severity === 'error' ? '#ff4d4f' : '#8c8c8c' }}>{v.message}</span>
                  </div>
                ))
              )}
            </div>
          )}
        </div>

        {selectedNode && !panelCollapsed && (
          <div style={{ width: 300, background: '#fff', borderLeft: '1px solid #e8e8e8', padding: 16, overflowY: 'auto', flexShrink: 0 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
              <Text strong style={{ fontSize: 15 }}>属性面板</Text>
              <Space size={4}>
                <Button danger size="small" icon={<DeleteOutlined />} onClick={deleteSelectedNode}>删除</Button>
                <Button size="small" type="text" icon={<RightOutlined />} onClick={() => setPanelCollapsed(true)} title="收起" />
              </Space>
            </div>
            <div style={{ marginBottom: 16 }}>
              <Text type="secondary" style={{ display: 'block', marginBottom: 4 }}>节点名称</Text>
              <Input
                value={selectedNode.data.label}
                onChange={(e) => updateSelectedNode({ label: e.target.value })}
              />
            </div>
            <div style={{ marginBottom: 16 }}>
              <Text type="secondary" style={{ display: 'block', marginBottom: 4 }}>节点类型</Text>
              <Tag color={nodeTypeMeta[selectedNode.data.nodeType].color}>{nodeTypeMeta[selectedNode.data.nodeType].label}</Tag>
            </div>
            {outgoingEdges.length > 0 && (
              <div style={{ marginBottom: 16 }}>
                <Text type="secondary" style={{ display: 'block', marginBottom: 4 }}>出口转换标签</Text>
                {outgoingEdges.map((ed) => {
                  const target = nodes.find((n) => n.id === ed.target)
                  return (
                    <div key={ed.id} style={{ marginBottom: 8 }}>
                      <Text style={{ fontSize: 12, marginRight: 8 }}>→ {target?.data.label || ed.target}</Text>
                      <Input
                        size="small"
                        value={(ed.label as string) || ''}
                        onChange={(e) => setEdges((eds) => eds.map((x) => x.id === ed.id ? { ...x, label: e.target.value } : x))}
                      />
                    </div>
                  )
                })}
              </div>
            )}
            {selectedNode.data.nodeType === 'condition' && (
              <div style={{ marginBottom: 16 }}>
                <Text type="secondary" style={{ display: 'block', marginBottom: 4 }}>条件表达式</Text>
                <Input.TextArea
                  rows={4}
                  value={selectedNode.data.conditionExpr || ''}
                  onChange={(e) => updateSelectedNode({ conditionExpr: e.target.value })}
                  placeholder="例如：amount > 1000"
                />
              </div>
            )}
          </div>
        )}

        {selectedNode && panelCollapsed && (
          <div style={{ width: 32, background: '#fff', borderLeft: '1px solid #e8e8e8', display: 'flex', flexDirection: 'column', alignItems: 'center', paddingTop: 8, flexShrink: 0 }}>
            <Button size="small" type="text" icon={<LeftOutlined />} onClick={() => setPanelCollapsed(false)} title="展开属性面板" />
          </div>
        )}
      </div>

      {edgeMenu && (
        <>
          <div style={{ position: 'fixed', inset: 0, zIndex: 1000 }} onClick={() => setEdgeMenu(null)} onContextMenu={(e) => { e.preventDefault(); setEdgeMenu(null) }} />
          <div style={{ position: 'fixed', top: edgeMenu.y, left: edgeMenu.x, zIndex: 1001, background: '#fff', border: '1px solid #e8e8e8', borderRadius: 6, boxShadow: '0 2px 8px rgba(0,0,0,.15)', padding: 4, minWidth: 120 }}>
            <div
              style={{ padding: '6px 12px', cursor: 'pointer', color: '#ff4d4f', fontSize: 13, display: 'flex', alignItems: 'center', gap: 6 }}
              onClick={() => {
                setEdges((eds) => eds.filter((ed) => ed.id !== edgeMenu.edgeId))
                setEdgeMenu(null)
              }}
            >
              <DeleteOutlined /> 删除连线
            </div>
          </div>
        </>
      )}

      <Modal
        title="连接转换"
        open={edgeLabelModal.open}
        onCancel={() => setEdgeLabelModal({ open: false })}
        onOk={confirmEdgeLabel}
        okText="确定"
        cancelText="取消"
      >
        <Input
          placeholder="请输入转换标签（可留空）"
          value={pendingLabel}
          onChange={(e) => setPendingLabel(e.target.value)}
          onPressEnter={confirmEdgeLabel}
        />
      </Modal>

      <Modal
        title="设置节点名称"
        open={nameModal.open}
        onCancel={() => setNameModal({ open: false })}
        onOk={confirmNodeName}
        okText="确定"
        cancelText="取消"
      >
        <Input
          value={pendingName}
          onChange={(e) => setPendingName(e.target.value)}
          placeholder="请输入节点名称"
          onPressEnter={confirmNodeName}
          autoFocus
        />
      </Modal>

      <Modal
        title="创建工作流"
        open={createModal}
        onCancel={() => navigate('/workflows')}
        confirmLoading={false}
        onOk={() => createForm.submit()}
        okText="创建并保存"
        cancelText="取消"
      >
        <Form form={createForm} layout="vertical" onFinish={handleCreateWorkflow}>
          <Form.Item label="工作流名称" name="workflowName" rules={[{ required: true, message: '请输入工作流名称' }]}>
            <Input placeholder="请输入工作流名称" />
          </Form.Item>
          <Form.Item label="描述" name="description">
            <Input.TextArea rows={2} placeholder="请输入工作流描述" />
          </Form.Item>
          <Form.Item label="分类" name="category">
            <Input placeholder="请输入分类" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}
