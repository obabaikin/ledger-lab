import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [react()],
  build: { outDir: '../build/page', emptyOutDir: true },
  server: { proxy: { '/api': 'http://localhost:8083' } },
  // page.css is read as text by the test of the type scale; other CSS stays empty in tests.
  test: { environment: 'jsdom', setupFiles: ['src/test/setup.ts'], unstubGlobals: true, css: { include: [/page\.css/] } },
})
