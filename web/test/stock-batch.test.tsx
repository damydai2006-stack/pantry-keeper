import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { render, screen, waitFor, within } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { ConfigProvider } from "antd"
import zhCN from "antd/locale/zh_CN"
import { describe, expect, it } from "vitest"
import { App } from "../src/App"
import type { PantryClient } from "../src/api/client"
import { ApiError, type Family, type Item, type Me } from "../src/api/types"
import type { SocketLike } from "../src/sync/useFamilySync"
import { ItemScreen } from "../src/ui/ItemScreen"

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

describe("in-stock batch mark", () => {
  it("marks a selection used up or discarded in one batch and leaves the other fields unchanged", async () => {
    const user = userEvent.setup()
    const rows = [
      item({ id: "milk", name: "牛奶", status: "in_stock", expiry_date: "2026-11-18", reminder_tiers: ["D30", "D5"], rev: 2 }),
      item({ id: "sauce", name: "酱油", status: "in_stock", expiry_date: "2026-12-20", reminder_tiers: ["D5"], rev: 3 }),
      item({ id: "rice", name: "大米", status: "used_up", expiry_date: "2026-12-01", reminder_tiers: ["D30"], rev: 4 }),
    ]
    const batches: { ids: string[]; action: string }[] = []
    let patches = 0
    const api = {
      requestCode: async () => undefined,
      login: async () => ({ user_id: "user-1", display_name: "用户" }),
      logout: async () => undefined,
      me: async () => me(),
      createFamily: async () => family(),
      joinFamily: async () => family(),
      currentFamily: async () => family(),
      createItem: async () => rows[0],
      patchItem: async () => {
        patches += 1
        return { item: rows[0], overwritten: [], message: null }
      },
      batch: async (ids: string[], action: string) => {
        batches.push({ ids: [...ids], action })
        if (action === "used_up" || action === "discarded") {
          for (const row of rows) {
            if (!ids.includes(row.id)) continue
            row.status = action
            row.rev += 1
          }
        }
        return { undo_token: "not-used", rev: 9 }
      },
      sync: async () => ({
        reset: false,
        rev: 9,
        items: rows.filter((row) => row.status !== "deleted"),
        tombstones: [],
        family: family(),
        notify_time: "09:00",
      }),
      webSocketUrl: () => "ws://127.0.0.1/api/v1/ws",
    } satisfies PantryClient
    renderApp(api)
    expect(await screen.findByRole("heading", { name: "牛奶" })).toBeInTheDocument()
    expect(screen.getByRole("heading", { name: "酱油" })).toBeInTheDocument()
    expect(screen.queryByRole("heading", { name: "大米" })).toBeNull()

    await user.click(screen.getByRole("button", { name: "管理" }))
    await user.click(screen.getByRole("checkbox", { name: "选择牛奶" }))
    const bar = screen.getByRole("toolbar", { name: "在库批量操作" })
    expect(bar).toHaveTextContent("已选 1 件")
    expect(within(bar).getByRole("button", { name: "已处理" })).toBeEnabled()
    await user.click(within(bar).getByRole("button", { name: "已用完" }))

    await waitFor(() => expect(batches).toEqual([{ ids: ["milk"], action: "used_up" }]))
    expect(patches).toBe(0)
    expect(rows[0]).toMatchObject({ status: "used_up", expiry_date: "2026-11-18", reminder_tiers: ["D30", "D5"] })
    expect(await screen.findByText("已将 1 件标记为已用完，已移入历史")).toBeInTheDocument()
    expect(screen.queryByRole("heading", { name: "牛奶" })).toBeNull()
    expect(screen.getByRole("heading", { name: "酱油" })).toBeInTheDocument()
    expect(screen.queryByRole("checkbox")).toBeNull()
    expect(screen.queryByRole("button", { name: "撤销" })).toBeNull()
    expect(screen.queryByRole("dialog")).toBeNull()
    expect(screen.getByText("已将 1 件标记为已用完，已移入历史")).not.toHaveTextContent("撤销")

    await user.click(screen.getByRole("button", { name: "管理" }))
    await user.click(screen.getByRole("checkbox", { name: "选择酱油" }))
    await user.click(within(screen.getByRole("toolbar", { name: "在库批量操作" })).getByRole("button", { name: "已处理" }))
    await waitFor(() => expect(batches[1]).toEqual({ ids: ["sauce"], action: "discarded" }))
    expect(rows[1]).toMatchObject({ status: "discarded", expiry_date: "2026-12-20", reminder_tiers: ["D5"] })
    expect(await screen.findByText("已将 1 件标记为已处理，已移入历史")).toBeInTheDocument()
    expect(screen.queryByRole("heading", { name: "酱油" })).toBeNull()

    await user.click(screen.getByRole("button", { name: "历史" }))
    const milk = historyRow("牛奶")
    const sauce = historyRow("酱油")
    expect(milk).toHaveTextContent("已用完")
    expect(milk).toHaveTextContent("2026-11-18")
    expect(milk).toHaveTextContent("提前 30 天、提前 5 天")
    expect(sauce).toHaveTextContent("已处理")
    expect(sauce).toHaveTextContent("2026-12-20")
    expect(sauce).toHaveTextContent("提前 5 天")
    expect(historyRow("大米")).toHaveTextContent("已用完")
  })

  it("keeps every selected item in stock when the batch is rejected", async () => {
    const user = userEvent.setup()
    const rows = [
      item({ id: "milk", name: "牛奶", status: "in_stock", expiry_date: "2026-11-14" }),
      item({ id: "sauce", name: "酱油", status: "in_stock", expiry_date: "2026-12-20" }),
    ]
    const batches: { ids: string[]; action: string }[] = []
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
        throw new ApiError(404, "not_found", "未找到")
      },
      sync: async () => ({
        reset: false,
        rev: 2,
        items: rows,
        tombstones: [],
        family: family(),
        notify_time: "09:00",
      }),
      webSocketUrl: () => "ws://127.0.0.1/api/v1/ws",
    } satisfies PantryClient
    renderApp(api)
    await screen.findByRole("heading", { name: "牛奶" })
    await user.click(screen.getByRole("button", { name: "管理" }))
    await user.click(screen.getByRole("button", { name: "全选" }))
    expect(screen.getByRole("checkbox", { name: "选择牛奶" })).toBeChecked()
    expect(screen.getByRole("checkbox", { name: "选择酱油" })).toBeChecked()
    await user.click(within(screen.getByRole("toolbar", { name: "在库批量操作" })).getByRole("button", { name: "已用完" }))
    expect(await screen.findByText("这批没有改动，物品仍在库：未找到")).toBeInTheDocument()
    expect(batches).toEqual([{ ids: ["milk", "sauce"], action: "used_up" }])
    expect(rows.map((row) => row.status)).toEqual(["in_stock", "in_stock"])
    expect(screen.getByRole("heading", { name: "牛奶" })).toBeInTheDocument()
    expect(screen.getByRole("heading", { name: "酱油" })).toBeInTheDocument()
    expect(screen.getByRole("checkbox", { name: "选择牛奶" })).toBeChecked()
    expect(screen.getByRole("checkbox", { name: "选择酱油" })).toBeChecked()
    expect(screen.queryByRole("button", { name: "撤销" })).toBeNull()
  })
})

