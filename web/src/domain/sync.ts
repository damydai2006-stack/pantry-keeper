import type { Family, Item, SyncPage } from "../api/types"

export type SyncState = {
  rev: number
  items: Item[]
}

export type WsEvent =
  | { t: "ready" }
  | { t: "changed"; rev?: number }
  | { t: "removed_from_family" }
  | { t: "session_revoked" }

export function applySync(state: SyncState, page: SyncPage, requestedSince: number): SyncState {
  if (page.reset) return state
  if (requestedSince === 0) {
    const gone = new Set(page.tombstones.map((row) => row.item_id))
    return { rev: page.rev, items: page.items.filter((item) => !gone.has(item.id)) }
  }
  const map = new Map(state.items.map((item) => [item.id, item]))
  for (const item of page.items) {
    const prev = map.get(item.id)
    if (!prev || item.rev >= prev.rev) map.set(item.id, item)
  }
  for (const tomb of page.tombstones) map.delete(tomb.item_id)
  return { rev: page.rev, items: [...map.values()] }
}

export function absorbPage(state: SyncState, page: SyncPage, requestedSince: number): { state: SyncState; family: Family | null } {
  return { state: applySync(state, page, requestedSince), family: page.family }
}

export function parseWs(text: string): WsEvent | null {
  try {
    const body = JSON.parse(text) as { t?: unknown; rev?: unknown }
    if (body.t === "ready") return { t: "ready" }
    if (body.t === "changed") return { t: "changed", rev: typeof body.rev === "number" ? body.rev : undefined }
    if (body.t === "removed_from_family") return { t: "removed_from_family" }
    if (body.t === "session_revoked") return { t: "session_revoked" }
    return null
  } catch {
    return null
  }
}

export const POLL_MS = 5000

export function shouldPoll(socketOpen: boolean, hidden: boolean): boolean {
  return !socketOpen && !hidden
}
