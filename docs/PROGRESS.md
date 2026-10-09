# 开发进度（会话交接文件）

> 每个会话开始时先读本文件；结束前必须更新本文件。
> 只记录事实：做了什么、怎么验证的、结果如何。未验证的内容标注「未验证」。

## 当前状态

- 当前里程碑：**M5 已完成**（验证点全绿：同一问题被多 Agent 命中只保留 1 条）；下一个会话进 M6
- 上次更新：2026-10-09
- 阻塞项：无

## 下一个会话要做的事

**进入 M6：跨会话 Map-Reduce（ChangeMap、ChunkSummary、GlobalReduce）。**

验证点（ROADMAP M6）：构造超预算 PR，能发现跨文件签名不一致问题。

要做的事（ARCHITECTURE 6 跨会话评审设计）：
1. 全局变更地图节点（`BuildChangeMapNode`）：只输入文件列表、方法签名变更、diff 统计 → 输出 `ChangeMap`
   （PR 意图、模块划分、高风险文件），之后每个子会话注入它
2. `ChunkSummary` 结构：子会话评审时，除了 Findings，还输出结构化摘要（changedSymbols、intent、risks、
   crossChunkQuestions），由 `ReviewChunkNode` 扩展返回
3. GlobalReduce 节点：只读所有 ChunkSummary（不读原始 diff），回答跨 chunk 问题，发现跨文件问题
4. `ReviewTask` 挂 ChangeMap、ChunkSummary、状态流转完善（PLANNING → REVIEWING → AGGREGATING）
5. 集成测试：构造跨文件签名不一致的多文件 diff，验证 GlobalReduce 能发现

跑验证的前置（已验证可用）：
```bash
cd deploy && docker compose up -d                  # 等 PG / Redis / Nacos healthy
export CODEMIND_LLM_API_KEY=<中转端点 Claude 分组 key>
mvn verify
```
注意：`CODEMIND_LLM_API_KEY` 不设时真实 LLM IT 会 `assumeTrue` 跳过，不算验证到。
安全提醒：key 曾两次贴进聊天记录（上一会话 + 本会话），建议轮换。

**M4 代码已就绪，可直接复用的东西：**
- 单 Agent 评审入口：`ReviewAgent.review(ReviewChunk)` → `AgentReview`（Finding 列表或降级结果）；
  Spring 装配在 `infrastructure/llm/ReviewAgentConfig`，LLM 适配器 `SpringAiLlmClient`
- 结构化输出解析：`FindingJsonParser.parse(String)` → `List<Finding>`（剥 markdown 围栏、严格校验、
  非法抛 `FindingParseException`）
- `ReviewChunkNode`：把 `ReviewAgent` 包成 DAG 节点，衔接 M3 `PlanChunksNode`（M5 多 Agent fan-out 用）
- `Finding` 值对象 + `Fingerprints.of(file, category, snippet)`（指纹已就绪，M5 去重 / M10 增量判重直接用）

M2/M3 可直接复用的东西（未变）：
- 切片入口 `SemanticChunker.chunk(List<DiffFile>, Map<path,newSource>, ChunkBudget)`，
  源码（new 侧）由调用方（integration 拉文件内容）提供，domain 不自己读文件
- `ChunkBudget.ofDefault()` = 128k×30% diff 预算；`ofDiffBudget(n)` 直接给 diff 上限（测试友好）
- DAG：`DagDefinition`（builder）+ `DagScheduler`（虚拟线程并行、checkpoint 续跑）+ `node_run` 表（V3）

## 已完成里程碑

### M5 多 Agent 并行评审与 fingerprint 去重 —— 完成于 2026-10-09

改动范围（review-service domain + infrastructure，扩展 M3 M4）：

- domain 新增 `com.codemind.review.domain.agent.ReviewDimension`：枚举（STYLE / SECURITY / PERFORMANCE / TEST）
- domain 修改 `ReviewAgent`：构造器增加 `ReviewDimension` 参数，`buildSystemPrompt()` 根据维度动态生成专属 prompt
  （M4 的通用 prompt 拆成 4 个维度各自聚焦的 prompt）
- domain 新增 `dag/ChunkReduceNode`：按 fingerprint 去重多个 Agent 对同一 chunk 的评审结果；
  同 fingerprint 只保留 severity 最高的一条（`ChunkReduceResult` 记录去重后的 Finding + 降级信息）
- domain 新增 `dag/DagOrchestrator`：动态扇出编排器，解决 M3 遗留的「PlanChunks → 为每个 chunk × 维度 fan-out 评审节点」；
  执行流程：PlanChunks 产出 chunks → 每个 chunk × 4 维度 = 4N 个 ReviewChunkNode 并行 → N 个 ChunkReduceNode 归约
- domain 修改 `DagDefinition.Builder`：新增 `addNode(Node, List<String> dependsOn)` 重载，支持声明依赖列表
- domain 修改 `ReviewTask`：新增 `assignFindings(List<Finding>)` / `findings()` 挂上评审结果（M4 遗留）
- domain 修改 `NodeContext`：新增 `initial(String)` 别名方法（测试友好）
- domain 修改 `AgentReview`：工厂方法改为 `public static`（测试可见）
- infrastructure 修改 `ReviewAgentConfig`：装配 `Map<ReviewDimension, ReviewAgent>`（4 个维度各一个 Agent）
- 测试：
  - `ReviewAgentTest`（5 个纯单测）：为各维度 Agent 补充测试，验证重试/降级
  - `ChunkReduceNodeTest`（3 个纯单测）：验证同 fingerprint 去重（保留 severity 高的）、降级信息收集、上游节点未完成抛异常
  - `DagOrchestratorTest`（2 个纯单测）：验证动态扇出 + 多 Agent 并行 + 去重（2 个 chunk × 4 维度 = 8 个并行节点）
  - `MultiAgentIntegrationIT`（Spring Boot IT，真实 LLM key 可选）：验证真实容器 + 多 Agent 并行 + fingerprint 去重
    （断言去重后所有 Finding 指纹互不相同）；无真实 key 时 `assumeTrue` 跳过
  - `ReviewAgentIT`：适配 M5 的 `Map<ReviewDimension, ReviewAgent>` 注入（取 SECURITY 维度作代表）

