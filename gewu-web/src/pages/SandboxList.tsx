import { useState, useEffect, useCallback } from 'react'
import {
  Table, Modal, Form, Input, InputNumber, Select, Button, Tag, Drawer, Tabs,
  Statistic, Row, Col, Popconfirm, message, Spin, Empty, Descriptions, Progress, Space,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import {
  PlusOutlined, CaretRightOutlined, StopOutlined, DeleteOutlined, FileTextOutlined,
  ContainerOutlined, ReloadOutlined,
} from '@ant-design/icons'
import { get, post, del, SandboxDTO, CreateSandboxRequest, SandboxLogDTO } from '../services/api'
import dayjs from 'dayjs'

const statusConfig: Record<string, { text: string; color: string }> = {
  running: { text: '运行中', color: 'green' },
  stopped: { text: '已停止', color: 'default' },
  error: { text: '异常', color: 'red' },
  creating: { text: '创建中', color: 'blue' },
  starting: { text: '启动中', color: 'processing' },
  stopping: { text: '停止中', color: 'processing' },
}

const imagePresets = [
  { value: 'ubuntu:22.04', label: 'ubuntu:22.04' },
  { value: 'node:20', label: 'node:20' },
  { value: 'python:3.12', label: 'python:3.12' },
  { value: 'openjdk:21-slim', label: 'openjdk:21-slim' },
]

interface SandboxDetail extends SandboxDTO {
  cpuUsage?: number
  memoryUsage?: number
}

type TabKey = 'basic' | 'logs'

function formatMemory(mb: number): string {
  if (mb >= 1024) {
    const gb = mb / 1024
    return `${Number.isInteger(gb) ? gb : gb.toFixed(1)} GB`
  }
  return `${mb} MB`
}

function formatTime(ts?: number): string {
  return ts ? dayjs(ts).format('YYYY-MM-DD HH:mm:ss') : '-'
}

const logColumns: ColumnsType<SandboxLogDTO> = [
  { title: '操作', dataIndex: 'action', key: 'action', width: 110, render: (v: string) => <Tag>{v}</Tag> },
  {
    title: '状态', dataIndex: 'status', key: 'status', width: 100,
    render: (v: string) => <Tag color={v === 'success' ? 'green' : v === 'failed' ? 'red' : 'blue'}>{v}</Tag>,
  },
  { title: '详情', dataIndex: 'detail', key: 'detail', ellipsis: true, render: (v?: string) => v || '-' },
  { title: '操作人', dataIndex: 'operatorName', key: 'operatorName', width: 120, render: (v?: string) => v || '-' },
  {
    title: '时间', dataIndex: 'createdAt', key: 'createdAt', width: 180,
    render: (v: number) => formatTime(v),
  },
]

export default function SandboxList() {
  const [data, setData] = useState<SandboxDTO[]>([])
  const [loading, setLoading] = useState(false)
  const [modalOpen, setModalOpen] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [imageSearch, setImageSearch] = useState('')
  const [form] = Form.useForm<CreateSandboxRequest>()

  const [drawerOpen, setDrawerOpen] = useState(false)
  const [current, setCurrent] = useState<SandboxDetail | null>(null)
  const [activeTab, setActiveTab] = useState<TabKey>('basic')
  const [logs, setLogs] = useState<SandboxLogDTO[]>([])
  const [logsLoading, setLogsLoading] = useState(false)

  const [actionLoading, setActionLoading] = useState<Record<string, boolean>>({})

  const fetchData = useCallback(async (silent = false) => {
    if (!silent) setLoading(true)
    try {
      const result = await get<SandboxDTO[]>('/sandboxes')
      if (result.success) {
        setData(result.data)
      } else if (!silent) {
        message.error(result.message)
      }
    } catch (error: any) {
      if (!silent) message.error(error.message || '获取沙箱列表失败')
    } finally {
      if (!silent) setLoading(false)
    }
  }, [])

  useEffect(() => {
    fetchData()
  }, [fetchData])

  const hasTransitional = data.some((s) => s.status === 'creating' || s.status === 'starting')
  useEffect(() => {
    if (!hasTransitional) return
    const timer = setInterval(() => fetchData(true), 10000)
    return () => clearInterval(timer)
  }, [hasTransitional, fetchData])

  const fetchLogs = async (id: string) => {
    setLogsLoading(true)
    try {
      const result = await get<SandboxLogDTO[]>(`/sandboxes/${id}/logs`)
      if (result.success) {
        setLogs(result.data)
      } else {
        message.error(result.message)
      }
    } catch (error: any) {
      message.error(error.message || '获取操作日志失败')
    } finally {
      setLogsLoading(false)
    }
  }

  const openDetail = (record: SandboxDTO, tab: TabKey = 'basic') => {
    setCurrent(record)
    setActiveTab(tab)
    setDrawerOpen(true)
    setLogs([])
    fetchLogs(record.sandboxId)
  }

  const openCreate = () => {
    form.resetFields()
    setImageSearch('')
    setModalOpen(true)
  }

  const handleCreate = async (values: CreateSandboxRequest) => {
    setSubmitting(true)
    try {
      const result = await post<SandboxDTO>('/sandboxes', values)
      if (result.success) {
        message.success('创建成功，沙箱正在准备中')
        setModalOpen(false)
        form.resetFields()
        fetchData()
      } else {
        message.error(result.message)
      }
    } catch (error: any) {
      message.error(error.message || '创建沙箱失败')
    } finally {
      setSubmitting(false)
    }
  }

  const handleStart = async (id: string) => {
    setActionLoading((s) => ({ ...s, [`${id}:start`]: true }))
    try {
      const result = await post<void>(`/sandboxes/${id}/start`)
      if (result.success) {
        message.success('启动指令已发送')
        fetchData(true)
      } else {
        message.error(result.message)
      }
    } catch (error: any) {
      message.error(error.message || '启动失败')
    } finally {
      setActionLoading((s) => {
        const next = { ...s }
        delete next[`${id}:start`]
        return next
      })
    }
  }

  const handleStop = async (id: string) => {
    setActionLoading((s) => ({ ...s, [`${id}:stop`]: true }))
    try {
      const result = await post<void>(`/sandboxes/${id}/stop`)
      if (result.success) {
        message.success('停止指令已发送')
        fetchData(true)
      } else {
        message.error(result.message)
      }
    } catch (error: any) {
      message.error(error.message || '停止失败')
    } finally {
      setActionLoading((s) => {
        const next = { ...s }
        delete next[`${id}:stop`]
        return next
      })
    }
  }

  const handleDestroy = async (id: string) => {
    setActionLoading((s) => ({ ...s, [`${id}:destroy`]: true }))
    try {
      const result = await del<void>(`/sandboxes/${id}`)
      if (result.success) {
        message.success('销毁指令已发送')
        setDrawerOpen(false)
        fetchData(true)
      } else {
        message.error(result.message)
      }
    } catch (error: any) {
      message.error(error.message || '销毁失败')
    } finally {
      setActionLoading((s) => {
        const next = { ...s }
        delete next[`${id}:destroy`]
        return next
      })
    }
  }

  const imageOptions =
    imageSearch && !imagePresets.some((o) => o.value === imageSearch)
      ? [{ value: imageSearch, label: `自定义: ${imageSearch}` }, ...imagePresets]
      : imagePresets

  const stats = {
    total: data.length,
    running: data.filter((s) => s.status === 'running').length,
    stopped: data.filter((s) => s.status === 'stopped').length,
    error: data.filter((s) => s.status === 'error').length,
  }

  const columns: ColumnsType<SandboxDTO> = [
    {
      title: '沙箱名称', dataIndex: 'sandboxName', key: 'sandboxName',
      render: (text: string, record) => <a onClick={() => openDetail(record)}>{text}</a>,
    },
    {
      title: '镜像', dataIndex: 'image', key: 'image', width: 180,
      render: (v: string) => <Tag>{v}</Tag>,
    },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 110,
      render: (v: string, record) => {
        const cfg = statusConfig[v]
        return <Tag color={cfg?.color || 'default'}>{record.statusDesc || cfg?.text || v}</Tag>
      },
    },
    { title: 'CPU', dataIndex: 'cpuCores', key: 'cpuCores', width: 90, render: (v: number) => `${v} 核` },
    {
      title: '内存', dataIndex: 'memoryMb', key: 'memoryMb', width: 100,
      render: (v: number) => formatMemory(v),
    },
    {
      title: 'IP 地址', dataIndex: 'ip', key: 'ip', width: 140,
      render: (v?: string) => (v ? <span style={{ fontFamily: 'monospace' }}>{v}</span> : '-'),
    },
    {
      title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 170,
      render: (v: number) => (v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '-'),
    },
    {
      title: '操作', key: 'action', width: 230, fixed: 'right',
      render: (_value, record) => (
        <Space size={4}>
          {record.status === 'stopped' && (
            <Button
              size="small"
              type="link"
              icon={<CaretRightOutlined />}
              loading={!!actionLoading[`${record.sandboxId}:start`]}
              onClick={() => handleStart(record.sandboxId)}
            >
              启动
            </Button>
          )}
          {record.status === 'running' && (
            <Button
              size="small"
              type="link"
              icon={<StopOutlined />}
              loading={!!actionLoading[`${record.sandboxId}:stop`]}
              onClick={() => handleStop(record.sandboxId)}
            >
              停止
            </Button>
          )}
          <Button size="small" type="link" icon={<FileTextOutlined />} onClick={() => openDetail(record, 'logs')}>
            日志
          </Button>
          <Popconfirm
            title="确定销毁此沙箱？此操作不可恢复！"
            okText="销毁"
            okButtonProps={{ danger: true }}
            cancelText="取消"
            onConfirm={() => handleDestroy(record.sandboxId)}
          >
            <Button
              size="small"
              type="link"
              danger
              icon={<DeleteOutlined />}
              loading={!!actionLoading[`${record.sandboxId}:destroy`]}
            >
              销毁
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ]

  const cardStyle: React.CSSProperties = {
    background: '#fff',
    padding: 16,
    borderRadius: 8,
    border: '1px solid #f0f0f0',
  }

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h2 style={{ margin: 0 }}>沙箱管理</h2>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => fetchData()}>刷新</Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>创建沙箱</Button>
        </Space>
      </div>

      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={6}>
          <div style={cardStyle}>
            <Statistic title="沙箱总数" value={stats.total} prefix={<ContainerOutlined />} />
          </div>
        </Col>
        <Col span={6}>
          <div style={cardStyle}>
            <Statistic title="运行中" value={stats.running} valueStyle={{ color: '#52c41a' }} />
          </div>
        </Col>
        <Col span={6}>
          <div style={cardStyle}>
            <Statistic title="已停止" value={stats.stopped} valueStyle={{ color: '#8c8c8c' }} />
          </div>
        </Col>
        <Col span={6}>
          <div style={cardStyle}>
            <Statistic title="异常" value={stats.error} valueStyle={{ color: '#cf1322' }} />
          </div>
        </Col>
      </Row>

      <Table<SandboxDTO>
        columns={columns}
        dataSource={data}
        rowKey="sandboxId"
        loading={loading}
        pagination={{
          defaultPageSize: 10,
          showSizeChanger: true,
          showTotal: (t) => `共 ${t} 条`,
        }}
      />

      <Modal
        title="创建沙箱"
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        confirmLoading={submitting}
        onOk={() => form.submit()}
        okText="创建"
        cancelText="取消"
        width={560}
      >
        <Form
          form={form}
          layout="vertical"
          onFinish={handleCreate}
          initialValues={{ cpuCores: 2, memoryMb: 2048, diskMb: 10240 }}
        >
          <Form.Item
            label="沙箱名称"
            name="sandboxName"
            rules={[{ required: true, message: '请输入沙箱名称' }]}
          >
            <Input placeholder="请输入沙箱名称" />
          </Form.Item>
          <Form.Item
            label="镜像"
            name="image"
            rules={[{ required: true, message: '请选择或输入镜像' }]}
          >
            <Select
              showSearch
              placeholder="请选择或输入镜像"
              filterOption={false}
              onSearch={(v) => setImageSearch(v)}
              options={imageOptions}
            />
          </Form.Item>
          <Row gutter={16}>
            <Col span={8}>
              <Form.Item label="CPU 核心数" name="cpuCores">
                <InputNumber min={0.5} max={16} step={0.5} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item label="内存 (MB)" name="memoryMb">
                <InputNumber min={512} max={32768} step={512} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item label="磁盘 (MB)" name="diskMb">
                <InputNumber min={1024} max={102400} step={1024} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item label="环境变量" name="env" tooltip="KEY=VALUE 格式，每行一个">
            <Input.TextArea
              rows={3}
              placeholder={'KEY=VALUE 格式，每行一个\n例如：\nNODE_ENV=production\nDEBUG=true'}
            />
          </Form.Item>
          <Form.Item label="启动命令" name="cmd">
            <Input placeholder="可选，例如 /bin/bash" />
          </Form.Item>
        </Form>
      </Modal>

      <Drawer
        title="沙箱详情"
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        width={640}
      >
        {current && (
          <>
            <div style={{ marginBottom: 16, display: 'flex', alignItems: 'center', gap: 12 }}>
              <h3 style={{ margin: 0 }}>{current.sandboxName}</h3>
              <Tag color={statusConfig[current.status]?.color || 'default'}>
                {current.statusDesc || statusConfig[current.status]?.text || current.status}
              </Tag>
            </div>
            <Tabs
              activeKey={activeTab}
              onChange={(k) => setActiveTab(k as TabKey)}
              items={[
                {
                  key: 'basic',
                  label: '基本信息',
                  children: (
                    <div>
                      <Descriptions column={1} bordered size="small">
                        <Descriptions.Item label="沙箱 ID">{current.sandboxId}</Descriptions.Item>
                        <Descriptions.Item label="镜像"><Tag>{current.image}</Tag></Descriptions.Item>
                        <Descriptions.Item label="状态">
                          <Tag color={statusConfig[current.status]?.color || 'default'}>
                            {current.statusDesc || statusConfig[current.status]?.text || current.status}
                          </Tag>
                        </Descriptions.Item>
                        <Descriptions.Item label="CPU">{current.cpuCores} 核</Descriptions.Item>
                        <Descriptions.Item label="内存">{formatMemory(current.memoryMb)}</Descriptions.Item>
                        <Descriptions.Item label="磁盘">{formatMemory(current.diskMb)}</Descriptions.Item>
                        <Descriptions.Item label="IP 地址">
                          {current.ip ? <span style={{ fontFamily: 'monospace' }}>{current.ip}</span> : '-'}
                        </Descriptions.Item>
                        <Descriptions.Item label="端口映射">{current.ports || '-'}</Descriptions.Item>
                        <Descriptions.Item label="创建时间">{formatTime(current.createdAt)}</Descriptions.Item>
                        <Descriptions.Item label="启动时间">{formatTime(current.startedAt)}</Descriptions.Item>
                        <Descriptions.Item label="停止时间">{formatTime(current.stoppedAt)}</Descriptions.Item>
                      </Descriptions>
                      {(current.cpuUsage != null || current.memoryUsage != null) && (
                        <div style={{ marginTop: 16 }}>
                          <div style={{ fontWeight: 600, marginBottom: 12 }}>资源使用</div>
                          {current.cpuUsage != null && (
                            <div style={{ marginBottom: 12 }}>
                              <div style={{ marginBottom: 4 }}>CPU 使用率</div>
                              <Progress
                                percent={current.cpuUsage}
                                status={current.cpuUsage > 90 ? 'exception' : 'normal'}
                              />
                            </div>
                          )}
                          {current.memoryUsage != null && (
                            <div>
                              <div style={{ marginBottom: 4 }}>内存使用率</div>
                              <Progress
                                percent={current.memoryUsage}
                                status={current.memoryUsage > 90 ? 'exception' : 'normal'}
                              />
                            </div>
                          )}
                        </div>
                      )}
                    </div>
                  ),
                },
                {
                  key: 'logs',
                  label: '操作日志',
                  children: (
                    <div>
                      {logsLoading ? (
                        <div style={{ textAlign: 'center', padding: 40 }}><Spin /></div>
                      ) : logs.length === 0 ? (
                        <Empty description="暂无操作日志" />
                      ) : (
                        <Table<SandboxLogDTO>
                          columns={logColumns}
                          dataSource={logs}
                          rowKey="logId"
                          size="small"
                          pagination={{ pageSize: 8, showTotal: (t) => `共 ${t} 条` }}
                        />
                      )}
                    </div>
                  ),
                },
              ]}
            />
          </>
        )}
      </Drawer>
    </div>
  )
}
