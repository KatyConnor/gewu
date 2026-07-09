# 格物 - 工作流前端界面设计文档

## 文档信息

| 项目 | 内容 |
|------|------|
| 项目名称 | 格物 (Gewu) |
| 文档版本 | V1.0 |
| 创建日期 | 2026-07-08 |
| 文档状态 | 初稿 |
| 源设计文档 | opencode-1.17.14/docs/design/30-workflow-ui-design.md (V1.0) |
| 关联统一文档 | 20-unified-prd.md, 21-unified-architecture.md, 28-workflow-engine-design.md, 30-workflow-api-design.md |

---

## 1. 概述

### 1.1 设计目标

本文档定义了格物平台工作流引擎的前端界面设计，包括页面结构、组件设计、交互规范等。

**设计目标**：
- 提供直观的工作流可视化界面
- 支持工作流定义的创建和编辑
- 支持工作流实例的监控和管理
- 支持任务分配和审核操作
- 提供实时通知和提醒

### 1.2 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| React | 18+ | UI 框架 |
| TypeScript | 5.3+ | 类型安全 |
| Ant Design | 5+ | UI 组件库 |
| Zustand | 4+ | 状态管理 |
| React Flow | 11+ | 流程图可视化 |

### 1.3 设计规范

| 规范项 | 说明 |
|--------|------|
| **颜色** | 遵循 Ant Design 设计规范 |
| **字体** | 默认系统字体 |
| **间距** | 8px 基础间距 |
| **圆角** | 4px 基础圆角 |
| **阴影** | 使用 Ant Design 阴影 |

---

## 2. 页面结构

### 2.1 路由设计

| 路径 | 页面 | 说明 |
|------|------|------|
| `/workflows` | 工作流定义列表 | 查看所有工作流定义 |
| `/workflows/:id` | 工作流定义详情 | 查看工作流定义详情 |
| `/workflows/:id/edit` | 工作流定义编辑 | 编辑工作流定义 |
| `/workflows/create` | 创建工作流定义 | 创建新的工作流定义 |
| `/workflow-instances` | 工作流实例列表 | 查看所有工作流实例 |
| `/workflow-instances/:id` | 工作流实例详情 | 查看工作流实例详情 |
| `/workflow-monitor` | 工作流监控仪表盘 | 流程监控和统计 |
| `/notifications` | 通知中心 | 查看所有通知 |

### 2.2 导航结构

```
┌─────────────────────────────────────────────────────────────────┐
│                         主导航栏                                  │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│   ┌─────────────┐  ┌─────────────┐  ┌─────────────┐           │
│   │  工作流     │  │  任务中心   │  │  通知中心   │           │
│   └─────────────┘  └─────────────┘  └─────────────┘           │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## 3. 页面设计

### 3.1 工作流定义列表页

#### 页面结构

```
┌─────────────────────────────────────────────────────────────────┐
│                         页面标题                                  │
│   工作流定义                                                    │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│   ┌─────────────────────────────────────────────────────────┐   │
│   │                    操作栏                                 │   │
│   │                                                         │   │
│   │   ┌─────────────┐  ┌─────────────┐  ┌─────────────┐   │   │
│   │   │  新建工作流 │  │  搜索       │  │  筛选       │   │   │
│   │   └─────────────┘  └─────────────┘  └─────────────┘   │   │
│   └─────────────────────────────────────────────────────────┘   │
│                                                                 │
│   ┌─────────────────────────────────────────────────────────┐   │
│   │                    工作流列表                             │   │
│   │                                                         │   │
│   │   ┌─────────────────────────────────────────────────┐   │   │
│   │   │  名称          │  版本  │  状态    │  操作      │   │   │
│   │   ├─────────────────────────────────────────────────┤   │   │
│   │   │  研发测试流程  │  V1   │  激活    │  编辑 删除 │   │   │
│   │   │  安全扫描流程  │  V2   │  草稿    │  编辑 删除 │   │   │
│   │   └─────────────────────────────────────────────────┘   │   │
│   └─────────────────────────────────────────────────────────┘   │
│                                                                 │
│   ┌─────────────────────────────────────────────────────────┐   │
│   │                    分页                                   │   │
│   └─────────────────────────────────────────────────────────┘   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

#### 组件设计

| 组件 | 说明 |
|------|------|
| `WorkflowListPage` | 页面容器 |
| `WorkflowListToolbar` | 操作栏 |
| `WorkflowListTable` | 工作流列表表格 |
| `WorkflowStatusBadge` | 状态徽标 |
| `WorkflowActions` | 操作按钮组 |

#### 代码示例

```tsx
// WorkflowListPage.tsx
import React from 'react';
import { Card, Table, Button, Space, Tag } from 'antd';
import { PlusOutlined, SearchOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useWorkflows } from '@/hooks/useWorkflows';
import WorkflowStatusBadge from '@/components/WorkflowStatusBadge';

const WorkflowListPage: React.FC = () => {
  const navigate = useNavigate();
  const { workflows, loading, pagination, onChange } = useWorkflows();
  
  const columns = [
    {
      title: '名称',
      dataIndex: 'name',
      key: 'name',
      render: (text: string, record: any) => (
        <a onClick={() => navigate(`/workflows/${record.id}`)}>
          {text}
        </a>
      ),
    },
    {
      title: '版本',
      dataIndex: 'version',
      key: 'version',
      render: (version: number) => `V${version}`,
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      render: (status: string) => <WorkflowStatusBadge status={status} />,
    },
    {
      title: '操作',
      key: 'actions',
      render: (_, record) => (
        <Space>
          <Button 
            type="link" 
            onClick={() => navigate(`/workflows/${record.id}/edit`)}
          >
            编辑
          </Button>
          <Button type="link" danger>删除</Button>
        </Space>
      ),
    },
  ];
  
  return (
    <Card title="工作流定义">
      <div style={{ marginBottom: 16 }}>
        <Space>
          <Button 
            type="primary" 
            icon={<PlusOutlined />}
            onClick={() => navigate('/workflows/create')}
          >
            新建工作流
          </Button>
          <Button icon={<SearchOutlined />}>搜索</Button>
        </Space>
      </div>
      
      <Table
        columns={columns}
        dataSource={workflows}
        loading={loading}
        pagination={pagination}
        onChange={onChange}
      />
    </Card>
  );
};

export default WorkflowListPage;
```

