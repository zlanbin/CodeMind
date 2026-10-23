# M7b 回写 PR Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `subagent-driven-development` or `executing-plans`，并配合 `test-driven-development`，逐任务执行。步骤使用复选框跟踪。本文件不是开始实施的授权，须先经用户审核。

**Goal:** 真实 PR 评审完成后，可靠投递报告，向 sandbox PR 提交总结与行级评论，收到回执后进入 `PUBLISHED`；不确定发布结果禁止自动重发。

**Architecture:** review 的纯领域服务生成报告，application 渲染后以事务保存 outbox，后台经 RocketMQ 补发原始事件。integration 在独立 PostgreSQL schema 保存发送状态，Redis 续租锁降低并发，GitHub 隐藏标记用于只读核查。发送前持久化栅栏，失败结果不确定时只核查不重发；成功回执在 review 内事务确认 outbox 与任务状态。

**Tech Stack:** Java 21、Spring Boot 3.5.16、JdbcClient / TransactionTemplate、Flyway、PostgreSQL 16、Redis 7、RocketMQ、RestClient、JUnit 5、MockRestServiceServer、Awaitility。沿用锁定依赖，不更换 LLM provider / 模型。

**Spec:** `docs/plans/M7b-design.md`（含判定门修订）；`docs/ROADMAP.md` M7；`docs/ARCHITECTURE.md` 3.3 / 4 / 5。

## Global Constraints

- 包名 `com.codemind.<service>`，`interfaces → application → domain ← infrastructure`；domain 不依赖 Spring、JDBC、Redis、HTTP 或 Flyway。
- 两个事件只定义在 `codemind-common/event`；公共事件不引用任一服务的领域对象。
- 密钥只从环境变量读，不进仓库、日志、异常或测试失败输出；不读取并展示仓库外秘密文件正文。
- 真实 GitHub 写入仅 `zlanbin/codemind-sandbox`。首次清理与首次发布分别列出目标和内容，停下等确认。
- 普通 `mvn verify` 不写真实 GitHub；profile 是测试选择器，不是生产发布开关。
- `*Test` 是不依赖中间件的 surefire 单测；`*IT` 走 failsafe、使用真实中间件。不用 Testcontainers 替换真实环境。
- 有 skipped 不算验证通过，逐条解释覆盖损失。显式写验收缺前置必须失败，不能 assumeTrue 跳过。
- 阈值默认 MAJOR、行级默认最多 10 组，锁 TTL 120s / 每 30s 续租；outbox 扫描 30s / 批量 50 / 租约 120s / 再投间隔 60s。
- `AGGREGATING` 在 GlobalReduce 前已存在：有 outbox 才证明完成交接。不能简单从所有卡死恢复 SQL 中删除这个状态。
- 不承诺端到端 exactly-once。`IN_FLIGHT` / `UNCERTAIN` 查不到标记也不能自动再次 POST。
- 不自动 commit / push。各任务只交付测试和差异；S6、验证门及用户授权后才走 S7。
- S6 固定点：`2a52baa`（M7a 合并后的 master），标准轴对 CLAUDE.md，规格轴对本计划与设计。

## Review Focus

1. **旧 IT 的副作用穿透**：`RealPullRequestReviewIT` 调执行服务后不能投递到真实发布 topic；测试出口替身与调度关闭须覆盖旧上下文 —— Task 1、6、9。
2. **POST 成功但客户端超时 / 进程退出**：持久化 IN_FLIGHT 先于 HTTP；重投只核查，缺标记也不得再 POST —— Task 5、7。
3. **恢复与回执竞争**：有 outbox 的任务不得被抢回 FAILED；注册与抢占同锁 task，回执和确认原子提交 —— Task 6、8。
4. **分页上限与锁过期**：不能把前 1000 条当完整结果；失锁不再写，PG 栅栏挡住第二消费者 —— Task 3、4、7。
5. **422 降级跨重启及回执丢失**：模式先持久化；已发布的实际 inlineCount/degraded 不得按原请求臆造 —— Task 5、7、9。

## 文件地图与依赖

下文用 `R = codemind-review/src/main/java/com/codemind/review`、`I = codemind-integration/src/main/java/com/codemind/integration`；
`RT / IT` 分别是对应模块的 `src/test/java/com/codemind/<service>`。这些仅是路径缩写，不新增目录。
每个任务 Files 列表即其所有新增/修改范围；同包 record 分文件，只有公共事件的 LineComment 用嵌套 record。

| Task | 产物 | 依赖 |
|---|---|---|
| 1 | 事件契约、普通测试隔离基线 | 无 |
| 2 | ReviewReport + renderer + 配置 | 无 |
| 3 | GitHub review 写端口和适配器 | 无 |
| 4 | 可续租 Redis 发布锁 | 无 |
| 5 | integration PG 发布记录 | 1 |
| 6 | review outbox、领取投递与执行服务交接 | 1、2 |
| 7 | integration 保守发布状态机 | 1、3、4、5 |
| 8 | review 成功回执与恢复边界 | 6 |
| 9 | MQ 接线与真实中间件链路 | 6、7、8 |
| 10 | profile、分阶段真实验收与运维说明 | 9 |

