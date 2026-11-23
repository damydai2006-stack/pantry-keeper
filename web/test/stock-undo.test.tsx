import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { ConfigProvider } from "antd"
import zhCN from "antd/locale/zh_CN"
import { afterEach, describe, expect, it, vi } from "vitest"
import { App } from "../src/App"
import type { PantryClient } from "../src/api/client"
import type { Family, Item, Me } from "../src/api/types"
import type { SocketLike } from "../src/sync/useFamilySync"
import { ItemScreen } from "../src/ui/ItemScreen"

afterEach(() => {
  vi.useRealTimers()
})

class QuietSocket implements SocketLike {
  onopen: (() => void) | null = null
  onmessage: ((event: { data: string }) => void) | null = null
  onclose: (() => void) | null = null
  close() {
    this.onclose?.()
  }
}

function family(): Family {
  return {
    id: "fam-1",
    name: "我家",
    admin_user_id: "user-1",
    invite_code: "AB23CD",
    invite_expires_at: "2026-11-27T00:00:00Z",
    role: "admin",
    members: [],
  }
}

function me(): Me {
  return { user_id: "user-1", display_name: "用户", notify_time: "09:00", family_id: "fam-1" }
}

function item(patch: Partial<Item> & Pick<Item, "id" | "name" | "status" | "expiry_date">): Item {
  return {
    category: "food",
    quantity: 1,
    location: "橱柜",
    note: null,
    production_date: null,
    shelf_life_value: null,
    shelf_life_unit: null,
    expiry_basis: "manual_date",
    opened_date: null,
    after_open_days: null,
    reminder_tiers: ["D30", "D5"],
    rev: 2,
    ...patch,
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

function card(name: string): HTMLElement {
  const heading = screen.getByRole("heading", { name, level: 3 })
  const row = heading.closest("li")
  if (!row) throw new Error(`missing card for ${name}`)
  return row
}

function historyRow(name: string): HTMLElement {
  const cell = screen.getByRole("cell", { name })
  const row = cell.closest("tr")
  if (!row) throw new Error(`missing row for ${name}`)
  return row
}

function undoBar(): HTMLElement {
  const bar = screen.getByRole("button", { name: "撤销" }).closest(".undo-bar")
  if (!bar) throw new Error("missing undo bar")
  return bar as HTMLElement
}

describe("batch undo bar", () => {
  it("restores marked items in place when undo is clicked within 5 seconds", async () => {
    const user = userEvent.setup()
    const rows = [
      item({ id: "salt", name: "盐", status: "in_stock", expiry_date: "2026-11-10", reminder_tiers: ["D5"], rev: 2 }),
      item({ id: "milk", name: "牛奶", status: "in_stock", expiry_date: "2026-11-18", reminder_tiers: ["D30", "D5"], rev: 3 }),
      item({ id: "sauce", name: "酱油", status: "in_stock", expiry_date: "2026-12-20", reminder_tiers: ["D5"], rev: 4 }),
    ]
    const batches: { ids: string[]; action: string }[] = []
    const undos: string[] = []
    const api = {
      requestCode: async () => undefined,
      login: async () => ({ user_id: "user-1", display_name: "用户" }),
      logout: async () => undefined,
      me: async () => me(),
      createFamily: async () => family(),
      joinFamily: async () => family(),
      currentFamily: async () => family(),
      createItem: async () => rows[0],
      patchItem: async () => ({ item: rows[0], overwritten: [], message: null }),
      batch: async (ids: string[], action: string) => {
        batches.push({ ids: [...ids], action })
        if (action === "used_up" || action === "discarded") {
          for (const row of rows) {
            if (!ids.includes(row.id)) continue
            row.status = action
            row.rev += 1
          }
        }
        return { undo_token: "token-used", rev: 9 }
      },
      undoBatch: async (token: string) => {
        undos.push(token)
        for (const row of rows) {
          if (row.id === "milk" || row.id === "sauce") {
            row.status = "in_stock"
            row.rev += 1
          }
        }
        return { skipped: 0, restored: 2, message: null }
      },
      sync: async () => ({
        reset: false,
        rev: 9,
        items: rows.filter((row) => row.status !== "deleted"),
        tombstones: [],
        family: family(),
        notify_time: "09:00",
      }),
      photo: async () => {
        throw new Error("photo unused")
      },
      webSocketUrl: () => "ws://127.0.0.1/api/v1/ws",
    } satisfies PantryClient
    renderApp(api)
    expect(await screen.findByRole("heading", { name: "盐" })).toBeInTheDocument()
    await user.click(screen.getByRole("button", { name: "管理" }))
    await user.click(screen.getByRole("checkbox", { name: "选择牛奶" }))
    await user.click(screen.getByRole("checkbox", { name: "选择酱油" }))
    await user.click(within(screen.getByRole("toolbar", { name: "在库批量操作" })).getByRole("button", { name: "已用完" }))

    const bar = await screen.findByText("已将 2 件标记为已用完，已移入历史")
    const frame = bar.closest(".undo-bar")
    if (!frame) throw new Error("missing undo bar")
    expect((frame as HTMLElement).style.backgroundColor).toBe("rgb(26, 29, 26)")
    expect((frame as HTMLElement).style.color).toBe("rgb(255, 255, 255)")
    expect(frame).toHaveTextContent("5 秒")
    expect(within(frame as HTMLElement).getByRole("button", { name: "撤销" }).style.minHeight).toBe("48px")
    expect(screen.queryByRole("heading", { name: "牛奶" })).toBeNull()
    expect(screen.queryByRole("heading", { name: "酱油" })).toBeNull()
    expect(screen.getByRole("heading", { name: "盐" })).toBeInTheDocument()

    await user.click(within(frame as HTMLElement).getByRole("button", { name: "撤销" }))
    await waitFor(() => expect(undos).toEqual(["token-used"]))
    expect(batches).toEqual([{ ids: ["milk", "sauce"], action: "used_up" }])
    await waitFor(() => expect(screen.queryByRole("button", { name: "撤销" })).toBeNull())
    expect(screen.getAllByRole("heading", { level: 3 }).map((heading) => heading.textContent)).toEqual(["盐", "牛奶", "酱油"])
    expect(card("牛奶")).toHaveTextContent("到期 2026-11-18")
    expect(card("酱油")).toHaveTextContent("到期 2026-12-20")
    expect(rows[1]).toMatchObject({ status: "in_stock", reminder_tiers: ["D30", "D5"], expiry_date: "2026-11-18" })
    expect(rows[2]).toMatchObject({ status: "in_stock", reminder_tiers: ["D5"], expiry_date: "2026-12-20" })
    expect(screen.queryByText("已恢复到清单")).toBeNull()
  })

  it("tells how many items were skipped and still restores the rest", async () => {
    const user = userEvent.setup()
    const rows = [
      item({ id: "milk", name: "牛奶", status: "in_stock", expiry_date: "2026-11-18" }),
      item({ id: "sauce", name: "酱油", status: "in_stock", expiry_date: "2026-12-20" }),
    ]
    const undos: string[] = []
    const api = {
      requestCode: async () => undefined,
      login: async () => ({ user_id: "user-1", display_name: "用户" }),
      logout: async () => undefined,
      me: async () => me(),
      createFamily: async () => family(),
      joinFamily: async () => family(),
      currentFamily: async () => family(),
      createItem: async () => rows[0],
      patchItem: async () => ({ item: rows[0], overwritten: [], message: null }),
      batch: async (ids: string[]) => {
        for (const row of rows) {
          if (!ids.includes(row.id)) continue
          row.status = "used_up"
        }
        return { undo_token: "token-skip", rev: 9 }
      },
      undoBatch: async (token: string) => {
        undos.push(token)
        rows[0].status = "in_stock"
        return { skipped: 1, restored: 1, message: "1 件已被他人修改，未撤销" }
      },
      sync: async () => ({
        reset: false,
        rev: 9,
        items: rows,
        tombstones: [],
        family: family(),
        notify_time: "09:00",
      }),
      photo: async () => {
        throw new Error("photo unused")
      },
      webSocketUrl: () => "ws://127.0.0.1/api/v1/ws",
    } satisfies PantryClient
    renderApp(api)
    await screen.findByRole("heading", { name: "牛奶" })
    await user.click(screen.getByRole("button", { name: "管理" }))
    await user.click(screen.getByRole("button", { name: "全选" }))
    await user.click(within(screen.getByRole("toolbar", { name: "在库批量操作" })).getByRole("button", { name: "已用完" }))
    await user.click(await screen.findByRole("button", { name: "撤销" }))
    expect(await screen.findByText("1 件已被他人修改，未撤销")).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole("button", { name: "撤销" })).toBeNull())
    expect(undos).toEqual(["token-skip"])
    expect(screen.getByRole("heading", { name: "牛奶" })).toBeInTheDocument()
    expect(screen.queryByRole("heading", { name: "酱油" })).toBeNull()
  })
})

