import { ConfigProvider } from "antd"
import zhCN from "antd/locale/zh_CN"
import { fireEvent, render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { describe, expect, it } from "vitest"
import type { Item } from "../src/api/types"
import { ItemForm } from "../src/ui/ItemForm"

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/

function tea(patch: Partial<Item> = {}): Item {
  return {
    id: "item-tea",
    name: "茶叶",
    category: "food",
    quantity: 1,
    location: "橱柜",
    note: null,
    expiry_date: "2027-09-09",
    production_date: "2026-09-09",
    shelf_life_value: 1,
    shelf_life_unit: "year",
    expiry_basis: "manual_duration_from_prod",
    opened_date: null,
    after_open_days: null,
    reminder_tiers: ["D30", "D5"],
    status: "in_stock",
    rev: 4,
    ...patch,
  }
}

function setProduction(value: string) {
  fireEvent.change(screen.getByLabelText("生产日期"), { target: { value } })
}

function renderForm(item: Item, today = "2026-11-22") {
  const updates: Record<string, unknown>[] = []
  let cancelled = 0
  render(
    <ConfigProvider locale={zhCN} button={{ autoInsertSpace: false }}>
      <ItemForm
        today={today}
        editing={item}
        busy={false}
        onCancel={() => { cancelled += 1 }}
        onCreate={async () => { throw new Error("create should not run") }}
        onUpdate={async (_id, _rev, fields) => {
          updates.push(fields)
          return null
        }}
      />
    </ConfigProvider>,
  )
  return {
    updates,
    cancelled: () => cancelled,
  }
}

describe("item form expiry editing", () => {
  it("reopens a shelf-life item in shelf mode and skips an unchanged save", async () => {
    const user = userEvent.setup()
    const form = renderForm(tea())
    expect(screen.getByRole("button", { name: "改为直接选择到期日" })).toBeInTheDocument()
    expect(screen.getByRole("button", { name: "1 年" })).toHaveClass("ant-btn-primary")
    expect(screen.getByText(/从生产日期 2026-09-09 起算/)).toHaveTextContent("2027-09-09")
    await user.click(screen.getByRole("button", { name: "保存" }))
    expect(form.updates).toEqual([])
    expect(form.cancelled()).toBe(1)
  })

  it("patches a renamed shelf item without touching the date basis or expiry", async () => {
    const user = userEvent.setup()
    const form = renderForm(tea())
    await user.type(screen.getByLabelText("物品名称"), "甲")
    await user.click(screen.getByRole("button", { name: "保存" }))
    expect(form.updates).toEqual([{ name: "茶叶甲" }])
    expect(form.updates[0]).not.toHaveProperty("expiry_basis")
    expect(form.updates[0]).not.toHaveProperty("expiry_date")
  })

  it("drops a stale invalid-date error once the draft becomes the legal 2026-09-09 shelf preview", async () => {
    const user = userEvent.setup()
    // The slash lives only in this client fixture so the form can reject it.
    // It is not a claim about a stored production date.
    const form = renderForm(tea({ production_date: "2026/09/09" }))
    expect(screen.queryByText(/按此计算/)).toBeNull()
    await user.click(screen.getByRole("button", { name: "保存" }))
    expect(await screen.findByRole("alert")).toHaveTextContent("日期不正确")
    expect(form.updates).toEqual([])
    expect(screen.queryByText(/按此计算/)).toBeNull()
    await user.click(screen.getByRole("button", { name: "1 年" }))
    setProduction("2026-09-09")
    expect(screen.queryByRole("alert")).toBeNull()
    expect(screen.getByText(/从生产日期 2026-09-09 起算/)).toHaveTextContent("2027-09-09")
    await user.click(screen.getByRole("button", { name: "保存" }))
    expect(form.updates).toEqual([{ production_date: "2026-09-09" }])
    expect(form.updates[0].production_date).toMatch(ISO_DATE)
    expect(form.updates[0]).not.toHaveProperty("expiry_basis")
  })

  it("does not shift a today-anchored shelf item when opened on a later day", async () => {
    const user = userEvent.setup()
    const form = renderForm(tea({
      expiry_basis: "manual_duration_from_today",
      production_date: null,
      expiry_date: "2027-09-09",
    }), "2026-11-22")
    expect(screen.getByRole("button", { name: "改为直接选择到期日" })).toBeInTheDocument()
    expect(screen.getByText(/2027-09-09/)).toBeInTheDocument()
    expect(screen.queryByText(/2027-11-22/)).toBeNull()
    await user.click(screen.getByRole("button", { name: "保存" }))
    expect(form.updates).toEqual([])
  })

  it("still lets a typed expiry switch to a one-year shelf life", async () => {
    const user = userEvent.setup()
    const form = renderForm(tea({
      expiry_basis: "manual_date",
      shelf_life_value: null,
      shelf_life_unit: null,
      production_date: "2026-09-09",
      expiry_date: "2027-01-01",
    }))
    expect(screen.getByRole("button", { name: "改为按保质期计算" })).toBeInTheDocument()
    expect(screen.getByLabelText("到期日")).toHaveValue("2027-01-01")
    await user.click(screen.getByRole("button", { name: "1 年" }))
    expect(screen.getByText(/从生产日期 2026-09-09 起算/)).toHaveTextContent("2027-09-09")
    await user.click(screen.getByRole("button", { name: "保存" }))
    expect(form.updates).toEqual([{
      expiry_date: "2027-09-09",
      shelf_life_value: 1,
      shelf_life_unit: "year",
      expiry_basis: "manual_duration_from_prod",
    }])
    expect(form.updates[0].expiry_date).toMatch(ISO_DATE)
  })
})
