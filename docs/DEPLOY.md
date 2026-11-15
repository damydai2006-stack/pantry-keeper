# 部署

脚本在 `deploy/windows/`。它们只处理 `pantry-` 服务、`C:\pantry` 和 `pantry` 库。不会重启 PostgreSQL，不会改 PostgreSQL 服务器配置文件，也不会修改 `ptt` 角色或库。

如果本机回环口令登录还没放开，`install.ps1` 只打印一行需要人工追加的 `pg_hba.conf` 说明，以及 `SELECT pg_reload_conf()`。脚本自己不写这个文件。

## 参数

`install.ps1`、`upgrade.ps1`、`uninstall.ps1`、`status.ps1` 都接受安装目录、服务前缀、监听端口、数据库名。默认是 `C:\pantry`、`pantry`、`127.0.0.1:18280`、对外 `9443`。

安装时还要传入：

- `-PostgresBin`：已有的 `psql.exe` 目录，脚本不安装数据库
- `-DatabasePassword`：只在角色不存在时使用；已存在则不改口令，除非 `-ResetPantryPassword`
- `-PublicIp`：安装时写入 Caddyfile。仓库模板里只有 `PANTRY_PUBLIC_IP`
- `-OfflineDir`：WinSW、JRE、Caddy、jar
- `-SkipFirewall`：跳过名为 `pantry-caddy-9443` 的规则

`upgrade.ps1` 只重启 `pantry-server`。健康检查失败时把上一个 jar 拷回去再启动，检查的仍是 pantry 端口。

`uninstall.ps1` 只停止并卸载 `pantry-server` 和 `pantry-caddy`。`-DropDatabase` 才会删除参数里的那个库，而且名字必须通过 pantry 范围检查。

证书按 TECH 2.8 方案 B：独立 `pantry-caddy` 使用 Let’s Encrypt 短期 IP 证书。HTTP-01 端口由模板里的 `PANTRY_ACME_HTTP_PORT` 代入，默认 80。仓库里没有真实地址。

照片上限默认每家庭 1024 MB，可用磁盘低于 1500 MB 时拒绝上传。上传接口不在 PR1，配额判断在 `PhotoQuota`。