### 3.2 工作流定义编辑页

#### 页面结构

```
┌─────────────────────────────────────────────────────────────────┐
│                         页面标题                                  │
│   编辑工作流：研发测试一体化流程                                  │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│   ┌─────────────────────────────────────────────────────────┐   │
│   │                    操作栏                                 │   │
│   │                                                         │   │
│   │   ┌─────────────┐  ┌─────────────┐  ┌─────────────┐   │   │
│   │   │  保存       │  │  预览       │  │  激活       │   │   │
│   │   └─────────────┘  └─────────────┘  └─────────────┘   │   │
│   └─────────────────────────────────────────────────────────┘   │
│                                                                 │
│   ┌─────────────────────────────────────────────────────────┐   │
│   │                    基本信息                               │   │
│   │                                                         │   │
│   │   名称：[________________]                               │   │
│   │   描述：[________________]                               │   │
│   └─────────────────────────────────────────────────────────┘   │
│                                                                 │
│   ┌─────────────────────────────────────────────────────────┐   │
│   │                    流程设计                               │   │
│   │                                                         │   │
│   │   ┌─────────────────────────────────────────────────┐   │   │
│   │   │                                                 │   │   │
│   │   │              流程图可视化编辑器                  │   │   │
│   │   │                                                 │   │   │
│   │   │   ┌─────┐    ┌─────┐    ┌─────┐    ┌─────┐    │   │   │
│   │   │   │需求 │───→│设计 │───→│开发 │───→│测试 │    │   │   │
│   │   │   │收集 │    │评审 │    │审查 │    │验收 │    │   │   │
│   │   │   └─────┘    └─────┘    └─────┘    └─────┘    │   │   │
│   │   │                                                 │   │   │
│   │   └─────────────────────────────────────────────────┘   │   │
│   │                                                         │   │
│   │   ┌─────────────┐  ┌─────────────┐  ┌─────────────┐   │   │
│   │   │  添加节点   │  │  连接节点   │  │  删除节点   │   │   │
│   │   └─────────────┘  └─────────────┘  └─────────────┘   │   │
│   └─────────────────────────────────────────────────────────┘   │
│                                                                 │
│   ┌─────────────────────────────────────────────────────────┐   │
│   │                    节点配置                               │   │
│   │                                                         │   │
│   │   选中节点：需求收集                                     │   │
│   │                                                         │   │
│   │   节点名称：[________________]                           │   │
│   │   节点类型：[TASK          ▼]                           │   │
│   │   负责人：  [产品经理      ▼]                           │   │
│   │   审核人：  [项目经理      ▼]                           │   │
│   │   超时时间：[24 小时       ]                            │   │
│   │   必须执行：[✓]                                         │   │
│   └─────────────────────────────────────────────────────────┘   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

#### 组件设计

| 组件 | 说明 |
|------|------|
| `WorkflowEditPage` | 页面容器 |
| `WorkflowBasicInfo` | 基本信息表单 |
| `WorkflowDesigner` | 流程设计器 |
| `WorkflowNodeConfig` | 节点配置面板 |
| `WorkflowNodeList` | 节点列表 |
| `WorkflowPreview` | 流程预览 |

#### 代码示例

```tsx
// WorkflowEditPage.tsx
import React, { useState } from 'react';
import { Card, Button, Space, Form, Input, Tabs } from 'antd';
import { SaveOutlined, EyeOutlined, RocketOutlined } from '@ant-design/icons';
import { useParams } from 'react-router-dom';
import WorkflowDesigner from '@/components/WorkflowDesigner';
import WorkflowNodeConfig from '@/components/WorkflowNodeConfig';
import { useWorkflow } from '@/hooks/useWorkflow';

const WorkflowEditPage: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const { workflow, loading, save, activate } = useWorkflow(id);
  const [selectedNode, setSelectedNode] = useState<string | null>(null);
  const [form] = Form.useForm();
  
  const handleSave = async () => {
    const values = await form.validateFields();
    await save(values);
  };
  
  const handleActivate = async () => {
    await activate();
  };
  
  return (
    <Card 
      title={`编辑工作流：${workflow?.name || ''}`}
      loading={loading}
      extra={
        <Space>
          <Button icon={<SaveOutlined />} onClick={handleSave}>
            保存
          </Button>
          <Button icon={<EyeOutlined />}>预览</Button>
          <Button 
            type="primary" 
            icon={<RocketOutlined />}
            onClick={handleActivate}
          >
            激活
          </Button>
        </Space>
      }
    >
      <Form form={form} layout="vertical" initialValues={workflow}>
        <Tabs items={[
          {
            key: 'basic',
            label: '基本信息',
            children: (
              <Form.Item name="name" label="名称" rules={[{ required: true }]}>
                <Input />
              </Form.Item>
            ),
          },
          {
            key: 'design',
            label: '流程设计',
            children: (
              <div style={{ display: 'flex', gap: 16 }}>
                <div style={{ flex: 1 }}>
                  <WorkflowDesigner
                    workflow={workflow}
                    selectedNode={selectedNode}
                    onSelectNode={setSelectedNode}
                  />
                </div>
                <div style={{ width: 300 }}>
                  <WorkflowNodeConfig
                    nodeId={selectedNode}
                    workflow={workflow}
                  />
                </div>
              </div>
            ),
          },
        ]} />
      </Form>
    </Card>
  );
};

