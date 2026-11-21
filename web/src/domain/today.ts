import { isValidIsoDate } from "./dateMath"

const SHANGHAI = "Asia/Shanghai"

/** Calendar date in Asia/Shanghai, matching Android `shanghaiToday()` for shelf submission. */
export function shanghaiToday(now: Date): string {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: SHANGHAI,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(now)
  const year = parts.find((part) => part.type === "year")?.value
  const month = parts.find((part) => part.type === "month")?.value
  const day = parts.find((part) => part.type === "day")?.value
  if (!year || !month || !day) throw new Error("timezone")
  return `${year}-${month}-${day}`
}

/**
 * Today used for grouping and `client_today`.
 * The `?today=` override sits inside `import.meta.env.DEV` so the production bundle drops it.
 */
export function todayForView(now: Date, search: string): string {
  if (import.meta.env.DEV) {
    const raw = new URLSearchParams(search).get("today")
    if (raw && isValidIsoDate(raw)) return raw
  }
  return shanghaiToday(now)
}
