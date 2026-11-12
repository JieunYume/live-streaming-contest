import { useEffect, useRef, useState } from 'react'
import { Room, RoomEvent, Track, type RemoteTrack } from 'livekit-client'
import { api, ApiError, type JoinInfo } from '../api'

/**
 * 시청: 서버에서 시청 전용 토큰(송출 권한 없음)을 받아 LiveKit 방에 들어가 영상·소리를 재생한다.
 * 브라우저 자동재생 정책 때문에 소리는 사용자가 한 번 눌러야 켜질 수 있다.
 */
export default function LiveViewer({ broadcastId, live }: { broadcastId: number; live: boolean }) {
  const videoRef = useRef<HTMLVideoElement>(null)
  const audioBoxRef = useRef<HTMLDivElement>(null)
  const roomRef = useRef<Room | null>(null)
  const [state, setState] = useState<'idle' | 'connecting' | 'waiting' | 'playing' | 'error'>('idle')
  const [needAudio, setNeedAudio] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    if (!live) {
      setState('idle')
      return
    }
    let cancelled = false
    const room = new Room({ adaptiveStream: true, dynacast: true })
    roomRef.current = room

    const attach = (track: RemoteTrack) => {
      if (track.kind === Track.Kind.Video && videoRef.current) {
        track.attach(videoRef.current)
        setState('playing')
      } else if (track.kind === Track.Kind.Audio && audioBoxRef.current) {
        audioBoxRef.current.appendChild(track.attach())
      }
    }
    room
      .on(RoomEvent.TrackSubscribed, (track) => attach(track))
      .on(RoomEvent.TrackUnsubscribed, (track) => {
        track.detach()
        if (track.kind === Track.Kind.Video) setState('waiting')
      })
      .on(RoomEvent.AudioPlaybackStatusChanged, () => setNeedAudio(!room.canPlaybackAudio))
      .on(RoomEvent.Disconnected, () => !cancelled && setState('waiting'))

    ;(async () => {
      setState('connecting')
      try {
        const join = await api<JoinInfo>(`/api/broadcasts/${broadcastId}/viewer-token`)
        if (cancelled) return
        await room.connect(join.url, join.token)
        if (cancelled) return
        setNeedAudio(!room.canPlaybackAudio)
        room.remoteParticipants.forEach((p) =>
          p.trackPublications.forEach((pub) => pub.track && attach(pub.track as RemoteTrack)),
        )
        setState((s) => (s === 'playing' ? s : 'waiting'))
      } catch (e) {
        if (cancelled) return
        setError(e instanceof ApiError ? e.message : '방송에 연결하지 못했습니다.')
        setState('error')
      }
    })()

    return () => {
      cancelled = true
      room.disconnect()
      if (audioBoxRef.current) audioBoxRef.current.innerHTML = ''
    }
  }, [broadcastId, live])

  return (
    <div className="player">
      <video ref={videoRef} autoPlay playsInline muted={false} />
      <div ref={audioBoxRef} hidden />
      {live && <span className="badge badge-live"><span className="dot" /> LIVE</span>}
      {!live && <div className="player-overlay"><b>방송 중이 아니에요</b></div>}
      {live && state === 'connecting' && <div className="player-overlay">방송에 연결하는 중…</div>}
      {live && state === 'waiting' && <div className="player-overlay">송출 화면을 기다리는 중…</div>}
      {live && state === 'error' && <div className="player-overlay">{error}</div>}
      {live && state === 'playing' && needAudio && (
        <div className="player-overlay" style={{ background: 'rgba(0,0,0,.35)' }}>
          <button className="btn btn-primary btn-lg" onClick={() => roomRef.current?.startAudio()}>🔊 소리 켜기</button>
        </div>
      )}
    </div>
  )
}
