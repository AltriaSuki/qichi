# 04 · 接口约定

> 第 1–3 阶段的接口已写成 `api/openapi.yaml`（OpenAPI 3.1），那是**唯一的契约来源**：本文件讲约定和全貌，字段细节以 yaml 为准。
> 后续阶段的接口先在本文件列出，到对应阶段再补进 yaml。

## 1. 通用约定

| 项 | 约定 |
|---|---|
| 地址 | `https://{你的域名}/api/v1/...` |
| 格式 | JSON，UTF-8；字段名 `camelCase`；时间 ISO-8601 UTC（`2026-09-21T11:30:00Z`）；纯日期 `2026-09-21` |
| 鉴权 | `Authorization: Bearer {accessToken}`；除注册、登录、刷新、健康检查、ICS 订阅外都需要 |
| 房间 | 房间内的资源都在 `/rooms/{roomId}/…` 下；服务端每次都校验当前用户是该房间成员，否则 **404**（不暴露房间是否存在） |
| 创建 | 请求体带客户端生成的 `id`（UUIDv7）。新建返回 **201**，同 id 已存在返回 **200** 和已有对象 |
| 修改 | `PATCH`，只发改动的字段；返回完整对象 |
| 删除 | `DELETE` = 软删除（进回收站），返回完整对象（带 `deletedAt`）；彻底删除走回收站接口 |
| 版本冲突 | 需要基线的写入（文稿、档案、留言编辑）带 `baseVersion`；不是最新 → **409**，`code = "conflict_version"`，响应里附最新版本号 |
| 分页 | 游标式：消息用 `beforeSeq`（指消息的 `createdSeq`），其它列表用 `cursor` + `limit`，响应带 `nextCursor` |
| 客户端信息 | 请求头 `X-Qichi-Client: android/{versionName}`，便于排查 |
| 大小限制 | 文字消息正文 ≤ 10,000 字；图片 ≤ 20MB；其它文件、EPUB、审稿文件 ≤ 100MB |

### 错误格式

一律 `Content-Type: application/problem+json`：

```json
{
  "type": "https://qichi.app/errors/conflict_version",
  "title": "文稿已有更新的版本",
  "status": 409,
  "code": "conflict_version",
  "detail": "当前最新版本是 v8，你基于 v7 保存",
  "latestVersion": 8
}
```

`code` 取值（客户端按它判断，不看 `title`）：

| code | HTTP | 含义 |
|---|---|---|
| `invalid_request` | 400 | 参数不合法，`errors` 字段列出具体字段 |
| `unauthorized` | 401 | 未登录或访问令牌过期（客户端应自动刷新一次后重试） |
| `forbidden` | 403 | 已登录但无权做这件事（如非作者撤回） |
| `not_found` | 404 | 资源不存在，或你不是该房间成员 |
| `conflict_version` | 409 | 基线落后 |
| `conflict_id` | 409 | 同 id 已存在但属于别人或别的房间 |
| `room_full` | 409 | 房间已有 2 人 |
| `username_taken` | 409 | 用户名已被注册 |
| `invite_invalid` | 400 | 邀请码不存在、已用或已过期 |
| `registration_closed` | 403 | 已有用户且未提供有效邀请码 |
| `payload_too_large` | 413 | 超过大小限制 |
| `unsupported_media_type` | 415 | 文件类型不支持 |
| `rate_limited` | 429 | 请求太频繁，看 `Retry-After` |
| `ai_unavailable` | 503 | AI 服务未配置或暂时不可用 |
| `ai_quota_exceeded` | 429 | 本月 AI 额度用完 |
| `internal_error` | 500 | 服务端未预料的错误（日志里有详情，响应里不含堆栈） |

### 令牌

- 登录/注册返回 `{accessToken, accessTokenExpiresAt, refreshToken, refreshTokenExpiresAt}`
- 访问令牌 15 分钟；刷新令牌 60 天，每次刷新都会换新，旧的立即作废；同一个旧刷新令牌被用第二次 → 该设备所有令牌作废（防盗用）

