import { addShelf, daysBetween, plusDays, plusMonths } from "./dateMath"

export type Tier = "D5" | "D10" | "D30" | "M2" | "M3"
export type ExpiryGroup = "EXPIRED" | "WITHIN_7" | "WITHIN_30" | "LATER"
export type ExpiryColor = "RED" | "ORANGE" | "YELLOW" | "GREEN"
export type NoticeKind = "TIER" | "EXPIRY_TODAY" | "EXPIRED"
export type ItemStatus = "IN_STOCK" | "USED_UP" | "DISCARDED"

export type Notice = {
  kind: NoticeKind
  tier: Tier | null
  daysRemaining: number
  expiredDays: number | null
}

const TIERS: readonly Tier[] = ["D5", "D10", "D30", "M2", "M3"]

export function parseTier(raw: string): Tier | null {
  const found = TIERS.find((tier) => tier.toLowerCase() === raw.trim().toLowerCase())
  return found ?? null
}

export function daysRemaining(today: string, expiry: string): number {
  return daysBetween(today, expiry)
}

export function groupOf(today: string, expiry: string): ExpiryGroup {
  const days = daysRemaining(today, expiry)
  if (days < 0) return "EXPIRED"
  if (days <= 7) return "WITHIN_7"
  if (days <= 30) return "WITHIN_30"
  return "LATER"
}

export function colorOf(group: ExpiryGroup): ExpiryColor {
  if (group === "EXPIRED") return "RED"
  if (group === "WITHIN_7") return "ORANGE"
  if (group === "WITHIN_30") return "YELLOW"
  return "GREEN"
}

export function effectiveExpiry(expiry: string, openedDate: string | null, afterOpenDays: number | null): string {
  if (!openedDate || afterOpenDays == null) return expiry
  return minDate(expiry, plusDays(openedDate, afterOpenDays))
}

export function triggerDate(expiry: string, tier: Tier): string {
  if (tier === "D5") return plusDays(expiry, -5)
  if (tier === "D10") return plusDays(expiry, -10)
  if (tier === "D30") return plusDays(expiry, -30)
  if (tier === "M2") return plusMonths(expiry, -2)
  return plusMonths(expiry, -3)
}

export function notices(
  today: string,
  expiry: string,
  tiers: readonly Tier[],
  entryDate: string,
  status: ItemStatus = "IN_STOCK",
  openedDate: string | null = null,
  afterOpenDays: number | null = null,
): Notice[] {
  if (status !== "IN_STOCK" || tiers.length === 0) return []
  const effective = effectiveExpiry(expiry, openedDate, afterOpenDays)
  const remaining = daysRemaining(today, effective)
  const out: Notice[] = []
  for (const tier of tiers) {
    const trigger = triggerDate(effective, tier)
    if (trigger < entryDate) continue
    if (trigger === today) out.push({ kind: "TIER", tier, daysRemaining: remaining, expiredDays: null })
  }
  if (today === effective && today >= entryDate) {
    out.push({ kind: "EXPIRY_TODAY", tier: null, daysRemaining: 0, expiredDays: null })
  }
  const daysAfter = daysBetween(effective, today)
  if (daysAfter >= 1 && (daysAfter - 1) % 7 === 0 && today >= entryDate) {
    out.push({ kind: "EXPIRED", tier: null, daysRemaining: -daysAfter, expiredDays: daysAfter })
  }
  return out
}

export function noticeToken(notice: Notice): string {
  if (notice.kind === "EXPIRY_TODAY") return "EXPIRY_TODAY"
  if (notice.kind === "EXPIRED") return `EXPIRED:${notice.expiredDays}`
  return `TIER:${notice.tier}`
}

function minDate(a: string, b: string): string {
  return a <= b ? a : b
}

export function shelfFrom(start: string, value: number, unit: "day" | "month" | "year"): string {
  return addShelf(start, value, unit)
}
