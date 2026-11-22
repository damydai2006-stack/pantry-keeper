import { addShelf, isValidIsoDate, shelfAllowed, type ShelfUnit } from "./dateMath"
import { itemNameError, locationError, noteError } from "./text"

export const CATEGORIES = [
  ["food", "食品"],
  ["medicine", "药品"],
  ["condiment", "调料"],
  ["other", "其他"],
] as const

export const LOCATIONS = ["冰箱", "冷冻", "橱柜", "药箱"] as const

export const SHELF_PRESETS: { value: number; unit: ShelfUnit; label: string }[] = [
  { value: 7, unit: "day", label: "7 天" },
  { value: 1, unit: "month", label: "1 个月" },
  { value: 6, unit: "month", label: "6 个月" },
  { value: 1, unit: "year", label: "1 年" },
  { value: 2, unit: "year", label: "2 年" },
]

export const TIER_OPTIONS = [
  ["D5", "提前 5 天"],
  ["D10", "提前 10 天"],
  ["D30", "提前 30 天"],
  ["M2", "提前 2 个月"],
  ["M3", "提前 3 个月"],
] as const

export type ItemDraft = {
  name: string
  category: string
  quantity: number
  location: string
  note: string
  mode: "date" | "shelf"
  expiryDate: string
  productionDate: string
  shelfValue: number | null
  shelfUnit: ShelfUnit
  tiers: string[]
  openedDate: string
  afterOpenDays: string
}

export type StoredItem = {
  id: string
  name: string
  category: string
  quantity: number
  location: string | null
  note: string | null
  expiry_date: string
  production_date: string | null
  shelf_life_value: number | null
  shelf_life_unit: string | null
  expiry_basis: string
  opened_date: string | null
  after_open_days: number | null
  reminder_tiers: string[]
  status: string
  rev: number
}

export function emptyDraft(): ItemDraft {
  return {
    name: "",
    category: "food",
    quantity: 1,
    location: "冰箱",
    note: "",
    mode: "shelf",
    expiryDate: "",
    productionDate: "",
    shelfValue: null,
    shelfUnit: "day",
    tiers: ["D30", "D5"],
    openedDate: "",
    afterOpenDays: "",
  }
}

const DURATION_BASES = new Set(["manual_duration_from_prod", "manual_duration_from_today", "ocr_prod_plus_life"])

function shelfUnitOf(unit: string | null): ShelfUnit | null {
  if (unit === "day" || unit === "month" || unit === "year") return unit
  return null
}

function hasShelf(item: StoredItem): boolean {
  const unit = shelfUnitOf(item.shelf_life_unit)
  return item.shelf_life_value != null && unit != null && shelfAllowed(item.shelf_life_value, unit)
}

// Duration rows that still have a usable shelf reopen in shelf mode.
// A package date or a typed date stays on that date, even when shelf columns are left behind.
function restoredMode(item: StoredItem): ItemDraft["mode"] {
  return DURATION_BASES.has(item.expiry_basis) && hasShelf(item) ? "shelf" : "date"
}

export function draftFromItem(item: StoredItem): ItemDraft {
  return {
    name: item.name,
    category: item.category,
    quantity: item.quantity,
    location: item.location ?? "",
    note: item.note ?? "",
    mode: restoredMode(item),
    expiryDate: item.expiry_date,
    productionDate: item.production_date ?? "",
    shelfValue: item.shelf_life_value,
    shelfUnit: shelfUnitOf(item.shelf_life_unit) ?? "day",
    tiers: [...item.reminder_tiers],
    openedDate: item.opened_date ?? "",
    afterOpenDays: item.after_open_days == null ? "" : String(item.after_open_days),
  }
}

export type ResolvedDraft = {
  expiry: string
  basis: string
  origin: string
}

export function resolveDraft(draft: ItemDraft, today: string): ResolvedDraft | { error: string } {
  const name = itemNameError(draft.name)
  if (name) return { error: name }
  if (!Number.isInteger(draft.quantity) || draft.quantity < 1) return { error: "数量至少为 1" }
  const location = locationError(draft.location.trim())
  if (location) return { error: location }
  const note = noteError(draft.note)
  if (note) return { error: note }
  const production = draft.productionDate.trim()
  if (production && !isValidIsoDate(production)) return { error: "日期不正确" }
  const opened = medicineOpened(draft)
  if ("error" in opened) return opened
  if (draft.mode === "date") {
    if (!isValidIsoDate(draft.expiryDate.trim())) return { error: "需要到期日或可推算的保质期" }
    return { expiry: draft.expiryDate.trim(), basis: "manual_date", origin: "按填写的到期日" }
  }
  if (draft.shelfValue == null || !shelfAllowed(draft.shelfValue, draft.shelfUnit)) {
    return { error: "保质期需要在 1 天到 10 年之间" }
  }
  const start = production || today
  const basis = production ? "manual_duration_from_prod" : "manual_duration_from_today"
  const origin = production ? `从生产日期 ${production} 起算` : `未填写生产日期，从入库当天（${today}）起算`
  return { expiry: addShelf(start, draft.shelfValue, draft.shelfUnit), basis, origin: `${origin}。按此计算，到期日为` }
}

