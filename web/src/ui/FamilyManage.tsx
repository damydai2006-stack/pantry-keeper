import { Button, Input, Modal, Radio } from "antd"
import { useEffect, useRef, useState, type ReactNode } from "react"
import type { Family, Member } from "../api/types"
import { familyNameError } from "../domain/text"

type DialogKind = "rename" | "reset" | "remove" | "transfer" | "leave-member" | "leave-last" | "leave-transfer"

const ADMIN_DIALOGS = new Set<DialogKind>(["rename", "reset", "remove", "transfer", "leave-transfer"])

function roleLabel(role: string): string {
  return role === "admin" ? "管理员" : "成员"
}

function visibleName(member: Member): string {
  const name = member.display_name.trim()
  return name || "用户"
}

function joinedLabel(member: Member): string {
  const day = member.joined_at.slice(0, 10)
  return day || "未知"
}

function otherMembers(family: Family, viewerId: string): Member[] {
  return family.members
    .filter((member) => member.user_id !== viewerId)
    .slice()
    .sort((left, right) => left.joined_at < right.joined_at ? -1 : left.joined_at > right.joined_at ? 1 : 0)
}

function shareSentence(name: string, code: string): string {
  return `邀请你加入${name}，邀请码 ${code}`
}

async function writeClipboard(text: string, copyText?: (text: string) => Promise<void>): Promise<void> {
  if (copyText) {
    await copyText(text)
    return
  }
  const clipboard = navigator.clipboard
  if (!clipboard || typeof clipboard.writeText !== "function") throw new Error("clipboard")
  await clipboard.writeText(text)
}

