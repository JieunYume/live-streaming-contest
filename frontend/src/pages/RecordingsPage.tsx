import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, timeAgo, type Recording } from '../api'

export const duration = (r: Recording) => {
  if (!r.endedAt) return ''
  const sec = Math.round((new Date(r.endedAt).getTime() - new Date(r.startedAt).getTime()) / 1000)
  return `${Math.floor(sec / 60)}:${String(sec % 60).padStart(2, '0')}`
}

export default function RecordingsPage() {
  const [list, setList] = useState<Recording[] | null>(null)
  useEffect(() => {
    api<Recording[]>('/api/recordings').then(setList).catch(() => setList([]))
  }, [])

  return (
    <div className="page">
      <h1>다시보기</h1>
      {list === null ? (
        <p className="muted">불러오는 중…</p>
      ) : list.length === 0 ? (
        <div className="empty">녹화된 방송이 없어요. 송출할 때 "녹화해서 다시보기로 남기기"를 켜면 여기에 쌓여요.</div>
      ) : (
        <div className="grid">
          {list.map((r) => (
            <Link key={r.id} to={`/recordings/${r.id}`} className="bc-card">
              <div className="bc-thumb">
                <span className="badge">{duration(r)}</span>
                REPLAY
              </div>
              <div className="bc-body">
                <span className="bc-title">{r.title}</span>
                <span className="muted small">{r.ownerNickname} · {timeAgo(r.startedAt)} · {(r.sizeBytes / 1024 / 1024).toFixed(1)}MB</span>
              </div>
            </Link>
          ))}
        </div>
      )}
    </div>
  )
}