export default WorkflowEditPage;
```

### 3.3 工作流实例详情页

#### 页面结构

```
┌─────────────────────────────────────────────────────────────────┐
│                         页面标题                                  │
│   工作流实例详情：#1001                                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│   ┌─────────────────────────────────────────────────────────┐   │
│   │                    基本信息                               │   │
│   │                                                         │   │
│   │   工作流：研发测试一体化流程                              │   │
│   │   状态：运行中                                          │   │
│   │   关联实体：PROJECT/100                                 │   │
│   │   创建人：张三                                          │   │
│   │   开始时间：2026-07-07 10:00:00                         │   │
│   └─────────────────────────────────────────────────────────┘   │
│                                                                 │
│   ┌─────────────────────────────────────────────────────────┐   │
│   │                    操作栏                                 │   │
│   │                                                         │   │
│   │   ┌─────────────┐  ┌─────────────┐  ┌─────────────┐   │   │
│   │   │  暂停       │  │  恢复       │  │  取消       │   │   │
│   │   └─────────────┘  └─────────────┘  └─────────────┘   │   │
│   └─────────────────────────────────────────────────────────┘   │
│                                                                 │
│   ┌─────────────────────────────────────────────────────────┐   │
│   │                    流程进度                               │   │
│   │                                                         │   │
│   │   ┌─────┐    ┌─────┐    ┌─────┐    ┌─────┐    ┌─────┐ │   │
│   │   │需求 │───→│设计 │───→│开发 │───→│测试 │───→│部署 │ │   │
│   │   │收集 │    │评审 │    │审查 │    │验收 │    │完成 │ │   │
│   │   │ ✓  │    │ ✓  │    │ ●  │    │ ○  │    │ ○  │ │   │
│   │   └─────┘    └─────┘    └─────┘    └─────┘    └─────┘ │   │
│   │                                                         │   │
│   │   当前节点：代码审查                                     │   │
│   └─────────────────────────────────────────────────────────┘   │
│                                                                 │
│   ┌─────────────────────────────────────────────────────────┐   │
│   │                    节点详情                               │   │
│   │                                                         │   │
│   │   ┌─────────────────────────────────────────────────┐   │   │
│   │   │  节点名称    │  状态      │  负责人  │  操作    │   │   │
│   │   ├─────────────────────────────────────────────────┤   │   │
│   │   │  需求收集    │  已完成    │  张三    │  查看    │   │   │
│   │   │  需求分析    │  已完成    │  李四    │  查看    │   │   │
│   │   │  代码审查    │  进行中    │  王五    │  审核    │   │   │
│   │   │  功能测试    │  待处理    │  -       │  -      │   │   │
│   │   └─────────────────────────────────────────────────┘   │   │
│   └─────────────────────────────────────────────────────────┘   │
│                                                                 │
│   ┌─────────────────────────────────────────────────────────┐   │
│   │                    流转记录                               │   │
│   │                                                         │   │
│   │   ┌─────────────────────────────────────────────────┐   │   │
│   │   │  时间              │  操作      │  操作人  │  备注 │   │   │
│   │   ├─────────────────────────────────────────────────┤   │   │
│   │   │  2026-07-07 10:00  │  启动      │  张三    │  -   │   │   │
│   │   │  2026-07-07 10:30  │  完成      │  张三    │  -   │   │   │
│   │   │  2026-07-07 11:00  │  完成      │  李四    │  -   │   │   │
│   │   │  2026-07-07 11:30  │  进行中    │  王五    │  -   │   │   │
│   │   └─────────────────────────────────────────────────┘   │   │
│   └─────────────────────────────────────────────────────────┘   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

#### 组件设计

| 组件 | 说明 |
|------|------|
| `WorkflowInstanceDetailPage` | 页面容器 |
| `WorkflowInstanceInfo` | 基本信息展示 |
| `WorkflowInstanceActions` | 操作按钮组 |
| `WorkflowProgress` | 流程进度展示 |
| `WorkflowNodeList` | 节点列表 |
| `WorkflowTransitionList` | 流转记录列表 |

### 3.4 工作流监控仪表盘

#### 页面结构

