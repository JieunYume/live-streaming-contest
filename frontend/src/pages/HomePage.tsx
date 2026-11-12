import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, timeAgo, type Broadcast, type BroadcastStatus } from '../api'

const FILTERS: { key: BroadcastStatus | 'ALL'; label: string }[] = [
  { key: 'ALL', label: '전체' },
  { key: 'LIVE', label: '방송 중' },
  { key: 'READY', label: '준비 중' },
  { key: 'ENDED', label: '종료' },
]

export function StatusBadge({ status }: { status: BroadcastStatus }) {
  if (status === 'LIVE') return <span className="badge badge-live"><span className="dot" /> LIVE</span>
  if (status === 'READY') return <span className="badge">준비 중</span>
  return <span className="badge">종료</span>
}

export default function HomePage() {
  const [filter, setFilter] = useState<BroadcastStatus | 'ALL'>('ALL')
  const [list, setList] = useState<Broadcast[] | null>(null)

  useEffect(() => {
    const load = () =>
      api<Broadcast[]>(`/api/broadcasts${filter === 'ALL' ? '' : `?status=${filter}`}`).then(setList).catch(() => setList([]))
    load()
    const t = setInterval(load, 15_000)
    return () => clearInterval(t)
  }, [filter])

  return (
    <div className="page">
      <div className="row" style={{ justifyContent: 'space-between' }}>
        <h1>방송</h1>
        <div className="tabs">
          {FILTERS.map((f) => (
            <button key={f.key} className={filter === f.key ? 'on' : ''} onClick={() => setFilter(f.key)}>{f.label}</button>
          ))}
        </div>
      </div>
      {list === null ? (
        <p className="muted">불러오는 중…</p>
      ) : list.length === 0 ? (
        <div className="empty">방송이 없어요. 로그인하고 첫 방송을 시작해 보세요.</div>
      ) : (
        <div className="grid">
          {list.map((b) => (
            <Link key={b.id} to={`/broadcasts/${b.id}`} className="bc-card">
              <div className="bc-thumb">
                <StatusBadge status={b.status} />
                {b.sourceType === 'CAMERA' ? 'CAMERA' : b.sourceType === 'SCREEN' ? 'SCREEN' : 'BLOCKCAST'}
              </div>
              <div className="bc-body">
                <span className="bc-title">{b.title}</span>
                <span className="muted small">{b.ownerNickname} · {timeAgo(b.startedAt ?? b.createdAt)}</span>
              </div>
            </Link>
          ))}
        </div>
      )}
    </div>
  )
}
