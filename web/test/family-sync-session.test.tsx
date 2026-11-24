import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { act, render, renderHook, screen, within } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { describe, expect, it } from "vitest"
import { App } from "../src/App"
import type { PantryClient } from "../src/api/client"
import { ApiError, type Family, type Item, type Me, type SyncPage } from "../src/api/types"
import { useFamilySync, type SocketLike } from "../src/sync/useFamilySync"

class ScriptedSocket implements SocketLike {
  onopen: (() => void) | null = null
  onmessage: ((event: { data: string }) => void) | null = null
  onclose: (() => void) | null = null
  close() {
    this.onclose?.()
  }
  emit(body: unknown) {
    this.onmessage?.({ data: JSON.stringify(body) })
  }
}

function noodle(): Item {
  return { ...milk(), id: "item-new", name: "面条", rev: 5 }
}

function milk(): Item {
  return {
    id: "item-old",
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
    rev: 2,
  }
}

function family(id: string, name: string, code: string | null): Family {
  return {
    id,
    name,
    admin_user_id: "user-1",
    invite_code: code,
    invite_expires_at: code ? "2026-11-22T00:00:00Z" : null,
    role: "admin",
    members: [{ user_id: "user-1", display_name: "自己", role: "admin", joined_at: "2026-11-01T00:00:00Z" }],
  }
}

function page(current: Family, items: Item[]): SyncPage {
  return { reset: false, rev: 4, items, tombstones: [], family: current, notify_time: "09:00" }
}

const oldFamily = family("fam-a", "旧家", "AB23CD")
const newFamily = family("fam-b", "新家", "ZZ99ZZ")
const oldPage = page(oldFamily, [milk()])
const newPage = page(newFamily, [])

function me(familyId: string | null): Me {
  return { user_id: "user-1", display_name: "自己", notify_time: "09:00", family_id: familyId }
}

describe("removed family sync session", () => {
  it("discards the in-flight family A sync and starts family B after removal", async () => {
    const held: Array<(page: SyncPage) => void> = []
    let mode: "a" | "hang" | "b" = "a"
    let syncs = 0
    const sockets: ScriptedSocket[] = []
    const api = {
      sync: () => {
        syncs += 1
        if (mode === "hang") return new Promise<SyncPage>((resolve) => { held.push(resolve) })
        return Promise.resolve(mode === "b" ? newPage : oldPage)
      },
      webSocketUrl: () => "ws://127.0.0.1/api/v1/ws",
    } as unknown as PantryClient
    const view = renderHook(
      ({ enabled, sessionKey }: { enabled: boolean; sessionKey: string | null }) => (
        useFamilySync(api, enabled, () => {
          const socket = new ScriptedSocket()
          sockets.push(socket)
          return socket
        }, sessionKey)
      ),
      { initialProps: { enabled: true, sessionKey: "fam-a" } },
    )
    await act(async () => {
      await Promise.resolve()
    })
    expect(view.result.current.family?.id).toBe("fam-a")
    expect(view.result.current.items.map((item) => item.name)).toEqual(["牛奶"])
    expect(view.result.current.removed).toBe(false)

    mode = "hang"
    const beforeHang = syncs
    await act(async () => {
      sockets[0]?.emit({ t: "changed", rev: 4 })
      await Promise.resolve()
    })
    expect(syncs).toBe(beforeHang + 1)
    expect(held).toHaveLength(1)

    await act(async () => {
      sockets[0]?.emit({ t: "removed_from_family" })
    })
    expect(view.result.current.removed).toBe(true)
    expect(view.result.current.items).toEqual([])
    expect(view.result.current.family).toBeNull()

    const afterRemoval = syncs
    await act(async () => {
      sockets[0]?.emit({ t: "changed", rev: 5 })
      await Promise.resolve()
    })
    expect(syncs).toBe(afterRemoval)
    expect(view.result.current.items).toEqual([])
    expect(view.result.current.family).toBeNull()

    view.rerender({ enabled: true, sessionKey: "fam-a" })
    expect(view.result.current.removed).toBe(true)

    mode = "b"
    view.rerender({ enabled: true, sessionKey: "fam-b" })
    await act(async () => {
      await Promise.resolve()
    })
    expect(view.result.current.removed).toBe(false)
    expect(view.result.current.family?.id).toBe("fam-b")
    expect(view.result.current.items).toEqual([])

    await act(async () => {
      for (const resolve of held.splice(0)) resolve(oldPage)
      await Promise.resolve()
    })
    expect(view.result.current.removed).toBe(false)
    expect(view.result.current.family?.id).toBe("fam-b")
    expect(view.result.current.family?.name).toBe("新家")
    expect(view.result.current.items).toEqual([])
    expect(JSON.stringify(view.result.current.items)).not.toContain("牛奶")
    expect(view.result.current.family?.invite_code).toBe("ZZ99ZZ")
  })

  it("drops a sync that resolves after the session is turned off", async () => {
    let release: ((page: SyncPage) => void) | null = null
    let started = false
    const api = {
      sync: () => {
        if (!started) {
          started = true
          return Promise.resolve(oldPage)
        }
        return new Promise<SyncPage>((resolve) => { release = resolve })
      },
      webSocketUrl: () => "ws://127.0.0.1/api/v1/ws",
    } as unknown as PantryClient
    const sockets: ScriptedSocket[] = []
    const view = renderHook(
      ({ enabled, sessionKey }: { enabled: boolean; sessionKey: string | null }) => (
        useFamilySync(api, enabled, () => {
          const socket = new ScriptedSocket()
          sockets.push(socket)
          return socket
        }, sessionKey)
      ),
      { initialProps: { enabled: true, sessionKey: "fam-a" as string | null } },
    )
    await act(async () => {
      await Promise.resolve()
    })
    await act(async () => {
      sockets[0]?.emit({ t: "changed", rev: 4 })
      await Promise.resolve()
    })
    expect(release).not.toBeNull()
    view.rerender({ enabled: false, sessionKey: null })
    await act(async () => {
      release?.(oldPage)
      await Promise.resolve()
    })
    expect(view.result.current.items).toEqual([])
    expect(view.result.current.family).toBeNull()
    expect(view.result.current.removed).toBe(false)
  })
})

