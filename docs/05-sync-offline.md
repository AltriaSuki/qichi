# 05 · 同步与离线

> 这是全项目最容易出错的部分。先读完再动手；任何偏离都要先问人类。

## 1. 核心思路

- **服务端为每个房间维护一个只增不减的序号 `seq`。** 房间里任何实体发生任何变化，都会拿到一个新的 `seq`，并在 `change_log` 里记一行。
- **客户端记住自己同步到的 `lastSeq`**，之后只要问服务端「`lastSeq` 之后变了什么」。
- **客户端只从 Room 读数据**，从不直接把网络结果显示到界面上。
- **客户端的每一次写操作都先落到本机**，再由发件箱按顺序发给服务端。

## 2. 服务端

### 2.1 seq 与 change_log

每次写入（在同一个数据库事务里）：

```sql
UPDATE rooms SET last_seq = last_seq + 1 WHERE id = :roomId RETURNING last_seq;   -- 得到 newSeq，同时对房间加行锁，保证顺序
-- 写实体，实体行的 seq 列 = newSeq，updated_at = now()
INSERT INTO change_log (room_id, seq, entity_type, entity_id, op, actor_id, at)
VALUES (:roomId, :newSeq, :type, :id, 'upsert', :userId, now());
```

提交后，向这个房间的所有 WebSocket 连接广播：`{"type":"changed","roomId":"…","seq":newSeq}`。

- 删除一律是**软删除**：设置 `deleted_at`、`deleted_by`，op 仍是 `upsert`。只有「彻底删除」才写 op = `delete`。
- 这段逻辑封装为 `db/RoomWrite.kt` 里的一个函数，所有 Service 都通过它写库，不允许绕开。

### 2.2 接口

| 接口 | 用途 |
|---|---|
| `GET /api/v1/rooms/{roomId}/bootstrap` | 首次安装或清除数据后：返回房间信息、成员、`lastSeq`、除消息外所有同步实体的当前状态（含已软删除的，用于回收站）、最近 50 条消息 |
| `GET /api/v1/rooms/{roomId}/sync?since={seq}&limit=500` | 增量：返回 `since` 之后变化过的实体 |
| `GET /api/v1/rooms/{roomId}/messages?beforeSeq={seq}&limit=50` | 往上翻历史消息 |
| `WS /api/v1/ws` | 前台实时提示 |

`sync` 的响应：

```json
{
  "fromSeq": 120,
  "toSeq": 180,
  "hasMore": false,
  "changes": [
    { "seq": 131, "type": "todo", "id": "0192…", "op": "upsert", "data": { "…完整的 Todo 对象…": "" } },
    { "seq": 180, "type": "message", "id": "0192…", "op": "upsert", "data": { "…": "" } }
  ]
}
```

- 同一个实体在区间内变化多次，只返回最后一次（`seq` 取最大值）。
- `data` 始终是实体的**完整当前状态**，客户端直接覆盖本地。
- 大内容不放在同步里：文稿版本正文、书籍文件、审稿页面图片，按需另取。
- `bootstrap` 同理只放「列表级」数据。

### 2.3 幂等

所有「创建」接口的请求体里带客户端生成的 `id`（UUIDv7）。服务端：

```sql
INSERT INTO … (id, …) VALUES (…) ON CONFLICT (id) DO NOTHING;
```

- 插入成功：201 + 实体
- 已存在：200 + 已存在的实体（并校验是同一个作者、同一个房间，否则 409）

这样发件箱重试多少次都不会产生重复数据。

## 3. 客户端

### 3.1 本地表

- 每个实体表都有 `syncState`：`SYNCED` / `PENDING` / `FAILED` / `CONFLICT`，以及服务端给的 `seq`（未同步时为空）。
- `sync_state(roomId, lastSeq)`
- `outbox(localId 自增, roomId, entityType, entityId, method, path, bodyJson, createdAt, attempts, state, lastError)`
- `drafts(roomId, key, text, baseVersion, updatedAt)`：聊天草稿、文稿未保存内容、留言草稿

### 3.2 拉取（SyncEngine.pull）

```
loop:
  resp = GET sync?since=lastSeq&limit=500
  在一个 Room 事务里：逐条 upsert changes（syncState=SYNCED）；lastSeq = resp.toSeq
  if !resp.hasMore: break
```

触发时机：App 启动与回到前台、WebSocket 收到 `changed` 且 `seq > lastSeq`、发件箱发完一批、下拉刷新、WorkManager 每 15 分钟一次（有网络约束）。