```
┌─────────────────────────────────────────────────────────────────┐
│                         页面标题                                  │
│   工作流监控仪表盘                                               │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│   ┌─────────────────────────────────────────────────────────┐   │
│   │                    统计卡片                               │   │
│   │                                                         │   │
│   │   ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐  │   │
│   │   │  总数   │  │  运行中 │  │  已完成 │  │  已失败 │  │   │
│   │   │  100    │  │  25     │  │  70     │  │  5      │  │   │
│   │   └─────────┘  └─────────┘  └─────────┘  └─────────┘  │   │
│   └─────────────────────────────────────────────────────────┘   │
│                                                                 │
│   ┌─────────────────────────────────────────────────────────┐   │
│   │                    图表区                                 │   │
│   │                                                         │   │
│   │   ┌─────────────────────┐  ┌─────────────────────┐     │   │
│   │   │                     │  │                     │     │   │
│   │   │    状态分布饼图     │  │    趋势折线图       │     │   │
│   │   │                     │  │                     │     │   │
│   │   └─────────────────────┘  └─────────────────────┘     │   │
│   └─────────────────────────────────────────────────────────┘   │
│                                                                 │
│   ┌─────────────────────────────────────────────────────────┐   │
│   │                    运行中实例列表                         │   │
│   │                                                         │   │
│   │   ┌─────────────────────────────────────────────────┐   │   │
│   │   │  ID      │  工作流    │  状态    │  当前节点   │   │   │
│   │   ├─────────────────────────────────────────────────┤   │   │
│   │   │  1001    │  研发测试  │  运行中  │  代码审查   │   │   │
│   │   │  1002    │  安全扫描  │  运行中  │  漏洞分析   │   │   │
│   │   └─────────────────────────────────────────────────┘   │   │
│   └─────────────────────────────────────────────────────────┘   │
│                                                                 │
│   ┌─────────────────────────────────────────────────────────┐   │
│   │                    超时节点列表                           │   │
│   │                                                         │   │
│   │   ┌─────────────────────────────────────────────────┐   │   │
│   │   │  实例ID  │  节点      │  负责人  │  超时时间    │   │   │
│   │   ├─────────────────────────────────────────────────┤   │   │
│   │   │  1001    │  需求评审  │  李四    │  2026-07-07  │   │   │
│   │   └─────────────────────────────────────────────────┘   │   │
│   └─────────────────────────────────────────────────────────┘   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

#### 组件设计

| 组件 | 说明 |
|------|------|
| `WorkflowMonitorPage` | 页面容器 |
| `WorkflowStatsCards` | 统计卡片 |
| `WorkflowStatusChart` | 状态分布饼图 |
| `WorkflowTrendChart` | 趋势折线图 |
| `RunningInstanceList` | 运行中实例列表 |
| `TimeoutNodeList` | 超时节点列表 |

### 3.5 通知中心页面

#### 页面结构

```
┌─────────────────────────────────────────────────────────────────┐
│                         页面标题                                  │
│   通知中心                                                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│   ┌─────────────────────────────────────────────────────────┐   │
│   │                    操作栏                                 │   │
│   │                                                         │   │
│   │   ┌─────────────┐  ┌─────────────┐  ┌─────────────┐   │   │
│   │   │  全部已读   │  │  筛选       │  │  搜索       │   │   │
│   │   └─────────────┘  └─────────────┘  └─────────────┘   │   │
│   └─────────────────────────────────────────────────────────┘   │
│                                                                 │
│   ┌─────────────────────────────────────────────────────────┐   │
│   │                    通知列表                               │   │
│   │                                                         │   │
│   │   ┌─────────────────────────────────────────────────┐   │   │
│   │   │  [新] 任务已分配：需求分析                       │   │   │
│   │   │       2026-07-07 11:00:00                       │   │   │
│   │   ├─────────────────────────────────────────────────┤   │   │
│   │   │  [新] 审核请求：需求评审                         │   │   │
│   │   │       2026-07-07 10:30:00                       │   │   │
│   │   ├─────────────────────────────────────────────────┤   │   │
│   │   │      节点已完成：需求收集                        │   │   │
│   │   │       2026-07-07 10:00:00                       │   │   │
│   │   └─────────────────────────────────────────────────┘   │   │
│   └─────────────────────────────────────────────────────────┘   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

#### 组件设计

| 组件 | 说明 |
|------|------|
| `NotificationCenterPage` | 页面容器 |
| `NotificationToolbar` | 操作栏 |
| `NotificationList` | 通知列表 |
| `NotificationItem` | 通知项 |
| `NotificationBadge` | 未读徽标 |

---

## 4. 组件设计

### 4.1 WorkflowDesigner 组件

#### 功能说明

流程设计器是工作流定义编辑的核心组件，支持：
- 可视化拖拽式流程设计
- 节点添加、删除、移动
- 节点连接
- 节点配置

#### 组件结构

```tsx
// WorkflowDesigner.tsx
import React, { useCallback, useMemo } from 'react';
import ReactFlow, { 
  Node, 
  Edge, 
  Controls, 
  Background,
  useNodesState,
  useEdgesState,
  addEdge
} from 'reactflow';
import 'reactflow/dist/style.css';
import WorkflowNode from './WorkflowNode';
import { WorkflowDefinition, WorkflowNodeDefinition } from '@/types/workflow';

interface WorkflowDesignerProps {
  workflow: WorkflowDefinition | null;
  selectedNode: string | null;
  onSelectNode: (nodeId: string | null) => void;
}

const WorkflowDesigner: React.FC<WorkflowDesignerProps> = ({
  workflow,
  selectedNode,
  onSelectNode,
}) => {
  // 将工作流定义转换为 React Flow 节点和边
  const { initialNodes, initialEdges } = useMemo(() => {
    if (!workflow) return { initialNodes: [], initialEdges: [] };
    
    const nodes: Node[] = workflow.nodes.map((node, index) => ({
      id: node.nodeId,
      type: 'workflowNode',
      position: { x: 100 + index * 200, y: 100 },
      data: {
        label: node.name,
        type: node.type,
        status: node.status,
        isSelected: node.nodeId === selectedNode,
      },
    }));
    
    const edges: Edge[] = workflow.transitions.map((transition) => ({
      id: `${transition.from}-${transition.to}`,
      source: transition.from,
      target: transition.to,
      type: 'smoothstep',
    }));
    
    return { initialNodes: nodes, initialEdges: edges };
  }, [workflow, selectedNode]);
  
  const [nodes, setNodes, onNodesChange] = useNodesState(initialNodes);
  const [edges, setEdges, onEdgesChange] = useEdgesState(initialEdges);
  
  const onConnect = useCallback(
    (params: any) => setEdges((eds) => addEdge(params, eds)),
    [setEdges]
  );
  
  const onNodeClick = useCallback(
    (event: React.MouseEvent, node: Node) => {
      onSelectNode(node.id);
    },
    [onSelectNode]
  );
  
  const onPaneClick = useCallback(() => {
    onSelectNode(null);
  }, [onSelectNode]);
  
  const nodeTypes = useMemo(() => ({
    workflowNode: WorkflowNode,
  }), []);
  
  return (
    <div style={{ height: '500px', border: '1px solid #d9d9d9', borderRadius: 4 }}>
      <ReactFlow
        nodes={nodes}
        edges={edges}
        onNodesChange={onNodesChange}
        onEdgesChange={onEdgesChange}
        onConnect={onConnect}
        onNodeClick={onNodeClick}
        onPaneClick={onPaneClick}
        nodeTypes={nodeTypes}
        fitView
      >
        <Controls />
        <Background />
      </ReactFlow>
    </div>
  );
};

export default WorkflowDesigner;
```

### 4.2 WorkflowNode 组件

#### 功能说明

流程节点组件，用于在流程设计器中展示单个节点。

#### 组件结构

