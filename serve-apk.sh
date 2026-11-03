#!/usr/bin/env bash
# 启动临时 HTTP 服务供 APK 下载
# 用法: ./serve-apk.sh [端口]
set -euo pipefail

PORT="${1:-10000}"
APK_DIR="$(dirname "$0")/app/build/outputs/apk/github/debug"

if [[ ! -d "$APK_DIR" ]]; then
  echo "APK 目录不存在，请先执行 ./gradlew assembleGithubDebug"
  exit 1
fi

# 杀掉占用端口的旧进程
if command -v fuser &>/dev/null; then
  fuser -k "${PORT}/tcp" 2>/dev/null || true
elif command -v lsof &>/dev/null; then
  lsof -ti :"$PORT" | xargs -r kill 2>/dev/null || true
fi

sleep 0.5

cd "$APK_DIR"

# 获取本机 IP
LOCAL_IP=$(hostname -I 2>/dev/null | awk '{print $1}')
[[ -z "$LOCAL_IP" ]] && LOCAL_IP="127.0.0.1"

echo "========================================="
echo "  APK 下载服务已启动"
echo "========================================="
echo ""
echo "可用文件:"
ls -lh *.apk 2>/dev/null | awk '{printf "  %-45s %s\n", $NF, $5}'
echo ""
echo "下载地址:"
for f in *.apk; do
  echo "  http://${LOCAL_IP}:${PORT}/${f}"
done
echo ""
echo "浏览器打开: http://${LOCAL_IP}:${PORT}/"
echo "按 Ctrl+C 停止服务"
echo "========================================="

exec python3 -m http.server "$PORT" --bind 0.0.0.0
