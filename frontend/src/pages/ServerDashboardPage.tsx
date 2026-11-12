import { useCallback, useEffect, useState } from 'react'
import {
  ACTION_LABELS,
  ACTION_STATUS_LABELS,
  api,
  ApiError,
  timeAgo,
  won,
  type ActionType,
  type McAction,
  type McInvite,
  type McServer,
} from '../api'

const PARAM_HINT: Partial<Record<ActionType, string>> = {
  FOOD: '아이템 id (기본 cooked_beef)',
  SPAWN_MOB: '몹 id (기본 zombie)',
  EFFECT: '효과 id (기본 speed)',
  COMMAND: '예: give {player} diamond 3',
}

/**
 * 마크 서버 대시보드
 * - 서버장: 서버 등록 → 연동 코드로 플러그인 연결 → 화이트리스트(닉네임 + 초대할 계정) → 후원 규칙
 * - 스트리머: 받은 초대 수락
 */
export default function ServerDashboardPage() {
  const [servers, setServers] = useState<McServer[] | null>(null)
  const [invites, setInvites] = useState<McInvite[]>([])
  const [name, setName] = useState('')
  const [error, setError] = useState('')

  const load = useCallback(() => {
    api<McServer[]>('/api/mc/servers').then(setServers).catch(() => setServers([]))
    api<McInvite[]>('/api/mc/invites').then(setInvites).catch(() => {})
  }, [])

  useEffect(() => {
    load()
    const t = setInterval(load, 5000) // 플러그인 연결·접속자 갱신
    return () => clearInterval(t)
  }, [load])

  const run = async (fn: () => Promise<unknown>) => {
    setError('')
    try {
      await fn()
      load()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '요청에 실패했습니다.')
    }
  }

  return (
    <div className="page">
      <h1>마크 서버</h1>
      {error && <p className="error">{error}</p>}

      <div className="card">
        <h2>받은 초대</h2>
        <p className="muted small">서버장이 내 계정을 화이트리스트에 넣으면 여기에 떠요. 수락하면 내 방송 후원이 그 서버의 내 캐릭터로 연결돼요.</p>
        {invites.length === 0 ? (
          <p className="muted small">받은 초대가 없어요.</p>
        ) : (
          invites.map((i) => (
            <div key={i.memberId} className="row" style={{ justifyContent: 'space-between' }}>
              <span><b>{i.serverName}</b> <span className="muted">({i.ownerNickname}) · 캐릭터 {i.mcName}</span></span>
              <div className="row">
                {i.status === 'INVITED' ? (
                  <button className="btn btn-sm btn-primary" onClick={() => run(() => api(`/api/mc/invites/${i.memberId}/accept`, { method: 'POST' }))}>수락</button>
                ) : (
                  <span className="badge badge-ok">참여 중</span>
                )}
                <button className="btn btn-sm btn-ghost" onClick={() => run(() => api(`/api/mc/invites/${i.memberId}`, { method: 'DELETE' }))}>
                  {i.status === 'INVITED' ? '거절' : '나가기'}
                </button>
              </div>
            </div>
          ))
        )}
      </div>

      <form
        className="card"
        onSubmit={(e) => {
          e.preventDefault()
          run(async () => {
            await api('/api/mc/servers', { method: 'POST', json: { name } })
            setName('')
          })
        }}
      >
        <h2>내 서버 등록 (서버장)</h2>
        <div className="row">
          <input id="server-name" placeholder="서버 이름" value={name} maxLength={50} required onChange={(e) => setName(e.target.value)} style={{ flex: 1 }} />
          <button className="btn btn-primary">등록</button>
        </div>
      </form>

      {servers?.map((s) => <ServerCard key={s.id} server={s} run={run} />)}
    </div>
  )
}

