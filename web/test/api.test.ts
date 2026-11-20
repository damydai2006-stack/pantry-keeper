import { describe, expect, it } from "vitest"
import { createClient, webSocketUrl } from "../src/api/client"
import { ApiError } from "../src/api/types"
import { applySync, parseWs, shouldPoll } from "../src/domain/sync"
import type { Item, SyncPage } from "../src/api/types"

const phone = "13" + "9".padStart(9, "0")

function item(patch: Partial<Item> = {}): Item {
  return {
    id: "item-1",
    name: "牛奶",
    category: "food",
    quantity: 1,
    location: "冰箱",
    note: null,
    expiry_date: "2026-11-14",
    production_date: null,
    shelf_life_value: null,
    shelf_life_unit: null,
    expiry_basis: "manual_date",
    opened_date: null,
    after_open_days: null,
    reminder_tiers: ["D30", "D5"],
    status: "in_stock",
    rev: 1,
    ...patch,
  }
}

describe("api client", () => {
  it("logs in as web and does not keep a bearer token", async () => {
    const seen: { url: string; init: RequestInit }[] = []
    const api = createClient({
      baseUrl: "",
      fetch: async (url, init) => {
        seen.push({ url, init: init ?? {} })
        return new Response(JSON.stringify({
          user_id: "user-1",
          display_name: "用户",
          client_kind: "web",
          token: "not-for-the-browser",
        }), { status: 200 })
      },
    })
    const result = await api.login(phone, "123456")
    expect(result).toEqual({ user_id: "user-1", display_name: "用户" })
    expect(JSON.stringify(result)).not.toContain("not-for-the-browser")
    const body = JSON.parse(String(seen[0].init.body))
    expect(body).toEqual({ phone, code: "123456", client_kind: "web" })
    expect(new Headers(seen[0].init.headers).get("X-Pantry-Request")).toBe("1")
    expect(seen[0].init.credentials).toBe("include")
  })

  it("does not send the cookie header on reads and rejects error payloads", async () => {
    const api = createClient({
      fetch: async (_url, init) => {
        expect(new Headers(init?.headers).get("X-Pantry-Request")).toBeNull()
        return new Response(JSON.stringify({ error: "not_found", message: "未找到", items: [{ id: "other", name: "别家" }] }), { status: 404 })
      },
    })
    await expect(api.sync(0)).rejects.toMatchObject({ status: 404, code: "not_found", message: "未找到" })
  })

  it("surfaces auth_disabled and a dead network without inventing a session", async () => {
    const disabled = createClient({
      fetch: async () => new Response(JSON.stringify({ error: "auth_disabled", message: "登录未开放" }), { status: 403 }),
    })
    await expect(disabled.requestCode(phone)).rejects.toBeInstanceOf(ApiError)
    const offline = createClient({
      fetch: async () => { throw new TypeError("Failed to fetch") },
    })
    await expect(offline.me()).rejects.toBeInstanceOf(TypeError)
  })

  it("treats a default-omitted sync body as an empty snapshot", async () => {
    const api = createClient({
      fetch: async () => new Response(JSON.stringify({ family: { id: "fam-1", name: "我家", admin_user_id: null, invite_code: "AB23CD", invite_expires_at: null, role: "admin", members: [] } }), { status: 200 }),
    })
    const page = await api.sync(0)
    expect(page.items).toEqual([])
    expect(page.tombstones).toEqual([])
    expect(page.reset).toBe(false)
    expect(page.family?.id).toBe("fam-1")
  })

  it("builds a same-origin socket url and does not invent a public host", () => {
    expect(webSocketUrl("")).toMatch(/^ws:\/\/.+\/api\/v1\/ws$/)
    expect(webSocketUrl("https://127.0.0.1:8443")).toBe("wss://127.0.0.1:8443/api/v1/ws")
  })
})

describe("sync merge", () => {
  const page = (patch: Partial<SyncPage>): SyncPage => ({
    reset: false,
    rev: 1,
    items: [],
    tombstones: [],
    family: null,
    notify_time: null,
    ...patch,
  })

  it("replaces the snapshot, applies a newer rev, and drops tombstones", () => {
    const first = applySync({ rev: 0, items: [] }, page({ rev: 2, items: [item(), item({ id: "item-2", name: "盐", rev: 2 })] }), 0)
    expect(first.items.map((row) => row.id)).toEqual(["item-1", "item-2"])
    const edited = applySync(first, page({ rev: 3, items: [item({ name: "鲜奶", rev: 3 })] }), 2)
    expect(edited.items.find((row) => row.id === "item-1")?.name).toBe("鲜奶")
    const stale = applySync(edited, page({ rev: 4, items: [item({ name: "旧", rev: 1 })] }), 3)
    expect(stale.items.find((row) => row.id === "item-1")?.name).toBe("鲜奶")
    const removed = applySync(stale, page({ rev: 5, tombstones: [{ item_id: "item-2", rev: 5 }] }), 4)
    expect(removed.items.map((row) => row.id)).toEqual(["item-1"])
  })

  it("does not wipe local rows when the server asks for a reset", () => {
    const state = { rev: 4, items: [item()] }
    expect(applySync(state, page({ reset: true, rev: 9, items: [] }), 4)).toBe(state)
  })

  it("parses socket events and polls only while disconnected and visible", () => {
    expect(parseWs('{"t":"ready"}')).toEqual({ t: "ready" })
    expect(parseWs('{"t":"changed","rev":3}')).toEqual({ t: "changed", rev: 3 })
    expect(parseWs('{"t":"removed_from_family"}')?.t).toBe("removed_from_family")
    expect(parseWs('{"t":"session_revoked"}')?.t).toBe("session_revoked")
    expect(parseWs("nope")).toBeNull()
    expect(shouldPoll(false, false)).toBe(true)
    expect(shouldPoll(true, false)).toBe(false)
    expect(shouldPoll(false, true)).toBe(false)
  })
})
