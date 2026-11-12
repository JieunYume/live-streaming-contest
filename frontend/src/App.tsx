import { Link, NavLink, Navigate, Route, Routes, useNavigate } from 'react-router-dom'
import type { ReactNode } from 'react'
import { useAuth } from './auth'
import HomePage from './pages/HomePage'
import LoginPage from './pages/LoginPage'
import NewBroadcastPage from './pages/NewBroadcastPage'
import WatchPage from './pages/WatchPage'
import StudioPage from './pages/StudioPage'
import RecordingsPage from './pages/RecordingsPage'
import RecordingDetailPage from './pages/RecordingDetailPage'
import ServerDashboardPage from './pages/ServerDashboardPage'
import DonationResultPage from './pages/DonationResultPage'
import MyPage from './pages/MyPage'

function RequireLogin({ children }: { children: ReactNode }) {
  const { user, ready } = useAuth()
  if (!ready) return <div className="page">불러오는 중…</div>
  if (!user) return <Navigate to={`/login?next=${encodeURIComponent(location.pathname + location.search)}`} replace />
  return <>{children}</>
}

export default function App() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()

  return (
    <>
      <header className="topbar">
        <div className="topbar-inner">
          <Link to="/" className="brand">
            <span className="brand-block" aria-hidden />
            BLOCKCAST
          </Link>
          <nav className="nav">
            <NavLink to="/" end>방송</NavLink>
            <NavLink to="/recordings">다시보기</NavLink>
            {user && <NavLink to="/my">내 방송</NavLink>}
            {user && <NavLink to="/servers">마크 서버</NavLink>}
          </nav>
          <div className="nav-right">
            {user ? (
              <>
                <Link to="/broadcasts/new" className="btn btn-primary btn-sm">방송하기</Link>
                <span className="who">{user.nickname}</span>
                <button
                  className="btn btn-ghost btn-sm"
                  onClick={async () => {
                    await logout()
                    navigate('/')
                  }}
                >
                  로그아웃
                </button>
              </>
            ) : (
              <Link to="/login" className="btn btn-primary btn-sm">로그인</Link>
            )}
          </div>
        </div>
      </header>
      <main>
        <Routes>
          <Route path="/" element={<HomePage />} />
          <Route path="/login" element={<LoginPage />} />
          <Route path="/broadcasts/new" element={<RequireLogin><NewBroadcastPage /></RequireLogin>} />
          <Route path="/broadcasts/:id" element={<WatchPage />} />
          <Route path="/broadcasts/:id/studio" element={<RequireLogin><StudioPage /></RequireLogin>} />
          <Route path="/recordings" element={<RecordingsPage />} />
          <Route path="/recordings/:id" element={<RecordingDetailPage />} />
          <Route path="/my" element={<RequireLogin><MyPage /></RequireLogin>} />
          <Route path="/servers" element={<RequireLogin><ServerDashboardPage /></RequireLogin>} />
          <Route path="/donations/success" element={<RequireLogin><DonationResultPage success /></RequireLogin>} />
          <Route path="/donations/fail" element={<DonationResultPage success={false} />} />
          <Route path="*" element={<div className="page"><h1>페이지를 찾을 수 없어요</h1><Link to="/">홈으로</Link></div>} />
        </Routes>
      </main>
    </>
  )
}
