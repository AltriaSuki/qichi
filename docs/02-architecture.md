# 02 · 技术栈与架构

> 本文件中的选择已经锁定。要改，先在对话里向人类说明理由和代价，得到同意后再改，并在文末「决策记录」追加一条。

## 1. 全景

```
 ┌──────────── 手机 A ────────────┐        ┌──────────── 手机 B ────────────┐
 │ Compose 界面                    │        │ 同一个 App，另一个账号          │
 │   ↕ ViewModel (StateFlow)       │        └────────────────────────────────┘
 │ Repository                      │                        │
 │   ├─ Room（唯一数据源）         │                        │
 │   ├─ Outbox（离线发件箱）       │                        │
 │   └─ Ktor Client ── HTTPS/WSS ──┼───────────┐            │
 │ WorkManager：补发、同步、缓存    │           │            │
 └─────────────────────────────────┘           ▼            ▼
                                   ┌──────────── 你的 VPS ───────────────────┐
                                   │ Caddy（自动 HTTPS，反向代理）            │
                                   │   ↓                                     │
                                   │ Ktor 服务端                              │
                                   │   ├─ REST /api/v1/*   ├─ WebSocket       │
                                   │   ├─ 任务队列（AI、文件转换、年度回顾）   │
                                   │   ├─ AiGateway ──→ 大模型 API（外部）     │
                                   │   └─ PushSender ──→ FCM / UnifiedPush    │
                                   │ PostgreSQL        文件目录 /data/files    │
                                   └─────────────────────────────────────────┘
```

## 2. 技术栈

### 客户端（`android/`）

| 用途 | 选择 | 理由 |
|---|---|---|
| 语言 | Kotlin | Android 官方语言，与服务端同语言，可共享代码 |
| 界面 | Jetpack Compose + Material 3 | 官方现代界面框架；Material 只用作底层组件，外观全部由自定义主题覆盖 |
| 导航 | Navigation Compose（类型安全路由） | 支持深链：通知直接打开某个房间的某个页面，返回键逐级回退 |
| 依赖注入 | Hilt（KSP） | 官方推荐，AI 最熟悉 |
| 本地数据库 | Room（KSP） | 离线优先的唯一数据源 |
| 长列表 | Paging 3 + LazyColumn | 聊天记录虚拟化滚动、向上翻历史 |
| 后台任务 | WorkManager | 发件箱补发、定期同步、书籍缓存 |
| 小型设置 | DataStore | 字号、行距、显示模式等本机设置 |
| 网络 | Ktor Client（OkHttp 引擎）+ kotlinx.serialization | 与服务端同一套序列化，可直接用 `shared/` 的数据类 |
| 实时 | Ktor Client WebSocket | 前台时接收变更提示 |
| 图片 | Coil | Compose 原生支持 |
| Markdown | commonmark-java 解析 + 自写 Compose 渲染 | 渲染样式要完全服从设计系统 |
| EPUB | Readium Kotlin Toolkit | 目录、分页、书签、定位、搜索都现成（第 6 阶段才引入） |
| 令牌保存 | DataStore + Android Keystore 加密（Tink） | 不用明文 SharedPreferences |
| 推送 | FCM 或 UnifiedPush（二选一，见 §6） | 取决于手机是否有谷歌服务 |

### 服务端（`server/`）

| 用途 | 选择 | 理由 |
|---|---|---|
| 框架 | Ktor（Netty 引擎） | Kotlin 原生、轻量，一个人的 VPS 足够 |
| 数据库 | PostgreSQL | 事务、JSON、全文/三元组索引都有 |
| 数据访问 | Exposed（DSL 风格） | Kotlin 写 SQL，类型安全 |
| 迁移 | Flyway | 数据库结构有版本，可重复部署 |
| 连接池 | HikariCP | 事实标准 |
| 鉴权 | JWT 访问令牌 + 可轮换的刷新令牌 | 手机长期登录 |
| 密码 | Argon2id | 当前推荐的密码哈希 |
| 中文搜索 | `pg_trgm` 三元组索引 + `ILIKE` | 两个人的数据量很小，不需要分词插件 |
| ICS | iCal4j | 日历导入、导出、订阅链接 |
| 文档预览（审稿） | LibreOffice 无界面模式转 PDF，再按页渲染成图片 | 「安全预览」= 只给手机看渲染后的图片（第 6 阶段） |
| 测试 | Ktor testApplication + Testcontainers（PostgreSQL） | 集成测试跑真实数据库 |

