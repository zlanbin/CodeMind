# M12 压测报告：10 并发 x 30 任务摸底

## 测试条件

- 并发数：10（信号量限流，虚拟线程池发 webhook）
- 任务数：30（另加 1 个单任务 smoke test，用于验证管道可用性，数据未计入下方统计）
- `loadtest` profile 下 `StubLlmClient` 的调用延迟：`[2000ms, 8000ms]` 均匀分布，每次 `LlmClient.complete()` 调用独立取样
- 运行环境：本机 Windows 11，12 逻辑核 / 16GB 内存；PostgreSQL / Redis / Nacos / RocketMQ / Prometheus / Grafana 均为 Docker 容器（`deploy/docker-compose.yml`），review-service 与 integration-service 为本机直接起的 JVM 进程（未容器化）
- review-service 以 `--spring.profiles.active=loadtest` 启动（`StubLlmClient` 生效，无真实 LLM 调用）；integration-service 以默认 profile 启动，但**配置了真实 `GITHUB_TOKEN`**（见下方「环境修正」）
- 压测目标：`zlanbin/codemind-sandbox` PR #1（已有历史 IT 使用过的同一个真实沙箱仓库/PR，见下方「环境修正」），30 个任务共享同一 repo+PR，`head_sha` 各自用独立 UUID 区分

## 结果

| 指标 | 数值 |
|---|---|
| 发送成功（HTTP 200） | 30 / 30 |
| 发送失败（HTTP 层） | 0 |
| `review_task` 表核对（按 repo+pr_number+head_sha） | 30 / 30，与发送数一致 |
| 成功（终态 AGGREGATING） | 30 |
| 失败（终态 FAILED） | 0 |
| 超时（180s 内未到终态） | 0 |
| 延迟 P50 | 25.4s |
| 延迟 P95 | 25.5s |
| 延迟 P99 | 25.5s |
| 延迟 min / max | 20.5s / 25.5s |
| 吞吐（成功任务数/分钟，首发到末次成功完成窗口计） | 70.32 |

30/30 全部成功，无失败、无超时。延迟分布很窄（P50 到 P99 只差 0.1s），说明在 10 并发、本机资源（12 核）下，30 个任务没有相互显著排队等待，表现稳定。

## 观察现象

### 1. 各 node_type 耗时（Prometheus `codemind_dag_node_duration_seconds`，31 组样本 = 30 压测 + 1 smoke）

| node_type | 样本数 | 总耗时(s) | 均值(s) |
|---|---|---|---|
| BUILD_CHANGE_MAP | 31 | 136.775 | 4.41 |
| RECALL_RULES | 31 | 29.541 | 0.95 |
| PLAN_CHUNKS | 31 | 0.237 | 0.008 |
| REVIEW_CHUNK | 124 | 619.352 | 4.99 |
| CHUNK_REDUCE | 31 | 0.069 | 0.002 |
| GLOBAL_REDUCE | 31 | 150.748 | 4.86 |

**最慢的是 REVIEW_CHUNK**（均值 4.99s/次），其次是 BUILD_CHANGE_MAP（4.41s）和 GLOBAL_REDUCE（4.86s）——这三个都是走 `StubLlmClient` 的节点，耗时主要来自固定注入的 `[2s, 8s]` 随机延迟，均值落在区间中点附近符合预期。PLAN_CHUNKS 和 CHUNK_REDUCE 不调 LLM，耗时趋近 0（纯本地计算）。

> Grafana Token 用量面板与节点耗时 P95 面板**都没能从 Grafana 界面直接核实**，原因见下方「发现的问题」第 2、3 条——用 Prometheus API 直接查询验证的是 `sum`/`count`，不是面板本身渲染的 P95/Token 曲线。

### 2. DAG 并行节点是否真的并行

每个任务平均产生 124/31 ≈ 4 个 `REVIEW_CHUNK` 节点。若这 4 个节点在同一任务内顺序执行，预期贡献 4 × 4.99s ≈ 19.96s；加上 BUILD_CHANGE_MAP（4.41s）+ RECALL_RULES（0.95s）+ PLAN_CHUNKS（≈0）+ CHUNK_REDUCE（≈0）+ GLOBAL_REDUCE（4.86s），**若全链路顺序执行**，单任务预期总耗时 ≈ 30.2s。

实测 P50 端到端延迟为 25.4s，低于顺序执行的理论估算值，与 `DagScheduler`（入度为 0 的同层节点用虚拟线程并行执行，参见 `DagScheduler.java` 注释与 `DagSchedulerTest.parallelNodesExecuteConcurrently` 的设计）一致：多个 `REVIEW_CHUNK` 节点大概率是并行跑的，而不是相加。

**局限**：这是基于聚合均值的粗略推断，不是逐任务的节点级时间戳对比（压测工具没有采集单任务内每个节点的开始/结束时间，Prometheus 的 summary 类型也只给汇总 sum/count，拿不到单任务分布）。要严谨验证需要在 `DagScheduler` 或节点执行处加日志打点 taskId+nodeType+start/end，这次压测没有做到这一步。

### 3. call_site 维度的 token 用量分布

**无法报告：`codemind_llm_tokens_total` 在本次压测期间完全没有产生任何数据点**（`curl .../actuator/prometheus | grep codemind_llm_tokens_total` 返回空，Grafana Token 面板对应查询 `sum by (call_site) (codemind_llm_tokens_total)` 同样返回空结果）。根因见下方「发现的问题」第 1 条——这不是本次压测操作上的失误，是 `StubLlmClient`（M12 Task 5 产物）本身没有打这个点。

## 环境修正（非压测本身范围，但压测过程中发现必须处理才能跑通）

