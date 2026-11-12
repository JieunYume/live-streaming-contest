import { useEffect, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { api, ApiError, type LoginResult } from '../api'
import { useAuth } from '../auth'

interface TestAccount {
  username: string
  nickname: string
  role: string
}

export default function LoginPage() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const next = params.get('next') || '/'
  const [mode, setMode] = useState<'login' | 'signup'>('login')
  const [form, setForm] = useState({ username: '', password: '', nickname: '' })
  const [error, setError] = useState('')
  const [testAccounts, setTestAccounts] = useState<TestAccount[]>([])

  useEffect(() => {
    api<TestAccount[]>('/api/auth/test-accounts').then(setTestAccounts).catch(() => {})
  }, [])

  const done = (r: LoginResult) => {
    login(r)
    navigate(next, { replace: true })
  }

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError('')
    try {
      const r = await api<LoginResult>(`/api/auth/${mode}`, { method: 'POST', json: form })
      done(r)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '요청에 실패했습니다.')
    }
  }

  return (
    <div className="page page-narrow">
      {testAccounts.length > 0 && (
        <div className="card">
          <h2>테스트 계정으로 바로 로그인</h2>
          <p className="muted small">심사·체험용 계정이에요. 비밀번호 없이 눌러서 들어가요.</p>
          {testAccounts.map((a) => (
            <button
              key={a.username}
              className="btn"
              style={{ justifyContent: 'space-between' }}
              onClick={async () => {
                try {
                  done(await api<LoginResult>('/api/auth/test-login', { method: 'POST', json: { username: a.username } }))
                } catch (err) {
                  setError(err instanceof ApiError ? err.message : '로그인에 실패했습니다.')
                }
              }}
            >
              <b>{a.nickname}</b>
              <span className="muted small">{a.role}</span>
            </button>
          ))}
        </div>
      )}
      <form className="card" onSubmit={submit}>
        <div className="tabs">
          <button type="button" className={mode === 'login' ? 'on' : ''} onClick={() => setMode('login')}>로그인</button>
          <button type="button" className={mode === 'signup' ? 'on' : ''} onClick={() => setMode('signup')}>회원가입</button>
        </div>
        <label>
          아이디
          <input id="username" autoComplete="username" value={form.username} onChange={(e) => setForm({ ...form, username: e.target.value })} required />
        </label>
        <label>
          비밀번호
          <input id="password" type="password" autoComplete={mode === 'login' ? 'current-password' : 'new-password'} value={form.password}
            onChange={(e) => setForm({ ...form, password: e.target.value })} required minLength={mode === 'signup' ? 8 : undefined} />
        </label>
        {mode === 'signup' && (
          <label>
            닉네임
            <input id="nickname" value={form.nickname} maxLength={20} onChange={(e) => setForm({ ...form, nickname: e.target.value })} required />
          </label>
        )}
        {mode === 'signup' && <p className="muted small">아이디는 영문·숫자·_ 4~20자, 비밀번호는 8자 이상이에요.</p>}
        {error && <p className="error">{error}</p>}
        <button className="btn btn-primary btn-lg">{mode === 'login' ? '로그인' : '가입하고 시작하기'}</button>
      </form>
    </div>
  )
}