export function FamilyManage({
  family,
  viewerId,
  loading,
  error,
  online,
  busy,
  onRefresh,
  onRename,
  onResetInvite,
  onTransfer,
  onRemove,
  onLeave,
  copyText,
}: {
  family: Family | null
  viewerId: string
  loading: boolean
  error: string | null
  online: boolean
  busy: boolean
  onRefresh: () => Promise<void>
  onRename: (name: string) => Promise<void>
  onResetInvite: () => Promise<void>
  onTransfer: (userId: string) => Promise<void>
  onRemove: (userId: string) => Promise<void>
  onLeave: () => Promise<void>
  copyText?: (text: string) => Promise<void>
}) {
  const [dialog, setDialog] = useState<DialogKind | null>(null)
  const [target, setTarget] = useState<Member | null>(null)
  const [successorId, setSuccessorId] = useState<string | null>(null)
  const [rename, setRename] = useState("")
  const [formError, setFormError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [shareDraft, setShareDraft] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const submittingRef = useRef(false)
  const admin = family?.role === "admin"
  const others = family ? otherMembers(family, viewerId) : []
  const locked = !online || busy || submitting

  useEffect(() => {
    setShareDraft(null)
  }, [family?.id, family?.invite_code])

  useEffect(() => {
    if (submittingRef.current || !dialog || !family) return
    if (ADMIN_DIALOGS.has(dialog) && family.role !== "admin") {
      setDialog(null)
      setNotice("你已经不是管理员")
      return
    }
    if ((dialog === "remove" || dialog === "transfer") && target && !family.members.some((member) => member.user_id === target.user_id)) {
      setDialog(null)
      setNotice("成员已不在家庭中")
    }
  }, [family, dialog, target])

  function closeDialog() {
    if (submittingRef.current) return
    setDialog(null)
    setFormError(null)
  }

  function begin(): boolean {
    if (submittingRef.current) return false
    if (!online) {
      setNotice("恢复网络后再修改")
      return false
    }
    submittingRef.current = true
    setSubmitting(true)
    setFormError(null)
    return true
  }

  function finish() {
    submittingRef.current = false
    setSubmitting(false)
  }

  async function copyCode() {
    const code = family?.invite_code
    if (!code) return
    try {
      await writeClipboard(code, copyText)
      setShareDraft(null)
      setNotice("邀请码已复制")
    } catch {
      setNotice("复制失败，请手动选择邀请码")
    }
  }

  async function shareCode() {
    const code = family?.invite_code
    if (!family || !code) return
    const sentence = shareSentence(family.name, code)
    try {
      await writeClipboard(sentence, copyText)
      setShareDraft(null)
      setNotice("分享文字已复制，可粘贴给家人")
    } catch {
      setShareDraft(sentence)
      setNotice("复制失败，请手动选择分享文字")
    }
  }

  async function saveName() {
    if (!begin()) return
    const message = familyNameError(rename)
    if (message) {
      setFormError(message)
      finish()
      return
    }
    try {
      await onRename(rename.trim())
      setDialog(null)
      setNotice("家庭名已保存")
    } catch (caught) {
      setFormError(caught instanceof Error ? caught.message : "操作失败")
    } finally {
      finish()
    }
  }

  async function confirmReset() {
    if (!begin()) return
    try {
      await onResetInvite()
      setDialog(null)
      setNotice("邀请码已重置，有效期重新计算为 7 天")
    } catch (caught) {
      setNotice(caught instanceof Error ? caught.message : "操作失败")
      setDialog(null)
    } finally {
      finish()
    }
  }

  async function confirmRemove() {
    if (!target || !begin()) return
    try {
      await onRemove(target.user_id)
      setDialog(null)
      setNotice(`已将${visibleName(target)}移出家庭`)
    } catch (caught) {
      setNotice(caught instanceof Error ? caught.message : "操作失败")
    } finally {
      finish()
    }
  }

  async function confirmTransfer() {
    if (!target || !begin()) return
    try {
      await onTransfer(target.user_id)
      setDialog(null)
      setNotice("已转让管理员，你现在是普通成员")
    } catch (caught) {
      setNotice(caught instanceof Error ? caught.message : "操作失败")
    } finally {
      finish()
    }
  }

  async function confirmLeave() {
    if (!begin()) return
    try {
      await onLeave()
      setDialog(null)
    } catch (caught) {
      setNotice(caught instanceof Error ? caught.message : "操作失败")
      setDialog(null)
    } finally {
      finish()
    }
  }

  async function confirmTransferLeave() {
    if (!successorId || !begin()) return
    let transferred = false
    try {
      await onTransfer(successorId)
      transferred = true
      await onLeave()
      setDialog(null)
    } catch (caught) {
      const message = caught instanceof Error ? caught.message : "操作失败"
      setNotice(transferred ? `管理员已转让，但退出失败。${message}` : message)
      if (transferred) setDialog(null)
    } finally {
      finish()
    }
  }

  function currentDialog(): {
    title: string
    okText: string
    danger: boolean
    okDisabled: boolean
    onOk: () => Promise<void>
    body: ReactNode
  } | null {
    if (!dialog || !family) return null
    if (dialog === "rename") {
      return {
        title: "家庭名称",
        okText: "保存",
        danger: false,
        okDisabled: false,
        onOk: saveName,
        body: (
          <div className="stack">
            <Input aria-label="家庭名称" value={rename} onChange={(event) => setRename(event.target.value)} />
            <div className="muted">2 到 20 个字。只有管理员可以修改。</div>
            {formError ? <div role="alert">{formError}</div> : null}
          </div>
        ),
      }
    }
    if (dialog === "reset") {
      return {
        title: "重置邀请码？",
        okText: "重置",
        danger: false,
        okDisabled: false,
        onOk: confirmReset,
        body: "重置后旧邀请码立即失效。",
      }
    }
    if (dialog === "remove" && target) {
      return {
        title: "移除成员",
        okText: "移除",
        danger: true,
        okDisabled: false,
        onOk: confirmRemove,
        body: `确定将${visibleName(target)}移出「${family.name}」？移出后对方将立即看不到该家庭的物品。`,
      }
    }
    if (dialog === "transfer" && target) {
      return {
        title: "设为管理员",
        okText: "确认转让",
        danger: false,
        okDisabled: false,
        onOk: confirmTransfer,
        body: `确定将${visibleName(target)}设为管理员？转让后你将变为普通成员。`,
      }
    }
    if (dialog === "leave-member") {
      return {
        title: "退出家庭？",
        okText: "退出",
        danger: true,
        okDisabled: false,
        onOk: confirmLeave,
        body: "退出后将不能再看到该家庭的物品。邀请码有效期内可再加入。",
      }
    }
    if (dialog === "leave-last") {
      return {
        title: "退出家庭？",
        okText: "退出",
        danger: true,
        okDisabled: false,
        onOk: confirmLeave,
        body: "你是最后一名成员。家庭将保留 30 天后删除。保留期内不可再加入，邀请码将失效。",
      }
    }
    if (dialog === "leave-transfer") {
      return {
        title: "须先转让管理员",
        okText: "转让并退出",
        danger: true,
        okDisabled: !successorId,
        onOk: confirmTransferLeave,
        body: (
          <div className="stack">
            <div>还有其他成员时不能直接退出。请选择接任的管理员，默认已选最早加入的人。转让后你将变为普通成员，并离开这个家庭。</div>
            <Radio.Group
              aria-label="接任管理员"
              value={successorId ?? undefined}
              onChange={(event) => setSuccessorId(String(event.target.value))}
            >
              <div className="stack">
                {others.map((person) => (
                  <Radio key={`${person.user_id}:${person.joined_at}`} value={person.user_id}>
                    {visibleName(person)} · 加入 {joinedLabel(person)}
                  </Radio>
                ))}
              </div>
            </Radio.Group>
            <div>退出后将不能再看到该家庭的物品。邀请码有效期内可再加入。</div>
          </div>
        ),
      }
    }
    return null
  }

  function openLeave() {
    if (!family || locked) return
    setNotice(null)
    if (admin && others.length > 0) {
      setSuccessorId(others[0]?.user_id ?? null)
      setDialog("leave-transfer")
      return
    }
    setDialog(others.length === 0 ? "leave-last" : "leave-member")
  }

  if (!family) {
    return (
      <section className="stack" aria-label="家庭">
        {loading ? <div role="status">正在加载</div> : null}
        {!loading && error ? <div role="alert">{error}</div> : null}
        {!loading && !error ? <div role="status">家庭信息暂时不可用</div> : null}
        <Button onClick={() => void onRefresh()}>重试</Button>
      </section>
    )
  }

  const expiry = family.invite_expires_at?.slice(0, 10) || ""
  const code = family.invite_code
  const activeDialog = currentDialog()

  return (
    <section className="stack" aria-label="家庭">
      {!online ? <div role="status">恢复网络后再修改</div> : null}
      {error ? <div role="alert">{error}</div> : null}
      {notice ? <div role="status">{notice}</div> : null}
      <div className="panel stack">
        <div className="row spread">
          <h2>{family.name}</h2>
          {admin ? (
            <Button
              aria-label="编辑家庭名"
              disabled={locked}
              onClick={() => {
                if (locked) return
                setRename(family.name)
                setFormError(null)
                setNotice(null)
                setDialog("rename")
              }}
            >
              编辑
            </Button>
          ) : null}
        </div>
        <div className="muted">你的身份：{roleLabel(family.role)}。家庭不能解散。</div>
      </div>
      <div className="panel stack family-card">
        <div className="muted">邀请码</div>
        {code ? (
          <strong className="invite-code" aria-label="当前邀请码">{code}</strong>
        ) : (
          <div role="status">当前没有可用邀请码</div>
        )}
        <div className="muted">
          {code
            ? `有效期至 ${expiry || "未知"}，所有成员可复制和分享。`
            : "家庭没有成员后邀请码失效。"}
        </div>
        <div className="row">
          <Button disabled={!code} onClick={() => void copyCode()}>复制邀请码</Button>
          <Button disabled={!code} onClick={() => void shareCode()}>分享邀请码</Button>
          {admin ? (
            <Button
              aria-label="重置邀请码"
              disabled={locked}
              onClick={() => {
                if (locked) return
                setNotice(null)
                setDialog("reset")
              }}
            >
              重置
            </Button>
          ) : null}
        </div>
        {shareDraft ? <p aria-label="分享文字">{shareDraft}</p> : null}
      </div>
      <div className="stack">
        <h2>成员</h2>
        <ul className="member-list" aria-label="家庭成员">
          {family.members.map((member) => {
            const self = member.user_id === viewerId
            const name = visibleName(member)
            return (
              <li key={`${member.user_id}:${member.joined_at}`} className="member-row stack">
                <strong>{name}{self ? "（我）" : ""}</strong>
                <div className="muted">{roleLabel(member.role)} · 加入 {joinedLabel(member)}</div>
                {admin && !self ? (
                  <div className="row">
                    <Button
                      aria-label={`设为管理员 ${name}`}
                      disabled={locked}
                      onClick={() => {
                        if (locked) return
                        setTarget(member)
                        setNotice(null)
                        setDialog("transfer")
                      }}
                    >
                      设为管理员
                    </Button>
                    <Button
                      danger
                      aria-label={`移除 ${name}`}
                      disabled={locked}
                      onClick={() => {
                        if (locked) return
                        setTarget(member)
                        setNotice(null)
                        setDialog("remove")
                      }}
                    >
                      移除
                    </Button>
                  </div>
                ) : null}
              </li>
            )
          })}
        </ul>
      </div>
      <Button danger disabled={locked} onClick={openLeave}>退出家庭</Button>
      {activeDialog ? (
        <Modal
          open
          transitionName=""
          maskTransitionName=""
          title={activeDialog.title}
          okText={activeDialog.okText}
          cancelText="取消"
          okButtonProps={{ danger: activeDialog.danger, disabled: locked || activeDialog.okDisabled }}
          cancelButtonProps={{ disabled: submitting }}
          onCancel={closeDialog}
          onOk={() => void activeDialog.onOk()}
        >
          {activeDialog.body}
        </Modal>
      ) : null}
    </section>
  )
}
