import { useState, useEffect, useCallback } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import {
  Tabs, Form, Input, Button, Tag, Switch, Table, Modal, Select, Space, Spin, message, Descriptions,
} from 'antd'
import { ArrowLeftOutlined, PlusOutlined, ReloadOutlined, EditOutlined, CheckOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import {
  get, post, put,
  AgentDTO, UpdateAgentRequest, AgentToolDTO, CreateToolRequest, AgentExecutionDTO, PageResult,
} from '../services/api'
import dayjs from 'dayjs'

const providerLabels: Record<string, string> = {
  alibaba: '阿里通义',
  deepseek: 'DeepSeek',
  openai: 'OpenAI',
}

const toolTypeColor: Record<string, string> = {
  api: 'blue',
  code: 'geekblue',
  function: 'purple',
  workflow: 'magenta',
}

const executionStatusColor: Record<string, string> = {
  pending: 'default',
  running: 'processing',
  completed: 'success',
  failed: 'error',
  cancelled: 'warning',
}

type TabKey = 'basic' | 'model' | 'tools' | 'executions'

function BasicInfoTab({ agent, onSaved }: { agent: AgentDTO; onSaved: (a: AgentDTO) => void }) {
  const [form] = Form.useForm<UpdateAgentRequest>()
  const [submitting, setSubmitting] = useState(false)

  useEffect(() => {
    form.setFieldsValue({
      agentName: agent.agentName,
      description: agent.description,
      modelProvider: agent.modelProvider,
      modelName: agent.modelName,
      status: agent.status,
      systemPrompt: agent.systemPrompt,
    })
  }, [agent, form])

  const handleSave = async (values: UpdateAgentRequest) => {
    setSubmitting(true)
    try {
      const result = await put<AgentDTO>(`/agents/${agent.agentId}`, {
        ...values,
        status: values.status ? 1 : 0,
      })
      if (result.success) {
        message.success('保存成功')
        onSaved(result.data)
      } else {
        message.error(result.message)
      }
    } catch (error: any) {
      message.error(error.message || '保存失败')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Form form={form} layout="vertical" style={{ maxWidth: 720 }} onFinish={handleSave}>
      <Form.Item label="Agent 名称" name="agentName" rules={[{ required: true, message: '请输入 Agent 名称' }]}>
        <Input placeholder="请输入 Agent 名称" />
      </Form.Item>
      <Form.Item label="描述" name="description">
        <Input.TextArea rows={2} placeholder="请输入 Agent 描述" />
      </Form.Item>
      <Form.Item label="模型提供商" name="modelProvider">
        <Input disabled />
      </Form.Item>
      <Form.Item label="模型名称" name="modelName">
        <Input disabled />
      </Form.Item>
      <Form.Item label="状态" name="status" valuePropName="checked" getValueProps={(v) => ({ checked: v === 1 })}>
        <Switch checkedChildren="启用" unCheckedChildren="禁用" />
      </Form.Item>
      <Form.Item label="系统提示词" name="systemPrompt">
        <Input.TextArea rows={6} disabled />
      </Form.Item>
      <Form.Item>
        <Button type="primary" htmlType="submit" loading={submitting} icon={<CheckOutlined />}>
          保存
        </Button>
      </Form.Item>
    </Form>
  )
}

function ModelConfigTab({ agent, onSaved }: { agent: AgentDTO; onSaved: (a: AgentDTO) => void }) {
  const [editing, setEditing] = useState(false)
  const [modelConfig, setModelConfig] = useState(agent.modelConfig ?? '')
  const [systemPrompt, setSystemPrompt] = useState(agent.systemPrompt ?? '')
  const [submitting, setSubmitting] = useState(false)

  useEffect(() => {
    setModelConfig(agent.modelConfig ?? '')
    setSystemPrompt(agent.systemPrompt ?? '')
  }, [agent])

  const handleSave = async () => {
    setSubmitting(true)
    try {
      const result = await put<AgentDTO>(`/agents/${agent.agentId}`, {
        modelConfig,
        systemPrompt,
      })
      if (result.success) {
        message.success('模型配置已保存')
        setEditing(false)
        onSaved(result.data)
      } else {
        message.error(result.message)
      }
    } catch (error: any) {
      message.error(error.message || '保存失败')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div style={{ maxWidth: 800 }}>
      <Descriptions column={1} bordered size="small" style={{ marginBottom: 16 }}>
        <Descriptions.Item label="模型提供商">{providerLabels[agent.modelProvider] || agent.modelProvider}</Descriptions.Item>
        <Descriptions.Item label="模型名称">{agent.modelName}</Descriptions.Item>
      </Descriptions>
      <div style={{ marginBottom: 8, fontWeight: 600 }}>模型参数 (modelConfig)</div>
      <Input.TextArea
        value={modelConfig}
        onChange={(e) => setModelConfig(e.target.value)}
        readOnly={!editing}
        rows={10}
        style={{ fontFamily: 'monospace', marginBottom: 16 }}
        placeholder='{"temperature":0.7,"max_tokens":2048}'
      />
      <div style={{ marginBottom: 8, fontWeight: 600 }}>系统提示词 (systemPrompt)</div>
      <Input.TextArea
        value={systemPrompt}
        onChange={(e) => setSystemPrompt(e.target.value)}
        readOnly={!editing}
        rows={8}
        style={{ marginBottom: 16 }}
        placeholder="请输入系统提示词"
      />
      <Space>
        {!editing ? (
          <Button icon={<EditOutlined />} onClick={() => setEditing(true)}>编辑</Button>
        ) : (
          <>
            <Button type="primary" icon={<CheckOutlined />} loading={submitting} onClick={handleSave}>保存</Button>
            <Button onClick={() => { setEditing(false); setModelConfig(agent.modelConfig ?? ''); setSystemPrompt(agent.systemPrompt ?? '') }}>取消</Button>
          </>
        )}
      </Space>
    </div>
  )
}

function ToolsTab({ agentId }: { agentId: string }) {
  const [tools, setTools] = useState<AgentToolDTO[]>([])
  const [loading, setLoading] = useState(false)
  const [modalOpen, setModalOpen] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [form] = Form.useForm<CreateToolRequest>()

  const fetchTools = useCallback(async () => {
    setLoading(true)
    try {
      const result = await get<AgentToolDTO[]>(`/agents/${agentId}/tools`)
      if (result.success) {
        setTools(result.data)
      } else {
        message.error(result.message)
      }
    } catch (error: any) {
      message.error(error.message || '获取工具列表失败')
    } finally {
      setLoading(false)
    }
  }, [agentId])

  useEffect(() => {
    fetchTools()
  }, [fetchTools])

  const handleCreate = async (values: CreateToolRequest) => {
    setSubmitting(true)
    try {
      const result = await post<AgentToolDTO>('/agents/tools', { ...values, agentId })
      if (result.success) {
        message.success('工具添加成功')
        setModalOpen(false)
        form.resetFields()
        fetchTools()
      } else {
        message.error(result.message)
      }
    } catch (error: any) {
      message.error(error.message || '添加工具失败')
    } finally {
      setSubmitting(false)
    }
  }

  const openCreate = () => {
    form.resetFields()
    form.setFieldsValue({ agentId, toolType: 'api', timeoutMs: 30000, sortOrder: 0 })
    setModalOpen(true)
  }

  const columns: ColumnsType<AgentToolDTO> = [
    { title: '工具名称', dataIndex: 'toolName', key: 'toolName' },
    { title: '描述', dataIndex: 'description', key: 'description', ellipsis: true, render: (v) => v || '-' },
    {
      title: '类型', dataIndex: 'toolType', key: 'toolType', width: 110,
      render: (v: string) => <Tag color={toolTypeColor[v] || 'default'}>{v}</Tag>,
    },
    { title: '端点', dataIndex: 'endpoint', key: 'endpoint', ellipsis: true, render: (v) => v || '-' },
    { title: '超时(ms)', dataIndex: 'timeoutMs', key: 'timeoutMs', width: 100, render: (v) => v ?? '-' },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 90,
      render: (v: number) => <Tag color={v === 1 ? 'green' : 'default'}>{v === 1 ? '启用' : '禁用'}</Tag>,
    },
    { title: '排序', dataIndex: 'sortOrder', key: 'sortOrder', width: 80, render: (v) => v ?? 0 },
  ]

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between' }}>
        <h3 style={{ margin: 0 }}>工具管理</h3>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={fetchTools}>刷新</Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>添加工具</Button>
        </Space>
      </div>
      <Table<AgentToolDTO>
        columns={columns}
        dataSource={tools}
        rowKey="toolId"
        loading={loading}
        pagination={false}
        size="small"
      />
      <Modal
        title="添加工具"
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        confirmLoading={submitting}
        onOk={() => form.submit()}
        okText="创建"
        cancelText="取消"
      >
        <Form form={form} layout="vertical" onFinish={handleCreate}>
          <Form.Item label="工具名称" name="toolName" rules={[{ required: true, message: '请输入工具名称' }]}>
            <Input placeholder="请输入工具名称" />
          </Form.Item>
          <Form.Item label="描述" name="description">
            <Input.TextArea rows={2} placeholder="请输入工具描述" />
          </Form.Item>
          <Form.Item label="工具类型" name="toolType" rules={[{ required: true, message: '请选择工具类型' }]}>
            <Select
              options={[
                { value: 'api', label: 'API' },
                { value: 'code', label: 'Code' },
                { value: 'function', label: 'Function' },
                { value: 'workflow', label: 'Workflow' },
              ]}
            />
          </Form.Item>
          <Form.Item label="端点" name="endpoint">
            <Input placeholder="https://api.example.com/invoke" />
          </Form.Item>
          <Form.Item label="超时时间(ms)" name="timeoutMs">
            <Input type="number" />
          </Form.Item>
          <Form.Item label="排序" name="sortOrder">
            <Input type="number" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}

function ExecutionsTab({ agentId }: { agentId: string }) {
  const [data, setData] = useState<AgentExecutionDTO[]>([])
  const [loading, setLoading] = useState(false)
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(10)

  const fetchExecutions = useCallback(async () => {
    setLoading(true)
    try {
      const result = await get<PageResult<AgentExecutionDTO>>(`/agents/executions/agent/${agentId}?page=${page}&size=${pageSize}`)
      if (result.success) {
        setData(result.data.records)
        setTotal(result.data.total)
      } else {
        message.error(result.message)
      }
    } catch (error: any) {
      message.error(error.message || '获取执行记录失败')
    } finally {
      setLoading(false)
    }
  }, [agentId, page, pageSize])

  useEffect(() => {
    fetchExecutions()
  }, [fetchExecutions])

  const columns: ColumnsType<AgentExecutionDTO> = [
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 110,
      render: (v: string) => <Tag color={executionStatusColor[v] || 'default'}>{v}</Tag>,
    },
    { title: 'Token 用量', dataIndex: 'tokensUsed', key: 'tokensUsed', width: 110, render: (v) => v ?? '-' },
    {
      title: '开始时间', dataIndex: 'startedAt', key: 'startedAt', width: 170,
      render: (v?: number) => (v ? dayjs(v).format('YYYY-MM-DD HH:mm:ss') : '-'),
    },
    {
      title: '完成时间', dataIndex: 'completedAt', key: 'completedAt', width: 170,
      render: (v?: number) => (v ? dayjs(v).format('YYYY-MM-DD HH:mm:ss') : '-'),
    },
    { title: '耗时(ms)', dataIndex: 'durationMs', key: 'durationMs', width: 100, render: (v) => v ?? '-' },
  ]

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between' }}>
        <h3 style={{ margin: 0 }}>执行记录</h3>
        <Button icon={<ReloadOutlined />} onClick={fetchExecutions}>刷新</Button>
      </div>
      <Table<AgentExecutionDTO>
        columns={columns}
        dataSource={data}
        rowKey="executionId"
        loading={loading}
        size="small"
        expandable={{
          expandedRowRender: (record) => (
            <div style={{ display: 'flex', gap: 24 }}>
              <div style={{ flex: 1 }}>
                <div style={{ fontWeight: 600, marginBottom: 4 }}>输入</div>
                <Input.TextArea value={record.input ?? ''} readOnly rows={6} style={{ fontFamily: 'monospace' }} />
              </div>
              <div style={{ flex: 1 }}>
                <div style={{ fontWeight: 600, marginBottom: 4 }}>输出</div>
                <Input.TextArea value={record.output ?? ''} readOnly rows={6} style={{ fontFamily: 'monospace' }} />
                {record.errorMessage && (
                  <div style={{ color: 'red', marginTop: 8 }}>错误: {record.errorMessage}</div>
                )}
              </div>
            </div>
          ),
        }}
        pagination={{
          current: page,
          pageSize,
          total,
          showSizeChanger: true,
          showTotal: (t) => `共 ${t} 条`,
          onChange: (p, s) => { setPage(p); setPageSize(s) },
        }}
      />
    </div>
  )
}