Task 2、3、4 独立，适合并行；Task 6~9 接口关联紧密，不得各自改名后让下一任务猜接口。

## 验证命令约定

在仓库根运行。单模块带 `-am`，避免 common 已修改却仍解析到旧安装产物：

```bash
# 单测
mvn test -pl <module> -am -Dtest=<Test1,Test2> -Dsurefire.failIfNoSpecifiedTests=false
# IT（先 docker compose，使用隔离的测试配置）
mvn verify -pl <module> -am -Dit.test=<IT1,IT2> -Dfailsafe.failIfNoSpecifiedTests=false
```

`failIfNoSpecifiedTests=false` 仅允许 reactor 的其它模块没有指定类，**不允许目标类没被执行**；检查目标模块测试报告。
首次新增类型的红灯允许是编译失败；类型存在后要确认是目标行为断言失败，不能把缺中间件当预期红灯。
测试失败先 `systematic-debugging`。每个任务记录实际命令、Tests run / failures / errors / skipped。

---

### Task 1: 公共事件与普通 IT 隔离基线

**Files:**
- Create: `codemind-common/src/main/java/com/codemind/common/event/ReviewCompleted.java`、`ReviewPublished.java`
- Test: `codemind-common/src/test/java/com/codemind/common/event/ReviewPublicationEventsTest.java`
- Create: `RT/support/RecordingDomainEventPublisher.java`（review 发布端口在 Task 6 出现时补齐 implements）
- Create: 两模块 `src/test/resources/application.properties`
- Modify: 两模块现有 `@SpringBootTest` IT 的 topic / group 配置及清理逻辑（逐类审计，不改产品默认 topic）

**Interfaces:**
- `ReviewCompleted implements DomainEvent`：`String eventId, Instant occurredAt, String taskId, String repo, int prNumber, String headSha, String summaryBody, List<ReviewCompleted.LineComment> comments`。
- 嵌套 `LineComment(String path, int line, String body)`；只允许 line > 0。
- `ReviewCompleted.of(taskId, repo, prNumber, headSha, summaryBody, comments)` 仅首次生成 UUID/时间。
- `ReviewPublished implements DomainEvent`：`eventId, occurredAt, taskId, int inlineCount, boolean degraded`，同样有 `of(...)`。

- [x] **1. 写红灯测试。** `eventsImplementDomainContract` 断言两个 record 是 DomainEvent；`commentsAreDefensivelyCopied` 对原 list clear 后事件 comments 大小不变且不能修改；`rejectsNonPositiveInlineLine` 断言 0/-1 非法；`rejectsNegativePublishedCount` 断言负数非法。字段不做 GitHub 平台类型耦合。
- [x] **2. 跑 common 单测确认红灯。** 使用上方命令与 `ReviewPublicationEventsTest`，确认目标未实现。
- [x] **3. 实现事件，并建立测试隔离默认值。** 测试资源把 review/integration 的新旧 topic、group、producer group 全改成测试命名；`codemind.publication.dispatch-enabled=false`、`codemind.recovery.enabled=false`。每个 MQ 链路类再覆盖为本类专用 topic/group；消费类测试 `@DirtiesContext(AFTER_CLASS)` 避免缓存消费者跨类竞争。真实验收显式覆盖这些默认值。
- [x] **4. common 单测转绿，并审计现有 IT。** 列出所有 SpringBootTest 类的事件出口与 topic；`RealPullRequestReviewIT` 的新发布替身在 Task 6 注入，Task 9 前不得连接新增真实写消费者。此阶段产品还没有发布链路，不通过启动真实发布来“验证隔离”。
- [x] **5. 交付。** 记录事件字段与测试隔离清单，不提交代码。

### Task 2: 报告纯领域规则与 markdown

**Files:**
- Create: `R/domain/report/{ReviewReport,InlineComment,SummaryItem,Reason,ReportPolicy,ReviewReportBuilder}.java`
- Create: `R/application/ReviewMarkdownRenderer.java`、`R/infrastructure/config/ReviewReportConfig.java`
- Modify: `R/domain/task/ReviewTask.java`、review `application.yml`（报告配置）
- Test: `RT/domain/report/ReviewReportBuilderTest.java`、`RT/application/ReviewMarkdownRendererTest.java`；补现有 ReviewTaskTest

**Interfaces:**
- record 字段完全按设计 §5；`ReportPolicy.defaults()` = MAJOR/10，maxInline >= 0，0 表示全部进总结。
- `ReviewReportBuilder.build(List<Finding>, List<DiffFile>, List<String>, ReportPolicy): ReviewReport`。
- `ReviewMarkdownRenderer.renderSummary(ReviewReport): String`、`renderInline(InlineComment): String`。
- `ReviewTask.assignReport(ReviewReport): void` / `report(): ReviewReport`（未生成时 null，沿用 changeMap 访问惯例）。
- 配置 `codemind.report.min-inline-severity=${CODEMIND_MIN_INLINE_SEVERITY:MAJOR}` / `max-inline=${CODEMIND_MAX_INLINE:10}`。

