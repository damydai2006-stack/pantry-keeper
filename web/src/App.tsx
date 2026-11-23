import { Button, ConfigProvider } from "antd"
import zhCN from "antd/locale/zh_CN"
import { useQuery, useQueryClient } from "@tanstack/react-query"
import { useEffect, useMemo, useRef, useState } from "react"
import { createClient, type PantryClient } from "./api/client"
import { isApiError, isUnauthorized, userMessage } from "./api/types"
import { todayForView } from "./domain/today"
import { useFamilySync, type SocketLike } from "./sync/useFamilySync"
import { FamilyPanel } from "./ui/FamilyPanel"
import { ItemScreen } from "./ui/ItemScreen"
import { LoginPanel } from "./ui/LoginPanel"

const COOKIE_HINT = "登录没有完成：服务器接受了验证码，但浏览器没有保存会话。网页必须和 API 同源。本地 HTTP 需要把 PANTRY_COOKIE_SECURE 设为 false。"

export function App({
  api: external,
  now = () => new Date(),
  socketFactory,
}: {
  api?: PantryClient
  now?: () => Date
  socketFactory?: (url: string) => SocketLike
}) {
  const api = useMemo(() => external ?? createClient(), [external])
  const today = todayForView(now(), window.location.search)
  const queryClient = useQueryClient()
  const me = useQuery({ queryKey: ["me", api], queryFn: () => api.me(), retry: false, refetchOnWindowFocus: false })
  const hadSession = useRef(false)
  const [cookieNotice, setCookieNotice] = useState<string | null>(null)
  const [familyNotice, setFamilyNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  if (me.isSuccess) hadSession.current = true
  const familyId = me.data?.family_id ?? null
  const sync = useFamilySync(api, Boolean(familyId), socketFactory)

  useEffect(() => {
    if (!sync.revoked) return
    hadSession.current = true
    void queryClient.invalidateQueries({ queryKey: ["me", api] })
  }, [sync.revoked, api, queryClient])

  useEffect(() => {
    if (!sync.removed) return
    setFamilyNotice("你已被移出家庭")
    void queryClient.invalidateQueries({ queryKey: ["me", api] })
  }, [sync.removed, api, queryClient])

  async function requestCode(phone: string) {
    try {
      await api.requestCode(phone)
    } catch (error) {
      throw new Error(userMessage(error))
    }
  }

  async function login(phone: string, code: string) {
    setBusy(true)
    setCookieNotice(null)
    try {
      await api.login(phone, code)
      const result = await me.refetch()
      if (result.error && isUnauthorized(result.error)) setCookieNotice(COOKIE_HINT)
    } finally {
      setBusy(false)
    }
  }

  async function logout() {
    try {
      await api.logout()
    } catch (error) {
      if (!isUnauthorized(error)) throw new Error(userMessage(error))
    }
    hadSession.current = false
    setCookieNotice(null)
    setFamilyNotice(null)
    queryClient.clear()
  }

  async function createFamily(name: string) {
    setBusy(true)
    try {
      await api.createFamily(name)
      setFamilyNotice(null)
      await me.refetch()
    } catch (error) {
      if (isApiError(error) && error.code === "already_in_family") await me.refetch()
      throw new Error(userMessage(error))
    } finally {
      setBusy(false)
    }
  }

  async function joinFamily(code: string) {
    setBusy(true)
    try {
      await api.joinFamily(code)
      setFamilyNotice(null)
      await me.refetch()
    } catch (error) {
      throw new Error(userMessage(error))
    } finally {
      setBusy(false)
    }
  }

  async function createItem(body: Record<string, unknown>) {
    setBusy(true)
    try {
      await api.createItem(body)
      await sync.refresh().catch(() => undefined)
    } catch (error) {
      throw new Error(userMessage(error))
    } finally {
      setBusy(false)
    }
  }

  async function updateItem(id: string, baseRev: number, fields: Record<string, unknown>) {
    setBusy(true)
    try {
      const result = await api.patchItem(id, baseRev, fields)
      await sync.refresh().catch(() => undefined)
      return result.message
    } catch (error) {
      throw new Error(userMessage(error))
    } finally {
      setBusy(false)
    }
  }

  async function changeItems(ids: string[], action: string) {
    setBusy(true)
    try {
      const result = await api.batch(ids, action)
      await sync.refresh().catch(() => undefined)
      return result
    } catch (error) {
      const wrapped = new Error(userMessage(error))
      if (isApiError(error)) Object.assign(wrapped, { batchRejected: true })
      throw wrapped
    } finally {
      setBusy(false)
    }
  }

  async function undoItems(undoToken: string) {
    setBusy(true)
    try {
      const result = await api.undoBatch(undoToken)
      await sync.refresh().catch(() => undefined)
      return {
        skipped: result.skipped ?? 0,
        restored: result.restored ?? 0,
        message: result.message ?? null,
      }
    } catch (error) {
      throw new Error(userMessage(error))
    } finally {
      setBusy(false)
    }
  }

  let body
  if (me.isPending) body = <div role="status">正在加载</div>
  else if (me.isError && !isUnauthorized(me.error)) {
    body = (
      <div className="stack">
        <div role="alert">{userMessage(me.error)}</div>
        <Button onClick={() => void me.refetch()}>重试</Button>
      </div>
    )
  } else if (!me.data) {
    const expired = hadSession.current ? "登录已失效，请重新登录" : null
    body = <LoginPanel notice={cookieNotice ?? expired} busy={busy} onRequestCode={requestCode} onLogin={login} />
  } else if (!familyId || sync.removed) {
    body = (
      <FamilyPanel
        notice={sync.removed ? "你已被移出家庭" : familyNotice}
        busy={busy}
        onCreate={createFamily}
        onJoin={joinFamily}
        onLogout={logout}
      />
    )
  } else {
    body = (
      <ItemScreen
        familyName={sync.family?.name ?? "家庭清单"}
        inviteCode={sync.family?.invite_code ?? null}
        inviteExpires={sync.family?.invite_expires_at ?? null}
        items={sync.items}
        today={today}
        loading={sync.loading}
        error={sync.removed ? "你已被移出家庭" : sync.error}
        live={sync.live}
        busy={busy}
        onRefresh={async () => { await sync.refresh().catch(() => undefined) }}
        onCreate={createItem}
        onUpdate={updateItem}
        accountName={me.data.display_name}
        onDelete={async (id) => { await changeItems([id], "delete") }}
        onUsed={async (id) => { await changeItems([id], "used_up") }}
        onMark={(ids, action) => changeItems(ids, action)}
        onUndo={undoItems}
        onRestore={async (ids) => { await changeItems(ids, "restore") }}
        onDeleteHistory={async (ids) => { await changeItems(ids, "delete") }}
        onLogout={logout}
        onLoadPhoto={(photoId) => api.photo(photoId)}
      />
    )
  }

  return (
    <ConfigProvider
      locale={zhCN}
      button={{ autoInsertSpace: false }}
      theme={{ token: { colorPrimary: "#1e4a62", colorError: "#9b1c1c", borderRadius: 12, fontSize: 16 } }}
    >
      <main className={me.data && familyId && !sync.removed ? "app" : "app app-narrow"}>{body}</main>
    </ConfigProvider>
  )
}