### 共用（`shared/`）

纯 Kotlin/JVM 库，不依赖 Android 也不依赖 Ktor：
- 接口数据类（`@Serializable`），与 `api/openapi.yaml` 一一对应
- 枚举：消息类型、情绪标签、回应类型、实体类型等
- 校验规则：强度 1–10、标题长度等，两端共用同一份
- 工具：UUIDv7 生成、中文字数与阅读时长计算、逐行 diff（java-diff-utils）

两边都用 `includeBuild("../shared")` 引入，**不发布到任何仓库**。

### 部署（`deploy/`）

Docker Compose：`caddy`（自动申请 HTTPS 证书）+ `server` + `db`（PostgreSQL）。文件存在 VPS 磁盘的 Docker 卷里。细节见 `07-deploy.md`。

### 版本策略

- JDK 21（Android Studio 自带的 JBR 即可）
- Android：minSdk 26；compileSdk / targetSdk 用初始化当天的最新稳定版
- 所有库在初始化时取最新稳定版，写死在 `libs.versions.toml`；之后升级要单独一个任务

## 3. 仓库结构

```
qichi/
├── CLAUDE.md                    给 AI 的总规约
├── README.md                    给人看的使用说明
├── api/openapi.yaml             接口契约（唯一来源）
├── docs/                        本目录
├── design/screens/*.html        设计稿（参考用）
├── deploy/
│   ├── docker-compose.yml       线上
│   ├── docker-compose.dev.yml   本地开发只起 PostgreSQL
│   ├── Caddyfile
│   ├── server.Dockerfile
│   ├── .env.example
│   └── backup.sh
├── shared/                      独立 Gradle 构建
│   ├── settings.gradle.kts      rootProject.name = "qichi-shared"
│   ├── build.gradle.kts         group = "app.qichi"
│   └── src/main/kotlin/app/qichi/shared/
│       ├── api/                 请求与响应数据类（按模块分文件）
│       ├── model/               枚举与值类型
│       ├── rules/               校验规则
│       └── util/                UUIDv7、字数统计、diff
├── server/                      独立 Gradle 构建
│   ├── settings.gradle.kts      rootProject.name = "qichi-server"; includeBuild("../shared")
│   ├── build.gradle.kts         application 插件，mainClass = app.qichi.server.ApplicationKt
│   └── src/
│       ├── main/kotlin/app/qichi/server/
│       │   ├── Application.kt   入口：读配置、装插件、挂路由
│       │   ├── config/          环境变量 → AppConfig
│       │   ├── plugins/         序列化、鉴权、错误处理、日志、限流、WebSocket
│       │   ├── db/              Exposed 表定义、事务工具、seq 分配、change_log
│       │   ├── auth/            注册、登录、刷新、登出、密码哈希
│       │   ├── rooms/           房间、成员、邀请、房间设置
│       │   ├── sync/            bootstrap、sync、实时事件广播
│       │   ├── files/           上传、下载、缩略图、存储接口
│       │   ├── messages/        聊天
│       │   ├── moods/  qna/  plans/  todos/  calendar/  ideas/
│       │   ├── board/           留言
│       │   ├── writing/         共同写作
│       │   ├── archive/         档案、决定、时间线
│       │   ├── reading/         书架、进度、标注
│       │   ├── review/          审稿
│       │   ├── summaries/       总结、年度回顾
│       │   ├── trash/           回收站
│       │   ├── ai/              AiGateway、各供应商实现、提示词模板
│       │   ├── push/            PushSender（FCM / UnifiedPush / 空实现）
│       │   └── jobs/            基于 PostgreSQL 的任务队列与定时任务
│       ├── main/resources/
│       │   ├── db/migration/    Flyway：V1__init.sql …
│       │   └── prompts/         AI 提示词模板（*.md）
│       └── test/kotlin/…        与 main 同结构
└── android/                     独立 Gradle 构建
    ├── settings.gradle.kts      include(":app"); includeBuild("../shared")
    ├── gradle/libs.versions.toml
    └── app/src/main/java/app/qichi/
        ├── QichiApplication.kt  @HiltAndroidApp
        ├── MainActivity.kt      唯一的 Activity，承载 NavHost
        ├── navigation/          路由定义、深链、底部标签
        ├── core/
        │   ├── designsystem/    主题（四种天色）、字体、间距、组件
        │   ├── database/        Room 数据库、DAO、实体
        │   ├── network/         Ktor Client、鉴权拦截、WebSocket
        │   ├── sync/            同步引擎、发件箱、WorkManager Worker
        │   ├── auth/            令牌存储、会话状态
        │   ├── push/            推送注册与通知
        │   └── util/
        └── feature/
            ├── auth/  today/  chat/  together/  me/
            ├── mood/  qna/  plan/  todo/  calendar/  ideas/
            ├── board/  writing/
            ├── archive/  decisions/  timeline/  reading/  review/  summary/
            └── trash/
```

