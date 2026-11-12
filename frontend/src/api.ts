// 백엔드 API 호출. 로그인 토큰은 localStorage 에 두고 Authorization 헤더로 보낸다.
export const API_BASE = import.meta.env.VITE_API_BASE_URL ?? ''

const TOKEN_KEY = 'livecraft.token'

export function getToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY)
  } catch {
    return null
  }
}

export function setToken(token: string | null) {
  try {
    if (token) localStorage.setItem(TOKEN_KEY, token)
    else localStorage.removeItem(TOKEN_KEY)
  } catch {
    /* 저장소를 못 쓰는 환경(사생활 보호 모드 등) */
  }
}

export class ApiError extends Error {
  status: number
  code: string
  constructor(status: number, code: string, message: string) {
    super(message)
    this.status = status
    this.code = code
  }
}

export async function api<T>(path: string, init: RequestInit & { json?: unknown } = {}): Promise<T> {
  const headers = new Headers(init.headers)
  const token = getToken()
  if (token) headers.set('Authorization', `Bearer ${token}`)
  let body = init.body
  if (init.json !== undefined) {
    headers.set('Content-Type', 'application/json')
    body = JSON.stringify(init.json)
  }
  const res = await fetch(API_BASE + path, { ...init, headers, body })
  if (!res.ok) {
    let code = `HTTP_${res.status}`
    let message = '요청을 처리하지 못했습니다.'
    try {
      const err = await res.json()
      code = err.code ?? code
      message = err.message ?? message
    } catch {
      /* 본문 없음 */
    }
    if (res.status === 401 && token) setToken(null) // 만료·로그아웃된 토큰 정리
    throw new ApiError(res.status, code, message)
  }
  if (res.status === 204) return undefined as T
  const text = await res.text()
  return (text ? JSON.parse(text) : undefined) as T
}

// ---------- 타입 ----------

export type BroadcastStatus = 'READY' | 'LIVE' | 'ENDED'
export type SourceType = 'SCREEN' | 'CAMERA'

export interface User {
  id: number
  username: string
  nickname: string
}

export interface LoginResult {
  token: string
  user: User
}

export interface Broadcast {
  id: number
  title: string
  description: string | null
  status: BroadcastStatus
  sourceType: SourceType | null
  ownerId: number
  ownerNickname: string
  createdAt: string
  startedAt: string | null
  endedAt: string | null
}

export interface JoinInfo {
  url: string
  token: string
  room: string
  identity: string
}

export interface Comment {
  id: number
  authorId: number
  authorNickname: string
  content: string
  edited: boolean
  createdAt: string
}

export type ActionStatus = 'PENDING' | 'SENT' | 'DONE' | 'FAILED'

export interface Donation {
  id: number
  donorNickname: string
  amount: number
  message: string | null
  paidAt: string
  actionLabel: string | null
  actionStatus: ActionStatus | null
  actionTarget: string | null
}

export interface DonationSummary {
  total: number
  count: number
  items: Donation[]
}

export interface RuleOption {
  minAmount: number
  label: string
  actionType: string
  cooldownSeconds: number
  waiting: number
  etaSeconds: number
}

export interface DonationOptions {
  broadcastId: number
  streamerId: number
  streamerNickname: string
  live: boolean
  minecraftLinked: boolean
  serverName: string | null
  mcName: string | null
  pluginConnected: boolean
  playerOnline: boolean
  rules: RuleOption[]
  tossClientKey: string | null
  minAmount: number
  maxAmount: number
}

export interface PrepareResult {
  orderId: string
  amount: number
  orderName: string
  customerKey: string
  tossClientKey: string
  ruleLabel: string | null
}

export interface Recording {
  id: number
  broadcastId: number
  title: string
  ownerNickname: string
  status: 'RECORDING' | 'READY' | 'EMPTY'
  mimeType: string
  sizeBytes: number
  startedAt: string
  endedAt: string | null
}

export type ActionType = 'FOOD' | 'RANDOM_BOX' | 'KILL' | 'SPAWN_MOB' | 'EFFECT' | 'COMMAND'

export interface McRule {
  id: number
  minAmount: number
  label: string
  actionType: ActionType
  param: string | null
  cooldownSeconds: number
}

export interface McMember {
  id: number
  mcName: string
  userId: number
  username: string
  nickname: string
  status: 'INVITED' | 'ACCEPTED'
  online: boolean
}

export interface McServer {
  id: number
  name: string
  linked: boolean
  connected: boolean
  linkCode: string | null
  linkCodeExpiresAt: string | null
  onlinePlayers: string[]
  members: McMember[]
  rules: McRule[]
}

export interface McAction {
  id: number
  donationId: number
  target: string
  label: string
  actionType: ActionType
  donorNickname: string
  amount: number
  status: ActionStatus
  attempts: number
  resultMessage: string | null
  createdAt: string
  doneAt: string | null
}

export interface McInvite {
  memberId: number
  serverId: number
  serverName: string
  ownerNickname: string
  mcName: string
  status: 'INVITED' | 'ACCEPTED'
}

// ---------- 표시용 ----------

export const ACTION_LABELS: Record<ActionType, string> = {
  FOOD: '음식 지급',
  RANDOM_BOX: '랜덤박스',
  KILL: '즉사',
  SPAWN_MOB: '몹 소환',
  EFFECT: '포션 효과',
  COMMAND: '명령어 실행',
}

export const ACTION_STATUS_LABELS: Record<ActionStatus, string> = {
  PENDING: '대기 중',
  SENT: '실행 중',
  DONE: '실행 완료',
  FAILED: '실패',
}

export const won = (n: number) => `${n.toLocaleString('ko-KR')}원`

export const timeAgo = (iso: string | null) => {
  if (!iso) return ''
  const sec = Math.floor((Date.now() - new Date(iso).getTime()) / 1000)
  if (sec < 60) return '방금 전'
  if (sec < 3600) return `${Math.floor(sec / 60)}분 전`
  if (sec < 86400) return `${Math.floor(sec / 3600)}시간 전`
  return new Date(iso).toLocaleDateString('ko-KR')
}
