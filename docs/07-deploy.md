# 07 · 部署到你的 VPS

> 配置文件都在 `deploy/`。本页写给人类，也写给 AI：AI 可以替你执行这些命令，但**每一条会改动服务器的命令都要先给你看、得到你同意**。

## 1. 需要准备

| 项 | 要求 |
|---|---|
| VPS | 建议 2 核 4GB 内存、20GB 硬盘（审稿的文档转换容器最多占 1GB）；只有 2GB 内存也能跑，转换大文件时会慢 |
| 系统 | Ubuntu 22.04 / 24.04 或 Debian 12 |
| 域名 | 一个你能改解析的域名，例如 `qichi.你的域名.com` |
| SSH | 能用密钥登录 VPS |

**关于备案**：如果 VPS 在中国大陆，用域名对外提供 80/443 端口服务需要先完成 ICP 备案，否则会被运营商拦截。香港或海外的 VPS 不需要备案。先确认你的 VPS 在哪里。

## 2. 域名解析

在域名服务商后台加一条 A 记录：`qichi` → 你的 VPS 公网 IP。
等几分钟，本机执行 `ping qichi.你的域名.com`，能看到 VPS 的 IP 就说明生效了。

## 3. 服务器初始化（只做一次）

```bash
# 安装 Docker（官方脚本）
curl -fsSL https://get.docker.com | sh

# 防火墙：只开 SSH、HTTP、HTTPS
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw allow 443/udp
sudo ufw enable
```

## 4. 首次部署

```bash
# 1. 把代码放到服务器（推荐用私有 Git 仓库）
git clone <你的私有仓库地址> ~/qichi
cd ~/qichi/deploy

# 2. 填写配置
cp .env.example .env
openssl rand -base64 48   # 生成随机串，分别填进 POSTGRES_PASSWORD 和 JWT_SECRET
nano .env                  # 填 DOMAIN、PUBLIC_BASE_URL 等

# 3. 启动
docker compose up -d --build

# 4. 看日志，确认服务端启动、数据库迁移成功
docker compose logs -f server

# 5. 检查
curl https://qichi.你的域名.com/api/v1/health
# 应返回 {"status":"ok","version":"…"}
```

服务端每次启动都会自动执行 Flyway 迁移，不需要手动建表。

### 服务器上已经有别的服务（共用一台小 VPS）

例如服务器上已经装了 Caddy 占着 80、443，而且只有 1GB 内存：

1. `.env` 里 `COMPOSE_PROFILES=`（不开自带的 caddy 和 converter），`CONVERTER_URL=` 留空。审稿只收 PDF
2. 把 `deploy/Caddyfile.host-example` 里的两段（换成你的域名）加进服务器的 `/etc/caddy/Caddyfile`，先备份原文件，再 `caddy validate --config /etc/caddy/Caddyfile && systemctl reload caddy`
3. 1GB 内存的服务器上别编译（Kotlin 编译要 1GB 以上内存）：在自己电脑上构建镜像再传过去
   ```bash
   # 在自己电脑的仓库根目录
   docker build -f deploy/server.Dockerfile -t qichi-server:latest .
   docker save qichi-server:latest | gzip | ssh -p <端口> root@<服务器> 'gunzip | docker load'
   # 在服务器上
   cd /opt/qichi/deploy && docker compose up -d --no-build
   ```
4. 以后内存加到 2GB 以上时，`.env` 里改成 `COMPOSE_PROFILES=converter`、`CONVERTER_URL=http://converter:3000`，`docker compose up -d` 就能审 Word、Excel、PowerPoint

## 5. 第一个账号

1. 编译正式版 App：`cd android && ./gradlew :app:assembleRelease -Pqichi.baseUrl=https://qichi.你的域名.com`，
   APK 在 `android/app/build/outputs/apk/release/`。正式版签名用 `android/local.properties` 里的 `qichi.release.storeFile / password / alias`
   （钥匙文件放在仓库外，**务必另外备份**：丢了以后就不能覆盖安装新版本，只能卸载重装）
2. 打开 App 注册：系统里还没有用户时，第一个人不需要邀请码
3. 建房间 → 在「我的 → 成员与邀请」生成邀请码 → 发给对方
4. 对方安装 App，用邀请码注册，自动进入同一个房间

之后服务器不再接受没有邀请码的注册。

## 6. 更新

```bash
cd ~/qichi && git pull
cd deploy && docker compose up -d --build
docker compose logs -f server
```

更新前先手动跑一次备份（见下一节）。

## 7. 备份与恢复

### 每日自动备份

```bash
sudo mkdir -p /var/backups/qichi
crontab -e
# 加一行：每天凌晨 4 点
0 4 * * * /home/<你的用户名>/qichi/deploy/backup.sh >> /var/log/qichi-backup.log 2>&1
```

备份在 `/var/backups/qichi/`：`db-时间.dump`（数据库）和 `files-时间.tar.gz`（图片、文件、书）。默认保留 14 天。

