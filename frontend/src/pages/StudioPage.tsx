import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import {
  Room,
  RoomEvent,
  Track,
  VideoPresets,
  createLocalAudioTrack,
  createLocalScreenTracks,
  createLocalVideoTrack,
  type LocalTrack,
  type LocalVideoTrack,
} from 'livekit-client'
import { api, ApiError, won, type Broadcast, type JoinInfo, type SourceType } from '../api'
import { useAuth } from '../auth'
import CommentPanel, { useComments } from '../components/CommentPanel'
import DonationList, { useDonations } from '../components/DonationList'
import { BroadcastRecorder } from '../components/recorder'
import { useBroadcastEvents } from '../components/useBroadcastEvents'
import { useToasts } from '../components/Toasts'
import { StatusBadge } from './HomePage'

const isMobile = /Android|iPhone|iPad|iPod/i.test(navigator.userAgent)
const canShareScreen = !!navigator.mediaDevices?.getDisplayMedia

/**
 * 송출 화면 (방송 소유자만)
 * - PC: 화면 공유 + 탭/시스템 소리 + 마이크
 * - 모바일: 카메라(전면/후면) + 마이크
 * 순서: 장치 권한(화면 선택) → 서버에서 송출 토큰 발급(소유자 확인) → LiveKit 연결 → 트랙 송출
 */
