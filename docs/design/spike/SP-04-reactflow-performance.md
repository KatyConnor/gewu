# SP-04: React Flow 性能验证报告

## 文档信息

| 项目 | 内容 |
|------|------|
| Spike 编号 | SP-04 |
| 验证目标 | React Flow 在 100+ 节点工作流设计器中的渲染性能 |
| 负责人 | 前端开发 |
| 计划工期 | 2 天 |
| 关联设计文档 | 31-workflow-ui-design.md |

---

## 1. 验证目标

| 编号 | 验证项 | 通过标准 | 失败回退 |
|------|--------|---------|---------|
| SP-04-01 | 100 节点渲染 | 首次渲染 < 2s | Canvas 虚拟化方案 |
| SP-04-02 | 拖拽交互帧率 | > 30 fps | 降低渲染精度 |
| SP-04-03 | 连线交互响应 | < 100ms | 简化连线动画 |
| SP-04-04 | 缩放/平移流畅度 | > 30 fps | 使用 WebGL 加速 |
| SP-04-05 | 内存占用 | < 500MB (100节点) | 节点虚拟化 |

---

## 2. 测试环境

| 资源 | 规格 |
|------|------|
| 浏览器 | Chrome 120+ / Firefox 120+ |
| CPU | Intel i5 / AMD Ryzen 5 |
| 内存 | 8GB+ |
| 网络 | 本地（无网络延迟） |

---

## 3. 测试代码

### 3.1 测试项目初始化

```bash
# 创建测试项目
cd /tmp
npx create-react-app react-flow-spike --template typescript
cd react-flow-spike

# 安装依赖
npm install reactflow @reactflow/core @reactflow/minimap @reactflow/background @reactflow/controls
npm install --save-dev @types/reactflow

# 启动开发服务器
npm start
```

### 3.2 100 节点渲染测试

```tsx
// src/test/ManyNodesTest.tsx
import React, { useCallback, useMemo } from 'react';
import ReactFlow, {
  Node,
  Edge,
  useNodesState,
  useEdgesState,
  Controls,
  MiniMap,
  Background,
} from 'reactflow';
import 'reactflow/dist/style.css';

// 生成 100 个节点
const generateNodes = (count: number): Node[] => {
  const nodes: Node[] = [];
  const cols = 10;
  for (let i = 0; i < count; i++) {
    nodes.push({
      id: `node-${i}`,
      type: 'default',
      position: {
        x: (i % cols) * 200,
        y: Math.floor(i / cols) * 150,
      },
      data: {
        label: `Node ${i}`,
        status: ['pending', 'running', 'completed'][i % 3],
      },
    });
  }
  return nodes;
};

// 生成连线（每个节点连接到下一个）
const generateEdges = (count: number): Edge[] => {
  const edges: Edge[] = [];
  for (let i = 0; i < count - 1; i++) {
    edges.push({
      id: `edge-${i}`,
      source: `node-${i}`,
      target: `node-${i + 1}`,
      animated: i % 3 === 1,
    });
  }
  return edges;
};

export const ManyNodesTest: React.FC = () => {
  const initialNodes = useMemo(() => generateNodes(100), []);
  const initialEdges = useMemo(() => generateEdges(100), []);

  const [nodes, setNodes, onNodesChange] = useNodesState(initialNodes);
  const [edges, setEdges, onEdgesChange] = useEdgesState(initialEdges);

  // 测量渲染时间
  const startTime = performance.now();
  React.useEffect(() => {
    const endTime = performance.now();
    console.log(`Render time: ${(endTime - startTime).toFixed(2)}ms`);
  }, []);

  return (
    <div style={{ width: '100vw', height: '100vh' }}>
      <ReactFlow
        nodes={nodes}
        edges={edges}
        onNodesChange={onNodesChange}
        onEdgesChange={onEdgesChange}
        fitView
      >
        <Controls />
        <MiniMap />
        <Background />
      </ReactFlow>
    </div>
  );
};
```

### 3.3 拖拽性能测试

```tsx
// src/test/DragPerformanceTest.tsx
import React, { useState, useEffect } from 'react';
import ReactFlow, { Node, useNodesState } from 'reactflow';

export const DragPerformanceTest: React.FC = () => {
  const [fps, setFps] = useState(0);
  const [nodes, setNodes, onNodesChange] = useNodesState(generateNodes(100));

  // FPS 计数器
  useEffect(() => {
    let frameCount = 0;
    let lastTime = performance.now();

    const measureFps = () => {
      frameCount++;
      const currentTime = performance.now();
      if (currentTime - lastTime >= 1000) {
        setFps(frameCount);
        frameCount = 0;
        lastTime = currentTime;
      }
      requestAnimationFrame(measureFps);
    };

    const rafId = requestAnimationFrame(measureFps);
    return () => cancelAnimationFrame(rafId);
  }, []);

  return (
    <div style={{ width: '100vw', height: '100vh' }}>
      <div style={{ position: 'absolute', top: 10, left: 10, zIndex: 1000 }}>
        FPS: {fps} {fps >= 30 ? '✅' : '❌'}
      </div>
      <ReactFlow nodes={nodes} onNodesChange={onNodesChange} fitView />
    </div>
  );
};
```

