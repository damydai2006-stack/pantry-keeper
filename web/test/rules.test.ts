import { readFileSync } from "node:fs"
import { resolve } from "node:path"
import { describe, expect, it } from "vitest"
import { addShelf, plusDays, plusMonths, plusYears, shelfAllowed } from "../src/domain/dateMath"
import { daysLabel, groupItems, itemDaysLabel } from "../src/domain/labels"
import { colorOf, groupOf, noticeToken, notices, parseTier, type ItemStatus, type Tier } from "../src/domain/reminderRules"
import { shanghaiToday, todayForView } from "../src/domain/today"

type DateCases = {
  dateMath: { id: string; op: "plusDays" | "plusMonths" | "plusYears"; start: string; amount: number; expect: string }[]
  shelfLife: { id: string; start?: string; value: number; unit: "day" | "month" | "year"; valid: boolean; expect?: string }[]
  groups: { id: string; today: string; expiry: string; days: number; group: string; color: string }[]
  notices: {
    id: string
    today: string
    expiry: string
    entry: string
    tiers: string[]
    expect: string[]
    status?: string
    opened?: string
    afterOpenDays?: number
  }[]
}

const cases = JSON.parse(readFileSync(resolve(process.cwd(), "../testdata/date-cases.json"), "utf8")) as DateCases

describe("date vectors", () => {
  it.each(cases.dateMath)("$id", (row) => {
    const actual = row.op === "plusDays" ? plusDays(row.start, row.amount) : row.op === "plusMonths" ? plusMonths(row.start, row.amount) : plusYears(row.start, row.amount)
    expect(actual).toBe(row.expect)
  })

  it.each(cases.shelfLife)("$id", (row) => {
    expect(shelfAllowed(row.value, row.unit)).toBe(row.valid)
    if (row.start && row.expect) expect(addShelf(row.start, row.value, row.unit)).toBe(row.expect)
  })

  it.each(cases.groups)("$id", (row) => {
    expect(groupOf(row.today, row.expiry)).toBe(row.group)
    expect(colorOf(groupOf(row.today, row.expiry))).toBe(row.color)
    expect(itemDaysLabel(row.today, { id: row.id, name: row.id, expiry_date: row.expiry, status: "in_stock" })).toBe(daysLabel(row.days))
  })

  it.each(cases.notices)("$id", (row) => {
    const tiers = row.tiers.map((tier) => {
      const parsed = parseTier(tier)
      if (!parsed) throw new Error("tier")
      return parsed
    })
    const actual = notices(
      row.today,
      row.expiry,
      tiers,
      row.entry,
      (row.status as ItemStatus | undefined) ?? "IN_STOCK",
      row.opened ?? null,
      row.afterOpenDays ?? null,
    ).map(noticeToken)
    expect(actual).toEqual(row.expect)
  })
})

describe("shanghai today", () => {
  it("crosses midnight at UTC+8", () => {
    expect(shanghaiToday(new Date("2026-11-20T15:59:00Z"))).toBe("2026-11-20")
    expect(shanghaiToday(new Date("2026-11-20T16:00:00Z"))).toBe("2026-11-21")
  })

  it("accepts a dev override and rejects an impossible date", () => {
    expect(todayForView(new Date("2026-11-20T04:00:00Z"), "?today=2026-01-02")).toBe("2026-01-02")
    expect(todayForView(new Date("2026-11-20T04:00:00Z"), "?today=2026-02-31")).toBe("2026-11-20")
  })
})

describe("list grouping", () => {
  it("puts expired items first and uses the earlier medicine date", () => {
    const groups = groupItems("2026-11-15", [
      { id: "later", name: "米", expiry_date: "2026-12-16", status: "in_stock", reminder_tiers: [] },
      { id: "med", name: "药", expiry_date: "2026-12-01", opened_date: "2026-11-01", after_open_days: 10, status: "in_stock", reminder_tiers: ["D5"] as Tier[] },
      { id: "gone", name: "历史", expiry_date: "2026-11-01", status: "used_up", reminder_tiers: [] },
    ], "")
    expect(groups.map((group) => group.bucket)).toEqual(["EXPIRED", "LATER"])
    expect(groups[0].items.map((item) => item.id)).toEqual(["med"])
    expect(itemDaysLabel("2026-11-15", groups[0].items[0])).toBe("已过期 4 天")
  })
})
