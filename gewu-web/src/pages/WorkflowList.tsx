import { useState, useEffect, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { Table, Button, message, Tag, Space, Tabs, Modal, Input } from 'antd'
import { PlusOutlined, ReloadOutlined, EditOutlined, SendOutlined, PlayCircleOutlined, EyeOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import { get, post, WorkflowDTO, WorkflowInstanceDTO, PageResult } from '../services/api'
import dayjs from 'dayjs'

const statusMap: Record<number, { text: string; color: string }> = {
  0: { text: '草稿', color: 'blue' },
  1: { text: '已发布', color: 'green' },
  2: { text: '已归档', color: 'default' },
}

const instanceStatusMap: Record<string, { text: string; color: string }> = {
  running: { text: '运行中', color: 'blue' },
  completed: { text: '已完成', color: 'green' },
  suspended: { text: '已挂起', color: 'orange' },
  terminated: { text: '已终止', color: 'red' },
  failed: { text: '已失败', color: 'red' },
}

export default function WorkflowList() {
  const navigate = useNavigate()
  const [data, setData] = useState<WorkflowDTO[]>([])
  const [loading, setLoading] = useState(false)
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(10)
  const [activeTab, setActiveTab] = useState<'workflows' | 'instances'>('workflows')
  const [instances, setInstances] = useState<WorkflowInstanceDTO[]>([])
  const [instanceLoading, setInstanceLoading] = useState(false)
  const [instanceTotal, setInstanceTotal] = useState(0)
  const [instancePage, setInstancePage] = useState(1)
  const [instancePageSize, setInstancePageSize] = useState(10)
  const [startModal, setStartModal] = useState<WorkflowDTO | null>(null)
  const [startTitle, setStartTitle] = useState('')
  const [starting, setStarting] = useState(false)

  const fetchData = useCallback(async () => {
    setLoading(true)
    try {
      const result = await get<PageResult<WorkflowDTO>>(`/workflows?page=${page}&size=${pageSize}`)
      if (result.success) {
        setData(result.data.records)
        setTotal(result.data.total)
      } else {
        message.error(result.message)
      }
    } catch (error: any) {
      message.error(error.message || '获取工作流列表失败')
    } finally {
      setLoading(false)
    }
  }, [page, pageSize])

  const fetchInstances = useCallback(async () => {
    setInstanceLoading(true)
    try {
      const result = await get<PageResult<WorkflowInstanceDTO>>(
        `/workflows/instances/my?page=${instancePage}&size=${instancePageSize}`,
      )
      if (result.success) {
        setInstances(result.data.records)
        setInstanceTotal(result.data.total)
      } else {
        message.error(result.message)
      }
    } catch (error: any) {
      message.error(error.message || '获取我的实例失败')
    } finally {
      setInstanceLoading(false)
    }
  }, [instancePage, instancePageSize])

  useEffect(() => {
    if (activeTab === 'workflows') fetchData()
    else fetchInstances()
  }, [activeTab, fetchData, fetchInstances])

  const handleStartInstance = async () => {
    if (!startModal) return
    setStarting(true)
    try {
      const result = await post<WorkflowInstanceDTO>(
        `/workflows/instances/${startModal.workflowId}/start`,
        { title: startTitle || startModal.workflowName },
      )
      if (result.success) {
        message.success('实例已启动')
        setStartModal(null)
        setStartTitle('')
        navigate(`/workflows/instances/${result.data.instanceId}`)
      } else {
        message.error(result.message)
      }
    } catch (error: any) {
      message.error(error.message || '启动实例失败')
    } finally {
      setStarting(false)
    }
  }

  const handlePublish = async (record: WorkflowDTO) => {
    try {
      const result = await post<void>(`/workflows/${record.workflowId}/publish`)
      if (result.success) {
        message.success('发布成功')
        fetchData()
      } else {
        message.error(result.message)
      }
    } catch (error: any) {
      message.error(error.message || '发布失败')
    }
  }

  const columns: ColumnsType<WorkflowDTO> = [
    {
      title: '工作流名称',
      dataIndex: 'workflowName',
      key: 'workflowName',
      render: (text) => <span style={{ color: '#1677ff' }}>{text}</span>,
    },
    { title: '描述', dataIndex: 'description', key: 'description', ellipsis: true, render: (text) => text || '-' },
    { title: '版本', dataIndex: 'version', key: 'version', width: 80, render: (v) => `v${v}` },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (value: number) => {
        const item = statusMap[value]
        return item ? <Tag color={item.color}>{item.text}</Tag> : value
      },
    },
    { title: '分类', dataIndex: 'category', key: 'category', width: 120, render: (text) => text || '-' },
    { title: '节点数', dataIndex: 'nodeCount', key: 'nodeCount', width: 90, render: (v) => v ?? 0 },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 170,
      render: (value: number) => (value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '-'),
    },
    {
      title: '操作',
      key: 'action',
      width: 240,
      render: (_, record) => (
        <Space>
          <Button
            type="link"
            size="small"
            icon={<EditOutlined />}
            onClick={() => navigate(`/workflows/${record.workflowId}/design`)}
          >
            设计
          </Button>
          {record.status === 0 && (
            <Button
              type="link"
              size="small"
              icon={<SendOutlined />}
              onClick={() => handlePublish(record)}
            >
              发布
            </Button>
          )}
          {record.status === 1 && (
            <Button
              type="link"
              size="small"
              icon={<PlayCircleOutlined />}
              onClick={() => {
                setStartTitle('')
                setStartModal(record)
              }}
            >
              启动实例
            </Button>
          )}
        </Space>
      ),
    },
  ]

  const instanceColumns: ColumnsType<WorkflowInstanceDTO> = [
    {
      title: '实例标题',
      dataIndex: 'title',
      key: 'title',
      render: (text) => <span style={{ color: '#1677ff' }}>{text || '-'}</span>,
    },
    { title: '工作流版本', dataIndex: 'workflowVersion', key: 'workflowVersion', width: 100, render: (v) => `v${v}` },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (value: string) => {
        const item = instanceStatusMap[value]
        return item ? <Tag color={item.color}>{item.text}</Tag> : value
      },
    },
    { title: '当前节点', dataIndex: 'currentNodeName', key: 'currentNodeName', width: 140, render: (t) => t || '-' },
    { title: '发起人', dataIndex: 'initiatorName', key: 'initiatorName', width: 120, render: (t) => t || '-' },
    {
      title: '开始时间',
      dataIndex: 'startedAt',
      key: 'startedAt',
      width: 170,
      render: (value: number) => (value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '-'),
    },
    {
      title: '操作',
      key: 'action',
      width: 120,
      render: (_, record) => (
        <Button
          type="link"
          size="small"
          icon={<EyeOutlined />}
          onClick={() => navigate(`/workflows/instances/${record.instanceId}`)}
        >
          查看实例
        </Button>
      ),
    },
  ]

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h2 style={{ margin: 0 }}>工作流</h2>
        <Space>
          <Button
            icon={<ReloadOutlined />}
            onClick={activeTab === 'workflows' ? fetchData : fetchInstances}
          >
            刷新
          </Button>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => navigate('/workflows/new')}
          >
            创建工作流
          </Button>
        </Space>
      </div>
      <Tabs
        activeKey={activeTab}
        onChange={(key) => setActiveTab(key as 'workflows' | 'instances')}
        items={[
          {
            key: 'workflows',
            label: '工作流定义',
            children: (
              <Table<WorkflowDTO>
                columns={columns}
                dataSource={data}
                rowKey="workflowId"
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
            ),
          },
          {
            key: 'instances',
            label: '我的实例',
            children: (
              <Table<WorkflowInstanceDTO>
                columns={instanceColumns}
                dataSource={instances}
                rowKey="instanceId"
                loading={instanceLoading}
                pagination={{
                  current: instancePage,
                  pageSize: instancePageSize,
                  total: instanceTotal,
                  showSizeChanger: true,
                  showTotal: (t) => `共 ${t} 条`,
                  onChange: (p, s) => {
                    setInstancePage(p)
                    setInstancePageSize(s)
                  },
                }}
              />
            ),
          },
        ]}
      />
      <Modal
        title="启动工作流实例"
        open={!!startModal}
        onOk={handleStartInstance}
        onCancel={() => setStartModal(null)}
        confirmLoading={starting}
        okText="启动"
        cancelText="取消"
      >
        <div style={{ marginBottom: 8, color: '#888' }}>
          工作流: {startModal?.workflowName} (v{startModal?.version})
        </div>
        <Input
          value={startTitle}
          onChange={(e) => setStartTitle(e.target.value)}
          placeholder="实例标题 (可选, 默认使用工作流名称)"
        />
      </Modal>
    </div>
  )
}