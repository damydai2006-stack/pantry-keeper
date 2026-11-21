/// <reference types="vitest/config" />
import { defineConfig } from "vite"
import react from "@vitejs/plugin-react"

// Dev-server proxy only. The value is not compiled into the static site.
// Empty means the documented local listen address. Do not put a public host here.
const proxyTarget = process.env.PANTRY_DEV_PROXY || "http://127.0.0.1:18280"

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      "/api": { target: proxyTarget, changeOrigin: true, ws: true },
      "/ws": { target: proxyTarget, changeOrigin: true, ws: true },
    },
  },
  test: {
    environment: "jsdom",
    setupFiles: "./test/setup.ts",
    include: ["test/**/*.test.ts", "test/**/*.test.tsx"],
  },
})
