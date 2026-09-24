#!/usr/bin/env bash
# 更新 VPS 上的服务端（会改动服务器：每次运行前先让人类同意）。
# 小内存的服务器上不编译：本机构建镜像 → 传过去 → 重启 → 看健康检查。数据库迁移在服务端启动时自动执行。
#
#   QICHI_SSH_HOST=root@服务器 QICHI_SSH_PORT=端口 QICHI_DOMAIN=qichi1.duckdns.org tools/deploy_server.sh
#
# 同时把 deploy/docker-compose.yml 和 backup.sh 同步过去（.env 不动）。更新前先在服务器上跑一次备份。
set -euo pipefail
cd "$(dirname "$0")/.."
: "${QICHI_SSH_HOST:?请设置 QICHI_SSH_HOST，如 root@1.2.3.4}"
PORT="${QICHI_SSH_PORT:-22}"
DOMAIN="${QICHI_DOMAIN:-qichi1.duckdns.org}"
TAG="$(git rev-parse --short HEAD)"

echo "· 本机构建镜像 qichi-server:$TAG"
docker build -q -f deploy/server.Dockerfile -t qichi-server:latest -t "qichi-server:$TAG" . >/dev/null

echo "· 服务器上先备份"
ssh -p "$PORT" "$QICHI_SSH_HOST" "/opt/qichi/deploy/backup.sh | tail -2"

echo "· 传配置和镜像"
scp -q -P "$PORT" deploy/docker-compose.yml deploy/backup.sh "$QICHI_SSH_HOST:/opt/qichi/deploy/"
docker save qichi-server:latest | gzip -1 | ssh -p "$PORT" "$QICHI_SSH_HOST" 'gunzip | docker load'

echo "· 重启"
ssh -p "$PORT" "$QICHI_SSH_HOST" "cd /opt/qichi/deploy && docker compose up -d --no-build 2>&1 | tail -3"
for _ in $(seq 40); do
    if curl -s -m 5 "https://$DOMAIN/api/v1/health" | grep -q '"ok"'; then
        echo "· 服务端正常：$(curl -s "https://$DOMAIN/api/v1/health")"
        ssh -p "$PORT" "$QICHI_SSH_HOST" "docker image prune -f >/dev/null"
        exit 0
    fi
    sleep 3
done
echo "!! 健康检查没通过，看日志：ssh -p $PORT $QICHI_SSH_HOST 'cd /opt/qichi/deploy && docker compose logs --tail 50 server'"
exit 1