验证命令与结果：

| 验证点 | 命令 | 结果 |
|---|---|---|
| 同一问题被多 Agent 命中只保留 1 条 | `mvn test -pl codemind-review` | **通过**。`ChunkReduceNodeTest.deduplicatesSameFingerprintAcrossAgents` 验证：4 条原始 Finding（f1+f2 同指纹）→ 去重后 3 条，保留 severity 更高的 f2；`DagOrchestratorTest.dynamicFanOutAndDedup` 验证：2 个 chunk × 4 维度并行评审 → 各归约为 2 条去重后 Finding，且保留 severity 更高者 |
| 全仓回归 | `mvn verify`（2026-10-09 13:57） | **通过**。8 模块 BUILD SUCCESS；review 模块 **49 单测 + 15 IT** 全绿，0 失败。IT 中 2 个 skip 为真实 LLM 用例（`ReviewAgentIT` / `MultiAgentIntegrationIT`），无真实 key 时按 `assumeTrue` 跳过 |

> 注：首次 `mvn verify` 曾因 `ReviewAgentIT` 仍注入已不存在的单个 `ReviewAgent` bean 而失败
> （`ReviewAgentConfig` 改为只产出 `Map<ReviewDimension, ReviewAgent>`）。已改为注入 Map 并取
> SECURITY 维度作代表，重跑通过。

遗留问题 / 技术债：

- `ReviewTask.assignFindings` 已就绪，但尚未在完整流程中调用（`DagOrchestrator.executeReview` 返回归约结果，
  未挂回 `ReviewTask`）；状态流转 `PLANNING → REVIEWING → AGGREGATING` 也未接线（M6 完整流程时补）
- `ChunkSummary`（子会话摘要）尚未实现，`ReviewChunkNode` 只返回 `AgentReview`（Findings + 降级信息），
  未输出结构化摘要（M6 跨会话 Map-Reduce 时补）
- `DagOrchestrator` 接受泛型 `Node` 而非具体 `PlanChunksNode`，测试用 stub；真实流程接线在 M6
- `GlobalReduce` 节点（读 ChunkSummary 发现跨文件问题）留 M6
- `BuildChangeMapNode`（全局变更地图）留 M6
- 真实 LLM IT（`MultiAgentIntegrationIT`）因无真实 key 跳过，未实际验证真实模型的多维度输出；
  本地可带真实 key 补跑：`export CODEMIND_LLM_API_KEY=<key> && mvn test -Dtest=MultiAgentIntegrationIT`

与 ARCHITECTURE.md 的偏差：无。多 Agent 并行维度（STYLE / SECURITY / PERFORMANCE / TEST）按 ARCHITECTURE 5 落地；
fingerprint 去重按 ARCHITECTURE 4 落地。

## 已完成里程碑

### M4 LLM 接入与结构化输出（单 Agent）—— 完成于 2026-10-09

> 代码写于 2026-10-05；真实 LLM 验证点于 2026-10-09 补跑通过，M4 收尾。

改动范围（review-service domain + infrastructure，不碰 M1~M3 行为）：

- 根 `pom.xml`：`properties` 加 `spring-ai.version=1.1.8`，`dependencyManagement` import `spring-ai-bom`
  （Boot BOM 不管 Spring AI，版本在此锁死；PLAN 第 5 节已指定 1.1.8）
- `codemind-review` 引入 `spring-ai-starter-model-openai`（只在 infrastructure 用）
- domain 新增 `com.codemind.review.domain.finding` 包：
  - `Finding`：评审意见值对象（`file / line / severity / category / message / suggestion / fingerprint`，ARCHITECTURE 4）
  - `Severity` / `Category`：枚举（LLM 输出必须落在集合内，否则判非法）
  - `Fingerprints`：`fingerprint = hash(file + category + normalize(snippet))`（SHA-256，归一化去空白转小写）
- domain 新增 `com.codemind.review.domain.agent` 包：
  - `LlmClient`：LLM 调用端口（纯接口，domain 不依赖 Spring AI）；`LlmException` 调用失败
  - `FindingJsonParser`：剥 markdown 围栏 → Jackson 严格解析 → `List<Finding>`，非法抛 `FindingParseException`
  - `ReviewAgent`：领域服务，组 prompt → 调 `LlmClient` → 解析；**解析/调用失败重试（默认 2 次），
    重试耗尽降级**（返回空 Findings + 降级原因，不抛异常中断 DAG）
  - `AgentReview`：单 Agent 对一个 chunk 的结果（findings / degraded / degradeReason / attempts）
- domain 新增 `dag/ReviewChunkNode`：把 `ReviewAgent` 对单 chunk 的评审包成 DAG 节点，衔接 M3 `PlanChunksNode`
- infrastructure 新增 `infrastructure/llm`：
  - `SpringAiLlmClient implements LlmClient`：Spring AI `ChatClient`，OpenAI 兼容协议
  - `ReviewAgentConfig`：把 `FindingJsonParser` / `ReviewAgent` 装配成 Bean（domain 服务不带 Spring 注解）
