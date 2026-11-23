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
import type { PhotoLoad } from "../src/ui/PhotoView"

const MILK_PHOTO = "photo-milk-secret"
const RICE_PHOTO = "photo-rice-secret"

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
    location: "冰箱",
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

function jpeg(): Blob {
  return new Blob([Uint8Array.from([0xff, 0xd8, 0xff, 0xd9])], { type: "image/jpeg" })
}

type Calls = {
  photo: string[]
  update: number
  create: number
  delete: number
  used: number
  mark: string[]
  undo: string[]
  restore: string[][]
  historyDelete: string[][]
}

function calls(): Calls {
  return { photo: [], update: 0, create: 0, delete: 0, used: 0, mark: [], undo: [], restore: [], historyDelete: [] }
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

function renderScreen(items: Item[], load: PhotoLoad, log = calls(), online?: boolean) {
  return render(
    <ConfigProvider locale={zhCN} button={{ autoInsertSpace: false }}>
      <ItemScreen
        familyName="我家"
        inviteCode={null}
        inviteExpires={null}
        items={items}
        today="2026-11-15"
        loading={false}
        error={null}
        live={false}
        busy={false}
        online={online}
        onRefresh={async () => undefined}
        onCreate={async () => {
          log.create += 1
        }}
        onUpdate={async () => {
          log.update += 1
          return null
        }}
        onDelete={async () => {
          log.delete += 1
        }}
        onUsed={async () => {
          log.used += 1
        }}
        onMark={async (ids, action) => {
          log.mark.push(`${action}:${ids.join(",")}`)
          return { undo_token: "undo-1" }
        }}
        onUndo={async (token) => {
          log.undo.push(token)
          return { skipped: 0, restored: idsFrom(log.mark), message: null }
        }}
        onRestore={async (ids) => {
          log.restore.push(ids)
        }}
        onDeleteHistory={async (ids) => {
          log.historyDelete.push(ids)
        }}
        onLogout={async () => undefined}
        onLoadPhoto={async (id) => {
          log.photo.push(id)
          return load(id)
        }}
      />
    </ConfigProvider>,
  )
}

function idsFrom(mark: string[]): number {
  const last = mark[mark.length - 1] ?? ""
  const ids = last.split(":")[1] ?? ""
  return ids === "" ? 0 : ids.split(",").length
}

function expectReadOnly(dialog: HTMLElement) {
  for (const name of ["删除", "编辑", "已用完", "已处理", "恢复", "永久删除", "上传", "保存", "重试"]) {
    expect(within(dialog).queryByRole("button", { name })).toBeNull()
  }
  expect(dialog.querySelector('input[type="file"]')).toBeNull()
  expect(dialog.innerHTML).not.toContain("/api/")
  expect(dialog.innerHTML).not.toContain("Authorization")
  expect(dialog.innerHTML).not.toContain(MILK_PHOTO)
  expect(dialog.innerHTML).not.toContain(RICE_PHOTO)
}

describe("read-only photo view", () => {
  it("opens a stock photo and closes back to the same list without changing the item", async () => {
    const user = userEvent.setup()
    const log = calls()
    const milk = item({ id: "milk", name: "牛奶", status: "in_stock", expiry_date: "2026-11-20", photo_id: MILK_PHOTO })
    const salt = item({ id: "salt", name: "盐", status: "in_stock", expiry_date: "2026-12-01", photo_id: "   " })
    renderScreen([milk, salt], async () => jpeg(), log)
    expect(within(card("盐")).getByText("无照片")).toBeInTheDocument()
    expect(within(card("盐")).queryByRole("button", { name: "查看盐的照片" })).toBeNull()
    await user.click(within(card("盐")).getByText("无照片"))
    expect(screen.queryByRole("dialog")).toBeNull()
    expect(log.photo).toEqual([])

    await user.click(within(card("牛奶")).getByRole("button", { name: "查看牛奶的照片" }))
    const dialog = await screen.findByRole("dialog", { name: "查看照片" })
    const image = await within(dialog).findByRole("img", { name: "牛奶的照片" })
    expect(image.getAttribute("src")).toMatch(/^blob:/)
    expectReadOnly(dialog)
    expect(screen.getByRole("heading", { name: "牛奶", hidden: true })).toBeInTheDocument()
    expect(log).toMatchObject({ photo: [MILK_PHOTO], update: 0, create: 0, delete: 0, used: 0, mark: [], restore: [] })
    expect(document.body.innerHTML).not.toContain(MILK_PHOTO)
    expect(document.body.innerHTML).not.toContain("/api/v1/photos")
    expect(document.querySelector('input[type="file"]')).toBeNull()

    await user.click(within(dialog).getByRole("button", { name: "关闭" }))
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull())
    expect(screen.getByRole("heading", { name: "牛奶" })).toBeInTheDocument()
    expect(screen.queryByRole("region", { name: "编辑物品" })).toBeNull()
    expect(screen.queryByText("永久删除，不可恢复")).toBeNull()
    expect(log.update).toBe(0)
    expect(log.delete).toBe(0)
  })

  it("opens from the edit form and returns to that form", async () => {
    const user = userEvent.setup()
    const log = calls()
    const milk = item({ id: "milk", name: "牛奶", status: "in_stock", expiry_date: "2026-11-20", photo_id: MILK_PHOTO })
    renderScreen([milk], async () => jpeg(), log)
    await user.click(screen.getByRole("button", { name: "编辑" }))
    const form = screen.getByRole("region", { name: "编辑物品" })
    expect(form).toHaveTextContent("照片")
    expect(within(form).queryByText("无照片")).toBeNull()
    await user.click(within(form).getByRole("button", { name: "查看牛奶的照片" }))
    const dialog = await screen.findByRole("dialog", { name: "查看照片" })
    expect(await within(dialog).findByRole("img", { name: "牛奶的照片" })).toBeInTheDocument()
    expectReadOnly(dialog)
    await user.click(within(dialog).getByRole("button", { name: "关闭" }))
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull())
    expect(screen.getByRole("region", { name: "编辑物品" })).toBeInTheDocument()
    expect(screen.getByRole("heading", { name: "牛奶" })).toBeInTheDocument()
    expect(log).toMatchObject({ photo: [MILK_PHOTO], update: 0, create: 0, delete: 0 })
  })

  it("shows an empty photo on a new item and does not offer upload", async () => {
    const user = userEvent.setup()
    const log = calls()
    renderScreen([], async () => jpeg(), log)
    await user.click(screen.getByRole("button", { name: "新增物品" }))
    const form = screen.getByRole("region", { name: "新增物品" })
    expect(within(form).getByText("无照片")).toBeInTheDocument()
    expect(within(form).queryByRole("button", { name: /查看/ })).toBeNull()
    expect(form.querySelector('input[type="file"]')).toBeNull()
    expect(log.photo).toEqual([])
    expect(log.create).toBe(0)
  })

  it("keeps loading, failure, and missing photos distinct", async () => {
    const user = userEvent.setup()
    const log = calls()
    let release: (blob: Blob) => void = () => undefined
    let mode: "hold" | "fail" | "missing" | "ok" = "hold"
    const milk = item({ id: "milk", name: "牛奶", status: "in_stock", expiry_date: "2026-11-20", photo_id: MILK_PHOTO })
    renderScreen([milk], () => new Promise<Blob>((resolve, reject) => {
      release = resolve
      if (mode === "fail") reject(new TypeError("Failed to fetch"))
      if (mode === "missing") reject(new ApiError(404, "not_found", "未找到"))
      if (mode === "ok") resolve(jpeg())
    }), log)

    await user.click(screen.getByRole("button", { name: "查看牛奶的照片" }))
    const dialog = await screen.findByRole("dialog", { name: "查看照片" })
    expect(within(dialog).getByRole("status")).toHaveTextContent("正在加载照片")
    expect(within(dialog).queryByRole("img")).toBeNull()
    expectReadOnly(dialog)
    await waitFor(() => expect(log.photo).toEqual([MILK_PHOTO]))
    release(jpeg())
    expect(await within(dialog).findByRole("img", { name: "牛奶的照片" })).toHaveAttribute("src", expect.stringMatching(/^blob:/))

    mode = "fail"
    await user.click(within(dialog).getByRole("button", { name: "关闭" }))
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull())
    await user.click(screen.getByRole("button", { name: "查看牛奶的照片" }))
    const failed = await screen.findByRole("dialog", { name: "查看照片" })
    expect(await within(failed).findByRole("alert")).toHaveTextContent("照片加载失败")
    expect(within(failed).queryByText("照片已不存在")).toBeNull()
    expect(within(failed).queryByRole("img")).toBeNull()
    mode = "ok"
    await user.click(within(failed).getByRole("button", { name: "重新加载" }))
    expect(await within(failed).findByRole("img", { name: "牛奶的照片" })).toBeInTheDocument()

    mode = "missing"
    await user.click(within(failed).getByRole("button", { name: "关闭" }))
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull())
    await user.click(screen.getByRole("button", { name: "查看牛奶的照片" }))
    const missing = await screen.findByRole("dialog", { name: "查看照片" })
    expect(await within(missing).findByRole("alert")).toHaveTextContent("照片已不存在")
    expect(within(missing).queryByText("照片加载失败")).toBeNull()
    expect(within(missing).queryByRole("button", { name: "重新加载" })).toBeNull()
    expect(within(missing).queryByRole("img")).toBeNull()
    expectReadOnly(missing)
    expect(log.update).toBe(0)
    expect(log.delete).toBe(0)
    expect(log.used).toBe(0)
  })

  it("treats other request errors as load failures, including while offline", async () => {
    const user = userEvent.setup()
    const log = calls()
    const milk = item({ id: "milk", name: "牛奶", status: "in_stock", expiry_date: "2026-11-20", photo_id: MILK_PHOTO })
    renderScreen([milk], async () => {
      throw new ApiError(403, "not_in_family", "不在家庭中")
    }, log, false)
    expect(screen.getByRole("button", { name: "查看牛奶的照片" })).toBeEnabled()
    expect(screen.getByRole("button", { name: "已用完" })).toBeDisabled()
    expect(screen.getByRole("button", { name: "删除" })).toBeDisabled()
    await user.click(screen.getByRole("button", { name: "查看牛奶的照片" }))
    const dialog = await screen.findByRole("dialog", { name: "查看照片" })
    expect(await within(dialog).findByRole("alert")).toHaveTextContent("照片加载失败")
    expect(within(dialog).queryByText("照片已不存在")).toBeNull()
    expect(within(dialog).queryByText("不在家庭中")).toBeNull()
    expectReadOnly(dialog)
    expect(log).toMatchObject({ delete: 0, used: 0, mark: [], update: 0 })
  })

  it("opens a history photo and still restores only when restore is clicked", async () => {
    const user = userEvent.setup()
    const log = calls()
    const milk = item({ id: "milk", name: "牛奶", status: "in_stock", expiry_date: "2026-11-20", photo_id: MILK_PHOTO })
    const rice = item({
      id: "rice",
      name: "大米",
      status: "used_up",
      expiry_date: "2026-12-01",
      photo_id: RICE_PHOTO,
      status_changed_at: "2026-11-20T00:00:00Z",
    })
    const plain = item({
      id: "salt",
      name: "盐",
      status: "discarded",
      expiry_date: "2026-10-01",
      status_changed_at: "2026-11-01T00:00:00Z",
    })
    renderScreen([milk, rice, plain], async () => jpeg(), log)
    await user.click(screen.getByRole("button", { name: "历史" }))
    expect(within(historyRow("盐")).getByText("无照片")).toBeInTheDocument()
    expect(within(historyRow("盐")).queryByRole("button", { name: "查看盐的照片" })).toBeNull()
    await user.click(within(historyRow("大米")).getByRole("button", { name: "查看大米的照片" }))
    const dialog = await screen.findByRole("dialog", { name: "查看照片" })
    expect(await within(dialog).findByRole("img", { name: "大米的照片" })).toHaveAttribute("src", expect.stringMatching(/^blob:/))
    expectReadOnly(dialog)
    expect(log.restore).toEqual([])
    expect(log.historyDelete).toEqual([])
    await user.click(within(dialog).getByRole("button", { name: "关闭" }))
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull())
    expect(screen.getByRole("heading", { name: "历史" })).toBeInTheDocument()
    expect(historyRow("大米")).toBeInTheDocument()
    await user.click(within(historyRow("大米")).getByRole("button", { name: "恢复" }))
    expect(log.restore).toEqual([["rice"]])
    expect(log.historyDelete).toEqual([])
    expect(log.photo).toEqual([RICE_PHOTO])
    expect(log.mark).toEqual([])
    expect(document.body.innerHTML).not.toContain(RICE_PHOTO)
  })

  it("does not dismiss the undo bar or mark items when a photo is only viewed", async () => {
    const user = userEvent.setup()
    const log = calls()
    const milk = item({ id: "milk", name: "牛奶", status: "in_stock", expiry_date: "2026-11-20", photo_id: MILK_PHOTO })
    const sauce = item({ id: "sauce", name: "酱油", status: "in_stock", expiry_date: "2026-12-20" })
    renderScreen([milk, sauce], async () => jpeg(), log)
    await user.click(screen.getByRole("button", { name: "管理" }))
    expect(screen.getByText("已选 0 件")).toBeInTheDocument()
    await user.click(screen.getByRole("button", { name: "查看牛奶的照片" }))
    const preview = await screen.findByRole("dialog", { name: "查看照片" })
    await user.click(within(preview).getByRole("button", { name: "关闭" }))
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull())
    expect(screen.getByText("已选 0 件")).toBeInTheDocument()
    expect(screen.getByRole("checkbox", { name: "选择牛奶" })).not.toBeChecked()
    expect(log.mark).toEqual([])

    await user.click(screen.getByRole("checkbox", { name: "选择酱油" }))
    await user.click(within(screen.getByRole("toolbar", { name: "在库批量操作" })).getByRole("button", { name: "已用完" }))
    expect(await screen.findByText("已将 1 件标记为已用完，已移入历史")).toBeInTheDocument()
    await user.click(screen.getByRole("button", { name: "查看牛奶的照片" }))
    const again = await screen.findByRole("dialog", { name: "查看照片" })
    expect(document.querySelector(".undo-bar")).toHaveTextContent("已将 1 件标记为已用完，已移入历史")
    await user.click(within(again).getByRole("button", { name: "关闭" }))
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull())
    const bar = screen.getByText("已将 1 件标记为已用完，已移入历史").closest(".undo-bar")
    if (!bar) throw new Error("missing undo bar")
    await user.click(within(bar as HTMLElement).getByRole("button", { name: "撤销" }))
    await waitFor(() => expect(log.undo).toEqual(["undo-1"]))
    expect(log.mark).toEqual(["used_up:sauce"])
    expect(log.photo).toEqual([MILK_PHOTO, MILK_PHOTO])
    expect(log.delete).toBe(0)
    expect(log.restore).toEqual([])
  })

  it("loads the photo through the app client and leaves batch undo untouched", async () => {
    const user = userEvent.setup()
    const seen: string[] = []
    const milk = item({ id: "milk", name: "牛奶", status: "in_stock", expiry_date: "2026-11-20", photo_id: MILK_PHOTO, rev: 4 })
    const api = {
      requestCode: async () => undefined,
      login: async () => ({ user_id: "user-1", display_name: "用户" }),
      logout: async () => undefined,
      me: async () => me(),
      createFamily: async () => family(),
      joinFamily: async () => family(),
      currentFamily: async () => family(),
      createItem: async () => milk,
      patchItem: async () => {
        throw new Error("photo view must not patch")
      },
      batch: async () => {
        throw new Error("photo view must not batch")
      },
      undoBatch: async () => {
        throw new Error("photo view must not undo")
      },
      sync: async () => ({ reset: false, rev: 4, items: [milk], tombstones: [], family: family(), notify_time: "09:00" }),
      photo: async (id: string) => {
        seen.push(id)
        return jpeg()
      },
      webSocketUrl: () => "ws://127.0.0.1/api/v1/ws",
    } satisfies PantryClient
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={queryClient}>
        <App api={api} now={() => new Date("2026-11-15T04:00:00Z")} socketFactory={() => new QuietSocket()} />
      </QueryClientProvider>,
    )
    expect(await screen.findByRole("heading", { name: "牛奶", level: 3 })).toBeInTheDocument()
    await user.click(screen.getByRole("button", { name: "查看牛奶的照片" }))
    const dialog = await screen.findByRole("dialog", { name: "查看照片" })
    expect(await within(dialog).findByRole("img", { name: "牛奶的照片" })).toHaveAttribute("src", expect.stringMatching(/^blob:/))
    expect(seen).toEqual([MILK_PHOTO])
    expect(document.body.innerHTML).not.toContain(MILK_PHOTO)
    expect(document.body.innerHTML).not.toContain("/api/v1/photos")
    await user.click(within(dialog).getByRole("button", { name: "关闭" }))
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull())
    expect(screen.getByRole("heading", { name: "牛奶", level: 3 })).toBeInTheDocument()
    expect(screen.getByRole("button", { name: "编辑" })).toBeInTheDocument()
  })
})