- [x] **1. 写红灯测试。** `lineZeroGoesToSummary`、`outsideHunkAndUnknownFileGoToSummary`、`renamedUsesNewPath`、`deletedFileNeverUsesRightSide`、`contextLineCanBeInline` 分别断言 Reason 与落行。`mergesThreeDimensionsAtInventoryLine11` 断言 3 条合成 1 组、merged 大小 3、最高 severity；`mixedMajorAndMinorStaysOneInlineGroup` 证明门槛按组而不是逐条过滤。`ordersAndCapsAtTen` 用 11+ 组验证完整排序和 OVER_LIMIT；`shuffledInputProducesEqualReport` 乱序重复；`zeroLimitMovesAllToSummary`；`reportCollectionsAreImmutable`。
- [x] **2. 跑 builder / renderer 单测确认红灯。** renderer 断言 N=L+M、K 单独显示；四种 Reason 中文提示；混合维度完整展示；零意见无降级“未发现问题”；有降级含“评审覆盖不完整”。
- [x] **3. 实现纯规则及渲染。** 严重度显式比较或正确使用现有 BLOCKER→INFO 顺序，不能误把 enum ordinal 大当严重。删除文件显式排除；组内与 summary 的所有稳定键按设计排序。application renderer 不追加 GitHub task 标记。装配放 config，domain 无注解。
- [x] **4. 跑测试转绿。** 包括 ReviewTask 挂载/替换报告测试；不改变旧 findings/chunks 的行为。
- [x] **5. 交付。** 保存确定性输入→报告→markdown 示例供真实发布预览复用，不提交。

### Task 3: GitHub review 端口与完整分页

**Files:**
- Create: `I/application/port/{ScmReviewPublisher,ExistingReview,ReviewDraft,DraftComment}.java`
- Modify: `I/infrastructure/github/GithubScmClient.java`、`I/infrastructure/config/GithubConfig.java`
- Test: 补 `IT/infrastructure/github/GithubScmClientTest.java`；Create `IT/infrastructure/config/GithubConfigTest.java`

**Interfaces:**
- `ScmReviewPublisher.listReviews(String repo,int pr): List<ExistingReview>`；`createReview(String repo,int pr,ReviewDraft draft): ExistingReview`。
- `ExistingReview(long id,String body)`、`ReviewDraft(String commitId,String body,List<DraftComment> comments)`、`DraftComment(String path,int line,String body)`。
- listReviews 只返回已提交 review（过滤 PENDING）；body null 统一空串；空响应/畸形创建响应报异常，不伪造成功 ID。
- GithubConfig 的 bean 返回具体 `GithubScmClient`，同时可按两个端口注入，保持只有一个实例。

- [x] **1. 写红灯测试。** `postsOneCommentReviewAtRequestedCommit` 用 MockRestServiceServer 断言 commit_id/event=COMMENT/side=RIGHT、auth/API version、JSON 中含空格/# 的 path 原样保留。`listsAllPages` 断言 per_page=100 和页序；`filtersPendingReviews`；`tenthPageWithNextLinkFailsClosed`；`emptyPageEndsListing`；`maps422AndNetworkErrors`；`neverLeaksToken`。
- [x] **2. 跑 GithubScmClientTest 红灯。** mock 绑定 builder，不能由实现覆盖 requestFactory。
- [x] **3. 实现写适配。** 延续路径逐段编码；分页基于下一页信号，最多 10 页，明确还有下一页必须抛 ScmException、不能返回部分列表。错误响应不拼原始请求头或 token；创建解析出 id/body。
- [x] **4. 转绿并验证双端口装配。** GithubConfigTest 使用 ApplicationContextRunner/测试配置，无中间件，断言两个接口解析到同一对象。旧只读客户端全部测试仍通过。
- [x] **5. 交付。** 不跑真实 POST，不提交。

### Task 4: Redis 可续租任务锁

**Files:**
- Create: `I/application/port/PublicationLock.java`、`PublicationLease.java`
- Create: `I/infrastructure/redis/RedisPublicationLock.java`
- Test: `IT/infrastructure/redis/RedisPublicationLockTest.java`、`RedisPublicationLockIT.java`

**Interfaces:**
- `PublicationLock.tryAcquire(String taskId): Optional<PublicationLease>`。
- `PublicationLease extends AutoCloseable`：`boolean isHeld()`、`void close()`；isHeld 同步检查 token，续租失败后不可恢复为 true。
- Redis key 前缀 `codemind:publish-lock:`，UUID token，120s TTL；默认每 30s Lua compare-and-PEXPIRE；释放 Lua compare-and-delete。调度执行器随 bean 关闭，close 取消该 lease 的续租。