- `application.yml`：`spring.ai.openai`（base-url 默认 `https://api.aiblitz.io`、api-key 走
  `CODEMIND_LLM_API_KEY`、model 默认 `claude-haiku-4-5-20251001`、temperature 0.0）、`codemind.llm.max-retries`
- 测试：
  - `FindingJsonParserTest`（纯单测，8 个）：合法 / 带围栏 / 空数组 / 非 JSON / 缺 findings / 枚举非法 /
    缺必填字段 / 空输入
  - `ReviewAgentTest`（纯单测，5 个，stub LlmClient）：合法一次成功、首次畸形→重试成功、
    始终畸形→重试耗尽降级、LLM 调用异常→重试降级、maxRetries=0 立即降级
  - `ReviewAgentIT`（真实 LLM）：样例 diff → 真实中转端点 → 断言输出可解析为合法 Finding JSON；
    无真实 key 时 `assumeTrue` 跳过

验证命令与结果：

| 验证点 | 命令 | 结果 |
|---|---|---|
| 非法输出触发重试/降级 | `mvn -pl codemind-review test`（surefire） | **通过**。`ReviewAgentTest` 5 个 + `FindingJsonParserTest` 8 个全绿；review 模块 44 个单测全通过 |
| 样例 diff 输出合法 Finding JSON | `mvn verify`（真实 LLM key + 中间件，2026-10-09） | **通过**。`ReviewAgentIT.realLlmProducesLegalFindingJson` 实跑 11.76s（真实网络调用，未被 `assumeTrue` 跳过）：真实 LLM 返回 **4 条 Finding，attempts=1**（首次即解析成功，`degraded()==false`）；每条 Finding 的 file / severity / category / fingerprint 均非空合法 |
| 全仓回归 | `mvn verify`（2026-10-09） | **通过**。8 模块 BUILD SUCCESS；66 单测 + 21 IT 全绿，0 失败 0 跳过 |
| 中转端点连通性（只读自测，非 IT） | `curl /v1/models`、`/v1/chat/completions` | 端点正常，`claude-haiku-4-5-20251001` 返回合法 OpenAI 格式 JSON |

本会话（2026-10-09）另修一处 M3 遗留测试缺陷：

- `ChunkRepositoryIT` 两个用例原先硬编码 `"owner/repo"` 且无清理逻辑，`review_task` 残留数据会让
  `insertIfAbsent` 被唯一索引拦掉（返回 false 但测试未断言），后续插 `review_chunk` 撞外键
  `review_chunk_task_id_fkey` 报 `DataIntegrityViolationException`。改为对齐
  `ReviewTaskRepositoryIT` 的做法：每轮用独立 repo 名（`codemind/chunk-it-<UUID>`）+ `@AfterEach`
  先删 chunk 再删 task，并关掉 Nacos discovery。修复后 2 个用例稳定通过

遗留问题 / 技术债：

- `application.yml` 的 `api-key` 给了占位默认值 `placeholder-no-key`：Spring AI 对空 key 会在**启动期**
  报错（会拖垮现有不需要 LLM 的 IT 如 `ReviewApplicationIT` / 各 RepositoryIT 的上下文加载），
  故给非空占位；真实调用由 `ReviewAgentIT` 按环境变量是否为真实 key 决定是否执行
- `ReviewChunkNode` 已能跑单 chunk，但「PlanChunks 产出 chunks → 为每个 chunk fan-out 评审节点」的
  动态扇出接线仍未做（M3 遗留 + M5 多 Agent 一起补）；`ReviewTask` 挂 Finding、状态流转到
  `REVIEWING / AGGREGATING` 也未接
- `ReviewAgent` 的 prompt 是单维度通用评审，未按 Style/Security/Perf/Test 分维度（ARCHITECTURE 5 的
  并行维度在 M5 做）；未注入 ChangeMap / 规则 / 记忆（M6 跨会话、M8 记忆时补）
- 重试退避：`ReviewAgent` 当前无退避 sleep（纯内存重试，立即重试）；真实 LLM 限流场景可能需要退避，
  观察 IT 实跑后再定
- Spring AI 的 token 用量 / 成本未采集（M12 可观测时接 Micrometer）

与 ARCHITECTURE.md 的偏差：无。技术栈表已列 Spring AI 1.1.8「M4 引入」，本次按此落地；
`LlmClient` 端口在 domain、Spring AI 适配器在 infrastructure，符合 ARCHITECTURE 3.2 分层。

### M3 DAG 引擎（拓扑调度、并行、超时重试、checkpoint）—— 完成于 2026-10-04

改动范围（review-service domain + infrastructure）：

- domain 新增 `com.codemind.review.domain.dag` 包：
  - `Node` 接口：`execute(NodeContext)`，可配置超时、重试策略
  - `NodeContext`：节点执行上下文（任务标识、已完成节点结果、共享状态）
  - `DagDefinition`：DAG 拓扑定义（节点 + 依赖关系），代码构建（builder 模式），构建时检测循环依赖
  - `DagScheduler`：拓扑调度器，虚拟线程并行执行入度为 0 的节点，checkpoint 续跑，已完成节点不重复执行
  - `NodeRun` / `NodeRunRepository`：节点运行记录（checkpoint 持久化）
  - `PlanChunksNode`：M2 `SemanticChunker` 包装成 Node（暂未接入完整 DAG 流程，M4 用）
