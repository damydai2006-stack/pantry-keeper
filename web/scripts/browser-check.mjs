import { readFileSync } from "node:fs"
import puppeteer from "puppeteer-core"

function redact(text) {
  return text.replace(/\d/g, "#").slice(0, 500)
}

const env = readFileSync("/tmp/pantry-it/env", "utf8")
const phone = env.match(/^PANTRY_IT_PHONE_A=(.*)$/m)?.[1]?.trim()
const code = env.match(/^PANTRY_IT_CODE=(.*)$/m)?.[1]?.trim()
const origin = process.env.PANTRY_WEB_ORIGIN || "http://127.0.0.1:5173"
if (!phone || !code) throw new Error("it server env is incomplete")
const originHost = new URL(origin).hostname
if (originHost !== "127.0.0.1" && originHost !== "localhost") {
  throw new Error("PANTRY_WEB_ORIGIN must be loopback")
}

const browser = await puppeteer.launch({
  executablePath: process.env.CHROME_PATH || "/usr/bin/google-chrome",
  headless: true,
  args: ["--no-sandbox", "--disable-dev-shm-usage"],
})
const page = await browser.newPage()
try {
  await page.goto(origin, { waitUntil: "networkidle0" })
  await page.waitForSelector("input[aria-label='手机号']")
  await page.type("input[aria-label='手机号']", phone)
  await page.click("button::-p-text(获取验证码)")
  await page.type("input[aria-label='验证码']", code)
  await page.click(".ant-checkbox-input")
  await page.click("button::-p-text(登录 / 注册)")
  await page.waitForFunction(
    () => document.body.textContent.includes("创建家庭") || document.body.textContent.includes("新增物品"),
    { timeout: 10000 },
  )
  const visibleCookie = await page.evaluate(() => document.cookie)
  if (visibleCookie.includes("pantry_session")) throw new Error("HttpOnly session cookie is visible to script")
  const meStatus = await page.evaluate(async () => {
    const response = await fetch("/api/v1/me", { credentials: "include" })
    return response.status
  })
  if (meStatus !== 200) throw new Error("browser did not send the session cookie")
  if (await page.$("input[aria-label='家庭名称']")) {
    await page.type("input[aria-label='家庭名称']", "浏览器家庭")
    await page.click("button::-p-text(创建家庭)")
    await page.waitForFunction(() => document.body.textContent.includes("新增物品"), { timeout: 10000 })
  }
  await page.click("button::-p-text(新增物品)")
  await page.waitForSelector("input[aria-label='物品名称']")
  await page.type("input[aria-label='物品名称']", "酸奶")
  await page.click("button::-p-text(7 天)")
  await page.click("button::-p-text(保存并入库)")
  await page.waitForFunction(() => document.body.textContent.includes("酸奶") && document.body.textContent.includes("7 天后到期"), { timeout: 10000 })
  console.log("browser pass=1 fail=0 skip=0 httpOnly=true me=200 badge=7天后到期")
} catch (error) {
  console.log("browser pass=0 fail=1 skip=0")
  console.error(redact(error instanceof Error ? error.message : "browser failed"))
  const detail = await page.evaluate(() => {
    const labels = [...document.querySelectorAll("input,button")].map((node) => node.getAttribute("aria-label") || node.textContent || "").join(" | ")
    return document.body.innerText.slice(0, 700) + "\nLABELS " + labels.slice(0, 700)
  }).catch(() => "")
  console.error(redact(detail))
  process.exitCode = 1
} finally {
  await browser.close()
}
