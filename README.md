# pantry-keeper

家用食品、药品、调料保质期。公开仓库，不含服务器地址、测试码、手机号或密钥。

安卓已有登录、家庭、清单、手填、批量撤销、历史、离线同步、扫码和本地提醒。网页在 `web/`，和安卓使用同一套 API。本地运行、测试和 API 配置见 `web/README.md`。仓库没有线上地址，静态构建不等于已经部署。

## 本地运行

需要 JDK 21。

```bash
./gradlew :server:shadowJar
```

把 `.env.example` 复制为不入库的 `server.env`，填入本机数据库和密钥后再启动：

```bash
export PANTRY_ENV_FILE="$PWD/server.env"
java -jar server/build/libs/pantry-server.jar
```

默认只监听 `127.0.0.1:18280`。`PANTRY_PROFILE` 默认 `prod`。若同时把测试时钟设为打开，进程会拒绝启动。测试码登录只有在 `AUTH_MODE=test` 且验证码和白名单都写在 `server.env` 里时才启用。

```bash
./gradlew check
./scripts/check-all.sh
./scripts/release-gate.sh
```

服务端集成测试使用 Zonky 内嵌 PostgreSQL 16，不需要 Docker。

## 目录

- `core/` 纯 Kotlin（目前只开 JVM）：`DateMath`、`DateParser`、`ReminderRules`
- `server/` Ktor 与 Flyway
- `web/` React + TypeScript。构建产物是静态文件，需要和 API 同源托管
- `testdata/` `date-cases.json` 与 `ocr/*.txt`
- `deploy/windows/` 安装、升级、卸载、状态脚本，以及 WinSW、Caddy 模板
- `docs/TECH.md` 技术方案 v0.2

方案见 `docs/TECH.md`。接口见 `docs/API.md`。部署见 `docs/DEPLOY.md`。