- [x] **1. 单测先红。** mock Redis 调用和可控 scheduler：抢锁失败不启动续租；续租 token 比较；续租失败 lease 失效；close 只释放当前 token 且幂等。测试允许注入 Duration / scheduler，不硬等 30s。
- [x] **2. 跑纯单测确认失败。** Redis 测试替身不连 6379。
- [x] **3. 最小实现。** 锁是应用端口，不把 Redis 类型漏给 ReviewPublishService；无法确认仍持锁时返回 false，后续写入 fail-closed。
- [x] **4. 真实 Redis IT 转绿。** 测试用短 TTL：续租后原键仍是本 token；覆盖新 token 后旧 close 不能删；续租停止后到期可被新 token 获取。用 Awaitility，不依赖毫秒级一次性 sleep；按本测试唯一 taskId 清理。
- [x] **5. 交付。** 记录 PG 栅栏仍是正确性保障，不能因锁成功就绕过发送状态。

### Task 5: integration 独立 PG 发布账本

**Files:**
- Modify: `codemind-integration/pom.xml`、integration `application.yml`
- Create: `codemind-integration/src/main/resources/db/integration-migration/V1__create_review_publication.sql`
- Create: `I/domain/publication/{PublicationState,PublicationMode,PublicationRecord,PublicationResult,PublicationRepository}.java`
- Create: `I/infrastructure/persistence/JdbcPublicationRepository.java`
- Test: `IT/infrastructure/persistence/PublicationRepositoryIT.java`

**Interfaces:**
- `PublicationState = READY, IN_FLIGHT, UNCERTAIN, PUBLISHED`；`PublicationMode = INLINE, SUMMARY_ONLY`。
- `PublicationResult(long reviewId,int inlineCount,boolean degraded)`。
- `PublicationRecord` 字段：taskId、原始 ReviewCompleted、内容摘要、state、mode、attemptToken（可空）、result（可空）、createdAt/updatedAt、lastError。
- repository：`register(ReviewCompleted): PublicationRecord`（首次原文固定，冲突 payload 抛异常）；`findByTaskId(String): Optional<PublicationRecord>`；`beginAttempt(String taskId,String token,PublicationMode expectedMode): boolean`；`markUncertain(String taskId,String token,String reason): boolean`；`recordRejection(String taskId,String token,PublicationMode nextMode,String reason): boolean`；`recordSuccess(String taskId,String token,PublicationResult): boolean`；`reconcileSuccess(String taskId,PublicationResult): boolean`。
- recordRejection 仅从对应 IN_FLIGHT 恢复 READY；recordSuccess 允许对应 attempt 的 IN_FLIGHT/UNCERTAIN，但不覆盖 PUBLISHED；reconcileSuccess 是只读查到完整标记后的确认路径，不是重发授权。

- [x] **1. 写真实 PG 红灯 IT。** 验 schema/table 存在、同 task 重放返回第一次请求、摘要不同拒绝覆盖、READY 只有一方能 beginAttempt、重启读取 IN_FLIGHT、旧 token 不能改新结果、422 模式跨实例读回 SUMMARY_ONLY、成功实际计数保存、PUBLISHED 不被 rejection 覆盖。
- [x] **2. compose 启动后运行 IT 确认目标红灯。** `docker compose -f deploy/docker-compose.yml up -d`，确认 PG/Redis/Nacos healthy；迁移尚未实现时为预期红灯。
- [x] **3. 加依赖/配置/迁移/仓储。** JDBC、postgresql runtime、flyway-core、flyway-database-postgresql 版本沿根管理；`spring.flyway.default-schema=integration`、schemas=integration、locations=classpath:db/integration-migration。表 SQL 使用 schema 限定；不启用 baseline 掩盖已有不匹配历史。记录正文用普通 JSON、摘要不含 eventId/occurredAt 以外的随机派生值；同 task 比较固定业务载荷，不因重投的传输 ID 误报冲突。
- [x] **4. IT 转绿。** 两服务各自历史存在、review 的 public V1~V5 不被 integration 扫描；事务失败不留下半初始化数据；不删除 schema。并发测试用两个独立连接与屏障，断言 beginAttempt 恰好一方成功。
- [x] **5. 交付。** 记录新增“integration 启动也依赖 PG”的前置变化，不提交。

### Task 6: review outbox 与完成交接

**Files:**
- Create: review `src/main/resources/db/migration/V5__create_review_publication_outbox.sql`
- Create: `R/domain/event/DomainEventPublisher.java`
- Create: `R/domain/publication/{OutboxEntry,OutboxDelivery,ReviewOutboxRepository}.java`
- Create: `R/application/{ReviewCompletionService,ReviewOutboxDispatcher}.java`
- Create: `R/infrastructure/persistence/JdbcReviewOutboxRepository.java`、`R/infrastructure/config/PublicationSchedulingConfig.java`
- Modify: `R/application/ReviewExecutionService.java`、`R/domain/dag/DagOrchestrator.java`（空切片早退状态）、`R/domain/task/ReviewTaskRepository.java`、`R/infrastructure/persistence/JdbcReviewTaskRepository.java`、review `application.yml`
- Test: `RT/application/{ReviewCompletionServiceTest,ReviewOutboxDispatcherTest}.java`、补 ReviewExecutionServiceTest
- IT: `RT/infrastructure/persistence/ReviewOutboxRepositoryIT.java`、`RT/application/ReviewCompletionIT.java`
- Modify: `RT/application/RealPullRequestReviewIT.java` 和受构造器变化影响的测试；补 Task 1 记录发布器

