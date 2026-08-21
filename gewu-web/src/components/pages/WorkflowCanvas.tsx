'use client';
import { useState, useRef, useCallback, useEffect, memo } from 'react';
import {
  ZoomIn, ZoomOut, Maximize2, Play, Save, Undo2, Redo2, Trash2, X,
  Hand, Clock, Globe, Zap, GitBranch, Split, Repeat, Filter,
  Bot, Search, Code, ArrowRightLeft, Braces, Variable,
  Database, Mail, Bell, Timer, Hash, Workflow, CornerDownRight,
  Copy, Scissors, ChevronRight, Plus, Minus, ArrowDown, Send,
} from 'lucide-react';
import { useToast } from '@/components/ui/Toast';
import {
  nodeTypes, categoryConfig, generateId, getNodeConfig, getDefaultConfig,
  type WorkflowNode, type WorkflowConnection, type NodeTypeConfig, type NodeCategory,
} from './workflowTypes';

// --- Icon Map ---
const iconMap: Record<string, React.ComponentType<{ className?: string }>> = {
  Hand, Clock, Globe, Zap, GitBranch, Split, Repeat, Filter,
  Bot, Search, Code, ArrowRightLeft, Braces, Variable,
  Database, Mail, Bell, Timer, Hash, Workflow, CornerDownRight,
};

// --- Constants ---
const NODE_WIDTH = 160;
const NODE_HEADER_HEIGHT = 36;
const PORT_SIZE = 10;
const PORT_Y_OFFSET = NODE_HEADER_HEIGHT / 2;

// --- Connection Line ---
const ConnectionLine = memo(function ConnectionLine({
  from, to, nodes, label, onDelete,
}: {
  from: string; to: string; nodes: WorkflowNode[];
  label?: string; onDelete: (id: string) => void;
}) {
  const fromNode = nodes.find(n => n.id === from);
  const toNode = nodes.find(n => n.id === to);
  if (!fromNode || !toNode) return null;

  const startX = fromNode.x + NODE_WIDTH;
  const startY = fromNode.y + PORT_Y_OFFSET;
  const endX = toNode.x;
  const endY = toNode.y + PORT_Y_OFFSET;
  const dist = Math.abs(endX - startX);
  const cpOffset = Math.max(60, dist * 0.4);
  const midY = (startY + endY) / 2;

  const path = `M ${startX} ${startY} C ${startX + cpOffset} ${startY}, ${endX - cpOffset} ${endY}, ${endX} ${endY}`;

  return (
    <g>
      <path d={path} fill="none" stroke="rgba(0,184,148,0.35)" strokeWidth="2" />
      <path d={path} fill="none" stroke="transparent" strokeWidth="14" style={{ cursor: 'pointer' }} onClick={() => onDelete(`${from}-${to}`)} />
      <circle cx={endX} cy={endY} r="4" fill="rgba(0,184,148,0.6)" />
      <polygon points={`${endX - 7},${endY - 4} ${endX - 7},${endY + 4} ${endX - 1},${endY}`} fill="rgba(0,184,148,0.6)" />
      {label && (
        <foreignObject x={startX + (endX - startX) / 2 - 24} y={midY - 10} width="48" height="20">
          <div className="flex items-center justify-center">
            <span className="text-[9px] px-1.5 py-0.5 rounded-full bg-[#0e1c1b] border border-tech-500/20 text-tech-400 whitespace-nowrap">
              {label}
            </span>
          </div>
        </foreignObject>
      )}
    </g>
  );
});

// --- Node Card ---
interface NodeCardProps {
  node: WorkflowNode;
  config: NodeTypeConfig;
  isSelected: boolean;
  connecting: { nodeId: string; portId: string } | null;
  onSelect: (id: string) => void;
  onDragStart: (id: string, e: React.MouseEvent) => void;
  onPortDragStart: (nodeId: string, portId: string, e: React.MouseEvent) => void;
  onPortDrop: (nodeId: string, portId: string) => void;
}