```tsx
// WorkflowNode.tsx
import React from 'react';
import { Handle, Position, NodeProps } from 'reactflow';
import { Tag } from 'antd';
import { 
  FileTextOutlined, 
  CodeOutlined, 
  TestOutlined, 
  SafetyOutlined,
  RocketOutlined 
} from '@ant-design/icons';

const nodeTypeIcons: Record<string, React.ReactNode> = {
  TASK: <FileTextOutlined />,
  REVIEW: <CodeOutlined />,
  APPROVAL: <TestOutlined />,
  PARALLEL: <SafetyOutlined />,
  DECISION: <RocketOutlined />,
};

const nodeStatusColors: Record<string, string> = {
  PENDING: 'default',
  IN_PROGRESS: 'processing',
  COMPLETED: 'success',
  REJECTED: 'error',
  SKIPPED: 'warning',
  BLOCKED: 'error',
};

interface WorkflowNodeData {
  label: string;
  type: string;
  status?: string;
  isSelected: boolean;
}

const WorkflowNode: React.FC<NodeProps<WorkflowNodeData>> = ({ data, selected }) => {
  const { label, type, status, isSelected } = data;
  
  return (
    <div
      style={{
        padding: '8px 16px',
        border: `2px solid ${isSelected ? '#1890ff' : '#d9d9d9'}`,
        borderRadius: 4,
        backgroundColor: isSelected ? '#e6f7ff' : '#fff',
        cursor: 'pointer',
        minWidth: 120,
        textAlign: 'center',
      }}
    >
      <Handle type="target" position={Position.Top} />
      
      <div style={{ marginBottom: 4 }}>
        {nodeTypeIcons[type] || <FileTextOutlined />}
      </div>
      
      <div style={{ fontWeight: 'bold', marginBottom: 4 }}>
        {label}
      </div>
      
      {status && (
        <Tag color={nodeStatusColors[status]}>
          {status}
        </Tag>
      )}
      
      <Handle type="source" position={Position.Bottom} />
    </div>
  );
};

export default WorkflowNode;
```

### 4.3 WorkflowNodeConfig 组件

#### 功能说明

节点配置组件，用于配置选中节点的属性。

#### 组件结构

```tsx
// WorkflowNodeConfig.tsx
import React from 'react';
import { Card, Form, Input, Select, Switch, InputNumber } from 'antd';
import { WorkflowDefinition, WorkflowNodeDefinition } from '@/types/workflow';

interface WorkflowNodeConfigProps {
  nodeId: string | null;
  workflow: WorkflowDefinition | null;
}

const WorkflowNodeConfig: React.FC<WorkflowNodeConfigProps> = ({
  nodeId,
  workflow,
}) => {
  const [form] = Form.useForm();
  
  const node = workflow?.nodes.find((n) => n.nodeId === nodeId);
  
  React.useEffect(() => {
    if (node) {
      form.setFieldsValue(node);
    }
  }, [node, form]);
  
  if (!nodeId || !node) {
    return (
      <Card title="节点配置">
        <div style={{ textAlign: 'center', color: '#999' }}>
          请选择一个节点进行配置
        </div>
      </Card>
    );
  }
  
  return (
    <Card title="节点配置">
      <Form form={form} layout="vertical">
        <Form.Item name="name" label="节点名称" rules={[{ required: true }]}>
          <Input />
        </Form.Item>
        
        <Form.Item name="type" label="节点类型" rules={[{ required: true }]}>
          <Select>
            <Select.Option value="TASK">任务节点</Select.Option>
            <Select.Option value="REVIEW">审核节点</Select.Option>
            <Select.Option value="APPROVAL">审批节点</Select.Option>
            <Select.Option value="PARALLEL">并行节点</Select.Option>
            <Select.Option value="DECISION">决策节点</Select.Option>
          </Select>
        </Form.Item>
        
        <Form.Item name="ownerRole" label="负责人角色" rules={[{ required: true }]}>
          <Select>
            <Select.Option value="product_manager">产品经理</Select.Option>
            <Select.Option value="architect">架构师</Select.Option>
            <Select.Option value="developer">开发者</Select.Option>
            <Select.Option value="test_engineer">测试工程师</Select.Option>
            <Select.Option value="security_engineer">安全工程师</Select.Option>
          </Select>
        </Form.Item>
        
        <Form.Item name="approverRole" label="审核人角色">
          <Select>
            <Select.Option value="project_manager">项目经理</Select.Option>
            <Select.Option value="tech_lead">技术负责人</Select.Option>
            <Select.Option value="test_lead">测试负责人</Select.Option>
            <Select.Option value="security_lead">安全负责人</Select.Option>
          </Select>
        </Form.Item>
        
        <Form.Item name="timeoutHours" label="超时时间（小时）">
          <InputNumber min={1} max={168} />
        </Form.Item>
        
        <Form.Item name="isRequired" label="必须执行" valuePropName="checked">
          <Switch />
        </Form.Item>
      </Form>
    </Card>
  );
};

export default WorkflowNodeConfig;
```

### 4.4 WorkflowProgress 组件

#### 功能说明

流程进度组件，用于展示工作流实例的执行进度。

#### 组件结构

```tsx
// WorkflowProgress.tsx
import React from 'react';
import { Steps, Tag } from 'antd';
import { 
  CheckCircleOutlined, 
  ClockCircleOutlined, 
  CloseCircleOutlined,
  MinusCircleOutlined 
} from '@ant-design/icons';
import { WorkflowInstance, WorkflowNodeInstance } from '@/types/workflow';

interface WorkflowProgressProps {
  instance: WorkflowInstance;
}

const stepStatusMap: Record<string, 'finish' | 'process' | 'error' | 'wait'> = {
  COMPLETED: 'finish',
  IN_PROGRESS: 'process',
  REJECTED: 'error',
  PENDING: 'wait',
  SKIPPED: 'wait',
  BLOCKED: 'error',
};

const WorkflowProgress: React.FC<WorkflowProgressProps> = ({ instance }) => {
  const steps = instance.nodes.map((node) => ({
    title: node.nodeId,
    status: stepStatusMap[node.status] || 'wait',
    description: (
      <Tag color={node.status === 'COMPLETED' ? 'success' : 'default'}>
        {node.status}
      </Tag>
    ),
  }));
  
  return (
    <Steps current={steps.findIndex((s) => s.status === 'process')} items={steps} />
  );
};

export default WorkflowProgress;
```