export default function AgentDetail() {
  const { agentId } = useParams<{ agentId: string }>()
  const navigate = useNavigate()
  const [agent, setAgent] = useState<AgentDTO | null>(null)
  const [loading, setLoading] = useState(true)
  const [activeKey, setActiveKey] = useState<TabKey>('basic')

  const fetchAgent = useCallback(async () => {
    if (!agentId) return
    setLoading(true)
    try {
      const result = await get<AgentDTO>(`/agents/${agentId}`)
      if (result.success) {
        setAgent(result.data)
      } else {
        message.error(result.message)
      }
    } catch (error: any) {
      message.error(error.message || '获取 Agent 详情失败')
    } finally {
      setLoading(false)
    }
  }, [agentId])

  useEffect(() => {
    fetchAgent()
  }, [fetchAgent])

  if (loading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '60vh' }}>
        <Spin size="large" />
      </div>
    )
  }

  if (!agent) {
    return (
      <div style={{ textAlign: 'center', padding: '60px 0' }}>
        <div style={{ color: '#999', marginBottom: 16 }}>Agent 不存在或已被删除</div>
        <Button onClick={() => navigate('/agents')}>返回列表</Button>
      </div>
    )
  }

  const statusColor = agent.status === 1 ? 'green' : 'default'

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', marginBottom: 16, gap: 12 }}>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/agents')}>返回</Button>
        <h2 style={{ margin: 0 }}>{agent.agentName}</h2>
        <Tag color={statusColor}>{agent.statusDesc || (agent.status === 1 ? '启用' : '禁用')}</Tag>
      </div>

      <Tabs
        activeKey={activeKey}
        onChange={(k) => setActiveKey(k as TabKey)}
        items={[
          { key: 'basic', label: '基本信息', children: <BasicInfoTab agent={agent} onSaved={setAgent} /> },
          { key: 'model', label: '模型配置', children: <ModelConfigTab agent={agent} onSaved={setAgent} /> },
          { key: 'tools', label: '工具管理', children: <ToolsTab agentId={agent.agentId} /> },
          { key: 'executions', label: '执行记录', children: <ExecutionsTab agentId={agent.agentId} /> },
        ]}
      />
    </div>
  )
}