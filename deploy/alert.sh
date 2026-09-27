#!/usr/bin/env bash
# 往手机发一条告警（备份失败、磁盘快满时由 backup.sh、check_disk.sh 调用）。
# .env 里配了 ALERT_NTFY_URL（ntfy 的一个主题地址）就推送过去；没配只打印到日志。见 docs/07-deploy.md 第 7 节。
#   ./alert.sh "标题" "内容"
set -uo pipefail

cd "$(dirname "$0")"

title="${1:?用法：alert.sh 标题 [内容]}"
body="${2:-$1}"

# 只从 .env 取需要的那一项（.env 里有带空格的值，不能整个 source）
env_get() { sed -n "s/^$1=//p" .env 2>/dev/null | tail -n1 | sed -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'$/\1/"; }
url="${ALERT_NTFY_URL:-$(env_get ALERT_NTFY_URL)}"
# 自建 ntfy 改成只有带令牌的才能发之后要带上（Q4）
token="${ALERT_NTFY_TOKEN:-$(env_get ALERT_NTFY_TOKEN)}"

echo "[$(date -Is)] 告警：$title —— $body" >&2
[ -n "$url" ] || exit 0

# 标题是中文：按 RFC 2047 编码放进请求头（ntfy 会解开）；内容放在请求体里，原样是 UTF-8
encoded_title="=?UTF-8?B?$(printf '%s' "$title" | base64 | tr -d '\n')?="
auth=()
[ -n "$token" ] && auth=(-H "Authorization: Bearer $token")
if ! curl -fsS -m 20 ${auth[@]+"${auth[@]}"} -H "Title: $encoded_title" -H "Priority: high" -H "Tags: warning" --data-binary "$body" "$url" >/dev/null; then
    echo "[$(date -Is)] 告警没发出去：检查 .env 里的 ALERT_NTFY_URL 和网络" >&2
    exit 1
fi
