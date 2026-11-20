export type ShelfUnit = "day" | "month" | "year"

export const MIN_YEAR = 2000
export const MAX_YEAR = 2045
export const MAX_SHELF_DAYS = 3650
export const MAX_SHELF_MONTHS = 120
export const MAX_SHELF_YEARS = 10

const ISO = /^(\d{4})-(\d{2})-(\d{2})$/

export function isLeap(year: number): boolean {
  return year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0)
}

export function lastDayOfMonth(year: number, month: number): number {
  if (month === 2) return isLeap(year) ? 29 : 28
  if (month === 4 || month === 6 || month === 9 || month === 11) return 30
  if (month >= 1 && month <= 12) return 31
  throw new Error("month")
}

export function isValidIsoDate(value: string): boolean {
  const match = ISO.exec(value)
  if (!match) return false
  const year = Number(match[1])
  const month = Number(match[2])
  const day = Number(match[3])
  if (month < 1 || month > 12) return false
  return day >= 1 && day <= lastDayOfMonth(year, month)
}

export function toEpochDays(iso: string): number {
  if (!isValidIsoDate(iso)) throw new Error("日期不正确")
  const [year, month, day] = iso.split("-").map(Number)
  return Math.round(Date.UTC(year, month - 1, day) / 86_400_000)
}

export function fromEpochDays(epoch: number): string {
  const date = new Date(epoch * 86_400_000)
  const year = date.getUTCFullYear()
  const month = String(date.getUTCMonth() + 1).padStart(2, "0")
  const day = String(date.getUTCDate()).padStart(2, "0")
  return `${year}-${month}-${day}`
}

export function plusDays(iso: string, days: number): string {
  return fromEpochDays(toEpochDays(iso) + days)
}

function floorDiv(a: number, b: number): number {
  const q = Math.trunc(a / b)
  const r = a % b
  return r !== 0 && a < 0 ? q - 1 : q
}

function floorMod(a: number, b: number): number {
  return a - floorDiv(a, b) * b
}

export function plusMonths(iso: string, months: number): string {
  if (!isValidIsoDate(iso)) throw new Error("日期不正确")
  const [yearText, monthText, dayText] = iso.split("-")
  const monthIndex = Number(monthText) - 1 + months
  const year = Number(yearText) + floorDiv(monthIndex, 12)
  const month = floorMod(monthIndex, 12) + 1
  const day = Math.min(Number(dayText), lastDayOfMonth(year, month))
  return `${year}-${String(month).padStart(2, "0")}-${String(day).padStart(2, "0")}`
}

export function plusYears(iso: string, years: number): string {
  return plusMonths(iso, years * 12)
}

export function addShelf(start: string, value: number, unit: ShelfUnit): string {
  if (unit === "day") return plusDays(start, value)
  if (unit === "month") return plusMonths(start, value)
  return plusYears(start, value)
}

export function shelfAllowed(value: number, unit: ShelfUnit): boolean {
  if (!Number.isInteger(value)) return false
  if (unit === "day") return value >= 1 && value <= MAX_SHELF_DAYS
  if (unit === "month") return value >= 1 && value <= MAX_SHELF_MONTHS
  return value >= 1 && value <= MAX_SHELF_YEARS
}

export function daysBetween(from: string, to: string): number {
  return toEpochDays(to) - toEpochDays(from)
}