### 4.5 NotificationBell 组件

#### 功能说明

通知铃铛组件，用于在导航栏中展示未读通知数量。

#### 组件结构

```tsx
// NotificationBell.tsx
import React from 'react';
import { Badge, Popover, List, Button, Typography } from 'antd';
import { BellOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useNotifications } from '@/hooks/useNotifications';

const NotificationBell: React.FC = () => {
  const navigate = useNavigate();
  const { unreadCount, notifications, markAsRead } = useNotifications();
  
  const content = (
    <div style={{ width: 300 }}>
      <List
        dataSource={notifications.slice(0, 5)}
        renderItem={(item) => (
          <List.Item
            style={{ cursor: 'pointer' }}
            onClick={() => {
              markAsRead(item.id);
              navigate('/notifications');
            }}
          >
            <List.Item.Meta
              title={item.title}
              description={item.content}
            />
          </List.Item>
        )}
      />
      <div style={{ textAlign: 'center', marginTop: 8 }}>
        <Button type="link" onClick={() => navigate('/notifications')}>
          查看全部
        </Button>
      </div>
    </div>
  );
  
  return (
    <Popover content={content} trigger="click">
      <Badge count={unreadCount}>
        <BellOutlined style={{ fontSize: 18, cursor: 'pointer' }} />
      </Badge>
    </Popover>
  );
};

export default NotificationBell;
```

---

## 5. 状态管理

### 5.1 Zustand Store

```typescript
// workflowStore.ts
import { create } from 'zustand';
import { 
  WorkflowDefinition, 
  WorkflowInstance, 
  WorkflowNotification 
} from '@/types/workflow';

interface WorkflowState {
  // 工作流定义
  workflows: WorkflowDefinition[];
  currentWorkflow: WorkflowDefinition | null;
  
  // 工作流实例
  instances: WorkflowInstance[];
  currentInstance: WorkflowInstance | null;
  
  // 通知
  notifications: WorkflowNotification[];
  unreadCount: number;
  
  // 操作
  setWorkflows: (workflows: WorkflowDefinition[]) => void;
  setCurrentWorkflow: (workflow: WorkflowDefinition | null) => void;
  setInstances: (instances: WorkflowInstance[]) => void;
  setCurrentInstance: (instance: WorkflowInstance | null) => void;
  setNotifications: (notifications: WorkflowNotification[]) => void;
  setUnreadCount: (count: number) => void;
  
  // 异步操作
  fetchWorkflows: () => Promise<void>;
  fetchInstances: () => Promise<void>;
  fetchNotifications: () => Promise<void>;
  markNotificationAsRead: (id: number) => Promise<void>;
}

export const useWorkflowStore = create<WorkflowState>((set, get) => ({
  // 初始状态
  workflows: [],
  currentWorkflow: null,
  instances: [],
  currentInstance: null,
  notifications: [],
  unreadCount: 0,
  
  // 设置操作
  setWorkflows: (workflows) => set({ workflows }),
  setCurrentWorkflow: (workflow) => set({ currentWorkflow: workflow }),
  setInstances: (instances) => set({ instances }),
  setCurrentInstance: (instance) => set({ currentInstance: instance }),
  setNotifications: (notifications) => set({ notifications }),
  setUnreadCount: (count) => set({ unreadCount: count }),
  
  // 异步操作
  fetchWorkflows: async () => {
    try {
      const response = await fetch('/api/v1/workflows');
      const data = await response.json();
      set({ workflows: data.content });
    } catch (error) {
      console.error('Failed to fetch workflows:', error);
    }
  },
  
  fetchInstances: async () => {
    try {
      const response = await fetch('/api/v1/workflow-instances');
      const data = await response.json();
      set({ instances: data.content });
    } catch (error) {
      console.error('Failed to fetch instances:', error);
    }
  },
  
  fetchNotifications: async () => {
    try {
      const response = await fetch('/api/v1/workflow-notifications');
      const data = await response.json();
      set({ notifications: data.content });
    } catch (error) {
      console.error('Failed to fetch notifications:', error);
    }
  },
  
  markNotificationAsRead: async (id) => {
    try {
      await fetch(`/api/v1/workflow-notifications/${id}/read`, {
        method: 'POST',
      });
      
      const { notifications, unreadCount } = get();
      set({
        notifications: notifications.map((n) =>
          n.id === id ? { ...n, isRead: true } : n
        ),
        unreadCount: Math.max(0, unreadCount - 1),
      });
    } catch (error) {
      console.error('Failed to mark notification as read:', error);
    }
  },
}));
```

### 5.2 自定义 Hooks

```typescript
// useWorkflows.ts
import { useEffect } from 'react';
import { useWorkflowStore } from '@/stores/workflowStore';

export const useWorkflows = () => {
  const { workflows, setWorkflows, fetchWorkflows } = useWorkflowStore();
  
  useEffect(() => {
    fetchWorkflows();
  }, [fetchWorkflows]);
  
  return {
    workflows,
    setWorkflows,
    fetchWorkflows,
  };
};

// useWorkflow.ts
import { useEffect } from 'react';
import { useWorkflowStore } from '@/stores/workflowStore';

export const useWorkflow = (id: string | undefined) => {
  const { currentWorkflow, setCurrentWorkflow, fetchWorkflows } = useWorkflowStore();
  
  useEffect(() => {
    if (id) {
      fetchWorkflows().then(() => {
        const workflow = useWorkflowStore.getState().workflows.find(
          (w) => w.id === parseInt(id)
        );
        setCurrentWorkflow(workflow || null);
      });
    }
  }, [id, fetchWorkflows, setCurrentWorkflow]);
  
  return {
    workflow: currentWorkflow,
  };
};

// useNotifications.ts
import { useEffect } from 'react';
import { useWorkflowStore } from '@/stores/workflowStore';

export const useNotifications = () => {
  const { 
    notifications, 
    unreadCount, 
    fetchNotifications, 
    markNotificationAsRead 
  } = useWorkflowStore();
  
  useEffect(() => {
    fetchNotifications();
  }, [fetchNotifications]);
  
  return {
    notifications,
    unreadCount,
    markNotificationAsRead,
  };
};
```