function renderApp(api: PantryClient, socketFactory: () => SocketLike) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <App api={api} now={() => new Date("2026-11-15T04:00:00Z")} socketFactory={socketFactory} />
    </QueryClientProvider>,
  )
}

describe("app removal timeline", () => {
  it("keeps the removed notice while /me still returns A, then shows B and ignores the old sync", async () => {
    const user = userEvent.setup()
    const held: Array<(page: SyncPage) => void> = []
    let mode: "a" | "hang" | "b" = "a"
    let session = me("fam-a")
    const calls: string[] = []
    const sockets: ScriptedSocket[] = []
    const api = {
      requestCode: async () => undefined,
      login: async () => ({ user_id: "user-1", display_name: "自己" }),
      logout: async () => undefined,
      me: async () => session,
      createFamily: async (name: string) => {
        calls.push(`create:${name}`)
        session = me("fam-b")
        mode = "b"
        return { ...newFamily, name }
      },
      joinFamily: async (code: string) => {
        calls.push(`join:${code}`)
        session = me("fam-b")
        mode = "b"
        return newFamily
      },
      currentFamily: async () => (session.family_id === "fam-b" ? newFamily : oldFamily),
      renameFamily: async () => oldFamily,
      resetInvite: async () => oldFamily,
      transferAdmin: async () => oldFamily,
      removeMember: async () => undefined,
      leaveFamily: async () => {
        calls.push("leave")
        session = me(null)
      },
      createItem: async () => milk(),
      patchItem: async () => ({ item: milk(), overwritten: [], message: null }),
      batch: async () => {
        calls.push("batch")
        return { rev: 9 }
      },
      undoBatch: async () => {
        calls.push("undo")
        throw new Error("timeline must not undo")
      },
      sync: () => {
        if (mode === "hang") return new Promise<SyncPage>((resolve) => { held.push(resolve) })
        return Promise.resolve(mode === "b" ? page({ ...newFamily, name: "新家" }, []) : oldPage)
      },
      photo: async () => {
        calls.push("photo")
        throw new Error("timeline must not load a photo")
      },
      webSocketUrl: () => "ws://127.0.0.1/api/v1/ws",
    } satisfies PantryClient
    renderApp(api, () => {
      const socket = new ScriptedSocket()
      sockets.push(socket)
      return socket
    })
    expect(await screen.findByRole("heading", { name: "旧家" })).toBeInTheDocument()
    expect(screen.getByRole("heading", { name: "牛奶", level: 3 })).toBeInTheDocument()

    mode = "hang"
    await act(async () => {
      sockets[0]?.emit({ t: "changed", rev: 4 })
      await Promise.resolve()
    })
    expect(held).toHaveLength(1)
    await act(async () => {
      sockets[0]?.emit({ t: "removed_from_family" })
    })
    expect(await screen.findByText("你已被移出家庭")).toBeInTheDocument()
    expect(screen.getByRole("button", { name: "创建家庭" })).toBeInTheDocument()
    expect(session.family_id).toBe("fam-a")
    expect(screen.queryByRole("heading", { name: "牛奶", level: 3 })).toBeNull()

    await user.type(screen.getByLabelText("家庭名称"), "新家")
    await user.click(screen.getByRole("button", { name: "创建家庭" }))
    expect(await screen.findByRole("heading", { level: 1, name: "新家" })).toBeInTheDocument()
    expect(screen.queryByText("你已被移出家庭")).toBeNull()
    expect(screen.getByText("这个家庭还没有在库物品")).toBeInTheDocument()
    expect(screen.getByText("ZZ99ZZ")).toBeInTheDocument()

    await act(async () => {
      for (const resolve of held.splice(0)) resolve(oldPage)
      await Promise.resolve()
      await Promise.resolve()
    })
    expect(screen.getByRole("heading", { level: 1, name: "新家" })).toBeInTheDocument()
    expect(screen.queryByText("你已被移出家庭")).toBeNull()
    expect(screen.queryByText("牛奶")).toBeNull()
    expect(screen.queryByText("AB23CD")).toBeNull()
    expect(screen.queryByText("旧家")).toBeNull()
    expect(calls).toEqual(["create:新家"])
  })

  it("rejoins a new family and still ignores the sync that started in the old one", async () => {
    const user = userEvent.setup()
    const held: Array<(page: SyncPage) => void> = []
    let mode: "a" | "hang" | "b" = "a"
    let session = me("fam-a")
    const calls: string[] = []
    const sockets: ScriptedSocket[] = []
    const api = {
      requestCode: async () => undefined,
      login: async () => ({ user_id: "user-1", display_name: "自己" }),
      logout: async () => undefined,
      me: async () => session,
      createFamily: async () => oldFamily,
      joinFamily: async (code: string) => {
        calls.push(`join:${code}`)
        session = me("fam-b")
        mode = "b"
        return newFamily
      },
      currentFamily: async () => newFamily,
      renameFamily: async () => newFamily,
      resetInvite: async () => newFamily,
      transferAdmin: async () => newFamily,
      removeMember: async () => undefined,
      leaveFamily: async () => undefined,
      createItem: async () => milk(),
      patchItem: async () => ({ item: milk(), overwritten: [], message: null }),
      batch: async () => ({ rev: 1 }),
      undoBatch: async () => ({ skipped: 0, restored: 0, message: null }),
      sync: () => {
        if (mode === "hang") return new Promise<SyncPage>((resolve) => { held.push(resolve) })
        return Promise.resolve(mode === "b" ? newPage : oldPage)
      },
      photo: async () => {
        throw new Error("unused")
      },
      webSocketUrl: () => "ws://127.0.0.1/api/v1/ws",
    } satisfies PantryClient
    renderApp(api, () => {
      const socket = new ScriptedSocket()
      sockets.push(socket)
      return socket
    })
    expect(await screen.findByText("牛奶")).toBeInTheDocument()
    mode = "hang"
    await act(async () => {
      sockets[0]?.emit({ t: "changed", rev: 4 })
      sockets[0]?.emit({ t: "removed_from_family" })
      await Promise.resolve()
    })
    expect(await screen.findByText("你已被移出家庭")).toBeInTheDocument()
    await user.type(screen.getByLabelText("邀请码"), "CD45EF")
    await user.click(screen.getByRole("button", { name: "加入家庭" }))
    expect(await screen.findByRole("heading", { level: 1, name: "新家" })).toBeInTheDocument()
    await act(async () => {
      for (const resolve of held.splice(0)) resolve(oldPage)
      await Promise.resolve()
    })
    expect(screen.getByRole("heading", { level: 1, name: "新家" })).toBeInTheDocument()
    expect(screen.queryByText("牛奶")).toBeNull()
    expect(screen.queryByText("AB23CD")).toBeNull()
    expect(screen.queryByText("你已被移出家庭")).toBeNull()
    expect(calls).toEqual(["join:CD45EF"])
  })

  it("rejoins the same family id and does not revive the sealed session", async () => {
    const user = userEvent.setup()
    const held: Array<(page: SyncPage) => void> = []
    let mode: "a" | "hang" | "fresh" = "a"
    let session = me("fam-a")
    let syncs = 0
    const calls: string[] = []
    const sockets: ScriptedSocket[] = []
    const fresh = page(family("fam-a", "旧家", "CD45EF"), [noodle()])
    const api = {
      requestCode: async () => undefined,
      login: async () => ({ user_id: "user-1", display_name: "自己" }),
      logout: async () => undefined,
      me: async () => session,
      createFamily: async () => oldFamily,
      joinFamily: async (code: string) => {
        calls.push(`join:${code}`)
        session = me("fam-a")
        mode = "fresh"
        return fresh.family!
      },
      currentFamily: async () => fresh.family!,
      renameFamily: async () => fresh.family!,
      resetInvite: async () => fresh.family!,
      transferAdmin: async () => fresh.family!,
      removeMember: async () => undefined,
      leaveFamily: async () => undefined,
      createItem: async () => milk(),
      patchItem: async () => ({ item: milk(), overwritten: [], message: null }),
      batch: async () => ({ rev: 1 }),
      undoBatch: async () => ({ skipped: 0, restored: 0, message: null }),
      sync: () => {
        syncs += 1
        if (mode === "hang") return new Promise<SyncPage>((resolve) => { held.push(resolve) })
        return Promise.resolve(mode === "fresh" ? fresh : oldPage)
      },
      photo: async () => {
        throw new Error("unused")
      },
      webSocketUrl: () => "ws://127.0.0.1/api/v1/ws",
    } satisfies PantryClient
    renderApp(api, () => {
      const socket = new ScriptedSocket()
      sockets.push(socket)
      return socket
    })
    expect(await screen.findByText("牛奶")).toBeInTheDocument()
    mode = "hang"
    await act(async () => {
      sockets[0]?.emit({ t: "changed", rev: 4 })
      sockets[0]?.emit({ t: "removed_from_family" })
      await Promise.resolve()
    })
    expect(held).toHaveLength(1)
    expect(await screen.findByText("你已被移出家庭")).toBeInTheDocument()
    expect(session.family_id).toBe("fam-a")
    await user.type(screen.getByLabelText("邀请码"), "CD45EF")
    await user.click(screen.getByRole("button", { name: "加入家庭" }))
    expect(session.family_id).toBe("fam-a")
    expect(await screen.findByText("面条")).toBeInTheDocument()
    expect(screen.queryByText("你已被移出家庭")).toBeNull()
    expect(screen.queryByText("牛奶")).toBeNull()
    const afterJoin = syncs
    await act(async () => {
      for (const resolve of held.splice(0)) resolve(oldPage)
      sockets[0]?.emit({ t: "changed", rev: 9 })
      await Promise.resolve()
      await Promise.resolve()
    })
    expect(syncs).toBe(afterJoin)
    expect(screen.getByRole("heading", { level: 1, name: "旧家" })).toBeInTheDocument()
    expect(screen.getByText("面条")).toBeInTheDocument()
    expect(screen.getByText("CD45EF")).toBeInTheDocument()
    expect(screen.queryByText("牛奶")).toBeNull()
    expect(screen.queryByText("AB23CD")).toBeNull()
    expect(screen.queryByText("你已被移出家庭")).toBeNull()
    expect(calls).toEqual(["join:CD45EF"])
  })

  it("drops the old sync after a voluntary leave and can create the next family", async () => {
    const user = userEvent.setup()
    const held: Array<(page: SyncPage) => void> = []
    let mode: "a" | "hang" | "b" = "a"
    let session = me("fam-a")
    const calls: string[] = []
    const sockets: ScriptedSocket[] = []
    const api = {
      requestCode: async () => undefined,
      login: async () => ({ user_id: "user-1", display_name: "自己" }),
      logout: async () => undefined,
      me: async () => session,
      createFamily: async (name: string) => {
        calls.push(`create:${name}`)
        session = me("fam-b")
        mode = "b"
        return { ...newFamily, name }
      },
      joinFamily: async () => newFamily,
      currentFamily: async () => newFamily,
      renameFamily: async () => oldFamily,
      resetInvite: async () => oldFamily,
      transferAdmin: async () => oldFamily,
      removeMember: async () => undefined,
      leaveFamily: async () => {
        calls.push("leave")
        session = me(null)
      },
      createItem: async () => milk(),
      patchItem: async () => ({ item: milk(), overwritten: [], message: null }),
      batch: async () => ({ rev: 1 }),
      undoBatch: async () => ({ skipped: 0, restored: 0, message: null }),
      sync: () => {
        if (mode === "hang") return new Promise<SyncPage>((resolve) => { held.push(resolve) })
        return Promise.resolve(mode === "b" ? page({ ...newFamily, name: "新家" }, []) : oldPage)
      },
      photo: async () => {
        throw new Error("unused")
      },
      webSocketUrl: () => "ws://127.0.0.1/api/v1/ws",
    } satisfies PantryClient
    renderApp(api, () => {
      const socket = new ScriptedSocket()
      sockets.push(socket)
      return socket
    })
    expect(await screen.findByText("牛奶")).toBeInTheDocument()
    mode = "hang"
    await act(async () => {
      sockets[0]?.emit({ t: "changed", rev: 4 })
      await Promise.resolve()
    })
    expect(held).toHaveLength(1)
    await user.click(screen.getByRole("button", { name: "家庭" }))
    await user.click(screen.getByRole("button", { name: "退出家庭" }))
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "退出" }))
    expect(await screen.findByRole("button", { name: "创建家庭" })).toBeInTheDocument()
    await act(async () => {
      for (const resolve of held.splice(0)) resolve(oldPage)
      await Promise.resolve()
    })
    expect(screen.queryByText("牛奶")).toBeNull()
    expect(screen.queryByText("AB23CD")).toBeNull()
    expect(screen.queryByText("你已被移出家庭")).toBeNull()
    await user.type(screen.getByLabelText("家庭名称"), "新家")
    await user.click(screen.getByRole("button", { name: "创建家庭" }))
    expect(await screen.findByRole("heading", { level: 1, name: "新家" })).toBeInTheDocument()
    expect(screen.queryByText("牛奶")).toBeNull()
    expect(calls).toEqual(["leave", "create:新家"])
  })

  it("stays in the family when transfer succeeds and leave fails", async () => {
    const user = userEvent.setup()
    const calls: string[] = []
    let current = family("fam-a", "旧家", "AB23CD")
    current = {
      ...current,
      members: [
        { user_id: "user-3", display_name: "小红", role: "member", joined_at: "2026-11-10T00:00:00Z" },
        { user_id: "user-1", display_name: "自己", role: "admin", joined_at: "2026-11-03T00:00:00Z" },
      ],
    }
    const session = me("fam-a")
    const api = {
      requestCode: async () => undefined,
      login: async () => ({ user_id: "user-1", display_name: "自己" }),
      logout: async () => undefined,
      me: async () => session,
      createFamily: async () => current,
      joinFamily: async () => current,
      currentFamily: async () => current,
      renameFamily: async () => current,
      resetInvite: async () => current,
      transferAdmin: async (userId: string) => {
        calls.push(`transfer:${userId}`)
        current = {
          ...current,
          role: "member",
          admin_user_id: userId,
          members: current.members.map((person) => (
            person.user_id === userId ? { ...person, role: "admin" } : person.user_id === "user-1" ? { ...person, role: "member" } : person
          )),
        }
        return current
      },
      removeMember: async () => undefined,
      leaveFamily: async () => {
        calls.push("leave")
        throw new ApiError(409, "admin_must_transfer", "请稍后重试")
      },
      createItem: async () => milk(),
      patchItem: async () => ({ item: milk(), overwritten: [], message: null }),
      batch: async () => ({ rev: 1 }),
      undoBatch: async () => ({ skipped: 0, restored: 0, message: null }),
      sync: async () => page(current, [milk()]),
      photo: async () => {
        throw new Error("unused")
      },
      webSocketUrl: () => "ws://127.0.0.1/api/v1/ws",
    } satisfies PantryClient
    renderApp(api, () => new ScriptedSocket())
    expect(await screen.findByText("牛奶")).toBeInTheDocument()
    await user.click(screen.getByRole("button", { name: "家庭" }))
    await user.click(screen.getByRole("button", { name: "退出家庭" }))
    const dialog = await screen.findByRole("dialog")
    await user.click(within(dialog).getByRole("radio", { name: /小红/ }))
    await user.click(within(dialog).getByRole("button", { name: "转让并退出" }))
    expect(await screen.findByText("管理员已转让，但退出失败。请稍后重试")).toBeInTheDocument()
    expect(screen.queryByRole("button", { name: "编辑家庭名" })).toBeNull()
    expect(screen.getByText("自己（我）")).toBeInTheDocument()
    expect(screen.queryByRole("button", { name: "创建家庭" })).toBeNull()
    await user.click(screen.getByRole("button", { name: "清单" }))
    expect(screen.getByText("牛奶")).toBeInTheDocument()
    expect(calls).toEqual(["transfer:user-3", "leave"])
  })
})