describe("undo bar boundaries", () => {
  function renderList(online?: boolean) {
    const marks: { ids: string[]; action: string }[] = []
    const undos: string[] = []
    const restores: string[][] = []
    const deletes: string[] = []
    const used: string[] = []
    let token = "token-1"
    const rows = [
      item({ id: "milk", name: "牛奶", status: "in_stock", expiry_date: "2026-11-14", location: "冰箱" }),
      item({ id: "sauce", name: "酱油", status: "in_stock", expiry_date: "2026-12-20", location: "橱柜" }),
      item({ id: "rice", name: "大米", status: "used_up", expiry_date: "2026-12-01", reminder_tiers: ["D30"] }),
    ]
    let undoImpl: (token: string) => Promise<{ skipped: number; restored: number; message: string | null }> = async (next) => {
      undos.push(next)
      return { skipped: 0, restored: 1, message: null }
    }
    const view = render(
      <ConfigProvider locale={zhCN} button={{ autoInsertSpace: false }}>
        <ItemScreen
          familyName="我家"
          inviteCode={null}
          inviteExpires={null}
          items={rows}
          today="2026-11-15"
          loading={false}
          error={null}
          live={false}
          busy={false}
          online={online}
          onRefresh={async () => undefined}
          onCreate={async () => undefined}
          onUpdate={async () => null}
          onDelete={async (id) => { deletes.push(id) }}
          onUsed={async (id) => { used.push(id) }}
          onMark={async (ids, action) => {
            marks.push({ ids: [...ids], action })
            const current = token
            token = token === "token-1" ? "token-2" : "token-3"
            return { undo_token: action === "used_up" ? current : `discarded-${current}` }
          }}
          onUndo={(next) => undoImpl(next)}
          onRestore={async (ids) => { restores.push(ids) }}
          onDeleteHistory={async () => undefined}
          onLogout={async () => undefined}
        />
      </ConfigProvider>,
    )
    return {
      marks,
      undos,
      restores,
      deletes,
      used,
      rows,
      setUndo(next: typeof undoImpl) { undoImpl = next },
      unmount: () => view.unmount(),
      rerender(nextOnline: boolean | undefined, nextItems = rows) {
        view.rerender(
          <ConfigProvider locale={zhCN} button={{ autoInsertSpace: false }}>
            <ItemScreen
              familyName="我家"
              inviteCode={null}
              inviteExpires={null}
              items={nextItems}
              today="2026-11-15"
              loading={false}
              error={null}
              live={false}
              busy={false}
              online={nextOnline}
              onRefresh={async () => undefined}
              onCreate={async () => undefined}
              onUpdate={async () => null}
              onDelete={async (id) => { deletes.push(id) }}
              onUsed={async (id) => { used.push(id) }}
              onMark={async (ids, action) => {
                marks.push({ ids: [...ids], action })
                const current = token
                token = token === "token-1" ? "token-2" : "token-3"
                return { undo_token: action === "used_up" ? current : `discarded-${current}` }
              }}
              onUndo={(next) => undoImpl(next)}
              onRestore={async (ids) => { restores.push(ids) }}
              onDeleteHistory={async () => undefined}
              onLogout={async () => undefined}
            />
          </ConfigProvider>,
        )
      },
    }
  }

  async function markOne(user: ReturnType<typeof userEvent.setup>, name: string, action: "已用完" | "已处理") {
    await user.click(screen.getByRole("button", { name: "管理" }))
    await user.click(screen.getByRole("checkbox", { name: `选择${name}` }))
    await user.click(within(screen.getByRole("toolbar", { name: "在库批量操作" })).getByRole("button", { name: action }))
    await screen.findByRole("button", { name: "撤销" })
  }

  it("shows the discarded copy, then removes the bar when the 5 seconds elapse", async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] })
    const user = userEvent.setup()
    const view = renderList(true)
    await markOne(user, "酱油", "已处理")
    const bar = undoBar()
    expect(bar).toHaveTextContent("已将 1 件标记为已处理，已移入历史")
    expect(bar).toHaveTextContent("5 秒")
    expect(bar.style.backgroundColor).toBe("rgb(26, 29, 26)")
    expect(bar.style.color).toBe("rgb(255, 255, 255)")
    act(() => { vi.advanceTimersByTime(1000) })
    expect(undoBar()).toHaveTextContent("4 秒")
    act(() => { vi.advanceTimersByTime(4000) })
    expect(screen.queryByRole("button", { name: "撤销" })).toBeNull()
    expect(screen.queryByText(/已移入历史/)).toBeNull()
    expect(view.undos).toEqual([])
    expect(view.marks).toEqual([{ ids: ["sauce"], action: "discarded" }])
  })

  it("keeps a retry after the countdown ends during an undo that was not rejected as expired", async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] })
    const user = userEvent.setup()
    const view = renderList(true)
    let rejectPending: (reason: Error) => void = () => undefined
    const pending = new Promise<{ skipped: number; restored: number; message: null }>((_resolve, reject) => {
      rejectPending = reject
    })
    let attempts = 0
    view.setUndo(() => {
      attempts += 1
      return pending
    })
    await markOne(user, "牛奶", "已用完")
    fireEvent.click(screen.getByRole("button", { name: "撤销" }))
    expect(attempts).toBe(1)
    act(() => { vi.advanceTimersByTime(5000) })
    expect(screen.getByRole("button", { name: "撤销" })).toBeDisabled()
    expect(screen.getByText("已将 1 件标记为已用完，已移入历史")).toBeInTheDocument()
    await act(async () => { rejectPending(new Error("当前无网络，请稍后重试")) })
    expect(await screen.findByText("当前无网络，请稍后重试")).toBeInTheDocument()
    const retry = screen.getByRole("button", { name: "撤销" })
    expect(retry).toBeEnabled()
    expect(undoBar()).toHaveTextContent("5 秒")
    view.setUndo(async () => {
      attempts += 1
      return { skipped: 0, restored: 1, message: null }
    })
    await user.click(retry)
    await waitFor(() => expect(attempts).toBe(2))
    await waitFor(() => expect(screen.queryByRole("button", { name: "撤销" })).toBeNull())
  })

  it("still closes the undo bar when the server says the undo window has expired", async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] })
    const user = userEvent.setup()
    const view = renderList(true)
    let rejectPending: (reason: Error) => void = () => undefined
    const pending = new Promise<{ skipped: number; restored: number; message: null }>((_resolve, reject) => {
      rejectPending = reject
    })
    view.setUndo(() => pending)
    await markOne(user, "牛奶", "已用完")
    fireEvent.click(screen.getByRole("button", { name: "撤销" }))
    act(() => { vi.advanceTimersByTime(5000) })
    expect(screen.getByRole("button", { name: "撤销" })).toBeInTheDocument()
    await act(async () => { rejectPending(new Error("撤销已超过时限")) })
    expect(await screen.findByText("撤销已超过时限")).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole("button", { name: "撤销" })).toBeNull())
    expect(view.undos).toEqual([])
  })

  it("does not show an undo bar for row delete or the single used-up action", async () => {
    const user = userEvent.setup()
    const view = renderList(true)
    await user.click(within(card("牛奶")).getByRole("button", { name: "已用完" }))
    expect(view.used).toEqual(["milk"])
    expect(screen.queryByRole("button", { name: "撤销" })).toBeNull()
    expect(screen.queryByText(/已移入历史/)).toBeNull()

    await user.click(within(card("酱油")).getByRole("button", { name: "删除" }))
    const dialog = await screen.findByRole("dialog")
    expect(within(dialog).queryByRole("button", { name: "撤销" })).toBeNull()
    expect(dialog).toHaveTextContent("永久删除，不可恢复")
    await user.click(within(dialog).getByRole("button", { name: "删除" }))
    expect(view.deletes).toEqual(["sauce"])
    expect(screen.queryByRole("button", { name: "撤销" })).toBeNull()
    expect(screen.queryByText(/已移入历史/)).toBeNull()
  })

  it("disables in-stock delete while offline and blocks a confirmation that loses the network", async () => {
    const user = userEvent.setup()
    const view = renderList(false)
    const remove = within(card("牛奶")).getByRole("button", { name: "删除" })
    expect(remove).toBeDisabled()
    expect(within(card("牛奶")).getByRole("button", { name: "已用完" })).toBeDisabled()
    fireEvent.click(remove)
    expect(screen.queryByRole("dialog")).toBeNull()
    expect(view.deletes).toEqual([])

    view.rerender(true)
    await user.click(within(card("牛奶")).getByRole("button", { name: "删除" }))
    const dialog = await screen.findByRole("dialog")
    expect(within(dialog).getByRole("button", { name: "删除" })).toBeEnabled()
    view.rerender(false)
    const dropped = screen.getByRole("dialog")
    expect(within(dropped).getByRole("button", { name: "删除" })).toBeDisabled()
    expect(within(dropped).getByText("恢复网络后再修改")).toBeInTheDocument()
    fireEvent.click(within(dropped).getByRole("button", { name: "删除" }))
    expect(view.deletes).toEqual([])
    expect(screen.getByRole("dialog")).toBeInTheDocument()
  })

  it("keeps one in-flight undo, reports a failure, and closes the bar when the token has expired", async () => {
    const user = userEvent.setup()
    const view = renderList(true)
    let release: (value: { skipped: number; restored: number; message: null }) => void = () => undefined
    const pending = new Promise<{ skipped: number; restored: number; message: null }>((resolve) => { release = resolve })
    let attempts = 0
    view.setUndo(() => {
      attempts += 1
      return pending
    })
    await markOne(user, "牛奶", "已用完")
    const button = screen.getByRole("button", { name: "撤销" })
    fireEvent.click(button)
    fireEvent.click(button)
    expect(attempts).toBe(1)
    await act(async () => { release({ skipped: 0, restored: 1, message: null }) })
    await waitFor(() => expect(screen.queryByRole("button", { name: "撤销" })).toBeNull())

    view.setUndo(async () => { throw new Error("当前无网络，请稍后重试") })
    await markOne(user, "酱油", "已用完")
    await user.click(screen.getByRole("button", { name: "撤销" }))
    expect(await screen.findByText("当前无网络，请稍后重试")).toBeInTheDocument()
    expect(screen.getByRole("button", { name: "撤销" })).toBeEnabled()
    expect(undoBar()).toHaveTextContent("已将 1 件标记为已用完，已移入历史")

    view.setUndo(async () => { throw new Error("撤销已超过时限") })
    await user.click(screen.getByRole("button", { name: "撤销" }))
    expect(await screen.findByText("撤销已超过时限")).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole("button", { name: "撤销" })).toBeNull())
  })

  it("disables undo while offline and replaces the bar when another batch succeeds", async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] })
    const user = userEvent.setup()
    const view = renderList(true)
    await markOne(user, "牛奶", "已用完")
    expect(undoBar()).toHaveTextContent("5 秒")
    act(() => { vi.advanceTimersByTime(2000) })
    expect(undoBar()).toHaveTextContent("3 秒")
    view.rerender(false)
    expect(screen.getByText("恢复网络后再修改")).toBeInTheDocument()
    expect(screen.getByRole("button", { name: "撤销" })).toBeDisabled()
    fireEvent.click(screen.getByRole("button", { name: "撤销" }))
    expect(view.undos).toEqual([])
    view.rerender(true)
    await markOne(user, "酱油", "已处理")
    const bar = undoBar()
    expect(bar).toHaveTextContent("已将 1 件标记为已处理，已移入历史")
    expect(bar).toHaveTextContent("5 秒")
    expect(screen.queryByText("已将 1 件标记为已用完，已移入历史")).toBeNull()
    await user.click(within(bar).getByRole("button", { name: "撤销" }))
    await waitFor(() => expect(view.undos).toEqual(["discarded-token-2"]))
    await waitFor(() => expect(screen.queryByRole("button", { name: "撤销" })).toBeNull())
    expect(view.marks).toEqual([
      { ids: ["milk"], action: "used_up" },
      { ids: ["sauce"], action: "discarded" },
    ])
  })

  it("hides the bar when leaving the list, drops it on timeout, and does not treat history restore as undo", async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] })
    const user = userEvent.setup()
    const view = renderList(true)
    await markOne(user, "牛奶", "已用完")
    await user.click(screen.getByRole("button", { name: "历史" }))
    expect(screen.queryByRole("button", { name: "撤销" })).toBeNull()
    expect(view.undos).toEqual([])
    await user.click(within(historyRow("大米")).getByRole("button", { name: "恢复" }))
    expect(view.restores).toEqual([["rice"]])
    expect(view.undos).toEqual([])
    expect(await screen.findByText("已恢复到清单")).toBeInTheDocument()
    await user.click(screen.getByRole("button", { name: "清单" }))
    expect(undoBar()).toHaveTextContent("已将 1 件标记为已用完，已移入历史")
    expect(undoBar()).toHaveTextContent("5 秒")

    await user.click(screen.getByRole("button", { name: "历史" }))
    act(() => { vi.advanceTimersByTime(5000) })
    await user.click(screen.getByRole("button", { name: "清单" }))
    expect(screen.queryByRole("button", { name: "撤销" })).toBeNull()
    expect(view.undos).toEqual([])
    expect(screen.getByRole("heading", { name: "酱油" })).toBeInTheDocument()
  })

  it("clears the countdown when the list unmounts", async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] })
    const user = userEvent.setup()
    const cleared: unknown[] = []
    const original = window.clearInterval.bind(window)
    const spy = vi.spyOn(window, "clearInterval").mockImplementation((handle) => {
      cleared.push(handle)
      original(handle as number)
    })
    const view = renderList(true)
    await markOne(user, "牛奶", "已用完")
    expect(screen.getByRole("button", { name: "撤销" })).toBeInTheDocument()
    const before = cleared.length
    view.unmount()
    expect(cleared.length).toBeGreaterThan(before)
    act(() => { vi.advanceTimersByTime(5000) })
    expect(view.undos).toEqual([])
    spy.mockRestore()
  })
})
