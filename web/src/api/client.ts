import {
  ApiError,
  type Family,
  type Item,
  type LoginResult,
  type Me,
  type PatchResult,
  type SyncPage,
} from "./types"

// kotlinx.serialization omits values that equal the Kotlin default.
// An empty snapshot therefore has no `items` field. Missing lists are empty.

export type FetchLike = (input: string, init?: RequestInit) => Promise<Response>

export type PantryClient = {
  requestCode(phone: string): Promise<void>
  login(phone: string, code: string): Promise<LoginResult>
  logout(): Promise<void>
  me(): Promise<Me>
  createFamily(name: string): Promise<Family>
  joinFamily(code: string): Promise<Family>
  currentFamily(): Promise<Family>
  createItem(body: Record<string, unknown>): Promise<Item>
  patchItem(id: string, baseRev: number, fields: Record<string, unknown>): Promise<PatchResult>
  batch(ids: string[], action: string): Promise<{ undo_token?: string | null; rev: number }>
  sync(since: number): Promise<SyncPage>
  webSocketUrl(): string
}

export function apiBase(): string {
  const configured = import.meta.env.VITE_API_BASE ?? ""
  return configured.replace(/\/$/, "")
}

export function createClient(options: { baseUrl?: string; fetch?: FetchLike } = {}): PantryClient {
  const baseUrl = (options.baseUrl ?? apiBase()).replace(/\/$/, "")
  const fetchImpl = options.fetch ?? ((input, init) => fetch(input, init))

  async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
    const headers = new Headers()
    headers.set("Accept", "application/json")
    if (body !== undefined) headers.set("Content-Type", "application/json")
    if (method !== "GET" && method !== "HEAD") headers.set("X-Pantry-Request", "1")
    let response: Response
    try {
      response = await fetchImpl(baseUrl + path, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
        credentials: "include",
      })
    } catch (error) {
      if (error instanceof TypeError) throw error
      throw new TypeError("当前无网络，请稍后重试")
    }
    const text = await response.text()
    if (!response.ok) throw parseError(response.status, text)
    if (!text) return undefined as T
    return JSON.parse(text) as T
  }

  return {
    requestCode: (phone) => request("POST", "/api/v1/auth/code", { phone }),
    login: async (phone, code) => {
      const body = await request<{ user_id: string; display_name: string; token?: string | null }>(
        "POST",
        "/api/v1/auth/login",
        { phone, code, client_kind: "web" },
      )
      return { user_id: body.user_id, display_name: body.display_name }
    },
    logout: () => request("POST", "/api/v1/auth/logout", {}),
    me: () => request("GET", "/api/v1/me"),
    createFamily: (name) => request("POST", "/api/v1/families", { name }),
    joinFamily: (code) => request("POST", "/api/v1/families/join", { code }),
    currentFamily: () => request("GET", "/api/v1/families/current"),
    createItem: (body) => request("POST", "/api/v1/items", body),
    patchItem: (id, baseRev, fields) => request("PATCH", `/api/v1/items/${id}`, { base_rev: baseRev, fields }),
    batch: (ids, action) => request("POST", "/api/v1/items/batch", { ids, action }),
    sync: async (since) => normalizeSync(await request<Partial<SyncPage>>("GET", `/api/v1/sync?since=${since}`)),
    webSocketUrl: () => webSocketUrl(baseUrl),
  }
}

export function webSocketUrl(baseUrl: string): string {
  if (baseUrl.startsWith("https://")) return `wss://${baseUrl.slice("https://".length)}/api/v1/ws`
  if (baseUrl.startsWith("http://")) return `ws://${baseUrl.slice("http://".length)}/api/v1/ws`
  const proto = location.protocol === "https:" ? "wss:" : "ws:"
  return `${proto}//${location.host}${baseUrl}/api/v1/ws`
}

function normalizeSync(raw: Partial<SyncPage> | null | undefined): SyncPage {
  return {
    reset: raw?.reset ?? false,
    rev: raw?.rev ?? 0,
    items: raw?.items ?? [],
    tombstones: raw?.tombstones ?? [],
    family: raw?.family ?? null,
    notify_time: raw?.notify_time ?? null,
  }
}

function parseError(status: number, text: string): ApiError {
  try {
    const body = JSON.parse(text) as { error?: unknown; message?: unknown }
    const code = typeof body.error === "string" ? body.error : "http"
    const message = typeof body.message === "string" && body.message ? body.message : "请求失败"
    return new ApiError(status, code, message)
  } catch {
    return new ApiError(status, "http", "请求失败")
  }
}
