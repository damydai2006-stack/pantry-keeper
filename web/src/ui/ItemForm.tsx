import { Button, Checkbox, Input, InputNumber, Radio } from "antd"
import { useMemo, useState } from "react"
import { createBody, draftFromItem, emptyDraft, patchFields, previewDraft, CATEGORIES, LOCATIONS, SHELF_PRESETS, TIER_OPTIONS, type ItemDraft } from "../domain/draft"
import type { Item } from "../api/types"
import { takeCodePoints } from "../domain/text"
import { PhotoEntry } from "./PhotoView"

// The save baseline is the opened id and rev. A different item, a new item, or a newer
// revision has to start from that row; a list refresh alone does not change it.
function formIdentity(editing: Item | null): string {
  return editing ? `${editing.id}:${editing.rev}` : "new"
}

export function ItemForm({
  today,
  editing,
  busy,
  onCancel,
  onCreate,
  onUpdate,
  onViewPhoto,
}: {
  today: string
  editing: Item | null
  busy: boolean
  onCancel: () => void
  onCreate: (body: Record<string, unknown>) => Promise<void>
  onUpdate: (id: string, baseRev: number, fields: Record<string, unknown>) => Promise<string | null>
  onViewPhoto?: () => void
}) {
  const identity = formIdentity(editing)
  const [draft, setDraft] = useState<ItemDraft>(() => (editing ? draftFromItem(editing) : emptyDraft()))
  const [error, setError] = useState<string | null>(null)
  const [boundIdentity, setBoundIdentity] = useState(identity)
  if (boundIdentity !== identity) {
    setBoundIdentity(identity)
    setDraft(editing ? draftFromItem(editing) : emptyDraft())
    setError(null)
  }
  const preview = useMemo(() => previewDraft(draft, today, editing), [draft, today, editing])
  const customLocation = !LOCATIONS.includes(draft.location as (typeof LOCATIONS)[number]) && draft.location !== ""

  function update(patch: Partial<ItemDraft>) {
    setDraft((current) => ({ ...current, ...patch }))
    setError(null)
  }

  async function save() {
    setError(null)
    try {
      if (editing) {
        const fields = patchFields(editing, draft, today)
        if ("error" in fields) {
          setError(fields.error)
          return
        }
        if (fields.value) await onUpdate(editing.id, editing.rev, fields.value)
        onCancel()
        return
      }
      const body = createBody(draft, today)
      if ("error" in body) {
        setError(body.error)
        return
      }
      await onCreate(body.value)
      onCancel()
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "操作失败")
    }
  }

  return (
    <section className="panel form-grid" aria-label={editing ? "编辑物品" : "新增物品"}>
      <h2>{editing ? "编辑物品" : "新增物品"}</h2>
      <div>
        照片
        <PhotoEntry name={editing?.name ?? "物品"} photoId={editing?.photo_id} onOpen={() => onViewPhoto?.()} />
      </div>
      <label>
        名称
        <Input
          aria-label="物品名称"
          value={draft.name}
          placeholder="1 到 40 个字"
          onChange={(event) => update({ name: takeCodePoints(event.target.value, 40) })}
        />
      </label>
      <div>
        分类
        <Radio.Group value={draft.category} onChange={(event) => update({ category: event.target.value })}>
          {CATEGORIES.map(([value, label]) => <Radio.Button key={value} value={value}>{label}</Radio.Button>)}
        </Radio.Group>
      </div>
      <label>
        数量
        <InputNumber aria-label="数量" min={1} value={draft.quantity} onChange={(value) => update({ quantity: value ?? 1 })} />
      </label>
      <div>
        存放位置
        <div className="row">
          {LOCATIONS.map((location) => (
            <Button key={location} type={draft.location === location ? "primary" : "default"} onClick={() => update({ location })}>
              {location}
            </Button>
          ))}
          <Button type={customLocation ? "primary" : "default"} onClick={() => update({ location: customLocation ? draft.location : "" })}>
            自定义
          </Button>
        </div>
        {customLocation || draft.location === "" ? (
          <Input
            aria-label="自定义位置"
            value={draft.location}
            placeholder="最多 12 个字"
            onChange={(event) => update({ location: takeCodePoints(event.target.value, 12) })}
          />
        ) : null}
      </div>
      <div>
        保质期或到期日
        <div className="row">
          {SHELF_PRESETS.map((preset) => (
            <Button
              key={preset.label}
              type={draft.mode === "shelf" && draft.shelfValue === preset.value && draft.shelfUnit === preset.unit ? "primary" : "default"}
              onClick={() => update({ mode: "shelf", shelfValue: preset.value, shelfUnit: preset.unit })}
            >
              {preset.label}
            </Button>
          ))}
        </div>
        <Button type="link" onClick={() => update({ mode: draft.mode === "date" ? "shelf" : "date" })}>
          {draft.mode === "date" ? "改为按保质期计算" : "改为直接选择到期日"}
        </Button>
        {draft.mode === "date" ? (
          <Input aria-label="到期日" type="date" value={draft.expiryDate} onChange={(event) => update({ expiryDate: event.target.value })} />
        ) : null}
      </div>
      <label>
        生产日期（选填）
        <Input aria-label="生产日期" type="date" value={draft.productionDate} onChange={(event) => update({ productionDate: event.target.value })} />
      </label>
      {"origin" in preview ? <div className="muted">{preview.origin} {preview.expiry}</div> : null}
      <div>
        提醒档位，默认 30 天和 5 天，可全部取消。
        {TIER_OPTIONS.map(([value, label]) => (
          <label key={value} className="row">
            <Checkbox
              checked={draft.tiers.includes(value)}
              onChange={(event) => update({
                tiers: event.target.checked ? [...draft.tiers, value] : draft.tiers.filter((tier) => tier !== value),
              })}
            >
              {label}
            </Checkbox>
          </label>
        ))}
      </div>
      {draft.category === "medicine" ? (
        <div className="form-grid">
          <label>
            开封日期（选填）
            <Input aria-label="开封日期" type="date" value={draft.openedDate} onChange={(event) => update({ openedDate: event.target.value })} />
          </label>
          <label>
            开封后天数
            <Input aria-label="开封后天数" inputMode="numeric" value={draft.afterOpenDays} onChange={(event) => update({ afterOpenDays: event.target.value.replace(/\D/g, "") })} />
          </label>
          <div className="muted">药品同时填写开封信息时，清单按到期日和开封后期限中较早的一天计算。</div>
        </div>
      ) : null}
      <label>
        备注（选填）
        <Input.TextArea aria-label="备注" value={draft.note} onChange={(event) => update({ note: takeCodePoints(event.target.value, 200) })} />
      </label>
      {error ? <div role="alert">{error}</div> : null}
      <div className="row">
        <Button type="primary" disabled={busy} onClick={() => void save()}>{editing ? "保存" : "保存并入库"}</Button>
        <Button onClick={onCancel}>取消</Button>
      </div>
    </section>
  )
}
