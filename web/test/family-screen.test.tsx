import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { useState } from "react"
import { afterEach, describe, expect, it, vi } from "vitest"
import { App } from "../src/App"
import type { PantryClient } from "../src/api/client"
import { ApiError, type Family, type Item, type Me, type Member } from "../src/api/types"
import type { SocketLike } from "../src/sync/useFamilySync"
import { ItemScreen } from "../src/ui/ItemScreen"
import { ConfigProvider } from "antd"
import zhCN from "antd/locale/zh_CN"

class QuietSocket implements SocketLike {
  onopen: (() => void) | null = null
  onmessage: ((event: { data: string }) => void) | null = null
  onclose: (() => void) | null = null
  close() {
    this.onclose?.()
  }
}

const clipboardState: { writeText: ReturnType<typeof vi.fn<(text: string) => Promise<void>>> | null } = { writeText: null }
const shareSpy = vi.fn()

afterEach(() => {
  clipboardState.writeText = null
  shareSpy.mockReset()
  Object.defineProperty(navigator, "share", { configurable: true, value: shareSpy })
})

function member(userId: string, name: string, role: string, joinedAt: string): Member {
  return { user_id: userId, display_name: name, role, joined_at: joinedAt }
}

function household(role: "admin" | "member" = "admin"): Family {
  const viewerRole = role
  return {
    id: "fam-1",
    name: "我家",
    admin_user_id: role === "admin" ? "user-1" : "user-2",
    invite_code: "AB23CD",
    invite_expires_at: "2026-11-22T08:00:00Z",
    role: viewerRole,
    members: [
      member("user-3", "小红", "member", "2026-11-10T00:00:00Z"),
      member("user-1", "自己", viewerRole, "2026-11-03T00:00:00Z"),
      member("user-2", "小米", role === "admin" ? "member" : "admin", "2026-11-01T00:00:00Z"),
    ],
  }
}

function alone(): Family {
  return {
    id: "fam-1",
    name: "我家",
    admin_user_id: "user-1",
    invite_code: "AB23CD",
    invite_expires_at: "2026-11-22T08:00:00Z",
    role: "admin",
    members: [member("user-1", "自己", "admin", "2026-11-03T00:00:00Z")],
  }
}

function milk(): Item {
  return {
    id: "item-1",
    name: "牛奶",
    category: "food",
    quantity: 1,
    location: "冰箱",
    note: null,
    expiry_date: "2026-11-20",
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

type Harness = {
  api: PantryClient
  family: () => Family
  setFamily: (next: Family) => void
  session: () => Me
  setSession: (next: Me) => void
  rows: () => Item[]
  setRows: (next: Item[]) => void
  calls: string[]
  protectedCalls: string[]
}

function harness(initial: Family): Harness {
  let current = initial
  let session: Me = { user_id: "user-1", display_name: "自己", notify_time: "09:00", family_id: "fam-1" }
  let rows = [milk()]
  const calls: string[] = []
  const protectedCalls: string[] = []
  const api: PantryClient = {
    requestCode: async () => undefined,
    login: async () => ({ user_id: "user-1", display_name: "自己" }),
    logout: async () => undefined,
    me: async () => session,
    createFamily: async (name) => {
      calls.push(`create:${name}`)
      session = { ...session, family_id: "fam-2" }
      rows = []
      current = {
        ...alone(),
        id: "fam-2",
        name,
        invite_code: "ZZ99ZZ",
        members: [member("user-1", "自己", "admin", "2026-11-24T00:00:00Z")],
      }
      return current
    },
    joinFamily: async (code) => {
      calls.push(`join:${code}`)
      return current
    },
    currentFamily: async () => current,
    renameFamily: async (name) => {
      calls.push(`rename:${name}`)
      current = { ...current, name }
      return current
    },
    resetInvite: async () => {
      calls.push("reset")
      current = { ...current, invite_code: "CD45EF", invite_expires_at: "2026-12-01T00:00:00Z" }
      return current
    },
    transferAdmin: async (userId) => {
      calls.push(`transfer:${userId}`)
      current = {
        ...current,
        role: "member",
        admin_user_id: userId,
        members: current.members.map((person) => {
          if (person.user_id === "user-1") return { ...person, role: "member" }
          if (person.user_id === userId) return { ...person, role: "admin" }
          return person
        }),
      }
      return current
    },
    removeMember: async (userId) => {
      calls.push(`remove:${userId}`)
      current = { ...current, members: current.members.filter((person) => person.user_id !== userId) }
    },
    leaveFamily: async () => {
      calls.push("leave")
      session = { ...session, family_id: null }
      rows = []
      current = { ...current, invite_code: null, invite_expires_at: null, members: [] }
    },
    createItem: async () => milk(),
    patchItem: async () => ({ item: milk(), overwritten: [], message: null }),
    batch: async () => {
      protectedCalls.push("batch")
      return { rev: 4 }
    },
    undoBatch: async () => {
      protectedCalls.push("undo")
      throw new Error("family screen must not undo")
    },
    sync: async () => ({
      reset: false,
      rev: 4,
      items: rows.filter((row) => row.status === "in_stock"),
      tombstones: [],
      family: session.family_id ? current : null,
      notify_time: "09:00",
    }),
    photo: async () => {
      protectedCalls.push("photo")
      throw new Error("family screen must not load a photo")
    },
    webSocketUrl: () => "ws://127.0.0.1/api/v1/ws",
  }
  return {
    api,
    family: () => current,
    setFamily: (next) => { current = next },
    session: () => session,
    setSession: (next) => { session = next },
    rows: () => rows,
    setRows: (next) => { rows = next },
    calls,
    protectedCalls,
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

function useClipboard() {
  const writeText = vi.fn<(text: string) => Promise<void>>(async () => undefined)
  clipboardState.writeText = writeText
  Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText } })
  Object.defineProperty(navigator, "share", { configurable: true, value: shareSpy })
  return writeText
}

async function openFamily(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole("button", { name: "家庭" }))
  expect(await screen.findByRole("heading", { name: "成员" })).toBeInTheDocument()
}

