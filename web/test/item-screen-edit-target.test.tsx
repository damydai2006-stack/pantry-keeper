import { ConfigProvider } from "antd"
import zhCN from "antd/locale/zh_CN"
import { render, screen, within } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { describe, expect, it } from "vitest"
import type { Item } from "../src/api/types"
import { ItemScreen } from "../src/ui/ItemScreen"

type Save = { id: string; rev: number; fields: Record<string, unknown> }

function row(patch: Partial<Item> & Pick<Item, "id" | "name" | "rev" | "expiry_date" | "location">): Item {
  return {
    category: "food",
    quantity: 1,
    note: null,
    production_date: null,
    shelf_life_value: null,
    shelf_life_unit: null,
    expiry_basis: "manual_date",
    opened_date: null,
    after_open_days: null,
    reminder_tiers: ["D30", "D5"],
    status: "in_stock",
    ...patch,
  }
}

const itemA = row({ id: "item-a", rev: 3, name: "物品甲", expiry_date: "2027-01-01", location: "冰箱" })
const itemB = row({ id: "item-b", rev: 8, name: "物品乙", expiry_date: "2028-06-15", location: "冷冻" })

function renderScreen(items: Item[]) {
  const saves: Save[] = []
  const creates: Record<string, unknown>[] = []
  const view = render(
    <ConfigProvider locale={zhCN} button={{ autoInsertSpace: false }}>
      <ItemScreen
        familyName="测试家庭"
        inviteCode={null}
        inviteExpires={null}
        items={items}
        today="2026-11-22"
        loading={false}
        error={null}
        live={false}
        busy={false}
        onRefresh={async () => undefined}
        onCreate={async (body) => { creates.push(body) }}
        onUpdate={async (id, rev, fields) => {
          saves.push({ id, rev, fields })
          return null
        }}
        onDelete={async () => undefined}
        onUsed={async () => undefined}
        onLogout={async () => undefined}
      />
    </ConfigProvider>,
  )
  return {
    saves,
    creates,
    rerender(next: Item[]) {
      view.rerender(
        <ConfigProvider locale={zhCN} button={{ autoInsertSpace: false }}>
          <ItemScreen
            familyName="测试家庭"
            inviteCode={null}
            inviteExpires={null}
            items={next}
            today="2026-11-22"
            loading={false}
            error={null}
            live={false}
            busy={false}
            onRefresh={async () => undefined}
            onCreate={async (body) => { creates.push(body) }}
            onUpdate={async (id, rev, fields) => {
              saves.push({ id, rev, fields })
              return null
            }}
            onDelete={async () => undefined}
            onUsed={async () => undefined}
            onLogout={async () => undefined}
          />
        </ConfigProvider>,
      )
    },
  }
}

function editButton(name: string) {
  const card = screen.getByRole("heading", { name }).closest("li")
  if (!card) throw new Error(`missing card for ${name}`)
  return within(card).getByRole("button", { name: "编辑" })
}

describe("item screen edit target", () => {
  it("saves item B without writing item A's open draft onto it", async () => {
    const user = userEvent.setup()
    const screenApi = renderScreen([itemA, itemB])
    await user.click(editButton("物品甲"))
    await user.type(screen.getByLabelText("物品名称"), "改")
    expect(screen.getByLabelText("物品名称")).toHaveValue("物品甲改")
    await user.click(editButton("物品乙"))
    await user.click(screen.getByRole("button", { name: "保存" }))
    expect(screenApi.saves).toEqual([])
    expect(screenApi.saves).not.toContainEqual({
      id: "item-b",
      rev: 8,
      fields: { name: "物品甲改", location: "冰箱", expiry_date: "2027-01-01" },
    })
  })

  it("submits only item B's own name change after leaving item A's draft", async () => {
    const user = userEvent.setup()
    const screenApi = renderScreen([itemA, itemB])
    await user.click(editButton("物品甲"))
    await user.type(screen.getByLabelText("物品名称"), "改")
    await user.click(editButton("物品乙"))
    expect(screen.getByLabelText("物品名称")).toHaveValue("物品乙")
    await user.type(screen.getByLabelText("物品名称"), "改")
    await user.click(screen.getByRole("button", { name: "保存" }))
    expect(screenApi.saves).toEqual([
      { id: "item-b", rev: 8, fields: { name: "物品乙改" } },
    ])
  })

  it("drops item A's draft when the open form switches to a new item", async () => {
    const user = userEvent.setup()
    const screenApi = renderScreen([itemA, itemB])
    await user.click(editButton("物品甲"))
    await user.type(screen.getByLabelText("物品名称"), "改")
    await user.click(screen.getByRole("button", { name: "新增物品" }))
    expect(screen.getByLabelText("物品名称")).toHaveValue("")
    await user.click(screen.getByRole("button", { name: "保存并入库" }))
    expect(screenApi.creates).toEqual([])
    expect(await screen.findByRole("alert")).toHaveTextContent("物品名需要 1 到 40 个字")
  })

  it("reloads the same item when its open revision changes", async () => {
    const user = userEvent.setup()
    const screenApi = renderScreen([itemA, itemB])
    await user.click(editButton("物品甲"))
    await user.type(screen.getByLabelText("物品名称"), "改")
    const revised = { ...itemA, rev: 8, location: "冷冻" }
    screenApi.rerender([revised, itemB])
    await user.click(editButton("物品甲"))
    expect(screen.getByLabelText("物品名称")).toHaveValue("物品甲")
    await user.click(screen.getByRole("button", { name: "保存" }))
    expect(screenApi.saves).toEqual([])
  })

  it("keeps the draft for the revision that was opened when only the list refreshes", async () => {
    const user = userEvent.setup()
    const screenApi = renderScreen([itemA, itemB])
    await user.click(editButton("物品甲"))
    await user.type(screen.getByLabelText("物品名称"), "改")
    screenApi.rerender([{ ...itemA, rev: 8, location: "冷冻" }, itemB])
    expect(screen.getByLabelText("物品名称")).toHaveValue("物品甲改")
    await user.click(screen.getByRole("button", { name: "保存" }))
    expect(screenApi.saves).toEqual([
      { id: "item-a", rev: 3, fields: { name: "物品甲改" } },
    ])
  })

  it("clears a save error from item A when the form moves to item B", async () => {
    const user = userEvent.setup()
    // Slash production is only this client fixture, so save can fail before the switch.
    const stained = { ...itemA, production_date: "2026/09/09" }
    renderScreen([stained, itemB])
    await user.click(editButton("物品甲"))
    await user.click(screen.getByRole("button", { name: "保存" }))
    expect(await screen.findByRole("alert")).toHaveTextContent("日期不正确")
    await user.click(editButton("物品乙"))
    expect(screen.queryByRole("alert")).toBeNull()
    expect(screen.getByLabelText("物品名称")).toHaveValue("物品乙")
    expect(screen.getByLabelText("到期日")).toHaveValue("2028-06-15")
  })
})