export function previewDraft(draft: ItemDraft, today: string, original?: StoredItem | null): ResolvedDraft | { error: string } {
  const resolved = resolveDraft(draft, today)
  if ("error" in resolved || !original || !expiryCalculationUnchanged(original, draft)) return resolved
  if (resolved.expiry === original.expiry_date) return { ...resolved, basis: original.expiry_basis }
  const production = draft.productionDate.trim()
  const origin = production
    ? `从生产日期 ${production} 起算。按此计算，到期日为`
    : `未填写生产日期，从入库当天起算。按此计算，到期日为`
  return { expiry: original.expiry_date, basis: original.expiry_basis, origin }
}

export function createBody(draft: ItemDraft, today: string): { error: string } | { value: Record<string, unknown> } {
  const resolved = resolveDraft(draft, today)
  if ("error" in resolved) return { error: resolved.error }
  const opened = medicineOpened(draft)
  if ("error" in opened) return opened
  const body: Record<string, unknown> = {
    name: draft.name.trim(),
    category: draft.category,
    quantity: draft.quantity,
    reminder_tiers: draft.tiers,
    client_today: today,
    expiry_basis: resolved.basis,
  }
  const location = draft.location.trim()
  if (location) body.location = location
  const note = draft.note.trim()
  if (note) body.note = note
  const production = draft.productionDate.trim()
  if (production) body.production_date = production
  if (draft.mode === "date") body.expiry_date = resolved.expiry
  else {
    body.shelf_life_value = draft.shelfValue
    body.shelf_life_unit = draft.shelfUnit
  }
  if (opened.openedDate) {
    body.opened_date = opened.openedDate
    body.after_open_days = opened.afterOpenDays
  }
  return { value: body }
}

export function patchFields(original: StoredItem, draft: ItemDraft, today: string): { error: string } | { value: Record<string, unknown> | null } {
  const resolved = resolveDraft(draft, today)
  if ("error" in resolved) return { error: resolved.error }
  const opened = medicineOpened(draft)
  if ("error" in opened) return opened
  const fields: Record<string, unknown> = {}
  const name = draft.name.trim()
  if (name !== original.name) fields.name = name
  if (draft.category !== original.category) fields.category = draft.category
  if (draft.quantity !== original.quantity) fields.quantity = draft.quantity
  const location = draft.location.trim()
  if (location !== (original.location ?? "")) fields.location = location || null
  const note = draft.note.trim()
  if (note !== (original.note ?? "")) fields.note = note || null
  // Leave a stored expiry and its basis alone until the user edits that calculation.
  // Opening a today-anchored shelf on a later day must not slide the date forward,
  // and an untouched OCR source must not be rewritten as a manual basis.
  if (!expiryCalculationUnchanged(original, draft)) {
    if (resolved.expiry !== original.expiry_date) fields.expiry_date = resolved.expiry
    if (draft.mode === "shelf") {
      if (draft.shelfValue !== original.shelf_life_value) fields.shelf_life_value = draft.shelfValue
      if (draft.shelfUnit !== (original.shelf_life_unit ?? "")) fields.shelf_life_unit = draft.shelfUnit
    }
    if (resolved.basis !== original.expiry_basis) fields.expiry_basis = resolved.basis
  }
  const production = draft.productionDate.trim()
  if (production !== (original.production_date ?? "")) fields.production_date = production || null
  if (!sameTiers(draft.tiers, original.reminder_tiers)) fields.reminder_tiers = draft.tiers
  const nextOpened = opened.openedDate
  const prevOpened = original.opened_date
  if (nextOpened !== prevOpened) fields.opened_date = nextOpened
  if (opened.afterOpenDays !== original.after_open_days) fields.after_open_days = opened.afterOpenDays
  return { value: Object.keys(fields).length === 0 ? null : fields }
}

function expiryCalculationUnchanged(original: StoredItem, draft: ItemDraft): boolean {
  if (draft.mode !== restoredMode(original)) return false
  if (draft.mode === "shelf") {
    return draft.shelfValue === original.shelf_life_value
      && draft.shelfUnit === shelfUnitOf(original.shelf_life_unit)
      && draft.productionDate.trim() === (original.production_date ?? "")
  }
  return draft.expiryDate.trim() === original.expiry_date
}

function sameTiers(left: readonly string[], right: readonly string[]): boolean {
  if (left.length !== right.length) return false
  return left.every((tier, index) => tier === right[index])
}

function medicineOpened(draft: ItemDraft): { error: string } | { openedDate: string | null; afterOpenDays: number | null } {
  if (draft.category !== "medicine") return { openedDate: null, afterOpenDays: null }
  const opened = draft.openedDate.trim()
  const daysText = draft.afterOpenDays.trim()
  if (!opened && !daysText) return { openedDate: null, afterOpenDays: null }
  if (!isValidIsoDate(opened) || !/^\d+$/.test(daysText)) return { error: "开封日期和开封后天数需要一起填写" }
  const days = Number(daysText)
  if (!shelfAllowed(days, "day")) return { error: "开封后天数需要在 1 天到 10 年之间" }
  return { openedDate: opened, afterOpenDays: days }
}
