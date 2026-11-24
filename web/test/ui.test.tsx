import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { render, screen, waitFor, within } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { describe, expect, it } from "vitest"
import { App } from "../src/App"
import type { PantryClient } from "../src/api/client"
import { ApiError, type Family, type Item, type Me } from "../src/api/types"
import type { SocketLike } from "../src/sync/useFamilySync"

const phone = "13" + "7".padStart(9, "0")

class QuietSocket implements SocketLike {
  onopen: (() => void) | null = null
  onmessage: ((event: { data: string }) => void) | null = null
  onclose: (() => void) | null = null
  close() {
    this.onclose?.()
  }
}

function family(name = "我家"): Family {
  return {
    id: "fam-1",
    name,
    admin_user_id: "user-1",
    invite_code: "AB23CD",
    invite_expires_at: "2026-11-27T00:00:00Z",
    role: "admin",
    members: [],
  }
}

function me(familyId: string | null): Me {
  return { user_id: "user-1", display_name: "用户", notify_time: "09:00", family_id: familyId }
}

function milk(): Item {
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
    rev: 3,
  }
}

function client(partial: Partial<PantryClient>): PantryClient {
  return {
    requestCode: async () => undefined,
    login: async () => ({ user_id: "user-1", display_name: "用户" }),
    logout: async () => undefined,
    me: async () => { throw new ApiError(401, "unauthorized", "请先登录") },
    createFamily: async () => family(),
    joinFamily: async () => family(),
    currentFamily: async () => family(),
    renameFamily: async () => {
      throw new Error("family unused")
    },
    resetInvite: async () => {
      throw new Error("family unused")
    },
    transferAdmin: async () => {
      throw new Error("family unused")
    },
    removeMember: async () => {
      throw new Error("family unused")
    },
    leaveFamily: async () => {
      throw new Error("family unused")
    },
    createItem: async () => milk(),
    patchItem: async () => ({ item: milk(), overwritten: [], message: null }),
    batch: async () => ({ rev: 4 }),
    undoBatch: async () => {
      throw new Error("this screen does not undo")
    },
    sync: async () => ({ reset: false, rev: 1, items: [], tombstones: [], family: family(), notify_time: "09:00" }),
    photo: async () => {
      throw new Error("photo unused")
    },
    webSocketUrl: () => "ws://127.0.0.1/api/v1/ws",
    ...partial,
  }
}

function renderApp(api: PantryClient) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <App api={api} now={() => new Date("2026-11-15T04:00:00Z")} socketFactory={() => new QuietSocket()} />
    </QueryClientProvider>,
  )
}

describe("session and family screens", () => {
  it("shows login, keeps a verifier off the page, and reports auth_disabled", async () => {
    const user = userEvent.setup()
    const calls: string[] = []
    const api = client({
      requestCode: async () => {
        calls.push("code")
        throw new ApiError(403, "auth_disabled", "登录未开放")
      },
    })
    renderApp(api)
    expect(await screen.findByLabelText("验证码")).toHaveValue("")
    expect(screen.queryByRole("button", { name: "调试填充" })).toBeNull()
    expect(screen.getByRole("button", { name: "登录 / 注册" })).toBeDisabled()
    await user.type(screen.getByLabelText("手机号"), phone)
    await user.type(screen.getByLabelText("验证码"), "123456")
    expect(screen.getByRole("button", { name: "登录 / 注册" })).toBeDisabled()
    await user.click(screen.getByRole("button", { name: "获取验证码" }))
    expect(await screen.findByRole("alert")).toHaveTextContent("登录未开放")
    expect(calls).toEqual(["code"])
    expect(screen.queryByText("家庭清单")).toBeNull()
  })

  it("requires agreement before login and stays on the form when the code is rejected", async () => {
    const user = userEvent.setup()
    let loggedIn = false
    const api = client({
      login: async () => {
        loggedIn = true
        throw new ApiError(401, "code_incorrect", "验证码不正确")
      },
    })
    renderApp(api)
    await screen.findByLabelText("手机号")
    await user.type(screen.getByLabelText("手机号"), phone)
    await user.type(screen.getByLabelText("验证码"), "123456")
    await user.click(screen.getByRole("checkbox"))
    await user.click(screen.getByRole("button", { name: "登录 / 注册" }))
    expect(await screen.findByRole("alert")).toHaveTextContent("验证码不正确")
    expect(loggedIn).toBe(true)
    expect(screen.getByRole("button", { name: "登录 / 注册" })).toBeEnabled()
  })

  it("shows a network failure instead of the login form", async () => {
    const api = client({ me: async () => { throw new TypeError("Failed to fetch") } })
    renderApp(api)
    expect(await screen.findByRole("alert")).toHaveTextContent("当前无网络，请稍后重试")
    expect(screen.queryByLabelText("手机号")).toBeNull()
  })

  it("creates a family and then shows an empty list", async () => {
    const user = userEvent.setup()
    let current = me(null)
    const api = client({
      me: async () => current,
      createFamily: async (name) => {
        current = me("fam-1")
        return family(name)
      },
    })
    renderApp(api)
    await user.type(await screen.findByLabelText("家庭名称"), "我家")
    await user.click(screen.getByRole("button", { name: "创建家庭" }))
    expect(await screen.findByText("这个家庭还没有在库物品")).toBeInTheDocument()
  })

  it("joins with a normalized invite and shows the server error for a bad code", async () => {
    const user = userEvent.setup()
    const seen: string[] = []
    const api = client({
      me: async () => me(null),
      joinFamily: async (code) => {
        seen.push(code)
        throw new ApiError(404, "invite_invalid", "邀请码无效")
      },
    })
    renderApp(api)
    await user.type(await screen.findByLabelText("邀请码"), "ab23cd")
    await user.click(screen.getByRole("button", { name: "加入家庭" }))
    expect(await screen.findByRole("alert")).toHaveTextContent("邀请码无效")
    expect(seen).toEqual(["AB23CD"])
  })
})

