import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react'
import { api, getToken, setToken, type LoginResult, type User } from './api'

interface AuthState {
  user: User | null
  ready: boolean
  login: (result: LoginResult) => void
  logout: () => Promise<void>
}

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null)
  const [ready, setReady] = useState(false)

  // 새로고침해도 로그인 유지: 저장된 토큰으로 내 정보 확인
  useEffect(() => {
    if (!getToken()) {
      setReady(true)
      return
    }
    api<User>('/api/auth/me')
      .then(setUser)
      .catch(() => setToken(null))
      .finally(() => setReady(true))
  }, [])

  const login = useCallback((result: LoginResult) => {
    setToken(result.token)
    setUser(result.user)
  }, [])

  const logout = useCallback(async () => {
    try {
      await api('/api/auth/logout', { method: 'POST' }) // 서버에서 토큰 삭제
    } finally {
      setToken(null)
      setUser(null)
    }
  }, [])

  return <AuthContext.Provider value={{ user, ready, login, logout }}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('AuthProvider 밖에서 useAuth 사용')
  return ctx
}