- infrastructure：
  - `JdbcNodeRunRepository`：节点运行记录 JDBC 实现，结果序列化为 JSON
  - `JdbcChunkRepository`：语义切片 JDBC 实现（M2 建表，M3 补接线）
  - `JdbcReviewTaskRepository.updateStatus`：状态更新方法，同时刷新 `updated_at`
- domain 端口新增：
  - `ChunkRepository`：语义切片仓储接口
  - `ReviewTaskRepository.updateStatus`：状态更新方法
- `db/migration/V3__create_node_run.sql`：`node_run` 表 + `uk_node_run_task_node` 唯一索引 + `idx_node_run_task_status` 索引
- 测试：
  - `DagSchedulerTest`（纯单测）：线性 DAG 按序执行、并行节点总耗时 ≈ 最慢节点、失败节点重试、checkpoint 跳过已完成节点、循环依赖拒绝
  - `NodeRunRepositoryIT`：checkpoint 落库、状态更新、按任务查询已完成节点
  - `ChunkRepositoryIT`：切片落库、续跑时替换旧切片

验证命令与结果：

| 验证点 | 命令 | 结果 |
|---|---|---|
| 构建 + 全部测试 | `mvn verify` | 通过，8 模块；review 37 单测 + 13 IT 全绿；全仓 66 测试全通过 |
| 并行节点总耗时 ≈ 最慢节点 | `DagSchedulerTest.parallelNodesExecuteConcurrently` | B 和 C 各 sleep 100ms 并行，总耗时 < 180ms（非 200ms） |
| 已完成节点不重复执行 | `DagSchedulerTest.checkpointSkipsCompletedNodes` | A 完成后重新调度 A→B→C，只执行 B 和 C（execCount=2） |
| 失败节点重试 | `DagSchedulerTest.failedNodeRetriesAndEventuallyFails` | 初次 + 2 次重试，共 3 次执行 |
| Checkpoint 落库 | `NodeRunRepositoryIT` | 运行中 / 已完成 / 失败状态正确落库；按任务查询已完成节点 |
| 切片持久化 | `ChunkRepositoryIT` | 切片 JSON 字段正确序列化；续跑时旧切片被清空并替换 |
| V3 迁移 | `mvn verify` | Flyway `Migrating schema to version "3"`；`node_run` 表 + 唯一索引 + 普通索引均在 |

遗留问题 / 技术债：

- 动态扇出（`PlanChunks` 节点运行时按 chunk 数产出下游子图）暂未实现，M5 跨会话 Map-Reduce 时补
- `PlanChunksNode` 只做了切片落库，未接入完整 DAG 流程（如挂回聚合、状态流转），M4 接入 LLM 时完善
- `NodeRunRepository` 的结果序列化用 `ObjectMapper.readValue(..., Object.class)`，反序列化后需调用方手动转型；
  若结果类型复杂（如嵌套对象、泛型集合），需改用 `TypeReference` 或单独存类型元信息
- 虚拟线程池用 `Executors.newVirtualThreadPerTaskExecutor()`（每次并行创建新池并 shutdown），
  未复用线程池；高频调度场景可改为共享池（但需注意生命周期管理）
- `DagScheduler` 的超时机制是单节点超时（`Future.get(timeout)`），不是整个 DAG 超时；
  若需全局超时，需在调度器外包一层 `Future.get`
- 测试中清空数据用 `truncate review_chunk, review_task cascade`，IT 间可能有残留数据干扰；
  理想是每个 IT 用独立 schema 或事务回滚（`@Transactional` + `@Rollback`），但当前未配置

与 ARCHITECTURE.md 的偏差：无（动态扇出标注为 M5 实现，M3 只做基础调度，符合预期）

### M2 Diff 解析、AST 语义切片、Token 估算 —— 完成于 2026-10-04

改动范围（纯 review-service domain 层，不碰 Spring / 中间件 / LLM）：

- 根 `pom.xml`：`dependencyManagement` 锁 `javaparser-core` 3.28.2、`jtokkit` 1.1.0
  （SCA / Boot BOM 都不管这两个）。**原计划的 `java-diff-utils` 已移除**，改为自写解析器，见决策日志
- `codemind-review` 加 `javaparser-core`、`jtokkit`（compile 作用域；均为纯计算库，无 Spring，不破坏 domain 纯度）
- domain 新增 `com.codemind.review.domain.chunk` 包：
  - `DiffParser`：自写 unified diff 解析器 → `List<DiffFile>`（path、`ChangeType`、`DiffHunk` 行号区间 + 原始行）
  - `DiffFile` / `DiffHunk` / `ChangeType`：diff 值对象
  - `TokenEstimator` 接口 + `JtokkitTokenEstimator`（jtokkit `cl100k_base` 精确计数）
  - `ChunkBudget`：可配置总预算 + 默认 128k；`diffBudget()` = 总预算 × 30%（ARCHITECTURE 6.3）
  - `SemanticChunker`：Java 按 AST 方法边界切（`JavaMethodIndex` + `JavaParser`），非 Java / 解析失败降级为 hunk 切片；
    跨文件按调用关系合并（`Unit` + `DisjointSet` 并查集）；按 diff 预算贪心装箱；单 unit 超预算整片输出并标 `oversized`
  - `ReviewChunk` 值对象（chunkId、files、changedSymbols、diffContent、tokenEstimate、oversized）
- `ReviewTask` 聚合根加 `assignChunks(...)` / `chunks()`（只读视图），挂上切片结果
- `db/migration/V2__create_review_chunk.sql`：`review_chunk` 表 + `(task_id, seq)` 唯一索引 + FK 到 `review_task`
  （**只建表，JdbcChunkRepository 留 M3**）