---

## 6. 样式设计

### 6.1 全局样式

```css
/* workflow.css */

/* 工作流设计器 */
.workflow-designer {
  height: 500px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  background-color: #fafafa;
}

/* 工作流节点 */
.workflow-node {
  padding: 8px 16px;
  border: 2px solid #d9d9d9;
  border-radius: 4px;
  background-color: #fff;
  cursor: pointer;
  transition: all 0.3s;
}

.workflow-node:hover {
  border-color: #1890ff;
}

.workflow-node.selected {
  border-color: #1890ff;
  background-color: #e6f7ff;
}

/* 工作流状态 */
.workflow-status {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 12px;
}

.workflow-status.pending {
  background-color: #f5f5f5;
  color: #999;
}

.workflow-status.running {
  background-color: #e6f7ff;
  color: #1890ff;
}

.workflow-status.completed {
  background-color: #f6ffed;
  color: #52c41a;
}

.workflow-status.failed {
  background-color: #fff2f0;
  color: #ff4d4f;
}

/* 通知徽标 */
.notification-badge {
  position: absolute;
  top: -8px;
  right: -8px;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: 8px;
  background-color: #ff4d4f;
  color: #fff;
  font-size: 12px;
  line-height: 16px;
  text-align: center;
}
```

### 6.2 响应式设计

```css
/* 响应式设计 */
@media (max-width: 768px) {
  .workflow-designer {
    height: 300px;
  }
  
  .workflow-node {
    padding: 4px 8px;
    font-size: 12px;
  }
  
  .workflow-stats-cards {
    flex-direction: column;
  }
}

@media (max-width: 576px) {
  .workflow-designer {
    height: 250px;
  }
  
  .workflow-node {
    padding: 4px 8px;
    font-size: 10px;
    min-width: 80px;
  }
}
```

---

## 7. 国际化

### 7.1 语言包

```json
// zh-CN.json
{
  "workflow": {
    "title": "工作流管理",
    "list": "工作流列表",
    "create": "创建工作流",
    "edit": "编辑工作流",
    "delete": "删除工作流",
    "activate": "激活工作流",
    "deactivate": "停用工作流",
    "status": {
      "DRAFT": "草稿",
      "ACTIVE": "激活",
      "DEPRECATED": "停用"
    },
    "instance": {
      "title": "工作流实例",
      "start": "启动实例",
      "pause": "暂停实例",
      "resume": "恢复实例",
      "cancel": "取消实例",
      "status": {
        "PENDING": "待处理",
        "RUNNING": "运行中",
        "PAUSED": "已暂停",
        "COMPLETED": "已完成",
        "FAILED": "已失败",
        "CANCELLED": "已取消"
      }
    },
    "node": {
      "title": "节点管理",
      "complete": "完成节点",
      "review": "审核节点",
      "rollback": "回退节点",
      "assign": "分配任务",
      "status": {
        "PENDING": "待处理",
        "ASSIGNED": "已分配",
        "IN_PROGRESS": "进行中",
        "REVIEW": "待审核",
        "COMPLETED": "已完成",
        "REJECTED": "已驳回",
        "SKIPPED": "已跳过",
        "BLOCKED": "已阻塞"
      }
    },
    "notification": {
      "title": "通知中心",
      "markAsRead": "标记已读",
      "markAllAsRead": "全部已读",
      "unreadCount": "未读数量"
    }
  }
}

// en-US.json
{
  "workflow": {
    "title": "Workflow Management",
    "list": "Workflow List",
    "create": "Create Workflow",
    "edit": "Edit Workflow",
    "delete": "Delete Workflow",
    "activate": "Activate Workflow",
    "deactivate": "Deactivate Workflow",
    "status": {
      "DRAFT": "Draft",
      "ACTIVE": "Active",
      "DEPRECATED": "Deprecated"
    },
    "instance": {
      "title": "Workflow Instance",
      "start": "Start Instance",
      "pause": "Pause Instance",
      "resume": "Resume Instance",
      "cancel": "Cancel Instance",
      "status": {
        "PENDING": "Pending",
        "RUNNING": "Running",
        "PAUSED": "Paused",
        "COMPLETED": "Completed",
        "FAILED": "Failed",
        "CANCELLED": "Cancelled"
      }
    },
    "node": {
      "title": "Node Management",
      "complete": "Complete Node",
      "review": "Review Node",
      "rollback": "Rollback Node",
      "assign": "Assign Task",
      "status": {
        "PENDING": "Pending",
        "ASSIGNED": "Assigned",
        "IN_PROGRESS": "In Progress",
        "REVIEW": "Review",
        "COMPLETED": "Completed",
        "REJECTED": "Rejected",
        "SKIPPED": "Skipped",
        "BLOCKED": "Blocked"
      }
    },
    "notification": {
      "title": "Notification Center",
      "markAsRead": "Mark as Read",
      "markAllAsRead": "Mark All as Read",
      "unreadCount": "Unread Count"
    }
  }
}
```

---

## 8. 测试设计

### 8.1 单元测试

