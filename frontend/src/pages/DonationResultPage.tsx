import { useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api, ApiError, won, type Donation } from '../api'

/** 토스 결제창이 돌려보내는 페이지. 성공이면 서버에 승인을 요청해 금액·대상 검증 후 후원을 확정한다 */
export default function DonationResultPage({ success }: { success: boolean }) {
  const [params] = useSearchParams()
  const orderId = params.get('orderId') ?? ''
  const broadcastId = params.get('broadcastId') ?? sessionStorage.getItem(`donation:${orderId}`)
  const [result, setResult] = useState<Donation | null>(null)
  const [error, setError] = useState(success ? '' : params.get('message') ?? '결제가 취소됐어요.')
  const once = useRef(false)

  useEffect(() => {
    if (!success || once.current) return
    once.current = true // StrictMode 개발 모드에서 두 번 호출 방지 (서버도 중복 승인을 막는다)
    api<Donation>('/api/donations/confirm', {
      method: 'POST',
      json: { paymentKey: params.get('paymentKey'), orderId, amount: Number(params.get('amount')) },
    })
      .then(setResult)
      .catch((e) => setError(e instanceof ApiError ? e.message : '후원을 확정하지 못했습니다.'))
  }, [success, params, orderId])

  return (
    <div className="page page-narrow">
      <div className="card">
        {result ? (
          <>
            <h1>후원 완료!</h1>
            <p><b>{won(result.amount)}</b>을 후원했어요.</p>
            {result.actionLabel && <p>게임에서 <b>{result.actionLabel}</b> 반응이 곧 실행돼요.</p>}
          </>
        ) : error ? (
          <>
            <h1>후원하지 못했어요</h1>
            <p className="error">{error}</p>
          </>
        ) : (
          <p>결제를 확인하는 중…</p>
        )}
        {broadcastId && <Link className="btn btn-primary" to={`/broadcasts/${broadcastId}`}>방송으로 돌아가기</Link>}
      </div>
    </div>
  )
}