function dialog() {
  return screen.findByRole("dialog")
}

describe("family page", () => {
  it("shows the invite to every member and hides admin actions from a member", async () => {
    const user = userEvent.setup()
    const writeText = useClipboard()
    const home = household("member")
    home.admin_user_id = "user-1"
    const box = harness(home)
    renderApp(box.api)
    await openFamily(user)
    expect(screen.getByLabelText("当前邀请码")).toHaveTextContent("AB23CD")
    expect(screen.getByText("有效期至 2026-11-22，所有成员可复制和分享。")).toBeInTheDocument()
    expect(screen.getByText("自己（我）")).toBeInTheDocument()
    expect(screen.getByText("成员 · 加入 2026-11-03")).toBeInTheDocument()
    expect(screen.queryByRole("button", { name: "编辑家庭名" })).toBeNull()
    expect(screen.queryByRole("button", { name: "重置邀请码" })).toBeNull()
    expect(screen.queryByRole("button", { name: /设为管理员/ })).toBeNull()
    expect(screen.queryByRole("button", { name: /移除/ })).toBeNull()
    expect(document.body.textContent).not.toContain("user-1")
    expect(document.body.textContent).not.toContain("user-2")
    await user.click(screen.getByRole("button", { name: "复制邀请码" }))
    await user.click(screen.getByRole("button", { name: "分享邀请码" }))
    await waitFor(() => expect(writeText.mock.calls.map((call) => call[0])).toEqual([
      "AB23CD",
      "邀请你加入我家，邀请码 AB23CD",
    ]))
    expect(shareSpy).not.toHaveBeenCalled()
    expect(document.body.innerHTML).not.toContain("mailto:")
    expect(screen.getByText("分享文字已复制，可粘贴给家人")).toBeInTheDocument()
    expect(box.calls).toEqual([])
    expect(box.protectedCalls).toEqual([])
  })

  it("keeps a failed share on the page and does not call the system share sheet", async () => {
    const user = userEvent.setup()
    const writeText = useClipboard()
    writeText.mockRejectedValue(new Error("denied"))
    const box = harness(household("member"))
    renderApp(box.api)
    await openFamily(user)
    await user.click(screen.getByRole("button", { name: "分享邀请码" }))
    expect(await screen.findByLabelText("分享文字")).toHaveTextContent("邀请你加入我家，邀请码 AB23CD")
    expect(shareSpy).not.toHaveBeenCalled()
    expect(box.calls).toEqual([])
  })

  it("does not submit when admin confirmations are cancelled", async () => {
    const user = userEvent.setup()
    const box = harness(household("admin"))
    renderApp(box.api)
    await openFamily(user)
    expect(screen.getByRole("button", { name: "编辑家庭名" })).toBeEnabled()
    expect(screen.getByRole("button", { name: "重置邀请码" })).toBeEnabled()
    expect(screen.getByRole("button", { name: "设为管理员 小米" })).toBeEnabled()
    expect(screen.queryByRole("button", { name: "设为管理员 自己" })).toBeNull()
    expect(screen.queryByRole("button", { name: "移除 自己" })).toBeNull()
    const mine = screen.getByText("自己（我）").closest("li")
    expect(mine).not.toBeNull()
    expect(within(mine as HTMLElement).queryByRole("button")).toBeNull()

    await user.click(screen.getByRole("button", { name: "编辑家庭名" }))
    await user.click(within(await dialog()).getByRole("button", { name: "取消" }))
    await user.click(screen.getByRole("button", { name: "重置邀请码" }))
    expect(within(await dialog()).getByText("重置后旧邀请码立即失效。")).toBeInTheDocument()
    await user.click(within(await dialog()).getByRole("button", { name: "取消" }))
    await user.click(screen.getByRole("button", { name: "移除 小米" }))
    expect(within(await dialog()).getByText("确定将小米移出「我家」？移出后对方将立即看不到该家庭的物品。")).toBeInTheDocument()
    await user.click(within(await dialog()).getByRole("button", { name: "取消" }))
    await user.click(screen.getByRole("button", { name: "设为管理员 小米" }))
    expect(within(await dialog()).getByText("确定将小米设为管理员？转让后你将变为普通成员。")).toBeInTheDocument()
    await user.click(within(await dialog()).getByRole("button", { name: "取消" }))
    await user.click(screen.getByRole("button", { name: "退出家庭" }))
    const leaving = await dialog()
    expect(within(leaving).getByText("须先转让管理员")).toBeInTheDocument()
    expect(within(leaving).getByText(/转让后你将变为普通成员/)).toBeInTheDocument()
    expect(within(leaving).getByRole("radio", { name: /小米/ })).toBeChecked()
    expect(within(leaving).getByRole("radio", { name: /小红/ })).not.toBeChecked()
    await user.click(within(leaving).getByRole("button", { name: "取消" }))
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull())
    expect(box.calls).toEqual([])
    expect(box.protectedCalls).toEqual([])
    await user.click(screen.getByRole("button", { name: "清单" }))
    expect(screen.getByRole("heading", { name: "牛奶", level: 3 })).toBeInTheDocument()
    expect(screen.getByLabelText("当前邀请码")).toHaveTextContent("AB23CD")
  })

  it("rejects a short family name before calling rename, then saves a trimmed name", async () => {
    const user = userEvent.setup()
    const box = harness(household("admin"))
    renderApp(box.api)
    await openFamily(user)
    await user.click(screen.getByRole("button", { name: "编辑家庭名" }))
    const name = within(await dialog()).getByLabelText("家庭名称")
    await user.clear(name)
    await user.type(name, "米")
    await user.click(within(await dialog()).getByRole("button", { name: "保存" }))
    expect(await screen.findByRole("alert")).toHaveTextContent("家庭名需要 2 到 20 个字")
    expect(box.calls).toEqual([])
    await user.clear(name)
    await user.type(name, "  新家  ")
    await user.click(within(await dialog()).getByRole("button", { name: "保存" }))
    await waitFor(() => expect(box.calls).toEqual(["rename:新家"]))
    expect(await screen.findByRole("heading", { level: 1, name: "新家" })).toBeInTheDocument()
    expect(screen.getByText("家庭名已保存")).toBeInTheDocument()
    expect(box.protectedCalls).toEqual([])
  })

  it("resets the invite only after confirmation", async () => {
    const user = userEvent.setup()
    useClipboard()
    const box = harness(household("admin"))
    renderApp(box.api)
    await openFamily(user)
    await user.click(screen.getByRole("button", { name: "重置邀请码" }))
    await user.click(within(await dialog()).getByRole("button", { name: "取消" }))
    expect(box.calls).toEqual([])
    expect(screen.getByLabelText("当前邀请码")).toHaveTextContent("AB23CD")
    await user.click(screen.getByRole("button", { name: "重置邀请码" }))
    await user.click(within(await dialog()).getByRole("button", { name: "重置" }))
    await waitFor(() => expect(screen.getByLabelText("当前邀请码")).toHaveTextContent("CD45EF"))
    expect(box.calls).toEqual(["reset"])
    expect(screen.getByText("有效期至 2026-12-01，所有成员可复制和分享。")).toBeInTheDocument()
    expect(shareSpy).not.toHaveBeenCalled()
    expect(box.protectedCalls).toEqual([])
  })

  it("removes a member only after confirmation and keeps the actor in the family", async () => {
    const user = userEvent.setup()
    const box = harness(household("admin"))
    renderApp(box.api)
    await openFamily(user)
    await user.click(screen.getByRole("button", { name: "移除 小红" }))
    await user.click(within(await dialog()).getByRole("button", { name: "移除" }))
    await waitFor(() => expect(screen.queryByText("小红")).toBeNull())
    expect(box.calls).toEqual(["remove:user-3"])
    expect(screen.getByText("自己（我）")).toBeInTheDocument()
    expect(screen.queryByRole("button", { name: "创建家庭" })).toBeNull()
    await user.click(screen.getByRole("button", { name: "清单" }))
    expect(screen.getByRole("heading", { name: "牛奶", level: 3 })).toBeInTheDocument()
    expect(box.protectedCalls).toEqual([])
  })

  it("shows the server refusal and does not drop the member", async () => {
    const user = userEvent.setup()
    const box = harness(household("admin"))
    box.api.removeMember = async () => {
      box.calls.push("remove")
      throw new ApiError(403, "admin_only", "只有管理员可以执行该操作")
    }
    renderApp(box.api)
    await openFamily(user)
    await user.click(screen.getByRole("button", { name: "移除 小米" }))
    await user.click(within(await dialog()).getByRole("button", { name: "移除" }))
    expect(await screen.findByText("只有管理员可以执行该操作")).toBeInTheDocument()
    expect(screen.getByText("小米")).toBeInTheDocument()
    expect(box.calls).toEqual(["remove"])
    await user.click(within(await dialog()).getByRole("button", { name: "取消" }))
    expect(box.calls).toEqual(["remove"])
    expect(box.protectedCalls).toEqual([])
  })

  it("ignores a second remove confirmation while the first is still running", async () => {
    const user = userEvent.setup()
    let release: () => void = () => undefined
    const gate = new Promise<void>((resolve) => { release = resolve })
    const box = harness(household("admin"))
    box.api.removeMember = async (userId) => {
      box.calls.push(`remove:${userId}`)
      await gate
      box.setFamily({ ...box.family(), members: box.family().members.filter((person) => person.user_id !== userId) })
    }
    renderApp(box.api)
    await openFamily(user)
    await user.click(screen.getByRole("button", { name: "移除 小米" }))
    const confirm = within(await dialog()).getByRole("button", { name: "移除" })
    fireEvent.click(confirm)
    fireEvent.click(confirm)
    await waitFor(() => expect(box.calls).toEqual(["remove:user-2"]))
    release()
    await waitFor(() => expect(screen.queryByText("小米")).toBeNull())
    expect(box.calls).toEqual(["remove:user-2"])
  })

  it("transfers admin, stays in the family, and hides admin actions", async () => {
    const user = userEvent.setup()
    const box = harness(household("admin"))
    renderApp(box.api)
    await openFamily(user)
    await user.click(screen.getByRole("button", { name: "设为管理员 小米" }))
    await user.click(within(await dialog()).getByRole("button", { name: "取消" }))
    expect(box.calls).toEqual([])
    await user.click(screen.getByRole("button", { name: "设为管理员 小米" }))
    await user.click(within(await dialog()).getByRole("button", { name: "确认转让" }))
    expect(await screen.findByText("已转让管理员，你现在是普通成员")).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole("button", { name: "编辑家庭名" })).toBeNull())
    expect(screen.queryByRole("button", { name: "重置邀请码" })).toBeNull()
    expect(screen.queryByRole("button", { name: /设为管理员/ })).toBeNull()
    expect(screen.getByText("自己（我）")).toBeInTheDocument()
    expect(box.calls).toEqual(["transfer:user-2"])
    await user.click(screen.getByRole("button", { name: "清单" }))
    expect(screen.getByRole("heading", { name: "牛奶", level: 3 })).toBeInTheDocument()
    expect(box.calls).not.toContain("leave")
    expect(box.protectedCalls).toEqual([])
  })

  it("leaves a member only after confirmation and returns to create or join without the old family", async () => {
    const user = userEvent.setup()
    const box = harness(household("member"))
    renderApp(box.api)
    await openFamily(user)
    await user.click(screen.getByRole("button", { name: "退出家庭" }))
    const leaving = await dialog()
    expect(within(leaving).getByText("退出后将不能再看到该家庭的物品。邀请码有效期内可再加入。")).toBeInTheDocument()
    await user.click(within(leaving).getByRole("button", { name: "取消" }))
    expect(box.calls).toEqual([])
    expect(screen.getByText("AB23CD")).toBeInTheDocument()
    await user.click(screen.getByRole("button", { name: "退出家庭" }))
    await user.click(within(await dialog()).getByRole("button", { name: "退出" }))
    expect(await screen.findByRole("button", { name: "创建家庭" })).toBeInTheDocument()
    expect(screen.queryByText("AB23CD")).toBeNull()
    expect(screen.queryByText("牛奶")).toBeNull()
    expect(screen.queryByText("小米")).toBeNull()
    expect(screen.queryByText("你已被移出家庭")).toBeNull()
    expect(box.calls).toEqual(["leave"])
    await user.type(screen.getByLabelText("家庭名称"), "新家")
    await user.click(screen.getByRole("button", { name: "创建家庭" }))
    expect(await screen.findByRole("heading", { level: 1, name: "新家" })).toBeInTheDocument()
    expect(screen.getByText("这个家庭还没有在库物品")).toBeInTheDocument()
    expect(screen.queryByText("AB23CD")).toBeNull()
    expect(screen.queryByText("牛奶")).toBeNull()
    expect(screen.getByText("ZZ99ZZ")).toBeInTheDocument()
    expect(box.protectedCalls).toEqual([])
  })

  it("tells the last member about the 30 day hold and leaves only after confirmation", async () => {
    const user = userEvent.setup()
    const box = harness(alone())
    renderApp(box.api)
    await openFamily(user)
    await user.click(screen.getByRole("button", { name: "退出家庭" }))
    const leaving = await dialog()
    expect(within(leaving).getByText("你是最后一名成员。家庭将保留 30 天后删除。保留期内不可再加入，邀请码将失效。")).toBeInTheDocument()
    await user.click(within(leaving).getByRole("button", { name: "取消" }))
    expect(box.calls).toEqual([])
    await user.click(screen.getByRole("button", { name: "清单" }))
    expect(screen.getByText("牛奶")).toBeInTheDocument()
    await user.click(screen.getByRole("button", { name: "家庭" }))
    await user.click(screen.getByRole("button", { name: "退出家庭" }))
    await user.click(within(await dialog()).getByRole("button", { name: "退出" }))
    expect(await screen.findByRole("button", { name: "创建家庭" })).toBeInTheDocument()
    expect(screen.queryByText("牛奶")).toBeNull()
    expect(screen.queryByText("AB23CD")).toBeNull()
    expect(box.calls).toEqual(["leave"])
    expect(box.protectedCalls).toEqual([])
  })

  it("stays when the server still requires a transfer", async () => {
    const user = userEvent.setup()
    const box = harness(alone())
    box.api.leaveFamily = async () => {
      box.calls.push("leave")
      throw new ApiError(409, "admin_must_transfer", "请先转让管理员")
    }
    renderApp(box.api)
    await openFamily(user)
    await user.click(screen.getByRole("button", { name: "退出家庭" }))
    await user.click(within(await dialog()).getByRole("button", { name: "退出" }))
    expect(await screen.findByText("请先转让管理员")).toBeInTheDocument()
    expect(screen.queryByRole("button", { name: "创建家庭" })).toBeNull()
    await user.click(screen.getByRole("button", { name: "清单" }))
    expect(screen.getByText("牛奶")).toBeInTheDocument()
    expect(box.calls).toEqual(["leave"])
  })

  it("transfers the chosen successor and then leaves, and does neither when cancelled", async () => {
    const user = userEvent.setup()
    const box = harness(household("admin"))
    renderApp(box.api)
    await openFamily(user)
    await user.click(screen.getByRole("button", { name: "退出家庭" }))
    let leaving = await dialog()
    await user.click(within(leaving).getByRole("radio", { name: /小红/ }))
    await user.click(within(leaving).getByRole("button", { name: "取消" }))
    expect(box.calls).toEqual([])
    await user.click(screen.getByRole("button", { name: "退出家庭" }))
    leaving = await dialog()
    expect(within(leaving).getByRole("radio", { name: /小米/ })).toBeChecked()
    await user.click(within(leaving).getByRole("radio", { name: /小红/ }))
    await user.click(within(leaving).getByRole("button", { name: "转让并退出" }))
    expect(await screen.findByRole("button", { name: "创建家庭" })).toBeInTheDocument()
    expect(box.calls).toEqual(["transfer:user-3", "leave"])
    expect(screen.queryByText("牛奶")).toBeNull()
    expect(screen.queryByText("AB23CD")).toBeNull()
    expect(screen.queryByText("小红")).toBeNull()
    expect(box.protectedCalls).toEqual([])
  })

  it("keeps the family when transfer succeeds and leave fails", async () => {
    const user = userEvent.setup()
    const box = harness(household("admin"))
    box.api.leaveFamily = async () => {
      box.calls.push("leave")
      throw new ApiError(409, "admin_must_transfer", "请稍后重试")
    }
    renderApp(box.api)
    await openFamily(user)
    await user.click(screen.getByRole("button", { name: "退出家庭" }))
    await user.click(within(await dialog()).getByRole("radio", { name: /小红/ }))
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "转让并退出" }))
    expect(await screen.findByText("管理员已转让，但退出失败。请稍后重试")).toBeInTheDocument()
    expect(screen.queryByRole("button", { name: "编辑家庭名" })).toBeNull()
    expect(screen.getByText("自己（我）")).toBeInTheDocument()
    expect(screen.queryByRole("button", { name: "创建家庭" })).toBeNull()
    expect(box.calls).toEqual(["transfer:user-3", "leave"])
    await user.click(screen.getByRole("button", { name: "清单" }))
    expect(screen.getByText("牛奶")).toBeInTheDocument()
    expect(box.protectedCalls).toEqual([])
  })

  it("does not leave when the transfer itself fails", async () => {
    const user = userEvent.setup()
    const box = harness(household("admin"))
    box.api.transferAdmin = async () => {
      box.calls.push("transfer")
      throw new ApiError(404, "not_found", "未找到")
    }
    renderApp(box.api)
    await openFamily(user)
    await user.click(screen.getByRole("button", { name: "退出家庭" }))
    await user.click(within(await dialog()).getByRole("button", { name: "转让并退出" }))
    expect(await screen.findByText("未找到")).toBeInTheDocument()
    expect(screen.getByRole("button", { name: "编辑家庭名" })).toBeInTheDocument()
    expect(box.calls).toEqual(["transfer"])
    await user.click(screen.getByRole("button", { name: "清单" }))
    expect(screen.getByText("牛奶")).toBeInTheDocument()
  })
})

