import { Button, Checkbox, Input, Modal } from "antd"
import { useEffect, useMemo, useRef, useState } from "react"
import type { Item } from "../api/types"
import { basisLabel, categoryLabel, groupItems, itemDaysLabel, itemEffectiveExpiry, listBucket } from "../domain/labels"
import { HistoryPanel } from "./HistoryPanel"
import { ItemForm } from "./ItemForm"

type StockAction = "used_up" | "discarded"

const UNDO_SECONDS = 5
const UNDO_TICK_MS = 1000

type UndoOffer = {
  token: string
  action: StockAction
  count: number
  seconds: number
  startedAt: number
}

type UndoOutcome = {
  skipped: number
  restored: number
  message?: string | null
}

function useOnlineFlag(forced: boolean | undefined): boolean {
  const [online, setOnline] = useState(() => forced ?? navigator.onLine)
  useEffect(() => {
    if (forced !== undefined) {
      setOnline(forced)
      return
    }
    const update = () => setOnline(navigator.onLine)
    window.addEventListener("online", update)
    window.addEventListener("offline", update)
    update()
    return () => {
      window.removeEventListener("online", update)
      window.removeEventListener("offline", update)
    }
  }, [forced])
  return online
}

function markedNotice(action: StockAction, count: number): string {
  const verb = action === "discarded" ? "已处理" : "已用完"
  return `已将 ${count} 件标记为${verb}，已移入历史`
}

