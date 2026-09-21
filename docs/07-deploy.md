# 07 · 部署到你的 VPS

> 配置文件都在 `deploy/`。本页写给人类，也写给 AI：AI 可以替你执行这些命令，但**每一条会改动服务器的命令都要先给你看、得到你同意**。

## 1. 需要准备

| 项 | 要求 |
|---|---|
| VPS | 1 核 2GB 内存、20GB 硬盘即可起步；第 7 阶段的审稿要跑文档转换，届时建议 4GB |
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

## 5. 第一个账号

1. 在 `android/local.properties` 里写上 `qichi.baseUrl=https://qichi.你的域名.com`，然后编译安装 App
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

## 8. AI 配置（第 4 阶段起）

在 `.env` 里填：

| 变量 | 说明 |
|---|---|
| `AI_PROVIDER` | `openai-compatible`（大多数模型服务兼容这种接口）或 `anthropic` |
| `AI_BASE_URL` | 模型服务的接口地址 |
| `AI_API_KEY` | 密钥，只放在服务器上 |
| `AI_MODEL` | 模型名 |
| `AI_MONTHLY_TOKEN_LIMIT` | 每月用量上限，防止意外花费 |

选择你的 VPS 所在地区能合规访问的模型服务。改完执行 `docker compose up -d` 生效。留空时 App 里的 AI 按钮显示为不可用。

## 9. 推送（第 3 阶段）

先确认两台手机有没有谷歌服务（能否正常打开 Google Play）。

**有谷歌服务 → FCM**
1. 在 Firebase 控制台建项目，添加 Android 应用（包名 `app.qichi`），下载 `google-services.json` 放到 `android/app/`（已在 .gitignore 中）
2. 在「项目设置 → 服务账号」生成私钥 JSON，放到服务器 `deploy/secrets/fcm.json`
3. 在 `docker-compose.yml` 里取消 `fcm.json` 那行挂载的注释；`.env` 里 `PUSH_PROVIDERS=fcm`

**没有谷歌服务 → UnifiedPush + 自建 ntfy**
1. 再加一条解析 `push.你的域名.com`
2. 在 `docker-compose.yml` 增加 ntfy 服务，在 `Caddyfile` 增加 `push.你的域名.com` 的反向代理（第 3 阶段的推送任务里由 AI 完成）
3. 两台手机安装 ntfy App，服务器地址填 `https://push.你的域名.com`
4. 在系统设置里把 ntfy 和栖迟都加入「电池优化白名单 / 允许后台运行 / 自启动」
5. `.env` 里 `PUSH_PROVIDERS=unifiedpush`

## 10. 日常检查

```bash
docker compose ps                    # 三个服务都应是 running
docker compose logs --since 1h server
df -h                                # 磁盘空间
ls -lh /var/backups/qichi | tail     # 最近的备份
```
