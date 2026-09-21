# 栖迟 Qichi

两个人共用的生活空间 App。装在各自手机上，账号独立，内容按房间共享：聊天、心情、问答、计划与待办、日历、共同写作、阅读、审稿，以及回看过去的一切。断网也能用，联网后自动补发。

- **手机端**：Android（Kotlin + Jetpack Compose），数据先存在手机里，再同步到服务器。
- **服务端**：Kotlin + Ktor + PostgreSQL，用 Docker 部署在自己的 VPS 上，数据完全自己掌握。
- **开发方式**：代码由 AI 编写，人类提需求、试用、做决定。给 AI 的规约见 [`CLAUDE.md`](CLAUDE.md)。

## 当前进度

第 0 阶段（地基）。完整路线图与勾选状态见 [`docs/08-roadmap.md`](docs/08-roadmap.md)。

## 目录

| 路径 | 内容 |
|---|---|
| `docs/` | 产品、架构、数据模型、接口、同步、设计系统、部署、路线图 |
| `api/openapi.yaml` | 接口契约 |
| `design/screens/` | 设计稿源码；在线查看：[栖迟 · 界面总览](https://claude.ai/artifact/M871aFHDnXdZ3fUPvrUW7X) |
| `shared/` | 手机端和服务端共用的 Kotlin 代码（P0-01 建立） |
| `server/` | 服务端（P0-02 建立；数据库迁移已在 `server/src/main/resources/db/migration/`） |
| `android/` | Android App（P0-03 建立） |
| `deploy/` | Docker Compose、Caddy、备份脚本 |

## 文档

1. [产品功能总览](docs/01-product.md)
2. [技术栈与架构](docs/02-architecture.md)
3. [数据模型](docs/03-data-model.md)
4. [接口约定](docs/04-api.md)
5. [同步与离线](docs/05-sync-offline.md)
6. [设计系统「晨雾」](docs/06-design-system.md)
7. [部署到 VPS](docs/07-deploy.md)
8. [开发路线图](docs/08-roadmap.md)

## 在本机开发

第一次准备环境（Arch Linux）：见 [`SETUP-ARCH.md`](SETUP-ARCH.md)。需要 JDK 21、Docker、Android SDK 和一个模拟器。

```bash
# 服务端
docker compose -f deploy/docker-compose.dev.yml up -d   # 本地数据库
cd server && ./gradlew run                              # http://localhost:8080/api/v1/health

# 手机端（先启动模拟器）
emulator -avd qichi_api37 &
cd android && ./gradlew :app:installDebug
```

`android/local.properties` 里写服务器地址：模拟器连本机用 `qichi.baseUrl=http://127.0.0.1:8080`（先执行 `adb reverse tcp:8080 tcp:8080`），连线上用 `https://你的域名`。

## 部署

见 [`docs/07-deploy.md`](docs/07-deploy.md)：在 VPS 上填好 `deploy/.env`，执行 `docker compose up -d --build`。
