import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, ApiError, won, type Broadcast } from '../api'
import { useAuth } from '../auth'
import LiveViewer from '../components/LiveViewer'
import CommentPanel, { useComments } from '../components/CommentPanel'
import DonationPanel from '../components/DonationPanel'
import DonationList, { useDonations } from '../components/DonationList'
import { useBroadcastEvents } from '../components/useBroadcastEvents'
import { useToasts } from '../components/Toasts'
import { StatusBadge } from './HomePage'

export default function WatchPage() {
  const id = Number(useParams().id)
  const { user } = useAuth()
  const [broadcast, setBroadcast] = useState<Broadcast | null>(null)
  const [error, setError] = useState('')
  const [refreshKey, setRefreshKey] = useState(0)
  const { comments, applyCreated, applyUpdated, applyDeleted } = useComments(id)
  const { summary, applyDonation, applyAction } = useDonations(id)
  const toasts = useToasts()

  useEffect(() => {
    api<Broadcast>(`/api/broadcasts/${id}`).then(setBroadcast).catch((e) => setError(e instanceof ApiError ? e.message : '방송을 불러오지 못했습니다.'))
  }, [id])

  useBroadcastEvents(id, {
    COMMENT_CREATED: applyCreated,
    COMMENT_UPDATED: applyUpdated,
    COMMENT_DELETED: applyDeleted,
    DONATION: (d) => {
      applyDonation(d)
      setRefreshKey((k) => k + 1)
      toasts.push(<><b>{d.donorNickname}</b> 님이 {won(d.amount)} 후원!{d.actionLabel && <> → {d.actionLabel}</>}{d.message && <div>{d.message}</div>}</>)
    },
    GAME_ACTION: (a) => {
      applyAction(a)
      setRefreshKey((k) => k + 1)
      if (a.status === 'DONE') toasts.push(<>🎮 {a.target} 에게 <b>{a.label}</b> 실행 완료</>)
    },
    BROADCAST_STARTED: (b) => setBroadcast(b),
    BROADCAST_ENDED: () => setBroadcast((b) => (b ? { ...b, status: 'ENDED' } : b)),
  })

  if (error) return <div className="page"><p className="error">{error}</p><Link to="/">목록으로</Link></div>
  if (!broadcast) return <div className="page">불러오는 중…</div>
  const isOwner = user?.id === broadcast.ownerId

  return (
    <div className="page">
      <div className="watch">
        <div className="stack">
          <LiveViewer broadcastId={id} live={broadcast.status === 'LIVE'} />
          <div className="row" style={{ justifyContent: 'space-between' }}>
            <div className="stack" style={{ gap: 4 }}>
              <div className="row">
                <StatusBadge status={broadcast.status} />
                <h1>{broadcast.title}</h1>
              </div>
              <span className="muted">{broadcast.ownerNickname}{broadcast.sourceType && ` · ${broadcast.sourceType === 'SCREEN' ? 'PC 화면' : '모바일 카메라'}`}</span>
            </div>
            {isOwner && <Link className="btn" to={`/broadcasts/${id}/studio`}>송출 화면으로</Link>}
          </div>
          {broadcast.description && <p>{broadcast.description}</p>}
          {broadcast.status === 'ENDED' && <p className="notice">종료된 방송이에요. <Link to="/recordings">다시보기</Link>에서 녹화 영상을 볼 수 있어요.</p>}
        </div>
        <div className="side">
          <CommentPanel broadcastId={id} comments={comments} />
          <DonationPanel broadcastId={id} isOwner={isOwner} refreshKey={refreshKey} />
          <DonationList summary={summary} />
        </div>
      </div>
      {toasts.view}
    </div>
  )
}
