import { describe, expect, it } from "vitest"
import type { Item } from "../src/api/types"
import { filterHistory, handledOnLabel, historyEmptyLabel, tierSummary } from "../src/domain/history"

function row(patch: Partial<Item> & Pick<Item, "id" | "name" | "status" | "rev">): Item {
  return {
    category: "food",
    quantity: 1,
    location: null,
    note: null,
    expiry_date: "2026-12-01",
    production_date: null,
    shelf_life_value: null,
    shelf_life_unit: null,
    expiry_basis: "manual_date",
    opened_date: null,
    after_open_days: null,
    reminder_tiers: ["D30", "D5"],
    ...patch,
  }
}

describe("history rows", () => {
  const items = [
    row({ id: "stock", name: "牛奶", status: "in_stock", rev: 1, expiry_date: "2026-11-14" }),
    row({ id: "old", name: "大米", status: "used_up", rev: 2, status_changed_at: "2026-11-18T00:00:00Z" }),
    row({ id: "new", name: "酱油", status: "discarded", rev: 3, status_changed_at: "2026-11-20T00:00:00Z", reminder_tiers: [] }),
    row({ id: "same", name: "盐", status: "used_up", rev: 9, status_changed_at: "2026-11-18T00:00:00Z" }),
  ]

  it("shows every family history row and ignores in-stock items", () => {
    expect(filterHistory(items, "all").map((item) => item.id)).toEqual(["new", "same", "old"])
  })

  it("splits used-up and discarded segments", () => {
    expect(filterHistory(items, "used_up").map((item) => item.name)).toEqual(["盐", "大米"])
    expect(filterHistory(items, "discarded").map((item) => item.name)).toEqual(["酱油"])
  })

  it("formats the handled date in Asia/Shanghai and keeps tier text", () => {
    expect(handledOnLabel("2026-11-20T16:30:00Z")).toBe("2026-11-21")
    expect(handledOnLabel(null)).toBe("—")
    expect(handledOnLabel("not-a-date")).toBe("—")
    expect(tierSummary(["D30", "D5"])).toBe("提前 30 天、提前 5 天")
    expect(tierSummary([])).toBe("不提醒")
  })

  it("uses an empty-history sentence only when the family has no history", () => {
    expect(historyEmptyLabel("all", 0)).toBe("还没有已用完或已处理的物品")
    expect(historyEmptyLabel("used_up", 2)).toBe("还没有已用完的物品")
    expect(historyEmptyLabel("discarded", 2)).toBe("还没有已处理的物品")
  })
})
