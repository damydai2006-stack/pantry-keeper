import { Button, Input, Modal } from "antd"
import { useMemo, useState } from "react"
import type { Item } from "../api/types"
import { basisLabel, categoryLabel, groupItems, itemDaysLabel, itemEffectiveExpiry, listBucket } from "../domain/labels"
import { ItemForm } from "./ItemForm"

export function ItemScreen({
  familyName,
  inviteCode,
  inviteExpires,
  items,
  today,
  loading,
  error,
  live,
  busy,
  onRefresh,
  onCreate,
  onUpdate,
  onDelete,
  onUsed,
  onLogout,
}: {
  familyName: string
  inviteCode: string | null
  inviteExpires: string | null
  items: Item[]
  today: string
  loading: boolean
  error: string | null
  live: boolean
  busy: boolean
  onRefresh: () => Promise<void>
  onCreate: (body: Record<string, unknown>) => Promise<void>
  onUpdate: (id: string, baseRev: number, fields: Record<string, unknown>) => Promise<string | null>
  onDelete: (id: string) => Promise<void>
  onUsed: (id: string) => Promise<void>
  onLogout: () => Promise<void>
}) {
  const [query, setQuery] = useState("")
  const [editing, setEditing] = useState<Item | null | "new">(null)
  const [pendingDelete, setPendingDelete] = useState<Item | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const groups = useMemo(() => groupItems(today, items, query), [today, items, query])
  const stock = items.filter((item) => item.status === "in_stock")

  return (
    <section className="stack">
      <div className="row spread">
        <div>
          <h1>{familyName || "家庭清单"}</h1>
          <div className="muted">{live ? "已连接，家庭内的改动会自动更新" : "实时连接不可用，每 5 秒从服务器刷新"}</div>
        </div>
        <div className="row">
          <Button onClick={() => void onRefresh()}>刷新</Button>
          <Button onClick={() => void onLogout().catch((caught: unknown) => setNotice(caught instanceof Error ? caught.message : "操作失败"))}>退出登录</Button>
        </div>
      </div>
      {inviteCode ? (
        <div className="panel">
          <div className="muted">邀请码，所有成员可复制。有效期至 {inviteExpires?.slice(0, 10) || "未知"}。</div>
          <strong aria-label="当前邀请码">{inviteCode}</strong>
          <Button
            type="link"
            onClick={() => void navigator.clipboard?.writeText(inviteCode).catch(() => setNotice("复制失败，请手动选择邀请码"))}
          >
            复制邀请码
          </Button>
        </div>
      ) : null}
      {error ? <div role="alert">{error}</div> : null}
      {notice ? <div role="status">{notice}</div> : null}
      <div className="row">
        <Input aria-label="搜索物品" value={query} placeholder="搜索名称" onChange={(event) => setQuery(event.target.value)} />
        <Button type="primary" onClick={() => setEditing("new")}>新增物品</Button>
      </div>
      {editing !== null ? (
        <ItemForm
          today={today}
          editing={editing === "new" ? null : editing}
          busy={busy}
          onCancel={() => setEditing(null)}
          onCreate={onCreate}
          onUpdate={async (id, rev, fields) => {
            const message = await onUpdate(id, rev, fields)
            if (message) setNotice(message)
            return message
          }}
        />
      ) : null}
      {loading ? <div role="status">正在加载</div> : null}
      {!loading && stock.length === 0 ? <div role="status">这个家庭还没有在库物品</div> : null}
      {!loading && stock.length > 0 && groups.length === 0 ? <div role="status">没有匹配的物品</div> : null}
      {groups.map((group) => (
        <section key={group.bucket}>
          <h2 className="group-title">{group.title} {group.items.length} 件</h2>
          <ul style={{ listStyle: "none", margin: 0, padding: 0 }}>
            {group.items.map((item) => {
              const effective = itemEffectiveExpiry(item)
              const bucket = listBucket(today, effective)
              return (
                <li key={item.id} className="item">
                  <div className="row spread">
                    <h3>{item.name}</h3>
                    <span className={`badge badge-${bucket === "EXPIRED" ? "expired" : bucket === "SOON" ? "soon" : bucket === "MONTH" ? "month" : "later"}`}>
                      {itemDaysLabel(today, item)}
                    </span>
                  </div>
                  <div className="muted">
                    {categoryLabel(item.category)} · 数量 {item.quantity}
                    {item.location ? ` · ${item.location}` : ""} · 到期 {item.expiry_date}
                    {effective !== item.expiry_date ? ` · 有效到期 ${effective}` : ""} · {basisLabel(item.expiry_basis)}
                  </div>
                  <div className="row">
                    <Button onClick={() => setEditing(item)}>编辑</Button>
                    <Button onClick={() => void onUsed(item.id).catch((caught: unknown) => setNotice(caught instanceof Error ? caught.message : "操作失败"))}>已用完</Button>
                    <Button danger onClick={() => setPendingDelete(item)}>删除</Button>
                  </div>
                </li>
              )
            })}
          </ul>
        </section>
      ))}
      <Modal
        open={pendingDelete !== null}
        transitionName=""
        maskTransitionName=""
        title="永久删除"
        okText="删除"
        cancelText="取消"
        okButtonProps={{ danger: true }}
        onCancel={() => setPendingDelete(null)}
        onOk={() => {
          const target = pendingDelete
          setPendingDelete(null)
          if (target) void onDelete(target.id).catch((caught: unknown) => setNotice(caught instanceof Error ? caught.message : "操作失败"))
        }}
      >
        永久删除，不可恢复。想留记录可以改用「已用完」。
      </Modal>
    </section>
  )
}
