export type Me = {
  user_id: string
  display_name: string
  notify_time: string
  family_id: string | null
}

export type Member = {
  user_id: string
  display_name: string
  role: string
  joined_at: string
}

export type Family = {
  id: string
  name: string
  admin_user_id: string | null
  invite_code: string | null
  invite_expires_at: string | null
  role: string
  members: Member[]
}

export type Item = {
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
  photo_id?: string | null
  status_changed_at?: string | null
  rev: number
}

export type Tombstone = {
  item_id: string
  deleted_by?: string | null
  deleted_at?: string
  rev: number
}

export type SyncPage = {
  reset: boolean
  rev: number
  items: Item[]
  tombstones: Tombstone[]
  family: Family | null
  notify_time: string | null
}

export type LoginResult = {
  user_id: string
  display_name: string
}

export type PatchResult = {
  item: Item
  overwritten: { field: string; by_name: string }[]
  message: string | null
}

export class ApiError extends Error {
  readonly status: number
  readonly code: string

  constructor(status: number, code: string, message: string) {
    super(message)
    this.name = "ApiError"
    this.status = status
    this.code = code
  }
}

export function isApiError(error: unknown): error is ApiError {
  return error instanceof ApiError
}

export function isUnauthorized(error: unknown): boolean {
  return isApiError(error) && (error.status === 401 || error.code === "unauthorized")
}

export function isNotInFamily(error: unknown): boolean {
  return isApiError(error) && error.code === "not_in_family"
}

export function userMessage(error: unknown): string {
  if (isApiError(error)) return error.message || "操作失败"
  if (error instanceof TypeError) return "当前无网络，请稍后重试"
  return "操作失败，请稍后重试"
}
