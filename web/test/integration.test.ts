import { readFileSync } from "node:fs"
import { resolve } from "node:path"
import { describe, expect, it } from "vitest"
import WebSocket from "ws"
import { createClient, type FetchLike, type PantryClient } from "../src/api/client"
import { ApiError } from "../src/api/types"
import { addShelf, type ShelfUnit } from "../src/domain/dateMath"

type Creds = { base: string; phoneA: string; phoneB: string; code: string }
type Jar = { fetch: FetchLike; cookie: () => string; setCookie: () => string }

const envPath = "/tmp/pantry-it/env"

function loadCreds(): Creds {
  const text = readFileSync(envPath, "utf8")
  const read = (key: string) => text.match(new RegExp(`^${key}=(.*)$`, "m"))?.[1]?.trim() ?? ""
  const creds = {
    base: read("PANTRY_IT_BASE"),
    phoneA: read("PANTRY_IT_PHONE_A"),
    phoneB: read("PANTRY_IT_PHONE_B"),
    code: read("PANTRY_IT_CODE"),
  }
  if (!creds.base || !creds.phoneA || !creds.phoneB || !creds.code) {
    throw new Error("it server env is incomplete; start :server:itServer")
  }
  let host = ""
  try {
    host = new URL(creds.base).hostname
  } catch {
    throw new Error("PANTRY_IT_BASE is not a URL")
  }
  if (host !== "127.0.0.1" && host !== "localhost" && host !== "::1") {
    throw new Error("PANTRY_IT_BASE must point at loopback")
  }
  return creds
}

function outsiderPhone(): string {
  return "13" + "9".padStart(9, "0")
}

async function login(creds: Creds, phone: string): Promise<{ jar: Jar; api: PantryClient; userId: string }> {
  const codeCall = await fetch(creds.base + "/api/v1/auth/code", {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify({ phone }),
    redirect: "error",
  })
  const codeText = await codeCall.text()
  if (!codeCall.ok) throw new Error("code request failed: " + codeCall.status)
  if (codeText.includes(creds.code)) throw new Error("code response leaked the verifier")
  const wrong = creds.code === "000000" ? "111111" : "000000"
  const anonymous = createClient({ baseUrl: creds.base })
  await expect(anonymous.login(phone, wrong)).rejects.toBeInstanceOf(ApiError)
  const raw = await fetch(creds.base + "/api/v1/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify({ phone, code: creds.code, client_kind: "web" }),
    redirect: "error",
  })
  const text = await raw.text()
  if (!raw.ok) throw new Error("login failed: " + raw.status)
  const body = JSON.parse(text) as { user_id?: string; token?: unknown }
  if (typeof body.token === "string" && body.token) throw new Error("web login returned a bearer")
  const lines = typeof raw.headers.getSetCookie === "function" ? raw.headers.getSetCookie() : []
  const session = lines.find((line) => line.startsWith("pantry_session="))
  if (!session) throw new Error("session cookie missing")
  if (!session.includes("HttpOnly") || !session.includes("SameSite=Strict")) {
    throw new Error("session cookie is missing HttpOnly or SameSite=Strict")
  }
  const pair = session.split(";")[0] ?? ""
  const value = pair.slice(pair.indexOf("=") + 1)
  const fetchWithCookie = seededFetch(value)
  return {
    jar: { fetch: fetchWithCookie, cookie: () => value, setCookie: () => session },
    api: createClient({ baseUrl: creds.base, fetch: fetchWithCookie }),
    userId: body.user_id ?? "",
  }
}

function seededFetch(value: string): FetchLike {
  return async (input, init) => {
    const next = new Headers(init?.headers)
    next.set("Cookie", `pantry_session=${value}`)
    return fetch(input, { ...init, headers: next, redirect: "error" })
  }
}

async function raw(base: string, cookie: string, method: string, path: string, body?: unknown, extra?: HeadersInit) {
  const headers = new Headers(extra)
  if (cookie) headers.set("Cookie", `pantry_session=${cookie}`)
  if (body !== undefined) headers.set("Content-Type", "application/json")
  const response = await fetch(base + path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    redirect: "error",
  })
  const text = await response.text()
  let json: { error?: string; message?: string; name?: string } | null = null
  try {
    json = text ? JSON.parse(text) as { error?: string; message?: string; name?: string } : null
  } catch {
    json = null
  }
  return { status: response.status, json, text }
}

