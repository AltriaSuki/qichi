#!/usr/bin/env bash
# 栖迟每日备份（cron 每天跑一次；用法、恢复见 docs/07-deploy.md 第 7 节）。在 deploy/ 目录外调用也可以。
#   BACKUP_DIR=/var/backups/qichi KEEP_DAYS=14 ./backup.sh
#
# - 数据库：pg_dump 自定义格式，放在 BACKUP_DIR，保留 KEEP_DAYS 天（很小）
# - 文件（图片、文件、书）：
#     .env 里配了 RESTIC_REPOSITORY → restic 去重增量备份：每天只多存变了的部分，仓库可以在异地；
#       数据库导出也放进同一个快照，恢复时两者对得上。保留：14 天每天一份、8 周每周一份、12 个月每月一份
#     没配 → 每天整包打一份 tar.gz（文件有 N GB 就要 14×N GB）；放不下时这次跳过并告警
# - 出错、磁盘用量超过 80% 时经 alert.sh 告警
set -Eeuo pipefail

cd "$(dirname "$0")"

env_get() { sed -n "s/^$1=//p" .env 2>/dev/null | tail -n1 | sed -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'$/\1/"; }

# docker compose run restic 挂载备份目录时要用同一个值
export BACKUP_DIR="${BACKUP_DIR:-/var/backups/qichi}"
KEEP_DAYS="${KEEP_DAYS:-14}"
FILES_VOLUME="${FILES_VOLUME:-qichi_files}"
RESTIC_REPOSITORY="${RESTIC_REPOSITORY:-$(env_get RESTIC_REPOSITORY)}"
ts="$(date +%Y%m%d-%H%M%S)"

fail() {
    echo "[$(date -Is)] 备份失败：$1" >&2
    ./alert.sh "栖迟备份失败" "$1" || true
    exit 1
}
trap 'fail "backup.sh 第 $LINENO 行出错，详情看备份日志（/var/log/qichi-backup.log）"' ERR

mkdir -p "$BACKUP_DIR"

echo "[$(date -Is)] 备份开始"

# 数据库：先写临时文件，成功后再改名，避免留下半个备份。
docker compose exec -T db sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom' \
    > "$BACKUP_DIR/db-$ts.dump.part"
mv "$BACKUP_DIR/db-$ts.dump.part" "$BACKUP_DIR/db-$ts.dump"

if [ -n "$RESTIC_REPOSITORY" ]; then
    # restic 在容器里跑（docker-compose.yml 里的 restic 服务）
    restic() { docker compose run --rm -T restic "$@"; }

    # 第一次用：仓库还不存在（restic 退出码 10）就新建；别的错误（密码不对、连不上）直接报
    if err="$(restic cat config 2>&1 >/dev/null)"; then rc=0; else rc=$?; fi
    if [ "$rc" -eq 10 ]; then
        echo "[$(date -Is)] restic 仓库还不存在，新建"
        restic init
    elif [ "$rc" -ne 0 ]; then
        echo "$err" >&2
        fail "打不开 restic 仓库（退出码 $rc）：检查 .env 里的 RESTIC_REPOSITORY、RESTIC_PASSWORD 和网络"
    fi

    # 一个快照里：/data/files（文件卷，只读）+ /data/db.dump（刚导出的数据库）
    docker compose run --rm -T -v "$BACKUP_DIR/db-$ts.dump:/data/db.dump:ro" restic backup --host qichi --tag qichi /data
    # 每周日顺便回收空间、检查仓库结构
    if [ "$(date +%u)" = 7 ]; then
        restic forget --host qichi --keep-daily 14 --keep-weekly 8 --keep-monthly 12 --prune
        restic check
    else
        restic forget --host qichi --keep-daily 14 --keep-weekly 8 --keep-monthly 12
    fi
    files_note="文件与数据库已存入 restic 仓库"
else
    # 文件：只读挂载数据卷打包。先看放不放得下（留 20% 和 1GB 余量，不能把盘写满让数据库停掉）
    need_kb="$(docker run --rm -v "$FILES_VOLUME":/data:ro alpine du -sk /data | cut -f1)"
    free_kb="$(df -Pk "$BACKUP_DIR" | awk 'NR == 2 { print $4 }')"
    if [ "$free_kb" -lt $((need_kb + need_kb / 5 + 1048576)) ]; then
        files_note="文件这次没备份（放不下）"
        ./alert.sh "栖迟备份：文件这次没备份" \
            "备份目录只剩 $((free_kb / 1024)) MB，文件有 $((need_kb / 1024)) MB，放不下；数据库已备份。建议改用 restic（docs/07-deploy.md 第 7 节）" || true
    else
        docker run --rm \
            -v "$FILES_VOLUME":/data:ro \
            -v "$BACKUP_DIR":/backup \
            alpine sh -c "tar czf /backup/files-$ts.tar.gz.part -C /data . && mv /backup/files-$ts.tar.gz.part /backup/files-$ts.tar.gz"
        files_note="files-$ts.tar.gz"
    fi
fi

# 清理过期备份与失败留下的临时文件。
find "$BACKUP_DIR" -maxdepth 1 -type f \( -name 'db-*.dump' -o -name 'files-*.tar.gz' \) -mtime +"$KEEP_DAYS" -delete
find "$BACKUP_DIR" -maxdepth 1 -type f -name '*.part' -mmin +60 -delete

./check_disk.sh || true

echo "[$(date -Is)] 备份完成：db-$ts.dump、$files_note"
ls -lh "$BACKUP_DIR" | tail -n 4
