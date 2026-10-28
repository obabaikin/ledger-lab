import './page.css'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { createBrowserRouter, RouterProvider } from 'react-router'
import { Impressum, Privacy } from './Legal'
import { Landing } from './Landing'
import { SessionPage } from './SessionPage'

const router = createBrowserRouter([
  { path: '/', element: <Landing /> },
  { path: '/s/:sessionId', element: <SessionPage /> },
  { path: '/privacy', element: <Privacy /> },
  { path: '/impressum', element: <Impressum /> },
])

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <RouterProvider router={router} />
  </StrictMode>,
)
