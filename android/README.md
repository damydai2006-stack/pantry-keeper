安卓端在 `feat/android-core`。

- `:android:app`：Compose 界面。登录、家庭、清单、手填入库、批量与 5 秒撤销、历史、离线只读。扫码、拍照、提醒页是占位。
- `:android:data`：SQLite 缓存、OkHttp、`/sync` 与前台 WebSocket。登出或收到 `session_revoked` 时断开连接。Room 的注解处理器还读不了 Kotlin 2.4 的 metadata，所以缓存直接用 SQLite，表结构按 Room 的 items/meta 来。
- 依赖 `:core`。minSdk 26，targetSdk 35。
- 服务器地址和证书钉从 `PANTRY_SERVER_URL`、`PANTRY_CA_PINS` 或 `local.properties` 注入，不进仓库。调试填充只在 debug 源码集，且只在本机配置了号码时出现。