**Interfaces:**
- `DomainEventPublisher.publish(DomainEvent): void`，与 integration 同名端口不同包。
- `ReviewTaskRepository.findByIdForUpdate(String): Optional<ReviewTask>`（只能在事务内）；`markFailedIfUnpublished(String): boolean`（条件排除有 outbox / PUBLISHED）。
- `ReviewCompletionService.register(ReviewCompleted): ReviewCompleted`，事务锁 task 后已有 outbox 返回原事件；否则要求 AGGREGATING 并保存请求、刷新 updated_at。
- `ReviewOutboxRepository.findByTaskId(String): Optional<OutboxEntry>`、`insert(ReviewCompleted): void`、`claimDue(Instant now,int limit,Duration lease): List<OutboxDelivery>`、`claimTask(String taskId,Instant now,Duration lease): Optional<OutboxDelivery>`、`reschedule(String taskId,String leaseToken,Instant nextAt,String error): boolean`、`acknowledge(String taskId,Instant now): void`。claimTask 同样遵守到期/未确认/租约条件，但只能领取指定 task。
- OutboxEntry：event、createdAt、nextAttemptAt、attempts、acknowledgedAt；OutboxDelivery：event、leaseToken。
- `ReviewOutboxDispatcher.dispatchDue(): int`、`dispatchTask(String taskId): boolean`；注入 repository、DomainEventPublisher、Clock、配置；返回尝试发送条数或是否实际尝试。显式验收只能调用 dispatchTask，不能顺带投递其它任务。MQ 抛异常仍保留待确认记录，单条失败不终止整批。

- [x] **1. 写红灯测试。** `executionPersistsRenderedRequestAfterDag` 检查 taskId/headSha/comments/body；评审失败无 outbox；`mqFailureDoesNotMarkTaskFailed`；`dispatcherResendsSameEventUntilAcknowledged`（同 eventId/time/body）；`oneSendFailureDoesNotDropRest`；`completionReusesFirstPayload`；`dispatchTaskNeverSendsAnotherPendingTask`（两个待发任务，只领取并发送指定的一个）。
- [x] **2. 跑单测红灯。** 使用假时钟、内存仓储，不为此改 LLM 行为。
- [x] **3. 实现迁移、仓储和事务。** taskId FK/unique、eventId、payload JSONB、租约 token/到期、nextAttemptAt、attempts、acknowledgedAt、lastError、时间戳。claim 用一个短事务 SELECT FOR UPDATE SKIP LOCKED + 更新租约；调用 MQ 在事务外。ACK 后 reschedule 必须 no-op，避免迟到 sender 重开记录。register 事务提交后才可被 dispatcher 看见。
- [x] **4. 执行服务接线。** DAG 结果→报告→渲染→register；保留 executeReview 返回值。不在整个几十秒执行方法外套事务。报告交接失败可条件标 FAILED；已有 outbox / 已发布不得被 catch 无条件回退。空 chunks 的早退路径也须到合法 AGGREGATING 再交接，补空 diff 回归，不能在 PLANNING 上插“完成”请求。
- [x] **5. 实现扫描配置。** `codemind.publication.dispatch-enabled=${CODEMIND_PUBLICATION_DISPATCH_ENABLED:true}`；30s/50/120s/60s 默认值按 Global Constraints。普通测试关闭扫描；服务始终可注入、手动 dispatchDue。生产 publisher 的 MQ bean 由 Task 9 添加，阶段测试用记录型 publisher，不以空实现悄悄吞生产事件。
- [x] **6. 真实 PG IT 转绿。** register 插入失败事务回滚；多 dispatcher 只一方领取；lease 到期重新领取；ACK 与迟到 reschedule 竞争后仍确认；无 outbox 的中断可恢复。有 outbox 的旧任务清理先删 outbox 再 task。原 RealPullRequestReviewIT 验 AGGREGATING/outbox/记录型事件，不能连真实 topic。
- [x] **7. 交付。** 保存单测与 PG IT 结果，未接 MQ 的事实明确标注，不自动提交。

### Task 7: integration 保守发布编排

**Files:**
- Create: `I/application/{ReviewPublishService,PublishOutcome,ReviewPublicationBodies}.java`
- Test: `IT/application/ReviewPublishServiceTest.java`、`ReviewPublicationBodiesTest.java`

