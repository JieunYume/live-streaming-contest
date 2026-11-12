import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, API_BASE, ApiError, type Recording } from '../api'
import { duration } from './RecordingsPage'

export default function RecordingDetailPage() {
  const id = Number(useParams().id)
  const [rec, setRec] = useState<Recording | null>(null)
  const [error, setError] = useState('')

  useEffect(() => {
    api<Recording>(`/api/recordings/${id}`).then(setRec).catch((e) => setError(e instanceof ApiError ? e.message : '녹화를 불러오지 못했습니다.'))
  }, [id])

  if (error) return <div className="page"><p className="error">{error}</p><Link to="/recordings">목록으로</Link></div>
  if (!rec) return <div className="page">불러오는 중…</div>

  return (
    <div className="page">
      <div className="player">
        <video src={`${API_BASE}/api/recordings/${id}/video`} controls playsInline preload="metadata" />
      </div>
      <div className="stack" style={{ gap: 4 }}>
        <h1>{rec.title}</h1>
        <span className="muted">
          {rec.ownerNickname} · {new Date(rec.startedAt).toLocaleString('ko-KR')} · {duration(rec)}
        </span>
      </div>
      <div className="row">
        <Link className="btn" to={`/broadcasts/${rec.broadcastId}`}>방송 정보·댓글·후원 내역</Link>
        <Link className="btn btn-ghost" to="/recordings">다시보기 목록</Link>
      </div>
      {rec.mimeType.startsWith('video/webm') && (
        <p className="muted small">WebM 형식이라 일부 아이폰 사파리에서는 재생되지 않을 수 있어요.</p>
      )}
    </div>
  )
}
