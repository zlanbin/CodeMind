#!/usr/bin/env bash
# M13 演示脚本第 2 步：构建全部模块，起 6 个服务，等 /actuator/health 全 UP。
set -euo pipefail

cd "$(dirname "$0")/../.."

: "${GITHUB_WEBHOOK_SECRET:?请先运行 00-check-secrets.sh 或手动 export}"

echo "构建全部模块（跳过测试，测试在 mvn verify 里单独跑）..."
mvn -q package -DskipTests

mkdir -p /tmp/codemind-demo
declare -A JAR_OF=(
  [gateway]=codemind-gateway
  [integration]=codemind-integration
  [review]=codemind-review
  [memory]=codemind-memory
  [rule]=codemind-rule
  [admin]=codemind-admin
)
declare -A PORT_OF=(
  [gateway]=8080
  [integration]=8081
  [review]=8082
  [memory]=8083
  [rule]=8084
  [admin]=8085
)

for name in gateway integration review memory rule admin; do
  module="${JAR_OF[$name]}"
  jar="/tmp/codemind-demo/${module}-run.jar"
  cp "${module}/target/${module}-0.1.0-SNAPSHOT.jar" "$jar"
  echo "启动 $name (端口 ${PORT_OF[$name]})..."
  GITHUB_WEBHOOK_SECRET="$GITHUB_WEBHOOK_SECRET" \
    GITHUB_TOKEN="${GITHUB_TOKEN:-}" \
    CODEMIND_LLM_API_KEY="${CODEMIND_LLM_API_KEY:-}" \
    java -jar "$jar" > "/tmp/codemind-demo/${name}.log" 2>&1 &
  echo $! > "/tmp/codemind-demo/${name}.pid"
done

echo "等待全部服务 /actuator/health 返回 UP..."
for name in gateway integration review memory rule admin; do
  port="${PORT_OF[$name]}"
  for i in $(seq 1 30); do
    if curl -sf "http://127.0.0.1:${port}/actuator/health" | grep -q '"status":"UP"'; then
      echo "$name (:$port): UP"
      break
    fi
    if [ "$i" -eq 30 ]; then
      echo "$name 在 150s 内未就绪，查看日志：/tmp/codemind-demo/${name}.log" >&2
      exit 1
    fi
    sleep 5
  done
done

echo "6 个服务全部 UP。停止方式：bash docs/demo/99-stop.sh"
