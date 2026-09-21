#!/usr/bin/env bash
# 栖迟每日备份：数据库（pg_dump 自定义格式）+ 文件目录（tar.gz），默认保留 14 天。
# 用法见 docs/07-deploy.md 第 7 节；在 deploy/ 目录外调用也可以。
#   BACKUP_DIR=/var/backups/qichi KEEP_DAYS=14 ./backup.sh
set -euo pipefail

cd "$(dirname "$0")"

BACKUP_DIR="${BACKUP_DIR:-/var/backups/qichi}"
KEEP_DAYS="${KEEP_DAYS:-14}"
FILES_VOLUME="${FILES_VOLUME:-qichi_files}"
ts="$(date +%Y%m%d-%H%M%S)"

mkdir -p "$BACKUP_DIR"

echo "[$(date -Is)] 备份开始"

# 数据库：先写临时文件，成功后再改名，避免留下半个备份。
docker compose exec -T db sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom' \
    > "$BACKUP_DIR/db-$ts.dump.part"
mv "$BACKUP_DIR/db-$ts.dump.part" "$BACKUP_DIR/db-$ts.dump"

# 文件：只读挂载数据卷打包。
docker run --rm \
    -v "$FILES_VOLUME":/data:ro \
    -v "$BACKUP_DIR":/backup \
    alpine sh -c "tar czf /backup/files-$ts.tar.gz.part -C /data . && mv /backup/files-$ts.tar.gz.part /backup/files-$ts.tar.gz"

# 清理过期备份与失败留下的临时文件。
find "$BACKUP_DIR" -maxdepth 1 -type f \( -name 'db-*.dump' -o -name 'files-*.tar.gz' \) -mtime +"$KEEP_DAYS" -delete
find "$BACKUP_DIR" -maxdepth 1 -type f -name '*.part' -mmin +60 -delete

echo "[$(date -Is)] 备份完成：db-$ts.dump、files-$ts.tar.gz"
ls -lh "$BACKUP_DIR" | tail -n 4
