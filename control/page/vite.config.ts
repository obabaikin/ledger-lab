import react from '@vitejs/plugin-react'
import { execSync } from 'node:child_process'
import { defineConfig } from 'vitest/config'

/** Without Gradle (the dev server), the same SemVer as control/build.gradle.kts: v0.4.0+20.changes. */
function gitVersion(): string {
  let described: string
  try {
    described = execSync("git describe --tags --match 'v[0-9]*' --long --always --dirty=+changes", { encoding: 'utf8' }).trim()
  } catch {
    return 'unknown'
  }
  const match = /^(v.+)-(\d+)-g[0-9a-f]+(\+changes)?$/.exec(described)
  if (!match) return described || 'unknown'
  const [, tag, since, changes] = match
  const metadata = [since !== '0' ? since : null, changes ? 'changes' : null].filter(Boolean).join('.')
  return metadata ? `${tag}+${metadata}` : tag
}

export default defineConfig({
  plugins: [react()],
  define: { __BUILD_VERSION__: JSON.stringify(process.env.BUILD_VERSION ?? gitVersion()) },
  build: { outDir: '../build/page', emptyOutDir: true },
  server: { proxy: { '/api': 'http://localhost:8083' } },
  test: { environment: 'jsdom', setupFiles: ['src/test/setup.ts'], unstubGlobals: true },
})
