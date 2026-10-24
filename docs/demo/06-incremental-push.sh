#!/usr/bin/env bash
# M13 演示脚本第 6 步：同一 PR 第二次 push（M10 增量评审）。
# 前置（人工在本地 git 里做，真实外部写操作）：
#   在 sandbox PR 对应分支上新增一个 commit 并 push，记下新的 head sha。
set -euo pipefail

: "${GITHUB_WEBHOOK_SECRET:?}"
CODEMIND_DEMO_REPO="${CODEMIND_DEMO_REPO:?}"
CODEMIND_DEMO_PR="${CODEMIND_DEMO_PR:?}"
CODEMIND_DEMO_BASE_SHA="${CODEMIND_DEMO_BASE_SHA:?}"
NEW_HEAD_SHA="${1:?用法: bash 06-incremental-push.sh <新 head sha>}"

BODY=$(cat <<EOF
{"action":"synchronize","repository":{"full_name":"${CODEMIND_DEMO_REPO}"},"pull_request":{"number":${CODEMIND_DEMO_PR},"head":{"sha":"${NEW_HEAD_SHA}"},"base":{"sha":"${CODEMIND_DEMO_BASE_SHA}"}}}
EOF
)
SIG="sha256=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$GITHUB_WEBHOOK_SECRET" | awk '{print $2}')"

echo "发送第二次 webhook（action=synchronize, head=${NEW_HEAD_SHA}）..."
curl -sf -X POST http://127.0.0.1:8081/webhook/github -H "Content-Type: application/json" \
  -H "X-GitHub-Event: pull_request" -H "X-GitHub-Delivery: demo-incr-$(date +%s)" \
  -H "X-Hub-Signature-256: $SIG" -d "$BODY"
echo ""

echo "等待第二次评审任务到达 PUBLISHED（预留 500s，原因见 04-trigger-review.sh 注释）..."
for i in $(seq 1 100); do
  status=$(docker exec codemind-postgres psql -U codemind -d codemind -tA -c \
    "select status from review_task where repo='${CODEMIND_DEMO_REPO}' and pr_number=${CODEMIND_DEMO_PR} and head_sha='${NEW_HEAD_SHA}';")
  echo "  [$i] status=$status"
  if [ "$status" = "PUBLISHED" ]; then
    echo "增量评审已发布。核对方式：PR 页面上第二次评审的行级评论数应明显少于第一次"
    echo "（只评审新 commit 带来的 diff，不是整个 PR 重新评审一遍）。"
    echo "数据库侧核对：last_reviewed_head 表应已记录本次 head/base。"
    docker exec codemind-postgres psql -U codemind -d codemind -c \
      "select repo, pr_number, base_sha, head_sha from last_reviewed_head where repo='${CODEMIND_DEMO_REPO}' and pr_number=${CODEMIND_DEMO_PR};"
    exit 0
  fi
  if [ "$status" = "FAILED" ]; then
    echo "任务 FAILED，查看 review 服务日志：/tmp/codemind-demo/review.log" >&2
    exit 1
  fi
  sleep 5
done

echo "500s 内未到达 PUBLISHED，当前状态：$status" >&2
exit 1