**Interfaces:**
- `ReviewPublishService.publish(ReviewCompleted): PublishOutcome`，依赖 ScmReviewPublisher、PublicationLock、PublicationRepository、现有 DomainEventPublisher。
- `PublishOutcome = PUBLISHED, AWAITING_CONFIRMATION`；锁忙/可重试拒绝/查重失败抛异常；不确定返回 AWAITING_CONFIRMATION（不发成功事件，outbox 后续仍重投核查）。
- `ReviewPublicationBodies.marker(String taskId): String`、`normal(ReviewCompleted): String`、`summaryOnly(ReviewCompleted): String`、`matches(String body,String taskId): boolean`；所有正文保留原 summary，降级正文收齐原始行级意见、path/line，不再调 LLM。
- 重投找到已有 review 时的实际结果：从同一 task 的持久化发送模式计算 inlineCount/degraded；旧 IN_FLIGHT 在 POST 前已有 mode，不能按新传入事件猜。

- [x] **1. 写发布状态机红灯。** `durableBarrierPrecedesHttp` 记录调用顺序；`databaseFailureBeforeAttemptMakesZeroPosts`；`publishedRecordOnlyResendsReceipt`；`exactMarkerMatchAvoidsSubstringCollision`；`timeoutThenMissingMarkerNeverReposts`；`serverErrorNeverBlindlyRetries`；`restartedInFlightOnlyReconciles`；`markerFoundAfterTimeoutPersistsActualResult`；`receiptFailureDoesNotRepost`。
- [x] **2. 写边界红灯。** `persistsSummaryOnlyBefore422Fallback`；`restartAfter422UsesSummaryOnly`；`summaryOnly422DoesNotLoop`；`incompleteListingMakesZeroPosts`；`lostLeaseBeforeHttpMakesZeroPosts`；`twoConsumersWithExpiredLockStillMakeOneAttempt`（用共享 ledger stub 原子 CAS）；`differentPayloadRejected`；`staleAttemptCannotOverwritePublished`。stub 抛 ScmException 模拟结果，不用真实网络。
- [x] **3. 跑单测确认红灯。** 每个错误场景都同时断言 POST 次数、ledger 状态、是否发送回执，不能只断言抛没抛。
- [x] **4. 实现 spec §7.3 顺序。** 本地已成功直接补回执；其它先完整查标记；READY 用 CAS 提交 IN_FLIGHT 后再检查 lease 才 POST。失锁后不继续降级写。网络/0/5xx/无法判断结果置 UNCERTAIN；明确拒绝的 401/403/404/422/429 等恢复 READY 后重试（INLINE 422 先保存 SUMMARY_ONLY）。即使回写 ledger 失败，也不能清除原 IN_FLIGHT 栅栏。finally 释放锁不得吞掉主异常。
- [x] **5. 转绿。** 遍历正常、422、超时、重启、回执失败流程；`summaryOnly` 标记一致，重投不会生成新 taskId。
- [x] **6. 交付。** B 策略代价如实保留：发前落库后尚未 POST 就退出，也会保守等待人工确认。不自动按超时重置 READY。

### Task 8: 成功回执事务与恢复边界

**Files:**
- Create: `R/application/ReviewPublicationService.java`
- Modify: `R/infrastructure/persistence/JdbcReviewTaskRepository.java`、`R/domain/task/ReviewTaskRepository.java`
- Modify: `R/application/StaleTaskRecoveryService.java`（更新注释、必要的条件调用，不扩成新调度系统）
- Test: `RT/application/ReviewPublicationServiceTest.java`、补 StaleTaskRecoveryServiceTest
- IT: `RT/application/ReviewPublicationServiceIT.java`、补 `RT/infrastructure/persistence/StaleTaskRecoveryIT.java`

**Interfaces:**
- `ReviewPublicationService.markPublished(ReviewPublished): boolean`：成功推进或幂等确认返回 true，未知/不匹配状态返回 false 并记录 warn。
- 仓储新增 `markPublishedIfAggregating(String taskId): boolean`；只在持有 task 行锁的应用事务里使用。
- 原 `findStaleInProgress` 与 `claimForRecovery` **都**加 NOT EXISTS outbox；claim 更新也须受同一条件保护，不能只改 SELECT。

- [x] **1. 写红灯。** AGGREGATING + outbox 成功→PUBLISHED 且 ACK；重复回执不变；缺 task/缺 outbox/非法状态不推进、不 ACK；事务中途故障全部回滚。
- [x] **2. 写真实 PG 竞争 IT 红灯。** 无 outbox 的 AGGREGATING 旧任务被捞；有 outbox 的任意未确认任务不被捞；查询后新增 outbox，再调用 claim 也失败。两连接屏障测试 register 与 claim 竞争：注册先赢则 claim false；claim 先赢则 register 拒绝且无 outbox；不得出现 FAILED + 新 outbox。回执与 claim 竞争也不把 PUBLISHED 改回 FAILED。
- [x] **3. 跑目标单测/IT 确认红灯。** PG 错误不当作行为红灯。
- [x] **4. 实现。** 应用事务固定锁序 task→outbox；ack 与条件状态更新同事务。已有 outbox 不重建报告。执行服务 conditional markFailed 和恢复 SQL 共同守住边界。
- [x] **5. 转绿并回归旧 6 个 StaleTaskRecoveryIT 用例。** 原“任务新节点旧不算卡死”仍成立；不把当前里程碑变成全面修复首次触发抢占。
- [x] **6. 交付。** 状态含义、恢复条件和并发断言写进测试描述，不提交。

