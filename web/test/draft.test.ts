import { describe, expect, it } from "vitest"
import { createBody, draftFromItem, emptyDraft, patchFields, previewDraft, resolveDraft, type StoredItem } from "../src/domain/draft"
import type { Item } from "../src/api/types"
import { familyNameError, normalizeInvite, normalizePhone } from "../src/domain/text"

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/

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

  it("adds one year to 2026-09-09 and submits an ISO production date without an expiry date", () => {
    const draft = {
      ...emptyDraft(),
      name: "茶叶",
      mode: "shelf" as const,
      shelfValue: 1,
      shelfUnit: "year" as const,
      productionDate: "2026-09-09",
    }
    expect(resolveDraft(draft, "2026-11-22")).toMatchObject({ expiry: "2027-09-09", basis: "manual_duration_from_prod" })
    const body = createBody(draft, "2026-11-22")
    expect(body).toMatchObject({
      value: {
        production_date: "2026-09-09",
        shelf_life_value: 1,
        shelf_life_unit: "year",
        expiry_basis: "manual_duration_from_prod",
      },
    })
    expect("value" in body ? body.value : {}).not.toHaveProperty("expiry_date")
    expectDatesOmittedOrIso("value" in body ? body.value : null)
  })

  it("rejects a slash production date before any shelf preview", () => {
    const draft = {
      ...emptyDraft(),
      name: "茶叶",
      mode: "shelf" as const,
      shelfValue: 1,
      shelfUnit: "year" as const,
      productionDate: "2026/09/09",
    }
    expect(resolveDraft(draft, "2026-11-22")).toEqual({ error: "日期不正确" })
    expect(createBody(draft, "2026-11-22")).toEqual({ error: "日期不正确" })
    expect(patchFields(stored(), draft, "2026-11-22")).toEqual({ error: "日期不正确" })
  })
})

