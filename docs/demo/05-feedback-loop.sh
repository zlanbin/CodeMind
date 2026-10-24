#!/usr/bin/env bash
# M13 演示脚本第 5 步：反馈闭环（M9a/M9b）。
# 前置（人工在 GitHub 网页上做，真实外部写操作）：
#   1. 用 lan-bin 账号打开上一步评审发布的 PR 行级评论
#   2. 对其中一条回复「本项目不需要这个校验，xxx 场景下是预期行为」
#   3. 对同一条评论点 👎（reaction: thumbs down / content=-1）
# 做完后再运行本脚本触发轮询。
set -euo pipefail

CODEMIND_DEMO_REPO="${CODEMIND_DEMO_REPO:?}"
CODEMIND_DEMO_PR="${CODEMIND_DEMO_PR:?}"

echo "触发反馈轮询（repo=${CODEMIND_DEMO_REPO}, pr=${CODEMIND_DEMO_PR}）..."
curl -sf -X POST "http://127.0.0.1:8081/internal/feedback/poll?repo=${CODEMIND_DEMO_REPO}&prNumber=${CODEMIND_DEMO_PR}"
echo ""

echo "等待 memory 学到 REJECTED_PATTERN（抽取 + 冲突消解走异步 MQ，预计 10~30s）..."
sleep 15

echo "查询 memory 召回，确认是否学到否决记忆..."
mkdir -p /tmp/codemind-demo
RECALL_PAYLOAD=/tmp/codemind-demo/feedback-recall-request.json
# 含中文 query，写文件再用 --data-binary @file 发送，原因见 03-upload-rule.sh 注释
node -e '
const fs = require("fs");
const [, repo, outFile] = process.argv;
fs.writeFileSync(outFile, JSON.stringify({ project: repo, query: "校验", kind: "REJECTED_PATTERN", topK: 5, minScore: 0 }));
' "$CODEMIND_DEMO_REPO" "$RECALL_PAYLOAD"
curl -sf -X POST http://127.0.0.1:8083/internal/memories/recall \
  -H "Content-Type: application/json" --data-binary "@${RECALL_PAYLOAD}" \
  | tee /tmp/codemind-demo/feedback-recall.json
echo ""
echo "如果 memories 数组非空，说明 👎 + 回复已被 LLM 抽取成 REJECTED_PATTERN 记忆。"
echo "下次评审命中同类意见时，FindingFilter（M9b）会把它过滤掉，不再出现在新的评论里。"
