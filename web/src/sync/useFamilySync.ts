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
  adoptFamily: (family: Family | null) => void
}

export function useFamilySync(
  api: PantryClient,
  enabled: boolean,
  socketFactory: (url: string) => SocketLike = (url) => new WebSocket(url) as unknown as SocketLike,
  sessionKey: string | null = null,
): SyncSnapshot {
  const [items, setItems] = useState<Item[]>([])
  const [family, setFamily] = useState<Family | null>(null)
  const [loading, setLoading] = useState(false)
  const [live, setLive] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [removed, setRemoved] = useState(false)
  const [revoked, setRevoked] = useState(false)
  const stateRef = useRef<SyncState>({ rev: 0, items: [] })
  const epoch = useRef(0)
  const sealed = useRef(false)
  const pullRef = useRef<() => Promise<void>>(async () => {})
  const factoryRef = useRef(socketFactory)
  factoryRef.current = socketFactory

  const seal = useCallback(() => {
    epoch.current += 1
    sealed.current = true
    stateRef.current = { rev: 0, items: [] }
    setItems([])
    setFamily(null)
    setError(null)
    setRemoved(true)
  }, [])

  const pull = useCallback(async () => {
    if (sealed.current) return
    const ticket = epoch.current
    const since = stateRef.current.rev
    let page = await api.sync(since)
    if (ticket !== epoch.current || sealed.current) return
    let used = since
    if (page.reset) {
      page = await api.sync(0)
      if (ticket !== epoch.current || sealed.current) return
      used = 0
    }
    const next = applySync(used === 0 ? { rev: 0, items: [] } : stateRef.current, page, used)
    if (ticket !== epoch.current || sealed.current) return
    stateRef.current = next
    setItems(next.items)
    if (page.family) setFamily(page.family)
    setError(null)
  }, [api])

  useEffect(() => {
    pullRef.current = pull
  }, [pull])

  const noteFailure = useCallback((caught: unknown) => {
    if (isUnauthorized(caught)) setRevoked(true)
    else if (isNotInFamily(caught)) seal()
    else setError(userMessage(caught))
  }, [seal])

  const refresh = useCallback(async () => {
    try {
      await pull()
    } catch (caught) {
      noteFailure(caught)
      throw caught
    }
  }, [noteFailure, pull])

  const adoptFamily = useCallback((next: Family | null) => {
    setFamily(next)
    if (next) setError(null)
  }, [])

  useEffect(() => {
    epoch.current += 1
    sealed.current = false
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
    stateRef.current = { rev: 0, items: [] }
    setItems([])
    setFamily(null)
    setRemoved(false)
    setError(null)
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
          noteFailure(caught)
        })
      }, POLL_MS)
    }
    setLoading(true)
    void pull()
      .catch((caught: unknown) => {
        if (closed) return
        noteFailure(caught)
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
        noteFailure(caught)
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
            noteFailure(caught)
          })
        } else if (message.t === "removed_from_family") {
          seal()
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
  }, [api, enabled, noteFailure, pull, seal, sessionKey])

  return { items, family, loading, live, error, removed, revoked, refresh, adoptFamily }
}
