import { TIER_OPTIONS } from "./draft"

export type HistorySegment = "used_up" | "discarded" | "all"

export const HISTORY_SEGMENTS: { id: HistorySegment; label: string }[] = [
  { id: "used_up", label: "已用完" },
  { id: "discarded", label: "已处理" },
  { id: "all", label: "全部" },
]

export type HistoryRow = {
  id: string
  name: string
  status: string
  expiry_date: string
  reminder_tiers: readonly string[]
  status_changed_at?: string | null
  rev: number
}

export function isHistoryStatus(status: string): boolean {
  return status === "used_up" || status === "discarded"
}

export function historyStatusLabel(status: string): string {
  if (status === "used_up") return "已用完"
  if (status === "discarded") return "已处理"
  return "在库"
}

export function tierSummary(tiers: readonly string[]): string {
  if (tiers.length === 0) return "不提醒"
  return tiers.map((tier) => TIER_OPTIONS.find((option) => option[0] === tier)?.[1] ?? tier).join("、")
}

export function handledOnLabel(value: string | null | undefined): string {
  if (!value) return "—"
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return "—"
  return new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Shanghai",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(parsed)
}

export function filterHistory<T extends HistoryRow>(items: readonly T[], segment: HistorySegment): T[] {
  return items
    .filter((item) => isHistoryStatus(item.status) && (segment === "all" || item.status === segment))
    .slice()
    .sort((a, b) => {
      const byTime = (b.status_changed_at ?? "").localeCompare(a.status_changed_at ?? "")
      if (byTime !== 0) return byTime
      if (a.rev !== b.rev) return b.rev - a.rev
      return a.name.localeCompare(b.name, "zh")
    })
}

export function historyEmptyLabel(segment: HistorySegment, totalHistory: number): string {
  if (totalHistory === 0 || segment === "all") return "还没有已用完或已处理的物品"
  if (segment === "used_up") return "还没有已用完的物品"
  return "还没有已处理的物品"
}
