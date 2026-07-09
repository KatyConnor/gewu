import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Form, Input, Button, Card, message } from 'antd'
import { UserOutlined, LockOutlined } from '@ant-design/icons'
import { post, LoginRequest, RegisterRequest, TokenDTO } from '../services/api'

export default function Login() {
  const navigate = useNavigate()
  const [loading, setLoading] = useState(false)
  const [isLogin, setIsLogin] = useState(true)

  const handleLogin = async (values: LoginRequest) => {
    setLoading(true)
    try {
      const result = await post<TokenDTO>('/auth/login', values)
      if (result.success) {
        localStorage.setItem('accessToken', result.data.accessToken)
        localStorage.setItem('refreshToken', result.data.refreshToken)
        message.success('登录成功')
        navigate('/')
      } else {
        message.error(result.message)
      }
    } catch (error: any) {
      message.error(error.message || '登录失败')
    } finally {
      setLoading(false)
    }
  }

  const handleRegister = async (values: RegisterRequest) => {
    setLoading(true)
    try {
      const result = await post<TokenDTO>('/auth/register', values)
      if (result.success) {
        localStorage.setItem('accessToken', result.data.accessToken)
        localStorage.setItem('refreshToken', result.data.refreshToken)
        message.success('注册成功')
        navigate('/')
      } else {
        message.error(result.message)
      }
    } catch (error: any) {
      message.error(error.message || '注册失败')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div style={{ 
      height: '100vh', 
      display: 'flex', 
      justifyContent: 'center', 
      alignItems: 'center',
      background: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)'
    }}>
      <Card style={{ width: 400, borderRadius: 8, boxShadow: '0 4px 20px rgba(0,0,0,0.1)' }}>
        <h2 style={{ textAlign: 'center', marginBottom: 24 }}>
          格物平台
        </h2>
        <Form
          name="login"
          initialValues={{ remember: true }}
          onFinish={isLogin ? handleLogin : handleRegister}
          autoComplete="off"
          layout="vertical"
        >
          <Form.Item
            label="用户名"
            name="username"
            rules={[{ required: true, message: '请输入用户名' }]}
          >
            <Input prefix={<UserOutlined />} placeholder="用户名" />
          </Form.Item>

          {!isLogin && (
            <Form.Item
              label="邮箱"
              name="email"
              rules={[
                { required: true, message: '请输入邮箱' },
                { type: 'email', message: '请输入有效的邮箱地址' },
              ]}
            >
              <Input placeholder="邮箱" />
            </Form.Item>
          )}

          {!isLogin && (
            <Form.Item
              label="显示名称"
              name="displayName"
              rules={[{ required: true, message: '请输入显示名称' }]}
            >
              <Input placeholder="显示名称" />
            </Form.Item>
          )}

          <Form.Item
            label="密码"
            name="password"
            rules={[{ required: true, message: '请输入密码' }]}
          >
            <Input.Password prefix={<LockOutlined />} placeholder="密码" />
          </Form.Item>

          <Form.Item>
            <Button type="primary" htmlType="submit" loading={loading} block>
              {isLogin ? '登录' : '注册'}
            </Button>
          </Form.Item>

          <Form.Item style={{ textAlign: 'center', marginBottom: 0 }}>
            <Button type="link" onClick={() => setIsLogin(!isLogin)}>
              {isLogin ? '还没有账号？去注册' : '已有账号？去登录'}
            </Button>
          </Form.Item>
        </Form>
      </Card>
    </div>
  )
}