export default function StudioPage() {
  const id = Number(useParams().id)
  const navigate = useNavigate()
  const { user } = useAuth()
  const [broadcast, setBroadcast] = useState<Broadcast | null>(null)
  const [source, setSource] = useState<SourceType>(isMobile || !canShareScreen ? 'CAMERA' : 'SCREEN')
  const [facing, setFacing] = useState<'user' | 'environment'>('user')
  const [useMic, setUseMic] = useState(true)
  const [record, setRecord] = useState(BroadcastRecorder.supported())
  const [phase, setPhase] = useState<'idle' | 'starting' | 'live' | 'stopping'>('idle')
  const [hasTabAudio, setHasTabAudio] = useState(false)
  const [micOn, setMicOn] = useState(true)
  const [error, setError] = useState('')
  const [edit, setEdit] = useState<{ title: string; description: string } | null>(null)
  const previewRef = useRef<HTMLVideoElement>(null)
  const roomRef = useRef<Room | null>(null)
  const tracksRef = useRef<LocalTrack[]>([])
  const recorderRef = useRef<BroadcastRecorder | null>(null)
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
      toasts.push(<><b>{d.donorNickname}</b> 님이 {won(d.amount)} 후원!{d.actionLabel && <> → {d.actionLabel}</>}{d.message && <div>{d.message}</div>}</>, 8000)
    },
    GAME_ACTION: applyAction,
    BROADCAST_ENDED: () => {
      cleanup()
      setBroadcast((b) => (b ? { ...b, status: 'ENDED' } : b))
      setPhase('idle')
    },
  })

  // 페이지를 떠나면 송출 연결을 정리한다 (방송 상태는 서버가 웹훅·유예 시간으로 정리)
  useEffect(() => () => cleanup(), [])

  function cleanup() {
    recorderRef.current?.stop()
    recorderRef.current = null
    tracksRef.current.forEach((t) => t.stop())
    tracksRef.current = []
    roomRef.current?.disconnect()
    roomRef.current = null
  }

  async function acquireTracks(): Promise<{ video: LocalVideoTrack; audios: LocalTrack[] }> {
    const tracks: LocalTrack[] = []
    let video: LocalVideoTrack
    if (source === 'SCREEN') {
      // 화면 공유 창에서 "탭 오디오 공유"(크롬) 또는 "시스템 오디오 공유"(윈도우)를 켜면 소리가 함께 들어온다
      const screen = await createLocalScreenTracks({ audio: true, systemAudio: 'include', resolution: VideoPresets.h1080.resolution })
      tracks.push(...screen)
      video = screen.find((t) => t.kind === Track.Kind.Video) as LocalVideoTrack
      setHasTabAudio(screen.some((t) => t.kind === Track.Kind.Audio))
    } else {
      video = await createLocalVideoTrack({ facingMode: facing, resolution: VideoPresets.h720.resolution })
      tracks.push(video)
      setHasTabAudio(false)
    }
    if (useMic) {
      tracks.push(await createLocalAudioTrack({ echoCancellation: true, noiseSuppression: true }))
    }
    return { video, audios: tracks.filter((t) => t.kind === Track.Kind.Audio) }
  }

  async function start() {
    setError('')
    setPhase('starting')
    try {
      const { video, audios } = await acquireTracks() // 사용자가 화면 선택을 취소하면 여기서 멈춘다 (방송 상태 그대로)
      tracksRef.current = [video, ...audios]
      if (previewRef.current) video.attach(previewRef.current)

      const join = await api<JoinInfo>(`/api/broadcasts/${id}/publish-token`, { method: 'POST', json: { sourceType: source } })
      const room = new Room({ dynacast: true })
      roomRef.current = room
      room.on(RoomEvent.Disconnected, () => {
        if (roomRef.current === room) setError('송출 연결이 끊겼어요. 다시 시작하면 같은 방송으로 이어서 송출돼요.')
      })
      await room.connect(join.url, join.token)
      for (const t of tracksRef.current) {
        await room.localParticipant.publishTrack(t, source === 'SCREEN' && t.kind === Track.Kind.Video ? { simulcast: false } : {})
      }
      // 브라우저의 "공유 중지" 버튼으로 화면 공유를 끄면 방송도 끝낸다
      video.mediaStreamTrack.addEventListener('ended', () => stop())

      if (record && BroadcastRecorder.supported()) {
        const rec = new BroadcastRecorder()
        await rec.start(id, video.mediaStreamTrack, audios.map((a) => a.mediaStreamTrack))
        recorderRef.current = rec
      }
      setBroadcast((b) => (b ? { ...b, status: 'LIVE', sourceType: source } : b))
      setMicOn(useMic)
      setPhase('live')
    } catch (e) {
      cleanup()
      setPhase('idle')
      const msg = e instanceof ApiError ? e.message : e instanceof Error && e.name === 'NotAllowedError' ? '화면·카메라·마이크 권한이 필요해요.' : '송출을 시작하지 못했습니다.'
      setError(msg)
    }
  }

  async function stop() {
    if (!roomRef.current && phase !== 'live') return
    setPhase('stopping')
    try {
      await recorderRef.current?.stop() // 남은 녹화 조각을 다 올린 뒤
      recorderRef.current = null
      const b = await api<Broadcast>(`/api/broadcasts/${id}/end`, { method: 'POST' }) // 서버가 LiveKit 방을 닫아 송출을 끊는다
      setBroadcast(b)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '종료 요청에 실패했습니다.')
    } finally {
      cleanup()
      setPhase('idle')
    }
  }

  async function toggleMic() {
    const room = roomRef.current
    if (!room) return
    const pub = room.localParticipant.getTrackPublication(Track.Source.Microphone)
    if (!pub?.track) return
    if (micOn) await pub.mute()
    else await pub.unmute()
    setMicOn(!micOn)
  }

  if (error && !broadcast) return <div className="page"><p className="error">{error}</p></div>
  if (!broadcast) return <div className="page">불러오는 중…</div>
  if (user && broadcast.ownerId !== user.id) {
    return <div className="page"><p className="error">방송 소유자만 송출할 수 있어요.</p><Link to={`/broadcasts/${id}`}>시청 화면으로</Link></div>
  }
  const ended = broadcast.status === 'ENDED'

  return (
    <div className="page">
      <div className="watch">
        <div className="stack">
          <div className="player">
            <video ref={previewRef} autoPlay playsInline muted />
            {phase === 'live' && <span className="badge badge-live"><span className="dot" /> LIVE</span>}
            {phase !== 'live' && (
              <div className="player-overlay">
                {ended ? <b>종료된 방송이에요</b> : phase === 'starting' ? '송출 준비 중…' : phase === 'stopping' ? '종료하는 중…' : <b>송출 전 미리보기 화면이에요</b>}
              </div>
            )}
          </div>

          <div className="row" style={{ justifyContent: 'space-between' }}>
            <div className="row">
              <StatusBadge status={broadcast.status} />
              <h1>{broadcast.title}</h1>
            </div>
            <Link to={`/broadcasts/${id}`} className="btn btn-sm">시청 화면 보기</Link>
          </div>

          {error && <p className="error">{error}</p>}

          {ended ? (
            <div className="card">
              <p>이 방송은 종료됐어요. 다시 방송하려면 새 방송을 만들어 주세요.</p>
              <div className="row">
                <button className="btn btn-primary" onClick={() => navigate('/broadcasts/new')}>새 방송 만들기</button>
                <Link className="btn" to="/recordings">다시보기</Link>
              </div>
            </div>
          ) : phase === 'live' ? (
            <div className="card">
              <div className="row">
                <span className="badge badge-ok">{source === 'SCREEN' ? 'PC 화면 송출 중' : '카메라 송출 중'}</span>
                {source === 'SCREEN' && <span className={`badge ${hasTabAudio ? 'badge-ok' : 'badge-warn'}`}>{hasTabAudio ? '탭/시스템 소리 포함' : '화면 소리 없음'}</span>}
                {useMic && <span className={`badge ${micOn ? 'badge-ok' : 'badge-warn'}`}>마이크 {micOn ? '켜짐' : '꺼짐'}</span>}
                {recorderRef.current && <span className="badge badge-ok">녹화 중</span>}
              </div>
              {source === 'SCREEN' && !hasTabAudio && (
                <p className="muted small">화면 소리를 함께 보내려면 종료 후 다시 시작해서, 공유 창의 "탭 오디오 공유"(크롬 탭) 또는 "시스템 오디오 공유"(윈도우 전체 화면)를 켜 주세요.</p>
              )}
              <div className="row">
                {useMic && <button className="btn" onClick={toggleMic}>{micOn ? '마이크 끄기' : '마이크 켜기'}</button>}
                <button className="btn btn-danger btn-lg" onClick={stop}>방송 종료</button>
              </div>
            </div>
          ) : (
            <div className="card">
              <h2>송출 방식</h2>
              <div className="source-pick">
                <button className={source === 'SCREEN' ? 'on' : ''} disabled={!canShareScreen} onClick={() => setSource('SCREEN')}>
                  <b>🖥 PC 화면 공유</b>
                  <span className="muted small">{canShareScreen ? '화면 + 탭/시스템 소리 + 마이크' : '이 기기에서는 화면 공유를 지원하지 않아요'}</span>
                </button>
                <button className={source === 'CAMERA' ? 'on' : ''} onClick={() => setSource('CAMERA')}>
                  <b>📱 카메라</b>
                  <span className="muted small">휴대폰 카메라 + 마이크</span>
                </button>
              </div>
              {source === 'CAMERA' && (
                <div className="row">
                  <span className="muted small">카메라</span>
                  <button className={`btn btn-sm ${facing === 'user' ? 'btn-primary' : ''}`} onClick={() => setFacing('user')}>전면</button>
                  <button className={`btn btn-sm ${facing === 'environment' ? 'btn-primary' : ''}`} onClick={() => setFacing('environment')}>후면</button>
                </div>
              )}
              <label style={{ flexDirection: 'row', alignItems: 'center' }}>
                <input id="use-mic" type="checkbox" style={{ width: 'auto' }} checked={useMic} onChange={(e) => setUseMic(e.target.checked)} />
                마이크 함께 송출
              </label>
              <label style={{ flexDirection: 'row', alignItems: 'center' }}>
                <input id="use-record" type="checkbox" style={{ width: 'auto' }} checked={record} disabled={!BroadcastRecorder.supported()}
                  onChange={(e) => setRecord(e.target.checked)} />
                녹화해서 다시보기로 남기기 {!BroadcastRecorder.supported() && <span className="muted small">(이 브라우저는 녹화 미지원)</span>}
              </label>
              {source === 'SCREEN' && (
                <p className="notice">공유 창에서 <b>크롬 탭</b>을 고르고 "탭 오디오도 공유"를 켜면 탭 소리가, 윈도우에서 <b>전체 화면</b>을 고르고 "시스템 오디오 공유"를 켜면 PC 소리가 함께 나가요.</p>
              )}
              <button className="btn btn-primary btn-lg" disabled={phase === 'starting'} onClick={start}>
                {broadcast.status === 'LIVE' ? '송출 다시 연결' : '방송 시작'}
              </button>
            </div>
          )}

          <div className="card">
            <div className="row" style={{ justifyContent: 'space-between' }}>
              <h2>방송 정보</h2>
              {!edit && <button className="btn btn-sm" onClick={() => setEdit({ title: broadcast.title, description: broadcast.description ?? '' })}>수정</button>}
            </div>
            {edit ? (
              <form
                className="stack"
                onSubmit={async (e) => {
                  e.preventDefault()
                  try {
                    setBroadcast(await api<Broadcast>(`/api/broadcasts/${id}`, { method: 'PUT', json: edit }))
                    setEdit(null)
                  } catch (err) {
                    setError(err instanceof ApiError ? err.message : '수정하지 못했습니다.')
                  }
                }}
              >
                <label>제목<input id="edit-title" value={edit.title} maxLength={100} required onChange={(e) => setEdit({ ...edit, title: e.target.value })} /></label>
                <label>설명<textarea id="edit-description" value={edit.description} maxLength={1000} onChange={(e) => setEdit({ ...edit, description: e.target.value })} /></label>
                <div className="row">
                  <button className="btn btn-primary">저장</button>
                  <button type="button" className="btn btn-ghost" onClick={() => setEdit(null)}>취소</button>
                </div>
              </form>
            ) : (
              <p className="muted">{broadcast.description || '설명이 없어요.'}</p>
            )}
            <DeleteBroadcast id={id} onDeleted={() => { cleanup(); navigate('/my') }} />
          </div>
        </div>
        <div className="side">
          <CommentPanel broadcastId={id} comments={comments} />
          <DonationList summary={summary} />
        </div>
      </div>
      {toasts.view}
    </div>
  )
}

function DeleteBroadcast({ id, onDeleted }: { id: number; onDeleted: () => void }) {
  const [confirming, setConfirming] = useState(false)
  if (!confirming) return <button className="btn btn-sm btn-danger" style={{ alignSelf: 'flex-start' }} onClick={() => setConfirming(true)}>방송 삭제</button>
  return (
    <div className="row notice">
      <span>방송을 삭제할까요? 방송 중이면 송출도 끊겨요.</span>
      <button className="btn btn-sm btn-danger" onClick={async () => { await api(`/api/broadcasts/${id}`, { method: 'DELETE' }); onDeleted() }}>삭제</button>
      <button className="btn btn-sm btn-ghost" onClick={() => setConfirming(false)}>취소</button>
    </div>
  )
}