## 2. 实时通道（WebSocket）

`GET /api/v1/ws`，握手时带 `Authorization` 头。

服务端 → 客户端：

```json
{"type":"hello","userId":"…","rooms":[{"roomId":"…","lastSeq":180}]}
{"type":"changed","roomId":"…","seq":181}
{"type":"ai.done","roomId":"…","jobId":"…","status":"done"}
```

- `changed` 只是提示，客户端收到后调用 `sync` 拉取，不在通道里传实体内容。
- 客户端 → 服务端不发业务消息；心跳用 WebSocket 的 ping/pong 帧（30 秒）。
- **没有**「正在输入」「在线」等事件（产品明确不做）。

## 3. AI 请求的统一模式

1. 客户端 `POST …/ai/{kind}`，带 `jobId`（客户端生成）和参数 → 服务端立即返回 **202** `{jobId, status:"queued"}`
2. 服务端在后台调用大模型，结果写进对应实体（例如一条 `kind = "ai"` 的消息、一批待采纳的问题、一份总结）
3. 通过 WebSocket 发 `ai.done`，并照常产生 `changed`；客户端拉取同步即可看到结果
4. 失败时 `ai_jobs.status = failed`，客户端在原位置显示「没有得到回答，重试」

AI 请求**不进离线发件箱**；离线时按钮置灰。

## 4. 接口目录

小标题里的 P1…P7 对应 `08-roadmap.md` 的阶段。第 1–3 阶段的详细定义见 `api/openapi.yaml`。

### 系统与账号（P1）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/health` | 健康检查，返回版本号 |
| POST | `/auth/register` | 注册。没有任何用户时可直接注册（第一个账号）；否则必须带有效 `inviteCode`，注册后自动加入该房间 |
| POST | `/auth/login` | 登录 |
| POST | `/auth/refresh` | 刷新令牌 |
| POST | `/auth/logout` | 作废当前刷新令牌 |
| GET | `/me` | 当前用户与所在房间列表 |
| PATCH | `/me` | 改显示名、头像、通知偏好 |
| POST | `/me/password` | 改密码（作废其它设备的登录） |
| GET | `/me/sessions` · DELETE `/me/sessions/{id}` | 登录设备管理（P7，「安全」页） |

### 房间与同步（P1）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/rooms` | 建房间，创建者为 owner |
| GET | `/rooms/{roomId}` | 房间与成员 |
| PATCH | `/rooms/{roomId}` | 房间名、头像、主视觉照片、纪念日、时区 |
| POST | `/rooms/{roomId}/invites` | 生成邀请码（7 天有效；房间满员时 409 `room_full`） |
| POST | `/invites/accept` | 已登录用户用邀请码加入房间 |
| GET | `/rooms/{roomId}/bootstrap` | 首次同步快照 |
| GET | `/rooms/{roomId}/sync?since=&limit=` | 增量同步 |
| GET | `/ws` | 实时通道 |

### 心情、待办、日程（P2）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/rooms/{roomId}/moods` | 记录心情 |
| DELETE | `/rooms/{roomId}/moods/{id}` | 删除（作者本人） |
| POST | `/rooms/{roomId}/moods/{moodId}/responses` | 回应：`here` / `hug` / `ready` |
| DELETE | `/rooms/{roomId}/mood-responses/{id}` | 收回回应 |
| POST | `/rooms/{roomId}/todos` | 新建待办（可带 `parentId`、`recurrence`） |
| PATCH | `/rooms/{roomId}/todos/{id}` | 修改 |
| POST | `/rooms/{roomId}/todos/{id}/complete` | 完成；重复待办需带 `nextId`，服务端据此生成下一次 |
| POST | `/rooms/{roomId}/todos/{id}/reopen` | 取消完成 |
| DELETE | `/rooms/{roomId}/todos/{id}` | 删除 |
| POST · PATCH · DELETE | `/rooms/{roomId}/events[/{id}]` | 日程 |

