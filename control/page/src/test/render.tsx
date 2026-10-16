import { render } from '@testing-library/react'
import { StrictMode } from 'react'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { Landing } from '../Landing'
import { SessionPage } from '../SessionPage'

export function renderAt(url: string) {
  const router = createMemoryRouter(
    [
      { path: '/', element: <Landing /> },
      { path: '/s/:sessionId', element: <SessionPage /> },
    ],
    { initialEntries: [url] },
  )
  render(
    <StrictMode>
      <RouterProvider router={router} />
    </StrictMode>,
  )
  return router
}