function ScreenProbe({
  family,
  online,
  onRename,
  onReset,
  onTransfer,
  onRemove,
  onLeave,
}: {
  family: Family
  online: boolean
  onRename: (name: string) => Promise<void>
  onReset: () => Promise<void>
  onTransfer: (userId: string) => Promise<void>
  onRemove: (userId: string) => Promise<void>
  onLeave: () => Promise<void>
}) {
  return (
    <ConfigProvider locale={zhCN} button={{ autoInsertSpace: false }}>
      <ItemScreen
        familyName={family.name}
        inviteCode={family.invite_code}
        inviteExpires={family.invite_expires_at}
        items={[milk()]}
        today="2026-11-15"
        loading={false}
        error={null}
        live={false}
        busy={false}
        online={online}
        onRefresh={async () => undefined}
        onCreate={async () => undefined}
        onUpdate={async () => null}
        onDelete={async () => undefined}
        onUsed={async () => undefined}
        onMark={async () => {
          throw new Error("family probe must not mark")
        }}
        onUndo={async () => {
          throw new Error("family probe must not undo")
        }}
        onRestore={async () => {
          throw new Error("family probe must not restore")
        }}
        onDeleteHistory={async () => undefined}
        onLogout={async () => undefined}
        onLoadPhoto={async () => {
          throw new Error("family probe must not load a photo")
        }}
        family={family}
        viewerId="user-1"
        onRenameFamily={onRename}
        onResetInvite={onReset}
        onTransferAdmin={onTransfer}
        onRemoveMember={onRemove}
        onLeaveFamily={onLeave}
      />
    </ConfigProvider>
  )
}