### 聊天、文件、回收站、推送（P3）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/rooms/{roomId}/messages?beforeSeq=&limit=` | 往上翻历史 |
| POST | `/rooms/{roomId}/messages` | 发消息（text / image / file；可带 `replyToId`） |
| POST | `/rooms/{roomId}/messages/{id}/retract` | 撤回（仅作者） |
| DELETE | `/rooms/{roomId}/messages/{id}` | 删除进回收站 |
| GET | `/rooms/{roomId}/messages/search?q=` | 搜索（不含撤回、已删除） |
| PUT | `/rooms/{roomId}/read-marker` | 推进自己的未读位置 |
| POST | `/rooms/{roomId}/files` | 上传（multipart），返回文件元数据 |
| GET | `/files/{fileId}` · `/files/{fileId}/thumb?w=` | 下载 / 缩略图（需鉴权，支持 Range） |
| GET | `/rooms/{roomId}/trash` | 回收站列表 |
| POST | `/rooms/{roomId}/trash/{type}/{id}/restore` | 恢复 |
| DELETE | `/rooms/{roomId}/trash/{type}/{id}` | 彻底删除 |
| POST · DELETE | `/devices[/{id}]` | 注册 / 注销推送设备 |

### 问答、计划、日历扩展、灵感、AI（P4）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/rooms/{roomId}/ai/chat` | 问 AI（聊天里）→ 202 |
| GET | `/rooms/{roomId}/ai/jobs/{jobId}` | 查询 AI 任务状态 |
| GET | `/me/ai-usage?month=` | 我发起的 AI 使用 |
| GET | `/rooms/{roomId}/qna/today` | 今日一问：题目、我的答案、对方是否已确认（揭晓前不含对方答案） |
| PUT | `/rooms/{roomId}/qna/rounds/{roundId}/answer` | 写/改我的答案（确认前可改） |
| POST | `/rooms/{roomId}/qna/rounds/{roundId}/confirm` | 确认；双方都确认后服务端写 `revealed_at` |
| GET | `/rooms/{roomId}/questions?status=`（adopted 题库 / suggested 待采纳） | 题库 / 待采纳建议 |
| POST | `/rooms/{roomId}/questions` | 自己出题 |
| POST | `/rooms/{roomId}/ai/question-suggest` | 让 AI 根据共同历史出题 → 202 |
| POST | `/rooms/{roomId}/questions/{id}/adopt` | 采纳进题库 |
| DELETE | `/rooms/{roomId}/questions/{id}` | 软删除题目，进入回收站 |
| POST · PATCH · DELETE | `/rooms/{roomId}/plans[/{id}]` | 计划 |
| POST · PATCH · DELETE | `/rooms/{roomId}/plans/{planId}/stages[/{id}]`、`…/milestones[/{id}]` | 阶段、里程碑 |
| POST | `/rooms/{roomId}/plans/{planId}/logs` | 过程记录 |
| POST | `/rooms/{roomId}/plans/{planId}/complete` | 完成计划（带完成记录） |
| POST | `/rooms/{roomId}/calendar/import` | 导入 .ics（multipart） |
| GET | `/rooms/{roomId}/calendar/export.ics` | 导出 |
| POST | `/rooms/{roomId}/calendar/subscription` | 生成或重置只读订阅链接 |
| GET | `/ics/{token}.ics` | 只读订阅（令牌即凭证，无需登录） |
| POST · PATCH · DELETE | `/rooms/{roomId}/ideas[/{id}]` | 灵感 |

