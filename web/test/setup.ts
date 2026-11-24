import "@testing-library/jest-dom/vitest"
import { cleanup } from "@testing-library/react"
import { afterEach, vi } from "vitest"

afterEach(() => cleanup())

// jsdom has no blob URL helpers. The photo view still only puts a blob: URL on the page.
if (typeof URL.createObjectURL !== "function") {
  let sequence = 0
  URL.createObjectURL = () => `blob:photo-${sequence += 1}`
  URL.revokeObjectURL = () => undefined
}

class StubWebSocket {
  static CONNECTING = 0
  static OPEN = 1
  static CLOSING = 2
  static CLOSED = 3
  url: string
  onopen: (() => void) | null = null
  onmessage: ((event: { data: string }) => void) | null = null
  onclose: (() => void) | null = null
  constructor(url: string) {
    this.url = url
  }
  close() {
    this.onclose?.()
  }
}

vi.stubGlobal("WebSocket", StubWebSocket)
vi.stubGlobal("ResizeObserver", class {
  observe() {}
  unobserve() {}
  disconnect() {}
})

window.getComputedStyle = (() => ({ getPropertyValue: () => "" }) as unknown as CSSStyleDeclaration) as typeof window.getComputedStyle

Object.defineProperty(window, "matchMedia", {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => undefined,
    removeListener: () => undefined,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    dispatchEvent: () => false,
  }),
})
