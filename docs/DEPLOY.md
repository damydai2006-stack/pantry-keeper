# 部署

脚本在 `deploy/windows/`。目标是 Windows Server 2022 上的 Windows PowerShell 5.1。它们只处理 `pantry-` 服务、安装目录（默认 `C:\pantry`）和 `pantry` 库。不会重启 PostgreSQL，不会改 PostgreSQL 服务器配置文件，不会自动改 `pg_hba.conf`，也不会碰 `ptt` 的服务、文件、端口或防火墙规则。

构建机需要 JDK 21。服务器上跑的是 Temurin 21 JRE，由 `install.ps1` 从离线 zip 解压到 `<安装目录>\jre`，不和对讲机共用。

## 安装

`install.ps1` 默认安装到 `C:\pantry`。它会：

1. 把 Temurin 21 JRE 的 zip 解压到 `<安装目录>\jre`（里面要有 `bin\java.exe`），并把 `caddy.exe`、`pantry-server.jar` 复制到 `<安装目录>\bin`。
2. 创建或补全 `<安装目录>\conf\server.env`。已有且非空的键不覆盖。缺的 `PANTRY_DB_PASSWORD`、`PANTRY_PHONE_ENC_KEY`、`PANTRY_PHONE_HMAC_KEY` 用 CSPRNG 生成，脚本不打印这些值。`conf` 目录的 ACL 只留给 Administrators 和 SYSTEM。
3. 写 Caddyfile。`-AcmeEmail` 为空时，文件里不出现 `email` 行，空值会让 Caddy 起不来。
4. 若 `web\index.html` 还不存在，放入一份占位页，这样 `/` 返回 200。操作者删掉占位页后，Caddy 对没有的文件返回 404。
5. 把 `PANTRY_DATA_DIR` 设为 `<安装目录>\data\photos`（键已存在且非空则不改）。照片备份只打这个目录，不包含 Caddy 的 `data\caddy`，也不包含私钥。
6. 安装 WinSW 服务 `pantry-server`、`pantry-caddy`。日志目录是 `<安装目录>\logs`，启动类型 Automatic，失败后 10 秒重启。`PANTRY_ENV_FILE` 指向 `conf\server.env`。
7. 增加两条防火墙规则：`pantry-caddy-9443`（TCP 9443）和 `pantry-caddy-acme-80`（TCP 80）。`-SkipFirewall` 则两条都不加。

安装**不会**建数据库角色，**不会**注册备份计划任务。

离线目录 `-OfflineDir` 里放：`WinSW.exe`、Temurin 21 JRE 的 zip、`caddy.exe`、`pantry-server.jar`。也可以用 `-JreZip`、`-CaddyExe`、`-ServerJar` 单独指定。`-PublicIp` 只在安装时写入 Caddyfile，不进仓库。

JDK 21 用来在构建机打 fat jar。服务器进程用的是上面这份 JRE 21。

## 数据库

角色和库要另做，二选一。

`init-db.ps1` 会交互询问 PostgreSQL 超级用户口令（不回显），再从 `server.env` 读取 `PANTRY_DB_PASSWORD`，经 psql 的 `\getenv` 传给角色，不放进命令行，也不打印。它不改 `pg_hba.conf`，不重启 PostgreSQL。`-ResetPantryPassword` 才会改已存在角色的口令。

或者由操作者用超级用户在维护窗口执行下面的 SQL。`<password>` 换成 `server.env` 里的 `PANTRY_DB_PASSWORD`，不要把口令写进仓库或聊天记录：

```sql
CREATE ROLE pantry LOGIN PASSWORD '<password>' CONNECTION LIMIT 10;
CREATE DATABASE pantry OWNER pantry;
REVOKE CONNECT ON DATABASE pantry FROM PUBLIC;
ALTER ROLE pantry SET statement_timeout = '5s';
```

角色已存在时不要再次 `CREATE`。口令只在明确要重置时才 `ALTER ROLE`。

## pg_hba.conf

脚本从不修改这个文件。

PostgreSQL 按从上到下第一条匹配的规则生效。若文件靠前已经有：

```text
host all all 127.0.0.1/32 scram-sha-256
```

这条已经覆盖本机的 `pantry` 角色，**不必再改**。后面即使还有更具体的行，也不会推翻前面这条。

检查方法：打开 `pg_hba.conf`，跳过注释和空行，找到第一条地址是 `127.0.0.1/32`（或 `127.0.0.1`）、数据库和用户是 `all` 或 `pantry` 的 `host` / `hostssl` / `hostnossl` 行。认证方式是 `scram-sha-256`、`md5` 或 `password` 就停。把文件路径传给 `install.ps1 -PgHbaPath` 时，脚本只读并打印结论，仍然不写文件。路径里带 `ptt` 会直接拒绝。

只有上面这条不存在、第一条匹配又是 `reject` 时，才由操作者在维护窗口追加：

```text
host pantry pantry 127.0.0.1/32 scram-sha-256
```

然后执行 `SELECT pg_reload_conf();`。不要重启 PostgreSQL。

## Caddy

模板 `Caddyfile.template`：

