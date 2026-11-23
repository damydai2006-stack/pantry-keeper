# API（PR1）

前缀 `/api/v1`。JSON 字段使用 snake_case。错误体只有 `error` 和 `message`。

时间全部来自注入的 `Clock`。`PANTRY_PROFILE=prod` 时如果测试时钟被打开，进程拒绝启动，且不注册 `/test/*`。

## 登录

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/auth/code` | 手机号。测试模式只为白名单记下固定测试码，响应不包含验证码 |
| POST | `/auth/login` | `phone`、`code`、`client_kind`（`app` 或 `web`）。App 返回 Bearer。网页设置 `HttpOnly`、`Secure`、`SameSite=Strict` Cookie，响应不带令牌 |
| POST | `/auth/logout` | 只注销当前会话 |

`AUTH_MODE` 不是 `test` 时，上述接口返回 403 `auth_disabled`。验证码 10 分钟内有效，连续 5 次错误后作废。未在白名单中的号码登录失败，响应不包含家庭数据。

会话 30 天，从最近一次使用滑动。同一次会话 1 分钟内最多写一次库。App 与网页各自滑动，互不踢出。Cookie 写请求还要带 `X-Pantry-Request: 1`。

## 本人与家庭

| 方法 | 路径 | 说明 |
|---|---|---|
| PATCH | `/me` | `{display_name}`。1–20 个字，按码点。家庭成员列表用这个名字 |
| GET | `/me` | 不含手机号。未加入家庭时 `family_id` 为空 |
| PUT | `/me/settings` | `notify_time`，`HH:mm` |
| POST | `/families` | 创建。名称 2–20 字。创建者是管理员，并得到 6 位、7 天邀请码 |
| POST | `/families/join` | `{code}`。已在其他家庭返回 409 `already_in_family`。空家庭、过期、错误码一律 404 `invite_invalid`，不区分原因 |
| POST | `/families/leave` | 普通成员直接退出。还有其他成员时管理员返回 409 `admin_must_transfer`，数据不变。只剩管理员一人时退出，家庭进入 30 天保留并立刻作废邀请码 |
| GET | `/families/current` | 所有在籍成员都能看到当前邀请码和过期时间。成员含 `display_name` 和 `joined_at`，客户端用显示名，不用用户 id |
| PATCH | `/families/current` | 仅管理员改名。其他人 403 `admin_only`，名称不变 |
| POST | `/families/current/invite/reset` | 仅管理员 |
| POST | `/families/current/admin` | `{user_id}`。对方成为管理员，自己变为普通成员 |
| DELETE | `/families/current/members/{uid}` | 仅管理员。被移除者下一次家庭请求 403 `not_in_family`，响应不含家庭内容 |

没有解散家庭的接口。

加入邀请码每人每分钟最多 5 次。

## 测试时钟

仅 `PANTRY_PROFILE=test` 且 `PANTRY_TEST_CLOCK=on`，并携带 `X-Pantry-Test-Token`：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/test/clock` | `{offset_seconds}`，相对进程基准时钟的偏移 |
| POST | `/test/purge-now` | 删除 `empty_since` 已满 30 天的家庭及其物品、照片记录 |

## 健康检查

`GET /healthz`、`GET /readyz`。`readyz` 标出 `test_code_mode` 和 `test_clock`，不返回验证码、白名单或偏移量。

## 物品、同步、照片、条码（PR2）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/items` | 新建。名称必填且不超过 40 字。到期日，或生产日期加保质期，或只有保质期（从 `client_today` 起算，缺省用服务器时钟的 UTC 日期）。分类默认 `food`，数量默认 1。自定义位置不超过 12 字，备注不超过 200 字。提醒档位可多选，默认 `D30`+`D5`，空数组表示不提醒。可带开封日期和开封后天数。客户端可带 `id`，重复提交返回已有行 |
| GET | `/items`、`/items/{id}` | 只读当前家庭。已删除且有墓碑时，`GET /items/{id}` 返回 410 `item_deleted`，文案含「已被某某删除」 |
| PATCH | `/items/{id}` | `{base_rev, fields}`。按字段后写为准。别人先改过的字段放进 `overwritten`，`message` 含「已被某某修改」 |
| POST | `/items/batch` | `used_up` / `discarded` / `restore` / `delete`。一个事务。任何一个 id 不属于当前家庭，整批 404，不做修改。删除对在库和历史都可用，写墓碑，不返回撤销令牌。其他操作返回 `undo_token`。`restore` 只把 `status` 改回 `in_stock`，不改 `expiry_date` 和 `reminder_tiers` |
| POST | `/items/batch/undo` | 独立事务。令牌 60 秒内有效（客户端按钮是 5 秒），且只能成功使用一次：用过后删除令牌，再次调用返回 404 `not_found`，物品状态和 rev 不变。超过 60 秒仍是 410 `undo_expired`。别人在这批之后改过状态，或物品已删除，就跳过，响应 `skipped` 和「N 件已被他人修改，未撤销」 |
| GET | `/sync?since=` | `since=0` 为当前快照。否则返回 `rev` 更大的物品和墓碑，并带上当前家庭和提醒时间。游标早于已清理的墓碑时 `reset: true`，调用方应再拉 `since=0` |
| GET | `/ws` 与 `/api/v1/ws` | 注册成功后先推 `{"t":"ready"}`，之后只推 `changed`、`removed_from_family`、`session_revoked`。写提交之后才推 `changed`。被移除的连接先收到通知再关掉，下一次请求 403。登出会删掉当前会话并立刻向该会话的连接推 `session_revoked` 后关闭；会话过期后同样断开，不再推 `changed`。同一用户的其他会话不受影响 |
| POST | `/items/{id}/photo` | 仅 App 会话。只收 JPEG / WEBP。先锁家庭并确认物品属于当前家庭，不属于则 404，此时不写盘。通过后才写文件。单张上限 `PANTRY_PHOTO_MAX_BYTES`。家庭合计默认 1 GB，磁盘剩余低于 `PANTRY_MIN_FREE_MB`（默认 1500）时 507 |
| GET | `/photos/{id}?thumb=1` | 先核对家庭再读文件。其他家庭 404，响应里没有图片字节。已无家庭则 403 |
| GET | `/barcodes/{code}` | 先查服务端缓存，再请求 Open Food Facts，超时默认 3 秒。失败时 `found: false`，客户端改手填。20 位追溯码直接 `reason: trace_code`。不需要密钥 |

物品 JSON 在状态变更后可带 `status_changed_at`（ISO-8601）。没有变更过状态时省略。历史页用它显示处理日期。

## 资源归属（给后续 PR）

`FamilyAccess` 要求资源的 `family_id` 等于调用者当前家庭。没有家庭时 403 `not_in_family`。其他家庭或未知 id 一律 404 `not_found`，响应体只有错误码，不含字段或字节。批量里只要有一个 id 不属于当前家庭，整批按未找到拒绝。

## 以后的批量撤销（T-14，本期不实现）

标记、删除仍是整批一个事务。撤销是另一个事务：只回滚这批之后没有被别人改过的条目，并在响应里给出跳过数量。