- 测试资源 `src/test/resources/diff/`：`single-method` / `multi-method` / `cross-file` / `oversized-method`
  四个 diff，Java 用例配套 `src/*.java` 完整新源码

验证命令与结果：

| 验证点 | 命令 | 结果 |
|---|---|---|
| 构建 + 全部测试 | `mvn verify` | 通过，8 模块；review 25 单测 + 8 IT 全绿；全仓 IT 保持通过 |
| 切片结果符合预期 | `SemanticChunkerTest` | 单方法→1 片且符号=`OrderService#cancel`；跨文件调用关系→合并成 1 片含 2 文件 |
| 无方法被截断 | `SemanticChunkerTest` | 多方法预算收紧时按方法切成 2 片、各含 1 完整方法；oversized 场景方法体每行仍在片内 |
| 每片不超预算 | `everyNonOversizedChunkStaysWithinBudget...`（真实 jtokkit） | 非 oversized 片的 tokenEstimate ≤ diffBudget |
| 退化场景 | `oversizedMethodBecomesOneChunkFlagged...` | 单方法超小预算→1 片 `oversized=true`，不截断不丢弃 |
| diff 解析正确性 | `DiffParserTest` | 单/多 hunk、多文件不串接、增删文件、重命名、空输入 |
| V2 迁移 | `mvn verify`（review IT） | Flyway `Migrating schema to version "2"`；`\d review_chunk` 表 + 唯一索引 + FK + check 均在 |

遗留问题 / 技术债：

- `review_chunk` 表已建但无持久化接线（`JdbcChunkRepository` 未写），M3 落 checkpoint 时补
- `SemanticChunker` 的跨文件「调用关系」用的是轻量启发式（B 的 diff 文本里出现 A 的 `方法名(` 即判相关），
  不是真实调用图分析；足够 M2，M6 跨会话 Map-Reduce 若需更准可升级
- `DiffParser` 只解析评审需要的部分（path / changeType / hunk 行号 / 原始行），不解析 `index` 哈希、
  不校验 hunk 行数自洽、二进制文件（`Binary files ... differ`）跳过无 hunk
- `JavaMethodIndex` 的方法限定名是「外层类名#方法名」，非完全限定名（不含包名 / 不处理嵌套类同名方法歧义），
  用于分组足够；若 M5 fingerprint 需要全限定名再升级
- surefire 3.x 下 `-Dtest='pkg.*'` 包通配不生效（会「No tests executed」），跑单包用 `mvn test` 全量或 `-Dtest=ClassName`

与 ARCHITECTURE.md 的偏差：无（diff 解析改自写是实现手段，不影响架构；ARCHITECTURE 2 的技术栈表仍列
java-diff-utils 作为「代码解析」候选，已不使用——留待下次架构复盘时清理，不属 M2 范围）

### M1 Webhook 接入 —— 完成于 2026-10-03

改动范围：

- 根 `pom.xml`：锁 `rocketmq-spring-boot-starter` 2.3.4（SCA BOM 只管 `rocketmq-client`，不管 starter）；
  `pluginManagement` 加 `maven-failsafe-plugin` 并绑定 `integration-test` / `verify`
- `codemind-common`：`event/PullRequestReceived`（record，实现 `DomainEvent`）
- `codemind-integration`（加 `spring-boot-starter-data-redis`、`rocketmq-spring-boot-starter`）
  - domain：`WebhookSignatureVerifier`（HMAC-SHA256，`MessageDigest.isEqual` 常量时间比对）、
    `WebhookDeliveryStore`、`DomainEventPublisher`
  - application：`WebhookApplicationService`（验签 → 过滤 → 幂等 → 发事件）、`WebhookResult`、
    `InvalidSignatureException`、端口 `WebhookPayloadParser` / `PullRequestPayload`
  - infrastructure：`GithubWebhookPayloadParser`、`RedisWebhookDeliveryStore`（`SET NX EX`）、
    `RocketMqDomainEventPublisher`、`WebhookConfig`
  - interfaces：`GithubWebhookController`（`POST /webhook/github`）、`WebhookExceptionHandler`（401 / 400）
- `codemind-review`（加 jdbc、postgresql、flyway-core + flyway-database-postgresql、rocketmq starter、awaitility）
  - domain：`ReviewTask` 聚合根、`ReviewTaskStatus` 状态机、`ReviewTaskRepository`
  - application：`ReviewTaskApplicationService`
  - infrastructure：`JdbcReviewTaskRepository`（`on conflict do nothing`）
  - interfaces：`PullRequestReceivedListener`
  - `db/migration/V1__create_review_task.sql`：`review_task` 表 + `uk_review_task_repo_pr_head` 唯一索引
- `deploy/init/rocketmq/broker.conf`（新增）+ `docker-compose.yml`：broker 挂配置并配 `brokerIP1=127.0.0.1`

验证命令与结果：

