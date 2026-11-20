import { daysRemaining, effectiveExpiry, groupOf, type ExpiryGroup } from "./reminderRules"

export type ListBucket = "EXPIRED" | "SOON" | "MONTH" | "LATER"

export type ListedItem = {
  id: string
  name: string
  expiry_date: string
  opened_date?: string | null
  after_open_days?: number | null
  status: string
}

export function listBucket(today: string, expiry: string): ListBucket {
  const group: ExpiryGroup = groupOf(today, expiry)
  if (group === "EXPIRED") return "EXPIRED"
  if (group === "WITHIN_7") return "SOON"
  if (group === "WITHIN_30") return "MONTH"
  return "LATER"
}

export function bucketTitle(bucket: ListBucket): string {
  if (bucket === "EXPIRED") return "已过期"
  if (bucket === "SOON") return "7 天内（含今天）"
  if (bucket === "MONTH") return "8–30 天"
  return "更远"
}

export function daysLabel(days: number): string {
  if (days < 0) return `已过期 ${-days} 天`
  if (days === 0) return "今天到期"
  if (days <= 30) return `${days} 天后到期`
  return `还有 ${days} 天`
}

export function categoryLabel(category: string): string {
  if (category === "medicine") return "药品"
  if (category === "condiment") return "调料"
  if (category === "other") return "其他"
  return "食品"
}

export function basisLabel(basis: string): string {
  if (basis === "manual_duration_from_prod" || basis === "ocr_prod_plus_life") return "从生产日期起算"
  if (basis === "manual_duration_from_today") return "从今天起算"
  return "按填写的到期日"
}

export function itemEffectiveExpiry(item: ListedItem): string {
  return effectiveExpiry(item.expiry_date, item.opened_date ?? null, item.after_open_days ?? null)
}

export function itemDaysLabel(today: string, item: ListedItem): string {
  return daysLabel(daysRemaining(today, itemEffectiveExpiry(item)))
}

export type ItemGroup<T extends ListedItem> = { bucket: ListBucket; title: string; items: T[] }

const ORDER: ListBucket[] = ["EXPIRED", "SOON", "MONTH", "LATER"]

export function groupItems<T extends ListedItem>(today: string, items: readonly T[], query: string): ItemGroup<T>[] {
  const needle = query.trim()
  const visible = items.filter((item) => item.status === "in_stock" && (needle === "" || item.name.includes(needle)))
  return ORDER.flatMap((bucket) => {
    const rows = visible
      .filter((item) => listBucket(today, itemEffectiveExpiry(item)) === bucket)
      .slice()
      .sort((a, b) => itemEffectiveExpiry(a).localeCompare(itemEffectiveExpiry(b)) || a.name.localeCompare(b.name, "zh"))
    return rows.length === 0 ? [] : [{ bucket, title: bucketTitle(bucket), items: rows }]
  })
}
