import { act, renderHook } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"
import type { PantryClient } from "../src/api/client"
import type { Item, SyncPage } from "../src/api/types"
import { useFamilySync, type SocketLike } from "../src/sync/useFamilySync"

const empty = (): SyncPage => ({
  reset: false,
  rev: 1,
  items: [],
  tombstones: [],
  family: null,
  notify_time: null,
})

afterEach(() => {
  vi.useRealTimers()
})

describe("sync fallback timer", () => {
  it("requests another sync 5 seconds after the socket stays closed", async () => {
    vi.useFakeTimers()
    let calls = 0
    const api = {
      sync: async () => {
        calls += 1
        return empty()
      },
      webSocketUrl: () => "ws://127.0.0.1/api/v1/ws",
    } as unknown as PantryClient
    const socket: SocketLike = {
      onopen: null,
      onmessage: null,
      onclose: null,
      close() {
        this.onclose?.()
      },
    }
    renderHook(() => useFamilySync(api, true, () => socket))
    await act(async () => {
      await Promise.resolve()
    })
    const afterOpen = calls
    expect(afterOpen).toBeGreaterThan(0)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5000)
    })
    expect(calls).toBeGreaterThan(afterOpen)
  })

  it("drops a sync that finishes after the family session ends", async () => {
    let release: ((page: SyncPage) => void) | null = null
    const api = {
      sync: () => new Promise<SyncPage>((resolve) => {
        release = resolve
      }),
      webSocketUrl: () => "ws://127.0.0.1/api/v1/ws",
    } as unknown as PantryClient
    const socket: SocketLike = {
      onopen: null,
      onmessage: null,
      onclose: null,
      close() {
        this.onclose?.()
      },
    }
    const view = renderHook(
      ({ enabled }: { enabled: boolean }) => useFamilySync(api, enabled, () => socket),
      { initialProps: { enabled: true } },
    )
    await act(async () => {
      await Promise.resolve()
    })
    expect(release).not.toBeNull()
    view.rerender({ enabled: false })
    const stale = {
      reset: false,
      rev: 4,
      items: [{ id: "item-1", name: "牛奶" } as Item],
      tombstones: [],
      family: {
        id: "fam-old",
        name: "旧家",
        admin_user_id: "user-1",
        invite_code: "AB23CD",
        invite_expires_at: "2026-11-22T00:00:00Z",
        role: "admin",
        members: [],
      },
      notify_time: "09:00",
    }
    await act(async () => {
      release?.(stale)
      await Promise.resolve()
    })
    expect(view.result.current.items).toEqual([])
    expect(view.result.current.family).toBeNull()
    expect(JSON.stringify(view.result.current)).not.toContain("AB23CD")
    expect(JSON.stringify(view.result.current)).not.toContain("旧家")
  })
})