**只放在同一台 VPS 上的备份不算备份。** 至少再做一份异地副本，例如用 `rclone` 或 `restic` 每天同步到另一台机器、网盘或对象存储。

### 恢复（建议每季度演练一次）

```bash
cd ~/qichi/deploy
docker compose stop server
# 数据库
docker compose exec -T db pg_restore -U qichi -d qichi --clean --if-exists < /var/backups/qichi/db-时间.dump
# 文件
docker run --rm -v qichi_files:/data -v /var/backups/qichi:/backup alpine \
  sh -c "rm -rf /data/* && tar xzf /backup/files-时间.tar.gz -C /data"
docker compose start server
```

### 演练记录

| 日期 | 在哪里 | 做法 | 结果 |
|---|---|---|---|
| 2026-09-23 | 开发电脑（本机开发库） | 按上面的命令形式备份（`pg_dump --format=custom` + 打包文件目录）；恢复到一个全新的 Postgres 容器，解压文件，另起一个服务端连上去 | 登录正常；房间快照里各类内容数量与原库完全一致（最后序号 3213）；书的文件逐字节一致 |

| 2026-09-24 | VPS（qichi1.duckdns.org，首次部署当天） | 用 backup.sh 备份；恢复到一个临时 Postgres 容器（不动线上库），比对后删除临时库 | 表 45 张、迁移版本 17 与线上一致；那时还没有账号，只验证了结构完整。有了真实数据后再按内容比对演练一次 |

## 8. AI 配置（第 4 阶段起）

在 `.env` 里填：

| 变量 | 说明 |
|---|---|
| `AI_PROVIDER` | `openai-compatible`（大多数模型服务兼容这种接口）或 `anthropic` |
| `AI_BASE_URL` | 模型服务的接口地址 |
| `AI_API_KEY` | 密钥，只放在服务器上 |
| `AI_MODEL` | 模型名；现在用 `gpt-6-sol`（比较结果见 `docs/10-ai-assistant.md` 第五节） |
| `AI_MONTHLY_TOKEN_LIMIT` | 每月用量上限，防止意外花费 |

选择你的 VPS 所在地区能合规访问的模型服务。改完执行 `docker compose up -d` 生效。留空时 App 里的 AI 按钮显示为不可用。

## 9. 推送

**默认不需要装任何东西**：App 在「通知 → 后台接收消息」开着时，自己在后台和服务器保持连接，有消息直接弹通知（安卓要求通知栏常驻一条「栖迟正在接收消息」）。手机要把栖迟加入电池优化白名单、允许自启动和后台运行。

下面的 ntfy 是**备选**：手机总是把栖迟的后台杀掉时，可以另装 ntfy 来收。

### 备选：UnifiedPush + 自建 ntfy

两台手机没有谷歌服务，所以用 UnifiedPush：VPS 上跑一个 ntfy 当推送服务器，手机上装 ntfy App 负责收推送再转给栖迟。

1. 域名：用 DuckDNS 时 `push.qichi1.duckdns.org` 会自动解析到同一台 VPS，不用另加；其他域名服务商要再加一条 `push.你的域名` 的解析
2. `.env` 里填 `PUSH_DOMAIN=push.qichi1.duckdns.org`、`PUSH_PROVIDERS=unifiedpush`（`docker-compose.yml` 里已经有 ntfy 服务，`Caddyfile` 里已经有 `PUSH_DOMAIN` 的反向代理，Caddy 会自动给它申请证书）
3. 服务端只会往 `PUSH_DOMAIN` 发推送（`UNIFIEDPUSH_ALLOWED_HOSTS`），别的地址一律拒绝
4. 两台手机安装 ntfy App（F-Droid 或 GitHub 下载 APK），在 ntfy 设置里把「默认服务器」改成 `https://push.qichi1.duckdns.org`
5. 在系统设置里把 ntfy 和栖迟都加入「电池优化白名单 / 允许后台运行 / 自启动」
6. 打开栖迟 →「我的 → 通知」→ 点「开启推送」，选 ntfy

检查：`curl -d hi https://push.qichi1.duckdns.org/test` 后，在 ntfy App 里订阅 `test` 能收到。

## 10. 审稿的文档转换

`docker-compose.yml` 里的 `converter`（Gotenberg，内含 LibreOffice）负责把 Word、Excel、PowerPoint、OpenDocument、RTF、纯文本、CSV 转成 PDF，服务端再按页生成预览图和文字层。PDF 不经过它。

- 它只在内部网络里，不对外开放端口，也没有配置项要填
- 转换失败时，审稿页上会显示原因（例如「文件设了密码」「超过 300 页」）；可以让对方另存为 PDF 再传
- 查看：`docker compose logs --since 1h converter`

## 11. 日常检查

```bash
docker compose ps                    # 五个服务（caddy、server、db、ntfy、converter）都应是 running
docker compose logs --since 1h server
df -h                                # 磁盘空间
ls -lh /var/backups/qichi | tail     # 最近的备份
```
