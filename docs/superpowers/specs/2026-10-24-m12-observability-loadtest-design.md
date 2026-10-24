# M12 可观测与压测 设计

## 背景

ROADMAP M12 验证点：「Grafana 可见指标，压测报告存入 `docs/reports/`」。涉及模块：全部服务。
ARCHITECTURE.md 技术栈表已点名选型：`Micrometer + Prometheus + Grafana`，覆盖范围「Token 成本、
节点耗时」。无独立设计输入文档，本 spec 是本会话头脑风暴的产出。

历史遗留技术债（本里程碑要还的债）：
- M4/M6/M9 PROGRESS 均记过「Spring AI 的 token 用量 / 成本未采集，M12 接 Micrometer」
- M3 记过「`DagScheduler` 的超时机制是单节点超时」，但节点耗时从未被指标化，只有日志

## 范围

- 指标范围：只做 ARCHITECTURE 点名的两项——Token 成本、DAG 节点耗时。不做 HTTP 接口耗时/QPS、
  MQ 消费延迟、DB 连接池等（这些 Micrometer/Spring Boot Actuator 默认即有，不需要本里程碑专门开发）
- 压测范围：webhook → 评审全链路（integration 接收 webhook 到 review 任务推进到
  `AGGREGATING`/`FAILED`），不单独压测某个孤立组件
- 不做：告警规则、SLO/SLI 定义、长时间压测、生产级容量规划

## 一、指标采集

两条埋点都遵循项目既有的「domain 端口 + infrastructure 适配器」分层模式（`CLAUDE.md`：
domain 层不依赖 Spring 和任何基础设施）。

### 1.1 Token 成本

**现状**：`SpringAiLlmClient.complete()`（`codemind-review/.../infrastructure/llm/SpringAiLlmClient.java`）
用 `chatClient.prompt()...call().content()`，丢弃了 `ChatResponse`，拿不到 usage。

**改法**：
- 改用 `.call().chatResponse()` 取出完整 `ChatResponse`，从
  `response.getMetadata().getUsage()` 读 `promptTokens` / `completionTokens`（真实用量，
  不用 jtokkit 估算——Spring AI 1.1.8 对 OpenAI 兼容协议已解析 usage 字段）
- `LlmClient`（domain 端口）接口方法 `complete(systemPrompt, userPrompt)` 加一个
  `String callSite` 参数：`complete(systemPrompt, userPrompt, callSite)`。返回值不变
  （仍是 `String`，domain 不感知 token/成本概念，这是纯基础设施关切）
- 调用方（`ReviewAgent` 4 个维度各一个实例、`ChangeMapAgent`、`GlobalReduceAgent`）传入自己的
  标识：`"STYLE"` / `"SECURITY"` / `"PERFORMANCE"` / `"TEST"` / `"CHANGE_MAP"` / `"GLOBAL_REDUCE"`
  （6 个固定值，标签基数可控）
- `SpringAiLlmClient` 构造器注入 `MeterRegistry`，拿到 usage 后用
  `Counter.builder("codemind_llm_tokens_total").tag("call_site", callSite).tag("type", "prompt"/"completion")`
  分别累加 prompt/completion token 数，再把 `content()` 返回给调用方
- 若 `getUsage()` 返回 null 或 usage 字段缺失（中转端点可能不是所有模型都带 usage），按 0 记录并
  log.warn 一次，不抛异常、不中断调用链（和既有的「重试耗尽降级」思路一致，指标缺失不能影响评审）

### 1.2 DAG 节点耗时

**现状**：`DagScheduler.executeNode()`（`codemind-review/.../domain/dag/DagScheduler.java`）只有
`log.info`/`log.warn`，`NodeRun` 落库了开始/完成时间戳但没有指标化。`DagScheduler` 是纯 domain 层，
不能直接依赖 Micrometer。

**改法**：
- `Node` 接口（`domain/dag/Node.java`）加 `default String nodeType() { return getClass().getSimpleName(); }`
  各实现类覆写返回固定类型常量：`ReviewChunkNode→"REVIEW_CHUNK"`、`BuildChangeMapNode→"BUILD_CHANGE_MAP"`、
  `ChunkReduceNode→"CHUNK_REDUCE"`、`GlobalReduceNode→"GLOBAL_REDUCE"`、`RecallRulesNode→"RECALL_RULES"`、
  `PlanChunksNode→"PLAN_CHUNKS"`（6 个固定值，标签基数可控；不用 `node.id()`，那是任务内动态生成的
  字符串，直接当标签会导致 Prometheus 标签基数爆炸）
- domain 新增端口 `domain/dag/MetricsRecorder`：
  ```java
  public interface MetricsRecorder {
      void recordNodeExecution(String nodeType, long durationMillis, boolean success);
  }
  ```
- `DagScheduler` 构造器新增 `MetricsRecorder` 参数（和现有 `NodeRunRepository` 同等地位的端口依赖）；
  `executeNode()` 的 try 分支成功后、catch 分支重试耗尽前，各调一次
  `metricsRecorder.recordNodeExecution(node.nodeType(), durationMillis, success)`（success/failure
  各记一次，重试的中间失败也算一次 failure 记录，不只记最终结果——这样能在 Grafana 看到重试率）
- infrastructure 新增 `infrastructure/metrics/MicrometerMetricsRecorder implements MetricsRecorder`，
  用 `Timer.builder("codemind_dag_node_duration").tag("node_type", nodeType).tag("success", String.valueOf(success))`
  记录，注册成 bean 注入给 `DagScheduler`（现有 `DagScheduler` 的 bean 装配在
  `infrastructure/config/ReviewDagConfig`，顺带加这个参数）

