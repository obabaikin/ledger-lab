import { render } from '@testing-library/react'
import { StrictMode } from 'react'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { DeletePage } from '../DeletePage'
import { Impressum, Privacy } from '../Legal'
import { Landing } from '../Landing'
import { SessionPage } from '../SessionPage'

export function renderAt(url: string) {
  const router = createMemoryRouter(
    [
      { path: '/', element: <Landing /> },
      { path: '/s/:sessionId', element: <SessionPage /> },
      { path: '/privacy', element: <Privacy /> },
      { path: '/impressum', element: <Impressum /> },
      { path: '/delete', element: <DeletePage /> },
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
