#!/usr/bin/env bash
# M13 演示脚本第 3 步：上传团队规范文档到 rule-service（M11），供后续评审引用条款。
set -euo pipefail

SAMPLE_FILE="$(dirname "$0")/../../codemind-review/src/test/resources/rule/sample-convention.md"
PROJECT="${CODEMIND_DEMO_REPO:?请先 export CODEMIND_DEMO_REPO=<owner>/<repo>}"

mkdir -p /tmp/codemind-demo
PAYLOAD=/tmp/codemind-demo/rule-upload-request.json

# 含中文的内容必须先写临时文件再用 --data-binary @file 发送：mingw 原生 curl.exe 对
# 命令行参数/变量展开里的中文字符会按系统 locale 重新编码，直接 -d "$var" 或
# --data-raw 在 Git Bash 下会把 UTF-8 字节搞坏（Jackson 收到非法字节报「请求体无法解析」）；
# 写文件再用 @file 读，绕开这层转换，保证字节原样传递。
node -e '
const fs = require("fs");
const [, file, project, outFile] = process.argv;
const content = fs.readFileSync(file, "utf8");
fs.writeFileSync(outFile, JSON.stringify({ project, title: "示例团队规范", content }));
' "$SAMPLE_FILE" "$PROJECT" "$PAYLOAD"

echo "上传规范文档到 rule-service (project=$PROJECT)..."
curl -sf -X POST http://127.0.0.1:8084/internal/rules/documents \
  -H "Content-Type: application/json" --data-binary "@${PAYLOAD}" | tee /tmp/codemind-demo/rule-upload.json
echo ""
echo "上传完成。之后评审会在 RECALL_RULES 节点自动召回该文档条款（3.2.1 方法命名规则）。"
