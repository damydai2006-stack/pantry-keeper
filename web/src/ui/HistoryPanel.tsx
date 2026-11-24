import { Button, Checkbox, Modal } from "antd"
import { useEffect, useMemo, useState } from "react"
import type { Item } from "../api/types"
import { PhotoEntry } from "./PhotoView"
import {
  HISTORY_SEGMENTS,
  filterHistory,
  handledOnLabel,
  historyEmptyLabel,
  historyStatusLabel,
  tierSummary,
  type HistorySegment,
} from "../domain/history"

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

export function HistoryPanel({
  items,
  busy,
  online,
  onRestore,
  onDelete,
  onViewPhoto,
}: {
  items: Item[]
  busy: boolean
  online?: boolean
  onRestore: (ids: string[]) => Promise<void>
  onDelete: (ids: string[]) => Promise<void>
  onViewPhoto: (item: Item) => void
}) {
  const connected = useOnlineFlag(online)
  const [segment, setSegment] = useState<HistorySegment>("all")
  const [managing, setManaging] = useState(false)
  const [selected, setSelected] = useState<string[]>([])
  const [pendingDelete, setPendingDelete] = useState<string[] | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const rows = useMemo(() => filterHistory(items, segment), [items, segment])
  const totalHistory = useMemo(() => filterHistory(items, "all").length, [items])
  const visibleIds = rows.map((item) => item.id).join("\n")

  useEffect(() => {
    const visible = new Set(visibleIds === "" ? [] : visibleIds.split("\n"))
    setSelected((current) => current.filter((id) => visible.has(id)))
  }, [visibleIds])

  function blockOffline(): boolean {
    if (connected) return false
    setNotice("恢复网络后再修改")
    return true
  }

  async function restore(ids: string[]) {
    if (blockOffline() || ids.length === 0 || busy) return
    try {
      await onRestore(ids)
      setManaging(false)
      setSelected([])
      setNotice("已恢复到清单")
    } catch (caught) {
      setNotice(caught instanceof Error ? caught.message : "操作失败")
    }
  }

  async function remove(ids: string[]) {
    if (blockOffline() || ids.length === 0 || busy) return
    try {
      await onDelete(ids)
      setManaging(false)
      setSelected([])
      setNotice(null)
    } catch (caught) {
      setNotice(caught instanceof Error ? caught.message : "操作失败")
    }
  }

  function askDelete(ids: string[]) {
    if (blockOffline() || ids.length === 0 || busy) return
    setPendingDelete(ids)
  }

  return (
    <section className="stack" aria-label="历史">
      <div className="row spread">
        <h2>历史</h2>
        <Button
          onClick={() => {
            if (!connected) {
              setNotice("恢复网络后再修改")
              return
            }
            setNotice(null)
            setManaging((current) => {
              if (current) setSelected([])
              return !current
            })
          }}
        >
          {managing ? "取消" : "管理"}
        </Button>
      </div>
      {!connected ? <div role="status">恢复网络后再修改</div> : null}
      {connected && notice ? <div role="status">{notice}</div> : null}
      <div className="row" role="group" aria-label="历史分段">
        {HISTORY_SEGMENTS.map((entry) => (
          <Button
            key={entry.id}
            type={segment === entry.id ? "primary" : "default"}
            aria-pressed={segment === entry.id}
            onClick={() => setSegment(entry.id)}
          >
            {entry.label}
          </Button>
        ))}
      </div>
      {managing ? (
        <div className="row" role="toolbar" aria-label="历史批量操作">
          <Button type="primary" disabled={!connected || busy || selected.length === 0} onClick={() => void restore(selected)}>
            恢复
          </Button>
          <Button danger disabled={!connected || busy || selected.length === 0} onClick={() => askDelete(selected)}>
            永久删除
          </Button>
        </div>
      ) : null}
      {rows.length === 0 ? <div role="status">{historyEmptyLabel(segment, totalHistory)}</div> : null}
      {rows.length > 0 ? (
        <div className="history-wrap">
          <table className="history-table">
            <thead>
              <tr>
                {managing ? <th>选择</th> : null}
                <th>名称</th>
                <th>状态</th>
                <th>原到期日</th>
                <th>处理日期</th>
                <th>提醒档位</th>
                <th>照片</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((item) => (
                <tr key={item.id}>
                  {managing ? (
                    <td>
                      <Checkbox
                        aria-label={`选择${item.name}`}
                        checked={selected.includes(item.id)}
                        disabled={!connected || busy}
                        onChange={(event) => {
                          const checked = event.target.checked
                          setSelected((current) => (
                            checked
                              ? (current.includes(item.id) ? current : [...current, item.id])
                              : current.filter((id) => id !== item.id)
                          ))
                        }}
                      />
                    </td>
                  ) : null}
                  <td>{item.name}</td>
                  <td>{historyStatusLabel(item.status)}</td>
                  <td>{item.expiry_date}</td>
                  <td>{handledOnLabel(item.status_changed_at)}</td>
                  <td>{tierSummary(item.reminder_tiers)}</td>
                  <td>
                    <PhotoEntry name={item.name} photoId={item.photo_id} onOpen={() => onViewPhoto(item)} />
                  </td>
                  <td>
                    <div className="history-actions">
                      <Button disabled={!connected || busy} onClick={() => void restore([item.id])}>恢复</Button>
                      <Button danger disabled={!connected || busy} onClick={() => askDelete([item.id])}>永久删除</Button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
      <Modal
        open={pendingDelete !== null}
        transitionName=""
        maskTransitionName=""
        title="确定删除？"
        okText="删除"
        cancelText="取消"
        okButtonProps={{ danger: true }}
        onCancel={() => setPendingDelete(null)}
        onOk={() => {
          const ids = pendingDelete
          setPendingDelete(null)
          if (ids) void remove(ids)
        }}
      >
        删除后永久无法恢复，不会留在历史。确定删除这 {pendingDelete?.length ?? 0} 件吗？
      </Modal>
    </section>
  )
}