| 验证点 | 命令 | 结果 |
|---|---|---|
| 构建 + 全部测试 | `mvn verify` | 通过，27 个单测 + 15 个 IT 全绿 |
| 签名错误返回 401 | curl 带 `sha256=deadbeef` | 401，`code=40100`，且未占用 Redis 幂等键 |
| 缺签名头返回 401 | curl 不带签名头 | 401 |
| 同 webhook 重放 2 次只建 1 个 ReviewTask | 同一 `X-GitHub-Delivery` 连发 2 次 | 第 1 次 `ACCEPTED`、第 2 次 `DUPLICATE`；`review_task` 1 行，状态 `CREATED` |
| Redis 键过期后重放仍只有 1 行 | 换新 deliveryId 重投同一 `(repo, pr, headSha)` | 接口 `ACCEPTED`，唯一索引拦住，表仍 1 行，日志「评审任务已存在，跳过」 |
| 同 PR 不同 headSha 建新任务 | 改 headSha 重投 | 表 2 行，符合预期（新 push 要重新评审） |
| 非目标动作 / 非 pull_request 事件 | `action=closed`、`X-GitHub-Event: push` | 均 200 + `IGNORED`，不发事件 |
| 请求体缺字段 | 残缺 JSON | 400，错误信息指出缺失字段路径 |

遗留问题 / 技术债：

- 新建 topic 首次投递的消费延迟约 20s（consumer 需等路由刷新发现新队列）；topic 存在后稳定在 4s 内。
  本地开发靠 `autoCreateTopicEnable`，非本地环境应提前用 `mqadmin updateTopic` 建好 topic
- 消息体用 Boot 的 `ObjectMapper` 手动序列化成 JSON 字符串收发，没走 starter 自带的 MessageConverter
  （它的 `ObjectMapper` 未注册 `JavaTimeModule`，直接发 record 会在 `Instant` 字段抛
  `InvalidDefinitionException`）。后续事件变多时考虑统一封装一层
- 反序列化失败目前抛异常让 RocketMQ 重试，死信队列的处理策略没定（M9 反馈闭环时一并考虑）
- `PullRequestReceivedListener` 没做消费侧的 `eventId` 幂等，依赖 DB 唯一索引兜底；M3 引入
  checkpoint 后需要重新评估
- RocketMQ broker / namesrv 仍无 healthcheck（M0 遗留项，未解决）
- `ReviewTaskStatus` 的状态流转只有单测覆盖，`updated_at` 字段建了但还没有更新路径（M3 用）

与 ARCHITECTURE.md 的偏差：无（`(repo, pr_number, head_sha)` 唯一索引提前到 M1，见决策日志）

### M0 工程骨架与基础设施 —— 完成于 2026-10-03

改动范围：
- 根 `pom.xml`：父 POM，锁定 Spring Boot 3.5.16 / Spring Cloud 2025.0.3 / SCA 2025.0.0.0，编译目标 release 21
- `codemind-common`：`Result` 统一响应、`DomainEvent` 事件契约
- 6 个服务模块：gateway(8080)、integration(8081)、review(8082)、memory(8083)、rule(8084)、admin(8085)，每个带 DDD 四层 package-info 和启动类
- `deploy/docker-compose.yml`：PostgreSQL 16+pgvector、Redis 7、Nacos 3.0.3、RocketMQ 5.3.1
- `deploy/init/postgres/01-extension.sql`：建 vector 扩展
- `README.md`、`.gitignore`

验证命令与结果：

| 验证点 | 结果 |
|---|---|
| `mvn verify` | 通过，8 模块 8 个测试全绿 |
| `docker compose up -d` | 5 个容器启动，PG / Redis / Nacos 健康检查 healthy |
| pgvector 扩展 | `select extname from pg_extension` 返回 `vector` |
| 6 服务 `/actuator/health` | 全部 `UP`，discoveryClient 为 UP |
| Nacos 注册 | `/v3/admin/ns/service/list` 返回 totalCount=6，6 个服务都在 |
| 网关按服务名路由 | `/codemind-{integration,review,memory,rule,admin}/actuator/health` 全部返回 UP |

遗留问题 / 技术债：
- Nacos 本地关闭了鉴权，非本地环境必须开启并初始化管理员密码
- RocketMQ broker 和 namesrv 没配 healthcheck，M1 用到时再补
- 服务启动靠手工敲 6 条 java -jar，M13 补演示脚本
- 还没接数据库和 Redis 连接（M1、M2 按需引入）

<!--
每完成一个里程碑，按以下模板追加一节：

### M? 名称 —— 完成于 YYYY-MM-DD
- 改动范围：模块 / 关键类
- 验证命令：
- 验证结果：
- 遗留问题 / 技术债：
- 与 ARCHITECTURE.md 的偏差：（无 / 已同步更新）
-->

## 决策与偏差日志

