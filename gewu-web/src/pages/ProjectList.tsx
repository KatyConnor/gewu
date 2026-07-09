import { useState, useEffect, useCallback } from 'react'
import { Table, Button, Modal, Form, Input, Select, message, Tag, Space } from 'antd'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import { get, post, ProjectDTO, CreateProjectRequest, PageResult } from '../services/api'
import dayjs from 'dayjs'

const visibilityMap: Record<number, { text: string; color: string }> = {
  0: { text: '私有', color: 'orange' },
  1: { text: '公开', color: 'green' },
}

const statusMap: Record<number, { text: string; color: string }> = {
  0: { text: '活跃', color: 'green' },
  1: { text: '归档', color: 'default' },
  2: { text: '已删除', color: 'red' },
}

export default function ProjectList() {
  const [data, setData] = useState<ProjectDTO[]>([])
  const [loading, setLoading] = useState(false)
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(10)
  const [modalOpen, setModalOpen] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [form] = Form.useForm<CreateProjectRequest>()

  const fetchData = useCallback(async () => {
    setLoading(true)
    try {
      const result = await get<PageResult<ProjectDTO>>(`/projects?page=${page}&size=${pageSize}`)
      if (result.success) {
        setData(result.data.records)
        setTotal(result.data.total)
      } else {
        message.error(result.message)
      }
    } catch (error: any) {
      message.error(error.message || '获取项目列表失败')
    } finally {
      setLoading(false)
    }
  }, [page, pageSize])

  useEffect(() => {
    fetchData()
  }, [fetchData])

  const handleCreate = async (values: CreateProjectRequest) => {
    setSubmitting(true)
    try {
      const result = await post<ProjectDTO>('/projects', values)
      if (result.success) {
        message.success('创建成功')
        setModalOpen(false)
        form.resetFields()
        fetchData()
      } else {
        message.error(result.message)
      }
    } catch (error: any) {
      message.error(error.message || '创建项目失败')
    } finally {
      setSubmitting(false)
    }
  }

  const openCreate = () => {
    form.resetFields()
    setModalOpen(true)
  }

  const columns: ColumnsType<ProjectDTO> = [
    {
      title: '项目名称',
      dataIndex: 'projectName',
      key: 'projectName',
      render: (text) => <span style={{ color: '#1677ff' }}>{text}</span>,
    },
    { title: '描述', dataIndex: 'description', key: 'description', ellipsis: true, render: (text) => text || '-' },
    {
      title: '可见性',
      dataIndex: 'visibility',
      key: 'visibility',
      width: 100,
      render: (value: number) => {
        const item = visibilityMap[value]
        return item ? <Tag color={item.color}>{item.text}</Tag> : value
      },
    },
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
    { title: '成员数', dataIndex: 'memberCount', key: 'memberCount', width: 90, render: (v) => v ?? 0 },
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
        <h2 style={{ margin: 0 }}>项目列表</h2>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={fetchData}>刷新</Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>创建项目</Button>
        </Space>
      </div>
      <Table<ProjectDTO>
        columns={columns}
        dataSource={data}
        rowKey="projectId"
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
          onClick: () => message.info(`查看项目：${record.projectName}（${record.projectId}）`),
          style: { cursor: 'pointer' },
        })}
      />
      <Modal
        title="创建项目"
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
          initialValues={{ visibility: 0 }}
        >
          <Form.Item
            label="项目名称"
            name="projectName"
            rules={[{ required: true, message: '请输入项目名称' }]}
          >
            <Input placeholder="请输入项目名称" />
          </Form.Item>
          <Form.Item label="描述" name="description">
            <Input.TextArea rows={3} placeholder="请输入项目描述" />
          </Form.Item>
          <Form.Item label="可见性" name="visibility">
            <Select
              options={[
                { value: 0, label: '私有' },
                { value: 1, label: '公开' },
              ]}
            />
          </Form.Item>
          <Form.Item label="工作目录" name="worktree">
            <Input placeholder="请输入工作目录（git worktree 路径）" />
          </Form.Item>
          <Form.Item label="版本控制" name="vcs">
            <Select
              allowClear
              placeholder="请选择版本控制系统"
              options={[
                { value: 'git', label: 'Git' },
                { value: 'svn', label: 'SVN' },
              ]}
            />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}
