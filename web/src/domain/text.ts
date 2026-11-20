const PHONE = /^1[3-9]\d{9}$/
export const INVITE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

export function codePointLength(value: string): number {
  return [...value].length
}

export function takeCodePoints(value: string, max: number): string {
  const chars = [...value]
  if (max <= 0 || chars.length === 0) return ""
  return chars.length <= max ? value : chars.slice(0, max).join("")
}

export function normalizePhone(raw: string): string | null {
  let value = raw.trim().replace(/[\s-]/g, "")
  if (value.startsWith("+86")) value = value.slice(3)
  else if (value.startsWith("86") && value.length === 13) value = value.slice(2)
  return PHONE.test(value) ? value : null
}

export function normalizeInvite(raw: string): string | null {
  const value = raw.trim().toUpperCase().replace(/ /g, "")
  if (value.length !== 6 || [...value].some((char) => !INVITE_ALPHABET.includes(char))) return null
  return value
}

export function familyNameError(name: string): string | null {
  const count = codePointLength(name.trim())
  return count >= 2 && count <= 20 ? null : "家庭名需要 2 到 20 个字"
}

export function itemNameError(name: string): string | null {
  const count = codePointLength(name.trim())
  return count >= 1 && count <= 40 ? null : "物品名需要 1 到 40 个字"
}

export function noteError(note: string): string | null {
  return codePointLength(note) <= 200 ? null : "备注最多 200 个字"
}

export function locationError(location: string): string | null {
  return codePointLength(location) <= 12 ? null : "位置最多 12 个字"
}