| 日期 | 里程碑 | 决策 / 偏差 | 原因 |
|---|---|---|---|
| 2026-10-03 | M0 | 本机 JDK 26 直接构建，未装 JDK 21 | 实测 Boot 3.5.16 构建和测试均正常，编译目标已设为 release 21 |
| 2026-10-03 | M0 | Nacos 关闭鉴权 | Nacos 3.x 开启鉴权需先初始化管理员密码，无默认 nacos/nacos 账号。本地开发不值得为此加流程 |
| 2026-10-03 | M0 | Nacos 即使关鉴权也必须配 `NACOS_AUTH_TOKEN` 等三个变量 | 启动脚本强制校验，缺失则 exit 255 |
| 2026-10-03 | M0 | 网关加 `spring-cloud-starter-loadbalancer` | discovery locator 按服务名路由需要它解析实例，缺失时返回 503 |
| 2026-10-03 | M0 | 网关测试中关闭 discovery locator | locator 在无 Nacos 的测试环境需要 ReactiveDiscoveryClient，否则上下文加载失败 |
| 2026-10-03 | M0 | RocketMQ Proxy 映射到宿主机 18080 | 原计划 8081 与 integration-service 冲突 |
| 2026-10-03 | M0 | Nacos 管理 API 路径为 `/nacos/v3/admin/ns/...`，需带 identity 请求头 | 3.x 废弃 v1/v2 路径 |
| 2026-10-03 | M1 | `mvn verify` 依赖真实 Redis / MQ / PG，不用 mock、不用 Testcontainers | 用户要求用真实中间件；Testcontainers 还要额外拉 ryuk 镜像，本环境 Maven Central 不可达，不值得冒风险 |
| 2026-10-03 | M1 | 测试拆成 `*Test`（surefire，纯单测）和 `*IT`（failsafe，需中间件） | 纯逻辑测试仍能快速反馈；父 POM 未默认绑定 failsafe 执行，在根 POM 补了绑定 |
| 2026-10-03 | M1 | 原 `IntegrationApplicationTest` / `ReviewApplicationTest` 改名为 `*IT` | 加了 Redis / MQ / PG 依赖后，上下文加载已离不开真实中间件 |
| 2026-10-03 | M1 | `(repo, pr_number, head_sha)` 唯一索引提前到 M1 | M1 已需建 `review_task` 表，顺带加索引比留到 M2 更省事（原计划见 PROGRESS 旧版 M1 条目） |
| 2026-10-03 | M1 | `rocketmq-spring-boot-starter` 版本自行锁 2.3.4 | SCA 2025.0.0.0 BOM 只管 `rocketmq-client`，不管 Spring Boot starter；2.3.4 带 Boot 3 的 `AutoConfiguration.imports` |
| 2026-10-03 | M1 | broker 加 `broker.conf` 并配 `brokerIP1=127.0.0.1` | broker 默认把容器内网 IP 注册到 namesrv，宿主机上跑的服务拿到地址后连不上 |
| 2026-10-03 | M1 | 事件消息体手动序列化为 JSON 字符串 | starter 内置 MessageConverter 的 `ObjectMapper` 未注册 `JavaTimeModule`，`Instant` 字段会抛 `InvalidDefinitionException` |
| 2026-10-03 | M1 | Flyway 需额外加 `flyway-database-postgresql` | Flyway 11 起把 PostgreSQL 支持拆成独立 artifact，只有 `flyway-core` 会启动失败 |
| 2026-10-03 | M1 | 幂等检查放在载荷解析之后 | 解析失败属于坏请求，不该把 deliveryId 占住导致重投被当成重放丢弃 |
| 2026-10-03 | M1 | 控制器用 `byte[]` 接收请求体 | 验签必须对原始字节算 HMAC，反序列化再序列化会改变字节内容导致验签失败 |
| 2026-10-04 | M2 | 移除 `java-diff-utils`，自写 unified diff 解析器 | 实测 4.17 的 `UnifiedDiffReader` 解析真实 GitHub diff 不可靠：单文件 EOF 丢最后一个 hunk（deltas=0 或抛 `expected data line not found`）、多文件 git diff 直接抛异常、无 `diff --git` 头时后一文件的 `--- ` 头被当成前一 hunk 的上下文行静默串接（files 数变少）。自写约 180 行、纯 Java、全覆盖单测，可控性和正确性都更好 |
| 2026-10-04 | M2 | token 估算用 jtokkit `cl100k_base` 真实 BPE 分词 | 用户选真实分词器而非字符数启发式；已验证阿里云镜像可拉到 jtokkit 1.1.0；jtokkit 纯计算无 Spring 依赖，放 domain 层不破坏分层 |
| 2026-10-04 | M2 | `SemanticChunker` 入参为 diff + 文件完整源码（new 侧），源码由调用方提供 | AST 按方法边界切需要完整文件，而 unified diff 只有 hunk + 上下文行；拉文件内容是 integration 的职责（ARCHITECTURE 3.3），domain 不自己读文件、保持纯算法 |
| 2026-10-04 | M2 | 跨文件「调用关系」用轻量启发式（diff 文本出现 `方法名(` 即判相关） | 真实调用图分析超出 M2 范围；启发式足以满足验证点，M6 需更准再升级 |
| 2026-10-04 | M2 | 单方法超预算时整片输出并标 `oversized`，不截断不拆方法 | 「方法不被截断」是硬不变量；超预算的降级（裁上下文→压记忆→拆 chunk，ARCHITECTURE 6.3）属下游职责，切片阶段只负责标记 |
| 2026-10-04 | M2 | V2 只建 `review_chunk` 表，不写 JdbcChunkRepository | M2 验证点是纯 domain 单测；持久化在 M3 落 checkpoint 时才需要，提前写无处验证 |
| 2026-10-04 | M3 | 用标准虚拟线程池（`Executors.newVirtualThreadPerTaskExecutor()`）而非预览 API `StructuredTaskScope` | `StructuredTaskScope` 是预览 API，需 `--enable-preview`；标准虚拟线程池 API 稳定、功能够用 |
| 2026-10-04 | M3 | `DagDefinition.Builder.addDependency` 中特殊处理 `List.of()` 返回的不可变列表 | `addNode` 时用 `List.of()` 初始化依赖列表，`addDependency` 追加时会抛 `UnsupportedOperationException`；检测到不可变列表时重建为 `ArrayList` |
| 2026-10-04 | M3 | 测试前手动清空数据库（`truncate review_chunk, node_run, review_task cascade`） | IT 间可能有残留数据（特别是 `chunk_id` 主键冲突）；未配置 `@Transactional` + `@Rollback` 自动回滚 |
| 2026-10-05 | M4 | LLM 走 OpenAI 兼容协议 + 第三方中转端点 `https://api.aiblitz.io`（Claude 分组），模型 `claude-haiku-4-5-20251001` | 用户提供该中转端点与 key；Spring AI OpenAI starter 直接按 base-url 指向即可。haiku 快且省，够验证点。连通性已只读自测通过 |
| 2026-10-05 | M4 | 「非法输出触发重试/降级」用 stub `LlmClient` 的纯单测验证，不用真实 LLM | 真实模型输出不可控，无法确定性复现畸形输出来触发重试路径；happy-path（合法 Finding JSON）才用真实 LLM IT。用户确认「必须真实 LLM IT」指 happy-path 这条 |
| 2026-10-05 | M4 | `application.yml` 的 `spring.ai.openai.api-key` 给非空占位默认值 `placeholder-no-key` | Spring AI 对空 api-key 在**启动期**抛「OpenAI API key must be set」，会拖垮现有不需要 LLM 的 IT 的上下文加载；给占位值让上下文能起，真实调用由 `ReviewAgentIT` 按真实 key 存在与否 gate |
| 2026-10-05 | M4 | `ReviewAgent` 重试耗尽后降级（返回空 Findings + 原因），不抛异常中断 DAG | 单个 chunk 评审失败不应让整个 PR 评审失败；降级信息上交下游（ChunkReduce / GlobalReduce）决策，符合 ARCHITECTURE 6.3 降级思路 |
| 2026-10-05 | M4 | domain 层 `FindingJsonParser` 直接用 Jackson `ObjectMapper` | Jackson 为纯计算库、无 Spring 耦合，与 M2 的 jtokkit / javaparser 同层惯例一致，不破坏 domain 纯度；Bean 注入用容器的 `ObjectMapper`，`new` 构造用于单测 |
| 2026-10-05 | M4 | 本会话 Docker 无法启动，M4 真实 LLM 验证点未跑，不标完成 | `docker.exe` 在当前 shell 报 Permission denied（环境限制），依赖中间件的 IT 全部无法运行；按会话协议「验证点不通过不进下一里程碑」，M4 标「代码完成、待验证」 |
| 2026-10-09 | M4 | 补跑真实 LLM IT 通过，M4 标完成 | Docker 本会话恢复正常；`ReviewAgentIT` 实跑 11.76s，真实模型返回 4 条 Finding、attempts=1、未降级，验证点达成。全仓 `mvn verify` 8 模块绿 |
| 2026-10-09 | M4 | `ChunkRepositoryIT` 改用独立 repo 名 + `@AfterEach` 清理 | 原用例硬编码 `owner/repo` 且不清理，残留 `review_task` 让 `insertIfAbsent` 被唯一索引拦掉，后续插 chunk 撞外键失败。与 `ReviewTaskRepositoryIT` 的既有做法对齐（M3 遗留的「IT 间残留数据干扰」技术债的局部解决） |