**为什么是三个独立的 Gradle 构建而不是一个？** 服务端的 Docker 镜像只需要 `server/` 和 `shared/`，不需要安装 Android SDK；Android Studio 只打开 `android/`，同步速度快。`shared/` 被两边以源码方式引入，改一处两边同时生效。

**为什么 Android 只有一个 `app` 模块？** 只有两个用户、一个开发者（AI），多模块带来的构建复杂度得不偿失。用包（`feature/*`、`core/*`）分层，规则是：`feature` 之间不互相引用，只依赖 `core`。

## 4. 客户端架构

- **单 Activity + Compose**。底部四个标签各有自己的返回栈；「一起」下的页面压在「一起」的返回栈里。
- **单向数据流**：`Screen` 只接收 `UiState` 并发出事件；`ViewModel` 持有 `StateFlow<UiState>`，调用 `Repository`。
- **离线优先**：`Repository` 对外只暴露 Room 的 `Flow`。写操作 = 先写 Room（标记为待发送）→ 放进发件箱 → 由 `OutboxWorker` 发到服务端 → 服务端返回后更新 Room。详见 `05-sync-offline.md`。
- **深链格式**：`qichi://room/{roomId}/{page}[/{id}]`，例如 `qichi://room/r1/chat`、`qichi://room/r1/mood/m9`。通知点击、分享链接都走这个。
- **天色主题**：`core/designsystem` 根据本机时间选择清晨/白天/黄昏/深夜四套配色，整页淡入切换；「减少动画」开启时直接切换。
- **字体打包进 App**：思源宋体（Noto Serif SC，可变字重）和 Cormorant Garamond 放进 `res/font/`。不用谷歌的「可下载字体」，因为没有谷歌服务的手机加载不了。安装包会大约增加 20MB 以上，个人使用可以接受。
- **服务器地址**：从 `android/local.properties` 的 `qichi.baseUrl` 读入 `BuildConfig`，不写死在代码里。

## 5. 服务端架构

