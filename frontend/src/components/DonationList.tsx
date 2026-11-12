import { useCallback, useEffect, useState } from 'react'
import { ACTION_STATUS_LABELS, api, timeAgo, won, type ActionStatus, type Donation, type DonationSummary } from '../api'

/** 방송별 후원 내역·합계. 새 후원과 게임 반응 결과는 SSE 로 반영한다 */
export function useDonations(broadcastId: number) {
  const [summary, setSummary] = useState<DonationSummary>({ total: 0, count: 0, items: [] })
  const reload = useCallback(() => {
    api<DonationSummary>(`/api/broadcasts/${broadcastId}/donations`).then(setSummary).catch(() => {})
  }, [broadcastId])
  useEffect(reload, [reload])
  return {
    summary,
    applyDonation: (d: Donation) =>
      setSummary((s) => (s.items.some((x) => x.id === d.id) ? s : { total: s.total + d.amount, count: s.count + 1, items: [d, ...s.items] })),
    applyAction: (a: { donationId: number; status: ActionStatus }) =>
      setSummary((s) => ({ ...s, items: s.items.map((x) => (x.id === a.donationId ? { ...x, actionStatus: a.status } : x)) })),
  }
}

export default function DonationList({ summary }: { summary: DonationSummary }) {
  return (
    <div className="card">
      <div className="row" style={{ justifyContent: 'space-between' }}>
        <h2>후원 내역</h2>
        <span className="muted small">{summary.count}건</span>
      </div>
      <div>
        <span className="muted small">합계 </span>
        <span className="total">{won(summary.total)}</span>
      </div>
      <div className="don-list">
        {summary.items.length === 0 && <p className="muted small">아직 후원이 없어요.</p>}
        {summary.items.map((d) => (
          <div key={d.id} className="don-item">
            <div className="row" style={{ justifyContent: 'space-between' }}>
              <b>{d.donorNickname}</b>
              <span>{won(d.amount)}</span>
            </div>
            {d.message && <div>{d.message}</div>}
            <div className="row small muted">
              <span>{timeAgo(d.paidAt)}</span>
              {d.actionLabel && (
                <span className={`badge ${d.actionStatus === 'DONE' ? 'badge-ok' : d.actionStatus === 'FAILED' ? 'badge-warn' : ''}`}>
                  {d.actionLabel} · {d.actionStatus ? ACTION_STATUS_LABELS[d.actionStatus] : ''}
                </span>
              )}
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}
