# CLAUDE.md — 给 AI 的总规约

栖迟（Qichi）是两人共用的生活空间 App：Android 客户端 + 自建 Ktor 服务端，全栈 Kotlin，离线优先。
**代码全部由 AI 编写；人类负责提需求、试用和做决定。** 和人类交流用中文，少用术语，需要人类拍板的事明确列出来。

## 1. 先读什么

| 做什么 | 必读 |
|---|---|
| 任何任务 | 本文件、`docs/08-roadmap.md` 里对应的任务条目 |
| 产品行为 | `docs/01-product.md`（最终依据） |
| 技术栈、目录、分层 | `docs/02-architecture.md`（**已锁定**，要改先说明理由和代价，人类同意后再改，并在文末「决策记录」追加一条） |
| 数据库 | `docs/03-data-model.md`、`server/src/main/resources/db/migration/` |
| 接口 | `api/openapi.yaml`（**唯一契约**）、`docs/04-api.md`（约定） |
| 同步、离线、发件箱 | `docs/05-sync-offline.md`（最容易出错；**任何偏离先问人类**） |
| 界面 | `docs/06-design-system.md` + `design/screens/*.dc.html`（两者冲突以文档为准，并提醒人类更新设计稿） |
| 部署 | `docs/07-deploy.md`、`deploy/` |
| 本机环境 | `SETUP-ARCH.md` |

## 2. 工作方式

1. **一次只做一个路线图任务。** 开工前先读相关文档和设计稿，告诉人类实现计划：改哪些文件、加哪些测试、有哪些需要人类决定的地方。等人类回复「可以」再动手。
2. 做完后跑测试；界面任务在模拟器里截图给人类看（截图放在会话临时目录，不进仓库）。
3. 验收条目全部满足后，把 `docs/08-roadmap.md` 里的 `[ ]` 改成 `[x]`，和代码一起提交。
4. **分阶段提交**：一个任务里有几个清楚的步骤，就分几次提交；不要攒成一个大提交。提交信息用中文，格式 `类型(范围): 说明`，类型用 `feat` `fix` `docs` `test` `refactor` `build` `chore` 等，任务编号写在说明里，如 `feat(shared): UUIDv7 与中文字数统计（P0-01）`。
5. **推送到 GitHub（`origin`）之前先问人类。**
6. 需要 `sudo` 的命令（装系统软件、改系统服务、加用户组）**只写出来给人类执行，自己不执行**。
7. VPS 上每一条会改动服务器的命令，先给人类看、得到同意再执行。
8. 不把任何密码、密钥、令牌写进仓库、日志或记忆；需要时让人类放进 `deploy/.env` 或 `android/local.properties`（都已忽略）。
9. 发现文档之间或文档与设计稿之间有矛盾，先停下来问，不要自己挑一个。
10. 升级依赖是单独的任务，不顺手升级。

## 3. 常用命令

> 下列命令对应的工程在 P0-01～P0-03 建立后才存在。

```bash
# 共享模块
cd shared && ./gradlew test

# 服务端：本地数据库 → 运行 → 测试（测试用 Testcontainers，需要 Docker）
docker compose -f deploy/docker-compose.dev.yml up -d
cd server && ./gradlew run          # http://localhost:8080/api/v1/health
cd server && ./gradlew test

# 服务端镜像（在仓库根目录）
docker build -f deploy/server.Dockerfile .

# Android
cd android && ./gradlew :app:assembleDebug
cd android && ./gradlew :app:testDebugUnitTest
cd android && ./gradlew :app:installDebug
# 测流畅度、查 R8 问题用：和正式版一样经过 R8，调试签名，可连本机服务端
cd android && ./gradlew :app:assembleBenchmark
adb shell dumpsys gfxinfo app.qichi reset   # 操作之后再 dumpsys gfxinfo app.qichi 看掉帧

# 模拟器（名字 qichi_api37，见 SETUP-ARCH.md）
emulator -avd qichi_api37 -gpu host -no-snapshot-save &
adb wait-for-device && adb reverse tcp:8080 tcp:8080
adb shell am start -d "qichi://room/test/chat"      # 深链
adb exec-out screencap -p > shot.png                # 截图
```

