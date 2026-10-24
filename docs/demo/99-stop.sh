#!/usr/bin/env bash
# 停止演示脚本起的 6 个服务进程（不动基础设施容器）。
set -euo pipefail

for name in gateway integration review memory rule admin; do
  pidfile="/tmp/codemind-demo/${name}.pid"
  if [ -f "$pidfile" ]; then
    pid=$(cat "$pidfile")
    if kill "$pid" 2>/dev/null; then
      echo "已停止 $name (pid=$pid)"
    else
      echo "$name (pid=$pid) 已不在运行"
    fi
    rm -f "$pidfile"
  fi
done
