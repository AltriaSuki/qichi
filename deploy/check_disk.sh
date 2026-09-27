#!/usr/bin/env bash
# 磁盘检查：系统盘、Docker 数据目录、备份目录所在的磁盘，用量超过 DISK_ALERT_PERCENT（默认 80）就告警（alert.sh）。
# 同一块盘 12 小时内只报一次；正常时什么都不输出。cron 每半小时跑一次，backup.sh 结束时也会调用（docs/07-deploy.md 第 7 节）。
set -uo pipefail

cd "$(dirname "$0")"

env_get() { sed -n "s/^$1=//p" .env 2>/dev/null | tail -n1 | sed -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'$/\1/"; }
limit="${DISK_ALERT_PERCENT:-$(env_get DISK_ALERT_PERCENT)}"
limit="${limit:-80}"
backup_dir="${BACKUP_DIR:-/var/backups/qichi}"
state_dir="${ALERT_STATE_DIR:-/var/tmp/qichi-alerts}"
docker_root="$(docker info -f '{{.DockerRootDir}}' 2>/dev/null || true)"

mkdir -p "$state_dir"
seen=" "
status=0
for path in / "${docker_root:-/var/lib/docker}" "$backup_dir"; do
    [ -e "$path" ] || continue
    used="" mount=""
    read -r used mount < <(df -P "$path" | awk 'NR == 2 { sub(/%/, "", $5); print $5, $6 }')
    [ -n "$mount" ] || continue
    case "$seen" in *" $mount "*) continue ;; esac
    seen="$seen$mount "
    [ "$used" -ge "$limit" ] || continue
    status=1
    stamp="$state_dir/disk$(printf '%s' "$mount" | tr '/' '_')"
    [ -n "$(find "$stamp" -mmin -720 2>/dev/null)" ] && continue
    ./alert.sh "栖迟服务器磁盘快满了" "$mount 已用 $used%（超过 $limit%）。满了数据库会停，App 就用不了；清理办法见 docs/07-deploy.md 第 7 节" \
        && touch "$stamp"
done
exit "$status"
