import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, ApiError, type Broadcast } from '../api'

export default function NewBroadcastPage() {
  const navigate = useNavigate()
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [error, setError] = useState('')

  return (
    <div className="page page-narrow">
      <h1>새 방송</h1>
      <form
        className="card"
        onSubmit={async (e) => {
          e.preventDefault()
          setError('')
          try {
            const b = await api<Broadcast>('/api/broadcasts', { method: 'POST', json: { title, description } })
            navigate(`/broadcasts/${b.id}/studio`)
          } catch (err) {
            setError(err instanceof ApiError ? err.message : '방송을 만들지 못했습니다.')
          }
        }}
      >
        <label>
          방송 제목
          <input id="title" value={title} maxLength={100} onChange={(e) => setTitle(e.target.value)} required placeholder="예: 서버 야생 3일차" />
        </label>
        <label>
          설명
          <textarea id="description" value={description} maxLength={1000} onChange={(e) => setDescription(e.target.value)} />
        </label>
        {error && <p className="error">{error}</p>}
        <button className="btn btn-primary btn-lg">만들고 송출 화면으로</button>
      </form>
    </div>
  )
}
