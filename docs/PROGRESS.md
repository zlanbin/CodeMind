# 开发进度（会话交接文件）

> 每个会话开始时先读本文件；结束前必须更新本文件。
> 只记录事实：做了什么、怎么验证的、结果如何。未验证的内容标注「未验证」。

## 当前状态

- 当前里程碑：**M7b 已完成**（回写 PR：总结 + 行级评论 + `PUBLISHED`）。M7 整体完成，M0~M7 最小可演示版本就绪
- 上次更新：2026-10-23
- 分支：`feat/m7b-publish`（基于 master `2a52baa`），改动**全在工作区、未提交**，待用户说「代码提交」
- 阻塞项：无
- 未验证项：无（全量 `mvn verify` 带真实 key 0 跳过；真实写验收实跑通过，见 M7b 节）

## 下一个会话要做的事

**先收尾 M7b 的提交**（若本会话未做）：`feat/m7b-publish` 提交、推送、开 PR 到 master。然后进 **M8：memory-service（L1/L2 记忆、混合检索）**。

验证点（ROADMAP M8）：单测：写入与召回排序符合打分公式。ARCHITECTURE 7 是设计输入。

待用户决定的清理（不阻塞 M8）：
- Task 5 留下的测试库 `codemind_task5_reviewfix`、`codemind_task5_clean_green` 是否删除
- 本地分支 `feat/m7a-entry-chain`（已合并，远程已删）是否删除
- sandbox PR #2 上有两个 CodeMind review：`5624353955`（上个会话，无测试标记，工具清不掉）与 `5624674315`（本会话）。留着不影响

可直接复用：
- 回写链路：`ReviewExecutionService` 到 `AGGREGATING` 后自动登记 outbox → MQ → integration `ReviewPublishService` → GitHub。
  M9 反馈闭环要读 PR 评论时，integration 的 `GithubScmClient` 已有分页 `listReviews`
- 报告：`ReviewReportBuilder.build(findings, diffFiles, sourcePaths, ReportPolicy)` → `ReviewReport`；
  M9 的「记忆过滤」应插在 Findings 进 builder 之前（ARCHITECTURE 5 的 Dedup & Filter）
- 运维：`docs/operations/M7b-publication.md`（状态查询、UNCERTAIN 核查、人工重置）

跑验证的前置（2026-10-23 实测可用）：
```bash
powershell.exe -NoProfile -Command 'Start-Process -FilePath "C:\Users\35229\AppData\Local\Programs\DockerDesktop\Docker Desktop.exe"'
cd /d/pingshen/deploy && docker compose up -d          # 等 PG / Redis / Nacos healthy
source ~/.codemind-secrets.env                         # 仓库外，含 GITHUB_TOKEN / CODEMIND_LLM_API_KEY / CODEMIND_TEST_*
mvn -q package -DskipTests -pl codemind-integration -am
cp codemind-integration/target/codemind-integration-0.1.0-SNAPSHOT.jar /tmp/codemind-integration-run.jar
GITHUB_WEBHOOK_SECRET=<任意> java -jar /tmp/codemind-integration-run.jar &   # RealPullRequestReviewIT 直连 8081；M7b 起 integration 也连 PG
mvn verify                                             # 约 4~5 分钟；不跑真实写验收
```
- 每条命令里 `source` 密钥文件，不要把值贴进对话
- integration 从 jar **副本**启动（Windows 会锁运行中的 jar）；改了 integration 代码要重打包并重启
- 密钥文件里 `CODEMIND_TEST_PR=1`（作者 zlanbin = token 用户），只够只读 IT。**真实写验收要用 PR #2**（作者 `lan-bin`），
  命令行覆盖 `CODEMIND_TEST_PR=2 CODEMIND_TEST_HEAD_SHA=df8507c654a75f7e4a7809abf6192645dea4d8f6`，三阶段命令见运维文档
- 全量 IT 在一个 JVM 里会耗尽 PG `max_connections`，已把测试 Hikari 池压到 2（`src/test/resources/application.properties`）

安全提醒：`GITHUB_TOKEN` 与 LLM key 都进过聊天记录，**强烈建议轮换**。token 现有 `pull_requests: write`，只授 sandbox 仓库。

**本机环境坑：**
- 宿主机原生 PG 服务 `postgresql-x64-16` 占 5432 会与容器冲突（征兆「用户 codemind 密码认证失败」），需用户 `net stop`，助手无权限
- Docker Desktop 会无故停掉、容器会消失，verify 失败先探端口 5432/6379/8848/9876
- failsafe 的 `System.out` 不进控制台，在 `target/failsafe-reports/TEST-*.xml` 的 system-out 里

**M6 代码已就绪，可直接复用的东西：**
- 跨会话全流程入口：`DagOrchestrator.executeCrossSessionReview(buildChangeMapNode, planChunksNode, task, ctx)`
  → `CrossSessionReviewResult`（changeMap / 各片 ChunkReduceResult / GlobalReduceResult）；
  `allFindings()` 已做片级 + 跨文件的 fingerprint 合并，`allDegradations()` 汇总全部降级信息
- `ReviewTask` 已挂 `changeMap()` / `chunkSummaries()` / `findings()`，状态已推到 `AGGREGATING`
- AST 确定性签名提取：`ChangedSignatureExtractor.extract(List<DiffFile>, Map<path,newSource>)`
  → `List<SignatureChange>`（ADDED / REMOVED / SIGNATURE_CHANGED / BODY_CHANGED，带参数类型）

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

