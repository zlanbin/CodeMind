#!/usr/bin/env bash
# M13 演示脚本第 4 步：在 sandbox PR 上发真实 webhook，等评审走到 PUBLISHED。
# 前置：已在 zlanbin/codemind-sandbox 用 lan-bin 账号开好一个新 PR 专用于本次演示
# （token 用户不能是 PR 作者，见 docs/operations/M7b-publication.md）。
set -euo pipefail

: "${GITHUB_WEBHOOK_SECRET:?}"
CODEMIND_DEMO_REPO="${CODEMIND_DEMO_REPO:?请 export CODEMIND_DEMO_REPO=<owner>/<repo>}"
CODEMIND_DEMO_PR="${CODEMIND_DEMO_PR:?请 export CODEMIND_DEMO_PR=<pr number>}"
CODEMIND_DEMO_HEAD_SHA="${CODEMIND_DEMO_HEAD_SHA:?请 export CODEMIND_DEMO_HEAD_SHA=<完整 40 位 head sha>}"
CODEMIND_DEMO_BASE_SHA="${CODEMIND_DEMO_BASE_SHA:?请 export CODEMIND_DEMO_BASE_SHA=<完整 40 位 base sha>}"

BODY=$(cat <<EOF
{"action":"opened","repository":{"full_name":"${CODEMIND_DEMO_REPO}"},"pull_request":{"number":${CODEMIND_DEMO_PR},"head":{"sha":"${CODEMIND_DEMO_HEAD_SHA}"},"base":{"sha":"${CODEMIND_DEMO_BASE_SHA}"}}}
EOF
)
SIG="sha256=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$GITHUB_WEBHOOK_SECRET" | awk '{print $2}')"

echo "发送 webhook（action=opened）..."
curl -sf -X POST http://127.0.0.1:8081/webhook/github -H "Content-Type: application/json" \
  -H "X-GitHub-Event: pull_request" -H "X-GitHub-Delivery: demo-$(date +%s)" \
  -H "X-Hub-Signature-256: $SIG" -d "$BODY"
echo ""

echo "等待评审任务到达 PUBLISHED（真实 LLM 多 Agent 并行评审到 AGGREGATING 约 30~90s，"
echo "再等 outbox dispatcher 下一轮扫描发布，默认轮询间隔下还要再等最多 60s，总共预留 500s）..."
for i in $(seq 1 100); do
  status=$(docker exec codemind-postgres psql -U codemind -d codemind -tA -c \
    "select status from review_task where repo='${CODEMIND_DEMO_REPO}' and pr_number=${CODEMIND_DEMO_PR} and head_sha='${CODEMIND_DEMO_HEAD_SHA}';")
  echo "  [$i] status=$status"
  if [ "$status" = "PUBLISHED" ]; then
    echo "评审已回写 PR，查看 https://github.com/${CODEMIND_DEMO_REPO}/pull/${CODEMIND_DEMO_PR}"
    exit 0
  fi
  if [ "$status" = "FAILED" ]; then
    echo "任务 FAILED，查看 review 服务日志：/tmp/codemind-demo/review.log" >&2
    exit 1
  fi
  sleep 5
done

echo "500s 内未到达 PUBLISHED，当前状态：$status。常见原因见 docs/operations/M7b-publication.md；" >&2
echo "另外如果 GITHUB_TOKEN 限流打满（curl -H \"Authorization: Bearer \$GITHUB_TOKEN\" https://api.github.com/rate_limit），" >&2
echo "或 RocketMQ 的 codemind-review-completed / %RETRY%codemind-integration-review-completed 两个 topic 积压大量历史失败消息，" >&2
echo "也会让新事件一直排不到被消费（M13 演示验证时真实遇到过，用 mqadmin consumerProgress 查 Diff 是否异常偏高）。" >&2
exit 1
