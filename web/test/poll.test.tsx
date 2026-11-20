import { act, renderHook } from "@testing-library/react"
import { afterEach, describe, expect, it, vi } from "vitest"
import type { PantryClient } from "../src/api/client"
import type { SyncPage } from "../src/api/types"
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
})