function NodeCardInner({ node, config, isSelected, connecting, onSelect, onDragStart, onPortDragStart, onPortDrop }: NodeCardProps) {
  const IconComp = iconMap[config.icon];
  const nodeHeight = NODE_HEADER_HEIGHT + Math.max(config.inputs.length, config.outputs.length) * 24 + 8;
  const catCfg = categoryConfig[config.category];
  const isConnectingTarget = connecting && connecting.nodeId !== node.id && config.inputs.length > 0;

  return (
    <div
      className={`absolute rounded-xl border cursor-pointer select-none transition-shadow ${config.bgColor} ${isSelected ? 'ring-2 ring-tech-400 shadow-lg shadow-tech-500/20' : 'hover:shadow-md hover:border-tech-500/20'}`}
      style={{ left: node.x, top: node.y, width: NODE_WIDTH, minHeight: nodeHeight, borderColor: isSelected ? 'rgba(0,184,148,0.5)' : 'rgba(0,184,148,0.08)' }}
      onClick={() => onSelect(node.id)}
      onMouseDown={e => { if (e.button === 0) onDragStart(node.id, e); }}
      data-node-card
    >
      {/* Header */}
      <div className="flex items-center gap-2 px-3 py-2.5 rounded-t-xl" data-drag-handle style={{ background: 'rgba(0,0,0,0.15)' }}>
        <div className={`w-6 h-6 rounded-md flex items-center justify-center ${config.bgColor} ${config.color} flex-shrink-0`}>
          {IconComp && <IconComp className="w-3.5 h-3.5" />}
        </div>
        <span className="text-[11px] font-medium text-ink-100 truncate flex-1">{node.label}</span>
        <span className={`text-[8px] px-1.5 py-0.5 rounded-full ${catCfg.bgColor} ${catCfg.color} flex-shrink-0`}>
          {catCfg.label}
        </span>
      </div>

      {/* Ports */}
      <div className="px-1 py-1.5">
        {/* Input Ports */}
        {config.inputs.map((port) => (
          <div key={port.id} className="flex items-center gap-2 py-0.5 relative">
            <div
              className={`w-2.5 h-2.5 rounded-full border-2 cursor-crosshair transition-all flex-shrink-0 ${isConnectingTarget ? 'border-tech-400 bg-tech-400/30 scale-150 animate-pulse' : 'border-tech-400 bg-[#0e1c1b] hover:bg-tech-400 hover:scale-125'}`}
              style={{ marginLeft: -4 }}
              onMouseDown={e => { e.stopPropagation(); onPortDragStart(node.id, port.id, e); }}
              onMouseUp={e => { e.stopPropagation(); if (connecting) onPortDrop(node.id, port.id); }}
              title={`输入: ${port.label}`}
            />
            <span className="text-[10px] text-ink-400 truncate">{port.label}</span>
          </div>
        ))}
        {/* Output Ports */}
        {config.outputs.map((port) => (
          <div key={port.id} className="flex items-center justify-end gap-2 py-0.5 relative">
            <span className="text-[10px] text-ink-400 truncate">{port.label}</span>
            <div
              className={`w-2.5 h-2.5 rounded-full border-2 cursor-crosshair transition-all flex-shrink-0 ${connecting?.nodeId === node.id && connecting?.portId === port.id ? 'border-tech-400 bg-tech-400 animate-pulse scale-125' : 'border-tech-400 bg-[#0e1c1b] hover:bg-tech-400 hover:scale-125'}`}
              style={{ marginRight: -4 }}
              onMouseDown={e => { e.stopPropagation(); onPortDragStart(node.id, port.id, e); }}
              title={`输出: ${port.label}`}
            />
          </div>
        ))}
      </div>
    </div>
  );
}

const NodeCard = memo(NodeCardInner);