### Task 9: MQ 接线与隔离链路 IT

**Files:**
- Create: `R/infrastructure/mq/RocketMqDomainEventPublisher.java`、`R/interfaces/mq/ReviewPublishedListener.java`
- Create: `I/interfaces/mq/ReviewCompletedListener.java`
- Modify: `I/infrastructure/mq/RocketMqDomainEventPublisher.java`、两服务 `application.yml`
- Modify: `codemind-integration/pom.xml`（Awaitility test，版本沿 Boot）
- Test: 两服务 `infrastructure/mq/RocketMqDomainEventPublisherTest.java`、两个新 listener 的 `*Test`
- IT: `IT/interfaces/mq/ReviewCompletedListenerIT.java`、`RT/interfaces/mq/ReviewPublishedListenerIT.java`
- Modify: 既有 MQ/装配 IT 及 Task 1 测试配置，补 `RT/infrastructure/config/ReviewDagWiringIT.java`

**Interfaces:**
- review publisher：ReviewCompleted→`codemind.mq.topic.review-completed`。
- integration publisher：保留 PullRequestReceived 映射，加 ReviewPublished→`review-published`。
- listener 只做 Boot ObjectMapper 解析与调用应用服务；解析异常外抛让 MQ 重试。
- integration group `codemind-integration-review-completed`；review group `codemind-review-review-published`；生产 topics `codemind-review-completed` / `codemind-review-published`。

- [x] **1. 单测先红。** JSON 含 Instant 正确往返，发送 payload 是 String、KEYS=eventId；未知事件拒绝；listener 解析失败外抛；不是用 checkpoint ObjectMapper；未知任务回执不推进。
- [x] **2. 跑单测确认失败后实现 MQ 适配与配置。** 不为正常启动新增空 publisher，也不注册 checkpoint mapper 为全局 bean。
- [x] **3. integration 链路 IT。** 真实 MQ/Redis/PG，替换 ScmReviewPublisher 为线程安全内存实现（create 时保存 marker）；真实 RocketMQDomainEventPublisher 发回执，测试专用 MQ consumer 收集。重复投递等待收到两次完成处理/回执，断言 createReview=1，而不是第一次完成立刻断言。用独立 topic/group，测试结束关消费者。
- [x] **4. review 链路 IT。** 真实 MQ/PG；插任务+outbox 后发送 ReviewPublished，Awaitility 查库 PUBLISHED/ACK；重投不变，非法状态不变；按 taskId 清理。dispatcher 测试手动调用，不扫描其它测试数据。
- [x] **5. 回归普通隔离。** 所有新旧 IT 默认都不发生产 review-completed；RealPullRequestReviewIT 使用 recording publisher、dispatcher disabled；task/outbox 清理有依赖顺序。ReviewDagWiringIT 检查报告/outbox/MQ beans；integration bean 在 PG 迁移后可启动。
- [x] **6. 运行全仓 `mvn verify`。** 此时只读真实 LLM/GitHub IT 仍需真实 key 与运行中的 integration；没有前置就如实记录 skipped/失败，不能称全绿。不得为了获得 0 skipped 删除测试。
- [x] **7. 交付。** 记录真实中间件链路证据与剩余验收项，尚未执行 GitHub 写验收。

### Task 10: 显式 profile、两阶段真实验收和运维说明

**Files:**
- Modify: `codemind-review/pom.xml`
- Create: `RT/application/RealPullRequestPublishIT.java`
- Create: `RT/support/{SandboxGithubClient,SandboxReviewCleanup}.java`
- Test: `RT/support/SandboxReviewCleanupTest.java`
- Create: `docs/operations/M7b-publication.md`
- Modify: `README.md`（验收命令与 PG 前置）

**Interfaces:**
- review failsafe 默认排除 **精确文件** `**/RealPullRequestPublishIT.java`，不排除所有 *PublishIT 以免漏掉未来本地 IT。
- Maven profile `github-write` 清除此排除并包含普通 IT，避免 profile 把日常回归替换掉；用 effective-pom 验实际合并，不凭想象判断 excludes 继承。
- 单个验收入口读 `codemind.github-write.phase=prepare|publish`（默认 prepare）。prepare 只读 GitHub、跑 LLM、保存 outbox，打印 taskId 与完整待发预览；publish 只接受明确传入的已准备 taskId，不能重评审生成新请求。
- 验收上下文关闭后台 dispatcher，只对已确认 taskId 手动 dispatchTask；profile 显式覆盖隔离默认 topic 到确认的验收链路。选择期间 integration 必须连接相同 topic，不能误以为 Maven profile 会改变外部 jar 配置。prepare/publish 都不能启动扫描全部 outbox 的后台任务。

