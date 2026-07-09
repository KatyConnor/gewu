import { useState, useEffect, useCallback, useMemo } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import {
  Button,
  Tag,
  Spin,
  Descriptions,
  Timeline,
  Input,
  Space,
  Popconfirm,
  message,
} from 'antd'
import type { TimelineItemProps } from 'antd'
import {
  ArrowLeftOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
  ClockCircleOutlined,
  LoadingOutlined,
  MinusCircleOutlined,
  BellOutlined,
} from '@ant-design/icons'
import {
  get,
  put,
  WorkflowInstanceDTO,
  WorkflowNodeInstanceDTO,
  CompleteNodeRequest,
} from '../services/api'
import dayjs from 'dayjs'

const statusConfig: Record<string, { text: string; color: string }> = {
  running: { text: '运行中', color: 'blue' },
  completed: { text: '已完成', color: 'green' },
  suspended: { text: '已挂起', color: 'orange' },
  terminated: { text: '已终止', color: 'red' },
  failed: { text: '已失败', color: 'red' },
}

const nodeStatusIcon: Record<string, { icon: React.ReactNode; color: string }> = {
  completed: { icon: <CheckCircleOutlined />, color: 'green' },
  running: { icon: <LoadingOutlined />, color: 'blue' },
  pending: { icon: <ClockCircleOutlined />, color: 'gray' },
  failed: { icon: <CloseCircleOutlined />, color: 'red' },
  skipped: { icon: <MinusCircleOutlined />, color: 'gray' },
}

const nodeTypeColor: Record<string, string> = {
  start: 'green',
  end: 'red',
  task: 'blue',
  approval: 'purple',
  condition: 'orange',
  parallel: 'cyan',
}