### M7b 回写 PR（总结 + 行级评论 + PUBLISHED）—— 完成于 2026-10-23

设计 `docs/plans/M7b-design.md`，计划 `docs/plans/M7b.md` 的 Task 1~10 全部落地。跨两个会话：前一会话做完 Task 1~10
与首次真实写验收；本会话补运维文档、跑 S6 整体复审、修复 Task 10 的 4 项严重问题、重跑真实写验收与全量 verify。
执行账本 `.superpowers/sdd/M7b/progress.md`（含各任务独立评审与修复记录）。

改动范围（按任务）：

- common：`event/ReviewCompleted`（含嵌套 `LineComment`，line>0）、`event/ReviewPublished`
- review 报告：`domain/report`（`ReviewReport` / `InlineComment` / `SummaryItem` / `Reason` / `ReportPolicy` / `ReviewReportBuilder`），
  `application/ReviewMarkdownRenderer`，`infrastructure/config/ReviewReportConfig`；`ReviewTask.assignReport`
- review 交接：`V5__create_review_publication_outbox.sql`；`domain/event/DomainEventPublisher`、`domain/publication`；
  `application/ReviewCompletionService`（锁 task 同事务登记）、`ReviewOutboxDispatcher`、`ReviewPublicationService`（回执 → PUBLISHED + ACK）；
  `JdbcReviewOutboxRepository`（SKIP LOCKED 领取、租约、ACK 栅栏）、`PublicationSchedulingConfig`；
  恢复 SQL 加 `NOT EXISTS outbox`、`markFailedIfUnpublished`；空 diff 早退推进到 AGGREGATING
- integration 发送：`application/ReviewPublishService`（发前 IN_FLIGHT 栅栏、不确定只核查、422 降级 SUMMARY_ONLY）、
  `ReviewPublicationBodies`（隐藏标记）；端口 `ScmReviewPublisher` / `PublicationLock`；`GithubScmClient` 加分页 `listReviews`、`createReview`；
  `domain/publication` + `JdbcPublicationRepository` + 独立 schema 迁移 `db/integration-migration/V1`；`RedisPublicationLock`（续租）
- MQ：两侧 `RocketMqDomainEventPublisher`、`ReviewCompletedListener`（integration）、`ReviewPublishedListener`（review）
- 测试隔离：两模块 `src/test/resources/application.properties`（topic/group 加 `-test`、关后台投递与恢复、Hikari 池 2）
- 真实写验收：`RealPullRequestPublishIT`（prepare / cleanup / publish 三阶段，`-Pgithub-write` 才跑）+ `support/` 下 sandbox 客户端与清理
- 文档：`docs/operations/M7b-publication.md`、README「回写 PR」一节

验证命令与结果（2026-10-23，真实 `GITHUB_TOKEN` + `CODEMIND_LLM_API_KEY`、真实中间件、integration 跑在 8081）：

| 验证点 | 命令 / 用例 | 结果 |
|---|---|---|
| **在自己的 GitHub 仓库提 PR，看到行级评论**（ROADMAP M7） | `RealPullRequestPublishIT` prepare → publish（`zlanbin/codemind-sandbox` #2，作者 `lan-bin`，head `df8507c`） | **通过**。prepare 34s 真实 LLM，7 条意见 → 2 组行级 + 2 条总结；publish 后 GitHub 直接查询：review `5624674315` 带任务与测试标记，行级 `InventoryClient.java:11`（预埋 NPE）与 `OrderService.java:9`，均在新侧 hunk 内；库里 `PUBLISHED`、outbox 已 ACK、账本 `PUBLISHED/INLINE/inline_count=2` |
| 重投不重复发 | 同上 publish 阶段的重投步骤 | **通过**。重发同一事件，独立消费组收到新回执后回查，review 仍只有本任务一个 |
| 缺前置不跳过 | 不带密钥跑 `-Pgithub-write` | **通过**。`Failures: 1, Skipped: 0`，信息「缺少环境变量 GITHUB_TOKEN」 |
| 清理门禁 | `SandboxReviewCleanupTest`（10 例） | **通过**。只读列出、确认集合不一致整体放弃、IN_FLIGHT/UNCERTAIN 不清 |
| 普通回归不写真实 GitHub | 裸 `mvn verify` 日志 | **通过**。`RealPullRequestPublishIT` 出现 0 次；普通 IT 走 `-test` topic |
| 全仓回归 | `mvn verify`（不带过滤、不带 profile） | **通过**。8 模块 BUILD SUCCESS，**390 测试 0 失败 0 错误 0 跳过**（common 6；gateway 1；integration 127 单测 + 28 IT；review 174 单测 + 51 IT；memory / rule / admin 各 1）。真实 LLM/GitHub IT 均实跑：RealPullRequestReviewIT 42s、CrossSessionMapReduceIT 71s、MultiAgentIntegrationIT 16s、ReviewAgentIT 9s |

S6 整体复审（固定点 `2a52baa`，标准轴 / 规格轴两个独立子代理，本会话）：
- 标准轴 0 硬违规，7 条判断题（见下方技术债），本里程碑不处理
- 规格轴 4 项严重，全在 Task 10 验收 IT：缺前置用 `assumeTrue`、prepare 不经门禁就删评论、测试标记从未写入正文、
  第 7 步断言缺失（新侧 diff / 命中预埋缺陷 / 重投不增 review）。已回 S5 修复并重跑真实验收
