const UUID_BYTES = 16
const VERSION_BYTE = 6
const VARIANT_BYTE = 8
const GROUP_ENDS = [8, 12, 16, 20]

export function newRequestId(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(UUID_BYTES))
  bytes[VERSION_BYTE] = (bytes[VERSION_BYTE]! & 0x0f) | 0x40
  bytes[VARIANT_BYTE] = (bytes[VARIANT_BYTE]! & 0x3f) | 0x80
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
  return [0, ...GROUP_ENDS].map((from, i) => hex.slice(from, GROUP_ENDS[i])).join('-')
}
