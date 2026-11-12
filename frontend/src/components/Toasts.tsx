import { useCallback, useState, type ReactNode } from 'react'

interface Toast {
  id: number
  content: ReactNode
}

/** 후원 알림·반응 결과를 화면 오른쪽 아래에 잠깐 띄운다 */
export function useToasts() {
  const [toasts, setToasts] = useState<Toast[]>([])
  const push = useCallback((content: ReactNode, ms = 6000) => {
    const id = Date.now() + Math.random()
    setToasts((t) => [...t, { id, content }])
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), ms)
  }, [])
  const view = (
    <div className="toasts" aria-live="polite">
      {toasts.map((t) => (
        <div key={t.id} className="toast">{t.content}</div>
      ))}
    </div>
  )
  return { push, view }
}
