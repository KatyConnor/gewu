export function getToken() {
  return localStorage.getItem('accessToken')
}

export function setToken(accessToken: string, refreshToken: string) {
  localStorage.setItem('accessToken', accessToken)
  localStorage.setItem('refreshToken', refreshToken)
}

export function clearToken() {
  localStorage.removeItem('accessToken')
  localStorage.removeItem('refreshToken')
}

export function isAuthenticated() {
  return !!getToken()
}