### 3.4 内存占用测试

```tsx
// src/test/MemoryTest.tsx
import React, { useState } from 'react';

export const MemoryTest: React.FC = () => {
  const [nodeCount, setNodeCount] = useState(100);

  const getMemoryUsage = () => {
    if (performance.memory) {
      return {
        used: (performance.memory.usedJSHeapSize / 1048576).toFixed(2),
        total: (performance.memory.totalJSHeapSize / 1048576).toFixed(2),
      };
    }
    return { used: 'N/A', total: 'N/A' };
  };

  const [memory, setMemory] = useState(getMemoryUsage());

  return (
    <div>
      <h2>Memory Test</h2>
      <p>Node Count: {nodeCount}</p>
      <p>Used Memory: {memory.used} MB</p>
      <p>Total Memory: {memory.total} MB</p>
      <button onClick={() => setNodeCount(nodeCount + 50)}>Add 50 Nodes</button>
      <button onClick={() => setMemory(getMemoryUsage())}>Refresh Memory</button>
      <div style={{ width: '100vw', height: '80vh' }}>
        <ReactFlow nodes={generateNodes(nodeCount)} fitView />
      </div>
    </div>
  );
};
```

---

## 4. 执行步骤

```bash
# Step 1: 启动测试项目
cd /tmp/react-flow-spike
npm start

# Step 2: 在浏览器中打开测试页面
# http://localhost:3000

# Step 3: 打开 Chrome DevTools (F12)
# - Performance 面板: 录制渲染过程，分析帧率
# - Memory 面板: 拍摄堆快照，分析内存占用
# - Console: 查看渲染时间日志

# Step 4: 执行测试用例
# 4.1 100 节点渲染测试
#   - 访问 ManyNodesTest 页面
#   - 记录 Console 中的渲染时间
#   - 通过标准: < 2000ms

# 4.2 拖拽性能测试
#   - 访问 DragPerformanceTest 页面
#   - 拖拽节点，观察左上角 FPS 显示
#   - 通过标准: FPS >= 30

# 4.3 内存占用测试
#   - 访问 MemoryTest 页面
#   - 点击 "Refresh Memory" 查看内存
#   - 通过标准: < 500MB (100 节点)

# 4.4 缩放/平移测试
#   - 在 100 节点页面上使用鼠标滚轮缩放
#   - 按住空格键 + 拖拽平移
#   - 观察帧率
#   - 通过标准: FPS >= 30
```

---

## 5. 测试结果记录

| 测试项 | 通过标准 | 实际结果 | 状态 |
|--------|---------|---------|------|
| 100 节点渲染时间 | < 2000ms | | ⬜ |
| 拖拽帧率 (100节点) | >= 30 fps | | ⬜ |
| 连线交互响应 | < 100ms | | ⬜ |
| 缩放/平移帧率 | >= 30 fps | | ⬜ |
| 内存占用 (100节点) | < 500MB | | ⬜ |
| 内存占用 (200节点) | < 800MB | | ⬜ |

---

## 6. 性能优化方案（如未通过）

### 6.1 节点虚拟化

```tsx
// 只渲染可视区域内的节点
const visibleNodes = useMemo(() => {
  const viewport = useViewport();
  return nodes.filter(node => {
    const x = node.position.x * viewport.zoom + viewport.x;
    const y = node.position.y * viewport.zoom + viewport.y;
    return x > -200 && x < window.innerWidth + 200
        && y > -200 && y < window.innerHeight + 200;
  });
}, [nodes, viewport]);
```

### 6.2 Canvas 渲染替代 SVG

```tsx
// 使用 react-flow 的 Canvas 渲染模式
<ReactFlow
  nodes={nodes}
  edges={edges}
  // 使用 Canvas 替代 SVG（实验性功能）
  // 需要自定义节点渲染
/>
```

### 6.3 简化连线

```tsx
// 使用简单直线替代贝塞尔曲线
<Edge type="straight" />
// 或禁用动画
<Edge animated={false} />
```

---

## 7. 风险与缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|---------|
| 100 节点渲染卡顿 | 中 | 高 | 节点虚拟化 + Canvas 渲染 |
| 拖拽帧率低 | 中 | 中 | 简化连线动画 |
| 内存泄漏 | 低 | 高 | 定期垃圾回收 + 节点回收 |
| 浏览器兼容性 | 低 | 中 | 降级方案（简化设计器） |

---

## 8. Go/No-Go 决策

| 决策 | 条件 |
|------|------|
| **Go** | 100 节点渲染 < 2s，拖拽 FPS >= 30 |
| **Conditional Go** | 渲染 2-5s，拖拽 FPS 20-30，可优化 |
| **No-Go** | 渲染 > 5s 或 FPS < 20，使用 Canvas 方案 |

---

**报告填写人**: _______________  **日期**: _______________
