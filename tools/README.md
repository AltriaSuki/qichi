# tools/ · 开发时用的小工具

只在开发电脑上用，不进 App、不上服务器。除 `design/*.js`（要全局装的 playwright）外都是 Python 3 标准库，不用装依赖。

| 文件 | 做什么 | 用法 |
|---|---|---|
| `uix.py` | 操作模拟器：列出屏幕上的文字、按文字点击、输入英文、截图 | `python3 tools/uix.py texts`、`python3 tools/uix.py tap 聊天`、`python3 tools/uix.py shot 截图.png`；也可以在脚本里 `from uix import *` |
| `fake_ai.py` | 本机假 AI（兼容 OpenAI 的 `/v1/chat/completions`），按提示词里的关键字回固定内容 | `python3 tools/fake_ai.py`，服务端用 `AI_PROVIDER=openai-compatible AI_BASE_URL=http://127.0.0.1:9099/v1 AI_API_KEY=local-test AI_MODEL=mock` 启动 |
| `publish_release.sh` | 打正式包并发布给 App 内置更新（版本号按提交次数自动增加） | 发到 VPS：`QICHI_SSH_HOST=root@服务器 QICHI_SSH_PORT=端口 tools/publish_release.sh "这一版改了什么"`（会改动服务器，先经人类同意）；本机测试：`QICHI_VARIANT=debug QICHI_BASE_URL=http://127.0.0.1:8080 QICHI_RELEASE_DIR=server/data/files/app-releases tools/publish_release.sh "说明"` |
| `dev_up.sh` | 一条命令拉起本机开发环境（数据库、假 AI、服务端、模拟器、端口转发），已经在跑的跳过 | `tools/dev_up.sh`（`tools/dev_up.sh server` 不开模拟器） |
| `deploy_server.sh` | 更新 VPS 上的服务端：本机构建镜像、先备份、传过去、重启、看健康检查 | `QICHI_SSH_HOST=root@服务器 QICHI_SSH_PORT=端口 tools/deploy_server.sh`（会改动服务器，先经人类同意） |
| `ai_eval.py` | 拿固定材料对真 AI 跑各个提示词，检查格式并存下回答给人看 | 见文件开头说明（需要 AI 密钥） |
| `doc_versions.py` | 给一篇文稿按顺序存几版，两个人轮流存（测试署名、历史版本） | `python3 tools/doc_versions.py 信 aqi:password-aqi:第一版 xiaochi:password-chi:第二版` |
| `seed_bulk.py` | 灌大量消息和灵感测流畅度（只用在本机） | `python3 tools/seed_bulk.py 3000 500` |
| `dev_api.py` | 直接调本机服务端的接口准备数据：登录、以某人身份发消息、上传审稿文件 | `python3 tools/dev_api.py send xiaochi password-chi "晚上吃什么"`、`python3 tools/dev_api.py review aqi password-aqi 文件.pdf` |
| `design/gen_screens.py` | 生成设计稿「新方向」页（`design/screens/New-*.dc.html`），所有画板用同一套组件函数拼出来 | `python3 tools/design/gen_screens.py` |
| `design/render.js` / `design/sheet.js` | 把设计稿渲染成截图并报告内容溢出；几张截图拼成一张 | `NODE_PATH=$(npm root -g) node tools/design/render.js design/screens 截图目录 New-`（用预装的 Chromium，截图放会话临时目录） |
| `design/render_local.py` | 同上，但用本机装的 Chromium（不用装 playwright），截图和模拟器截图并排对照用 | `python3 tools/design/render_local.py 截图目录 New-Spec New-Today` |

说明：
- adb 输入不了中文，界面测试里输入英文；中文内容用 `dev_api.py` 通过接口准备。
- 截图放在会话临时目录，不要放进仓库。
- 模拟器访问本机服务端先 `adb reverse tcp:8080 tcp:8080`（见 CLAUDE.md）。
