import { Button, Input, Typography } from "antd"
import { useState } from "react"
import { familyNameError, normalizeInvite } from "../domain/text"

const { Text } = Typography

export function FamilyPanel({
  notice,
  busy,
  onCreate,
  onJoin,
  onLogout,
}: {
  notice?: string | null
  busy: boolean
  onCreate: (name: string) => Promise<void>
  onJoin: (code: string) => Promise<void>
  onLogout: () => Promise<void>
}) {
  const [name, setName] = useState("")
  const [code, setCode] = useState("")
  const [error, setError] = useState<string | null>(null)

  async function create() {
    const message = familyNameError(name)
    if (message) {
      setError(message)
      return
    }
    setError(null)
    try {
      await onCreate(name.trim())
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "操作失败")
    }
  }

  async function join() {
    const invite = normalizeInvite(code)
    if (!invite) {
      setError("邀请码不正确")
      return
    }
    setError(null)
    try {
      await onJoin(invite)
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "操作失败")
    }
  }

  return (
    <section className="stack">
      <h1>加入家庭</h1>
      {notice ? <div role="alert">{notice}</div> : null}
      {error ? <div role="alert">{error}</div> : null}
      <div className="panel stack">
        <Text strong>创建家庭</Text>
        <div className="muted">你将成为管理员，并得到邀请码。</div>
        <Input aria-label="家庭名称" value={name} placeholder="2 到 20 个字" onChange={(event) => setName(event.target.value)} />
        <Button type="primary" disabled={busy} onClick={() => void create()}>创建家庭</Button>
      </div>
      <div className="panel stack">
        <Text strong>用邀请码加入</Text>
        <div className="muted">输入家人分享的 6 位邀请码。</div>
        <Input
          aria-label="邀请码"
          value={code}
          placeholder="6 位邀请码"
          onChange={(event) => setCode(event.target.value.toUpperCase())}
        />
        <Button disabled={busy} onClick={() => void join()}>加入家庭</Button>
      </div>
      <Button type="link" onClick={() => void onLogout().catch((caught: unknown) => setError(caught instanceof Error ? caught.message : "操作失败"))}>退出登录</Button>
    </section>
  )
}
