#!/bin/bash
# 停止全部服务（按 run/*.pid）：先 SIGTERM 优雅停机，超时 SIGKILL 兜底
cd "$(dirname "$0")"
pids=""
for f in run/*.pid; do
  [ -f "$f" ] || continue
  name=$(basename "$f" .pid)
  pid=$(cat "$f")
  if kill -0 "$pid" 2>/dev/null; then
    kill "$pid" 2>/dev/null
    echo "==> 停止 $name (pid $pid)"
    pids="$pids $pid"
  fi
  rm -f "$f"
done
for ((i=0; i<15; i++)); do
  alive=""
  for pid in $pids; do
    kill -0 "$pid" 2>/dev/null && alive="$alive $pid"
  done
  [ -z "$alive" ] && break
  sleep 1
done
for pid in $alive; do
  kill -9 "$pid" 2>/dev/null
  echo "==> 强制结束残留进程 $pid"
done
# 垂死旧进程仍可能占着 8080 应答（Dubbo directory 已销毁 → 503），让新栈假就绪。
# 故再清理未被 pid 文件跟踪的残留实例，并等待网关端口真正释放。
for ((i=0; i<20; i++)); do
  orphans=$(ps -eo pid,command | grep -E "payment-[a-z]+/target/payment-[a-z]+-1\.0\.0\.jar" | grep -v grep | awk '{print $1}')
  if [ -z "$orphans" ] && ! lsof -nP -iTCP:"${GATEWAY_PORT:-8080}" -sTCP:LISTEN >/dev/null 2>&1; then
    break
  fi
  for pid in $orphans; do
    kill -9 "$pid" 2>/dev/null
    echo "==> 清理残留实例 $pid"
  done
  sleep 1
done
echo "==> 已停止。数据保留在 data/ 目录，重新启动 run.sh 可恢复。"