- [x] **1. 先写清理策略红灯单测。** 只有 repo 精确 sandbox、作者=token 当前用户、review body 同时有 task 标记和专用 `<!-- codemind:test=github-write -->` 才列为候选；排除他人回复和非测试 review；分页读完、失败不删；不删除 IN_FLIGHT/UNCERTAIN 的本地任务。
- [x] **2. 实现测试支持与 profile。** GitHub 验收辅助 HTTP 只放 test，不成为 review 生产 SCM 实现。清理删除行级评论、把提交 review body 改“已清理”，保留测试标记以便识别空壳；最新结果不在末尾删。正常生产 report 不附测试标记；验收用测试 renderer 装饰正文，并把该正文存入 outbox，不能投递时临时改 payload。
- [x] **3. 验证选择与安全前置。** 默认 effective-pom 排除目标；profile effective-pom 包含目标；`mvn test` 不执行任何 IT。显式验收缺 repo/pr/head/key/token/task 等用 assert 失败，不跳过。prepare 只读核对 repo/head；publish 再核对目标仍匹配，任何变化停下，不替用户选新 head。
- [x] **4. 首次清理门禁。** 只读列出旧测试 review/comment IDs、作者和目标 URL。停下来等用户确认后才允许 cleanup；没有旧测试内容就说明无需删除。清理和发布分别授权，不因用户选过清理方案就直接删。
- [x] **5. prepare。** 中间件 healthy、integration 新 jar 副本启动、无其它 review 生产实例；每条需密钥命令单独 source 仓库外环境文件，不回显。运行：
  `mvn verify -pl codemind-review -am -Pgithub-write -Dit.test=RealPullRequestPublishIT -Dfailsafe.failIfNoSpecifiedTests=false -Dcodemind.github-write.phase=prepare`。
  打印 taskId/目标/summary/行级 path-line-body，outbox 仍未投递。
- [x] **6. 首次发布门禁。** 展示 prepare 的准确内容、最多 10 条、将创建的 GitHub review、目标 sandbox PR，停下等用户确认；未确认不跑 publish。
- [x] **7. publish 与断言。** 使用同一命令加 `-Dcodemind.github-write.phase=publish -Dcodemind.github-write.task-id=<已确认UUID>`。投递已保存请求，等真实回执；查库 PUBLISHED/ACK，GitHub 恰好一个完整 task 标记 review；行级 1~10 且每条都在新侧 diff，至少一条覆盖 `i <= size()` 预埋缺陷。再发同事件并等处理确认，review 数仍 1。若真实 LLM 未命中目标，判验收失败，不能手工造 finding 后声称通过。
- [x] **8. 写运维说明并验证。** 包含 schema、状态查询、outbox 重投、UNCERTAIN 只读核查、回执丢失恢复；人工重置示例要求无活动 sender、用户确认、记录证据、WHERE task/state/attemptToken 条件和受影响行数检查；无自动超时重置。全部 SQL 示例不含真实秘密，不自动执行重置。
- [x] **9. 全仓回归与用户目视。** 普通 `mvn verify` 0 skipped、显式 profile 实跑通过；给用户 sandbox PR 链接查看最新行级评论。两阶段 prepare 不是发布验证通过；只有 publish 和网页验收通过才完成。

## S3 / S4 / S5 交接

- 判定门三项修订及 integration PG 存储已经用户确认；本计划按这些结论写成，没有重新改变整批 review、噪声规则、sandbox 范围。
- 审阅重点：Task 5 的持久化发送栅栏；Task 6/8 的 task→outbox 锁顺序；Task 10 的真实写门禁。
- S4 任务清单即 Task 1~10，每任务必须有红→绿证据。
- 建议执行方式：`subagent-driven-development`，先锁公共接口，独立的 Task 2/3/4 可并行，其余按依赖顺序；每个实现任务独立检查，最后仍跑 S6 双轴评审。
- 不自动创建远程项目、不自动提交、不自动合并；如需要工作树，先调用 `using-git-worktrees`，不要在未确认时复制密钥进工作树。

## S6 / S7 / S8 收尾检查

- [x] `code-review` 固定点 `2a52baa`，标准轴 CLAUDE.md、规格轴设计/本计划/ROADMAP M7；严重问题回 S5 修复。
- [x] `verification-before-completion`：compose 前置、普通 verify、显式真实 publish 验收，逐项统计 failures/errors/skipped；未实测的不标完成。
- [x] `knowledge-consolidation`：更新 PROGRESS（真实分支 feat/m7b-publish、命令/结果/遗留/下一会话）、ARCHITECTURE（outbox、账本、恢复与保证边界）。
- [ ] 用户要求代码提交后调用 `github-commit`；没有要求则只提示提交，不自行 commit/push。
