const UUID_BYTES = 16
const VERSION_BYTE = 6
const VARIANT_BYTE = 8
const GROUP_ENDS = [8, 12, 16, 20]
const VERSION_BITS = 0x0f
const VERSION_4 = 0x40
const VARIANT_BITS = 0x3f
const VARIANT_RFC_4122 = 0x80

export function newRequestId(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(UUID_BYTES))
  bytes[VERSION_BYTE] = (bytes[VERSION_BYTE]! & VERSION_BITS) | VERSION_4
  bytes[VARIANT_BYTE] = (bytes[VARIANT_BYTE]! & VARIANT_BITS) | VARIANT_RFC_4122
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
  return [0, ...GROUP_ENDS].map((from, i) => hex.slice(from, GROUP_ENDS[i])).join('-')
}
