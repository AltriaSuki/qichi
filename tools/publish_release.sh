#!/usr/bin/env bash
# 打正式包并发布，供 App 内置更新下载。
#
#   tools/publish_release.sh "这一版改了什么"
#
# 发布到哪里（二选一）：
#   QICHI_SSH_HOST=root@服务器 [QICHI_SSH_PORT=22]   传到 VPS 上运行中的服务端（/opt/qichi/deploy），会改动服务器，先让人类同意
#   QICHI_RELEASE_DIR=目录               放进本机目录（本机开发服务端的 FILES_DIR/app-releases，测试用）
# 其它：
#   QICHI_BASE_URL   App 连哪个服务器（默认 https://qichi1.duckdns.org）
#   QICHI_VARIANT    release（默认，正式签名）或 debug（本机测试，和模拟器上装的调试版签名一样）
set -euo pipefail
cd "$(dirname "$0")/.."

NOTES="${1:-}"
BASE_URL="${QICHI_BASE_URL:-https://qichi1.duckdns.org}"
VARIANT="${QICHI_VARIANT:-release}"
TASK="assemble${VARIANT^}"

(cd android && ./gradlew -q ":app:$TASK" -Pqichi.baseUrl="$BASE_URL")
OUT="android/app/build/outputs/apk/$VARIANT"
META="$OUT/output-metadata.json"
APK="$OUT/$(python3 -c "import json;print(json.load(open('$META'))['elements'][0]['outputFile'])")"
CODE=$(python3 -c "import json;print(json.load(open('$META'))['elements'][0]['versionCode'])")
NAME=$(python3 -c "import json;print(json.load(open('$META'))['elements'][0]['versionName'])")
SHA=$(sha256sum "$APK" | cut -d' ' -f1)
SIZE=$(stat -c %s "$APK")

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
cp "$APK" "$WORK/qichi-$CODE.apk"
python3 - "$WORK/latest.json" "$CODE" "$NAME" "$NOTES" "$SIZE" "$SHA" <<'PY'
import json, sys, datetime
path, code, name, notes, size, sha = sys.argv[1:]
json.dump({"versionCode": int(code), "versionName": name, "notes": notes, "sizeBytes": int(size), "sha256": sha,
           "publishedAt": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")},
          open(path, "w"), ensure_ascii=False)
PY
echo "版本 $NAME（$CODE），$SIZE 字节，sha256 $SHA"

if [[ -n "${QICHI_RELEASE_DIR:-}" ]]; then
    mkdir -p "$QICHI_RELEASE_DIR"
    cp "$WORK/qichi-$CODE.apk" "$QICHI_RELEASE_DIR/"
    cp "$WORK/latest.json" "$QICHI_RELEASE_DIR/latest.json"   # 最后写说明：安装包先到位
    echo "已放进 $QICHI_RELEASE_DIR"
elif [[ -n "${QICHI_SSH_HOST:-}" ]]; then
    PORT="${QICHI_SSH_PORT:-22}"
    scp -q -P "$PORT" "$WORK/qichi-$CODE.apk" "$WORK/latest.json" "$QICHI_SSH_HOST:/tmp/"
    ssh -p "$PORT" "$QICHI_SSH_HOST" "cd /opt/qichi/deploy && docker compose exec -T server mkdir -p /data/files/app-releases \
        && docker compose cp /tmp/qichi-$CODE.apk server:/data/files/app-releases/ \
        && docker compose cp /tmp/latest.json server:/data/files/app-releases/latest.json \
        && rm /tmp/qichi-$CODE.apk /tmp/latest.json"
    echo "已发布到服务器"
else
    echo "没设 QICHI_SSH_HOST 或 QICHI_RELEASE_DIR，只打了包：$APK"
fi
