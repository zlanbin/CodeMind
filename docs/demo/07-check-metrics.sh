#!/usr/bin/env bash
# M13 演示脚本第 7 步：查 Grafana 后端 API 核实两个面板有真实数据（M12 验证点同款做法）。
set -euo pipefail

GRAFANA_USER="${GRAFANA_USER:-admin}"
GRAFANA_PASS="${GRAFANA_PASS:-codemind}"

echo "查询数据源 uid..."
UID_JSON=$(curl -sf -u "$GRAFANA_USER:$GRAFANA_PASS" http://127.0.0.1:3000/api/datasources)
DS_UID=$(printf '%s' "$UID_JSON" | grep -o '"uid":"[^"]*"' | head -1 | cut -d'"' -f4)
if [ -z "$DS_UID" ]; then
  echo "未查到 Prometheus 数据源 uid，Grafana 可能还没 provisioning 完成" >&2
  exit 1
fi
echo "数据源 uid: $DS_UID"

query() {
  local promql="$1"
  curl -sf -u "$GRAFANA_USER:$GRAFANA_PASS" \
    --data-urlencode "query=${promql}" \
    "http://127.0.0.1:3000/api/datasources/proxy/uid/${DS_UID}/api/v1/query"
}

echo ""
echo "=== Token 用量面板：sum by (call_site) (codemind_llm_tokens_total) ==="
query 'sum by (call_site) (codemind_llm_tokens_total)' | tee /tmp/codemind-demo/metrics-tokens.json
echo ""

echo "=== DAG 节点耗时 P95 面板（需最近 5 分钟内有真实流量，否则滑出窗口返回 NaN）==="
query 'histogram_quantile(0.95, sum by (le, node_type) (rate(codemind_dag_node_duration_seconds_bucket[5m])))' \
  | tee /tmp/codemind-demo/metrics-node-duration-p95.json
echo ""

echo "如果上面两段 JSON 的 result 数组非空且 value 不是 \"NaN\"，面板核实通过。"
echo "若 P95 查询返回 NaN：说明最近 5 分钟窗口内没有新流量（见 docs/reports/M12-loadtest.md 的时间窗口陷阱记录），"
echo "重跑一次 04-trigger-review.sh 或 06-incremental-push.sh 再立即重跑本脚本即可。"
