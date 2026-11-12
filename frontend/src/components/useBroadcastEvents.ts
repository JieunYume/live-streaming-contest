import { useEffect, useRef } from 'react'
import { API_BASE } from '../api'

export type BroadcastEventHandlers = Partial<Record<
  'COMMENT_CREATED' | 'COMMENT_UPDATED' | 'COMMENT_DELETED' | 'DONATION' | 'GAME_ACTION' | 'BROADCAST_STARTED' | 'BROADCAST_ENDED',
  (data: any) => void
>>

/** 방송별 실시간 이벤트(SSE) 구독. 연결이 끊기면 브라우저가 자동으로 다시 연결한다 */
export function useBroadcastEvents(broadcastId: number | undefined, handlers: BroadcastEventHandlers) {
  const ref = useRef(handlers)
  ref.current = handlers

  useEffect(() => {
    if (!broadcastId) return
    const es = new EventSource(`${API_BASE}/api/broadcasts/${broadcastId}/events`)
    const types = ['COMMENT_CREATED', 'COMMENT_UPDATED', 'COMMENT_DELETED', 'DONATION', 'GAME_ACTION', 'BROADCAST_STARTED', 'BROADCAST_ENDED'] as const
    for (const type of types) {
      es.addEventListener(type, (e) => {
        try {
          ref.current[type]?.(JSON.parse((e as MessageEvent).data))
        } catch {
          /* 형식이 다른 이벤트는 무시 */
        }
      })
    }
    return () => es.close()
  }, [broadcastId])
}
