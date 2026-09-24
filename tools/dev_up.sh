#!/usr/bin/env bash
# 一条命令拉起本机开发环境（电脑重启后用）：开发数据库、假 AI、服务端、模拟器、端口转发。
# 已经在跑的会跳过。日志在 ${QICHI_LOG_DIR:-/tmp/qichi-dev}/。
#   tools/dev_up.sh          全部
#   tools/dev_up.sh server   只要数据库 + 假 AI + 服务端（不开模拟器）
set -euo pipefail
cd "$(dirname "$0")/.."
LOG="${QICHI_LOG_DIR:-/tmp/qichi-dev}"
mkdir -p "$LOG"
ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
EMULATOR="${EMULATOR:-$HOME/Android/Sdk/emulator/emulator}"

echo "· 开发数据库"
docker compose -f deploy/docker-compose.dev.yml up -d >/dev/null

if ! curl -s -m 2 -o /dev/null http://127.0.0.1:9099/v1/chat/completions -X POST -d '{}' 2>/dev/null; then
    echo "· 假 AI（127.0.0.1:9099）"
    nohup python3 tools/fake_ai.py >"$LOG/fake-ai.log" 2>&1 &
fi

if ! curl -s -m 2 http://127.0.0.1:8080/api/v1/health >/dev/null; then
    echo "· 服务端（127.0.0.1:8080，日志 $LOG/server.log）"
    (cd server && PUSH_PROVIDERS=unifiedpush AI_PROVIDER=openai-compatible AI_BASE_URL=http://127.0.0.1:9099/v1 \
        AI_API_KEY=local-test AI_MODEL=mock nohup ./gradlew run --console=plain >"$LOG/server.log" 2>&1 &)
fi

if [[ "${1:-all}" != "server" ]]; then
    if ! "$ADB" devices | grep -q "emulator-"; then
        echo "· 模拟器 qichi_api37"
        nohup "$EMULATOR" -avd qichi_api37 -gpu host -no-snapshot-save >"$LOG/emulator.log" 2>&1 &
    fi
    "$ADB" wait-for-device
    for _ in $(seq 60); do [[ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]] && break; sleep 3; done
    "$ADB" reverse tcp:8080 tcp:8080 >/dev/null
    echo "· 模拟器已就绪，端口 8080 已转发"
fi

for _ in $(seq 80); do curl -s -m 2 http://127.0.0.1:8080/api/v1/health >/dev/null && break; sleep 3; done
curl -s http://127.0.0.1:8080/api/v1/health && echo
