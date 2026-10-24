#!/usr/bin/env bash
# M13 演示脚本第 1 步：起基础设施容器，等健康检查通过。
set -euo pipefail

cd "$(dirname "$0")/../../deploy"
docker compose up -d

echo "等待 PostgreSQL / Redis / Nacos 健康检查..."
for svc in codemind-postgres codemind-redis codemind-nacos; do
  for i in $(seq 1 30); do
    status=$(docker inspect -f '{{.State.Health.Status}}' "$svc" 2>/dev/null || echo "starting")
    if [ "$status" = "healthy" ]; then
      echo "$svc: healthy"
      break
    fi
    if [ "$i" -eq 30 ]; then
      echo "$svc 在 150s 内未就绪（当前状态：$status），请检查 docker compose logs $svc" >&2
      exit 1
    fi
    sleep 5
  done
done

echo "基础设施就绪：PostgreSQL / Redis / Nacos / RocketMQ / Prometheus / Grafana"
echo "Grafana 控制台：http://127.0.0.1:3000 (admin / codemind)"