- Review Focus 1~5（旧 IT 穿透、POST 后超时、恢复与回执竞争、分页与失锁、422 跨重启）核心实现与规格一致

本里程碑踩到的真实环境问题（都不是生产代码缺陷）：
- GitHub 对「token 用户评审自己开的 PR」返回 422，即使 `event=COMMENT`、无行级评论。改用第二账号 `lan-bin` 开的 PR #2
- `commit_id` 必须是 40 位完整 SHA，短 SHA 报 `GitObjectID was provided invalid value`。验收现在强制校验 40 位
- 验收 IT 继承了测试隔离 topic，消息静默落在无人订阅的 topic 上。已显式覆盖回生产 topic
- 全量 IT 耗尽 PG 连接数，测试 Hikari 池压到 2

遗留问题 / 技术债：
- **SUMMARY_ONLY 被 422 拒后回 `READY`，outbox 每 60s 重投 → 无限重试调 GitHub**（不产生写入）。需要终态（如 `REJECTED`）+ 告警，属设计改动，M9 前处理
- 结果不明的 `IN_FLIGHT/UNCERTAIN` 没有自动重置，只能人工按运维文档处理（B 策略的有意代价：发前落库后、POST 前退出也会卡住）
- `GithubScmClient` 遇 422 不记录响应体，诊断要临时加日志（本里程碑两次）。可加脱敏后的 `message` 字段
- Task 8 I-5（Important）：「查询在前、outbox 后到」与「回执与 claim 竞争不把 PUBLISHED 改回 FAILED」两条场景用例未补；
  N-2（Important）：两条反向竞争用例的胜方用手抄 SQL 副本，与生产常量无同步；I-3：`readCommittedTransactions` 未设 `validateExistingTransaction(true)`；N-5 断言偏弱
- Task 7 I2/I3/M1~M4/X1~X4：部分状态机场景断言不全（原报告 `task-7-review-report.txt`）
- Task 9 R-2~R-5：异常消息未校验、`RocketMqDomainEventPublisher` 的 `@Value` 未被 IT 覆盖、Hikari 池 2 无余量、风格残留
- Task 6 没有单任务独立评审（账本早期两处说法矛盾，已更正），只被 S6 整体复审覆盖
- integration 普通 IT 未替换 GitHub 写端口（监听 `-test` topic，实际风险低）
- S6 标准轴判断题：`GithubScmClient` 同时承担读写两端口且 onStatus 处理重复 3 处；`JdbcReviewTaskRepository:172/208` 锁行逻辑重复；
  `ReviewExecutionService` 空 diff 手工推进状态与编排器重复；`ReviewPublishService` 用裸 HTTP 状态码判平台语义；
  `ReviewPublicationService` / `ReviewPublishService` 易混；端口位置不统一（`PublicationLock` 在 application/port，`PublicationRepository` 在 domain）
- `findings` 仍未落库，报告只经 outbox payload 持久化
- M7a 遗留仍在：internal 端点无鉴权且经网关可达；首次触发路径非原子（回写侧已由账本兜住外部副作用）

与 ARCHITECTURE.md 的偏差：**已同步更新**。3.3 补回写链路（outbox、账本、锁、写端口、保证边界、事件表加 `ReviewPublished`）；
4 补 `ReviewReport` 结构与分流规则；5 DAG 图 Publish 节点、恢复边界（有 outbox 不算卡死）；8 加两条决策。

与计划 / 设计的偏差：
- Task 10 两阶段改为三阶段：清理拆成独立的 `cleanup` 阶段，删除必须带 `-Dcodemind.github-write.confirm-delete` 且与重新计算的候选完全一致
- 「至少一条覆盖 `i <= size()`」改为参数 `-Dcodemind.github-write.expect-line`：PR #2 只有 `InventoryClient:11` 一处预埋缺陷
- 测试标记在 prepare 写入 outbox 时追加到正文（`jsonb_set`，限 `attempts=0`），而不是另建测试 renderer：`ReviewMarkdownRenderer` 是 final 类，
  且这样仍满足「存入 outbox 后投递时不改 payload」

### M7a 入口链路（真实 PR → 拉 diff 与源码 → 跨会话评审 → AGGREGATING）—— 完成于 2026-10-22

M7 拆成 M7a（本节，只读 GitHub）/ M7b（回写）。计划 `docs/plans/M7a.md` 的 Task 1~8 全部落地。
本里程碑首次按 `docs/WORKFLOW.md` 九级流程走完，S6 是第一次由不带实现上下文的评审者看代码。

改动范围（integration + review，按任务）：

- integration：`application/port/ScmClient` + `ScmException`（带 statusCode）；`infrastructure/github/GithubScmClient`
  （RestClient，只读，path/ref 逐段 URI 编码）；`GithubConfig`；`PullRequestContentService`（批量拉，单文件失败跳过）；
  `PullRequestContentController`（`GET /internal/pulls/diff`、`POST /internal/pulls/contents`）+
  `PullRequestContentExceptionHandler`（`ScmException` → 502）
- review：domain 端口 `scm/ScmContentPort` + `ScmContentException`；`infrastructure/scm` 的 Feign 客户端与适配器
  （加 `spring-cloud-starter-openfeign` + loadbalancer）；`ReviewTaskRepository.findById`；
  `application/ReviewExecutionService`（`executeReview` / `submitAsync` / `sourcePathsOf`）；
  `infrastructure/config/ReviewDagConfig`（DAG 全家装配成 bean）；listener 建任务后 `submitAsync`
