# 部署

脚本在 `deploy/windows/`。目标是 Windows Server 2022 上的 Windows PowerShell 5.1。它们只处理 `pantry-` 服务、安装目录（默认 `C:\pantry`）和 `pantry` 库。不会重启 PostgreSQL，不会改 PostgreSQL 服务器配置文件，不会自动改 `pg_hba.conf`，也不会碰 `ptt` 的服务、文件、端口或防火墙规则。

构建机需要 JDK 21。服务器上跑的是 Temurin 21 JRE，由 `install.ps1` 从离线 zip 解压到 `<安装目录>\jre`，不和对讲机共用。

## 安装

`install.ps1` 默认安装到 `C:\pantry`。它会：

1. 把 Temurin 21 JRE 的 zip 解压到 `<安装目录>\jre`（里面要有 `bin\java.exe`），并把 `caddy.exe`、`pantry-server.jar` 复制到 `<安装目录>\bin`。
2. 创建或补全 `<安装目录>\conf\server.env`。已有且非空的键不覆盖，用 5.1 或 pwsh 7 重跑都不会再追加一份键。缺的 `PANTRY_DB_PASSWORD`、`PANTRY_PHONE_ENC_KEY`、`PANTRY_PHONE_HMAC_KEY` 用 CSPRNG 生成，脚本不打印这些值。
3. 写 Caddyfile。`-AcmeEmail` 为空时，文件里不出现 `email` 行，空值会让 Caddy 起不来。
4. 若 `web\index.html` 还不存在，放入一份占位页，这样 `/` 返回 200。操作者删掉占位页后，Caddy 对没有的文件返回 404。
5. 把 `PANTRY_DATA_DIR` 设为 `<安装目录>\data\photos`（键已存在且非空则不改）。照片备份只打这个目录，不包含 Caddy 的 `data\caddy`，也不包含私钥。
6. 安装 WinSW **v2.12.0** 的 `WinSW-x64.exe`（[发布页](https://github.com/winsw/winsw/releases/tag/v2.12.0)）。该发布没有附带 SHA256，脚本不内置校验和，也不要自己编一个写进仓库。是否已注册看 `Get-Service`，不看 `WinSW status` 的退出码（v2 在未安装时也返回 0）。服务以 LocalSystem 运行。日志目录是 `<安装目录>\logs`，启动类型 Automatic，失败后 10 秒重启。`PANTRY_ENV_FILE` 指向 `conf\server.env`。参数里的路径加了引号，代入值会做 XML 转义。
7. 增加两条防火墙规则：`pantry-caddy-9443`（TCP 9443）和 `pantry-caddy-acme-80`（TCP 80）。添加失败（`netsh` 退出码非 0）时安装停止。`-SkipFirewall` 则两条都不加。80 端口如果已被别的程序占用，安装前的端口检查会拒绝继续。

安装**不会**建数据库角色，**不会**注册 `pantry-backup` 或 `pantry-purge` 计划任务。

离线目录 `-OfflineDir` 里放：`WinSW-x64.exe`（只要 v2.12.0）、Temurin 21 JRE 的 zip、`caddy.exe`、`pantry-server.jar`。也可以用 `-JreZip`、`-CaddyExe`、`-ServerJar` 单独指定。`-PublicIp` 只在安装时写入 Caddyfile，不进仓库。

文件都放好之后，安装目录整棵树收紧 ACL，只留 Administrators 和 SYSTEM，并去掉继承。包括安装目录本身，以及 `bin`、`jre`、`web`、`conf`、`backup`、`data\caddy`、`data\photos`、`logs`。这样本机普通用户不能在 `bin` 或 `jre` 里放 DLL 或 EXE。服务账号是 LocalSystem，也就是 SYSTEM，所以 pantry-server 和 pantry-caddy 仍能读程序、写照片、写证书和日志。未提权的管理员（UAC 过滤后的令牌）也读不了日志和备份，要在提权的 PowerShell 窗口里查看。

JDK 21 用来在构建机打 fat jar。服务器进程用的是上面这份 JRE 21。

## 数据库

角色和库要另做，二选一。

`init-db.ps1` 会交互询问 PostgreSQL 超级用户口令（不回显），再从 `server.env` 读取 `PANTRY_DB_PASSWORD`。发给 PostgreSQL 的是客户端算出的 SCRAM-SHA-256 验证串，不是明文；会话里还会 `SET log_statement = 'none'`。口令不放进命令行，也不打印。它不改 `pg_hba.conf`，不重启 PostgreSQL。`-ResetPantryPassword` 才会改已存在角色的口令。手工执行下面的 SQL 时，明文仍可能进 PostgreSQL 日志，所以优先用脚本。

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

`backup.ps1` 以角色 `pantry` 对 `pantry` 库做 `pg_dump`。口令从 `server.env` 的 `PANTRY_DB_PASSWORD` 读入 `PGPASSWORD`，dump 结束后清掉，不打印。同时把 `<安装目录>\data\photos` 打成 zip，并复制一份 `server.env`（里面有手机号密钥）。三份文件都写到 `<安装目录>\backup`。文件名带毫秒时间戳和随机后缀，同一秒内跑两次也不会互相覆盖。备份失败时会删掉已经写出的残缺 dump、zip 和 env。`-Keep` 默认 14，dump、照片 zip 和 env 副本各自保留最近 14 份。新文件和 `backup` 目录的 ACL 与 `conf` 相同，只有 Administrators 和 SYSTEM。

脚本结束时会提示：把这些备份拷到另一块磁盘或另一台机器，并且加密或限制谁能读。`server.env` 是明文，拿到异地副本的人就同时拿到数据和密钥。只留在本机系统盘上，磁盘坏了就一起没了。

`register-backup-task.ps1` 注册两个每日计划任务，都以 SYSTEM 运行，**不会**被 `install.ps1` 调用。`pantry-backup` 的时间是 `-At`，默认 `02:00`。`pantry-purge` 的时间是 `-PurgeAt`，默认 `03:10`；任务运行 `purge.ps1`，由它设置 `PANTRY_ENV_FILE`。`-PostgresBin` 末尾的 `\` 会被去掉，带空格的路径按 Windows 命令行规则加引号。`unregister-backup-task.ps1` 只删除这两个任务。

## 恢复

在一台新机器上，先装好 PostgreSQL 16、JRE 21 和同一份 pantry 服务（不要把旧的私钥目录混进来），然后：

1. 把备份里的 `pantry-env-*.env` 拷回 `<安装目录>\conf\server.env`。这是登录密钥，没有它，库里的手机号密文对不上。
2. 用超级用户建好 `pantry` 角色和空库（见上面的 SQL，口令用这份 `server.env` 里的 `PANTRY_DB_PASSWORD`），再恢复 dump：`pg_restore -h 127.0.0.1 -U pantry -d pantry --no-owner --role=pantry <备份>.dump`。
3. 把照片 zip 解压到 `<安装目录>\data\photos`，目录结构要和原来一致。
4. 启动 `pantry-server` 和 `pantry-caddy`。
5. 用 `server.env` 里的测试码和白名单登录一次，并打开一张已有照片，确认账号和文件都在。

Flyway 迁移只向前。升级失败如果已经执行了新的迁移，不能只换回旧 jar，要从这次升级前的 dump 恢复数据库。

## 状态

`status.ps1` 只读。它打印 `pantry-server` 和 `pantry-caddy` 的状态、本机 `healthz`、最近一次备份的时间。公网 `healthz` 用 `-PublicHealthUrl` 传入，脚本里不写死地址。地址是 https 时，另外打印证书颁发者和距离 `notAfter` 的天数；少于 3 天会告警（shortlived 证书大约 6 天有效）。

## 升级和卸载

`upgrade.ps1` 先停 `pantry-server`，调用 `backup.ps1` 做升级前备份，再换 jar 并启动。备份失败时 jar 不动，服务会重新启动到旧版本，并说明要检查数据库登录和 `pg_dump`。新 jar 和当前 jar 的 SHA256 相同时直接退出，不覆盖以前留下的 jar。真正要换的时候，旧 jar 按时间戳另存，不覆盖更早的那一份。`healthz` 会重试，直到成功或超过 `-HealthTimeoutSec`（默认 90 秒）。健康检查失败或启动失败时，把刚换上的 jar 换回去再启动。Flyway 迁移只向前：如果新 jar 已经执行了迁移，旧 jar 可能起不来，这时要用升级前的 dump 恢复数据库。不重启 PostgreSQL。

`uninstall.ps1` 只停止并卸载 `pantry-server` 和 `pantry-caddy`，并检查 WinSW `uninstall` 的退出码。同时删除 `pantry-caddy-9443`、`pantry-caddy-acme-80` 和计划任务 `pantry-backup`、`pantry-purge`。`-DropDatabase` 还必须带 `-ConfirmDropDatabase`，并且会先跑一次 `backup.ps1`。删的库名必须通过 pantry 范围检查。

`purge.ps1` 会把 `PANTRY_ENV_FILE` 指到 `conf\server.env` 再执行 jar 的 `purge`。

## 已知问题

- P2-14：非精确窗口闹钟可能越过午夜。提醒时间设在 23:59 时，过了零点发出的会是次日汇总，并把次日标成已发。
- P2-16：安装 JRE 时先删掉目标目录再移动。装到另一块盘时，跨卷移动失败会把原来的 JRE 留空。
- P2-17：服务正在运行时重跑 install 会因端口占用而拒绝。停掉服务后再跑，会覆盖已有的 WinSW 程序。重跑前先停 pantry 服务。
- P2-20：调用 psql 时先读完标准输出再读标准错误，错误输出很大时可能堵住。
- P2-22：角色已经存在且没有 `-ResetPantryPassword` 时，init-db 不会再试登录，发现不了 server.env 口令和库里不一致。
- P2-23：照片 zip 在 dump 之后、服务仍在跑的时候打包，两者可能对不上；zip 失败时 dump 已经写好，清理可能不会接着做。
- P2-24：备份失败只留在计划任务历史里。status 只看最近文件的时间，不看这次备份是否成功。
- P2-27：`install-secrets.test.ps1` 含中文且没有 BOM，Windows PowerShell 5.1 可能按系统 ANSI 代码页来读；测试用的临时目录不一定会删掉。
- P2-29：服务端读 server.env 时不会去掉 UTF-8 BOM。用会加 BOM 的编辑器保存后，第一个键会失效。
- P2-31：通知被永久拒绝后，每次保存提醒时间都会直接打开应用详情页，没有先说明一句，用的也不是通知设置页。
- NP2-1：升级回滚不看 Flyway 是否已经迁移，旧 jar 可能直接跑在新 schema 上，回滚后也不再查一次 healthz。
- NP2-4：异地备份里的 server.env 是明文，里面有手机号密钥和数据库口令，介质必须加密或限制访问。
- NP2-6：未提权的管理员读不了日志和备份，要在提权的 PowerShell 窗口里查看。
- NP2-7：`Trim-PantryPath` 的动词不符合 PowerShell 命名习惯，只是风格问题，不影响运行。

## fat jar 自测

Shadow 9 默认会丢掉重复的 `META-INF/services`。`mergeServiceFiles()` 要配合 `META-INF/services/**` 的 `DuplicatesStrategy.INCLUDE`，否则 fat jar 里只剩一份 Flyway 插件清单，启动迁移会在 `DryRunConfigurationExtensionStub` 上空指针。

不需要 Docker：

```bash
./scripts/verify-shadow-migrate.sh
```

它构建 fat jar，核对合并后的 Flyway 服务文件包含依赖里的全部条目，再只用这个 jar 对嵌入式 PostgreSQL 16 执行 `migrate`。
