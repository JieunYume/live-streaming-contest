import { api, getToken, API_BASE } from '../api'

const MIME_CANDIDATES = ['video/webm;codecs=vp9,opus', 'video/webm;codecs=vp8,opus', 'video/webm', 'video/mp4']
const CHUNK_MS = 4000

/**
 * 송출 중인 영상·소리를 브라우저에서 녹화해 4초 조각으로 서버에 순서대로 올린다.
 * 소리가 여러 개(탭 소리 + 마이크)면 하나로 섞어서 녹화한다.
 * 실패한 조각은 3번까지 다시 보내고, 서버는 조각 번호로 중복을 걸러낸다.
 */
export class BroadcastRecorder {
  private recorder: MediaRecorder | null = null
  private audioCtx: AudioContext | null = null
  private seq = 0
  private queue: Promise<void> = Promise.resolve()
  private recordingId: number | null = null
  failed = false

  static supported() {
    return typeof MediaRecorder !== 'undefined' && MIME_CANDIDATES.some((m) => MediaRecorder.isTypeSupported(m))
  }

  async start(broadcastId: number, video: MediaStreamTrack, audios: MediaStreamTrack[]) {
    const mimeType = MIME_CANDIDATES.find((m) => MediaRecorder.isTypeSupported(m))!
    const stream = new MediaStream([video])
    if (audios.length === 1) {
      stream.addTrack(audios[0])
    } else if (audios.length > 1) {
      this.audioCtx = new AudioContext()
      const dest = this.audioCtx.createMediaStreamDestination()
      audios.forEach((t) => this.audioCtx!.createMediaStreamSource(new MediaStream([t])).connect(dest))
      dest.stream.getAudioTracks().forEach((t) => stream.addTrack(t))
    }
    const rec = await api<{ id: number }>(`/api/broadcasts/${broadcastId}/recordings`, { method: 'POST', json: { mimeType } })
    this.recordingId = rec.id
    this.recorder = new MediaRecorder(stream, { mimeType, videoBitsPerSecond: 2_500_000 })
    this.recorder.ondataavailable = (e) => {
      if (e.data.size > 0) this.enqueue(e.data)
    }
    this.recorder.start(CHUNK_MS)
  }

  private enqueue(blob: Blob) {
    const seq = this.seq++
    const id = this.recordingId
    this.queue = this.queue.then(async () => {
      for (let attempt = 0; attempt < 3; attempt++) {
        try {
          const res = await fetch(`${API_BASE}/api/recordings/${id}/chunks?seq=${seq}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/octet-stream', Authorization: `Bearer ${getToken()}` },
            body: blob,
          })
          if (res.ok) return
        } catch {
          /* 네트워크 오류 → 재시도 */
        }
        await new Promise((r) => setTimeout(r, 1000 * (attempt + 1)))
      }
      this.failed = true
    })
  }

  /** 녹화를 멈추고 남은 조각을 다 올린 뒤 서버에 완료를 알린다 */
  async stop() {
    const rec = this.recorder
    if (!rec || !this.recordingId) return
    if (rec.state !== 'inactive') {
      await new Promise<void>((resolve) => {
        rec.addEventListener('stop', () => resolve(), { once: true })
        rec.stop()
      })
    }
    await this.queue
    await this.audioCtx?.close()
    try {
      await api(`/api/recordings/${this.recordingId}/finish`, { method: 'POST' })
    } catch {
      /* 방송 종료 시 서버가 자동으로 마무리한다 */
    }
    this.recorder = null
  }

  get active() {
    return this.recorder !== null && this.recorder.state === 'recording'
  }
}
