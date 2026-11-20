# 网页

家庭保质期的网页端。React 18、TypeScript、Vite、TanStack Query、Ant Design。和安卓调用同一套 `/api/v1`：验证码登录、创建或加入家庭、家庭内物品的列表、新增、编辑、删除，以及按已有日期规则显示到期和过期。

这是可以在本机构建的静态页面，不是已经上线的网站。没有配置真实服务器地址时，它不能直接登录。

## 登录方式

网页使用 `client_kind=web`。服务器把会话放进 `HttpOnly`、`SameSite=Strict` 的 `pantry_session` Cookie，响应里没有令牌。写请求带 `X-Pantry-Request: 1`。页面不保存令牌，也不预填验证码。

`AUTH_MODE` 不是 `test` 时，登录接口返回 403 `auth_disabled`，页面只显示服务器的「登录未开放」。本页不会放宽登录，也没有通用测试账号。

## API 地址

浏览器里的请求前缀是 `VITE_API_BASE`。留空表示相对当前页面的源，例如 `/api/v1/...`。这是默认，也是推荐方式。

会话 Cookie 是 `SameSite=Strict`。服务端默认不打开带凭证的跨域。把页面放到另一个网站上，即使填了 `VITE_API_BASE`，浏览器也不会带上这个 Cookie。不要把真实服务器地址写进仓库、`.env` 或构建日志。示例只在 `web/.env.example`。

本地开发时，Vite 把 `/api` 和 `/ws` 代理到 `PANTRY_DEV_PROXY`。这个变量只在开发机的 Vite 进程里使用，不会打进静态文件。不设置时，代理目标是文档里的本机监听地址 `http://127.0.0.1:18280`。

本地用 HTTP 时，服务器需要在不入库的 `server.env` 里把 `PANTRY_COOKIE_SECURE` 设为 `false`，浏览器才会保存 Cookie。正式 HTTPS 保持默认 `true`。

## 今天和到期

清单颜色和「从今天起算」使用 `Asia/Shanghai` 的日历日，和安卓提交保质期时的 `shanghaiToday()` 相同。到期日、分组和颜色用 `testdata/date-cases.json` 里的同一套规则。药品如果同时有开封日期和开封后天数，显示较早的那一天。

开发服务器可以用 `?today=YYYY-MM-DD` 覆盖今天。生产构建里这段代码由 `import.meta.env.DEV` 去掉。

## 同步

登录并进入家庭后，页面连接 `/api/v1/ws`。收到 `changed` 就按已有 `rev` 增量拉取 `/sync`。连接断开或还没连上时，每 5 秒拉一次；浏览器标签页在后台时不拉。`session_revoked` 回到登录页。`removed_from_family` 或 `not_in_family` 清空清单并回到加入家庭。页面不做离线编辑。

## 本地运行

需要 Node.js 20 或更新版本。

```bash
cd web
npm ci
npm test
npm run dev
```

打开 Vite 打印的本机地址。API 进程需要已经在代理目标上监听，并且登录按那台服务器自己的 `server.env` 开放。

```bash
npm run build
```

静态文件在 `web/dist`。把它和 API 放在同一个源下面：页面走 `/`，`/api` 和 `/ws` 转到 pantry-server。仓库不包含这个托管配置的真实地址。

## 测试

三组命令分开计数，不要把跳过的集成用例加进单测通过数。

`npm test`（等同 `npm run test:unit`）只跑 Vitest 单测。HTTP 和 WebSocket 是替身。覆盖日期向量、登录请求形状、家庭作用域、物品增删改、到期文案，以及登录失效、无网络和未加入家庭。

`npm run test:api` 只跑环回真实 API。先在仓库根目录启动测试进程，它使用内嵌数据库，只监听本机，并把两个合成账号写到本机临时文件，不打印：

```bash
./gradlew :server:itServer --no-daemon
```

该用例拒绝非回环地址。它检查两个账号各自的 Cookie、错误验证码、缺头和错头的 CSRF、跨家庭读写拒绝、保质期向量与服务器结果、并发删除，以及 WebSocket 只通知本家庭和只撤销当前会话。这个测试服务把 `PANTRY_COOKIE_SECURE` 设为 false，只为了在本机 HTTP 上保存 Cookie。

服务端和安卓自己的测试仍用 Gradle，例如：

```bash
./gradlew :core:jvmTest :server:test :android:data:test --no-daemon
```

## 这一版没有的功能

历史恢复、批量撤销、照片、扫码、提醒通知和成员管理还没有做。删除是永久的；「已用完」会把物品移出在库清单。安卓上的这些能力不受这次网页改动影响。

## 安卓包

调试 APK 不是正式签名包。服务器地址用 `PANTRY_SERVER_URL`，证书钉用 `PANTRY_CA_PINS`，可以从环境变量或本机 `local.properties` 注入，不要写进仓库。没有注入时，调试包仍使用工程里的本机占位地址，不能直接连真实服务器。

证书钉按现有规则：钉 YE1、YE2、YE3 这一代中间证书，以及 ISRG Root X1、X2 作为备用。不要钉叶子证书，也不要关闭 TLS 校验。钉值不在这个仓库里。

调试填充只有在本机另外注入 `PANTRY_DEBUG_PHONE` 和 `PANTRY_DEBUG_CODE` 时才会出现，发布源码集里没有。侧载安装后的登录、同步和提醒都还需要真机验收。把安装包交给使用的人之前，先通知 Damy。
