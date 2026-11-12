import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { loadTossPayments } from '@tosspayments/tosspayments-sdk'
import { api, ApiError, won, type DonationOptions, type PrepareResult } from '../api'
import { useAuth } from '../auth'

const QUICK = [1000, 5000, 10000, 30000]

/**
 * 후원하기: 금액 선택 → 서버에 주문 생성(prepare) → 토스 결제창 → 성공 페이지에서 승인(confirm)
 * 마크 연동된 스트리머면 금액별 게임 반응·대기열을 보여 주고, 실행할 수 없으면(서버 끊김·대상 오프라인) 버튼을 막는다.
 */
export default function DonationPanel({ broadcastId, isOwner, refreshKey }: { broadcastId: number; isOwner: boolean; refreshKey: number }) {
  const { user } = useAuth()
  const [options, setOptions] = useState<DonationOptions | null>(null)
  const [amount, setAmount] = useState(10000)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    const load = () => api<DonationOptions>(`/api/broadcasts/${broadcastId}/donation-options`).then(setOptions).catch(() => {})
    load()
    const t = setInterval(load, 10_000) // 접속 상태·대기열 갱신
    return () => clearInterval(t)
  }, [broadcastId, refreshKey])

  const matched = useMemo(
    () => options?.rules.filter((r) => r.minAmount <= amount).sort((a, b) => b.minAmount - a.minAmount)[0],
    [options, amount],
  )

  if (!options) return null
  const quick = [...new Set([...QUICK, ...options.rules.map((r) => r.minAmount)])].sort((a, b) => a - b)
  const blockedByGame = !!matched && !options.playerOnline
  const disabled = busy || !options.live || isOwner || blockedByGame || !options.tossClientKey

  const donate = async () => {
    setError('')
    setBusy(true)
    try {
      const order = await api<PrepareResult>('/api/donations/prepare', { method: 'POST', json: { broadcastId, amount, message: message || null } })
      sessionStorage.setItem(`donation:${order.orderId}`, String(broadcastId))
      const toss = await loadTossPayments(order.tossClientKey)
      const payment = toss.payment({ customerKey: order.customerKey })
      await payment.requestPayment({
        method: 'CARD',
        amount: { currency: 'KRW', value: order.amount },
        orderId: order.orderId,
        orderName: order.orderName,
        successUrl: `${location.origin}/donations/success?broadcastId=${broadcastId}`,
        failUrl: `${location.origin}/donations/fail?broadcastId=${broadcastId}`,
      })
    } catch (e) {
      setError(e instanceof ApiError ? e.message : e instanceof Error ? e.message : '결제를 시작하지 못했습니다.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="card">
      <h2>후원하기</h2>
      {options.minecraftLinked && (
        <div className="stack small">
          <div className="row">
            <span className="muted">마크 서버</span>
            <b>{options.serverName}</b>
            <span className={`badge ${options.playerOnline ? 'badge-ok' : 'badge-warn'}`}>
              <span className="dot" /> {options.mcName} {options.playerOnline ? '접속 중' : options.pluginConnected ? '오프라인' : '서버 연결 끊김'}
            </span>
          </div>
          <div className="rule-list">
            {options.rules.map((r) => (
              <div key={r.minAmount} className={`rule-item ${matched?.minAmount === r.minAmount ? 'on' : ''}`}>
                <span>{won(r.minAmount)} 이상 · {r.label}</span>
                <span className="muted">{r.waiting > 0 ? `대기 ${r.waiting}건 · 약 ${r.etaSeconds}초` : r.cooldownSeconds > 0 ? `쿨타임 ${r.cooldownSeconds}초` : '바로 실행'}</span>
              </div>
            ))}
          </div>
        </div>
      )}
      <div className="amounts">
        {quick.map((q) => (
          <button key={q} className={`btn btn-sm ${amount === q ? 'btn-primary' : ''}`} onClick={() => setAmount(q)}>{won(q)}</button>
        ))}
      </div>
      <label>
        금액 (원)
        <input id="donation-amount" type="number" min={options.minAmount} max={options.maxAmount} step={1000} value={amount}
          onChange={(e) => setAmount(Number(e.target.value))} />
      </label>
      <label>
        메시지
        <input id="donation-message" maxLength={100} placeholder="응원 메시지 (선택)" value={message} onChange={(e) => setMessage(e.target.value)} />
      </label>
      {matched && (
        <p className="small">
          이 금액이면 게임에서 <b>{matched.label}</b> 반응이 실행돼요.
          {matched.waiting > 0 && ` 지금 대기 ${matched.waiting}건이 있어 약 ${matched.etaSeconds}초 뒤에 실행돼요.`}
        </p>
      )}
      {blockedByGame && <p className="error">{options.mcName} 님이 게임에 없어서 지금은 이 금액으로 후원할 수 없어요.</p>}
      {!options.live && <p className="muted small">방송 중일 때만 후원할 수 있어요.</p>}
      {isOwner && <p className="muted small">자기 방송에는 후원할 수 없어요.</p>}
      {!options.tossClientKey && <p className="muted small">결제 키가 설정되지 않아 후원이 꺼져 있어요.</p>}
      {error && <p className="error">{error}</p>}
      {user ? (
        <button className="btn btn-primary btn-lg" disabled={disabled} onClick={donate}>
          {busy ? '결제창 여는 중…' : `${won(amount)} 후원하기`}
        </button>
      ) : (
        <Link className="btn btn-primary" to={`/login?next=/broadcasts/${broadcastId}`}>로그인하고 후원하기</Link>
      )}
      <p className="muted small">토스페이먼츠 테스트 결제라 실제로 돈이 나가지 않아요.</p>
    </div>
  )
}