- **分层**：`Route`（解析请求、鉴权、校验房间成员）→ `Service`（业务规则、事务）→ `Repository`（Exposed SQL）。
- **每次写入的固定动作**（封装在 `db/RoomWrite.kt` 一个函数里）：开事务 → `UPDATE rooms SET last_seq = last_seq + 1 … RETURNING last_seq` 取得新 `seq` → 写实体（`seq` 列 = 新 seq）→ 插入 `change_log` → 提交 → 通过 WebSocket 向房间广播 `{type:"changed", seq}`。
- **幂等**：创建类接口的实体 id 由客户端生成；`INSERT … ON CONFLICT (id) DO NOTHING`，冲突时返回已存在的那一条（HTTP 200），新建返回 201。
- **任务队列**：`jobs` 表 + 后台协程，用 `SELECT … FOR UPDATE SKIP LOCKED` 领取任务。用于 AI 请求、文档转换、年度回顾等耗时操作。不引入 Redis 等额外组件。
- **定时任务**：同一个队列，带 `run_at`。年度回顾在房间时区的每年 1 月 1 日生成上一年。
- **文件存储**：`files/FileStorage` 接口，默认实现写本地目录 `FILES_DIR`，按 `{roomId}/{yyyy}/{mm}/{fileId}` 存放；下载必须经过服务端鉴权，不暴露静态目录。
- **AI 网关**（`ai/AiGateway`）：
  - 接口：`suspend fun complete(request: AiRequest): AiResult`
  - 实现两种：`OpenAiCompatibleProvider`（大多数模型服务都兼容这个格式）和 `AnthropicProvider`，用 `AI_PROVIDER` 环境变量选择
  - 提示词放 `resources/prompts/*.md`，不写在代码里
  - 所有调用异步：接口先返回 `202 + jobId`，结果写入对应实体后通过 WebSocket 通知
  - 每月 token 上限 `AI_MONTHLY_TOKEN_LIMIT`，超出后返回明确的错误
  - 选用的模型服务必须是你的 VPS 所在地区可以合规访问的

## 6. 推送

App 在前台时靠 WebSocket 实时收到变更，不需要推送。App 在后台时：

| 手机情况 | 方案 |
|---|---|
| 有谷歌服务（能用 Google Play） | FCM：服务端用服务账号调用 FCM HTTP v1 接口 |
| 没有谷歌服务（多数国产手机） | UnifiedPush：在 VPS 上自建 ntfy 作为推送服务器，手机装 ntfy App 作为分发器；需要把 ntfy 和栖迟加入电池优化白名单 |

服务端 `push/PushSender` 两种都实现，按设备注册时上报的 `provider` 发送。推送内容只含「有新消息」这类提示和深链，不含正文。
在推送做好之前（第 3 阶段之前），后台靠 WorkManager 每 15 分钟同步一次。

## 7. 安全与隐私

- 全程 HTTPS；服务端只监听内网端口，由 Caddy 对外。
- 注册只有两种情况允许：系统里还没有任何用户（第一个账号），或者持有有效邀请码。其余一律拒绝。
- 每个房间最多 2 名成员。
- 访问令牌 15 分钟过期；刷新令牌 60 天、每次使用都轮换、数据库只存哈希，登出即作废。
- 每个接口都校验房间成员身份；文件下载同样校验。
- 问答答案在双方确认前，接口层面就不返回对方的内容。
- 撤回消息：清空正文，保留 `retracted_by` / `retracted_at`。
- 备份：每天 `pg_dump` 加文件目录打包，保留 14 天，并复制到另一台机器或对象存储（见 `07-deploy.md`）。

## 8. 决策记录

| 编号 | 决策 | 原因 |
|---|---|---|
| D1 | 全栈 Kotlin | 一种语言，AI 上下文更集中；接口数据类两端共享 |
| D2 | 自写 Ktor 服务端，部署在自有 VPS | 人类选择；数据完全自己掌握 |
| D3 | 离线优先 + 服务端 seq 增量同步 | 需求里的断网补发、跨设备未读位置都依赖它 |
| D4 | 客户端生成 UUIDv7 作为实体 id | 补发不重复；UUIDv7 按时间有序，便于排序和建索引 |
| D5 | 不用 Redis、消息队列等额外组件 | 两个用户，PostgreSQL 足够；VPS 上的东西越少越好维护 |
| D6 | 字体打包进 App | 不依赖谷歌服务 |
| D7 | 中文搜索用 pg_trgm | 数据量小，免装分词插件 |
| D8 | 推送 FCM / UnifiedPush 双实现 | 手机是否有谷歌服务尚未确定 |
