import { Button, Checkbox, Input, Typography } from "antd"
import { useEffect, useRef, useState } from "react"
import { normalizePhone } from "../domain/text"

const { Paragraph, Text } = Typography

export function LoginPanel({
  notice,
  busy,
  onRequestCode,
  onLogin,
}: {
  notice?: string | null
  busy: boolean
  onRequestCode: (phone: string) => Promise<void>
  onLogin: (phone: string, code: string) => Promise<void>
}) {
  const [phone, setPhone] = useState("")
  const [code, setCode] = useState("")
  const [agreed, setAgreed] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [wait, setWait] = useState(0)
  const timerRef = useRef<number | null>(null)
  const normalized = normalizePhone(phone)
  useEffect(() => () => {
    if (timerRef.current != null) window.clearInterval(timerRef.current)
  }, [])
  const codeReady = /^\d{6}$/.test(code)

  async function requestCode() {
    if (!normalized) {
      setError("手机号不正确")
      return
    }
    setError(null)
    try {
      await onRequestCode(normalized)
      setWait(60)
      if (timerRef.current != null) window.clearInterval(timerRef.current)
      timerRef.current = window.setInterval(() => {
        setWait((left) => {
          if (left <= 1) {
            if (timerRef.current != null) window.clearInterval(timerRef.current)
            timerRef.current = null
            return 0
          }
          return left - 1
        })
      }, 1000)
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "操作失败")
    }
  }

  async function login() {
    if (!agreed) {
      setError("请先勾选用户协议和隐私政策")
      return
    }
    if (!normalized || !codeReady) {
      setError("请输入手机号和 6 位验证码")
      return
    }
    setError(null)
    try {
      await onLogin(normalized, code)
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "操作失败")
    }
  }

  return (
    <section className="panel stack">
      <div className="brand">
        <div className="mark">米</div>
        <div>
          <h1>大米保质期</h1>
          <div className="muted">食品、药品、调料，到期不再错过</div>
        </div>
      </div>
      <label>
        <Text strong>手机号</Text>
        <Input
          aria-label="手机号"
          inputMode="numeric"
          value={phone}
          placeholder="请输入 11 位手机号"
          onChange={(event) => setPhone(event.target.value.replace(/\D/g, "").slice(0, 11))}
        />
      </label>
      <label>
        <Text strong>验证码</Text>
        <div className="row">
          <Input
            aria-label="验证码"
            inputMode="numeric"
            value={code}
            placeholder="6 位验证码"
            onChange={(event) => setCode(event.target.value.replace(/\D/g, "").slice(0, 6))}
          />
          <Button disabled={!normalized || wait > 0 || busy} onClick={() => void requestCode()}>
            {wait > 0 ? `${wait} 秒后可重发` : "获取验证码"}
          </Button>
        </div>
      </label>
      <Checkbox checked={agreed} onChange={(event) => setAgreed(event.target.checked)}>
        我已阅读并同意《用户协议》和《隐私政策》
      </Checkbox>
      {notice ? <div role="alert">{notice}</div> : null}
      {error ? <div role="alert">{error}</div> : null}
      <Button type="primary" disabled={!agreed || !normalized || !codeReady || busy} onClick={() => void login()}>
        登录 / 注册
      </Button>
      <Paragraph className="muted" style={{ margin: 0 }}>
        验证码由服务器发送，页面不会显示验证码。未注册的手机号验证后将自动创建账号。
      </Paragraph>
    </section>
  )
}
