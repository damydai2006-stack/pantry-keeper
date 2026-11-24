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

  it("posts used_up and discarded batches as ids and action only", async () => {
    const seen: { url: string; method: string; body: string; csrf: string | null }[] = []
    const api = createClient({
      fetch: async (url, init) => {
        seen.push({
          url,
          method: init?.method ?? "",
          body: String(init?.body ?? ""),
          csrf: new Headers(init?.headers).get("X-Pantry-Request"),
        })
        return new Response(JSON.stringify({ undo_token: "batch-token", rev: 4 }), { status: 200 })
      },
    })
    expect(await api.batch(["milk", "sauce"], "used_up")).toEqual({ undo_token: "batch-token", rev: 4 })
    expect(await api.batch(["sauce"], "discarded")).toEqual({ undo_token: "batch-token", rev: 4 })
    expect(seen.map((call) => call.url)).toEqual(["/api/v1/items/batch", "/api/v1/items/batch"])
    expect(seen[0]?.body).toBe(JSON.stringify({ ids: ["milk", "sauce"], action: "used_up" }))
    expect(seen[1]?.body).toBe(JSON.stringify({ ids: ["sauce"], action: "discarded" }))
    expect(seen.every((call) => call.method === "POST" && call.csrf === "1")).toBe(true)
    expect(seen.some((call) => call.url.includes("undo") || call.url.includes("purge"))).toBe(false)
  })

  it("posts a batch undo token and does not call purge", async () => {
    const seen: { url: string; method: string; body: string; csrf: string | null }[] = []
    const api = createClient({
      fetch: async (url, init) => {
        seen.push({
          url,
          method: init?.method ?? "",
          body: String(init?.body ?? ""),
          csrf: new Headers(init?.headers).get("X-Pantry-Request"),
        })
        return new Response(JSON.stringify({ skipped: 0, restored: 2 }), { status: 200 })
      },
    })
    expect(await api.undoBatch("undo-token")).toEqual({ skipped: 0, restored: 2, message: null })
    expect(seen).toEqual([{
      url: "/api/v1/items/batch/undo",
      method: "POST",
      body: JSON.stringify({ undo_token: "undo-token" }),
      csrf: "1",
    }])
    expect(seen.some((call) => call.url.includes("purge"))).toBe(false)
  })

  it("reads a photo with the session cookie and does not attach a token or query secret", async () => {
    const seen: { url: string; init: RequestInit }[] = []
    const bytes = Uint8Array.from([0xff, 0xd8, 0xff, 0xd9])
    const api = createClient({
      baseUrl: "http://127.0.0.1:18280",
      fetch: async (url, init) => {
        seen.push({ url, init: init ?? {} })
        return new Response(bytes, { status: 200, headers: { "content-type": "image/jpeg" } })
      },
    })
    const blob = await api.photo("11111111-1111-4111-8111-111111111111")
    expect(blob.type).toBe("image/jpeg")
    expect(blob.size).toBe(bytes.byteLength)
    expect(seen).toHaveLength(1)
    expect(seen[0]?.url).toBe("http://127.0.0.1:18280/api/v1/photos/11111111-1111-4111-8111-111111111111")
    expect(seen[0]?.init.method).toBe("GET")
    expect(seen[0]?.init.credentials).toBe("include")
    expect(seen[0]?.init.body).toBeUndefined()
    const headers = new Headers(seen[0]?.init.headers)
    expect(headers.get("Authorization")).toBeNull()
    expect(headers.get("X-Pantry-Request")).toBeNull()
    expect(seen[0]?.url).not.toContain("token")
    expect(seen[0]?.url).not.toContain("?")
    expect(seen[0]?.url).not.toContain("thumb")
    expect(seen[0]?.url).not.toContain("purge")
  })

  it("aborts an in-flight photo GET and keeps the abort error", async () => {
    const controller = new AbortController()
    let seenSignal: AbortSignal | undefined
    const api = createClient({
      fetch: (url, init) => new Promise((_resolve, reject) => {
        expect(url).toBe("/api/v1/photos/11111111-1111-4111-8111-111111111111")
        expect(init?.method).toBe("GET")
        expect(init?.credentials).toBe("include")
        expect(new Headers(init?.headers).get("Authorization")).toBeNull()
        seenSignal = init?.signal ?? undefined
        const signal = init?.signal
        if (!signal) {
          reject(new Error("missing signal"))
          return
        }
        signal.addEventListener("abort", () => {
          reject(new DOMException("The operation was aborted.", "AbortError"))
        }, { once: true })
      }),
    })
    const pending = api.photo("11111111-1111-4111-8111-111111111111", controller.signal)
    expect(seenSignal).toBe(controller.signal)
    expect(seenSignal?.aborted).toBe(false)
    controller.abort()
    await expect(pending).rejects.toMatchObject({ name: "AbortError" })
    await expect(pending).rejects.not.toMatchObject({ message: "当前无网络，请稍后重试" })
    expect(seenSignal?.aborted).toBe(true)
  })

  it("encodes the photo id into one path segment", async () => {
    let url = ""
    const api = createClient({
      fetch: async (input) => {
        url = input
        return new Response(Uint8Array.from([1, 2, 3, 4]), {
          status: 200,
          headers: { "content-type": "image/webp" },
        })
      },
    })
    const blob = await api.photo("abc/def token")
    expect(blob.type).toBe("image/webp")
    expect(url).toBe("/api/v1/photos/abc%2Fdef%20token")
  })

  it("maps a missing photo to not_found and rejects a non-image body", async () => {
    const missing = createClient({
      fetch: async () => new Response(JSON.stringify({ error: "not_found", message: "未找到" }), {
        status: 404,
        headers: { "content-type": "application/json" },
      }),
    })
    await expect(missing.photo("gone")).rejects.toMatchObject({ status: 404, code: "not_found", message: "未找到" })
    const html = createClient({
      fetch: async () => new Response("<html>nope</html>", {
        status: 200,
        headers: { "content-type": "text/html" },
      }),
    })
    await expect(html.photo("gone")).rejects.toMatchObject({ code: "unsupported_photo" })
    const empty = createClient({
      fetch: async () => new Response(new Uint8Array(), {
        status: 200,
        headers: { "content-type": "image/jpeg" },
      }),
    })
    await expect(empty.photo("gone")).rejects.toMatchObject({ code: "unsupported_photo" })
    const offline = createClient({
      fetch: async () => {
        throw new TypeError("Failed to fetch")
      },
    })
    await expect(offline.photo("gone")).rejects.toBeInstanceOf(TypeError)
    const failed = createClient({
      fetch: async () => new Response(JSON.stringify({ error: "http", message: "请求失败" }), { status: 500 }),
    })
    await expect(failed.photo("gone")).rejects.toMatchObject({ status: 500, code: "http", message: "请求失败" })
  })

  it("updates the family with cookie writes and does not call purge or share", async () => {
    const seen: { url: string; method: string; body: string; csrf: string | null; authorization: string | null; credentials: RequestCredentials | undefined }[] = []
    const family = {
      id: "fam-1",
      name: "新家",
      admin_user_id: "user-1",
      invite_code: "AB23CD",
      invite_expires_at: "2026-11-22T00:00:00Z",
      role: "admin",
      members: [{ user_id: "user-2", display_name: "小米", role: "member", joined_at: "2026-11-01T00:00:00Z" }],
    }
    const api = createClient({
      fetch: async (url, init) => {
        const headers = new Headers(init?.headers)
        seen.push({
          url,
          method: init?.method ?? "",
          body: String(init?.body ?? ""),
          csrf: headers.get("X-Pantry-Request"),
          authorization: headers.get("Authorization"),
          credentials: init?.credentials,
        })
        if (url.endsWith("/leave") || url.includes("/members/")) {
          return new Response(JSON.stringify({ status: "ok" }), { status: 200 })
        }
        return new Response(JSON.stringify(family), { status: 200 })
      },
    })
    expect((await api.renameFamily("新家")).name).toBe("新家")
    expect((await api.resetInvite()).invite_code).toBe("AB23CD")
    expect((await api.transferAdmin("user-2")).role).toBe("admin")
    await api.removeMember("user/2")
    await api.leaveFamily()
    expect(seen.map((call) => [call.method, call.url, call.body])).toEqual([
      ["PATCH", "/api/v1/families/current", JSON.stringify({ name: "新家" })],
      ["POST", "/api/v1/families/current/invite/reset", JSON.stringify({})],
      ["POST", "/api/v1/families/current/admin", JSON.stringify({ user_id: "user-2" })],
      ["DELETE", "/api/v1/families/current/members/user%2F2", ""],
      ["POST", "/api/v1/families/leave", JSON.stringify({})],
    ])
    expect(seen.every((call) => call.csrf === "1" && call.credentials === "include" && call.authorization === null)).toBe(true)
    expect(JSON.stringify(seen)).not.toContain("purge")
    expect(JSON.stringify(seen)).not.toContain("share")
    expect(JSON.stringify(seen)).not.toContain("token")
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
