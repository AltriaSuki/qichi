# tools/ · 开发时用的小工具

只在开发电脑上用，不进 App、不上服务器。都是 Python 3 标准库，不用装依赖。

| 文件 | 做什么 | 用法 |
|---|---|---|
| `uix.py` | 操作模拟器：列出屏幕上的文字、按文字点击、输入英文、截图 | `python3 tools/uix.py texts`、`python3 tools/uix.py tap 聊天`、`python3 tools/uix.py shot 截图.png`；也可以在脚本里 `from uix import *` |
| `fake_ai.py` | 本机假 AI（兼容 OpenAI 的 `/v1/chat/completions`），按提示词里的关键字回固定内容 | `python3 tools/fake_ai.py`，服务端用 `AI_PROVIDER=openai-compatible AI_BASE_URL=http://127.0.0.1:9099/v1 AI_API_KEY=local-test AI_MODEL=mock` 启动 |
| `dev_api.py` | 直接调本机服务端的接口准备数据：登录、以某人身份发消息、上传审稿文件 | `python3 tools/dev_api.py send xiaochi password-chi "晚上吃什么"`、`python3 tools/dev_api.py review aqi password-aqi 文件.pdf` |

说明：
- adb 输入不了中文，界面测试里输入英文；中文内容用 `dev_api.py` 通过接口准备。
- 截图放在会话临时目录，不要放进仓库。
- 模拟器访问本机服务端先 `adb reverse tcp:8080 tcp:8080`（见 CLAUDE.md）。