- 卡死恢复：`findStaleInProgress` / `claimForRecovery`、`StaleTaskRecoveryService`、
  `RecoverySchedulingConfig`（`CODEMIND_RECOVERY_ENABLED` 默认关）
- checkpoint：`infrastructure/persistence/CheckpointObjectMapperFactory`（default typing、非 bean、关 is-getter）
- `V4__review_chunk_task_scoped_pk.sql`：`review_chunk` 主键 `chunk_id` → `(task_id, chunk_id)`

验证命令与结果（2026-10-22，真实 `GITHUB_TOKEN` + `CODEMIND_LLM_API_KEY`、真实中间件、integration 跑在 8081）：

| 验证点 | 命令 / 用例 | 结果 |
|---|---|---|
| 真实 PR 能自动拉到 diff 与文件源码 | `RealPullRequestReviewIT` ① | **通过**。`zlanbin/codemind-sandbox` #1（head `6615e4d`）：diff 含 `diff --git`；每个 Java 文件新侧源码非空（S6 后补的断言） |
| 评审跑到 `AGGREGATING` 并产出 Finding | `RealPullRequestReviewIT` ③④ | **通过**。实跑 33~41s；库里状态 `AGGREGATING`；`allFindings()` 18~25 条（多次运行不等）。预埋缺陷都被抓到：`i <= size()` 越界、`discountPercent / 100` 整除、`stock.get` 改掉 `getOrDefault` 后的 NPE |
| Finding 的 file 落在真实 diff 文件列表内 | `RealPullRequestReviewIT` ⑤ | **通过**。无编造文件名 |
| 删除 / 重命名 / 非 Java 文件不误拉内容 | `ReviewExecutionServiceTest`（7 例） | **通过**。重命名用例走真实 `DiffParser`，含 100% 纯重命名 |
| 新 bean 在真实容器装配正确 | `ReviewDagWiringIT`（2 例） | **通过**。含回归 `sharedObjectMapperWritesPlainJson` |
| 卡死任务能续跑、正在跑的不被误判 | `StaleTaskRecoveryIT`（6 例）+ `StaleTaskRecoveryServiceTest`（4 例） | **通过**。含 S6 后补的「状态新节点旧不算卡死」「两侧都旧被捞到」 |
| checkpoint 各类节点结果往返 | `CheckpointObjectMapperFactoryTest`（6 例） | **通过**。修复前 3 例红（`complete` 字段），修复后绿 |
| GitHub 路径编码 | `GithubScmClientTest.pathAndRefAreEncodedButSlashesKept` | **通过**。修复前红（`#` 被当 fragment），修复后绿 |
| M6 遗留：真实 LLM 的 ChunkSummary 质量 | `CrossSessionMapReduceIT` | **通过**（48s 实跑，首次未被跳过） |
| M5 遗留：真实 LLM 多维度并行 | `MultiAgentIntegrationIT` | **通过**（14s 实跑，首次未被跳过） |
| 全仓回归 | `mvn verify` | **通过**。8 模块 BUILD SUCCESS；**179 测试 0 失败 0 跳过**（review 111 单测 + 30 IT，integration 25 + 7，其余模块 6） |

S6 评审（固定点 `d3b4625`，双轴子代理）：标准轴 0 硬违规、7 条判断题；规格轴 3 缺失 + 2 出入。
回 S5 修了 5 项（URI 编码、RealPR 残留跳过、源码非空断言、卡死判定反向用例、重命名走解析器），
其余判断题记入下方技术债。

本里程碑踩到并修掉的缺陷（都是 M3~M6 攒下、DAG 首次在真实容器里跑才暴露的）：

- 开了 default typing 的 checkpoint mapper 注册成 bean → Boot Jackson 自动配置退让、它成了全局 mapper →
  Feign 请求体与 LLM 调用 JSON 全带类型数组（Feign 400、LLM IT 超时）
- `ChunkSummary.isComplete()` 被当 is-getter 序列化成 `complete` 字段，读回时 record 不认，评审在 GlobalReduce 前失败。
  `NodeRunRepositoryIT` 的往返用例用 3 参构造器（summary 为 null）所以没覆盖到
- `review_chunk.chunk_id` 是全局主键而 chunkId 任务内编号，第二个任务 plan-chunks 必撞主键
- Feign 缺 loadbalancer、`ReviewAgent` 构造器歧义（`19111da`）
- `RealPullRequestReviewIT` 断言插库前的本地 task 实例（永远 `CREATED`）；有残留时被 `assumeTrue` 静默跳过

遗留问题 / 技术债：

- **安全：internal 端点无鉴权且经网关可达**。网关 discovery locator 自动建 `/codemind-integration/**` 路由，
  `/codemind-integration/internal/pulls/contents` 能用 integration 的 token 读仓库。本机网关当前未运行；
  网关暴露到本机之外前必须封掉（显式排除 `/internal/**` 或服务间鉴权）。M7b 回写端点若也走 internal，同样受影响