- 邮箱为空时没有 `email` 行。
- `disable_tlsalpn_challenge`，避免在 9443 上做 TLS-ALPN。
- 日志写到 `<安装目录>/logs/caddy.log`（Caddyfile 里用正斜杠）。
- 站点根目录同样用正斜杠。
- `:9443` 使用 Let’s Encrypt 的 IP 证书，`profile shortlived`。
- `/api`、`/ws`、`/healthz`、`/readyz` 反代到 `127.0.0.1:18280`。
- `:80` 只做 HTTP-01，不提供接口。

### Caddy 拿不到 IP 证书时用 Certbot

不要改 `ptt-caddy`。在 80 端口暂时只给这次验证用的前提下，由操作者在服务器上执行（公网地址不要写进仓库）：

```text
certbot certonly --standalone --preferred-profile shortlived --ip-address <公网地址> --cert-name pantry
```

需要 Certbot 5.4 或更新的版本，才有 `--ip-address` 和 shortlived profile。证书和私钥放到 `<安装目录>\conf\certs\`，跟着 `conf` 的 ACL，只有 Administrators 和 SYSTEM 能读。不要放进 `data\photos`，否则照片备份会把私钥打进去。

然后把该站点的 `tls { issuer acme ... }` 换成文件（文件名以实际为准）：

```text
tls C:/pantry/conf/certs/fullchain.pem C:/pantry/conf/certs/privkey.pem
```

续期单独做计划任务，不要从 `install.ps1` 里自动注册。续期后只重启 `pantry-caddy`。

## 证书钉扎（安卓）

Release 构建从环境变量 `PANTRY_CA_PINS` 或本机 `local.properties` 注入钉扎，格式是逗号分隔的 `sha256/<base64>`。具体钉值不进仓库，也不写进 `server.env`。

钉当前一代（Generation Y）的全部 ECDSA 中间证书：YE1、YE2、YE3。生产环境的链目前经过 YE2。Let’s Encrypt 会在同一代中间证书之间轮换，只钉 YE2 会在签到 YE1 或 YE3 时把客户端拒掉。不要钉叶子证书，短期证书大约 6 天就换。

再钉 ISRG Root X1 和 ISRG Root X2 作为备用。中间证书整代更换时，链上如果还有这些根，应用仍能连上。根钉比中间证书钉宽，只是退路，不能只钉叶子或只钉 YE2。

操作者从 Let’s Encrypt 公布的证书页在本机计算 SPKI 的 SHA-256，写入 `PANTRY_CA_PINS`。计算命令和结果都不要提交。

## 安装之后的登录

安装写好的 `server.env` 里 `AUTH_MODE=disabled`，进程可以先启动，登录接口不可用。操作者在服务器上改 `conf\server.env`（这些值只留在本机）：

- `AUTH_MODE=test`
- `AUTH_TEST_CODE` 必须刚好是 6 位数字
- `AUTH_TEST_PHONES` 是允许登录的号码，逗号分隔

然后只重启 `pantry-server`。`AUTH_MODE=test` 但验证码为空、不是 6 位，或白名单为空时，进程拒绝启动并退出。

## 备份

`backup.ps1` 以角色 `pantry` 对 `pantry` 库做 `pg_dump`。口令从 `server.env` 的 `PANTRY_DB_PASSWORD` 读入 `PGPASSWORD`，dump 结束后清掉，不打印。同时把 `<安装目录>\data\photos` 打成 zip。两份文件都写到 `<安装目录>\backup`，文件名带时间戳。`-Keep` 默认 14，dump 和照片 zip 各自保留最近 14 份。

`register-backup-task.ps1` 注册每日计划任务 `pantry-backup`，`-At` 默认 `02:00`，以 SYSTEM 运行。它**不会**被 `install.ps1` 调用。`unregister-backup-task.ps1` 只删除这个任务。

## 状态

`status.ps1` 只读。它打印 `pantry-server` 和 `pantry-caddy` 的状态、本机 `healthz`、最近一次备份的时间。公网 `healthz` 用 `-PublicHealthUrl` 传入，脚本里不写死地址。地址是 https 时，另外打印证书颁发者和距离 `notAfter` 的天数；少于 3 天会告警（shortlived 证书大约 6 天有效）。

## 升级和卸载

`upgrade.ps1` 只替换并重启 `pantry-server`。健康检查失败时把上一个 jar 拷回去再启动，检查的仍是 pantry 端口。不重启 PostgreSQL。

`uninstall.ps1` 只停止并卸载 `pantry-server` 和 `pantry-caddy`，并删除 `pantry-caddy-9443`、`pantry-caddy-acme-80` 和计划任务 `pantry-backup`、`pantry-purge`。`-DropDatabase` 才会删除参数里的那个库，名字必须通过 pantry 范围检查。

## fat jar 自测

Shadow 9 默认会丢掉重复的 `META-INF/services`。`mergeServiceFiles()` 要配合 `META-INF/services/**` 的 `DuplicatesStrategy.INCLUDE`，否则 fat jar 里只剩一份 Flyway 插件清单，启动迁移会在 `DryRunConfigurationExtensionStub` 上空指针。

不需要 Docker：

```bash
./scripts/verify-shadow-migrate.sh
```

它构建 fat jar，核对合并后的 Flyway 服务文件包含依赖里的全部条目，再只用这个 jar 对嵌入式 PostgreSQL 16 执行 `migrate`。