// --- Context Menu ---
function ContextMenu({ x, y, items, onClose }: {
  x: number; y: number; items: { label: string; icon?: React.ReactNode; onClick: () => void; danger?: boolean }[];
  onClose: () => void;
}) {
  useEffect(() => {
    const handler = () => onClose();
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [onClose]);

  return (
    <div className="fixed z-[99999] rounded-lg border shadow-xl py-1 min-w-[140px] animate-fade-up"
      style={{ left: x, top: y, background: '#0e1c1b', borderColor: 'rgba(0,184,148,0.15)' }}>
      {items.map((item, i) => (
        <button key={i} onClick={() => { item.onClick(); onClose(); }}
          className={`w-full flex items-center gap-2 px-3 py-2 text-xs transition-colors ${item.danger ? 'text-cinnabar-400 hover:bg-cinnabar-500/10' : 'text-ink-200 hover:bg-tech-500/10'}`}>
          {item.icon}<span>{item.label}</span>
        </button>
      ))}
    </div>
  );
}

// --- Main Canvas Component ---

interface WorkflowCanvasProps {
  name: string;
  description: string;
  onBack: () => void;
  onPublish?: () => void;
}

export default function WorkflowCanvas({ name, description, onBack, onPublish }: WorkflowCanvasProps) {
  const [nodes, setNodes] = useState<WorkflowNode[]>([
    { id: 'start', type: 'manual-trigger', label: '手动触发', x: 60, y: 140, config: getDefaultConfig('manual-trigger') },
  ]);
  const [connections, setConnections] = useState<WorkflowConnection[]>([]);
  const [selectedNode, setSelectedNode] = useState<string | null>(null);
  const [connecting, setConnecting] = useState<{ nodeId: string; portId: string } | null>(null);
  const [mousePos, setMousePos] = useState({ x: 0, y: 0 });
  const [zoom, setZoom] = useState(1);
  const [pan, setPan] = useState({ x: 0, y: 0 });
  const [isPanning, setIsPanning] = useState(false);
  const [dragging, setDragging] = useState<{ id: string; offsetX: number; offsetY: number } | null>(null);
  const [contextMenu, setContextMenu] = useState<{ x: number; y: number; items: { label: string; icon?: React.ReactNode; onClick: () => void; danger?: boolean }[] } | null>(null);
  const [collapsedCategories, setCollapsedCategories] = useState<Set<NodeCategory>>(new Set());
  const canvasRef = useRef<HTMLDivElement>(null);
  const toast = useToast();

  // --- Drag node from sidebar ---
  const [draggedType, setDraggedType] = useState<NodeTypeConfig | null>(null);

  const handleCanvasDrop = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    if (!draggedType || !canvasRef.current) return;
    const rect = canvasRef.current.getBoundingClientRect();
    const x = (e.clientX - rect.left - pan.x) / zoom - NODE_WIDTH / 2;
    const y = (e.clientY - rect.top - pan.y) / zoom - 20;
    const newNode: WorkflowNode = {
      id: generateId(),
      type: draggedType.type,
      label: draggedType.configFields.find(f => f.key === 'name')?.defaultValue || draggedType.label,
      x: Math.max(0, x),
      y: Math.max(0, y),
      config: getDefaultConfig(draggedType.type),
    };
    setNodes(prev => [...prev, newNode]);
    setDraggedType(null);
    toast(`已添加 ${draggedType.label} 节点`, 'success');
  }, [draggedType, zoom, pan, toast]);

  // --- Node dragging ---
  const handleNodeDragStart = useCallback((id: string, e: React.MouseEvent) => {
    const node = nodes.find(n => n.id === id);
    if (!node) return;
    setDragging({ id, offsetX: e.clientX / zoom - node.x, offsetY: e.clientY / zoom - node.y });
    setSelectedNode(id);
  }, [nodes, zoom]);

  // --- Port connection ---
  const handlePortDragStart = useCallback((nodeId: string, portId: string, e: React.MouseEvent) => {
    e.stopPropagation();
    const node = nodes.find(n => n.id === nodeId);
    if (!node) return;
    const cfg = getNodeConfig(node.type);
    // Only allow dragging from output ports
    const isOutput = cfg.outputs.some(p => p.id === portId);
    if (isOutput) {
      setConnecting({ nodeId, portId });
    }
  }, [nodes]);

  const handlePortDrop = useCallback((targetNodeId: string, targetPortId: string) => {
    if (!connecting) return;
    if (connecting.nodeId === targetNodeId) return;

    const fromNode = nodes.find(n => n.id === connecting.nodeId);
    const toNode = nodes.find(n => n.id === targetNodeId);
    if (!fromNode || !toNode) return;

    const fromCfg = getNodeConfig(fromNode.type);
    const toCfg = getNodeConfig(toNode.type);
    const outPort = fromCfg.outputs.find(p => p.id === connecting.portId);
    const inPort = toCfg.inputs.find(p => p.id === targetPortId);

    if (!outPort || !inPort) return;

    // Check if this input already has a connection
    const inputOccupied = connections.some(c => c.to === targetNodeId && c.toPort === targetPortId);
    if (inputOccupied) {
      toast('该输入端口已有连接', 'info');
      setConnecting(null);
      return;
    }

    const exists = connections.some(c => c.from === connecting.nodeId && c.to === targetNodeId && c.fromPort === connecting.portId);
    if (!exists) {
      setConnections(prev => [...prev, {
        id: `${connecting.nodeId}-${targetNodeId}-${connecting.portId}`,
        from: connecting.nodeId, fromPort: outPort.id,
        to: targetNodeId, toPort: inPort.id,
        label: outPort.label,
      }]);
      toast('已连接节点', 'success');
    }
    setConnecting(null);
  }, [connecting, nodes, connections, toast]);

  const handleCanvasMouseMove = useCallback((e: React.MouseEvent) => {
    setMousePos({ x: e.clientX, y: e.clientY });
    if (dragging) {
      setNodes(prev => prev.map(n => n.id === dragging.id ? {
        ...n,
        x: Math.max(0, e.clientX / zoom - dragging.offsetX),
        y: Math.max(0, e.clientY / zoom - dragging.offsetY),
      } : n));
    }
    if (isPanning) {
      setPan(prev => ({ x: prev.x + e.movementX, y: prev.y + e.movementY }));
    }
  }, [dragging, isPanning, zoom]);

  const handleCanvasMouseUp = useCallback(() => {
    setDragging(null);
    setIsPanning(false);
    // Clear connecting if still active (dropped on empty area)
    setConnecting(null);
  }, []);

  const handleCanvasMouseDown = useCallback((e: React.MouseEvent) => {
    if (e.button === 1 || (e.button === 0 && e.target === canvasRef.current)) {
      setIsPanning(true);
      setSelectedNode(null);
    }
  }, []);

  // --- Right click context menu ---
  const handleNodeRightClick = useCallback((nodeId: string, e: React.MouseEvent) => {
    e.preventDefault();
    setSelectedNode(nodeId);
    setContextMenu({
      x: e.clientX, y: e.clientY,
      items: [
        { label: '复制节点', icon: <Copy className="w-3.5 h-3" />, onClick: () => duplicateNode(nodeId) },
        { label: '断开连接', icon: <Scissors className="w-3.5 h-3" />, onClick: () => disconnectNode(nodeId) },
        { label: '删除节点', icon: <Trash2 className="w-3.5 h-3" />, danger: true, onClick: () => deleteNode(nodeId) },
      ],
    });
  }, [nodes]);

  // --- Node operations ---
  const duplicateNode = useCallback((id: string) => {
    const node = nodes.find(n => n.id === id);
    if (!node) return;
    const newNode = { ...node, id: generateId(), x: node.x + 30, y: node.y + 30, config: { ...node.config } };
    setNodes(prev => [...prev, newNode]);
    toast('节点已复制', 'success');
  }, [nodes, toast]);

  const disconnectNode = useCallback((id: string) => {
    setConnections(prev => prev.filter(c => c.from !== id && c.to !== id));
    toast('连接已断开', 'success');
  }, [toast]);

  const deleteNode = useCallback((id: string) => {
    setNodes(prev => prev.filter(n => n.id !== id));
    setConnections(prev => prev.filter(c => c.from !== id && c.to !== id));
    setSelectedNode(null);
    toast('节点已删除', 'success');
  }, [toast]);

  const deleteConnection = useCallback((id: string) => {
    setConnections(prev => prev.filter(c => c.id !== id));
  }, []);

  // --- Connect on node click when in connecting mode ---
  const handleNodeClick = useCallback((id: string) => {
    if (connecting && connecting.nodeId !== id) {
      const fromNode = nodes.find(n => n.id === connecting.nodeId);
      const toNode = nodes.find(n => n.id === id);
      if (fromNode && toNode) {
        const fromCfg = getNodeConfig(fromNode.type);
        const toCfg = getNodeConfig(toNode.type);
        const outPort = fromCfg.outputs.find(p => p.id === connecting.portId) || fromCfg.outputs[0];
        const inPort = toCfg.inputs[0];
        if (outPort && inPort) {
          const exists = connections.some(c => c.from === connecting.nodeId && c.to === id);
          if (!exists) {
            setConnections(prev => [...prev, {
              id: `${connecting.nodeId}-${id}`,
              from: connecting.nodeId, fromPort: outPort.id,
              to: id, toPort: inPort.id,
              label: outPort.label,
            }]);
            toast('已连接节点', 'success');
          }
        }
      }
      setConnecting(null);
    }
  }, [connecting, nodes, connections, toast]);

  // --- Zoom ---
  const zoomIn = () => setZoom(prev => Math.min(2, prev + 0.1));
  const zoomOut = () => setZoom(prev => Math.max(0.3, prev - 0.1));
  const zoomReset = () => { setZoom(1); setPan({ x: 0, y: 0 }); }

  // Keyboard shortcuts
  useEffect(() => {
    const handleKey = (e: KeyboardEvent) => {
      if ((e.key === 'Delete' || e.key === 'Backspace') && selectedNode) {
        const el = document.activeElement;
        if (el?.tagName !== 'INPUT' && el?.tagName !== 'TEXTAREA') {
          deleteNode(selectedNode);
        }
      }
      if (e.key === 'Escape') {
        setSelectedNode(null);
        setConnecting(null);
        setContextMenu(null);
      }
    };
    window.addEventListener('keydown', handleKey);
    return () => window.removeEventListener('keydown', handleKey);
  }, [selectedNode, deleteNode]);

  const selectedNodeData = nodes.find(n => n.id === selectedNode);
  const selectedConfig = selectedNodeData ? getNodeConfig(selectedNodeData.type) : null;

  const toggleCategory = (cat: NodeCategory) => {
    setCollapsedCategories(prev => {
      const next = new Set(prev);
      if (next.has(cat)) next.delete(cat); else next.add(cat);
      return next;
    });
  };

  // Group nodes by category
  const nodesByCategory = nodeTypes.reduce((acc, nt) => {
    if (!acc[nt.category]) acc[nt.category] = [];
    acc[nt.category].push(nt);
    return acc;
  }, {} as Record<NodeCategory, NodeTypeConfig[]>);

  return (
    <div className="flex h-[calc(100vh-10rem)] -mx-8 -mb-8 mt-[-2rem]">
      {/* ===== Left Sidebar - Node Palette ===== */}
      <aside className="w-60 border-r flex flex-col flex-shrink-0 sidebar-bg" style={{ borderColor: 'rgba(0,184,148,0.08)' }}>
        <div className="p-3 border-b" style={{ borderColor: 'rgba(0,184,148,0.08)' }}>
          <h3 className="text-sm font-semibold text-ink-100">节点库</h3>
          <p className="text-[10px] text-ink-500 mt-0.5">拖拽节点到画布 · 点击端口连线</p>
        </div>
        <div className="flex-1 overflow-y-auto scrollbar-thin p-2">
          {(Object.keys(nodesByCategory) as NodeCategory[]).map(cat => {
            const catCfg = categoryConfig[cat];
            const isCollapsed = collapsedCategories.has(cat);
            return (
              <div key={cat} className="mb-1">
                <button onClick={() => toggleCategory(cat)}
                  className="w-full flex items-center gap-1.5 px-2 py-1.5 text-[11px] font-medium rounded-md hover:bg-ink-800/50 transition-colors">
                  <ChevronRight className={`w-3 h-3 text-ink-500 transition-transform ${isCollapsed ? '' : 'rotate-90'}`} />
                  <span className={catCfg.color}>{catCfg.label}</span>
                  <span className="text-[9px] text-ink-600 ml-auto">{nodesByCategory[cat].length}</span>
                </button>
                {!isCollapsed && (
                  <div className="ml-2 mt-0.5 space-y-0.5">
                    {nodesByCategory[cat].map(nt => {
                      const IconComp = iconMap[nt.icon];
                      return (
                        <div key={nt.type} draggable
                          onDragStart={() => setDraggedType(nt)}
                          onDragEnd={() => setDraggedType(null)}
                          className={`flex items-center gap-2 px-2.5 py-2 rounded-lg border cursor-grab active:cursor-grabbing transition-all hover:shadow-sm ${nt.bgColor}`}
                          style={{ borderColor: 'rgba(0,184,148,0.06)' }}>
                          <div className={`w-5 h-5 rounded flex items-center justify-center ${nt.bgColor} ${nt.color} flex-shrink-0`}>
                            {IconComp && <IconComp className="w-3 h-3" />}
                          </div>
                          <div className="flex-1 min-w-0">
                            <p className="text-[11px] font-medium text-ink-200 truncate">{nt.label}</p>
                          </div>
                        </div>
                      );
                    })}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      </aside>

      {/* ===== Main Canvas Area ===== */}
      <div className="flex-1 flex flex-col min-w-0">
        {/* Toolbar */}
        <div className="flex items-center justify-between px-4 py-2 border-b flex-shrink-0" style={{ borderColor: 'rgba(0,184,148,0.08)', background: 'rgba(8,18,17,0.8)' }}>
          <div className="flex items-center gap-3">
            <button onClick={onBack} className="flex items-center gap-1.5 px-2.5 py-1.5 text-xs text-ink-300 hover:text-ink-100 rounded-md hover:bg-ink-800/50 transition-colors">
              <X className="w-3.5 h-3.5" /> 返回
            </button>
            <div className="w-px h-4 bg-ink-700" />
            <div>
              <h2 className="text-sm font-medium text-ink-100">{name}</h2>
              {description && <p className="text-[10px] text-ink-500">{description}</p>}
            </div>
          </div>
          <div className="flex items-center gap-0.5">
            <button onClick={() => toast('撤销', 'info')} className="p-1.5 text-ink-400 hover:text-ink-200 rounded transition-colors" title="撤销 Ctrl+Z"><Undo2 className="w-4 h-4" /></button>
            <button onClick={() => toast('重做', 'info')} className="p-1.5 text-ink-400 hover:text-ink-200 rounded transition-colors" title="重做 Ctrl+Y"><Redo2 className="w-4 h-4" /></button>
            <div className="w-px h-4 bg-ink-700 mx-1" />
            <button onClick={zoomOut} className="p-1.5 text-ink-400 hover:text-ink-200 rounded transition-colors" title="缩小"><Minus className="w-4 h-4" /></button>
            <span className="text-[11px] text-ink-400 w-10 text-center cursor-pointer" onClick={zoomReset} title="重置缩放">{Math.round(zoom * 100)}%</span>
            <button onClick={zoomIn} className="p-1.5 text-ink-400 hover:text-ink-200 rounded transition-colors" title="放大"><Plus className="w-4 h-4" /></button>
            <button onClick={zoomReset} className="p-1.5 text-ink-400 hover:text-ink-200 rounded transition-colors" title="适应画布"><Maximize2 className="w-4 h-4" /></button>
            <div className="w-px h-4 bg-ink-700 mx-1" />
            <button onClick={() => toast('工作流已保存', 'success')} className="flex items-center gap-1.5 px-3 py-1.5 text-xs text-ink-200 hover:text-ink-100 border border-tech-500/10 rounded-md hover:border-tech-500/25 transition-all">
              <Save className="w-3.5 h-3.5" /> 保存
            </button>
            {onPublish && (
              <button onClick={onPublish} className="flex items-center gap-1.5 px-3 py-1.5 text-xs text-tech-400 border border-tech-500/20 rounded-md hover:bg-tech-500/10 transition-all">
                <Send className="w-3.5 h-3.5" /> 发布
              </button>
            )}
            <button onClick={() => toast('请先发布工作流', 'info')} className="flex items-center gap-1.5 px-3 py-1.5 text-xs btn-primary text-white rounded-md">
              <Play className="w-3.5 h-3.5" /> 运行
            </button>
          </div>
        </div>

        {/* Canvas */}
        <div ref={canvasRef}
          className="flex-1 relative overflow-hidden"
          style={{ background: 'radial-gradient(circle at 1px 1px, rgba(0,184,148,0.06) 1px, transparent 0)', backgroundSize: `${20 * zoom}px ${20 * zoom}px`, backgroundPosition: `${pan.x}px ${pan.y}px` }}
          onDragOver={e => e.preventDefault()}
          onDrop={handleCanvasDrop}
          onMouseMove={handleCanvasMouseMove}
          onMouseUp={handleCanvasMouseUp}
          onMouseDown={handleCanvasMouseDown}
          onContextMenu={e => e.preventDefault()}
        >
          <div className="absolute inset-0" style={{ transform: `translate(${pan.x}px, ${pan.y}px) scale(${zoom})`, transformOrigin: '0 0' }}>
            {/* SVG connections */}
            <svg className="absolute inset-0 pointer-events-none" style={{ overflow: 'visible', width: '9999px', height: '9999px' }}>
              {connections.map(conn => (
                <ConnectionLine key={conn.id} from={conn.from} to={conn.to} nodes={nodes} label={conn.label} onDelete={deleteConnection} />
              ))}
              {/* Drawing line while connecting */}
              {connecting && (() => {
                const fromNode = nodes.find(n => n.id === connecting.nodeId);
                if (!fromNode) return null;
                const startX = fromNode.x + NODE_WIDTH;
                const startY = fromNode.y + PORT_Y_OFFSET;
                const endX = (mousePos.x - (canvasRef.current?.getBoundingClientRect().left || 0) - pan.x) / zoom;
                const endY = (mousePos.y - (canvasRef.current?.getBoundingClientRect().top || 0) - pan.y) / zoom;
                const dist = Math.abs(endX - startX);
                const cpOffset = Math.max(60, dist * 0.4);
                return (
                  <path d={`M ${startX} ${startY} C ${startX + cpOffset} ${startY}, ${endX - cpOffset} ${endY}, ${endX} ${endY}`}
                    fill="none" stroke="rgba(0,184,148,0.6)" strokeWidth="2" strokeDasharray="6 4" />
                );
              })()}
            </svg>

            {/* Nodes */}
            {nodes.map(node => {
              const ntConfig = getNodeConfig(node.type);
              return (
                <div key={node.id} onContextMenu={e => handleNodeRightClick(node.id, e)}>
                  <NodeCard node={node} config={ntConfig}
                    isSelected={selectedNode === node.id}
                    connecting={connecting}
                    onSelect={id => { handleNodeClick(id); setSelectedNode(id); }}
                    onDragStart={handleNodeDragStart}
                    onPortDragStart={handlePortDragStart}
                    onPortDrop={handlePortDrop}
                  />
                </div>
              );
            })}
          </div>

          {/* Connecting mode indicator */}
          {connecting && (
            <div className="absolute top-3 left-1/2 -translate-x-1/2 px-4 py-2 rounded-full text-xs text-tech-400 bg-tech-500/10 border border-tech-500/20 backdrop-blur-sm z-50">
              点击目标节点完成连接 · 按 Esc 取消
            </div>
          )}

          {/* Canvas info */}
          <div className="absolute bottom-3 left-3 text-[10px] text-ink-600 bg-ink-900/40 px-2 py-1 rounded backdrop-blur-sm">
            {nodes.length} 节点 · {connections.length} 连接
          </div>
        </div>
      </div>

      {/* ===== Right Sidebar - Node Config ===== */}
      {selectedNodeData && selectedConfig && (
        <aside className="w-72 border-l flex flex-col flex-shrink-0 sidebar-bg" style={{ borderColor: 'rgba(0,184,148,0.08)' }}>
          <div className="p-3 border-b flex items-center justify-between" style={{ borderColor: 'rgba(0,184,148,0.08)' }}>
            <div className="flex items-center gap-2">
              <div className={`w-6 h-6 rounded-md flex items-center justify-center ${selectedConfig.bgColor} ${selectedConfig.color}`}>
                {(() => { const I = iconMap[selectedConfig.icon]; return I ? <I className="w-3.5 h-3.5" /> : null; })()}
              </div>
              <div>
                <h3 className="text-xs font-semibold text-ink-100">节点配置</h3>
                <p className="text-[9px] text-ink-500">{selectedConfig.label}</p>
              </div>
            </div>
            <button onClick={() => setSelectedNode(null)} className="text-ink-500 hover:text-ink-300 p-1"><X className="w-3.5 h-3.5" /></button>
          </div>
          <div className="flex-1 overflow-y-auto scrollbar-thin p-3 space-y-3">
            {selectedConfig.configFields.map(field => (
              <div key={field.key}>
                <label className="block text-[11px] text-ink-400 mb-1">{field.label}</label>
                {field.type === 'select' ? (
                  <select value={selectedNodeData.config[field.key] || field.defaultValue || ''}
                    onChange={e => setNodes(prev => prev.map(n => n.id === selectedNodeData.id ? { ...n, config: { ...n.config, [field.key]: e.target.value } } : n))}
                    className="w-full px-2.5 py-1.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-xs text-ink-100 outline-none focus:border-tech-500/30 color-scheme-dark">
                    {field.options?.map(opt => <option key={opt.value} value={opt.value}>{opt.label}</option>)}
                  </select>
                ) : field.type === 'textarea' ? (
                  <textarea rows={3} placeholder={field.placeholder}
                    value={selectedNodeData.config[field.key] || ''}
                    onChange={e => setNodes(prev => prev.map(n => n.id === selectedNodeData.id ? { ...n, config: { ...n.config, [field.key]: e.target.value } } : n))}
                    className="w-full px-2.5 py-1.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-xs text-ink-100 placeholder-ink-500 outline-none focus:border-tech-500/30 resize-none font-mono" />
                ) : field.type === 'number' ? (
                  <input type="number" placeholder={field.placeholder}
                    value={selectedNodeData.config[field.key] || ''}
                    onChange={e => setNodes(prev => prev.map(n => n.id === selectedNodeData.id ? { ...n, config: { ...n.config, [field.key]: e.target.value } } : n))}
                    className="w-full px-2.5 py-1.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-xs text-ink-100 placeholder-ink-500 outline-none focus:border-tech-500/30" />
                ) : (
                  <input type="text" placeholder={field.placeholder}
                    value={selectedNodeData.config[field.key] || ''}
                    onChange={e => setNodes(prev => prev.map(n => n.id === selectedNodeData.id ? { ...n, config: { ...n.config, [field.key]: e.target.value } } : n))}
                    className="w-full px-2.5 py-1.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-xs text-ink-100 placeholder-ink-500 outline-none focus:border-tech-500/30" />
                )}
                {field.hint && <p className="text-[9px] text-ink-600 mt-0.5">{field.hint}</p>}
              </div>
            ))}

            {/* Connect downstream button - show if node has outputs */}
            {selectedConfig.outputs.length > 0 && (
              <div className="pt-3 border-t" style={{ borderColor: 'rgba(0,184,148,0.08)' }}>
                <button
                  onClick={() => {
                    setConnecting({ nodeId: selectedNodeData.id, portId: selectedConfig.outputs[0].id });
                    toast('请点击目标节点的输入端口完成连接', 'info');
                  }}
                  className="w-full flex items-center gap-2 px-2.5 py-2 text-[11px] text-tech-400 border border-tech-500/20 rounded-lg hover:bg-tech-500/10 transition-all">
                  <ArrowDown className="w-3 h-3" /> 连接下游节点
                </button>
              </div>
            )}

            {/* Branch info for condition/switch */}
            {(selectedConfig.type === 'condition' || selectedConfig.type === 'switch') && (
              <div className="pt-3 border-t" style={{ borderColor: 'rgba(0,184,148,0.08)' }}>
                <p className="text-[10px] text-ink-500 mb-2">分支出口</p>
                <div className="space-y-1">
                  {selectedConfig.outputs.map(port => {
                    const connCount = connections.filter(c => c.from === selectedNodeData.id && c.fromPort === port.id).length;
                    return (
                      <div key={port.id} className="flex items-center justify-between px-2 py-1.5 rounded-md bg-ink-800/30">
                        <span className="text-[10px] text-ink-300">{port.label}</span>
                        <span className="text-[9px] text-ink-500">{connCount} 连接</span>
                      </div>
                    );
                  })}
                </div>
              </div>
            )}

            {/* Quick actions */}
            <div className="pt-3 border-t" style={{ borderColor: 'rgba(0,184,148,0.08)' }}>
              <p className="text-[10px] text-ink-500 mb-2">快捷操作</p>
              <div className="space-y-1.5">
                <button onClick={() => duplicateNode(selectedNodeData.id)}
                  className="w-full flex items-center gap-2 px-2.5 py-1.5 text-[11px] text-ink-200 border border-tech-500/10 rounded-lg hover:border-tech-500/25 transition-all">
                  <Copy className="w-3 h-3" /> 复制节点
                </button>
                <button onClick={() => disconnectNode(selectedNodeData.id)}
                  className="w-full flex items-center gap-2 px-2.5 py-1.5 text-[11px] text-ink-200 border border-tech-500/10 rounded-lg hover:border-tech-500/25 transition-all">
                  <Scissors className="w-3 h-3" /> 断开所有连接
                </button>
                <button onClick={() => deleteNode(selectedNodeData.id)}
                  className="w-full flex items-center gap-2 px-2.5 py-1.5 text-[11px] text-cinnabar-400 border border-cinnabar-500/10 rounded-lg hover:border-cinnabar-500/25 transition-all">
                  <Trash2 className="w-3 h-3" /> 删除节点
                </button>
              </div>
            </div>
          </div>
        </aside>
      )}

      {/* Context Menu */}
      {contextMenu && <ContextMenu x={contextMenu.x} y={contextMenu.y} items={contextMenu.items} onClose={() => setContextMenu(null)} />}
    </div>
  );
}