同一时间只允许一个拉取在跑（`Mutex`）。

### 3.3 发件箱（Outbox）

写操作的固定流程：

1. 生成 UUIDv7 作为实体 id（新建时）
2. 在一个 Room 事务里：写入/修改本地实体，`syncState = PENDING`；插入一条 outbox 记录
3. 唤起 `OutboxWorker`（WorkManager 唯一任务，约束：有网络）

`OutboxWorker` 按房间、按 `localId` 顺序**一次只发一条**（保证「按顺序补发」）：

| 结果 | 处理 |
|---|---|
| 2xx | 用响应里的实体覆盖本地（带上 `seq`，`syncState = SYNCED`），删除这条 outbox，继续下一条 |
| 网络错误、超时、5xx、429 | `attempts + 1`，停止本轮，交给 WorkManager 指数退避后重试 |
| 409 且是版本冲突 | 实体标记 `CONFLICT`，保留本地内容（文稿进入「重基线」流程），删除这条 outbox，继续 |
| 其它 4xx | 实体标记 `FAILED`，界面上显示「发送失败」，可重试或放弃；同一实体后面排队的操作一并标记失败 |

界面上：`PENDING` 的消息排在最底部，按本机创建时间排序，旁边显示一个小时钟图标；发送成功后按服务端 `seq` 归位。

### 3.4 不进发件箱的操作

离线时直接提示「需要联网」，按钮置灰：

- 所有 AI 请求（问 AI、出题、解释、审稿发现、总结）
- 上传附件（图片、文件、书籍、审稿文件）；因此带附件的消息也必须在线发送
- 登录、注册、邀请、修改密码

### 3.5 冲突规则

| 实体 | 规则 |
|---|---|
| 大多数实体（待办、日程、计划、心情、灵感、设置…） | 字段级「后到者生效」：PATCH 只发改动的字段，服务端按到达顺序覆盖 |
| 共同写作的版本 | 版本不可变。保存新版本必须带 `baseVersion`；不是最新版本 → 409 → 客户端进入「重基线」：展示对方的新版本与自己未保存内容的逐行对比，由用户决定后再保存。**本地内容在用户确认前绝不丢弃** |
| 档案条目 | 同上，修订带 `baseRevision` |
| 留言的编辑 | 同上，并在界面显示「已修订」 |
| 未读位置 | 只进不退：服务端保存 `max(旧值, 新值)` |

### 3.6 未读与已读位置

- `PUT /rooms/{roomId}/read-marker {"lastReadSeq": n}`，按**用户**保存（不是按设备），所以跨设备同步。
- 进入聊天、滚动到新消息时推进；推进本身也走发件箱（多次推进只保留最后一次）。
- 未读数 = 对方发的、`seq > 我的 lastReadSeq`、未删除的消息条数，在本地计算。
- 这是给自己看的位置，**不会**展示给对方（不做已读回执）。

### 3.7 删除、回收站与撤回

- 删除：软删除，进回收站；恢复：清空 `deleted_at`，内容按原状态回来。
- 产品要求可恢复的类型：消息、问答、档案、计划、待办、灵感（`01-product.md`）；心情和日程也同样软删除、可恢复。
- 回收站默认不自动清空，可手动「彻底删除」（写 op = `delete`，客户端收到后物理删除本地行）。
- 撤回（仅作者、仅消息）：正文清空，保留 `retracted_by`、`retracted_at`；搜索与导出都跳过。

### 3.8 书籍缓存

- 整本书按房间缓存在 App 私有目录。
- 总占用上限默认 500MB（可在设置里改），超出时按「最近打开时间」淘汰最旧的书；正在阅读的书不淘汰。
- 阅读进度、书签、标注走普通同步；书籍文件本身按需下载。

## 4. 时间与时区

- 服务端存储与接口一律 UTC，ISO-8601 字符串。
- 「今天」「本周」「每年 1 月 1 日」等按**房间时区**（房间设置里的时区）计算。
- 天色主题按**手机本地时间**计算（两个人可能不在同一个地方）。

## 5. 必须有的测试

- 服务端：同一个 id 连续创建两次只产生一行；并发写入时 `seq` 连续且不重复；`sync` 在区间内对同一实体只返回最后状态；非房间成员访问任何房间接口都返回 404。
- 客户端：断网写三条消息，恢复网络后按顺序各发一次；5xx 时退避重试；4xx 标记失败且不阻塞后续其它实体；409 进入冲突且本地内容保留。
