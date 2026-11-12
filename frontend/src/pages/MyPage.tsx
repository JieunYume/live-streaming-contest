import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, timeAgo, type Broadcast } from '../api'
import { StatusBadge } from './HomePage'

export default function MyPage() {
  const [list, setList] = useState<Broadcast[] | null>(null)
  useEffect(() => {
    api<Broadcast[]>('/api/broadcasts/mine').then(setList).catch(() => setList([]))
  }, [])

  return (
    <div className="page">
      <div className="row" style={{ justifyContent: 'space-between' }}>
        <h1>내 방송</h1>
        <Link to="/broadcasts/new" className="btn btn-primary">새 방송</Link>
      </div>
      {list === null ? (
        <p className="muted">불러오는 중…</p>
      ) : list.length === 0 ? (
        <div className="empty">아직 만든 방송이 없어요.</div>
      ) : (
        <div className="card table-wrap">
          <table>
            <thead>
              <tr><th>상태</th><th>제목</th><th>만든 시각</th><th></th></tr>
            </thead>
            <tbody>
              {list.map((b) => (
                <tr key={b.id}>
                  <td><StatusBadge status={b.status} /></td>
                  <td>{b.title}</td>
                  <td className="muted small">{timeAgo(b.createdAt)}</td>
                  <td>
                    <div className="row">
                      <Link className="btn btn-sm" to={`/broadcasts/${b.id}/studio`}>{b.status === 'ENDED' ? '관리' : '송출 화면'}</Link>
                      <Link className="btn btn-sm btn-ghost" to={`/broadcasts/${b.id}`}>시청 화면</Link>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