## 二、Prometheus + Grafana 接入

- `deploy/docker-compose.yml` 新增两个服务：`prometheus`（挂
  `deploy/init/prometheus/prometheus.yml` 配置，scrape 全部 6 个服务的 `/actuator/prometheus`，
  15s 间隔）、`grafana`（挂数据卷持久化 + provisioning 目录自动加载数据源和 dashboard，不用手工在
  UI 里点）
- 6 个服务（gateway/integration/review/memory/rule/admin）统一在父 POM 加
  `micrometer-registry-prometheus` 依赖管理，各模块 `application.yml` 补
  `management.endpoints.web.exposure.include=health,prometheus`（当前应该已经有 health，只是补
  prometheus）
- Grafana provisioning 预置一个 dashboard JSON（`deploy/init/grafana/dashboards/codemind.json`），
  至少两个面板：
  - Token 用量按 `call_site` 分组的曲线（`sum by (call_site) (codemind_llm_tokens_total)`）
  - DAG 节点耗时按 `node_type` 分组的 P50/P95（`histogram_quantile` over `codemind_dag_node_duration`）
- 验证方式：`docker compose up -d` 后访问 Grafana（默认 3000 端口，和现有端口规划核对不冲突），
  确认两个面板有数据

## 三、压测

### 3.1 LLM 替身：`loadtest` profile

压测要跑评审全链路但不想真的调真实 LLM（中转端点偶发 524、单次最长约 150s，PROGRESS 已记录；
并发压测会把这些问题放大，而且压测的目标是摸底调度器/数据库/MQ 的吞吐，不是验证 LLM 本身）。

- 新增 Spring profile `loadtest`：`codemind-review/src/main/resources/application-loadtest.yml`
  （或等价的 profile 专属配置）
- `infrastructure/llm/StubLlmClient implements LlmClient`：`@Profile("loadtest")` 替换掉
  `@Profile("!loadtest")` 的 `SpringAiLlmClient`（两者互斥，避免同时存在两个 `LlmClient` bean 装配
  歧义）。按 `callSite` 返回预置的合法 Finding JSON 文本（4 个评审维度各给 1~2 条样例 Finding，
  `CHANGE_MAP`/`GLOBAL_REDUCE` 给对应结构的样例 JSON），`Thread.sleep` 随机 2~8 秒模拟真实延迟
  （范围取自 PROGRESS 记录的真实 LLM IT 实测耗时量级：`ReviewAgentIT` 11.76s、
  `MultiAgentIntegrationIT` 14s、`CrossSessionMapReduceIT` 48~71s，按单次 LLM 调用折算到 2~8s 量级）
- `loadtest` profile 下 token 计数仍然打点（用固定假数值，如 prompt=500/completion=200），验证
  埋点链路本身在压测时也在工作，不是只在生产装配下才生效

### 3.2 压测工具

独立 Java 小程序（放 `codemind-review/src/test/java/.../loadtest/LoadTestRunner.java`，具体位置
写计划时定，不打包进生产 jar）：

- 用虚拟线程并发发送带正确签名的 webhook POST 到 integration 的 `/webhook/github`
  （复用 README 里现成的签名脚本逻辑，用 Java 实现：HMAC-SHA256 签名）
- 每个 webhook 用不同的 `(repo, pr_number, head_sha)` 组合（避免被 M1 的幂等唯一索引拦掉）
- 发送后轮询 PG 的 `review_task.status`，记录每个任务从接收到推进到 `AGGREGATING`/`FAILED` 的
  端到端耗时
- 汇总：成功率（到 `AGGREGATING` vs `FAILED` vs 超时未完成）、延迟分布（P50/P95/P99）、吞吐
  （任务数/分钟）
- 小规模摸底：10 并发 × 30 个任务打底，目的是摸清单任务耗时分布和 DAG 并行度是否生效，不追求
  压垮系统或做严格容量规划

### 3.3 压测报告

跑完后写 `docs/reports/M12-loadtest.md`（新建 `docs/reports/` 目录）：
- 测试条件：并发数、任务数、`loadtest` profile 下 stub 延迟分布、运行环境（本机配置、容器资源）
- 结果表格：成功率、延迟分布（P50/P95/P99）、吞吐
- 配合 Grafana 指标快照/截图观察到的现象：哪个 `node_type` 最慢、DAG 并行节点是否真的并行
  （耗时是否接近最慢节点而非各节点相加）、token 用量按 `call_site` 的分布

## 风险与边界

- `loadtest` profile 的 `StubLlmClient` 与 `SpringAiLlmClient` 必须用 `@Profile` 互斥注解严格区分，
  否则 Spring 容器会因为两个 `LlmClient` bean 装配歧义启动失败（`ReviewAgentConfig` 现有的
  `Map<ReviewDimension, ReviewAgent>` 装配逻辑需要确认不受影响）
- Token usage 字段在中转端点上是否稳定返回未经验证，需要在真实环境先探一次（S5 实现时首个任务
  验证）
- Grafana/Prometheus 端口需要确认和现有 6 个服务 + PG/Redis/Nacos/RocketMQ 的端口规划不冲突
- 压测制造的 30 个 `review_task` 是测试数据，落在本机真实数据库里，压测完不清理（和 M8/M9/M10
  的 sandbox 测试数据处理口径一致：留着不影响，不另开清理流程）