- 首次触发路径非原子：两条并发投递同时看到 `CREATED` 可能双跑（恢复路径已原子抢占）。M7b 回写幂等表兜外部副作用
- 恢复判定是启发式的（15m 无活动即判卡死），无节点级心跳
- N 个 Java 文件 = N 次 `contents` 调用，无并发无缓存
- 噪声：同一问题被不同维度用不同措辞重复报，fingerprint 拦不住（M7b 处理）
- GlobalReduce 的 Finding `line=0`（M7b 回写需降级为总结评论）
- S6 判断题，未处理：共享状态键 `"diffText"` / `"newSources"` 裸字符串三处重复；`JdbcReviewTaskRepository` 的
  进行中状态名硬编码在 SQL、与枚举无关联；`ReviewExecutionService` 有只给测试用的 null 执行器构造器；
  `RecoverySchedulingConfig`（infrastructure）依赖 application
- `findings` 未落库，只挂在内存聚合上（M7b 回写需要时再定是否持久化）
- `CODEMIND_RECOVERY_ENABLED` 默认关，M13 演示脚本要带上

与 ARCHITECTURE.md 的偏差：**已同步更新**。3.3 补 review → integration 的 internal 端点 + Feign 落地方式与安全缺口；
4 澄清「FAILED 恢复回原阶段」实际是 `FAILED → PLANNING` + checkpoint 跳过已完成节点，补 `review_chunk` 主键；
5 补入口链路、卡死恢复、checkpoint 序列化约束；8 加一条决策（只有 integration 直连 GitHub）。

### M6 跨会话 Map-Reduce（ChangeMap、ChunkSummary、GlobalReduce）—— 完成于 2026-10-10

本会话按 `docs/M6_设计审查.docx` 的检查清单实施，10 项全部落地（含 1 项修订、1 项顺带解决，见下）。

改动范围（review-service domain + infrastructure）：

**M2 切片器升级（两处，都是 M6 的前置条件）**

- `JavaMethodIndex` / 新增 `MethodSignatures`：方法限定名从 `类名#方法名` 升级为
  **`类名#方法名(参数类型, ...)`**（如 `OrderService#cancel(int)`）。M6 验证点要区分
  `foo(int)` 与 `foo(String)`，不带参数类型做不到。`SemanticChunker.referencesAny` 同步改为
  只取裸方法名做调用形态匹配
- `Unit` + `SemanticChunker.splitOversizedUnits`：**补上 ARCHITECTURE 6.3 降级链的最后一步「拆分 chunk」**。
  `Unit.absorb` 改为只挂载关系、不并 hunk，保留原子边界；聚合体超 diff 预算时沿合并缝拆回原子单元。
  单个原子单元（一个完整方法）自身超预算仍标 `oversized` 不拆——「方法不被截断」是硬不变量

**确定性签名提取（domain/chunk）**

- 新增 `ChangedSignatureExtractor` / `SignatureChange`：新侧用 JavaParser 取被 hunk 命中的方法，
  旧侧从 diff 删除行按正则识别声明，两侧按裸方法名配对 → `SIGNATURE_CHANGED` / `REMOVED` /
  `ADDED` / `BODY_CHANGED`。**不依赖 LLM**，含关键字黑名单排除 `return foo(x);` 这类调用行，
  泛型参数按顶层逗号切分

**ChangeMap（新增 domain/changemap 包）**

- `ChangeMap`：LLM 产出（intent / modules / highRiskFiles）+ 确定性产出（files / signatureChanges）；
  `degraded()` 只影响前者，**LLM 挂了签名变更依然完整**
- `ChangeMapAgent` / `ChangeMapJsonParser` / `ChangeMapParseException` / `FileStat`：
  轻量会话，prompt 只给文件列表 + 签名变更 + 增删行数，**不给 diff 本体**（有单测盯着）
- `dag/BuildChangeMapNode`：从共享状态取 diffText / newSources，解析 → 统计 → 提签名 → 调 Agent

**ChunkSummary（domain/agent）**

- `ChunkSummary`：changedSymbols / intent / risks / crossChunkQuestions + 降级字段；
  `isComplete()` 校验 changedSymbols 与 intent 非空
- `ChunkSummaryJsonParser` / `ChunkSummaryParseException`：子会话一次调用同时产出 findings 与 summary
- `ReviewAgent`：prompt 扩展（要求 changedSymbols 带参数类型、约束 crossChunkQuestions 不兜底）；
  新增 `review(chunk, changeMap)` 重载注入变更地图；**摘要解析失败只降级摘要，不丢同次已解析的 Findings**
- `AgentReview`：增加 `summary` 字段（保留 M4/M5 的旧工厂方法签名，不破坏既有调用）
- `dag/ChunkReduceNode`：合并同一 chunk 各维度摘要（符号/风险/跨片问题取并集，intent 取首个非空），
  并校验完整性，不完整记入 `degradations`
- `dag/ChunkReduceResult`：增加 `summary` 字段（保留 M5 的 3 参构造器）

**GlobalReduce**

- `agent/GlobalReduceAgent` / `GlobalReduceResult`：只读 ChangeMap + 各片摘要，输出与 chunk 级同结构的
  Finding（category 以 CORRECTNESS 为主），失败降级为空结果
- `dag/GlobalReduceNode`：从上游各 `ChunkReduceResult` 取摘要；某片摘要缺失则跳过该片并记日志，
  不让整体失效
- `dag/DagOrchestrator`：新增 `executeCrossSessionReview(...)` 串全流程，ChangeMap 产出后写入共享状态；
  状态推进 `PLANNING → REVIEWING → AGGREGATING` 并落库；M5 的 `executeReview` 原样保留