describe("in-stock selection controls", () => {
  function renderList(online?: boolean) {
    const marks: { ids: string[]; action: string }[] = []
    const used: string[] = []
    const saves: { id: string; rev: number; fields: Record<string, unknown> }[] = []
    const rows = [
      item({ id: "milk", name: "牛奶", status: "in_stock", expiry_date: "2026-11-14", location: "冰箱" }),
      item({ id: "sauce", name: "酱油", status: "in_stock", expiry_date: "2026-12-20", location: "橱柜", rev: 8 }),
      item({ id: "rice", name: "大米", status: "used_up", expiry_date: "2026-12-01" }),
    ]
    const screenApi = render(
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
          onUpdate={async (id, rev, fields) => {
            saves.push({ id, rev, fields })
            return null
          }}
          onDelete={async () => undefined}
          onUsed={async (id) => { used.push(id) }}
          onMark={async (ids, action) => { marks.push({ ids: [...ids], action }) }}
          onRestore={async () => { marks.push({ ids: ["restore"], action: "restore" }) }}
          onDeleteHistory={async () => { marks.push({ ids: ["delete"], action: "delete" }) }}
          onLogout={async () => undefined}
        />
      </ConfigProvider>,
    )
    return {
      marks,
      used,
      saves,
      rows,
      rerender(nextOnline: boolean | undefined, nextItems = rows) {
        screenApi.rerender(
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
              onUpdate={async (id, rev, fields) => {
                saves.push({ id, rev, fields })
                return null
              }}
              onDelete={async () => undefined}
              onUsed={async (id) => { used.push(id) }}
              onMark={async (ids, action) => { marks.push({ ids: [...ids], action }) }}
              onRestore={async () => { marks.push({ ids: ["restore"], action: "restore" }) }}
              onDeleteHistory={async () => { marks.push({ ids: ["delete"], action: "delete" }) }}
              onLogout={async () => undefined}
            />
          </ConfigProvider>,
        )
      },
    }
  }

  it("cancels a selection without a request and drops rows hidden by search", async () => {
    const user = userEvent.setup()
    const view = renderList(true)
    await user.click(within(card("牛奶")).getByRole("button", { name: "已用完" }))
    expect(view.used).toEqual(["milk"])
    expect(view.marks).toEqual([])

    await user.click(screen.getByRole("button", { name: "管理" }))
    await user.click(screen.getByRole("checkbox", { name: "选择酱油" }))
    await user.click(screen.getByRole("button", { name: "取消" }))
    expect(screen.queryByRole("checkbox")).toBeNull()
    expect(view.marks).toEqual([])

    await user.click(screen.getByRole("button", { name: "管理" }))
    await user.click(screen.getByRole("button", { name: "全选" }))
    expect(screen.getByRole("checkbox", { name: "选择牛奶" })).toBeChecked()
    expect(screen.getByRole("checkbox", { name: "选择酱油" })).toBeChecked()
    await user.type(screen.getByLabelText("搜索物品"), "酱油")
    expect(screen.queryByRole("checkbox", { name: "选择牛奶" })).toBeNull()
    expect(screen.getByRole("checkbox", { name: "选择酱油" })).toBeChecked()
    await user.click(within(screen.getByRole("toolbar", { name: "在库批量操作" })).getByRole("button", { name: "已处理" }))
    await waitFor(() => expect(view.marks).toEqual([{ ids: ["sauce"], action: "discarded" }]))
    expect(view.saves).toEqual([])
  })

  it("disables manage and mark actions while offline", async () => {
    const offline = renderList(false)
    expect(screen.getByRole("status")).toHaveTextContent("恢复网络后再修改")
    expect(screen.getByRole("button", { name: "管理" })).toBeDisabled()
    expect(within(card("牛奶")).getByRole("button", { name: "已用完" })).toBeDisabled()
    expect(screen.queryByRole("checkbox")).toBeNull()
    expect(offline.marks).toEqual([])
    expect(offline.used).toEqual([])
  })

  it("blocks a selection that was already open when the network drops", async () => {
    const user = userEvent.setup()
    const online = renderList(true)
    await user.click(screen.getByRole("button", { name: "管理" }))
    await user.click(screen.getByRole("checkbox", { name: "选择牛奶" }))
    online.rerender(false)
    const bar = await screen.findByRole("toolbar", { name: "在库批量操作" })
    await waitFor(() => expect(within(bar).getByRole("button", { name: "已用完" })).toBeDisabled())
    expect(within(bar).getByRole("button", { name: "已处理" })).toBeDisabled()
    expect(within(bar).getByRole("button", { name: "全选" })).toBeDisabled()
    expect(screen.getByRole("checkbox", { name: "选择牛奶" })).toBeDisabled()
    await user.click(screen.getByRole("button", { name: "取消" }))
    expect(screen.queryByRole("checkbox")).toBeNull()
    expect(online.marks).toEqual([])
  })

  it("does not mix a stock selection with an open edit or the history view", async () => {
    const user = userEvent.setup()
    const view = renderList(true)
    await user.click(within(card("牛奶")).getByRole("button", { name: "编辑" }))
    await user.type(screen.getByLabelText("物品名称"), "改")
    expect(screen.getByLabelText("物品名称")).toHaveValue("牛奶改")

    await user.click(screen.getByRole("button", { name: "管理" }))
    await user.click(screen.getByRole("checkbox", { name: "选择酱油" }))
    await user.click(within(screen.getByRole("toolbar", { name: "在库批量操作" })).getByRole("button", { name: "已用完" }))
    await waitFor(() => expect(view.marks).toEqual([{ ids: ["sauce"], action: "used_up" }]))
    expect(view.saves).toEqual([])
    expect(screen.getByLabelText("物品名称")).toHaveValue("牛奶改")

    await user.click(screen.getByRole("button", { name: "管理" }))
    await user.click(screen.getByRole("checkbox", { name: "选择牛奶" }))
    await user.click(within(screen.getByRole("toolbar", { name: "在库批量操作" })).getByRole("button", { name: "已处理" }))
    await waitFor(() => expect(view.marks[1]).toEqual({ ids: ["milk"], action: "discarded" }))
    expect(screen.queryByLabelText("物品名称")).toBeNull()
    expect(view.saves).toEqual([])

    await user.click(screen.getByRole("button", { name: "管理" }))
    await user.click(screen.getByRole("checkbox", { name: "选择酱油" }))
    await user.click(screen.getByRole("button", { name: "历史" }))
    expect(screen.queryByRole("checkbox")).toBeNull()
    expect(historyRow("大米")).toHaveTextContent("已用完")
    await user.click(screen.getByRole("button", { name: "管理" }))
    await user.click(screen.getByRole("checkbox", { name: "选择大米" }))
    await user.click(screen.getByRole("button", { name: "清单" }))
    expect(screen.queryByRole("checkbox")).toBeNull()
    expect(screen.getByRole("heading", { name: "酱油" })).toBeInTheDocument()
    expect(view.marks).toEqual([
      { ids: ["sauce"], action: "used_up" },
      { ids: ["milk"], action: "discarded" },
    ])
  })
})