function ServerCard({ server: s, run }: { server: McServer; run: (fn: () => Promise<unknown>) => Promise<void> }) {
  const [invite, setInvite] = useState({ mcName: '', username: '' })
  const [rule, setRule] = useState({ minAmount: 10000, label: '', actionType: 'FOOD' as ActionType, param: '', cooldownSeconds: 0 })
  const [actions, setActions] = useState<McAction[]>([])
  const [confirmDelete, setConfirmDelete] = useState(false)

  useEffect(() => {
    const load = () => api<McAction[]>(`/api/mc/servers/${s.id}/actions`).then(setActions).catch(() => {})
    load()
    const t = setInterval(load, 5000)
    return () => clearInterval(t)
  }, [s.id])

  return (
    <div className="card">
      <div className="row" style={{ justifyContent: 'space-between' }}>
        <div className="row">
          <h2>{s.name}</h2>
          <span className={`badge ${s.connected ? 'badge-ok' : 'badge-warn'}`}><span className="dot" /> {s.connected ? '플러그인 연결됨' : s.linked ? '연결 끊김' : '연동 전'}</span>
          {s.connected && <span className="muted small">접속 {s.onlinePlayers.length}명</span>}
        </div>
        {confirmDelete ? (
          <div className="row">
            <span className="small">서버와 규칙·기록을 모두 지울까요?</span>
            <button className="btn btn-sm btn-danger" onClick={() => run(() => api(`/api/mc/servers/${s.id}`, { method: 'DELETE' }))}>삭제</button>
            <button className="btn btn-sm btn-ghost" onClick={() => setConfirmDelete(false)}>취소</button>
          </div>
        ) : (
          <button className="btn btn-sm btn-ghost" onClick={() => setConfirmDelete(true)}>서버 삭제</button>
        )}
      </div>

      <section className="stack">
        <span className="section-title">1. 플러그인 연동</span>
        <ol className="small" style={{ margin: 0, paddingLeft: 18 }}>
          <li>Paper 서버의 <span className="kbd">plugins/</span> 폴더에 BLOCKCAST 플러그인 jar를 넣고 서버를 켜요.</li>
          <li>게임 안(관리자)에서 <span className="kbd">/blockcast link {s.linkCode ?? '코드'}</span> 를 입력해요.</li>
          <li>위 배지가 "플러그인 연결됨"으로 바뀌면 끝이에요.</li>
        </ol>
        <div className="row">
          {s.linkCode ? (
            <>
              <span className="code">{s.linkCode}</span>
              <span className="muted small">{s.linkCodeExpiresAt && `${new Date(s.linkCodeExpiresAt).toLocaleTimeString('ko-KR')}까지 유효`}</span>
            </>
          ) : (
            <span className="muted small">{s.linked ? '연동이 끝났어요. 다시 연동하려면 새 코드를 받아요.' : '코드가 만료됐어요.'}</span>
          )}
          <button className="btn btn-sm" onClick={() => run(() => api(`/api/mc/servers/${s.id}/link-code`, { method: 'POST' }))}>새 연동 코드</button>
        </div>
      </section>

      <section className="stack">
        <span className="section-title">2. 화이트리스트 · 스트리머 초대</span>
        <div className="table-wrap">
          <table>
            <thead><tr><th>마크 닉네임</th><th>플랫폼 계정</th><th>상태</th><th>게임 접속</th><th></th></tr></thead>
            <tbody>
              {s.members.length === 0 && <tr><td colSpan={5} className="muted small">아직 아무도 없어요.</td></tr>}
              {s.members.map((m) => (
                <tr key={m.id}>
                  <td><b>{m.mcName}</b></td>
                  <td>{m.nickname} <span className="muted small">@{m.username}</span></td>
                  <td>{m.status === 'ACCEPTED' ? <span className="badge badge-ok">참여 중</span> : <span className="badge">초대 대기</span>}</td>
                  <td>{m.online ? <span className="badge badge-ok"><span className="dot" /> 접속 중</span> : <span className="muted small">오프라인</span>}</td>
                  <td><button className="btn btn-sm btn-ghost" onClick={() => run(() => api(`/api/mc/servers/${s.id}/members/${m.id}`, { method: 'DELETE' }))}>빼기</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <form
          className="row"
          onSubmit={(e) => {
            e.preventDefault()
            run(async () => {
              await api(`/api/mc/servers/${s.id}/members`, { method: 'POST', json: invite })
              setInvite({ mcName: '', username: '' })
            })
          }}
        >
          <input id={`mc-name-${s.id}`} placeholder="마크 닉네임" value={invite.mcName} required onChange={(e) => setInvite({ ...invite, mcName: e.target.value })} style={{ flex: 1, minWidth: 140 }} />
          <input id={`invite-user-${s.id}`} placeholder="초대할 플랫폼 아이디" value={invite.username} required onChange={(e) => setInvite({ ...invite, username: e.target.value })} style={{ flex: 1, minWidth: 140 }} />
          <button className="btn btn-primary">초대</button>
        </form>
      </section>

      <section className="stack">
        <span className="section-title">3. 후원 규칙 (서버 공통)</span>
        <p className="muted small">후원 금액 이하에서 가장 큰 기준 금액의 규칙이 실행돼요. 쿨타임 동안 들어온 후원은 대기열에 쌓였다가 순서대로 실행돼요.</p>
        <div className="table-wrap">
          <table>
            <thead><tr><th>기준 금액</th><th>이름</th><th>반응</th><th>추가 값</th><th>쿨타임</th><th></th></tr></thead>
            <tbody>
              {s.rules.length === 0 && <tr><td colSpan={6} className="muted small">규칙이 없으면 후원은 받지만 게임 반응은 없어요.</td></tr>}
              {s.rules.map((r) => (
                <tr key={r.id}>
                  <td>{won(r.minAmount)} 이상</td>
                  <td><b>{r.label}</b></td>
                  <td>{ACTION_LABELS[r.actionType]}</td>
                  <td className="small">{r.param ?? '-'}</td>
                  <td>{r.cooldownSeconds}초</td>
                  <td><button className="btn btn-sm btn-ghost" onClick={() => run(() => api(`/api/mc/servers/${s.id}/rules/${r.id}`, { method: 'DELETE' }))}>삭제</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <form
          className="stack"
          onSubmit={(e) => {
            e.preventDefault()
            run(async () => {
              await api(`/api/mc/servers/${s.id}/rules`, { method: 'POST', json: rule })
              setRule({ ...rule, label: '', param: '' })
            })
          }}
        >
          <div className="row">
            <label style={{ flex: 1, minWidth: 120 }}>기준 금액<input id={`rule-amount-${s.id}`} type="number" min={1000} step={1000} value={rule.minAmount} onChange={(e) => setRule({ ...rule, minAmount: Number(e.target.value) })} /></label>
            <label style={{ flex: 1, minWidth: 120 }}>이름<input id={`rule-label-${s.id}`} placeholder="예: 랜덤박스" value={rule.label} maxLength={30} required onChange={(e) => setRule({ ...rule, label: e.target.value })} /></label>
            <label style={{ flex: 1, minWidth: 120 }}>
              반응
              <select id={`rule-type-${s.id}`} value={rule.actionType} onChange={(e) => setRule({ ...rule, actionType: e.target.value as ActionType })}>
                {(Object.keys(ACTION_LABELS) as ActionType[]).map((t) => <option key={t} value={t}>{ACTION_LABELS[t]}</option>)}
              </select>
            </label>
            <label style={{ flex: 1, minWidth: 120 }}>쿨타임(초)<input id={`rule-cooldown-${s.id}`} type="number" min={0} max={3600} value={rule.cooldownSeconds} onChange={(e) => setRule({ ...rule, cooldownSeconds: Number(e.target.value) })} /></label>
          </div>
          {PARAM_HINT[rule.actionType] && (
            <label>추가 값<input id={`rule-param-${s.id}`} placeholder={PARAM_HINT[rule.actionType]} value={rule.param} maxLength={200} required={rule.actionType === 'COMMAND'} onChange={(e) => setRule({ ...rule, param: e.target.value })} /></label>
          )}
          <button className="btn btn-primary" style={{ alignSelf: 'flex-start' }}>규칙 추가</button>
        </form>
      </section>

      <section className="stack">
        <span className="section-title">4. 최근 게임 반응</span>
        <div className="table-wrap">
          <table>
            <thead><tr><th>시각</th><th>후원자</th><th>대상</th><th>반응</th><th>상태</th><th></th></tr></thead>
            <tbody>
              {actions.length === 0 && <tr><td colSpan={6} className="muted small">아직 실행된 반응이 없어요.</td></tr>}
              {actions.map((a) => (
                <tr key={a.id}>
                  <td className="small muted">{timeAgo(a.createdAt)}</td>
                  <td>{a.donorNickname} <span className="muted small">{won(a.amount)}</span></td>
                  <td>{a.target}</td>
                  <td>{a.label}</td>
                  <td>
                    <span className={`badge ${a.status === 'DONE' ? 'badge-ok' : a.status === 'FAILED' ? 'badge-warn' : ''}`}>{ACTION_STATUS_LABELS[a.status]}</span>
                    {a.resultMessage && <div className="muted small">{a.resultMessage}</div>}
                  </td>
                  <td>{a.status === 'FAILED' && <button className="btn btn-sm" onClick={() => run(() => api(`/api/mc/servers/${s.id}/actions/${a.id}/retry`, { method: 'POST' }))}>다시 실행</button>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  )
}
