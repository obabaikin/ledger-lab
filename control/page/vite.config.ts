import react from '@vitejs/plugin-react'
import { execSync } from 'node:child_process'
import { defineConfig } from 'vitest/config'

/** The release tag, or after it the commits since and the commit: v0.4.0-11-gb6d4263 (SemVer tags, git describe). */
function version(): string {
  try {
    return execSync("git describe --tags --match 'v[0-9]*' --always --dirty=+changes", { encoding: 'utf8' }).trim()
  } catch {
    return 'unknown'
  }
}

export default defineConfig({
  plugins: [react()],
  define: { __BUILD_VERSION__: JSON.stringify(version()), __BUILD_AT__: JSON.stringify(new Date().toISOString()) },
  build: { outDir: '../build/page', emptyOutDir: true },
  server: { proxy: { '/api': 'http://localhost:8083' } },
  test: { environment: 'jsdom', setupFiles: ['src/test/setup.ts'], unstubGlobals: true },
})
