import { useState, useEffect, useCallback } from 'react'
import { Table, Button, Modal, Form, Input, Select, message, Tag, Space } from 'antd'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import { get, post, SessionDTO, CreateSessionRequest, PageResult } from '../services/api'
import dayjs from 'dayjs'

const typeMap: Record<number, { text: string; color: string }> = {
  0: { text: '群聊', color: 'blue' },
  1: { text: '私聊', color: 'cyan' },
  2: { text: 'AI辅助', color: 'purple' },
}

const statusMap: Record<number, { text: string; color: string }> = {
  0: { text: '活跃', color: 'green' },
  1: { text: '空闲', color: 'default' },
  2: { text: '已关闭', color: 'red' },
}

export default function SessionList() {
  const [data, setData] = useState<SessionDTO[]>([])
  const [loading, setLoading] = useState(false)
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(10)
  const [modalOpen, setModalOpen] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [form] = Form.useForm<CreateSessionRequest>()

  const fetchData = useCallback(async () => {
    setLoading(true)
    try {
      const result = await get<PageResult<SessionDTO>>(`/sessions?page=${page}&size=${pageSize}`)
      if (result.success) {
        setData(result.data.records)
        setTotal(result.data.total)
      } else {
        message.error(result.message)
      }
    } catch (error: any) {
      message.error(error.message || '获取会话列表失败')
    } finally {
      setLoading(false)
    }
  }, [page, pageSize])

  useEffect(() => {
    fetchData()
  }, [fetchData])

  const handleCreate = async (values: CreateSessionRequest) => {
    setSubmitting(true)
    try {
      const result = await post<SessionDTO>('/sessions', values)
      if (result.success) {
        message.success('创建成功')
        setModalOpen(false)
        form.resetFields()
        fetchData()
      } else {
        message.error(result.message)
      }
    } catch (error: any) {
      message.error(error.message || '创建会话失败')
    } finally {
      setSubmitting(false)
    }
  }

  const openCreate = () => {
    form.resetFields()
    setModalOpen(true)
  }

  const columns: ColumnsType<SessionDTO> = [
    {
      title: '会话标题',
      dataIndex: 'title',
      key: 'title',
      render: (text) => <span style={{ color: '#1677ff' }}>{text}</span>,
    },
    {
      title: '类型',
      dataIndex: 'type',
      key: 'type',
      width: 110,
      render: (value: number, record) => {
        const item = typeMap[value]
        return item ? <Tag color={item.color}>{record.typeDesc || item.text}</Tag> : record.typeDesc || value
      },
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 110,
      render: (value: number, record) => {
        const item = statusMap[value]
        return item ? <Tag color={item.color}>{record.statusDesc || item.text}</Tag> : record.statusDesc || value
      },
    },
    { title: '消息数', dataIndex: 'messageCount', key: 'messageCount', width: 90, render: (v) => v ?? 0 },
    {
      title: '最近消息',
      dataIndex: 'lastMessageAt',
      key: 'lastMessageAt',
      width: 170,
      render: (value?: number) => (value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '-'),
    },
    { title: 'Agent', dataIndex: 'agent', key: 'agent', width: 120, render: (v) => v || '-' },
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
        <h2 style={{ margin: 0 }}>会话列表</h2>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={fetchData}>刷新</Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>创建会话</Button>
        </Space>
      </div>
      <Table<SessionDTO>
        columns={columns}
        dataSource={data}
        rowKey="sessionId"
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
        onRow={(record) => ({
          onClick: () => message.info(`查看会话：${record.title}（${record.sessionId}）`),
          style: { cursor: 'pointer' },
        })}
      />
      <Modal
        title="创建会话"
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
          initialValues={{ type: 2, isPublic: 0 }}
        >
          <Form.Item
            label="会话标题"
            name="title"
            rules={[{ required: true, message: '请输入会话标题' }]}
          >
            <Input placeholder="请输入会话标题" />
          </Form.Item>
          <Form.Item label="类型" name="type" rules={[{ required: true, message: '请选择类型' }]}>
            <Select
              options={[
                { value: 0, label: '群聊' },
                { value: 1, label: '私聊' },
                { value: 2, label: 'AI辅助' },
              ]}
            />
          </Form.Item>
          <Form.Item label="是否公开" name="isPublic">
            <Select
              options={[
                { value: 0, label: '私有' },
                { value: 1, label: '公开' },
              ]}
            />
          </Form.Item>
          <Form.Item label="Agent" name="agent">
            <Input placeholder="请输入关联 Agent 名称（可选）" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}
