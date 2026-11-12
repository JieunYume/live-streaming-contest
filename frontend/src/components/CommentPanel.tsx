import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, ApiError, type Comment } from '../api'
import { useAuth } from '../auth'

/** 방송별 댓글. 목록은 처음에 불러오고, 이후 변경은 부모가 SSE 로 받아 apply* 로 반영한다 */
export function useComments(broadcastId: number) {
  const [comments, setComments] = useState<Comment[]>([])
  useEffect(() => {
    api<Comment[]>(`/api/broadcasts/${broadcastId}/comments`).then(setComments).catch(() => setComments([]))
  }, [broadcastId])
  return {
    comments,
    applyCreated: (c: Comment) => setComments((list) => (list.some((x) => x.id === c.id) ? list : [...list, c])),
    applyUpdated: (c: Comment) => setComments((list) => list.map((x) => (x.id === c.id ? c : x))),
    applyDeleted: (d: { id: number }) => setComments((list) => list.filter((x) => x.id !== d.id)),
  }
}

export default function CommentPanel({ broadcastId, comments }: { broadcastId: number; comments: Comment[] }) {
  const { user } = useAuth()
  const [text, setText] = useState('')
  const [editing, setEditing] = useState<{ id: number; text: string } | null>(null)
  const [error, setError] = useState('')
  const listRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    listRef.current?.scrollTo({ top: listRef.current.scrollHeight })
  }, [comments.length])

  const run = async (fn: () => Promise<unknown>) => {
    setError('')
    try {
      await fn()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '요청에 실패했습니다.')
    }
  }

  return (
    <div className="card comments">
      <h2>댓글</h2>
      <div className="comment-list" ref={listRef}>
        {comments.length === 0 && <p className="muted small">첫 댓글을 남겨 보세요.</p>}
        {comments.map((c) =>
          editing?.id === c.id ? (
            <form
              key={c.id}
              className="comment-form"
              onSubmit={(e) => {
                e.preventDefault()
                run(async () => {
                  await api(`/api/broadcasts/${broadcastId}/comments/${c.id}`, { method: 'PUT', json: { content: editing.text } })
                  setEditing(null)
                })
              }}
            >
              <input id={`edit-${c.id}`} value={editing.text} maxLength={300} onChange={(e) => setEditing({ id: c.id, text: e.target.value })} />
              <button className="btn btn-sm">저장</button>
              <button type="button" className="btn btn-sm btn-ghost" onClick={() => setEditing(null)}>취소</button>
            </form>
          ) : (
            <div key={c.id} className="comment">
              <b>{c.authorNickname}</b>
              {c.content}
              {c.edited && <span className="muted small"> (수정됨)</span>}
              {user?.id === c.authorId && (
                <span className="actions">
                  <button onClick={() => setEditing({ id: c.id, text: c.content })}>수정</button>
                  <button onClick={() => run(() => api(`/api/broadcasts/${broadcastId}/comments/${c.id}`, { method: 'DELETE' }))}>삭제</button>
                </span>
              )}
            </div>
          ),
        )}
      </div>
      {error && <p className="error">{error}</p>}
      {user ? (
        <form
          className="comment-form"
          onSubmit={(e) => {
            e.preventDefault()
            if (!text.trim()) return
            run(async () => {
              await api(`/api/broadcasts/${broadcastId}/comments`, { method: 'POST', json: { content: text.trim() } })
              setText('')
            })
          }}
        >
          <input id="comment-input" placeholder="댓글 입력" value={text} maxLength={300} onChange={(e) => setText(e.target.value)} />
          <button className="btn btn-primary">등록</button>
        </form>
      ) : (
        <p className="muted small">
          <Link to={`/login?next=/broadcasts/${broadcastId}`}>로그인</Link>하면 댓글을 쓸 수 있어요.
        </p>
      )}
    </div>
  )
}