- `dag/CrossSessionReviewResult`：片级 + 跨文件 Finding 按 fingerprint 合并（片级优先，它有具体行号）

**其它**

- `ReviewTask`：新增 `assignChangeMap` / `changeMap()` / `assignChunkSummary` / `chunkSummaries()`
- `PlanChunksNode`：`ChunkBudget` 改为可注入（原先硬编码 `ofDefault()`），用于构造超预算场景
- `agent/JsonFences`：抽出三个解析器共用的围栏剥离与预览截断
- `infrastructure/llm/ReviewAgentConfig`：装配 `ChangeMapAgent` / `GlobalReduceAgent` /
  两个新解析器；`ReviewAgent` 改用带 summaryParser 的构造器
- 测试资源 `diff/cross-signature.diff` + 4 个配套源码（PaymentGateway / RefundClient /
  AuditLogger / NotificationSender）：`RefundClient#submitRefund` 首参 `String → long`，
  调用方 PaymentGateway 仍传字符串

验证命令与结果：

| 验证点 | 命令 | 结果 |
|---|---|---|
| **构造超预算 PR，能发现跨文件签名不一致**（ROADMAP M6） | `mvn verify`（2026-10-10） | **通过**。`CrossSessionMapReduceTest.callerAndCalleeLandInDifferentChunksAndGlobalReduceCatchesMismatch`：真实 `SemanticChunker` + 真实 4 文件 diff + `diffBudget=150` → 切成 3 片，PaymentGateway（调用方）与 RefundClient（定义方）**各自单独一片**；断言①两片各只看到自己一侧，②**任何单片的评审 prompt 都不同时含定义与调用**（否则片内就能发现，用不上全局归约），③GlobalReduce 只凭各片摘要产出 1 条 `file=RefundClient.java` / `category=CORRECTNESS` / message 含 signature 的 Finding |
| 变更地图不投喂 diff 本体 | `BuildChangeMapNodeTest.promptCarriesSignaturesAndStatsButNotDiffContent` | **通过**。prompt 含文件名、两侧完整签名、`+1/-1` 统计；不含 `@@` 与方法体 |
| 变更地图降级后签名仍完整 | `BuildChangeMapNodeTest`（2 个用例：LLM 抛异常 / 输出畸形） | **通过**。`degraded=true`、intent 为空，但 `signatureChangesOnly()` 仍有 1 条 |
| GlobalReduce 不读原始 diff | `CrossSessionMapReduceTest.globalReduceSeesOnlySummariesNoRawDiff` | **通过**。归约 prompt 不含 `diff --git` / `@@ -`；且确实含各片小节与上交的跨片问题 |
| 摘要不完整要显式降级 | `ChunkReduceNodeTest.recordsDegradationWhenMergedSummaryIncomplete` | **通过**。全维度摘要降级 → 合并后 `isComplete()=false` 且 `degradations` 有记录 |
| 状态流转 | `CrossSessionMapReduceTest.stateAdvancesThroughPlanningReviewingAggregating` | **通过**。落库顺序 `PLANNING → REVIEWING → AGGREGATING`；终态 AGGREGATING（PUBLISHED 属 M7） |
| 超预算聚合体沿合并缝拆分 | `SemanticChunkerTest`（3 个新用例） | **通过**。焊接单元拆开后每片都不 `oversized`、方法完整、每片仍是合法 unified diff；单方法超预算仍 `oversized` 不拆 |
| 全仓回归 | `mvn verify`（2026-10-10 13:0x） | **通过**。8 模块 BUILD SUCCESS；review **92 单测 + 16 IT**，0 失败。3 个 IT skipped 均为真实 LLM 用例（无 key，`assumeTrue`） |
| 新 Bean 在真实容器装配正确 | `mvn verify` | **通过**。`CrossSessionMapReduceIT` 上下文加载成功（跑 5.48s 后才在 `assumeTrue` 处跳过），4 个新 Bean 装配无误（M5 曾在此类问题上首次 verify 失败） |

断言策略（本会话判定门确认）：确定性 stub + 真实 LLM 双轨。stub 不是返回死字符串，而是**按 prompt 内容作答**
（`ScriptedLlmClient` / `SignatureAwareLlmClient`）——GlobalReduce 只在摘要里同时出现「定义方声明了
`submitRefund(long, int)`」和「另一片上交了字符串实参的调用点」时才报问题，两条证据分别来自不同切片的
summary，变更地图单独给不出第二条。所以断言证明的是「Map-Reduce 把足够信息送到了归约会话」
（设计审查 Q7 的核心疑问），而非「桩会不会说话」。

遗留问题 / 技术债：

- **真实 LLM 的 ChunkSummary 质量未验**：`CrossSessionMapReduceIT` 需 `CODEMIND_LLM_API_KEY`，
  本会话未设而跳过。它会打印各片摘要并断言「至少一片的 changedSymbols 带参数签名」。
  补跑：`export CODEMIND_LLM_API_KEY=<key> && mvn verify -pl codemind-review`
- 树形递归归约未实现（ARCHITECTURE 6.2 第 4 步）：M6 只做一层，摘要过多时一次性读完，阈值未定，留 M7
- ARCHITECTURE 6.3 降级链的前两步（裁剪上下文代码、压缩记忆条数）仍未实现，依赖 RecallContext
  与记忆召回，留 M8 之后；M6 只实现了第三步「拆分 chunk」