describe("family page guards", () => {
  it("disables modifications while offline and does not call them", async () => {
    const user = userEvent.setup()
    const calls: string[] = []
    const refuse = async () => {
      calls.push("called")
    }
    render(
      <ScreenProbe
        family={household("admin")}
        online={false}
        onRename={refuse}
        onReset={refuse}
        onTransfer={refuse}
        onRemove={refuse}
        onLeave={refuse}
      />,
    )
    await user.click(screen.getByRole("button", { name: "家庭" }))
    expect(await screen.findByText("恢复网络后再修改")).toBeInTheDocument()
    for (const name of ["编辑家庭名", "重置邀请码", "设为管理员 小米", "移除 小米", "退出家庭"]) {
      expect(screen.getByRole("button", { name })).toBeDisabled()
    }
    expect(screen.getByRole("button", { name: "复制邀请码" })).toBeEnabled()
    fireEvent.click(screen.getByRole("button", { name: "重置邀请码" }))
    fireEvent.click(screen.getByRole("button", { name: "退出家庭" }))
    fireEvent.click(screen.getByRole("button", { name: "移除 小米" }))
    expect(calls).toEqual([])
    expect(screen.queryByRole("dialog")).toBeNull()
    await user.click(screen.getByRole("button", { name: "清单" }))
    expect(screen.getByRole("heading", { name: "牛奶", level: 3 })).toBeInTheDocument()
  })

  it("closes an admin dialog without calling the server when the role changes", async () => {
    const user = userEvent.setup()
    const calls: string[] = []
    function Probe() {
      const [family, setFamily] = useState(household("admin"))
      return (
        <>
          <button type="button" onClick={() => setFamily({ ...family, role: "member" })}>变成成员</button>
          <ScreenProbe
            family={family}
            online
            onRename={async () => { calls.push("rename") }}
            onReset={async () => { calls.push("reset") }}
            onTransfer={async () => { calls.push("transfer") }}
            onRemove={async () => { calls.push("remove") }}
            onLeave={async () => { calls.push("leave") }}
          />
        </>
      )
    }
    render(<Probe />)
    await user.click(screen.getByRole("button", { name: "家庭" }))
    await user.click(await screen.findByRole("button", { name: "移除 小米" }))
    expect(await screen.findByRole("dialog")).toHaveTextContent("确定将小米移出")
    await user.click(screen.getByRole("button", { name: "变成成员" }))
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull())
    expect(screen.getByText("你已经不是管理员")).toBeInTheDocument()
    expect(screen.queryByRole("button", { name: "编辑家庭名" })).toBeNull()
    expect(calls).toEqual([])
  })

  it("does not confirm a removal after the network drops", async () => {
    const user = userEvent.setup()
    const calls: string[] = []
    function Probe() {
      const [online, setOnline] = useState(true)
      return (
        <>
          <button type="button" onClick={() => setOnline(false)}>断开</button>
          <ScreenProbe
            family={household("admin")}
            online={online}
            onRename={async () => { calls.push("rename") }}
            onReset={async () => { calls.push("reset") }}
            onTransfer={async () => { calls.push("transfer") }}
            onRemove={async () => { calls.push("remove") }}
            onLeave={async () => { calls.push("leave") }}
          />
        </>
      )
    }
    render(<Probe />)
    await user.click(screen.getByRole("button", { name: "家庭" }))
    await user.click(await screen.findByRole("button", { name: "移除 小米" }))
    expect(await screen.findByRole("dialog")).toBeInTheDocument()
    await user.click(screen.getByRole("button", { name: "断开" }))
    const confirm = within(screen.getByRole("dialog")).getByRole("button", { name: "移除" })
    expect(confirm).toBeDisabled()
    fireEvent.click(confirm)
    expect(calls).toEqual([])
    await user.click(screen.getByRole("button", { name: "清单" }))
    expect(screen.getByText("牛奶")).toBeInTheDocument()
  })
})
