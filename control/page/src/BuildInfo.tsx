declare const __BUILD_COMMIT__: string
declare const __BUILD_AT__: string

export function BuildInfo() {
  return (
    <footer className="build">
      build {__BUILD_COMMIT__} · {new Date(__BUILD_AT__).toLocaleString()}
    </footer>
  )
}