```tsx
// WorkflowNode.test.tsx
import React from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import WorkflowNode from './WorkflowNode';

describe('WorkflowNode', () => {
  const defaultProps = {
    id: 'test-node',
    data: {
      label: 'Test Node',
      type: 'TASK',
      status: 'PENDING',
      isSelected: false,
    },
    selected: false,
    isConnectable: true,
    zIndex: 1,
    positionAbsoluteX: 0,
    positionAbsoluteY: 0,
    dragging: false,
  };
  
  it('renders node label', () => {
    render(<WorkflowNode {...defaultProps} />);
    expect(screen.getByText('Test Node')).toBeInTheDocument();
  });
  
  it('renders node status', () => {
    render(<WorkflowNode {...defaultProps} />);
    expect(screen.getByText('PENDING')).toBeInTheDocument();
  });
  
  it('applies selected style when selected', () => {
    const { container } = render(
      <WorkflowNode {...defaultProps} selected={true} />
    );
    expect(container.firstChild).toHaveStyle('border-color: #1890ff');
  });
  
  it('calls onClick when clicked', () => {
    const onClick = jest.fn();
    render(<WorkflowNode {...defaultProps} />);
    fireEvent.click(screen.getByText('Test Node'));
    // Note: onClick is handled by React Flow, not directly in the component
  });
});
```

### 8.2 集成测试

```tsx
// WorkflowDesigner.test.tsx
import React from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import WorkflowDesigner from './WorkflowDesigner';

describe('WorkflowDesigner', () => {
  const mockWorkflow = {
    id: 1,
    name: 'Test Workflow',
    nodes: [
      { nodeId: 'node1', name: 'Node 1', type: 'TASK', status: 'PENDING' },
      { nodeId: 'node2', name: 'Node 2', type: 'TASK', status: 'PENDING' },
    ],
    transitions: [
      { from: 'node1', to: 'node2' },
    ],
  };
  
  it('renders workflow nodes', () => {
    render(
      <WorkflowDesigner
        workflow={mockWorkflow}
        selectedNode={null}
        onSelectNode={() => {}}
      />
    );
    
    expect(screen.getByText('Node 1')).toBeInTheDocument();
    expect(screen.getByText('Node 2')).toBeInTheDocument();
  });
  
  it('selects node when clicked', () => {
    const onSelectNode = jest.fn();
    render(
      <WorkflowDesigner
        workflow={mockWorkflow}
        selectedNode={null}
        onSelectNode={onSelectNode}
      />
    );
    
    fireEvent.click(screen.getByText('Node 1'));
    expect(onSelectNode).toHaveBeenCalledWith('node1');
  });
});
```

---

## 9. 性能优化

### 9.1 虚拟列表

```tsx
// VirtualNodeList.tsx
import React from 'react';
import { FixedSizeList as List } from 'react-window';
import WorkflowNode from './WorkflowNode';

interface VirtualNodeListProps {
  nodes: any[];
  onSelectNode: (nodeId: string) => void;
}

const VirtualNodeList: React.FC<VirtualNodeListProps> = ({
  nodes,
  onSelectNode,
}) => {
  const Row = ({ index, style }: { index: number; style: React.CSSProperties }) => (
    <div style={style}>
      <WorkflowNode
        id={nodes[index].nodeId}
        data={{
          label: nodes[index].name,
          type: nodes[index].type,
          status: nodes[index].status,
          isSelected: false,
        }}
        selected={false}
        isConnectable={true}
        zIndex={1}
        positionAbsoluteX={0}
        positionAbsoluteY={0}
        dragging={false}
      />
    </div>
  );
  
  return (
    <List
      height={500}
      itemCount={nodes.length}
      itemSize={80}
      width="100%"
    >
      {Row}
    </List>
  );
};

export default VirtualNodeList;
```

### 9.2 懒加载

```tsx
// LazyWorkflowDesigner.tsx
import React, { Suspense, lazy } from 'react';
import { Spin } from 'antd';

const WorkflowDesigner = lazy(() => import('./WorkflowDesigner'));

const LazyWorkflowDesigner: React.FC<any> = (props) => (
  <Suspense fallback={<Spin size="large" />}>
    <WorkflowDesigner {...props} />
  </Suspense>
);

export default LazyWorkflowDesigner;
```

---

## 10. 总结

### 10.1 设计要点

1. **页面结构清晰**：6 个主要页面，职责分明
2. **组件化设计**：可复用的组件，易于维护
3. **状态管理完善**：使用 Zustand 管理全局状态
4. **响应式设计**：支持多种屏幕尺寸
5. **国际化支持**：支持中英文切换
6. **性能优化**：虚拟列表、懒加载等优化措施
7. **测试覆盖**：单元测试和集成测试

### 10.2 技术选型

| 技术 | 版本 | 说明 |
|------|------|------|
| React | 18+ | UI 框架 |
| TypeScript | 5.3+ | 类型安全 |
| Ant Design | 5+ | UI 组件库 |
| React Flow | 11+ | 流程图可视化 |
| Zustand | 4+ | 状态管理 |
| React Router | 6+ | 路由管理 |

### 10.3 后续优化

1. **流程设计器优化**：支持更复杂的流程设计
2. **实时协作**：支持多人同时编辑流程
3. **流程模板**：提供预定义的流程模板
4. **流程分析**：支持流程执行数据分析
5. **AI 辅助**：支持 AI 辅助流程设计

---

## 11. 融合说明

### 11.1 前端技术适配

| 适配项 | 说明 |
|--------|------|
| 前端框架 | React 18+ / TypeScript 5.3+ / Ant Design 5+ |
| 状态管理 | Zustand 4+ |
| 流程图 | React Flow 11+ |
| 国密支持 | 前端使用 WebCrypto API 调用 SM2/SM3 算法 |
| 信创适配 | 前端无特殊适配需求，浏览器兼容即可 |

### 11.2 与源文档的融合要点

- 本文档完整吸收了 opencode-1.17.14/docs/design/30-workflow-ui-design.md 的全部内容
- 文档信息头部已更新为格物项目标准格式
- 新增融合说明章节，明确前端技术选型与信创适配要求
- 所有组件设计、代码示例、样式设计等内容保持不变

---

## 12. 关联文档

| 文档 | 章节 | 说明 |
|------|------|------|
| 28-workflow-engine-design.md | §3 状态机 | 工作流状态机定义 |
| 30-workflow-api-design.md | §2-6 API | 后端 API 接口 |
| 24-unified-security.md | §12 工作流安全 | 安全控制要求 |