- `ChangedSignatureExtractor` 旧侧靠正则：unified diff 不含旧文件全文，只能从删除行识别声明。
  识别不到的形态（跨行参数表、注解换行）会漏判为 `BODY_CHANGED`，不会误报成 `SIGNATURE_CHANGED`
- `ChangeMap` / `ChunkSummary` 未持久化：只挂在聚合内存里，`node_run` 的 checkpoint 间接存了
  节点结果（`ObjectMapper` 序列化为 JSON，反序列化后需手动转型，M3 遗留的同一问题）。
  续跑时 ChangeMap 能从 checkpoint 恢复，但未做针对性验证
- 拆分后「调用关系在同一片」的软约束被牺牲，靠 GlobalReduce 兜。但 GlobalReduce 依赖 LLM，
  一旦降级这类跨片问题就会漏——确定性兜底（如用 ChangeMap 的签名变更 + 各片符号做纯算法交叉校验）
  值得考虑，M6 未做
- `RecallContext` 节点仍未实现（ARCHITECTURE 5 DAG 图里有），子会话目前不注入召回的上下文代码、
  规则与记忆，留 M8 / M11
- Token 成本未采集：ChangeMap 1 次 + 子会话 4N 次 + GlobalReduce 1 次，M6 未做估算（M12 接 Micrometer）

与 ARCHITECTURE.md 的偏差：**已同步更新**。本次修订了 2（技术栈清掉未使用的 java-diff-utils、
补 jtokkit）、4（领域模型图：ChunkSummary 改为按 chunkId 关联、补方法限定名格式）、
5（DAG 图：BuildChangeMap 前置于 PlanChunks、标注各节点所属里程碑）、6.2 / 6.3 / 6.4（见决策日志）。

与 `M6_设计审查.docx` 的差异（文档本身是 2026-10-09 的评审记录，按原样留档不改）：

- 检查清单第 1 项「修正假设 A」：按 §5 执行，但 §5 说「复用 M2 JavaMethodIndex 的提取能力」
  **前提不成立**——M2 的限定名不含参数类型且类为包私有，故本会话升级了 `JavaMethodIndex`
  并新写 `ChangedSignatureExtractor`
- 检查清单第 5 项写「补全状态流转 PLANNING → REVIEWING → AGGREGATING → **PUBLISHED**」：
  `PUBLISHED` 的唯一入口是回写 PR（ROADMAP M7），M6 只接到 `AGGREGATING`
