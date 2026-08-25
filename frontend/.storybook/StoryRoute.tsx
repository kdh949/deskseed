import { useEffect, type ReactNode } from 'react'
import { Route, Routes, useNavigate } from 'react-router'

export function StoryRoute({
  children,
  path,
  to,
}: {
  children: ReactNode
  path: string
  to: string
}) {
  const navigate = useNavigate()

  useEffect(() => {
    navigate(to, { replace: true })
  }, [navigate, to])

  return (
    <Routes>
      <Route element={children} path={path} />
    </Routes>
  )
}
