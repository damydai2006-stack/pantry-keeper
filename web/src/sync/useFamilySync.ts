import { useCallback, useEffect, useRef, useState } from "react"
import type { PantryClient } from "../api/client"
import { isNotInFamily, isUnauthorized, userMessage } from "../api/types"
import type { Family, Item } from "../api/types"
import { applySync, parseWs, POLL_MS, type SyncState } from "../domain/sync"

export type SocketLike = {
  onopen: (() => void) | null
  onmessage: ((event: { data: string }) => void) | null
  onclose: (() => void) | null
  close: () => void
}

export type SyncSnapshot = {
  items: Item[]
  family: Family | null
  loading: boolean
  live: boolean
  error: string | null
  removed: boolean
  revoked: boolean
  refresh: () => Promise<void>
}

export function useFamilySync(
  api: PantryClient,
  enabled: boolean,
  socketFactory: (url: string) => SocketLike = (url) => new WebSocket(url) as unknown as SocketLike,
): SyncSnapshot {
  const [items, setItems] = useState<Item[]>([])
  const [family, setFamily] = useState<Family | null>(null)
  const [loading, setLoading] = useState(false)
  const [live, setLive] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [removed, setRemoved] = useState(false)
  const [revoked, setRevoked] = useState(false)
  const stateRef = useRef<SyncState>({ rev: 0, items: [] })
  const pullRef = useRef<() => Promise<void>>(async () => {})
  const factoryRef = useRef(socketFactory)
  factoryRef.current = socketFactory

  const pull = useCallback(async () => {
    const since = stateRef.current.rev
    let page = await api.sync(since)
    let used = since
    if (page.reset) {
      page = await api.sync(0)
      used = 0
    }
    const next = applySync(used === 0 ? { rev: 0, items: [] } : stateRef.current, page, used)
    stateRef.current = next
    setItems(next.items)
    if (page.family) setFamily(page.family)
    setError(null)
  }, [api])

  useEffect(() => {
    pullRef.current = pull
  }, [pull])

  const refresh = useCallback(async () => {
    try {
      await pull()
    } catch (caught) {
      if (isUnauthorized(caught)) setRevoked(true)
      else if (isNotInFamily(caught)) setRemoved(true)
      else setError(userMessage(caught))
      throw caught
    }
  }, [pull])

  useEffect(() => {
    if (!enabled) {
      stateRef.current = { rev: 0, items: [] }
      setItems([])
      setFamily(null)
      setLive(false)
      setError(null)
      setRemoved(false)
      setRevoked(false)
      setLoading(false)
      return
    }
    let closed = false
    let socket: SocketLike | null = null
    let timer: ReturnType<typeof setInterval> | null = null
    const stopPoll = () => {
      if (timer) clearInterval(timer)
      timer = null
    }
    const startPoll = () => {
      if (timer) return
      timer = setInterval(() => {
        if (document.visibilityState === "hidden") return
        void pullRef.current().catch((caught: unknown) => {
          if (closed) return
          if (isUnauthorized(caught)) setRevoked(true)
          else if (isNotInFamily(caught)) setRemoved(true)
          else setError(userMessage(caught))
        })
      }, POLL_MS)
    }
    setLoading(true)
    void pull()
      .catch((caught: unknown) => {
        if (closed) return
        if (isUnauthorized(caught)) setRevoked(true)
        else if (isNotInFamily(caught)) setRemoved(true)
        else setError(userMessage(caught))
      })
      .finally(() => {
        if (!closed) setLoading(false)
      })
    startPoll()
    try {
      socket = factoryRef.current(api.webSocketUrl())
    } catch {
      socket = null
    }
    const onVisible = () => {
      if (closed || document.visibilityState !== "visible") return
      void pullRef.current().catch((caught: unknown) => {
        if (closed) return
        if (isUnauthorized(caught)) setRevoked(true)
        else if (isNotInFamily(caught)) setRemoved(true)
        else setError(userMessage(caught))
      })
    }
    document.addEventListener("visibilitychange", onVisible)
    if (socket) {
      socket.onopen = () => {
        if (closed) return
        setLive(true)
        stopPoll()
      }
      socket.onmessage = (event) => {
        const message = parseWs(String(event.data))
        if (!message || closed) return
        if (message.t === "ready" || message.t === "changed") {
          void pullRef.current().catch((caught: unknown) => {
            if (closed) return
            if (isUnauthorized(caught)) setRevoked(true)
            else if (isNotInFamily(caught)) setRemoved(true)
            else setError(userMessage(caught))
          })
        } else if (message.t === "removed_from_family") {
          setRemoved(true)
          setItems([])
          stateRef.current = { rev: 0, items: [] }
        } else if (message.t === "session_revoked") {
          setRevoked(true)
        }
      }
      socket.onclose = () => {
        if (closed) return
        setLive(false)
        startPoll()
      }
    }
    return () => {
      closed = true
      stopPoll()
      document.removeEventListener("visibilitychange", onVisible)
      socket?.close()
    }
  }, [api, enabled, pull])

  return { items, family, loading, live, error, removed, revoked, refresh }
}
