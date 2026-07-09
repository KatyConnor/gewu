import { useState, useEffect, useCallback } from 'react'
import { Table, Button, Modal, Form, Input, Select, message, Tag, Space } from 'antd'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import { useNavigate } from 'react-router-dom'
import { get, post, AgentDTO, CreateAgentRequest, PageResult } from '../services/api'
import dayjs from 'dayjs'

const statusMap: Record<number, { text: string; color: string }> = {
  0: { text: '启用', color: 'green' },
  1: { text: '禁用', color: 'default' },
  2: { text: '草稿', color: 'orange' },
}

const providerLabels: Record<string, string> = {
  alibaba: '阿里通义',
  deepseek: 'DeepSeek',
  openai: 'OpenAI',
}

export default function AgentList() {
  const navigate = useNavigate()
  const [data, setData] = useState<AgentDTO[]>([])
  const [loading, setLoading] = useState(false)
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(10)
  const [modalOpen, setModalOpen] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [form] = Form.useForm<CreateAgentRequest>()

  const fetchData = useCallback(async () => {
    setLoading(true)
    try {
      const result = await get<PageResult<AgentDTO>>(`/agents?page=${page}&size=${pageSize}`)
      if (result.success) {
        setData(result.data.records)
        setTotal(result.data.total)
      } else {
        message.error(result.message)
      }
    } catch (error: any) {
      message.error(error.message || '获取 Agent 列表失败')
    } finally {
      setLoading(false)
    }
  }, [page, pageSize])

  useEffect(() => {
    fetchData()
  }, [fetchData])

  const handleCreate = async (values: CreateAgentRequest) => {
    setSubmitting(true)
    try {
      const result = await post<AgentDTO>('/agents', values)
      if (result.success) {
        message.success('创建成功')
        setModalOpen(false)
        form.resetFields()
        fetchData()
      } else {
        message.error(result.message)
      }
    } catch (error: any) {
      message.error(error.message || '创建 Agent 失败')
    } finally {
      setSubmitting(false)
    }
  }

  const openCreate = () => {
    form.resetFields()
    setModalOpen(true)
  }

  const columns: ColumnsType<AgentDTO> = [
    { title: 'Agent 名称', dataIndex: 'agentName', key: 'agentName', render: (text: string, record) => (
        <a onClick={() => navigate(`/agents/${record.agentId}`)}>{text}</a>
      ),
    },
    { title: '描述', dataIndex: 'description', key: 'description', ellipsis: true, render: (text) => text || '-' },
    {
      title: '模型提供商',
      dataIndex: 'modelProvider',
      key: 'modelProvider',
      width: 130,
      render: (value: string) => providerLabels[value] || value,
    },
    { title: '模型名称', dataIndex: 'modelName', key: 'modelName', width: 160 },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (value: number, record) => {
        const item = statusMap[value]
        return item ? <Tag color={item.color}>{record.statusDesc || item.text}</Tag> : record.statusDesc || value
      },
    },
    { title: '版本', dataIndex: 'version', key: 'version', width: 80, render: (v) => (v != null ? `v${v}` : '-') },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 170,
      render: (value: number) => (value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '-'),
    },
  ]

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h2 style={{ margin: 0 }}>Agent 列表</h2>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={fetchData}>刷新</Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>创建 Agent</Button>
        </Space>
      </div>
      <Table<AgentDTO>
        columns={columns}
        dataSource={data}
        rowKey="agentId"
        loading={loading}
        pagination={{
          current: page,
          pageSize: pageSize,
          total: total,
          showSizeChanger: true,
          showTotal: (t) => `共 ${t} 条`,
          onChange: (p, s) => {
            setPage(p)
            setPageSize(s)
          },
        }}
      />
      <Modal
        title="创建 Agent"
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        confirmLoading={submitting}
        onOk={() => form.submit()}
        okText="创建"
        cancelText="取消"
      >
        <Form
          form={form}
          layout="vertical"
          onFinish={handleCreate}
          initialValues={{ modelProvider: 'deepseek' }}
        >
          <Form.Item
            label="Agent 名称"
            name="agentName"
            rules={[{ required: true, message: '请输入 Agent 名称' }]}
          >
            <Input placeholder="请输入 Agent 名称" />
          </Form.Item>
          <Form.Item label="描述" name="description">
            <Input.TextArea rows={2} placeholder="请输入 Agent 描述" />
          </Form.Item>
          <Form.Item
            label="模型提供商"
            name="modelProvider"
            rules={[{ required: true, message: '请选择模型提供商' }]}
          >
            <Select
              options={[
                { value: 'alibaba', label: '阿里通义' },
                { value: 'deepseek', label: 'DeepSeek' },
                { value: 'openai', label: 'OpenAI' },
              ]}
            />
          </Form.Item>
          <Form.Item
            label="模型名称"
            name="modelName"
            rules={[{ required: true, message: '请输入模型名称' }]}
          >
            <Input placeholder="例如：qwen-max、deepseek-chat、gpt-4o" />
          </Form.Item>
          <Form.Item label="系统提示词" name="systemPrompt">
            <Input.TextArea rows={4} placeholder="请输入系统提示词（System Prompt）" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}
