import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [react()],
  define: { __BUILD_VERSION__: JSON.stringify(process.env.BUILD_VERSION ?? 'dev') },
  build: { outDir: '../build/page', emptyOutDir: true },
  server: { proxy: { '/api': 'http://localhost:8083' } },
  test: { environment: 'jsdom', setupFiles: ['src/test/setup.ts'], unstubGlobals: true },
})