function formatDuration(ms?: number) {
  if (!ms || ms < 0) return '-'
  if (ms < 1000) return `${ms}ms`
  const seconds = Math.floor(ms / 1000)
  if (seconds < 60) return `${seconds}s`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes}m ${seconds % 60}s`
  const hours = Math.floor(minutes / 60)
  return `${hours}h ${minutes % 60}m`
}

function formatTime(ts?: number) {
  return ts ? dayjs(ts).format('YYYY-MM-DD HH:mm:ss') : '-'
}

export default function WorkflowInstanceDetail() {
  const { instanceId } = useParams<{ instanceId: string }>()
  const navigate = useNavigate()
  const [instance, setInstance] = useState<WorkflowInstanceDTO | null>(null)
  const [nodes, setNodes] = useState<WorkflowNodeInstanceDTO[]>([])
  const [loading, setLoading] = useState(true)
  const [remark, setRemark] = useState('')
  const [output, setOutput] = useState('')
  const [submitting, setSubmitting] = useState(false)

  const fetchInstance = useCallback(async () => {
    if (!instanceId) return
    try {
      const [instanceRes, nodesRes] = await Promise.all([
        get<WorkflowInstanceDTO>(`/workflows/instances/${instanceId}`),
        get<WorkflowNodeInstanceDTO[]>(`/workflows/instances/${instanceId}/nodes`),
      ])
      if (instanceRes.success) setInstance(instanceRes.data)
      if (nodesRes.success) setNodes(nodesRes.data)
    } catch (error: any) {
      message.error(error.message || '获取实例详情失败')
    } finally {
      setLoading(false)
    }
  }, [instanceId])

  const refresh = useCallback(async () => {
    await fetchInstance()
  }, [fetchInstance])

  useEffect(() => {
    fetchInstance()
  }, [fetchInstance])

  const currentNode = useMemo<WorkflowNodeInstanceDTO | null>(() => {
    return nodes.find((n) => n.status === 'running') || null
  }, [nodes])

  const isRunning = instance?.status === 'running'
  const isSuspended = instance?.status === 'suspended'
  const isApproval = currentNode?.nodeType === 'approval'
  const isTask = currentNode?.nodeType === 'task'

  const handleComplete = async (approved?: boolean) => {
    if (!instanceId) return
    setSubmitting(true)
    try {
      const payload: CompleteNodeRequest = isApproval
        ? approved
          ? { approved: true, output: '审批通过', remark: remark || '' }
          : { approved: false, output: '审批驳回', remark: remark || '请修改后重新提交' }
        : { output: output || '任务完成', remark: remark || '' }
      const result = await put<WorkflowNodeInstanceDTO>(
        `/workflows/instances/${instanceId}/complete`,
        payload,
      )
      if (result.success) {
        message.success(approved === undefined ? '节点已完成' : approved ? '审批已通过' : '审批已驳回')
        setRemark('')
        setOutput('')
        refresh()
      } else {
        message.error(result.message)
      }
    } catch (error: any) {
      message.error(error.message || '操作失败')
    } finally {
      setSubmitting(false)
    }
  }

  const handleSuspend = async () => {
    if (!instanceId) return
    try {
      const result = await put<void>(`/workflows/instances/${instanceId}/suspend`)
      if (result.success) {
        message.success('实例已挂起')
        refresh()
      } else {
        message.error(result.message)
      }
    } catch (error: any) {
      message.error(error.message || '挂起失败')
    }
  }

  const handleResume = async () => {
    if (!instanceId) return
    try {
      const result = await put<void>(`/workflows/instances/${instanceId}/resume`)
      if (result.success) {
        message.success('实例已恢复')
        refresh()
      } else {
        message.error(result.message)
      }
    } catch (error: any) {
      message.error(error.message || '恢复失败')
    }
  }

  const handleTerminate = async () => {
    if (!instanceId) return
    try {
      const result = await put<void>(`/workflows/instances/${instanceId}/terminate`)
      if (result.success) {
        message.success('实例已终止')
        refresh()
      } else {
        message.error(result.message)
      }
    } catch (error: any) {
      message.error(error.message || '终止失败')
    }
  }

  const timelineItems = useMemo<TimelineItemProps[]>(() => {
    return nodes.map((node) => {
      const cfg = nodeStatusIcon[node.status] || nodeStatusIcon.pending
      const isCurrent = node.status === 'running'
      return {
        dot: (
          <span
            className={isCurrent ? 'wf-node-running' : undefined}
            style={{ color: cfg.color, fontSize: 16, display: 'inline-block' }}
          >
            {cfg.icon}
          </span>
        ),
        children: (
          <div>
            <Space size={6} align="center">
              <strong>{node.nodeName}</strong>
              <Tag color={nodeTypeColor[node.nodeType] || 'default'}>{node.nodeType}</Tag>
              {node.statusDesc && <Tag color={cfg.color}>{node.statusDesc}</Tag>}
              {isCurrent && <Tag color="processing">处理中</Tag>}
            </Space>
            <div style={{ fontSize: 12, color: '#888', marginTop: 4, lineHeight: 1.8 }}>
              {node.assigneeName && <div>处理人: {node.assigneeName}</div>}
              <div>开始: {formatTime(node.startedAt)}</div>
              <div>完成: {formatTime(node.completedAt)}</div>
              <div>
                耗时:{' '}
                {formatDuration(
                  node.startedAt && node.completedAt ? node.completedAt - node.startedAt : undefined,
                )}
              </div>
              {node.remark && <div>备注: {node.remark}</div>}
              {node.output && <div>输出: {node.output}</div>}
            </div>
          </div>
        ),
      }
    })
  }, [nodes])

  if (loading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '60vh' }}>
        <Spin size="large" />
      </div>
    )
  }

  const statusItem = instance ? statusConfig[instance.status] : null

  return (
    <>
      <style>{`
        @keyframes wf-pulse {
          0% { opacity: 1; transform: scale(1); }
          50% { opacity: 0.5; transform: scale(1.25); }
          100% { opacity: 1; transform: scale(1); }
        }
        .wf-node-running { animation: wf-pulse 1.4s ease-in-out infinite; display: inline-block; }
      `}</style>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/workflows')}>
            返回
          </Button>
          <h2 style={{ margin: 0 }}>{instance?.title || '工作流实例详情'}</h2>
          {statusItem && <Tag color={statusItem.color}>{statusItem.text}</Tag>}
          <Button
            type="link"
            icon={<BellOutlined />}
            onClick={() => navigate('/workflows?tab=notifications')}
          >
            我的通知
          </Button>
        </div>

        <div style={{ display: 'flex', gap: 16, alignItems: 'flex-start' }}>
          <div style={{ flex: '0 0 60%', background: '#fff', padding: 16, borderRadius: 8, border: '1px solid #f0f0f0' }}>
            <h3 style={{ marginTop: 0 }}>节点执行记录</h3>
            {timelineItems.length === 0 ? (
              <div style={{ textAlign: 'center', color: '#999', padding: '40px 0' }}>暂无节点记录</div>
            ) : (
              <Timeline items={timelineItems} />
            )}
          </div>

          <div style={{ flex: '0 0 calc(40% - 16px)', display: 'flex', flexDirection: 'column', gap: 16 }}>
            {(isRunning || isSuspended) && (
              <div style={{ background: '#fff', padding: 16, borderRadius: 8, border: '1px solid #f0f0f0' }}>
                <h3 style={{ marginTop: 0 }}>操作面板</h3>
                {isSuspended && (
                  <Space direction="vertical" style={{ width: '100%' }}>
                    <div>当前实例已挂起</div>
                    <Button type="primary" onClick={handleResume}>
                      恢复
                    </Button>
                  </Space>
                )}
                {isRunning && currentNode && (
                  <div>
                    <Space size={6} align="center" style={{ marginBottom: 12 }}>
                      <span>当前节点:</span>
                      <strong>{currentNode.nodeName}</strong>
                      <Tag color={nodeTypeColor[currentNode.nodeType] || 'default'}>
                        {currentNode.nodeType}
                      </Tag>
                    </Space>
                    {isApproval && (
                      <Space direction="vertical" style={{ width: '100%' }} size={12}>
                        <Input.TextArea
                          value={remark}
                          onChange={(e) => setRemark(e.target.value)}
                          placeholder="备注 (可选)"
                          autoSize={{ minRows: 2, maxRows: 4 }}
                        />
                        <Space>
                          <Button type="primary" loading={submitting} onClick={() => handleComplete(true)}>
                            通过
                          </Button>
                          <Button danger loading={submitting} onClick={() => handleComplete(false)}>
                            驳回
                          </Button>
                        </Space>
                      </Space>
                    )}
                    {isTask && (
                      <Space direction="vertical" style={{ width: '100%' }} size={12}>
                        <Input.TextArea
                          value={output}
                          onChange={(e) => setOutput(e.target.value)}
                          placeholder="任务输出"
                          autoSize={{ minRows: 2, maxRows: 4 }}
                        />
                        <Input.TextArea
                          value={remark}
                          onChange={(e) => setRemark(e.target.value)}
                          placeholder="备注 (可选)"
                          autoSize={{ minRows: 1, maxRows: 3 }}
                        />
                        <Button type="primary" loading={submitting} onClick={() => handleComplete()}>
                          完成任务
                        </Button>
                      </Space>
                    )}
                    {isRunning && (
                      <div style={{ marginTop: 16, borderTop: '1px dashed #e8e8e8', paddingTop: 12 }}>
                        <Space>
                          <Button onClick={handleSuspend}>挂起</Button>
                          <Popconfirm
                            title="确定终止该实例吗?"
                            okText="终止"
                            okButtonProps={{ danger: true }}
                            cancelText="取消"
                            onConfirm={handleTerminate}
                          >
                            <Button danger>终止</Button>
                          </Popconfirm>
                        </Space>
                      </div>
                    )}
                  </div>
                )}
              </div>
            )}

            <div style={{ background: '#fff', padding: 16, borderRadius: 8, border: '1px solid #f0f0f0' }}>
              <h3 style={{ marginTop: 0 }}>实例信息</h3>
              <Descriptions column={1} size="small" bordered>
                <Descriptions.Item label="实例ID">{instance?.instanceId || '-'}</Descriptions.Item>
                <Descriptions.Item label="工作流名称">
                  {instance?.title || '-'} (v{instance?.workflowVersion ?? '-'})
                </Descriptions.Item>
                <Descriptions.Item label="版本">v{instance?.workflowVersion ?? '-'}</Descriptions.Item>
                <Descriptions.Item label="发起人">
                  {instance?.initiatorName || instance?.initiatorId || '-'}
                </Descriptions.Item>
                <Descriptions.Item label="当前节点">
                  {instance?.currentNodeName || '-'}
                </Descriptions.Item>
                <Descriptions.Item label="开始时间">
                  {formatTime(instance?.startedAt)}
                </Descriptions.Item>
                <Descriptions.Item label="完成时间">
                  {formatTime(instance?.completedAt)}
                </Descriptions.Item>
              </Descriptions>
            </div>
          </div>
        </div>
      </div>
    </>
  )
}