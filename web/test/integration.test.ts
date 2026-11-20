import { readFileSync } from "node:fs"
import { describe, expect, it } from "vitest"
import WebSocket from "ws"
import { createClient, type FetchLike } from "../src/api/client"
import { ApiError } from "../src/api/types"
import { addShelf } from "../src/domain/dateMath"

const base = process.env.PANTRY_IT_BASE
const enabled = Boolean(base)

function assertLoopback(raw: string) {
  let host = ""
  try {
    host = new URL(raw).hostname
  } catch {
    throw new Error("PANTRY_IT_BASE is not a URL")
  }
  if (host !== "127.0.0.1" && host !== "localhost" && host !== "::1") {
    throw new Error("PANTRY_IT_BASE must point at loopback")
  }
}

function credentials(): { phone: string; code: string } {
  const text = readFileSync("/tmp/pantry-smoke/env", "utf8")
  const phone = text.match(/^PANTRY_SMOKE_PHONE=(.*)$/m)?.[1]?.trim()
  const code = text.match(/^PANTRY_SMOKE_CODE=(.*)$/m)?.[1]?.trim()
  if (!phone || !code) throw new Error("local smoke credentials are missing")
  return { phone, code }
}

function secondJar(target: { jar: Map<string, string> }, value: string) {
  target.jar.set("pantry_session", value)
}

function jarFetch(): { fetch: FetchLike; cookie: () => string; jar: Map<string, string> } {
  const jar = new Map<string, string>()
  const fetchImpl: FetchLike = async (input, init) => {
    const headers = new Headers(init?.headers)
    if (jar.size > 0) headers.set("Cookie", [...jar].map(([name, value]) => `${name}=${value}`).join("; "))
    const response = await fetch(input, { ...init, headers, redirect: "error" })
    const lines = typeof response.headers.getSetCookie === "function" ? response.headers.getSetCookie() : []
    for (const line of lines) {
      const pair = line.split(";")[0] ?? ""
      const index = pair.indexOf("=")
      if (index <= 0) continue
      const name = pair.slice(0, index).trim()
      const value = pair.slice(index + 1).trim()
      if (value) jar.set(name, value)
      else jar.delete(name)
    }
    return response
  }
  return { fetch: fetchImpl, cookie: () => jar.get("pantry_session") ?? "", jar }
}

describe.skipIf(!enabled)("local api", () => {
  it("logs in with the cookie protocol and syncs a family across two sessions", async () => {
    if (!base) throw new Error("PANTRY_IT_BASE")
    assertLoopback(base)
    const { phone, code } = credentials()
    const wrong = code === "000000" ? "111111" : "000000"
    const first = jarFetch()
    const second = jarFetch()
    const left = createClient({ baseUrl: base, fetch: first.fetch })
    const right = createClient({ baseUrl: base, fetch: second.fetch })

    const codeResponse = await fetch(base + "/api/v1/auth/code", {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify({ phone }),
      redirect: "error",
    })
    const codeText = await codeResponse.text()
    if (!codeResponse.ok) throw new Error("code request failed")
    if (codeText.includes(code)) throw new Error("code response leaked the verifier")

    await expect(left.login(phone, wrong)).rejects.toBeInstanceOf(ApiError)
    await left.login(phone, code)
    if (!first.cookie()) throw new Error("session cookie was not stored")
    // A second login needs another code, and the server accepts one code request per minute.
    // The second connection reuses this session cookie; it still has its own HTTP calls and socket.
    secondJar(second, first.cookie())

    await expect(left.sync(0)).rejects.toMatchObject({ code: "not_in_family" })
    const created = await left.createFamily("网页家庭")
    expect(created.invite_code).toMatch(/^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{6}$/)
    const seen = await right.sync(0)
    expect(seen.family?.id).toBe(created.id)
    expect(seen.items).toEqual([])

    const blocked = await fetch(base + "/api/v1/items", {
      method: "POST",
      headers: { "Content-Type": "application/json", Cookie: `pantry_session=${first.cookie()}` },
      body: JSON.stringify({ name: "无令牌头", client_today: "2026-11-20" }),
      redirect: "error",
    })
    expect(blocked.status).toBe(403)

    const today = "2026-11-15"
    const item = await left.createItem({
      name: "牛奶",
      category: "food",
      quantity: 1,
      production_date: today,
      shelf_life_value: 12,
      shelf_life_unit: "month",
      expiry_basis: "manual_duration_from_prod",
      reminder_tiers: ["D30", "D5"],
      client_today: today,
    })
    expect(item.expiry_date).toBe(addShelf(today, 12, "month"))

    const socket = new WebSocket(right.webSocketUrl(), { headers: { Cookie: `pantry_session=${second.cookie()}` } })
    const frames: string[] = []
    socket.on("message", (data: unknown) => frames.push(String(data)))
    await new Promise<void>((resolve, reject) => {
      socket.once("open", () => resolve())
      socket.once("error", () => reject(new Error("socket failed")))
    })

    const patched = await left.patchItem(item.id, item.rev, { name: "鲜奶" })
    expect(patched.item.name).toBe("鲜奶")
    await expect.poll(() => frames.some((frame) => frame.includes("changed")), { timeout: 5000 }).toBe(true)
    const afterEdit = await right.sync(seen.rev)
    expect(afterEdit.items.some((row) => row.id === item.id && row.name === "鲜奶")).toBe(true)

    await left.batch([item.id], "delete")
    const afterDelete = await right.sync(0)
    expect(afterDelete.items.some((row) => row.id === item.id)).toBe(false)
    await left.logout()
    await expect(left.me()).rejects.toMatchObject({ status: 401 })
    socket.close()
  }, 20000)
})
