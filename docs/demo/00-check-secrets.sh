#!/usr/bin/env bash
# M13 演示脚本第 0 步：校验全链路演示所需密钥是否已 source。
# 用法：source ~/.codemind-secrets.env && bash docs/demo/00-check-secrets.sh
set -euo pipefail

required_vars=(
  GITHUB_TOKEN
  CODEMIND_LLM_API_KEY
)

missing=0
for v in "${required_vars[@]}"; do
  if [ -z "${!v:-}" ]; then
    echo "缺少环境变量: $v" >&2
    missing=1
  fi
done

if [ -z "${GITHUB_WEBHOOK_SECRET:-}" ]; then
  echo "提示: 未设置 GITHUB_WEBHOOK_SECRET，本脚本将使用固定测试值 'codemind-demo-secret'（仅用于本地演示，不要在生产环境这样做）" >&2
  export GITHUB_WEBHOOK_SECRET="codemind-demo-secret"
fi

if [ "$missing" -ne 0 ]; then
  echo "" >&2
  echo "请先 source 密钥文件再运行演示脚本，例如：" >&2
  echo "  source ~/.codemind-secrets.env" >&2
  exit 1
fi

echo "密钥检查通过：GITHUB_TOKEN / CODEMIND_LLM_API_KEY 已设置，GITHUB_WEBHOOK_SECRET=${GITHUB_WEBHOOK_SECRET}"