function atomicNotice(caught: unknown): string {
  const message = caught instanceof Error && caught.message ? caught.message : "操作失败"
  return `这批没有改动，物品仍在库：${message}`
}

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
  onMark = async () => undefined,
  onUndo = async () => ({ skipped: 0, restored: 0, message: null }),
  onRestore = async () => undefined,
  onDeleteHistory = async () => undefined,
  onLogout,
  accountName = "",
  online,
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
  onMark?: (ids: string[], action: StockAction) => Promise<{ undo_token?: string | null } | void>
  onUndo?: (undoToken: string) => Promise<UndoOutcome>
  onRestore?: (ids: string[]) => Promise<void>
  onDeleteHistory?: (ids: string[]) => Promise<void>
  onLogout: () => Promise<void>
  accountName?: string
  online?: boolean
}) {
  const connected = useOnlineFlag(online)
  const [query, setQuery] = useState("")
  const [view, setView] = useState<"list" | "history">("list")
  const [editing, setEditing] = useState<Item | null | "new">(null)
  const [pendingDelete, setPendingDelete] = useState<Item | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [managing, setManaging] = useState(false)
  const [selected, setSelected] = useState<string[]>([])
  const [listNotice, setListNotice] = useState<string | null>(null)
  const [undoOffer, setUndoOffer] = useState<UndoOffer | null>(null)
  const [undoing, setUndoing] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const submittingRef = useRef(false)
  const undoingRef = useRef(false)
  const undoSerial = useRef(0)
  const groups = useMemo(() => groupItems(today, items, query), [today, items, query])
  const stock = items.filter((item) => item.status === "in_stock")
  const visibleIds = groups.flatMap((group) => group.items.map((item) => item.id)).join("\n")
  const visible = visibleIds === "" ? [] : visibleIds.split("\n")
  const allSelected = visible.length > 0 && visible.every((id) => selected.includes(id))
  const markLocked = !connected || busy || submitting || selected.length === 0

  useEffect(() => {
    const allowed = new Set(visibleIds === "" ? [] : visibleIds.split("\n"))
    setSelected((current) => {
      const next = current.filter((id) => allowed.has(id))
      return next.length === current.length ? current : next
    })
  }, [visibleIds])

  useEffect(() => {
    if (!undoOffer) return
    const token = undoOffer.token
    const startedAt = undoOffer.startedAt
    const timer = window.setInterval(() => {
      setUndoOffer((current) => {
        if (!current || current.token !== token || current.startedAt !== startedAt) return current
        if (current.seconds <= 1) return null
        return { ...current, seconds: current.seconds - 1 }
      })
    }, UNDO_TICK_MS)
    return () => window.clearInterval(timer)
  }, [undoOffer?.token, undoOffer?.startedAt])

  function leaveList() {
    setManaging(false)
    setSelected([])
    setView("history")
  }

  function toggleManage() {
    if (!connected && !managing) return
    setListNotice(null)
    if (managing) {
      setManaging(false)
      setSelected([])
      return
    }
    setManaging(true)
  }

  function toggleChecked(id: string, checked: boolean) {
    setSelected((current) => (
      checked ? (current.includes(id) ? current : [...current, id]) : current.filter((itemId) => itemId !== id)
    ))
  }

  function toggleAll() {
    if (!connected || busy || submitting || visible.length === 0) return
    setSelected(allSelected ? [] : visible)
  }

  async function mark(action: StockAction) {
    if (!connected || busy || submittingRef.current) return
    const ids = selected.slice()
    if (ids.length === 0) return
    submittingRef.current = true
    setSubmitting(true)
    setListNotice(null)
    try {
      const result = await onMark(ids, action)
      if (editing !== null && editing !== "new" && ids.includes(editing.id)) setEditing(null)
      setManaging(false)
      setSelected([])
      const token = result && typeof result.undo_token === "string" && result.undo_token !== "" ? result.undo_token : null
      if (token) {
        undoingRef.current = false
        setUndoing(false)
        setListNotice(null)
        undoSerial.current += 1
        setUndoOffer({ token, action, count: ids.length, seconds: UNDO_SECONDS, startedAt: undoSerial.current })
      } else {
        setUndoOffer(null)
        setListNotice(markedNotice(action, ids.length))
      }
    } catch (caught) {
      setListNotice(atomicNotice(caught))
    } finally {
      submittingRef.current = false
      setSubmitting(false)
    }
  }

  async function undoMarked() {
    const current = undoOffer
    if (!current || undoingRef.current || !connected) return
    undoingRef.current = true
    setUndoing(true)
    try {
      const result = await onUndo(current.token)
      setUndoOffer((offer) => offer?.token === current.token ? null : offer)
      undoingRef.current = false
      setUndoing(false)
      if (result.skipped > 0) {
        const message = result.message?.trim()
        setListNotice(message || `${result.skipped} 件已被他人修改，未撤销`)
      } else {
        setListNotice(null)
      }
    } catch (caught) {
      undoingRef.current = false
      setUndoing(false)
      const message = caught instanceof Error && caught.message ? caught.message : "操作失败"
      setListNotice(message)
      if (message.includes("超过时限")) {
        setUndoOffer((offer) => offer?.token === current.token ? null : offer)
      }
    }
  }

  const undoLocked = !connected || undoing || busy || submitting

  return (
    <section className="shell">
      <aside className="side">
        <div className="brand-name">大米保质期</div>
        <nav className="side-nav" aria-label="网页导航">
          <Button type={view === "list" ? "primary" : "default"} aria-current={view === "list" ? "page" : undefined} onClick={() => setView("list")}>
            清单
          </Button>
          <Button type={view === "history" ? "primary" : "default"} aria-current={view === "history" ? "page" : undefined} onClick={leaveList}>
            历史
          </Button>
        </nav>
        {accountName ? <div className="muted">{accountName}</div> : null}
      </aside>
      <div className="stack">
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
      {view === "history" ? (
        <HistoryPanel
          items={items}
          busy={busy}
          online={online}
          onRestore={onRestore}
          onDelete={onDeleteHistory}
        />
      ) : null}
      {view === "list" ? (
      <>
      <div className="row">
        <Input aria-label="搜索物品" value={query} placeholder="搜索名称" onChange={(event) => setQuery(event.target.value)} />
        <Button type="primary" onClick={() => setEditing("new")}>新增物品</Button>
        <Button disabled={!connected && !managing} onClick={toggleManage}>{managing ? "取消" : "管理"}</Button>
      </div>
      {!connected ? <div role="status">恢复网络后再修改</div> : null}
      {connected && listNotice ? <div role="status">{listNotice}</div> : null}
      {managing ? (
        <div className="row stock-batch" role="toolbar" aria-label="在库批量操作">
          <span>已选 {selected.length} 件</span>
          <Button disabled={!connected || busy || submitting || visible.length === 0} onClick={toggleAll}>
            {allSelected ? "取消全选" : "全选"}
          </Button>
          <Button type="primary" disabled={markLocked} onClick={() => void mark("used_up")}>已用完</Button>
          <Button disabled={markLocked} onClick={() => void mark("discarded")}>已处理</Button>
        </div>
      ) : null}
      {editing !== null ? (
        <ItemForm
          key={editing === "new" ? "new" : `${editing.id}:${editing.rev}`}
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
                    <div className="row">
                      {managing ? (
                        <span className="stock-check">
                          <Checkbox
                            aria-label={`选择${item.name}`}
                            checked={selected.includes(item.id)}
                            disabled={!connected || busy || submitting}
                            onChange={(event) => toggleChecked(item.id, event.target.checked)}
                          />
                        </span>
                      ) : null}
                      <h3>{item.name}</h3>
                    </div>
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
                    <Button
                      disabled={!connected}
                      onClick={() => void onUsed(item.id).catch((caught: unknown) => setNotice(caught instanceof Error ? caught.message : "操作失败"))}
                    >
                      已用完
                    </Button>
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
      {undoOffer ? (
        <div className="undo-bar" style={{ backgroundColor: "#1a1d1a", color: "#ffffff" }}>
          <div role="status" className="undo-bar-text">{markedNotice(undoOffer.action, undoOffer.count)}</div>
          <span className="undo-bar-time" aria-hidden="true">{undoOffer.seconds} 秒</span>
          <Button
            type="text"
            className="undo-bar-action"
            style={{ color: "#ffffff", minHeight: 48, minWidth: 48, fontWeight: 700 }}
            disabled={undoLocked}
            onClick={() => void undoMarked()}
          >
            撤销
          </Button>
        </div>
      ) : null}
      </>
      ) : null}
      </div>
    </section>
  )
}
