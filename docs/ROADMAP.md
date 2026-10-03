# 里程碑路线图

> 每个里程碑约等于 1 个会话的工作量。验证点不通过，不进入下一个里程碑。
> M0~M7 为最小可演示版本；M8~M10 为记忆亮点，必须做；M11~M13 按时间取舍。

| # | 里程碑 | 涉及模块 | 验证点 |
|---|---|---|---|
| M0 | Maven 多模块骨架、DDD 分层、Nacos、网关、Docker Compose | 全部骨架、deploy | `docker compose up` 后各服务注册到 Nacos，`/actuator/health` 全部 UP，`mvn verify` 通过 |
| M1 | Webhook 接入：验签、幂等、发 `PullRequestReceived` | integration、common | 同一 webhook 重放 2 次，只生成 1 个 ReviewTask；验签失败返回 401 |
| M2 | Diff 解析、AST 语义切片、Token 估算 | review（domain） | 单测：样例 diff 切片结果符合预期，无方法被截断，每片不超预算 |
| M3 | DAG 引擎：拓扑调度、并行、超时重试、动态扇出、checkpoint | review（domain + infra） | 单测：注入节点失败后续跑，已完成节点不重复执行；并行节点耗时≈最慢节点 |
| M4 | LLM 接入与结构化输出（单 Agent 跑通） | review（infra） | 集成测试：样例 diff 输出合法 Finding JSON；非法输出触发重试/降级 |
| M5 | 多 Agent 并行评审与 fingerprint 去重 | review | 同一问题被多个 Agent 命中只保留 1 条 |
| M6 | 跨会话 Map-Reduce：ChangeMap、ChunkSummary、GlobalReduce | review | 构造超预算 PR，能发现跨文件签名不一致问题 |
| M7 | 回写 PR：行级评论 + 总结报告 | integration | 在自己的 GitHub 仓库提 PR，看到行级评论 |
| M8 | memory-service：L1/L2 记忆、混合检索 | memory | 单测：写入与召回排序符合打分公式 |
| M9 | 反馈闭环与冲突消解 | integration、memory、review | 对某条意见 👎 后再提同类代码，该意见不再出现 |
| M10 | 增量评审 | review、memory | 第二次 push 只评审新 diff，不重复发评论 |
| M11 | rule-service：规范文档 RAG | rule | 上传规范文档后，评审意见能引用对应条款 |
| M12 | 可观测与压测：Token 成本、节点耗时看板 | 全部 | Grafana 可见指标，压测报告存入 `docs/reports/` |
| M13 | 文档、演示脚本、README 指标 | docs | 新环境按演示脚本从零跑通 |

## 里程碑过大时

如果一个里程碑在一个会话内做不完，拆成 `M3a / M3b`，每个子项也必须有独立验证点，并在 `PROGRESS.md` 中记录拆分。
