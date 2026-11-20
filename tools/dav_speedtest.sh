#!/usr/bin/env bash
# WebDAV 吞吐测试：分辨瓶颈是「网络带宽」还是「每块新建连接的协议开销」。
#
#   ./tools/dav_speedtest.sh http://192.168.31.234:23531/osmo360 osmo360 '密码' [测试大小MB]
#
# 五项：
#   1. 列目录           —— 顺便量 RTT（建连 + PROPFIND 往返）
#   2. 单请求拉 SIZE MB —— 带宽上限
#   3. 40 × 256KB，一路连接复用 —— App 改成 keep-alive 之后的样子
#   4. 40 × 256KB，每次 Connection: close —— App 现在（v0.5.2）的样子
#   5. 两路并发各拉一半 —— 双轨解码场景，看并发能否叠加
#
# 判读：
#   · 第 2 项就低于 10MB/s → NAS 或网络带宽不够，跟 App 无关
#   · 第 3 项明显高于第 4 项 → 就是「每块新建连接」的开销，App 侧可优化
#   · 第 2 项达标但第 5 项不叠加 → NAS 或 AP 对单流限速
#
# 双路 3000×3000 HEVC 实测码流约 80 Mbps ≈ 10 MB/s，这是播放不卡的下限。

set -u

URL_BASE="${1:-}"
USER_NAME="${2:-}"
PASSWORD="${3:-}"
SIZE_MB="${4:-64}"

if [ -z "$URL_BASE" ]; then
  echo "用法: $0 <webdav根地址> [用户名] [密码] [测试大小MB]"
  echo "例:   $0 http://192.168.31.234:23531/osmo360 osmo360 'mypass' 64"
  exit 1
fi

command -v curl >/dev/null 2>&1 || { echo "需要 curl"; exit 1; }

# macOS 的 date 不支持 %N，用 python3 取毫秒（没有就退回秒级）
now_ms() {
  if command -v python3 >/dev/null 2>&1; then
    python3 -c 'import time; print(int(time.time()*1000))'
  else
    echo $(( $(date +%s) * 1000 ))
  fi
}

# bash 3.2 在 set -u 下展开空数组会报 unbound variable，用 ${A[@]+...} 兜住
AUTH=()
[ -n "$USER_NAME" ] && AUTH=(-u "$USER_NAME:$PASSWORD")
BASE="${URL_BASE%/}"

# 字节数 → "x.xx MB/s"，$1 是字节数，$2 是毫秒
rate() {
  awk -v b="$1" -v ms="$2" 'BEGIN {
    if (ms <= 0) ms = 1
    printf "%.2f MB/s", (b / 1048576) / (ms / 1000)
  }'
}

echo "== 1. 连接与列目录 =="
curl -s -o /dev/null -w "  PROPFIND 状态=%{http_code}  总耗时=%{time_total}s  建连=%{time_connect}s  首字节=%{time_starttransfer}s\n" \
  ${AUTH[@]+"${AUTH[@]}"} -X PROPFIND -H "Depth: 1" -H "Content-Type: application/xml" \
  --data '<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:getcontentlength/><d:getlastmodified/><d:resourcetype/></d:prop></d:propfind>' \
  "$BASE/" || { echo "  连接失败，检查地址 / 端口 / 账号"; exit 1; }

# 找一个 .OSV/视频文件；没有就让用户手动指定
FILE=$(curl -s ${AUTH[@]+"${AUTH[@]}"} -X PROPFIND -H "Depth: 1" \
  --data '<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:getcontentlength/><d:resourcetype/></d:prop></d:propfind>' \
  "$BASE/" | grep -oiE '<[^>]*href>[^<]*\.(OSV|INSV|MP4)' | sed 's/.*<[^>]*>//' | head -1)

if [ -z "$FILE" ]; then
  echo "  目录里没找到视频文件；请把一个 .OSV 放到该目录，或把第一个参数换成文件的完整 URL"
  exit 1
fi
case "$FILE" in
  http*) FILE_URL="$FILE" ;;
  /*)    FILE_URL="http://$(echo "$BASE" | sed -E 's#^https?://##')$FILE" ;;
  *)     FILE_URL="$BASE/${FILE#/}" ;;
esac
echo "  测试文件: $FILE"

BYTES=$((SIZE_MB * 1024 * 1024))
RANGE_END=$((BYTES - 1))

echo
echo "== 2. 单请求拉 ${SIZE_MB}MB（带宽上限）=="
read -r SPD TM < <(curl -s -o /dev/null -w "%{speed_download} %{time_total}" \
  ${AUTH[@]+"${AUTH[@]}"} -r "0-$RANGE_END" "$FILE_URL")
awk -v s="${SPD:-0}" -v t="${TM:-0}" 'BEGIN {
  if (s + 0 <= 0) {
    printf "  没拿到数据（服务器可能不支持 Range），耗时=%ss\n", t
  } else {
    printf "  %.2f MB/s  耗时=%.2fs\n", s / 1048576, t
  }
}'

BLOCK=262144
COUNT=40

# $1 = keepalive | close。整个循环跑在同一个 curl 进程里，避免进程启动开销干扰。
run_blocks() {
  local mode="$1" i s e
  local -a args=()
  for ((i = 0; i < COUNT; i++)); do
    s=$((i * BLOCK)); e=$((s + BLOCK - 1))
    [ $i -gt 0 ] && args+=(--next)
    args+=(-s -o /dev/null)
    args+=(${AUTH[@]+"${AUTH[@]}"})
    args+=(-r "$s-$e")
    [ "$mode" = close ] && args+=(-H "Connection: close")
    args+=("$FILE_URL")
  done
  local t0 ms
  t0=$(now_ms)
  curl "${args[@]}"
  ms=$(( $(now_ms) - t0 ))
  rate $((COUNT * BLOCK)) "$ms"
  printf "（%.2fs / %d 块）" "$(awk -v m="$ms" 'BEGIN{print m/1000}')" "$COUNT"
}

echo
echo "== 3. 40 次 256KB，连接复用（keep-alive）=="
printf "  合计 10MB → "; run_blocks keepalive; echo

echo
echo "== 4. 40 次 256KB，每次 Connection: close（App 现在的行为）=="
printf "  合计 10MB → "; run_blocks close; echo

echo
echo "== 5. 两路并发各拉一半（双轨解码场景）=="
HALF=$((BYTES / 2)); HALF_END=$((HALF - 1))
T0=$(now_ms)
curl -s -o /dev/null ${AUTH[@]+"${AUTH[@]}"} -r "0-$HALF_END" "$FILE_URL" &
P1=$!
curl -s -o /dev/null ${AUTH[@]+"${AUTH[@]}"} -r "$HALF-$RANGE_END" "$FILE_URL" &
P2=$!
wait $P1 $P2
MS=$(( $(now_ms) - T0 ))
printf "  合计 %dMB → " "$SIZE_MB"; rate "$BYTES" "$MS"; echo
