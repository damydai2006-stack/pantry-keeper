# 大米保质期 技术方案 v0.2（对应 PRD v0.3.1）

作者：Kai　日期：2026-11-15　状态：方案已通过（Damy），v0.2 按 Quinn 审查 TECH-REVIEW.md 修订；剩余待 dai damy 决定的见第 11 节

## 修订记录（v0.1 → v0.2）

| 审查项 | 级别 | 改动位置 |
|---|---|---|
| T-01 改家庭名仅管理员 | P1 | 2.5 接口表、10 节 Q-38 |
| T-02 管理员须先转让才能退出；仅剩一人可直接退出 | P1 | 2.5 `leave` 语义、10 节 Q-07 |
| T-03 空家庭：邀请码立即失效、任何人不能加入、30 天后删除 | P1 | 2.4 `families`、2.5 `join`/`leave`、10 节 Q-05/Q-06 |
| T-04 会话 30 天按最近一次使用滑动，App/网页各自滑动 | P1 | 2.4 `sessions`、2.7、10 节 Q-31 |
| T-05 已过期提醒：本地日期 E+1+7k（k≥0），即过期第 1、8、15… 天 | P1 | 5.2、10 节 Q-20/C-02 |
| T-06 历史里的物品可永久删除（单条/多选，二次确认，墓碑同步） | P1 | 2.5 `batch delete`、2.6 第 5 条、第 6/7 节、10 节 C-05 |
| T-07 `EXP MM/YYYY`、`MM/YYYY`、`YYYY.MM`、`YYYY年M月` → 当月最后一天（含闰年），标黄 | P1 | 3.3、10 节 C-03 |
| T-08 所有按 ID 访问的资源校验属于当前家庭，拒绝时不泄露数据/字节 | P1 | 2.5 访问规则 |
| T-09 服务端可注入时钟，偏移只在测试配置可用，生产强制关闭，发布闸检查 | P1 | 2.9（新增）、8 节、9 节验收对照 |
| T-10 本机任何影响提醒集合的写入后立即重排闹钟 | P1 | 5.1 |
| T-11 验收机授予精确闹钟；debug「立即生成今日汇总」为 AC-03 正式方式；10 分钟窗口只是非边界体验 | P1 | 5.1、9 节验收对照 |
| T-12 删除已决项的“待确认”，标题改对应 PRD v0.3.1 | P2 | 全文、10 节、11 节 |
| T-13 所有成员可查看/分享邀请码，仅管理员可重置 | P2 | 2.4、2.5、第 6/7 节、10 节 C-04 |
| T-14 批量正操作整批事务；撤销是独立事务，只回滚未被他人改过的条目并提示跳过数 | P2 | 2.5、2.6 第 8 条、10 节 Q-25/Q-26 |
| T-15 客户端测试时钟注入 `:core` 的“今天”，清单分组与通知一致（仅 debug） | P2 | 5.1、5.2、第 7 节 |
| T-16 同账号多台手机各按本机时区各发一次（可接受） | P2 | 5.1、10 节 Q-18 |
| T-17 本期不做 REVOKE，列为已知残留风险 | P2 | 2.2 第 5 条、9 节验收对照、12 节 R11 |
| T-18 10 秒自动可见仅限对端前台 | P2 | 2.6 第 3 条、9 节验收对照、10 节 Q-39 |

dai damy 已决：公开仓库 `damydai2006-stack/pantry-keeper`；证书用 Let’s Encrypt IP 证书（2.8 方案 B）；登录用测试码 + 号码白名单；照片每家庭 1 GB 上限（保留磁盘剩余保护）。

本期范围：**安卓 + 网页 + 服务端**。iPhone 本期不开发（dai damy 11/15 原话“苹果版本APP先不开发”），选型只保证以后能补（见 1.3）。
本文不含任何服务器地址、密钥、账号。服务器统一称“生产 Windows 服务器”。标“待确认”的只剩本次没有核实或要实测的外部事实，产品规则均已按 PRD v0.3.1 定稿。

---

## 0. 结论速览

| 方面 | 推荐 | 一句话理由 |
|---|---|---|
| 安卓 | Kotlin + Jetpack Compose 原生 | 对讲机同款栈，代理和团队都熟；扫码、OCR、闹钟、厂商后台限制这些都要贴近系统 |
| 网页 | React + TypeScript + Vite 单页应用，由服务端的 Caddy 托管静态文件 | 网页只做清单/搜索/批量/编辑，普通网页最合适；中文字体和首屏比 Flutter Web 好控制 |
| 共享逻辑 | 纯 Kotlin `:core` 模块（KMP 结构，现在只开 JVM 目标）：日期算术、提醒规则、日期解析、接口 DTO | 服务端和安卓共用一份；网页侧 TS 移植同一套规则，并用**同一份 JSON 测试向量**保证一致 |
| 服务端 | Kotlin + Ktor 3 + PostgreSQL（复用现有实例，新库新角色）+ Flyway | 复用对讲机的鉴权、部署脚本、WinSW 经验；JVM 堆 256 MB 够用 |
| 隔离 | 服务 `pantry-server`、`pantry-caddy`（独立 Caddy 实例），目录 `C:\pantry`，库 `pantry`/角色 `pantry` | 重启、升级、改配置都碰不到 `ptt-*`；安装脚本不改 `ptt` 角色、不改 `postgresql.conf`、不重启 PG |
| 同步 | REST 增量同步（全局递增 `rev` 游标）+ WebSocket “有变化”通知，断线时前台 5 秒轮询兜底 | 满足 Q-39/C-01 “10 秒内自动可见”（对端在前台） |
| 冲突 | 按字段后写为准，被覆盖的字段提示“已被某某修改”；删除优先 | 直接实现 Q-27 |
| 离线 | 安卓本地缓存只读，修改按钮置灰 | 直接实现 Q-29，不做离线写队列 |
| 日期 OCR | 端侧：安卓用 ML Kit 中文识别**打包版**（不依赖 GMS）+ 自写中文日期解析器 | 服务器不加 CPU/内存负担；华为无 GMS 机型也能用 |
| 条码 | 扫码用 ML Kit 条码扫描（打包版）；查名走服务端代理 Open Food Facts（免费）+ 服务端缓存；查不到手填 | 免费；国内条码覆盖低，第一版靠手填兜底，P2 做家庭共享缓存 |
| 提醒 | **本地定时通知**：手机按已同步的数据每天在本人设定时间（默认 9:00）自己算汇总、自己发通知 | 不需要任何厂商账号、不花钱；提醒是每日汇总，不依赖服务器推送 |
| M1 | 服务端 + 安卓 P0，约 7–8 次代理运行，日历 2–3 周（与对讲机共用名额） | — |

---

## 1. 框架选型

### 1.1 候选对比（按本期“安卓 + 网页”，iPhone 只看“以后能不能补”）