1. **review-service 评审流程会经 integration-service 的 `/internal/pulls/diff` 回头拉真实 GitHub diff，`loadtest` profile 只替身了 LLM 调用，没有替身这条路径。** 最初按 brief 用随机 UUID 拼的虚构 repo 名（`codemind/loadtest-<uuid>`）全部在 `fetchPullRequestDiff` 阶段收到 GitHub 404，30 个任务全部在数秒内 FAILED（远早于 stub LLM 延迟应该产生的耗时），和"跑太快"的异常现象对应。
   修正：改为 30 个任务共享同一个真实存在的仓库 `zlanbin/codemind-sandbox` PR #1（该仓库此前已被 `RealPullRequestReviewIT` 等验收用例使用过，是用户自己的沙箱仓库，本次压测只做只读的 diff/文件内容拉取，不发起任何写操作），每个任务的 webhook payload 仅在 `head_sha`/`base_sha` 上用独立 UUID 区分，满足 `review_task` 的 `(repo, pr_number, head_sha)` 唯一索引、不互相冲突。
   `LoadTestRunner` 相应改为从 `CODEMIND_TEST_REPO` / `CODEMIND_TEST_PR` 环境变量读取目标仓库和 PR 号（与现有 `RealPullRequestReviewIT` 用的环境变量名一致），不再自己拼虚构仓库名。
2. **integration-service 最初未设置 `GITHUB_TOKEN` 启动，匿名调 GitHub API 限流很低（60 次/小时）。** 30 个任务并发向同一个真实 PR 拉 diff + 文件内容，匿名限流下很可能在压测过程中被 GitHub 拒绝。修正：重启 integration-service 前额外设置了 `GITHUB_TOKEN`（从本机 `C:\Users\35229\.codemind-secrets.env` 读取，只导出这一个变量用于此次压测，未写入任何文件或打印到此报告），重启后日志确认不再出现"未配置 GITHUB_TOKEN"的警告，压测期间也没有出现 403/限流相关的错误日志。

这两点都是 brief 没有预见到的真实环境约束，在此如实记录；没有擅自改变压测规模（仍是 10 并发 x 30 任务），只是调整了压测目标的构造方式使其能在当前架构下跑通。

## 发现的问题（需要用户决策，本次任务范围内不处理）

1. **`StubLlmClient`（M12 Task 5）没有打 `codemind_llm_tokens_total` 指标，导致 `loadtest` profile 下这个指标完全没有数据。** 对比 `SpringAiLlmClient`（生产实现）在每次调用后会读取 usage 并打点，`StubLlmClient` 的 `complete()` 只是 sleep + 返回固定 JSON，完全没有接触 `MeterRegistry`。
   这与 brief 给出的设计意图直接冲突：brief 原文要求"`loadtest` 下仍要打点验证埋点链路本身工作，不是只在生产装配下才生效"。实际情况是埋点链路（Micrometer → Prometheus → Grafana 的转发与抓取）本身是通的——`codemind_dag_node_duration` 这条链路在压测中确实产生了数据——但 token 这条指标因为 `StubLlmClient` 没调用打点代码，从源头就没有数据产生，不是传输或抓取环节的问题。
   这是 Task 5 遗留的实现缺口，不在本次 Task 6（压测工具+报告）范围内修复；是否要给 `StubLlmClient` 补一个固定假 token 数的打点（比如固定返回 prompt=500/completion=200 之类的常量），需要用户确认后回 S5 补。
2. **Grafana dashboard 的节点耗时 P95 面板查询 `histogram_quantile(0.95, sum by (le, node_type) (rate(codemind_dag_node_duration_seconds_bucket[5m])))`，但 `MicrometerMetricsRecorder` 用 `meterRegistry.timer(...)` 注册的是普通 Timer，默认只导出 `_count`/`_sum`/`_max`，没有开启 `publishPercentileHistogram`，Prometheus 端根本没有 `_bucket` 系列。** 本次压测期间直接拿 dashboard 用的 PromQL 向 Prometheter 查询，结果为空（`{"status":"success","data":{"resultType":"vector","result":[]}}`），不是 Grafana 面板渲染问题，是底层指标类型和 dashboard 查询语句不匹配。
   这同样是更早任务（Task 3/4）遗留的问题，不在本次范围内修复。报告里"最慢的 node_type"结论改用 `sum`/`count` 算出的均值替代（见上方表格），不是真正的 P95。
3. **因为上述两点，brief Step 5（Grafana 界面核实两个面板有数据点）实际验证结果是：两个面板都没有数据点**，不是"核实通过"。这点需要向用户如实报告，不能跳过不提或含糊带过。

## 结论

压测工具本身按 brief 要求的流程（并发发送、独立 head_sha、轮询终态、核对数量一致性、汇总统计）跑通，30/30 成功、延迟与吞吐数据真实可信。但压测过程中发现 Task 5 的 `StubLlmClient` 和 Task 3/4 的 Grafana P95 面板存在两个此前未被发现的实现缺口，导致 brief Step 4/5 要求的指标核实项**未能完全验证通过**：
- `codemind_dag_node_duration` 这条埋点链路验证通过（有真实数据，端到端打通）。
- `codemind_llm_tokens_total` 在 `loadtest` profile 下**未验证通过**（源头未打点，不是传输问题）。
- Grafana 节点耗时 P95 面板**未验证通过**（指标类型与面板查询不匹配，从未产生过数据）。

按硬门禁"跳过的测试不算通过"的精神类推，这两项不应标记为完成，需要用户决定是否回 S5 补齐（给 `StubLlmClient` 补 token 打点、给 `MicrometerMetricsRecorder` 开启 histogram 或改 dashboard 查询用 `_max`/均值替代）。