describe("loopback api", () => {
  const creds = loadCreds()

  it("rejects an unknown number, then issues two independent cookie sessions", async () => {
    const stranger = outsiderPhone()
    const denied = await fetch(creds.base + "/api/v1/auth/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ phone: stranger, code: creds.code, client_kind: "web" }),
      redirect: "error",
    })
    expect(denied.status).toBe(401)
    const a = await login(creds, creds.phoneA)
    const b = await login(creds, creds.phoneB)
    expect(a.userId).not.toBe(b.userId)
    expect(a.jar.cookie()).not.toBe(b.jar.cookie())
    const me = await raw(creds.base, a.jar.cookie(), "GET", "/api/v1/me")
    expect(me.status).toBe(200)
    const open = await raw(creds.base, "", "GET", "/api/v1/me")
    expect(open.status).toBe(401)
    const familyA = await a.api.createFamily("甲家")
    const familyB = await b.api.createFamily("乙家")
    expect(familyA.id).not.toBe(familyB.id)
    stash(a, b)
  }, 20000)

  it("rejects a missing or wrong CSRF header and accepts the real header", async () => {
    const a = need().a
    const missing = await raw(creds.base, a.jar.cookie(), "POST", "/api/v1/families", { name: "另一个家" })
    expect(missing.status).toBe(403)
    expect(missing.json?.error).toBe("csrf")
    const wrong = await raw(creds.base, a.jar.cookie(), "POST", "/api/v1/families", { name: "另一个家" }, { "X-Pantry-Request": "0" })
    expect(wrong.status).toBe(403)
    expect(wrong.json?.error).toBe("csrf")
    const accepted = await raw(creds.base, a.jar.cookie(), "POST", "/api/v1/families", { name: "另一个家" }, { "X-Pantry-Request": "1" })
    expect(accepted.status).toBe(409)
    expect(accepted.json?.error).toBe("already_in_family")
  })

  it("hides and refuses the other family's items", async () => {
    const { a, b } = need()
    const item = await a.api.createItem({
      name: "甲家牛奶",
      category: "food",
      quantity: 1,
      expiry_date: "2026-12-01",
      expiry_basis: "manual_date",
      reminder_tiers: [],
      client_today: "2026-11-20",
    })
    const read = await raw(creds.base, b.jar.cookie(), "GET", `/api/v1/items/${item.id}`)
    expect(read.status).toBe(404)
    expect(read.json?.name).toBeUndefined()
    const patch = await raw(
      creds.base,
      b.jar.cookie(),
      "PATCH",
      `/api/v1/items/${item.id}`,
      { base_rev: item.rev, fields: { name: "改掉" } },
      { "X-Pantry-Request": "1" },
    )
    expect(patch.status).toBe(404)
    const removed = await raw(
      creds.base,
      b.jar.cookie(),
      "POST",
      "/api/v1/items/batch",
      { ids: [item.id], action: "delete" },
      { "X-Pantry-Request": "1" },
    )
    expect(removed.status).toBe(404)
    const still = await raw(creds.base, a.jar.cookie(), "GET", `/api/v1/items/${item.id}`)
    expect(still.status).toBe(200)
    expect(still.json?.name).toBe("甲家牛奶")
    const syncB = await b.api.sync(0)
    expect(syncB.items.some((row) => row.id === item.id)).toBe(false)
  })

  it("matches shelf-life vectors to the server expiry", async () => {
    const { a } = need()
    const cases = JSON.parse(readFileSync(resolve(process.cwd(), "../testdata/date-cases.json"), "utf8")) as {
      dateMath: { id: string; op: string; start: string; amount: number; expect: string }[]
      shelfLife: { id: string; start?: string; value: number; unit: ShelfUnit; valid: boolean; expect?: string }[]
    }
    const rows = [
      ...cases.shelfLife.filter((row) => row.valid && row.start && row.expect).map((row) => ({
        id: row.id,
        start: row.start as string,
        value: row.value,
        unit: row.unit,
        expect: row.expect as string,
      })),
      ...cases.dateMath.filter((row) => row.amount > 0).map((row) => ({
        id: row.id,
        start: row.start,
        value: row.amount,
        unit: (row.op === "plusDays" ? "day" : row.op === "plusMonths" ? "month" : "year") as ShelfUnit,
        expect: row.expect,
      })),
    ]
    for (const row of rows) {
      const local = addShelf(row.start, row.value, row.unit)
      expect(local, row.id).toBe(row.expect)
      const saved = await a.api.createItem({
        name: row.id,
        category: "food",
        quantity: 1,
        production_date: row.start,
        shelf_life_value: row.value,
        shelf_life_unit: row.unit,
        expiry_basis: "manual_duration_from_prod",
        reminder_tiers: [],
        client_today: "2026-11-20",
      })
      expect(saved.expiry_date, row.id).toBe(row.expect)
    }
    const rejected = await raw(creds.base, a.jar.cookie(), "POST", "/api/v1/items", {
      name: "零保质期",
      shelf_life_value: 0,
      shelf_life_unit: "day",
      client_today: "2026-11-20",
    }, { "X-Pantry-Request": "1" })
    expect(rejected.status).toBe(400)
    expect(rejected.json?.error).toBe("invalid_shelf")
  }, 30000)

  it("lets one of two concurrent deletes win and leaves a single tombstone", async () => {
    const { a } = need()
    const item = await a.api.createItem({
      name: "并发删除",
      category: "food",
      quantity: 1,
      expiry_date: "2026-12-15",
      expiry_basis: "manual_date",
      reminder_tiers: [],
      client_today: "2026-11-20",
    })
    const call = () => raw(creds.base, a.jar.cookie(), "POST", "/api/v1/items/batch", {
      ids: [item.id],
      action: "delete",
    }, { "X-Pantry-Request": "1" })
    const [first, second] = await Promise.all([call(), call()])
    const statuses = [first.status, second.status].sort((left, right) => left - right)
    expect(statuses).toEqual([200, 404])
    const gone = await raw(creds.base, a.jar.cookie(), "GET", `/api/v1/items/${item.id}`)
    expect(gone.status).toBe(410)
    expect(gone.json?.error).toBe("item_deleted")
    const page = await a.api.sync(0)
    expect(page.items.some((row) => row.id === item.id)).toBe(false)
  })

  it("keeps websocket events inside the family and revokes only that session", async () => {
    const { a, b } = need()
    const framesA: string[] = []
    const framesB: string[] = []
    const sockA = new WebSocket(a.api.webSocketUrl(), { headers: { Cookie: `pantry_session=${a.jar.cookie()}` } })
    const sockB = new WebSocket(b.api.webSocketUrl(), { headers: { Cookie: `pantry_session=${b.jar.cookie()}` } })
    sockA.on("message", (data: unknown) => framesA.push(String(data)))
    sockB.on("message", (data: unknown) => framesB.push(String(data)))
    await Promise.all([opened(sockA), opened(sockB)])
    await expect.poll(
      () => framesA.some((frame) => frame.includes("ready")) && framesB.some((frame) => frame.includes("ready")),
      { timeout: 5000 },
    ).toBe(true)
    const item = await a.api.createItem({
      name: "只通知甲家",
      category: "food",
      quantity: 1,
      expiry_date: "2027-01-01",
      expiry_basis: "manual_date",
      reminder_tiers: [],
      client_today: "2026-11-20",
    })
    await expect.poll(() => framesA.some((frame) => frame.includes("changed")), { timeout: 5000 }).toBe(true)
    await new Promise((resolve) => setTimeout(resolve, 400))
    expect(framesB.some((frame) => frame.includes("changed") || frame.includes(item.id))).toBe(false)
    await a.api.logout()
    await expect.poll(() => framesA.some((frame) => frame.includes("session_revoked")), { timeout: 5000 }).toBe(true)
    expect(framesB.some((frame) => frame.includes("session_revoked"))).toBe(false)
    const dead = await raw(creds.base, a.jar.cookie(), "GET", "/api/v1/me")
    const alive = await raw(creds.base, b.jar.cookie(), "GET", "/api/v1/me")
    expect(dead.status).toBe(401)
    expect(alive.status).toBe(200)
    sockA.close()
    sockB.close()
  }, 20000)
})

type Pair = { jar: Jar; api: PantryClient; userId: string }
let pair: { a: Pair; b: Pair } | null = null

function stash(a: Pair, b: Pair) {
  pair = { a, b }
}

function need() {
  if (!pair) throw new Error("sessions were not created")
  return pair
}

function opened(socket: WebSocket) {
  return new Promise<void>((resolve, reject) => {
    socket.once("open", () => resolve())
    socket.once("error", () => reject(new Error("socket failed")))
  })
}