- §5「强化验证用例」建议「4 文件 diff + 小 diffBudget 强制拆成 2+ chunk」：**提出时不可达**
  （M2 的调用关系合并会把调用方与定义方永久焊在同一片，装箱从不拆 unit，预算压到 80 token
  也只是标 `oversized`，实测验证过）。本会话补上「拆分 chunk」降级后该场景变为可达，
  已按原意实现为直证验证点的用例

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
| 2026-10-10 | M6 | 方法限定名升级为带参数类型 `类名#方法名(参数类型, ...)`，改 M2 的 `JavaMethodIndex` | M6 验证点要区分 `foo(int)` 与 `foo(String)`，不带参数类型做不到。设计审查 §5 原以为「复用 M2 已有提取能力」即可，但 M2 的限定名不含参数类型、且 `JavaMethodIndex` 是包私有类，前提不成立。AST 取参数类型是确定性的，比让 LLM 在摘要里补签名可靠，且能反过来校验 LLM 输出（用户在判定门选「升级 JavaMethodIndex」） |
| 2026-10-10 | M6 | 补上 ARCHITECTURE 6.3 降级链最后一步「拆分 chunk」：聚合体超预算时沿合并缝拆回原子单元 | 原先只有「标 `oversized` 整片输出」，大 PR 真撞上会直接超上下文窗口（M2 把降级定义为「下游职责」，但下游一直没实现）。拆的是调用关系聚合、不是方法——单个原子单元超预算仍不拆，「方法不被截断」是硬不变量，排在预算之前 |
| 2026-10-10 | M6 | 设计审查 §5 的验证用例设计（4 文件 diff + 小预算强制拆成 2+ chunk）提出时不可达，补上拆分降级后变为可达 | M2 的 `mergeByCallRelationship` 只要在 B 的 diff 文本里看到 A 的方法名加括号就并查集合并，而 `packIntoChunks` 从不拆 unit——调用方与定义方只要调用行可见就必然同片（实测 `diffBudget=80` 仍同片，只是标 `oversized`）。即「验证跨会话归约」与「保证调用关系同片」两个设计直接冲突。拆分降级落地后 `diffBudget=150` 真的切成 3 片且调用方/定义方分离，故把断言从「扇出与跨片能力分两条证」升级为直证验证点的单条用例 |
| 2026-10-10 | M6 | 状态流转 M6 只接到 `AGGREGATING`，不接 `PUBLISHED`（设计审查清单第 5 项写到 PUBLISHED） | `PUBLISHED` 的唯一入口是回写 PR，属 ROADMAP M7。M6 内推到 PUBLISHED 会让它变成无实际含义的状态，M7 还得回来改（用户在判定门确认） |
| 2026-10-10 | M6 | 里程碑验证点用「确定性 stub + 真实 LLM」双轨断言 | 强断言（file / category / message 关键词）交给按 prompt 内容作答的 stub，确保可重复；真实 LLM 只做宽松检查（措辞不稳定）。stub 的触发条件要求两条证据分别来自不同切片的 summary，变更地图单独给不出第二条，故断言证明的是「信息真的流到了归约会话」而非「桩会说话」。沿用 M4「畸形输出用 stub、happy-path 用真实 LLM」的既有做法（用户在判定门选此项） |
| 2026-10-10 | M6 | `ChunkSummary` 按 `chunkId` 关联在 `ReviewTask` 上，而非塞进 `ReviewChunk` 值对象（ARCHITECTURE 4 原图画在后者下） | 切片是规划阶段产物、摘要是评审阶段产物。塞进值对象就得为挂摘要重建整个切片，还要动 `review_chunk` 表结构；按 chunkId 关联信息等价、改动面小。ARCHITECTURE 4 与 6.4 已同步 |
| 2026-10-10 | M6 | `BuildChangeMap` 排在 `PlanChunks` 之前（ARCHITECTURE 5 原图为之后） | 两者无数据依赖（地图要全量文件列表与签名，与切片结果无关）。地图先行可保证任何子会话启动前它已落进共享状态，省掉一次顺序约束。ARCHITECTURE 5 已同步 |
| 2026-10-10 | M6 | 本机 `docker.exe` 对当前账号执行不了，容器只能由用户手工启动（**2026-10-22 已不成立**，见环境坑） | Git Bash 与 cmd 均报「拒绝访问」，而 NTFS ACL 正常（完全控制）、非重解析点、无 `docker-users` 组、TCP 2375 未暴露。疑与 PATH 里 `C:\Program Files\YunShu\utils`（云枢终端管控）有关。另：宿主机原生 PG 服务 `postgresql-x64-16` 占 5432，与容器冲突，征兆是 IT 报「用户 codemind 密码认证失败」，需先 `net stop` |
| 2026-10-22 | M7a | M7 拆成 M7a（入口链路，只读）/ M7b（回写） | M3~M6 的 DAG 从未在真实容器里接线，回写之前得先让真实 PR 能流进评审。只读部分无外部副作用，可独立验证 |
| 2026-10-22 | M7a | 只有 integration 直连 GitHub，review 经 internal 端点 + Feign 取内容 | Token 只在一个服务；review 不感知 SCM 平台。ARCHITECTURE 3.3 / 8 已同步 |
| 2026-10-22 | M7a | checkpoint mapper 由 `JdbcNodeRunRepository` 自持，不注册 bean | 注册成 bean 会让 Boot 的 `JacksonAutoConfiguration` 按 `@ConditionalOnMissingBean` 退让，带类型信息的 mapper 成为全局唯一 mapper；`@Qualifier` 只管注入点，管不住自动配置退让 |
| 2026-10-22 | M7a | checkpoint mapper 关 IS_GETTER 探测，而不是给 domain 加 `@JsonIgnore` | domain 不碰 Jackson 注解；record 的 boolean 组件走组件访问器 `degraded()`，不受影响 |
| 2026-10-22 | M7a | V4 用于 `review_chunk` 主键修复，M7b 回写幂等表顺延 V5 | 主键缺陷阻塞验证点必须先修；计划原把 V4 预留给幂等表（用户在会话中确认） |
| 2026-10-22 | M7a | ScmException → 502 另建 `PullRequestContentExceptionHandler`，不改 `WebhookExceptionHandler` | 后者按 `assignableTypes` 限定到 webhook 控制器，放宽会让 401/400 映射溢出到新端点 |
| 2026-10-22 | M7a | `RealPullRequestReviewIT` 自动清残留 + `assertTrue`，不用 `assumeTrue(insertIfAbsent)` | 跳过的 IT 不算验证通过；残留让三个核心验证点被静默绕过（S6 规格轴发现） |
| 2026-10-22 | M7a | 恢复驱动 `CODEMIND_RECOVERY_ENABLED` 默认关 | 开着会让所有 IT 上下文里多一个扫库定时任务；逻辑由直接调 `recoverStaleTasks()` 的测试覆盖 |
| 2026-10-22 | M7b | review outbox + integration 独立 schema 发送账本，结果不明只核查不重发 | 外部写入做错要手工清理，宁可人工处理 `UNCERTAIN` 也不重复发评论；两服务各管自己的表，不做跨服务事务（判定门确认） |
| 2026-10-22 | M7b | integration 起依赖 PostgreSQL，Flyway 用独立 schema `integration` 与独立 locations | 与 review 的 public V1~V5 历史互不扫描；不启用 baseline 掩盖不匹配 |
| 2026-10-23 | M7b | 真实写验收改用第二账号 `lan-bin` 开的 PR #2 | GitHub 不允许 token 用户评审自己的 PR（422，即使 COMMENT、无行级评论）。生产与演示的发布账号须与 PR 作者不同 |
| 2026-10-23 | M7b | 验收 IT 三阶段（prepare / cleanup / publish），删除须带确认 id 集合 | S6 规格轴发现 prepare 未经门禁就删评论；拆出独立阶段让「清理」与「发布」各自授权 |
| 2026-10-23 | M7b | 显式验收缺前置判失败，不 `assumeTrue` | 跳过会让 `-Pgithub-write` 什么都没验也亮绿灯（S6 规格轴发现，计划 Global Constraints 原本就要求） |
| 2026-10-23 | M7b | SUMMARY_ONLY 422 无限重试留作技术债 | 修复需要新终态与告警，属设计改动；当前只会重复调 GitHub、不会重复写入 |

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
