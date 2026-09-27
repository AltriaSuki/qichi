#!/usr/bin/env bash
# 没有 Docker 的机器（例如云端会话）上起一个本机 PostgreSQL，给服务端测试用（有 Docker 时不需要它，测试自己起容器）。
#   tools/local_pg.sh          起库（已经在跑就跳过），最后打印要设的环境变量
#   tools/local_pg.sh stop     停库
# 然后：cd server && QICHI_TEST_DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/qichi_test ./gradlew test
# 这个库每个测试开始前都会被清空，只能给测试用。需要装好 PostgreSQL 16 服务端（Ubuntu 的 postgresql-16）。
set -euo pipefail

PGBIN="${PGBIN:-$(ls -d /usr/lib/postgresql/*/bin 2>/dev/null | sort -V | tail -1)}"
DIR="${QICHI_PG_DIR:-/tmp/qichi-pg}"
PORT="${QICHI_PG_PORT:-5432}"
[[ -x "$PGBIN/postgres" ]] || { echo "找不到 PostgreSQL 服务端，用 PGBIN=…/bin 指定"; exit 1; }

# PostgreSQL 不肯以 root 运行：root 时换成 postgres 用户
as_pg() { if [[ $(id -u) == 0 ]]; then su postgres -c "$*"; else bash -c "$*"; fi; }

if [[ "${1:-start}" == stop ]]; then
    as_pg "$PGBIN/pg_ctl -D $DIR/data stop -m fast"
    exit 0
fi

if [[ ! -d "$DIR/data" ]]; then
    mkdir -p "$DIR"
    if [[ $(id -u) == 0 ]]; then chown postgres:postgres "$DIR"; fi
    as_pg "$PGBIN/initdb -D $DIR/data -U postgres -A trust -E UTF8 --locale=C.UTF-8" >/dev/null
fi
if ! as_pg "$PGBIN/pg_ctl -D $DIR/data status" >/dev/null 2>&1; then
    as_pg "$PGBIN/pg_ctl -D $DIR/data -l $DIR/log.txt -o '-p $PORT -k /tmp -c listen_addresses=127.0.0.1' -w start" >/dev/null
fi

sql() { as_pg "psql -h 127.0.0.1 -p $PORT -U postgres -tAc \"$1\""; }
# 超级用户：V1 迁移要建 pg_trgm 扩展
[[ "$(sql "SELECT 1 FROM pg_roles WHERE rolname='qichi'")" == 1 ]] || sql "CREATE ROLE qichi LOGIN PASSWORD 'qichi' SUPERUSER" >/dev/null
[[ "$(sql "SELECT 1 FROM pg_database WHERE datname='qichi_test'")" == 1 ]] || sql "CREATE DATABASE qichi_test OWNER qichi" >/dev/null

echo "export QICHI_TEST_DATABASE_URL=jdbc:postgresql://127.0.0.1:$PORT/qichi_test"
