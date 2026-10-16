import react from '@vitejs/plugin-react'
import { execSync } from 'node:child_process'
import { defineConfig } from 'vitest/config'

function build(): string {
  const git = (args: string) => execSync(`git ${args}`, { encoding: 'utf8' }).trim()
  try {
    const changed = git('status --porcelain --untracked-files=no') === '' ? '' : '+changes'
    return `${git('rev-parse --short HEAD')}${changed}`
  } catch {
    return 'unknown'
  }
}

export default defineConfig({
  plugins: [react()],
  define: { __BUILD_COMMIT__: JSON.stringify(build()), __BUILD_AT__: JSON.stringify(new Date().toISOString()) },
  build: { outDir: '../build/page', emptyOutDir: true },
  server: { proxy: { '/api': 'http://localhost:8083' } },
  test: { environment: 'jsdom', setupFiles: ['src/test/setup.ts'], unstubGlobals: true },
})