describe("editing keeps the stored expiry basis", () => {
  const today = "2026-11-22"

  it("restores a production-anchored shelf item and does not rewrite it on an unchanged save", () => {
    const item = shelfItem({ expiry_basis: "manual_duration_from_prod" })
    const draft = draftFromItem(item)
    expect(draft.mode).toBe("shelf")
    expect(draft.shelfValue).toBe(1)
    expect(draft.shelfUnit).toBe("year")
    expect(patchFields(item, draft, today)).toEqual({ value: null })
  })

  it("keeps basis and expiry when only the name changes", () => {
    const cases = [
      shelfItem({ expiry_basis: "manual_duration_from_prod" }),
      shelfItem({ expiry_basis: "manual_duration_from_today", production_date: null }),
      shelfItem({ expiry_basis: "ocr_prod_plus_life" }),
      shelfItem({ expiry_basis: "ocr_expiry" }),
      shelfItem({ expiry_basis: "manual_date" }),
    ]
    for (const item of cases) {
      const patched = patchFields(item, { ...draftFromItem(item), name: "新茶叶" }, today)
      expect(patched, item.expiry_basis).toEqual({ value: { name: "新茶叶" } })
      expectDatesOmittedOrIso("value" in patched ? patched.value : null)
    }
  })

  it("does not move a today-anchored shelf item onto the edit day's date", () => {
    const item = shelfItem({
      expiry_basis: "manual_duration_from_today",
      production_date: null,
      expiry_date: "2027-09-09",
    })
    const draft = draftFromItem(item)
    expect(draft.mode).toBe("shelf")
    expect(draft.productionDate).toBe("")
    const patched = patchFields(item, draft, today)
    expect(patched).toEqual({ value: null })
    const preview = previewDraft(draft, today, item)
    expect(preview).toMatchObject({ expiry: "2027-09-09", basis: "manual_duration_from_today" })
    if ("origin" in preview) expect(preview.origin).not.toContain(today)
  })

  it("recomputes from today only after the shelf length itself changes", () => {
    const item = shelfItem({
      expiry_basis: "manual_duration_from_today",
      production_date: null,
      shelf_life_value: 7,
      shelf_life_unit: "day",
      expiry_date: "2026-11-22",
    })
    const draft = { ...draftFromItem(item), shelfValue: 1 }
    const patched = patchFields(item, draft, "2026-11-20")
    expect(patched).toEqual({
      value: {
        expiry_date: "2026-11-21",
        shelf_life_value: 1,
      },
    })
    expectDatesOmittedOrIso("value" in patched ? patched.value : null)
  })

  it("keeps an OCR production-plus-life source until the shelf inputs change", () => {
    const item = shelfItem({ expiry_basis: "ocr_prod_plus_life" })
    expect(draftFromItem(item).mode).toBe("shelf")
    expect(patchFields(item, draftFromItem(item), today)).toEqual({ value: null })
    const edited = { ...draftFromItem(item), shelfValue: 6, shelfUnit: "month" as const }
    const patched = patchFields(item, edited, today)
    expect(patched).toEqual({
      value: {
        expiry_date: "2027-03-09",
        shelf_life_value: 6,
        shelf_life_unit: "month",
        expiry_basis: "manual_duration_from_prod",
      },
    })
    expectDatesOmittedOrIso("value" in patched ? patched.value : null)
    const moved = patchFields(item, { ...draftFromItem(item), productionDate: "2026-08-01" }, today)
    expect(moved).toEqual({
      value: {
        production_date: "2026-08-01",
        expiry_date: "2027-08-01",
        expiry_basis: "manual_duration_from_prod",
      },
    })
    expectDatesOmittedOrIso("value" in moved ? moved.value : null)
  })

  it("keeps an OCR package date when the form is reopened, including when shelf columns are also present", () => {
    const item = shelfItem({ expiry_basis: "ocr_expiry" })
    const draft = draftFromItem(item)
    expect(draft.mode).toBe("date")
    expect(patchFields(item, draft, today)).toEqual({ value: null })
    const renamed = patchFields(item, { ...draft, name: "新茶叶" }, today)
    expect(renamed).toEqual({ value: { name: "新茶叶" } })
    const redated = patchFields(item, { ...draft, expiryDate: "2028-01-01" }, today)
    expect(redated).toEqual({ value: { expiry_date: "2028-01-01", expiry_basis: "manual_date" } })
    const productionOnly = patchFields(item, { ...draft, productionDate: "2026-01-01" }, today)
    expect(productionOnly).toEqual({ value: { production_date: "2026-01-01" } })
  })

  it("restores a typed expiry as date mode even if leftover shelf columns exist", () => {
    const item = shelfItem({ expiry_basis: "manual_date" })
    const draft = draftFromItem(item)
    expect(draft.mode).toBe("date")
    expect(draft.expiryDate).toBe("2027-09-09")
    expect(patchFields(item, draft, today)).toEqual({ value: null })
  })

  it("does not invent manual_date when a duration item is missing its shelf fields", () => {
    const missing = [
      shelfItem({ expiry_basis: "manual_duration_from_prod", shelf_life_value: null, shelf_life_unit: null }),
      shelfItem({ expiry_basis: "manual_duration_from_today", production_date: null, shelf_life_value: null, shelf_life_unit: "year" }),
      shelfItem({ expiry_basis: "ocr_prod_plus_life", shelf_life_value: 1, shelf_life_unit: null }),
      shelfItem({ expiry_basis: "manual_duration_from_prod", shelf_life_value: 0, shelf_life_unit: "day" }),
    ]
    for (const item of missing) {
      const draft = draftFromItem(item)
      expect(draft.mode, item.expiry_basis).toBe("date")
      expect(patchFields(item, draft, today), item.expiry_basis).toEqual({ value: null })
      expect(patchFields(item, { ...draft, name: "新茶叶" }, today), item.expiry_basis).toEqual({ value: { name: "新茶叶" } })
    }
  })

  it("still switches a typed date over to a shelf calculation", () => {
    const item = stored({ expiry_date: "2027-11-15", production_date: "2026-11-15" })
    const draft = { ...draftFromItem(item), mode: "shelf" as const, shelfValue: 12, shelfUnit: "month" as const }
    expect(patchFields(item, draft, "2026-11-20")).toEqual({
      value: {
        shelf_life_value: 12,
        shelf_life_unit: "month",
        expiry_basis: "manual_duration_from_prod",
      },
    })
  })

  it("still switches a shelf item over to a typed expiry date", () => {
    const item = shelfItem({ expiry_basis: "manual_duration_from_prod" })
    const draft = { ...draftFromItem(item), mode: "date" as const, expiryDate: "2028-01-15" }
    const patched = patchFields(item, draft, today)
    expect(patched).toEqual({ value: { expiry_date: "2028-01-15", expiry_basis: "manual_date" } })
    expectDatesOmittedOrIso("value" in patched ? patched.value : null)
  })

  it("records a switch onto the same typed date without sending the date again", () => {
    const item = shelfItem({ expiry_basis: "ocr_prod_plus_life" })
    const draft = { ...draftFromItem(item), mode: "date" as const }
    expect(patchFields(item, draft, today)).toEqual({ value: { expiry_basis: "manual_date" } })
  })

  it("recomputes from today when a production date is cleared", () => {
    const item = shelfItem({ expiry_basis: "manual_duration_from_prod" })
    const draft = { ...draftFromItem(item), productionDate: "" }
    const patched = patchFields(item, draft, today)
    expect(patched).toEqual({
      value: {
        production_date: null,
        expiry_date: "2027-11-22",
        expiry_basis: "manual_duration_from_today",
      },
    })
    expectDatesOmittedOrIso("value" in patched ? patched.value : null)
  })

  it("updates only the expiry date when a typed date changes", () => {
    const item = stored()
    const draft = { ...draftFromItem(item), expiryDate: "2028-01-01" }
    const patched = patchFields(item, draft, "2026-11-20")
    expect(patched).toEqual({ value: { expiry_date: "2028-01-01" } })
    expectDatesOmittedOrIso("value" in patched ? patched.value : null)
  })
})

function shelfItem(patch: Partial<Item>): StoredItem {
  return stored({
    name: "茶叶",
    expiry_date: "2027-09-09",
    production_date: "2026-09-09",
    shelf_life_value: 1,
    shelf_life_unit: "year",
    expiry_basis: "manual_duration_from_prod",
    ...patch,
  })
}

function expectDatesOmittedOrIso(value: Record<string, unknown> | null) {
  if (!value) return
  for (const key of ["expiry_date", "production_date", "opened_date"]) {
    if (value[key] == null) continue
    expect(value[key]).toMatch(ISO_DATE)
  }
}

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