describe("item list", () => {
  it("shows expiry groups, saves a shelf-life item, and deletes it", async () => {
    const user = userEvent.setup()
    const created: Record<string, unknown>[] = []
    const batches: string[] = []
    const rows = [milk()]
    const api = client({
      me: async () => me("fam-1"),
      sync: async () => ({ reset: false, rev: rows.length, items: rows.filter((row) => row.status === "in_stock" || true), tombstones: [], family: family(), notify_time: "09:00" }),
      createItem: async (body) => {
        created.push(body)
        const next = milk()
        next.id = "item-2"
        next.name = String(body.name)
        next.expiry_date = "2026-12-15"
        rows.unshift(next)
        return next
      },
      batch: async (ids, action) => {
        batches.push(action)
        if (action === "delete") rows.forEach((row) => { if (ids.includes(row.id)) row.status = "deleted" })
        return { rev: 9 }
      },
    })
    api.sync = async () => ({
      reset: false,
      rev: 1,
      items: rows.filter((row) => row.status === "in_stock"),
      tombstones: [],
      family: family(),
      notify_time: "09:00",
    })
    renderApp(api)
    expect(await screen.findByText("已过期 1 天")).toBeInTheDocument()
    expect(screen.getByText("AB23CD")).toBeInTheDocument()
    await user.click(screen.getByRole("button", { name: "新增物品" }))
    await user.type(screen.getByLabelText("物品名称"), "酸奶")
    await user.click(screen.getByRole("button", { name: "1 个月" }))
    await user.click(screen.getByRole("button", { name: "保存并入库" }))
    await waitFor(() => expect(created).toHaveLength(1))
    expect(created[0]).toMatchObject({ name: "酸奶", shelf_life_value: 1, shelf_life_unit: "month", client_today: "2026-11-15" })
    expect(created[0]).not.toHaveProperty("expiry_date")
    await user.click(screen.getAllByRole("button", { name: /^删\s*除$/ })[0])
    const dialog = await screen.findByRole("dialog")
    await user.click(within(dialog).getByRole("button", { name: /^删\s*除$/ }))
    await waitFor(() => expect(batches).toContain("delete"))
  })

  it("shows a sync error and leaves the list when the family is gone", async () => {
    let reads = 0
    const api = client({
      me: async () => {
        reads += 1
        return me(reads === 1 ? "fam-1" : null)
      },
      sync: async () => { throw new ApiError(403, "not_in_family", "不在家庭中") },
    })
    renderApp(api)
    expect(await screen.findByText("你已被移出家庭", {}, { timeout: 3000 })).toBeInTheDocument()
    expect(await screen.findByRole("button", { name: "创建家庭" })).toBeInTheDocument()
  })
})