| 维度 | A. Flutter（安卓 + Flutter Web） | B. React Native + 网页（RN Web 或独立 React 共享 TS） | C. 原生 Kotlin 安卓 + React 网页 + 共享 Kotlin 核心（推荐） |
|---|---|---|---|
| 一套代码覆盖 | 安卓/网页/以后 iOS 同一套 UI | 安卓/以后 iOS 一套 UI；网页用 RN Web 或另写 | 安卓、网页各一套 UI；规则逻辑共享 |
| 团队与代理熟悉度 | Dart 新栈 | TS 熟，RN 原生模块要另学 | 对讲机全套 Kotlin/Compose/Ktor 已跑通，代码和脚本可直接借鉴 |
| 和服务端共享代码 | 不能（Dart vs Kotlin） | 网页可共享 TS；服务端是 Kotlin 则不能 | `:core` 被服务端和安卓直接依赖 |
| 网页质量 | Canvas 渲染，首屏要下载 CanvasKit + 中文字体；默认从 gstatic 拉资源，国内要自托管，[`--no-web-resources-cdn` 后备字体仍会访问 fonts.gstatic.com](https://github.com/flutter/flutter/issues/163554)；浏览器文字选择/页内查找体验差 | RN Web 适合简单页；表格多选类页面常要另写 | 普通 DOM 网页，体积小，表格多选用现成组件（Ant Design 中文语言包） |
| 扫码/OCR/闹钟/后台 | 插件可用，遇到华为/卓易通兼容问题要钻到插件原生层 | 同左 | 直接用 CameraX、ML Kit、AlarmManager、WorkManager，问题好定位 |
| 以后补 iPhone | 最省：UI 直接复用 | 较省 | `:core` 加 iOS 目标，用 Compose Multiplatform 或 SwiftUI 写 UI；比 A 多一套 UI 工作（估 4–6 次运行，待确认） |
| 包体 | 较大（引擎约 +5–8 MB，待确认） | 中 | 小 |

### 1.2 推荐 C 的理由与代价
- 本期 iPhone 不做，Flutter/RN “一套 UI 三端”的最大好处兑现不了；反过来要付出新栈、网页首屏和国内字体问题的代价。
- 本项目难点在**安卓系统层**：无 GMS 机型的 OCR/扫码、国产 ROM 和卓易通下的定时通知。原生最好排查，对讲机已经踩过一轮。
- 日期规则（Q-11～Q-23）是产品的核心正确性。放进 `:core` 后服务端校验和安卓预览用同一份代码；网页用 TS 移植，两边共跑 `testdata/date-cases.json`，任何一边算错 CI 级别的本地测试就会挂。
- 代价：网页是第二套 UI（但范围小：清单、搜索、筛选、批量、编辑、手填）；以后补 iPhone 要再写 UI。
- 备选：如果 dai damy 明确下一期就要 iPhone，改选 A（Flutter），网页仍建议独立 React，不用 Flutter Web。

### 1.3 以后补 iPhone 需要什么（仅备忘，本期不做、不申请）
- 苹果开发者计划 99 美元/年（[Apple 报名说明](https://developer.apple.com/support/enrollment/)，价格待确认最新）；TestFlight 分发。
- 一台 Mac 打包签名：dai damy 的 Mac，或云 Mac。例如 Codemagic 个人账号每月 500 分钟免费 macOS M2，超出 0.095 美元/分钟，团队账号无免费分钟（[Codemagic 价格](https://codemagic.io/pricing/)，2026-11 查询）。GitHub Actions 计费受阻，不考虑。
- 推送：APNs 需开发者账号；本地通知方案在 iOS 同样可用（单 App 最多 64 条待发本地通知，待确认）。
- OCR：Apple Vision 文字识别支持简体中文（待确认最低系统版本）。
- 服务端和接口不需要改。

---

## 2. 服务端

### 2.1 技术栈
Kotlin + Ktor 3（JVM 21）+ kotlinx.serialization + Exposed/JDBC + HikariCP + Flyway，与对讲机一致（对讲机 TECH 第 2 节）。理由：鉴权（手机号+验证码、测试码、手机号加密+HMAC）、WinSW 部署脚本、健康检查、日志规范都能照搬；`:core` 直接复用。代码**复制**而不是依赖对讲机的 jar，两个项目互不影响升级。

### 2.2 部署隔离（生产 Windows 服务器，与对讲机同机）

| 项目 | 对讲机（现状，不动） | 保质期（新） |
|---|---|---|
| 安装目录 | `C:\ptt` | `C:\pantry`（bin / conf / data\photos / logs / backup） |
| Windows 服务 | `ptt-server`、`ptt-caddy`、`ptt-asr`；演练用 `ptt-drill-*` | `pantry-server`、`pantry-caddy` |
| 应用端口 | 本机 8080；演练 18080/18443 | 只绑回环 `127.0.0.1:18280`（避开 8080/18080/18443，安装前只读检查占用） |
| 对外端口 | 8443（Caddy） | IP 模式 `9443`；域名模式 443（另需 80 做 ACME）。安装前只读检查占用 |
| 防火墙规则 | `ptt-caddy-8443` | `pantry-caddy-9443`，名字不同；脚本只增删自己名字的规则 |
| 数据库 | 实例 `postgresql-x64-16`，库/角色 `ptt` | 同一实例，新库 `pantry`、新角色 `pantry` |
| JRE | 对讲机自带 | 自带 jlink 精简 JRE（约 50–60 MB，待确认），不共用，避免对讲机升级换 JRE 时连带 |
| 计划任务 | `ptt-backup` | `pantry-backup`、`pantry-purge` |

**Caddy 选独立实例而不是在 `ptt-caddy` 里加站点**：
- 加站点意味着改对讲机的 Caddyfile 并 reload/重启 `ptt-caddy`。配置写错会被拒绝，但升级 Caddy 或重启进程会断开所有对讲 WebSocket，违反 PRD 验收“升级或重启它不影响对讲”。
- 独立 `pantry-caddy`：独立二进制副本、独立 `data` 目录（证书/本地 CA 不混）、独立端口。内存约 30–50 MB，磁盘约 40 MB（待确认）。代价很小，换来完全隔离。
- 两个实例只要端口不冲突就能共存。以后如果对讲机也要上 443，可以再讨论合并，现在不合并。

**PostgreSQL 复用规则**（安装脚本 `deploy/windows/install.ps1` 的硬约束，吸取对讲机演练方案里的教训）：
1. 只执行：`CREATE ROLE pantry LOGIN PASSWORD … CONNECTION LIMIT 10`（角色不存在时）、`CREATE DATABASE pantry OWNER pantry`（库不存在时）、`REVOKE CONNECT ON DATABASE pantry FROM PUBLIC`、`ALTER ROLE pantry SET statement_timeout = '5s'`（只改自己的角色）。
2. **绝不**执行 `ALTER ROLE ptt`、不改 `postgresql.conf`、不重启 `postgresql-x64-16`、不调用对讲机的 `Install-Postgres16`/`Initialize-PttDatabase`。
3. 角色 `pantry` 已存在时不改口令（除非显式 `-ResetPantryPassword`）。
4. `pg_hba.conf`：安装前只读检查现有规则是否已允许本机回环用口令登录任意库（现有写法部署前只读核对）。如果需要加一行，只能追加 `host pantry pantry 127.0.0.1/32 scram-sha-256` 并 `SELECT pg_reload_conf()`（重读配置，不重启、不断开现有连接）。这一步属于改共享配置，要 dai damy 同意并在维护窗口做。
5. 默认 PUBLIC 对 `ptt` 库有 CONNECT 权限，`pantry` 角色理论上能连上 `ptt` 库但没有任何表权限。要彻底封死需要 `REVOKE CONNECT ON DATABASE ptt FROM PUBLIC`，这是改对讲机的库。**本期不做**（Damy 决定），列为已知残留风险（12 节 R11）；验收时只读核对 `pantry` 角色在 `ptt` 库无任何表权限、库名独立。
6. 连接池 `maximumPoolSize=5`，角色连接上限 10，PG 默认 `max_connections=100`（部署前只读核对现值），不挤占对讲机。

**安装/升级脚本从第一天就参数化**：`-InstallRoot`、`-ServicePrefix`、`-ListenPort`、`-PublicPort`、`-DatabaseName`、`-DatabaseUser`、`-FirewallRuleName`/`-SkipFirewall`、`-OfflineDir`；健康检查读配置里的端口，不写死。`uninstall.ps1` 只认 `pantry-` 前缀。升级失败自动回滚到上一个 jar，回滚后的检查只看 pantry 端口。
**安装时机**：对讲机升级演练结束、dai damy 同意后才装；本方案阶段不碰服务器。

### 2.3 资源预算（8 核 / 8 GB / 系统盘约 3 GB 可用，可用空间待部署前复核）

| 项 | 内存 | 磁盘 |
|---|---|---|
| pantry-server（`-Xmx256m`，`BelowNormal` 优先级） | 常驻约 350–400 MB | jar 约 30 MB + JRE 约 60 MB |
| pantry-caddy | 30–50 MB | 约 40 MB |
| PostgreSQL 新增（≤5 连接 + 小库） | 约 50 MB | 1 万件物品 < 50 MB |
| 照片 | — | 见下 |
| 日志（WinSW 滚动 10 MB × 5，两服务） | — | ≤ 100 MB |
| 合计（不含照片） | **≤ 500 MB** | **约 300 MB** |

照片是磁盘主要风险：
- 安卓端压缩：长边 ≤ 1600 px、JPEG 质量 80，约 200–300 KB；同时生成 320 px 缩略图约 25 KB。
- 文件存 `C:\pantry\data\photos\{family}\{id}.jpg`，数据库只存元数据。
- 服务端两道闸：单家庭上限 `PANTRY_FAMILY_PHOTO_MB=1024`（dai damy 定 1 GB，约 3500 张）；全盘可用空间低于 `PANTRY_MIN_FREE_MB=1500` 时拒绝上传（返回 507，App 提示“服务器空间不足，物品已保存但照片未上传”），**保护对讲机录音和 PG 的空间**。
- 物品永久删除时同步删照片文件。
- 系统盘只剩约 3 GB，单家庭 1 GB 上限本身挡不住撑满磁盘，真正保护对讲机的是“剩余低于 1.5 GB 拒绝上传”这道闸，两者都保留。
- CPU：保质期服务端只有增删改查，不做 OCR，对对讲机实时转发和 ASR 无可感知影响。

### 2.4 数据模型（PostgreSQL，Flyway `V1__init.sql` 起步）

时间戳一律 `timestamptz`；“日期”字段用 `date`（无时区，按用户手机本地日期解释，满足 Q-17/Q-18）。

```sql
users(
  id uuid pk, phone_enc bytea, phone_hmac bytea unique,   -- 同对讲机：加密存储 + HMAC 查找，密钥独立
  display_name text not null, created_at, updated_at)

auth_codes(phone_hmac bytea, code_hash text, expires_at, attempts int, created_at)   -- 限频、5 次错误作废

sessions(
  id uuid pk, user_id fk, token_hash bytea unique,
  client_kind text check (client_kind in ('app','web')),  -- Q-31 App 与网页各自会话
  device_name text, created_at, last_used_at, expires_at)  -- 滑动 30 天：每次经认证的请求把 expires_at 更新为 now+30 天（PRD v0.3）；Q-02 多端同时在线：不互踢

user_settings(user_id pk, notify_time time default '09:00', updated_at, rev bigint)   -- Q-18 每人可改，多台手机共享

families(
  id uuid pk, name text not null check (char_length(name) between 2 and 20),   -- Q-38 仅管理员可改名（v0.3），不能解散
  admin_user_id uuid fk,                           -- Q-07 创建者为管理员，可转让
  invite_code char(6) null unique, invite_expires_at,   -- Q-04 6 位、7 天、不限次数；C-04 所有成员可见可分享，仅管理员可重置；家庭空了置 null
  empty_since timestamptz null,                    -- 最后一人退出时写入，同时作废邀请码；非空期间拒绝任何加入；30 天后清理（v0.3）
  created_at, updated_at, rev bigint)

family_members(
  family_id fk, user_id fk, role text check (role in ('admin','member')),
  joined_at, left_at null, left_reason text null check (left_reason in ('left','removed')),
  primary key (family_id, user_id, joined_at))
-- Q-03 一人只属于一个家庭：
create unique index one_active_family on family_members(user_id) where left_at is null;

items(
  id uuid pk,                                       -- 客户端生成，便于重试幂等
  family_id fk, name text not null check (char_length(name) <= 40),   -- Q-38 允许重名；v0.3.1 ≤40 字
  category text not null default 'food' check (category in ('food','medicine','condiment','other')),  -- Q-08
  quantity int not null default 1 check (quantity >= 0),   -- Q-37 只是数字，无单位，不联动状态
  location text null check (char_length(location) <= 12), note text null check (char_length(note) <= 200), barcode text null,   -- v0.3.1 长度
  expiry_date date not null,                        -- Q-08 必填（或由下面三项推算后落库）
  production_date date null,
  shelf_life_value int null, shelf_life_unit text null check (shelf_life_unit in ('day','month','year')),
  expiry_basis text not null check (expiry_basis in ('ocr_expiry','ocr_prod_plus_life','manual_duration_from_prod','manual_duration_from_today','manual_date')),  -- Q-14 界面写明起算日
  opened_date date null, after_open_days int null,  -- P1 药品，Q-33
  reminder_tiers text[] not null default '{D30,D5}',-- 取值 D5/D10/D30/M2/M3；允许空数组（Q-19）
  status text not null default 'in_stock' check (status in ('in_stock','used_up','discarded')),  -- Q-10
  status_changed_at, status_changed_by,
  photo_id uuid null,
  field_meta jsonb not null default '{}',           -- {字段名: {"rev":n,"by":user_id}}，实现 Q-27 按字段后写为准
  created_by, created_at, updated_by, updated_at,
  rev bigint not null)                              -- 全局序列，用作同步游标
create index on items(family_id, rev);
create index on items(family_id, status, expiry_date);

item_tombstones(item_id uuid pk, family_id, deleted_by, deleted_at, rev bigint)  -- Q-24 永久删除只留墓碑供同步，保留 90 天

item_events(id bigserial, family_id, item_id, actor_id, action text, changed_fields text[], at)
-- 审计：create/update/status/delete/restore。只记字段名不记内容，永久删除后不留物品内容

photos(id uuid pk, family_id, item_id, path text, thumb_path text, bytes int, sha256 bytea, created_by, created_at)

batch_ops(undo_token uuid pk, family_id, user_id, action text, prior jsonb, created_at)  -- Q-25 撤销用，保留 10 分钟

barcode_cache(                                      -- P2；P0 只用 scope='global' 缓存 OFF 结果
  barcode text, scope text, family_id uuid null,    -- scope: 'global'（来自 OFF）/ 'family'
  name text, default_category text,                 -- Q-35 只共享 名称 + 默认分类
  source text, hit_count int, updated_at, primary key (barcode, scope, family_id))

devices(id uuid pk, user_id, platform text, push_provider text null, push_token text null,
        app_version text, last_seen_at)             -- 预留给以后服务器推送；P0 只记版本和最近在线
```

**提醒档位用字符串编码**（`D5`/`D10`/`D30`/`M2`/`M3`），“2 个月/3 个月”按日历月减，不能存成 60/90 天（Q-23）。

**rev 的提交顺序问题**：全局序列号在事务内分配，不同事务可能“后分配先提交”，客户端拿游标拉取时会漏掉。解决：同一家庭的所有写操作先 `SELECT … FROM families WHERE id=? FOR UPDATE`，把同家庭写入串行化。家庭内并发很低，代价可忽略，从而保证同一家庭内 rev 的提交顺序单调。

### 2.5 接口草案（`/api/v1`，JSON）

| 接口 | 说明 |
|---|---|
| `POST /auth/code`、`POST /auth/login` | 手机号+验证码；`login` 带 `client_kind`，App 返回 Bearer 令牌，网页设置 HttpOnly+Secure+SameSite=Strict Cookie |
| `POST /auth/logout` | 只注销当前会话 |
| `GET /me`、`PUT /me/settings` | 含 `notify_time` |
| `POST /families`、`POST /families/join {code}` | Q-03/Q-04/Q-06；`join` 遇到 `empty_since` 非空的家庭、码已过期或已被重置，一律 404 `invite_invalid`（含原成员、原管理员） |
| `POST /families/leave` | 普通成员直接退出；管理员在还有其他成员时返回 409 `admin_must_transfer`，不改数据；管理员是唯一成员时可退出，家庭写 `empty_since`、邀请码置空（v0.3） |
| `GET /families/current` | 家庭名、成员、管理员、**当前邀请码及过期时间**，所有在籍成员可见，可复制分享（C-04）；`/sync` 也带这些字段 |
| `PATCH /families/current {name}` | **仅管理员**（v0.3）；普通成员 403 `admin_only`，不改数据 |
| `POST /families/current/invite/reset`、`DELETE /families/current/members/{uid}`、`POST /families/current/admin {uid}` | 仅管理员（Q-07）；转让后自己变普通成员（v0.3.1“设为管理员”，客户端二次确认） |
| `GET /sync?since={rev}` | 返回 `rev > since` 的物品、墓碑、家庭信息、成员、本人设置；游标早于墓碑保留期则返回 `reset:true`，客户端全量重拉 |
| `POST /items` | 新建；服务端校验必填和日期推算（用 `:core`） |
| `PATCH /items/{id} {base_rev, fields}` | 只带改动的字段；见 2.6 |
| `POST /items/batch {ids, action}` | `action` 为 `used_up`/`discarded`/`restore`/`delete`；单事务，全成功或全失败（Q-26）；`delete` 对在库和历史（`used_up`/`discarded`）物品都可用，单条也走这个接口（C-05）；非删除操作返回 `undo_token` |
| `POST /items/batch/undo {undo_token}` | Q-25：客户端 5 秒内可点；服务端宽限 60 秒应对网络延迟。只回滚这批的 `status`；这批之后被他人改过（`field_meta.status.rev` 大于这批写入的 rev，或已被删除）的条目跳过，响应带 `skipped` 数，客户端提示“N 件已被他人修改，未撤销”（事务语义见 2.6 第 8 条） |
| `POST /items/{id}/photo`（仅 App）、`GET /photos/{id}?thumb=1` | Q-09 网页只看不传；按下方访问规则校验家庭归属 |
| `GET /barcodes/{code}` | 先查缓存，再代理 Open Food Facts |
| `GET /export.csv` | P1，Q-34；只导出当前家庭 |
| `GET /ws` | WebSocket，服务端只推 `{"t":"changed","rev":n}`、`{"t":"removed_from_family"}`、`{"t":"session_revoked"}` |
| `GET /healthz`、`GET /readyz` | `readyz` 标出测试码模式和测试时钟状态（2.9） |

**每个请求都按“当前有效成员关系”取家庭**（`family_members.left_at is null`），不把家庭 ID 缓存在令牌里。被移除者下一次请求立即 403 `not_in_family`（Q-07），服务端同时向其所有 WS 连接发 `removed_from_family` 并关闭；客户端收到后清空本地缓存的家庭数据和照片缓存。

**按资源 ID 访问的统一校验（T-08）**：`PATCH /items/{id}`、`POST /items/{id}/photo`、`GET /photos/{id}`、`POST /items/batch`（每个 id）、`POST /items/batch/undo`（`undo_token` 的 `family_id`）、`GET /export.csv` 都先取“当前有效家庭”，再要求资源的 `family_id` 等于它；查询直接带 `WHERE id=? AND family_id=?`。
- 不属于当前家庭（他家资源、已退出或被移除后的旧资源）：一律 404 `not_found`，响应体不含物品字段、照片字节或家庭信息。不区分“不存在”和“无权”，避免探测。
- 本人已无有效家庭：403 `not_in_family`。
- 批量里只要有一个 id 不属于当前家庭，整批 404、不做任何修改（与 Q-26 一致）。
- 照片文件路径不由客户端提供；服务端先查 `photos` 行并校验家庭，再读文件。

### 2.6 同步与冲突（实现 Q-27/Q-29/Q-39）
1. **拉取**：客户端保存 `last_rev`。启动、回到前台、收到 WS `changed`、下拉刷新时调用 `GET /sync?since=last_rev`。
2. **通知**：Ktor 维护 `familyId → 连接集合`。任何写事务提交后向该家庭所有连接（包括写入者的其他设备，Q-02）推 `changed`，端到端预期 < 2 秒。
3. **兜底**：前台且 WS 未连上时每 5 秒轮询一次 `sync`。网页和 App 都一样。后台不轮询。10 秒内可见对 WS 和轮询两条路径都成立，满足 Q-39/C-01（只能下拉刷新才看到算不通过）。**前提：对端 App 在前台打开清单，或网页标签页在前台**（Damy 决定，写进验收说明）；App 在后台时不承诺 10 秒，回到前台立即拉取。
4. **按字段后写为准**：
   - 客户端 `PATCH` 带 `base_rev`（它看到的这条物品的 rev）和改过的字段。
   - 服务端锁行。行不存在且有墓碑：返回 410 `item_deleted` 和 `deleted_by`，客户端提示“已被某某删除”（删除优先，Q-27）。
   - 对每个改动字段：若 `field_meta[字段].rev > base_rev` 且 `by ≠ 本人`，说明别人先改过，记进 `overwritten`。无论如何都写入（后写为准）。
   - 别人改过、本次没改的字段保持别人的值（按字段合并，不整行覆盖）。
   - 响应带新行和 `overwritten:[{field, by_name}]`，客户端提示“名称、到期日已被某某修改，已用你的修改覆盖”。
   - 批量改状态同样按 `status` 字段处理；恢复只改 `status`，档位保持原值（Q-28）。
5. **删除**：硬删除行和照片，写墓碑，二次确认在客户端（Q-24）。在库清单和历史页都能删，单条和多选都支持（C-05）；墓碑经 `/sync` 下发，其他端在 10 秒内（前台）移除该物品。
6. **离线（Q-29）**：安卓用 Room 存最近一次同步的快照和缩略图，离线可查看清单、详情、提醒清单；所有修改入口置灰并提示“离线中，联网后可修改”。不做写队列，避免离线冲突。网页不做离线。
7. **幂等**：新建用客户端 UUID；重复提交返回已有行。
8. **批量与撤销的事务语义（T-14）**：
   - 正操作（标记已用完/已处理/恢复/删除）：一个事务，全成功或全失败（Q-26）。不会出现“部分标记成功”。
   - 撤销：另一个独立事务，在锁住家庭行后，只把“这批之后没人动过 `status` 且仍存在”的条目恢复到批前状态；被他人改过或已删的条目跳过，返回跳过数。
   - 两者不矛盾：Q-26 约束的是用户发起的批量操作本身；撤销是对已提交结果的补偿，按 Damy 决定跳过冲突条目并明确提示，而不是整批失败。

### 2.7 登录与测试码（Q-01、Q-31、Q-40）
- 复制对讲机的 `AUTH_MODE=test|sms`、`AUTH_TEST_CODE`、`AUTH_TEST_PHONES`（白名单）机制，**配置独立**：保质期有自己的 `server.env`，测试码、白名单和对讲机无关。
- dai damy 已定：本期生产也用“测试码 + 号码白名单”，不接短信。只有白名单号码能登录；测试码和白名单只写在服务器本机的 `server.env`，不进仓库、日志和报告。启动日志和 `/readyz` 照常标出测试码模式，便于以后切换真实短信时核对。
- 客户端：测试码的自动填写、调时间等调试入口只放在安卓 `debug` 源集里，release 构建不包含。仓库带 `scripts/release-gate.sh`，检查 release 产物里没有调试类名和调试接口字符串（同对讲机的发布闸）。
- 会话按最近一次使用滑动 30 天（PRD v0.3）：每次经认证的请求更新 `last_used_at`，并把 `expires_at` 改为“本次使用 + 30 天”（同一会话 1 分钟内最多写库一次，减少写放大）。App 会话和网页会话各自滑动，互不影响（Q-31）。过期按服务端时钟判断（可测见 2.9）。
- 网页必须登录后使用（Q-40）。Cookie 用 SameSite=Strict，写接口另要求自定义请求头，防 CSRF。

### 2.8 HTTPS 与访问地址（已定：方案 B）
对讲机现在用 Caddy 本地自签 CA + APK 内置信任锚和 SPKI 固定。这对**自己的 App** 可行，但对**网页**不友好：每台电脑的浏览器都要手动安装并信任根证书，Chrome 冒烟会很别扭。三个选项（dai damy 已选 **B**）：

| 选项 | 做法 | 成本 | 问题 |
|---|---|---|---|
| A 继续自签 | `pantry-caddy` 用自己的 `tls internal`，APK 内置其根证书 | 0 | 网页每台电脑要装根证书；手机浏览器同理 |
| **B Let’s Encrypt IP 证书（已定）** | 2026-01 起 Let’s Encrypt 正式签发 IP 地址证书，必须用 6 天短期证书并自动续期（[公告](https://letsencrypt.org/2026/01/15/6day-and-ip-general-availability)、[profiles](https://letsencrypt.org/docs/profiles/)） | 0 | 验证需要公网 80（HTTP-01）或 443（TLS-ALPN-01）可达，要开新端口；Caddy 当前版本对 IP 证书和 `shortlived` 配置的支持待确认（M2 前在代理环境验证）；续期失败 6 天内就会过期，要监控 |
| C 域名 + 正式证书 | 买域名，Caddy 自动申请证书 | 域名约每年几十元（待确认） | 服务器在中国大陆时域名必须 ICP 备案，否则 80/443 会被拦截（对讲机 TECH 已记）；服务器是否在大陆、能否以这台机器备案待确认 |

落地方式（方案 B）：
- `pantry-caddy` 对外 9443 提供服务；HTTP-01 验证需要公网 80 可达（由 `pantry-caddy` 监听，对讲机不用 80）。放行 9443 和 80 的防火墙规则仍待 dai damy 同意（第 11 节）。
- 续期每天检查；`pantry-server` 的 `/readyz` 和部署 `status.ps1` 报告证书剩余天数，低于 3 天告警。
- 如果 Caddy 不支持 IP 证书，就退回：用独立 ACME 客户端（如 Certbot 5.4+ `--ip-address --preferred-profile shortlived`）签证书，由 `pantry-caddy` 加载文件。
- App 用系统信任库，不固定会轮换的叶子证书（可选固定 Let’s Encrypt 根证书 SPKI）。服务地址构建时注入，不进仓库。

### 2.9 服务端可测时钟（T-09，Damy 决定）
- 服务端所有“现在”都从注入的 `Clock` 取：会话滑动/过期、邀请码 7 天、`empty_since` 30 天清理、撤销 60 秒宽限、墓碑保留期。代码里禁止直接调用 `Instant.now()`/`LocalDate.now()`（由测试和 release-gate 检查）。
- 只有测试配置可以偏移时钟：`PANTRY_PROFILE=test` 且 `PANTRY_TEST_CLOCK=on` 时，开放 `POST /api/v1/test/clock {offsetSeconds}` 和 `POST /api/v1/test/purge-now`。调用要求测试令牌，令牌只在测试 env 文件里。
- 生产配置（`PANTRY_PROFILE=prod`）**强制关闭**：即使误配 `PANTRY_TEST_CLOCK=on`，启动时也拒绝启动并打 ERROR，测试接口不注册路由。
- 发布闸 `scripts/release-gate.sh` 检查：生产配置模板里没有测试时钟开关；生产启动自检能拒绝 `on`；发布包里的默认配置是 `prod`。
- 测试环境用独立进程、独立库（如代理本地 PG 容器或服务器上单独的测试实例），不改服务器主机时间，不影响对讲机。

---

## 3. 日期 OCR

### 3.1 端侧还是服务器

| 方案 | 优点 | 缺点 |
|---|---|---|
| 安卓 ML Kit 中文文字识别（**打包版** `com.google.mlkit:text-recognition-chinese`）（推荐） | 模型随 App 打包，不经 Google Play 服务下载，无 GMS 的华为机也能用；每个架构约 +4 MB；离线、零服务器开销（[ML Kit 文档](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)） | 点阵喷码识别一般；要求 minSdk 23 |
| 安卓 PaddleOCR 移动版（Paddle Lite） | 中文识别好，喷码可能更好 | 集成重、包体大（约 10–20 MB，待确认）；作为 ML Kit 不达标时的备选 |
| 服务器离线（RapidOCR/PaddleOCR ONNX） | 模型可换大的 | 每次识别占 1–2 核、几百 MB 内存，和对讲机 ASR 抢 CPU；上传照片多一跳；违背“不影响对讲” |

**推荐**：ML Kit 打包版（**不用** `play-services-mlkit-*` 非打包版）。M1 第一个安卓 PR 里做一个调试页，先在 Mate 70 Pro 上实测能否运行（见风险 R1）。ML Kit 不达标时换 PaddleOCR 移动版，服务器 OCR 不做。

### 3.2 识别流程
1. 拍照页给取景框提示“对准日期区域”；拍完可手动框选日期区域再识别。
2. 预处理：灰度、对比度增强、喷码点阵做轻度膨胀，原图和处理图各识别一次，合并文本行。
3. 文本交给 `:core` 的 `DateParser`，输出候选：`expiry`、`production`、`shelfLife`、每项的置信度和“歧义”标记。
4. **确认页永远出现**：显示识别到的原文行、推算出的到期日、起算依据（Q-14 写明“从生产日期起算”或“从今天起算”）。歧义项标黄（Q-13），用户确认后才能保存。
5. 识别不到可信到期日：直接跳到手选页（保质期长度 7天/1个月/6个月/1年/2年/自定义，或直接选到期日）。任何异常（超时 5 秒、无文本、解析抛错）一律落到手选页，不会卡死，也不会自动存（PRD 验收第 2 条）。

### 3.3 中文包装日期解析规则（`:core` 纯 Kotlin，网页手填不需要）
- **归一化**：全角转半角；中文数字（一二两三…十、半）转阿拉伯数字（“两年”=2 年，“十八个月”=18，“半年”=6 个月）；数字串里的 O/o→0、l/I→1；去掉数字间的空格；`生產/保質/有效` 繁体同义。
- **标签词**：
  - 生产：`生产日期|生产|制造日期|出厂日期|MFG|MFD|PD`
  - 保质期：`保质期|保存期|保质期限`（“见包装/见瓶身”忽略）
  - 到期：`有效期至|有效期|失效日期|到期日|限用日期|保质期至|EXP|BB|Best before|请于…前食用`
  - 忽略整行：`批号|生产批号|产品批号|批次|LOT|批准文号|国药准字`（避免把批号 20250312 当日期）
- **日期格式**（年份限 2000–2045，月日校验合法）：
  - `2025.03.12`、`2025-03-12`、`2025/03/12`、`2025年3月12日`
  - `20250312`（8 位）
  - `25/03/12`、`25.03.12`（2 位年→20YY，按年/月/日，**标黄**，Q-13）
  - `03/04`（无年份→按月/日，年份取最合理的一年：生产日期取不晚于今天的最近一年，到期日取不早于今天的最近一年，**标黄**）
  - 只到年月（C-03）：`EXP 03/2027`、`03/2027`、`3/2027`（MM/YYYY）、`2027.03`、`2027-03`、`2027/03`（YYYY.MM）、`2027年3月`、`2027年03月`（YYYY年M月）→ **该月最后一天**，平年 2 月为 28 日、闰年 29 日（`03/2027`→2027-03-31，`02/2028`→2028-02-29，`02/2027`→2027-02-28）。一律**标黄**，确认页注明“包装只标到月，已按月末计算，可改”
  - `MM/YYYY` 与 `DD/MM`、`MM/DD` 的区分：斜杠后是 4 位数且在年份范围内，就按 MM/YYYY
- **保质期**：`12个月|18个月|365天|2年|两年|半年|24 months`，单位天/月/年。
- **配对**：同一行或相邻行最近的标签决定日期的含义；无标签的唯一日期不自动认定，确认页让用户选“这是生产日期/这是到期日”。
- **计算**（Q-11/Q-12/Q-14，`:core.DateMath`）：
  - `到期日 = 生产日期 + 保质期`；加天按日历日；加月按日历月，目标月没有该日取月末（1/31 + 1 个月 = 2/28 或 2/29）；加年遇 2/29 落在非闰年取 2/28。
  - 不做“减 1 天”。
  - 同时识别到明示到期日和生产日期+保质期：两者一致直接用；不一致时以明示到期日为准并标黄。
- **合理性检查**：生产日期不晚于今天 + 1 天；到期日晚于生产日期；保质期在 1 天到 10 年之间（Q-15）。不通过就降为“未识别”。

### 3.4 测试集（对应 Q-16）
- Quinn 准备 10 张（清晰 5、不清晰 5），清晰的 5 张至少 4 张正确，不清晰的必须落到手选页。
- 建议 Kai 自测再扩充到约 20 张：牛奶盒喷码、酸奶杯盖、方便面、酱油瓶颈喷码、罐头底、药盒（有效期至/批号/生产日期同框）、药板铝箔、进口食品中文贴标、瓶盖内印、反光/模糊负样本。
- 每张照片的 OCR 原文存成 `testdata/ocr/*.txt` + 期望结果 JSON，解析器回归测试只跑文本，不需要图片，云端代理本地就能跑。
- 照片本身不提交到仓库（可能拍到住址等），由 Quinn 保管。

---

## 4. 条码查名

| 来源 | 费用 | 中国条码（690–699）覆盖 | 用法 |
|---|---|---|---|
| Open Food Facts | 免费，开放数据；读商品接口限 15 次/分钟/IP（[API 文档](https://openfoodfacts.github.io/openfoodfacts-server/api/)） | 偏低。本次抽查 4 个常见国产条码，3 个查到，其中只有 1 个是中文名，其余是外文或拼写不全；总量待确认 | P0：服务端代理 + 全局缓存（缓存命中不再请求），超时 3 秒 |
| 中国物品编码中心（中国商品信息服务平台） | 网站可人工查询；API 是否对个人开放、价格待确认 | 高（官方库） | 以后可选 |
| 第三方付费条码 API（如阿里云市场上的条码查询服务） | 约每次几分钱（待确认） | 较高 | 以后可选，需要 dai damy 付费 |
| 家庭共享缓存（P2） | 0 | 家里买过一次就有 | 家庭成员手填过的“条码→名称+默认分类”，下次直接带出（Q-35） |

- **扫码**：ML Kit 条码扫描打包版（`com.google.mlkit:barcode-scanning`，不依赖 GMS），支持 EAN-13/EAN-8/Code128/DataMatrix。
- **查询顺序**：家庭缓存（P2）→ 全局缓存 → Open Food Facts → 查不到就手填。手填的名称在 P2 写回家庭缓存。
- **OFF 要求**：请求带自定义 User-Agent；服务端统一出口，单 IP 限额对家庭规模足够。
- **药品**：药盒常同时有商品条码（69 开头 EAN-13）和 20 位**药品追溯码**（Code128，每盒唯一，前 7 位是药品标识码，待确认）。追溯码没有公开免费查询接口（码上放心等平台只供 App 查询，待确认）。
  - P0：识别到 20 位追溯码时提示“药品追溯码暂不能查名，请手填”。
  - P2：家庭缓存按追溯码前 7 位记“药品标识→名称”，同一种药第二盒可带出（前 7 位规则待确认）。

---

## 5. 提醒与推送

### 5.1 P0 推荐：手机本地定时通知（不需要任何账号）
提醒是“每天一次的汇总”，数据已经同步在手机里，不需要服务器实时推。

- **调度**（安卓）：
  - 每天在本人 `notify_time`（默认 9:00，手机本地时区，Q-18）用 `AlarmManager` 唤醒：有精确闹钟权限时用精确闹钟（`setExactAndAllowWhileIdle`），否则用 `setWindow` 9:00–9:10 时间窗。
  - Android 14 起新装应用默认不授予 `SCHEDULE_EXACT_ALARM`（[Android 14 行为变更](https://developer.android.com/about/versions/14/changes/schedule-exact-alarms)，本次未逐页核对）。App 在提示条里引导用户去设置授予“闹钟和提醒”权限。
  - **验收口径（T-11，Damy 决定）**：验收用机必须授予精确闹钟权限，REM-12 的“通知点前一分钟不发、到点或其后一分钟内已发”按精确闹钟判定；10 分钟窗口只是未授权时的体验兜底，不作为 REM-12 的通过标准。
  - 闹钟触发：先尝试 5 秒内 `sync` 一次，失败就用本地缓存 → 按 5.2 计算 → 有内容才发一条汇总通知 → 排下一次。
  - **重排触发（T-10）**：本机任何影响提醒集合的成功写入后立即重排，包括新建、改到期日/生产日期/保质期/档位/开封信息、批量标记已用完/已处理、撤销、恢复、删除（在库或历史）、改 `notify_time`；以及收到 WS `changed` 或任何一次 `/sync` 拉取后、开机（`BOOT_COMPLETED`）、进程启动、系统时间或时区变化（`TIME_SET`/`TIMEZONE_CHANGED`）、被移出家庭（清空并取消全部闹钟）。重排在本地写入提交后同步执行，不等下一次同步。
  - 另有 WorkManager 一次性补偿：排在当天提醒时间 15 分钟之后。跑的时候如果今天的汇总还没发，并且当地时间还没过 23:59，就补发一次，上午或下午的提醒时间都一样。跑完再排下一天。过了当天不再补发，第二天按提醒时间正常发。
- **点通知** → 打开“提醒清单”（已过期 / 7 天内 / 30 天内 / 更远，按到期日排序，已过期置顶，Q-20/Q-22）。
- **权限**（Q-21）：
  - Android 13+ 要申请 `POST_NOTIFICATIONS`。未授权时首页顶部常驻提示条，可跳系统设置。
  - 同一提示条也引导“允许自启动/后台运行/关闭电池优化”，国产 ROM 需要。
- **验收**（PRD 验收第 3 条）：
  - debug 包有“模拟今天日期”和“立即生成今日汇总”入口，release 不含。**“立即生成今日汇总”是 AC-03 的正式验收方式**（Damy 决定）；改系统时间可作补充。
  - 客户端测试时钟（T-15）：`:core` 的 `DateMath`/`ReminderRules` 所有函数都显式接收 `today: LocalDate`，由 App 的 `TodayProvider` 统一提供。debug 的“模拟今天”只改这个 Provider，清单分组、颜色、提醒清单和通知内容用的是同一个“今天”，不会不一致。release 里 `TodayProvider` 固定取系统本地日期，模拟入口在 `debug` 源集，发布闸检查。
  - 多设备（T-16，Damy 决定）：同一账号多台手机共享一个 `notify_time`，每台手机按**本机时区**的该时刻各发一次；两台手机在不同时区时会各收到一次，属预期行为。
  - 这个方案**不依赖厂商推送账号**，Damy 已同意 P0 用本地通知（PRD v0.3），真机通知照常验收。只有 Mate 70 Pro 若是 HarmonyOS 5 + 卓易通环境，后台闹钟可靠性要实测（风险 R1）。

### 5.2 提醒规则（`:core.ReminderRules`，安卓和网页颜色共用）
- `今天` = 手机本地日期（经 `TodayProvider` 注入，见 5.1）。`有效到期日 E` = `expiry_date`；药品有开封信息时取 `min(expiry_date, opened_date + after_open_days)`（Q-33，P1）。
- `剩余天数 d = E − 今天`（日历日）。分组和颜色（Q-17/Q-22/Q-32，PRD v0.3 定稿）：

| 条件 | 分组 | 颜色 |
|---|---|---|
| d < 0 | 已过期 | 红 |
| d = 0 | 今天到期（归入 7 天内） | 橙 |
| 1 ≤ d ≤ 7 | 7 天内（含今天，0–7 天） | 橙 |
| 8 ≤ d ≤ 30 | 30 天内 | 黄 |
| d > 30 | 更远 | 绿 |

- **当天触发**（只对 `status = in_stock` 且档位非空，Q-10/Q-19）：
  - 档位：`D5/D10/D30` 在 `E − N 天` 当天触发；`M2/M3` 在 `E − N 个月`（日历月，目标月无该日取月末）当天触发（Q-23）。
  - 到期当天 `E` 触发“今天到期”。
  - 已过期（C-02）：在本地日期 = `E + 1 + 7k`（k ≥ 0）当天触发，即过期第 1、8、15… 天，文案“已过期 1/8/15 天”。E 当天只走“今天到期”，不算已过期。提醒清单里已过期组置顶（Q-20）。
  - 档位清空：不触发任何通知，但仍出现在清单和颜色里（Q-19）。
  - 入库时已错过的档位不补发（PRD v0.3）：物品立即出现在提醒清单对应分组，只按下一个未到的触发点提醒（包括 E 当天和过期第 1/8/15… 天）。
- **汇总文案**按分组计数，不列物品名。只统计当天确实触发的在库物品。示例：“1 件已过期，3 件将在 7 天内到期。”已过期物品只在过期第 1、8、15… 天进入汇总（N=1+7k）。8–30 天写“N 件将在 8–30 天内到期”，更远写“N 件将在 30 天以后到期”。没有件数的分组不出现。

### 5.3 以后的服务器推送（可选，M5，看本地方案实测情况再定）

| 通道 | 费用/账号 | 说明 |
|---|---|---|
| 华为 Push Kit | 免费；需华为开发者联盟实名认证（个人可用人脸/银行卡/身份证），审核 1–2 个工作日（[FAQ](https://developer.huawei.com/consumer/en/doc/HMS-Plugin-Guides-V1/flutter-faq-0000001050296734-V1)、[实名认证](https://developer.huawei.com/consumer/cn/doc/doccenter-getting-started/hdc-verification-faq-0000002752607795)） | 要申请消息自分类，否则按营销消息每天每设备 2–5 条；卓易通里的 APK 能否收到 HMS 推送待确认 |
| 小米/OPPO/vivo/荣耀 | 各自开发者账号、实名，免费（待确认）；部分要求上架其应用商店才有完整额度（如 OPPO 非官方渠道下载的应用不提供公信消息） | 小米要求 2026-12-31 前完成私信模板接入（[个推文档](https://docs.getui.com/getui/mobile/vendor/qps/)） |
| 个推（聚合） | 免费版可用厂商通道；VIP 1 万日活以内 3000 元/月（[计费说明](https://docs.getui.com/getui/billing/rules/)） | 仍需上面各厂商账号 |
| 极光（聚合） | 免费版每次请求走厂商通道的用户最多 30%（[极光文档](https://docs.jiguang.cn/jpush/client/Android/android_3rd_qps)） | 同上 |

结论：聚合平台省接入工作，但不省厂商账号；家庭自用只有几台手机，直接接华为 Push Kit（家里主力机）最划算。仅在本地通知实测不可靠时才做。

---

## 6. 安卓端结构
- Kotlin + Compose + Navigation 单 Activity；minSdk 26、targetSdk 35（同对讲机）。
- 模块：`:app`（UI）、`:data`（Room 缓存、OkHttp REST/WS、同步引擎）、`:capture`（CameraX + ML Kit 扫码/OCR）、`:reminder`（闹钟/WorkManager/通知）、依赖 `:core`。
- 页面：登录 → 创建/加入家庭 → 清单（搜索、长按多选、批量栏、5 秒撤销条，Q-25）→ 物品详情/编辑 → 入库（扫码 → 拍照识别 → 确认页 / 手选页）→ 提醒清单 → 历史（单条/多选恢复；单条/多选永久删除，C-05）→ 家庭管理（所有成员可看、复制、分享邀请码；管理员可重置邀请码、改家庭名、移除成员、在成员行“设为管理员”并二次确认，C-04/v0.3.1）→ 设置（提醒时间）。具体流程和视觉以 Mia 的稿为准。
- 必填校验（Q-08）：名称 + 到期日（或可推算的保质期），其余有默认值。
- 删除：在库清单和历史页都可删，二次确认，文案写明“永久删除，不可恢复；想留记录请用‘已用完/已处理’”（Q-24/C-05）。
- 长度校验（v0.3.1）：家庭名 2–20 字，物品名 ≤ 40 字，备注 ≤ 200 字，自定义位置 ≤ 12 字；客户端和服务端都校验。
- 相机权限文案（v0.3.1）：“需要使用相机来扫描条形码和拍摄包装上的日期，照片只用于识别和保存到你的家庭清单。”拒绝后可手动填写。
- 管理员退出：还有其他成员时，“退出家庭”提示先转让管理员，不发请求。
- 构建：debug APK 由云端代理本地构建；不配 release 签名；服务地址等构建时注入，不进仓库。出包前先告诉 Damy。

## 7. 网页端结构
- React 18 + TypeScript + Vite + TanStack Query + Ant Design（中文语言包）。构建产物是静态文件，随服务端发布包一起放到 `C:\pantry\web`，由 `pantry-caddy` 托管：`/` 静态、`/api` 和 `/ws` 反代到 `127.0.0.1:18280`。同源，不需要 CORS。
- 功能（PRD P0 网页）：登录、清单（表格、搜索、筛选、多选批量标记/删除、撤销条）、编辑、手填新增（手选保质期/到期日，用 TS 版 `DateMath`）、历史（恢复；单条/多选永久删除，二次确认，C-05）、提醒清单视图、照片查看（Q-09 不上传）、家庭管理（所有成员可看/复制邀请码，管理员操作同安卓）。
- 网页的分组和颜色用 TS 版 `ReminderRules`，同样显式传入 `today`。开发构建可用 `?today=YYYY-MM-DD` 模拟今天（T-15），生产构建里由 `import.meta.env.DEV` 剔除，发布闸检查。
- 同步：同样 WS + 5 秒轮询兜底；不做离线。
- 服务器上不装 Node，网页在代理环境构建好再打包。

---

## 8. 仓库计划
- 仓库：**公开**仓库 `damydai2006-stack/pantry-keeper`（dai damy 已建）。公开仓库红线：服务器 IP、测试码、白名单号码、任何口令和密钥都不得进入仓库或提交历史；配置一律走服务器或代理本机的 env 文件，`.gitignore` 排除。
- 目录：

```
core/            纯 Kotlin：DateMath、ReminderRules、DateParser、DTO（KMP 结构，先只有 jvm 目标）
server/          Ktor 服务端、Flyway 迁移
android/         app / data / capture / reminder
web/             React + TS
testdata/        date-cases.json（core 与 web 共用测试向量）、ocr/*.txt 文本夹具
deploy/windows/  install / upgrade / uninstall / status .ps1（全部参数化）、WinSW xml 模板、Caddyfile 模板
scripts/         check-all.sh（gradle check + npm test）、release-gate.sh（含 debug 入口、测试时钟、密钥/地址扫描）
docs/            TECH.md、API.md、DEPLOY.md
.env.example     只有变量名和示例值，没有真实值
```

- 流程：每项工作开 `feat/*` 分支 → PR → Quinn 审 → dai damy 同意后才合并 main。PR 开出来告诉 Damy 一次。
- 没有 GitHub Actions：PR 描述里贴代理本地跑的 `scripts/check-all.sh` 和 `release-gate.sh` 输出（小样本自测）。
- 不提交：服务器地址、证书私钥、根证书、口令、测试码、白名单号码、测试令牌、真实照片。`.gitignore` 覆盖 `server.env`、`*.env`（`.env.example` 除外）、`local.properties`、`*.jks`。每个 PR 提交前跑密钥/IP 扫描。

---

## 9. 里程碑与工作量（本期不含 iPhone）

“次”= 一次云端代理运行。只有一个名额，与对讲机共用，对讲机阻塞问题优先，所以日历时间按“有空档就跑”估。

| 里程碑 | 范围 | PR 拆分 | 工作量 | 依赖 dai damy |
|---|---|---|---|---|
| **M1 服务端 + 安卓 P0** | 登录、家庭（角色/邀请/退出/移除）、物品增删改、批量+撤销、历史、同步、离线只读、扫码查名、OCR+手选、本地提醒、Windows 安装脚本 | ① `feat/skeleton-core`：仓库骨架、`:core` 日期/提醒规则+测试向量、服务端鉴权/家庭、Flyway、参数化安装脚本、`.env.example`（1.5 次）② `feat/items-sync`：物品接口、按字段后写、批量事务/撤销、墓碑、`/sync`、WS、照片（1.5 次）③ `feat/android-core`：登录、家庭、清单、编辑、多选批量、历史、离线只读、同步（2 次）④ `feat/android-capture`：扫码+OFF 代理、OCR+解析器+确认/手选页、Mate 70 Pro 调试页（1.5 次）⑤ `feat/android-reminder`：本地提醒、权限提示条、提醒清单、debug 调时入口、发布闸（1 次） | **约 7.5 次，日历 2–3 周** | 生产安装时机、9443/80 防火墙放行、`pg_hba`（第 11 节） |
| **M2 网页 P0** | 第 7 节全部 | `feat/web`（2 次）+ `feat/deploy-web`（Caddy 静态托管、证书方案落地，0.5 次） | **约 2.5 次，约 1 周** | 无（证书已定方案 B；需第 11 节防火墙） |
| **M3 P1** | 分类/位置/状态筛选（P0 已有搜索）、颜色标识（规则 M1 已在 core，UI 补全）、药品开封有效期、CSV 导出 | `feat/p1` | **约 1.5 次** | 无 |
| **M4 P2** | 家庭条码缓存、追溯码前缀缓存 | `feat/barcode-cache` | **约 0.5–1 次** | 是否买付费条码 API（可不买） |
| **M5 可选** | 服务器推送（华为 Push Kit 起步） | 视实测 | 约 1.5 次 | 华为开发者实名；其他厂商账号 |
| 以后 | iPhone（本期不做） | — | 约 4–6 次（粗估） | 苹果账号 99 美元/年、Mac |

**验收对照（PRD 第一版验收）**

| PRD 验收 | 由哪里实现 | 怎么验 |
|---|---|---|
| A 入库后 B 手机和网页 10 秒内自动可见（C-01：下拉刷新才看到算不通过） | 2.6 WS 通知 + 5 秒轮询兜底 | **前提：B 的 App 在前台打开清单、网页标签页在前台**（T-18）。M1 两台安卓；M2 加 Chrome。计时，断开 WS 再测轮询路径 |
| ≥10 张照片，清晰的 5 张至少 4 张对，失败落手选 | 3.2/3.3 + 3.4 测试集 | Quinn 的 10 张；解析器文本回归 |
| 设置档位后当天收到通知，点开进提醒清单且正确 | 5.1/5.2 本地提醒 | 正式方式：debug 包“模拟今天”+“立即生成今日汇总”（T-11）；REM-12 边界用例在已授予精确闹钟的验收机上测 |
| 多选 5 件标记已用完 → 移除、历史可见、可恢复；删除二次确认 | 2.5 批量单事务 + 撤销 + 历史 + 历史永久删除 | 安卓、网页各一遍 |
| 服务器时间相关（会话滑动、邀请码 7 天、空家庭 30 天删除） | 2.9 测试时钟 | 只在测试配置的实例上偏移时钟验证；生产配置拒绝开启 |
| 安卓真机（含 Mate 70 Pro）、Chrome 各一轮冒烟 | M1 末、M2 末 | Quinn 冒烟清单 |
| 与对讲机隔离，重启/升级不影响对讲 | 2.2 | 部署后：重启 `pantry-server`/`pantry-caddy`、跑一次升级+回滚，期间对讲机两台手机持续对讲、`ptt-*` 健康检查保持 200；只读核对 `pantry` 角色在 `ptt` 库无表权限（T-17） |

---

## 10. v0.2 规则 → 设计对照

| 规则 | 设计落点 |
|---|---|
| Q-01 测试码沿用、配置独立 | 2.7：独立 `server.env` 的 `AUTH_MODE/AUTH_TEST_CODE/AUTH_TEST_PHONES` |
| Q-02 多端同时在线 | `sessions` 不互踢；WS 推给同账号所有连接 |
| Q-03 一人一家庭 | `one_active_family` 部分唯一索引；加入新家庭前须先退出 |
| Q-04 邀请码 6 位/7 天/不限次/可重置 | `families.invite_code/invite_expires_at`；字母表去掉 0/O/1/I；加入接口限频（每人每分钟 5 次）防猜码；仅管理员重置 |
| Q-05 + v0.3 空家庭 | 最后一人退出：写 `empty_since`、邀请码置空；期间任何加入都拒绝；`pantry-purge` 每日任务 30 天后删家庭、物品、照片 |
| Q-06 退出后可再加入 | 家庭仍有成员时，凭有效邀请码加入，新增一条 `family_members` 记录（主键含 `joined_at`）；家庭已空则不可加入（v0.3） |
| Q-07 角色、移除即 403 | `admin_user_id`、管理员接口；每请求查成员关系；WS 推 `removed_from_family` 后断开；客户端清缓存。管理员退出：有其他成员时 409 `admin_must_transfer`，须先转让；仅剩一人可直接退出（v0.3） |
| Q-08 必填与默认值 | `items` 约束 + `:core` 校验；`category` 默认 food，`quantity` 默认 1 |
| Q-09 网页看照片不传 | 上传接口只接受 `client_kind=app` 会话 |
| Q-10 两种状态都不提醒、进历史、可恢复 | `status` 枚举；`ReminderRules` 只看 `in_stock`；`restore` 批量动作 |
| Q-11/Q-12 日历月、月末、闰年 | `:core.DateMath.plusMonths/plusYears` 截到月末；测试向量含 1/31、2/29、12/31 |
| Q-13 歧义格式标黄 | `DateParser` 歧义标记 → 确认页标黄，默认年/月/日、月/日 |
| Q-14 起算日 | `expiry_basis` 区分“从生产日期/从今天”，界面写明 |
| Q-15 自定义 1 天～10 年 | 手选页和服务端都校验 |
| Q-16 OCR 样本与通过线 | 3.4 |
| Q-17 当天算今天到期 | `d = E − 今天`，d=0 为“今天到期”，d<0 才是已过期 |
| Q-18 提醒时间每人可改、本地时区 | `user_settings.notify_time`；按手机本地时区排闹钟；同账号多台手机各按本机时区各发一次（5.1）；不处理夏令时 |
| Q-19 可清空档位 | `reminder_tiers` 允许空数组，不触发但仍显示颜色 |
| Q-20 + C-02 过期置顶、过期第 1/8/15… 天提醒 | 5.2 触发规则；提醒清单已过期组置顶 |
| Q-21 无通知权限的提示条 | 5.1 首页常驻提示条 |
| Q-22 + v0.3 分组 | 已过期 / 7 天内（0–7，含今天）/ 30 天内（8–30）/ 更远（>30）；5.2 分组表 |
| Q-23 提前天数按日历日 | D 档减日历日，M 档减日历月 |
| Q-24 删除永久、二次确认 | 硬删除 + 照片删除 + 墓碑只供同步（在库和历史都可删） |
| Q-25 5 秒撤销 | `batch_ops.undo_token`，客户端 5 秒撤销条，服务端宽限 60 秒；撤销跳过被他人改过的条目并提示数量（2.6 第 8 条） |
| Q-26 批量单事务 | `POST /items/batch` 一个事务，任何一条失败（含不属于当前家庭）全部回滚；撤销是独立补偿事务 |
| Q-27 按字段后写为准 + 提示 + 删除优先 | `field_meta` + `base_rev`，响应 `overwritten`；已删返回 410 |
| Q-28 恢复后档位不变 | 恢复只改 `status` |
| Q-29 离线只读 | Room 快照只读，修改入口置灰，无写队列 |
| Q-30 新成员看到全部历史 | 历史按 `family_id` 查，不按加入时间过滤 |
| Q-31 + v0.3 会话 | `sessions.client_kind`；每次认证请求把 `expires_at` 滑到本次 + 30 天，App/网页各自滑动（2.7） |
| Q-32 颜色 | 5.2 分组表 |
| Q-33 药品取较早日期 | `有效到期日 = min(到期日, 开封日 + 天数)`（P1） |
| Q-34 CSV | `GET /export.csv`：在库 + 历史，不含照片，任何成员可用（P1） |
| Q-35 条码库只共享名称+分类 | `barcode_cache` 只有这两个业务字段（P2） |
| Q-36 + v0.3 推送 | P0 本地定时通知，不需要账号，真机照常验收；厂商推送放 M5 |
| Q-37 数量只是数字 | `quantity int`，无单位，不联动状态 |
| Q-38 + v0.3 改名仅管理员、不能解散、可重名 | `PATCH /families/current` 仅管理员；无解散接口；物品 `name` 无唯一约束 |
| Q-39 / C-01 10 秒自动可见 | WS + 5 秒轮询兜底；仅对端前台（2.6 第 3 条） |
| Q-40 同一账号体系、网页须登录 | 同一 `users`；网页所有页面先过登录；证书用 Let’s Encrypt IP 证书（2.8） |
| C-02 过期提醒第 1/8/15… 天 | 5.2：本地日期 = E+1+7k（k≥0） |
| C-03 只有年月的 EXP | 3.3：取当月最后一天（含闰年），标黄可改 |
| C-04 邀请码所有成员可看可分享、仅管理员重置 | 2.5 `GET /families/current`；第 6/7 节 |
| C-05 历史可永久删除 | 2.5 `batch delete` 适用于历史；2.6 第 5 条；第 6/7 节 |
| v0.3 错过档位不补发 | 5.2 |
| v0.3.1 长度、相机文案、设为管理员 | 2.4 约束；第 6 节 |

---

## 11. 需要 dai damy 决定或付费的事项

| # | 事项 | 选项 / 成本 | 谁办 | 何时需要 | 影响里程碑 |
|---|---|---|---|---|---|
| 1 | 生产 Windows 服务器上安装保质期服务的时机 | 对讲机演练结束后；安装只新增 `pantry-*` 服务、`C:\pantry`、`pantry` 库 | dai damy 同意，Damy 远程执行 | M1 末联调前 | M1 |
| 2 | 防火墙放行新端口 | 9443（服务）+ 80（Let’s Encrypt HTTP-01 验证，方案 B 需要） | dai damy | M1 末 | M1/M2 |
| 3 | 如需加 `pg_hba.conf` 一行并 `pg_reload_conf()`（2.2 第 4 条） | 不重启、不断连，但属于改共享配置 | dai damy 同意 | M1 部署时（要先只读查看现有规则） | M1 |
| 4 | 照片备份位置 | 系统盘紧张，照片备份放哪里（数据库 `pg_dump` 很小） | dai damy | M1 部署前 | M1 |
| 5 | 服务器推送（可选） | 华为开发者实名（免费）；其他厂商账号；或个推/极光免费版 | dai damy 本人实名 | 本地通知实测不可靠时 | M5 |
| 6 | 付费条码 API（可选） | 每次约几分钱，价格待确认 | dai damy | 免费源覆盖太差时 | M4 之后 |
| 未来 | iPhone | 苹果开发者 99 美元/年 + Mac 或云 Mac（Codemagic 个人每月 500 分钟免费） | dai damy | 决定做 iPhone 时 | 以后 |

已决、不再列入：仓库（公开 `pantry-keeper`）、证书（方案 B）、登录（测试码 + 白名单）、照片上限（每家庭 1 GB + 磁盘剩余 1.5 GB 保护）、REVOKE（本期不做，见 R11）。产品问题已全部由 PRD v0.3/v0.3.1 定稿。

---

## 12. 风险

| # | 风险 | 影响 | 应对 |
|---|---|---|---|
| R1 | Mate 70 Pro 若是 HarmonyOS 5，APK 跑在卓易通兼容层；卓易通官方说明后台通知需要手动开启并保持后台，闹钟类可能受限（[卓易通常见问题](https://droitong.com/CommonQues.html)）；ML Kit/CameraX 在卓易通中的表现待实测 | 提醒和 OCR 验收 | 先确认该机系统版本；M1 第 ④ 个 PR 带调试页，第一时间实测 OCR、扫码、闹钟；不可靠时在验收里单列，并评估华为推送或鸿蒙原生（另立项） |
| R2 | 点阵喷码 OCR 准确率低 | Q-16 通过线 | 取景框 + 手动框选 + 预处理；不行换 PaddleOCR 移动版；失败一律落手选 |
| R3 | 国产 ROM 杀后台导致 9:00 提醒延迟或不发 | 体验 | 时间窗闹钟 + 当天 23:59 前一次性补发 + 引导开自启动；打开 App 时提醒清单永远是最新的 |
| R4 | Let’s Encrypt IP 证书 6 天有效，续期失败即中断；Caddy 对 IP 证书的支持待验证 | App 和网页都连不上 | 每日续期 + 剩余 3 天告警；Caddy 不支持则用 Certbot 签发、Caddy 加载文件（2.8） |
| R5 | 服务器磁盘只剩约 3 GB，而单家庭上限是 1 GB | 照片撑满影响对讲机 | 剩余低于 1.5 GB 拒绝上传这道闸始终生效；部署前复核可用空间 |
| R6 | 共用 PostgreSQL | 误操作影响对讲 | 2.2 脚本硬约束；连接上限 + 语句超时；安装脚本在代理环境先用 PG 容器跑完整流程 |
| R7 | 云端代理名额与对讲机共用 | 进度 | PR 拆小、每次代理把要改的一次写全；对讲机阻塞时暂停 |
| R8 | Open Food Facts 中文覆盖低，国内访问可能慢 | 扫码带不出名 | 服务端代理 + 3 秒超时 + 缓存；手填兜底；P2 家庭缓存 |
| R9 | 小米私信模板 2026-12-31 截止等厂商政策变化 | 只影响以后的服务器推送 | P0 不依赖厂商推送 |
| R10 | 测试码误开在生产 | 安全 | 白名单 + WARN + `/readyz` 标记；客户端调试入口只在 debug 源集，发布闸检查 |
| R11 | `pantry` 角色仍可 CONNECT 到 `ptt` 库（PUBLIC 默认权限），本期不 REVOKE（Damy 决定） | 已知残留面；无表权限，读不到对讲业务数据 | 验收只读核对无表权限；以后 dai damy 同意再 REVOKE（改对讲机库权限） |

---

## 附：本文引用来源（2026-11-15 查询）
- ML Kit 文字识别（打包/非打包）：https://developers.google.com/ml-kit/vision/text-recognition/v2/android
- Let’s Encrypt IP 证书正式可用：https://letsencrypt.org/2026/01/15/6day-and-ip-general-availability；profiles：https://letsencrypt.org/docs/profiles/
- Open Food Facts API 限额：https://openfoodfacts.github.io/openfoodfacts-server/api/
- 华为 Push Kit FAQ（免费、限额）：https://developer.huawei.com/consumer/en/doc/HMS-Plugin-Guides-V1/flutter-faq-0000001050296734-V1
- 华为开发者实名认证：https://developer.huawei.com/consumer/cn/doc/doccenter-getting-started/hdc-verification-faq-0000002752607795
- 个推计费：https://docs.getui.com/getui/billing/rules/；厂商限额：https://docs.getui.com/getui/mobile/vendor/qps/
- 极光厂商通道限制：https://docs.jiguang.cn/jpush/client/Android/android_3rd_qps
- 卓易通常见问题：https://droitong.com/CommonQues.html
- Flutter Web 字体 CDN 问题：https://github.com/flutter/flutter/issues/163554
- Codemagic 价格：https://codemagic.io/pricing/
- 未逐页核对（按已知信息引用，标待确认）：Apple 开发者计划价格、Android 14 精确闹钟变更页、ICP 备案细则
