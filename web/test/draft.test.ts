import { describe, expect, it } from "vitest"
import { createBody, emptyDraft, patchFields, resolveDraft } from "../src/domain/draft"
import type { Item } from "../src/api/types"
import { familyNameError, normalizeInvite, normalizePhone } from "../src/domain/text"

const phone = "13" + "8".padStart(9, "0")

function stored(patch: Partial<Item> = {}): Item {
  return {
    id: "item-1",
    name: "牛奶",
    category: "food",
    quantity: 1,
    location: "冰箱",
    note: null,
    expiry_date: "2027-11-15",
    production_date: "2026-11-15",
    shelf_life_value: null,
    shelf_life_unit: null,
    expiry_basis: "manual_date",
    opened_date: null,
    after_open_days: null,
    reminder_tiers: ["D30", "D5"],
    status: "in_stock",
    rev: 4,
    ...patch,
  }
}

describe("drafts", () => {
  it("computes shelf life from the production date and sends client_today", () => {
    const draft = { ...emptyDraft(), name: "牛奶", mode: "shelf" as const, shelfValue: 12, shelfUnit: "month" as const, productionDate: "2026-11-15" }
    const resolved = resolveDraft(draft, "2026-11-20")
    expect(resolved).toMatchObject({ expiry: "2027-11-15", basis: "manual_duration_from_prod" })
    const body = createBody(draft, "2026-11-20")
    expect(body).toMatchObject({
      value: {
        shelf_life_value: 12,
        shelf_life_unit: "month",
        client_today: "2026-11-20",
        production_date: "2026-11-15",
      },
    })
    expect("value" in body ? body.value : {}).not.toHaveProperty("expiry_date")
  })

  it("uses shanghai today when the production date is absent", () => {
    const draft = { ...emptyDraft(), name: "鸡蛋", mode: "shelf" as const, shelfValue: 7, shelfUnit: "day" as const }
    expect(resolveDraft(draft, "2026-11-15")).toMatchObject({ expiry: "2026-11-22", basis: "manual_duration_from_today" })
  })

  it("clamps a short month the same way as the shared vectors", () => {
    const draft = { ...emptyDraft(), name: "酸奶", mode: "shelf" as const, shelfValue: 1, shelfUnit: "month" as const, productionDate: "2026-01-31" }
    expect(resolveDraft(draft, "2026-02-01")).toMatchObject({ expiry: "2026-02-28" })
  })

  it("patches only changed fields and keeps base data out of the body", () => {
    const fields = patchFields(stored(), { ...emptyDraft(), name: "鲜奶", mode: "date", expiryDate: "2027-11-15", productionDate: "2026-11-15", shelfValue: 12, shelfUnit: "month", tiers: ["D30", "D5"], location: "冰箱", quantity: 1 }, "2026-11-20")
    expect(fields).toEqual({ value: { name: "鲜奶" } })
  })

  it("rejects a missing expiry and an overlong note", () => {
    expect(createBody({ ...emptyDraft(), name: " " }, "2026-11-20")).toEqual({ error: "物品名需要 1 到 40 个字" })
    expect(createBody({ ...emptyDraft(), name: "盐", mode: "date", expiryDate: "" }, "2026-11-20")).toEqual({ error: "需要到期日或可推算的保质期" })
  })
})

describe("phone and invite", () => {
  it("normalizes a mainland number and a family invite without accepting junk", () => {
    expect(normalizePhone("+86 " + phone)).toBe(phone)
    expect(normalizePhone("12345")).toBeNull()
    expect(normalizeInvite("ab23cd")).toBe("AB23CD")
    expect(normalizeInvite("IO0000")).toBeNull()
    expect(familyNameError("家")).toBe("家庭名需要 2 到 20 个字")
    expect(familyNameError("我家")).toBeNull()
  })
})