模拟器访问本机服务端：先 `adb reverse tcp:8080 tcp:8080`，App 用 `http://127.0.0.1:8080`（`android/local.properties` 的 `qichi.baseUrl`，也是默认值）。不要用 `10.0.2.2`：这台电脑的代理 TUN 模式会让它超时（见 SETUP-ARCH.md）。
adb 输入不了中文：界面测试里的名字用英文，中文内容通过接口准备。
模拟器操作、本机假 AI、用接口准备数据的小工具在 `tools/`（见 `tools/README.md`）；新写的测试小工具也放那里，不放会话临时目录。

## 4. 代码约定

### 通用
- 包名 `app.qichi`；服务端 `app.qichi.server`；共享 `app.qichi.shared`。
- 实体 id 一律 UUIDv7：客户端创建的由客户端生成（`shared/util/UuidV7`），服务端产生的由服务端生成。
- 时间：存储与传输一律 UTC `Instant`；「今天」「本周」按**房间时区**算；天色主题按**手机本地时间**算。
- 枚举值是英文小写字符串（与 `shared/model/` 一致），中文显示名只在客户端。
- 校验规则（长度、范围）写在 `shared/rules/`，两端共用同一份。
- 接口数据类放 `shared/api/`，字段与 `api/openapi.yaml` 一一对应。**改接口的顺序：openapi.yaml → shared → 服务端 → 客户端。**

### 服务端
- 分层：`Route`（解析、鉴权、`requireMember(roomId)`）→ `Service`（业务规则、事务）→ `Repository`（Exposed DSL）。
- **所有房间内写入都通过 `db/RoomWrite.kt`**（分配 seq → 写实体 → 写 change_log → 提交后广播），不允许绕开。
- 创建接口幂等：`INSERT … ON CONFLICT (id) DO NOTHING`，新建 201、已存在 200、属于别人 409。
- 非房间成员访问房间内任何资源一律 404。
- 错误一律 problem+json，用 `docs/04-api.md` 里的 `code`；新增 code 要同时改文档和 openapi.yaml。
- 数据库结构只通过 Flyway 迁移修改。**已经部署到 VPS 的迁移文件不能再改**，只能加新版本；首次部署（P0-06）之前可以直接改 `V1__init.sql`。
- 日志不打印请求正文、密码、令牌。
- AI 提示词放 `resources/prompts/*.md`，不写在代码里。

### Android
- 单 Activity + Compose；`feature/*` 之间不互相引用，只依赖 `core/*`。
- 单向数据流：`Screen(uiState, onEvent)` ← `ViewModel(StateFlow<UiState>)` ← `Repository`。
- **Repository 只向外暴露 Room 的 `Flow`**；写操作 = 写 Room（`PENDING`）+ 插入 outbox，同一个 Room 事务；界面从不直接显示网络结果。
- 颜色、字号、间距只从 `core/designsystem` 取，不在页面里写死数值；需要单独调字号时用 `N.tsp`（随「大字」放大），不用 `N.sp`。界面只有中文、不做多语言，文字直接写在 Compose 代码里（应用名等系统用到的放 `strings.xml`）。
- 字体打包在 `res/font/`，不用可下载字体。
- 「减少动画」开启时所有动效改为直接切换。

## 5. 测试要求

- 服务端：业务规则写集成测试（Ktor `testApplication` + Testcontainers PostgreSQL），`docs/05-sync-offline.md` 第 5 节列出的必测项一个不能少。
- 客户端：ViewModel、同步引擎、发件箱写单元测试（网络用 Ktor MockEngine）；Room 用内存数据库测。
- 修 bug 先写一个能复现它的测试。
- 测试不通过不提交；确实跑不了的（例如需要真机），在汇报里写明原因。

## 6. 刻意不做

已读回执、在线状态、正在输入；Redis、消息队列等额外组件；谷歌可下载字体；明文保存令牌。