### 共同写作、留言（P5）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET · POST | `/rooms/{roomId}/documents` | 文稿列表 / 新建 |
| PATCH · DELETE | `/rooms/{roomId}/documents/{id}` | 改标题（标题不进版本）/ 删除进回收站（彻底删除时连同所有版本） |
| GET | `/rooms/{roomId}/documents/{id}/versions` | 版本列表（不含正文，版本号从大到小，`cursor` + `limit` 分页） |
| GET | `/rooms/{roomId}/documents/{id}/versions/{v}` | 某个版本的正文 |
| POST | `/rooms/{roomId}/documents/{id}/versions` | 保存新版本：`{id, baseVersion, body, restoredFromVersion?}`，新文稿的基线是 0；基线落后 409；同 `id` 重试返回已保存的版本 |
| GET · POST · PATCH · DELETE | `/rooms/{roomId}/board/topics[/{id}]` | 留言主题：列表（置顶在前，其余按最近留言）/ 新建 / 改标题与置顶 / 删除进回收站 |
| POST | `/rooms/{roomId}/board/topics/{topicId}/posts` | 发帖（可引用，摘录由服务端生成，原文之后修订也不变） |
| PATCH · DELETE | `/rooms/{roomId}/board/posts/{id}` | 修订（仅作者，带 `baseRevision`，落后 409）/ 删除进回收站 |
| GET | `/rooms/{roomId}/board/posts/{id}/revisions` | 修订历史（旧版本） |
| PUT · DELETE | `/rooms/{roomId}/board/posts/{id}/reactions/{kind}` | 喜欢 / 拥抱 / 支持（PUT 带客户端 `id`；不能回应自己的）/ 收回 |
| GET | `/rooms/{roomId}/board/search?q=` | 搜索正文与标题 |

逐行对比由客户端用 `shared/util/Diff` 计算，不另设接口。

### 档案、决定、时间线、阅读、总结（P6）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET · POST | `/rooms/{roomId}/archive` | 档案条目（新建时产生第 1 次修订，可带来源消息） |
| DELETE | `/rooms/{roomId}/archive/{id}` | 删除进回收站 |
| GET · POST | `/rooms/{roomId}/archive/{id}/revisions` | 历次修订 / 新修订（带修订 `id` 与 `baseRevision`，落后 409） |
| GET · POST · PATCH · DELETE | `/rooms/{roomId}/decisions[/{id}]` | 决定记录（PATCH 只发改动的字段；`myConcern` 只改自己的关注点；`finalChoice` 非空为定下、null 为重新考虑） |
| GET | `/rooms/{roomId}/timeline?year=&month=` | 共同时间线（服务端拼装，按房间时区的月份；不带年月取最近有内容的月份；附带所有有内容的月份） |
| GET | `/rooms/{roomId}/timeline/picks` | 两个人各自选中了哪些照片 |
| PUT · DELETE | `/rooms/{roomId}/timeline/picks/{fileId}` | 选中 / 取消选中照片（两人都选中才上时间线） |
| GET · POST | `/rooms/{roomId}/books` | 书架；上传 EPUB 先走 `/files` |
| PUT | `/rooms/{roomId}/books/{bookId}/progress` | 我的进度 |
| POST · PATCH · DELETE | `/rooms/{roomId}/books/{bookId}/highlights[/{id}]` | 书签、摘录、标注、感想 |
| POST | `/rooms/{roomId}/ai/read-explain` | 选中段落请 AI 解释或对比 → 202 |
| GET · POST | `/rooms/{roomId}/summaries` | 总结列表 / 生成（→ 202）；年度回顾由服务端定时生成 |

### 审稿（P7）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET · POST | `/rooms/{roomId}/reviews` | 审稿文件列表 / 新建 |
| POST | `/rooms/{roomId}/reviews/{id}/versions` | 上传新版本（先传 `/files`），触发预览生成任务 |
| GET | `/rooms/{roomId}/reviews/{id}/versions/{v}/pages` | 预览页（图片 + 文字层坐标） |
| GET | `/rooms/{roomId}/reviews/{id}/diff?from=&to=` | 版本间文字差异 |
| POST · PATCH | `/rooms/{roomId}/reviews/{id}/annotations[/{annId}]` | 批注、提议；状态：open → accepted / archived |
| POST | `/rooms/{roomId}/annotations/{annId}/replies` | 讨论 |
| POST | `/rooms/{roomId}/ai/review-findings` | 本次授权 AI 出审稿发现 → 202 |
| POST | `/rooms/{roomId}/ai-findings/{id}/convert` | 转为人工批注 |
| POST | `/rooms/{roomId}/ai-findings/{id}/dismiss` | 忽略 |