## 环境与常用命令

已确认的环境：
- JDK 26.0.1、Maven 3.9.16、Docker 29.8.1、Docker Compose v5.5.1、Git 2.54
- Maven Central 不可达，但 Maven 全局 `settings.xml` 已配阿里云镜像，pom 中无需额外配置
- Docker 已配国内镜像加速，所需镜像均已拉到本地
- 版本锁定见 `docs/PLAN.md` 第 5 节

常用命令（详见 `README.md`）：

```bash
cd deploy && docker compose up -d                              # 起基础设施（mvn verify 的前置条件）
mvn verify                                                     # 构建 + 单测 + IT（IT 需中间件已启动）
mvn test                                                       # 只跑纯单测，不需要中间件

# integration 与 review 启动需要 GITHUB_WEBHOOK_SECRET（integration 缺它起不来）
GITHUB_WEBHOOK_SECRET=<secret> java -jar codemind-<module>/target/codemind-<module>-0.1.0-SNAPSHOT.jar

curl http://127.0.0.1:8082/actuator/health                     # 直连
curl http://127.0.0.1:8080/codemind-review/actuator/health     # 经网关
curl -H "codemind: codemind" "http://127.0.0.1:8848/nacos/v3/admin/ns/service/list?pageNo=1&pageSize=20"
```

本地手工发一个带正确签名的 webhook（M1 起）：

```bash
SECRET=<同启动时的 GITHUB_WEBHOOK_SECRET>
BODY='{"action":"opened","repository":{"full_name":"codemind/demo"},"pull_request":{"number":1,"head":{"sha":"h1"},"base":{"sha":"b1"}}}'
SIG="sha256=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$SECRET" | awk '{print $2}')"
curl -s -X POST http://127.0.0.1:8081/webhook/github -H "Content-Type: application/json" \
  -H "X-GitHub-Event: pull_request" -H "X-GitHub-Delivery: $(date +%s)" \
  -H "X-Hub-Signature-256: $SIG" -d "$BODY"

docker exec codemind-postgres psql -U codemind -d codemind -c "select repo, pr_number, head_sha, status from review_task;"
docker exec codemind-rmq-broker sh -c 'cd /home/rocketmq/rocketmq-5.3.1/bin && sh mqadmin consumerProgress -n rocketmq-namesrv:9876 -g codemind-review-pr-received'
```

注意：新建 topic 首次投递要等约 20s（consumer 路由刷新），之后稳定在 4s 内。

Windows 下停服务（本环境无 pkill）：

```bash
netstat -ano | grep ":8082 .*LISTENING"    # 查 PID
taskkill //F //PID <pid>
```

配置位置：各服务 `src/main/resources/application.yml`。Nacos 地址通过 `NACOS_ADDR` 覆盖。密钥一律用环境变量，不写入仓库。
