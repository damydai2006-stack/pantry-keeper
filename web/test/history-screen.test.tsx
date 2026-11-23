import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { render, screen, waitFor, within } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { describe, expect, it } from "vitest"
import { App } from "../src/App"
import type { PantryClient } from "../src/api/client"
import type { Family, Item, Me } from "../src/api/types"
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

function rowOf(name: string): HTMLElement {
  const cell = screen.getByRole("cell", { name })
  const row = cell.closest("tr")
  if (!row) throw new Error(`missing row for ${name}`)
  return row
}

function renderApp(api: PantryClient) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <App api={api} now={() => new Date("2026-11-15T04:00:00Z")} socketFactory={() => new QuietSocket()} />
    </QueryClientProvider>,
  )
}

describe("web history restore", () => {
  it("restores one history row without changing expiry or reminder tiers", async () => {
    const user = userEvent.setup()
    const rows = [
      item({ id: "milk", name: "牛奶", status: "in_stock", expiry_date: "2026-11-14", reminder_tiers: ["D5"], rev: 1 }),
      item({
        id: "rice",
        name: "大米",
        status: "used_up",
        expiry_date: "2026-12-01",
        reminder_tiers: ["D30", "D5"],
        status_changed_at: "2026-11-20T16:30:00Z",
        rev: 4,
      }),
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
        if (action === "restore") {
          for (const row of rows) {
            if (!ids.includes(row.id)) continue
            row.status = "in_stock"
            row.rev += 1
          }
        }
        return { rev: 8 }
      },
      sync: async () => ({
        reset: false,
        rev: 8,
        items: rows.filter((row) => row.status !== "deleted"),
        tombstones: rows.filter((row) => row.status === "deleted").map((row) => ({ item_id: row.id, rev: 8 })),
        family: family(),
        notify_time: "09:00",
      }),
      webSocketUrl: () => "ws://127.0.0.1/api/v1/ws",
    } satisfies PantryClient
    renderApp(api)
    expect(await screen.findByText("牛奶")).toBeInTheDocument()
    expect(screen.queryByText("大米")).toBeNull()
    await user.click(screen.getByRole("button", { name: "历史" }))
    const rice = rowOf("大米")
    expect(rice).toHaveTextContent("已用完")
    expect(rice).toHaveTextContent("2026-12-01")
    expect(rice).toHaveTextContent("2026-11-21")
    expect(rice).toHaveTextContent("提前 30 天、提前 5 天")
    expect(screen.queryByRole("cell", { name: "牛奶" })).toBeNull()
    await user.click(within(rice).getByRole("button", { name: "恢复" }))
    await waitFor(() => expect(batches).toEqual([{ ids: ["rice"], action: "restore" }]))
    expect(patches).toBe(0)
    expect(rows[1]).toMatchObject({ status: "in_stock", expiry_date: "2026-12-01", reminder_tiers: ["D30", "D5"] })
    expect(await screen.findByText("已恢复到清单")).toBeInTheDocument()
    expect(screen.queryByRole("cell", { name: "大米" })).toBeNull()
    await user.click(screen.getByRole("button", { name: "清单" }))
    expect(await screen.findByRole("heading", { name: "大米" })).toBeInTheDocument()
    expect(screen.getByRole("heading", { name: "大米" }).closest("li")).toHaveTextContent("到期 2026-12-01")
  })

  it("restores a multi-selection in one batch and permanently deletes only after confirmation", async () => {
    const user = userEvent.setup()
    const rows = [
      item({ id: "rice", name: "大米", status: "used_up", expiry_date: "2026-12-01", status_changed_at: "2026-11-20T01:00:00Z" }),
      item({ id: "sauce", name: "酱油", status: "discarded", expiry_date: "2026-10-01", reminder_tiers: [], status_changed_at: "2026-11-19T01:00:00Z" }),
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
        if (action === "delete") {
          for (const row of rows) {
            if (ids.includes(row.id)) row.status = "deleted"
          }
        }
        if (action === "restore") {
          for (const row of rows) {
            if (ids.includes(row.id)) row.status = "in_stock"
          }
        }
        return { rev: 9 }
      },
      sync: async () => ({
        reset: false,
        rev: 9,
        items: rows.filter((row) => row.status !== "deleted"),
        tombstones: rows.filter((row) => row.status === "deleted").map((row) => ({ item_id: row.id, rev: 9 })),
        family: family(),
        notify_time: "09:00",
      }),
      webSocketUrl: () => "ws://127.0.0.1/api/v1/ws",
    } satisfies PantryClient
    renderApp(api)
    await screen.findByRole("button", { name: "历史" })
    await user.click(screen.getByRole("button", { name: "历史" }))
    await user.click(screen.getByRole("button", { name: "已处理" }))
    expect(screen.getByRole("cell", { name: "酱油" })).toBeInTheDocument()
    expect(screen.queryByRole("cell", { name: "大米" })).toBeNull()
    await user.click(screen.getByRole("button", { name: "全部" }))
    await user.click(screen.getByRole("button", { name: "管理" }))
    await user.click(screen.getByRole("checkbox", { name: "选择大米" }))
    await user.click(screen.getByRole("checkbox", { name: "选择酱油" }))
    await user.click(within(screen.getByRole("toolbar", { name: "历史批量操作" })).getByRole("button", { name: "恢复" }))
    await waitFor(() => expect(batches).toEqual([{ ids: ["rice", "sauce"], action: "restore" }]))
    expect(await screen.findByText("已恢复到清单")).toBeInTheDocument()
    expect(rows.map((row) => ({ id: row.id, expiry_date: row.expiry_date, reminder_tiers: row.reminder_tiers, status: row.status }))).toEqual([
      { id: "rice", expiry_date: "2026-12-01", reminder_tiers: ["D30", "D5"], status: "in_stock" },
      { id: "sauce", expiry_date: "2026-10-01", reminder_tiers: [], status: "in_stock" },
    ])

    rows[0].status = "used_up"
    rows[1].status = "discarded"
    await user.click(screen.getByRole("button", { name: "刷新" }))
    await screen.findByRole("cell", { name: "大米" })
    await user.click(screen.getByRole("button", { name: "管理" }))
    await user.click(within(rowOf("酱油")).getByRole("button", { name: "永久删除" }))
    const cancelled = await screen.findByRole("dialog")
    expect(cancelled).toHaveTextContent("删除后永久无法恢复，不会留在历史。确定删除这 1 件吗？")
    await user.click(within(cancelled).getByRole("button", { name: "取消" }))
    expect(batches).toHaveLength(1)

    await user.click(screen.getByRole("checkbox", { name: "选择大米" }))
    await user.click(screen.getByRole("checkbox", { name: "选择酱油" }))
    await user.click(within(screen.getByRole("toolbar", { name: "历史批量操作" })).getByRole("button", { name: "永久删除" }))
    const dialog = await screen.findByRole("dialog")
    expect(dialog).toHaveTextContent("确定删除这 2 件吗？")
    await user.click(within(dialog).getByRole("button", { name: "删除" }))
    await waitFor(() => expect(batches[1]).toEqual({ ids: ["rice", "sauce"], action: "delete" }))
    await waitFor(() => expect(screen.queryByRole("cell", { name: "大米" })).toBeNull())
    expect(screen.queryByRole("cell", { name: "酱油" })).toBeNull()
  })

  it("keeps restore and permanent delete unavailable while offline", async () => {
    const user = userEvent.setup()
    const calls: string[] = []
    render(
      <ConfigProvider locale={zhCN} button={{ autoInsertSpace: false }}>
        <ItemScreen
          familyName="我家"
          inviteCode={null}
          inviteExpires={null}
          items={[item({ id: "rice", name: "大米", status: "used_up", expiry_date: "2026-12-01" })]}
          today="2026-11-15"
          loading={false}
          error={null}
          live={false}
          busy={false}
          online={false}
          onRefresh={async () => undefined}
          onCreate={async () => undefined}
          onUpdate={async () => null}
          onDelete={async () => undefined}
          onUsed={async () => undefined}
          onRestore={async () => { calls.push("restore") }}
          onDeleteHistory={async () => { calls.push("delete") }}
          onLogout={async () => undefined}
        />
      </ConfigProvider>,
    )
    await user.click(screen.getByRole("button", { name: "历史" }))
    expect(screen.getByRole("status")).toHaveTextContent("恢复网络后再修改")
    expect(screen.getByRole("button", { name: "恢复" })).toBeDisabled()
    expect(screen.getByRole("button", { name: "永久删除" })).toBeDisabled()
    await user.click(screen.getByRole("button", { name: "管理" }))
    expect(screen.queryByRole("checkbox")).toBeNull()
    expect(calls).toEqual([])
  })
})
