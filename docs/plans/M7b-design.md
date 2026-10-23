# M7b 回写 PR 设计（S1 共识，经判定门修订）

日期：2026-10-22。状态：原设计已逐段确认；下述判定门修订已在对话中确认，实施计划仍需审核。

## 1. 目标、约束与已定决策

目标：真实 PR 评审完成后，在 GitHub 提交一个 `event=COMMENT` 的 review（总结 + 行级评论），任务最终到 `PUBLISHED`。
只在 `zlanbin/codemind-sandbox` 验收。首次真实写入或清理前，列出目标 PR、待发内容或待删对象，等用户确认。

| 问题 | 已确认决定 |
|---|---|
| 回写前加工 | review 做同行合并、hunk 校验、报告和 markdown；integration 只负责平台适配与发布 |
| 发布方式 | `POST pulls/{n}/reviews`，指定 `commit_id=headSha`、`event=COMMENT`、评论 `side=RIGHT`；不走 issues comments |
| 噪声 | 同 `(path, line)` 合并，组最高严重度至少 `MAJOR`，默认最多 10 条行级评论；其余进总结 |
| 回传 | integration 发 `ReviewPublished`，review 消费并条件推进状态 |
| 幂等依据 | GitHub review body 的 `<!-- codemind:task=<taskId> -->`；Redis 只作并发锁 |
| 日常与验收隔离 | 普通 `mvn verify` 不写真实 GitHub；真实写入仅显式 `-Pgithub-write`；普通 IT 使用发布替身、独立 topic |
| 不确定结果 | 优先避免重复写入。POST 结果不确定后，只读核查；找不到标记也不自动再次 POST，等人工确认 |
| 可靠投递 | review 保存完整发布请求，通过 outbox 自动补发，直到收到成功回执；不重跑 LLM |
| integration 存储 | 接入现有 PG 实例，独立 schema / Flyway 历史保存发布记录，不直接操作 review 表 |
| 清理 | 验收前清上一轮测试评论，只处理 token 用户且带测试标记的内容；已提交 review 无法删除，留下“已清理”的空壳 |

不再采用原设计的两项承诺：固定 120s 锁足以覆盖所有调用、GitHub 标记能提供严格 exactly-once。
不建原先 `(task, fingerprint)` 评论幂等表；但新增 review outbox（V5）和 integration 发布记录，分别解决可靠投递和保守重试。

## 2. 判定门修订及依据

### 2.1 普通测试不能因新增消费链而意外发布

原来的 `RealPullRequestReviewIT` 会调用 `executeReview`。接入发布后，不能“原样保留”：
它必须把事件出口替换为记录型测试替身，并关闭后台 outbox 投递；可以显式调用投递服务验证事件，但不能送到真实消费 topic。
MQ IT 使用独立测试 topic、消费组及 GitHub 写端口替身。profile 本身不是运行中服务的安全开关。

### 2.2 不确定结果必须有持久化栅栏

在 GitHub POST 前先提交 integration 发布记录的 `IN_FLIGHT` 状态；提交失败绝不发请求。
超时、断网、5xx 等不能证明“未写入”的结果，或进程重启后的遗留 `IN_FLIGHT`，都进入保守核查路径。
查不到标记不是安全重发的证据。Redis 丢锁、进程重启、MQ 重投，都不能清除这个栅栏。

### 2.3 AGGREGATING 不是“评审已经完成”的充分证据

现有 DAG 在 GlobalReduce **开始前**进入 `AGGREGATING`（`DagOrchestrator`）。保留这个含义，不重构状态机：

- `AGGREGATING` **无 outbox**：全局归约或报告交接可能未完成，仍允许 checkpoint 恢复。
- `AGGREGATING` **有 outbox**：评审已完成，只重投持久化请求，卡死恢复不得退回 `FAILED`。
- 准备 outbox 与恢复抢占都锁住同一 `review_task` 行，并在更新条件里重查 outbox，防止“先查询后插入”的竞态。

## 3. 数据流与服务边界

```
review                                     integration
DAG → GlobalReduce 完成
 → ReviewReportBuilder / Renderer
 → 事务：锁 task，确认 AGGREGATING，保存 outbox
 → OutboxDispatcher ── ReviewCompleted ───▶ ReviewCompletedListener
    未收到回执则定期补发                      → Redis 任务锁（续租）
                                            → 发布记录 + GitHub 标记查重
                                            → 发前提交 IN_FLIGHT
                                            → POST 一个 review
                                            → 保存 PUBLISHED 结果
ReviewPublishedListener ◀─ ReviewPublished ──┘
 → 事务：锁 task，确认 outbox，推进 PUBLISHED
```

Publish 放在 DAG 外。DAG checkpoint 不承担消息送达证明；outbox 存原始请求，重启后无需重新拉 diff、重新生成报告或调用 LLM。
零 Finding 也发总结。有降级时不得把“未发现问题”写成“所有检查均通过”。
评审旧 headSha 时仍对旧 commit 发布，不自动改绑最新 commit；GitHub 的具体接受结果由适配器处理，拒收则按错误策略走。

## 4. 公共事件（`codemind-common/event`）

- `ReviewCompleted(eventId, occurredAt, taskId, repo, prNumber, headSha, summaryBody, List<LineComment(path, line, body)>)`
- `ReviewPublished(eventId, occurredAt, taskId, inlineCount, degraded)`

均实现 `DomainEvent`；公共 record 不依赖任何服务的 domain 类型。集合防御复制。
outbox 创建后事件 ID、时间、正文、行级评论保持不变；重投不是重建事件。
沿用 Boot `ObjectMapper` 手动 JSON 字符串收发，不能用 checkpoint 的私有 default-typing mapper。

## 5. review 报告规则

`domain/report` 保持纯 Java：

```
ReviewReportBuilder.build(List<Finding>, List<DiffFile>, List<String> degradations, ReportPolicy) → ReviewReport
ReviewReport(List<InlineComment> inline, List<SummaryItem> summary, List<String> degradations, int totalFindings)
InlineComment(path, line, Severity maxSeverity, List<Finding> merged)
SummaryItem(Finding, Reason)
Reason = NO_LINE | OUTSIDE_DIFF | BELOW_THRESHOLD | OVER_LIMIT
ReportPolicy(minInlineSeverity=MAJOR, maxInline=10)
```

按顺序执行：

1. `line=0` → `NO_LINE`；文件不在 diff 或不在该文件任一 hunk 新侧区间 → `OUTSIDE_DIFF`。
   使用 `DiffHunk.coversNewLine`，上下文行也可评论；删除文件明确排除，重命名按新路径匹配。
2. 剩余按 `(path, line)` 合并，保留所有原始 Finding，取最高严重度。
3. 组最高严重度低于门槛 → 全组原始 Finding 进总结，原因 `BELOW_THRESHOLD`。
4. 按最高严重度从高到低、合并条数从多到少、path、line 排序，取前 10 组。超出的所有原始 Finding 进总结，原因 `OVER_LIMIT`。
5. 同输入输出一致，输入顺序变化也不改变内容：组内意见与总结按 severity、category、file、line、message、suggestion、fingerprint 确定性排序；降级信息排序后输出。

`ReviewTask.assignReport` / `report()` 挂内存报告，不另存完整领域对象；outbox 保存渲染后的发布请求。

`application/ReviewMarkdownRenderer`：
- 行级标题带最高严重度，列明每条意见的维度、严重度、描述、建议；多维度合并不只显示一种维度。
- 总结包含统计、按严重度分组的非行级意见、转入总结的原因、所有降级信息。
- 统计区分“行级评论组数 K”和“行级覆盖的原始意见数 L”：总 Finding 数 N = L + 总结意见数 M，避免合并后 N != K+M 的歧义。
- 零 Finding 且无降级显示“未发现问题”；有降级须明确“评审覆盖不完整”。

## 6. review 可靠投递与恢复

### 6.1 V5 outbox

在 review 默认 schema 增加 `review_publication_outbox`，以 taskId 唯一保存完整 `ReviewCompleted` JSON、eventId、创建时间、下次投递时间、投递租约、次数、确认时间和最后错误摘要。
状态按 `acknowledged_at` 区分待确认 / 已确认；MQ 发送成功不等于 GitHub 发布成功。

`ReviewCompletionService.register(event)` 在事务中：锁 task → 先查已有 outbox（已有则返回原请求，不覆盖）→
确认任务为 `AGGREGATING` → 插入 outbox 并刷新 task 时间，事务一起提交。
保留 DAG 已经进入 `AGGREGATING` 的行为；这里保证的是“评审完成交接”原子化，不是假称所有早期状态更新也在同一事务。

后台按批领取到期未确认记录，使用 PG 原子租约（`SKIP LOCKED`），事务提交后才发 MQ；不在数据库事务内等网络。
默认扫描 30s、批量 50、领取租约 120s、每条未确认请求 60s 后再发；进程崩溃后租约到期可重领。
时间经 `Clock` / 显式 now 入参测试，不靠长时间 sleep。异步投递默认开，普通测试显式关闭。

### 6.2 成功回执

`ReviewPublicationService.markPublished(event)` 在事务内锁 task：
- 有本任务 outbox 且任务为 `AGGREGATING`：条件更新为 `PUBLISHED`，确认 outbox，同事务提交。
- 已经 `PUBLISHED`：幂等确认，不退回状态。
- 缺 task、缺 outbox 或其它状态：不伪造成功，不确认 outbox，记可定位日志。

卡死查询和原子抢占均排除“已有 outbox”的任务。注册请求、恢复抢占、成功回执使用一致的行锁顺序：task → outbox。
执行服务异常处理也不能把已有 outbox 或 `PUBLISHED` 的任务改为 `FAILED`。

## 7. integration 平台适配与发布记录

### 7.1 GitHub 适配

`application/port/ScmReviewPublisher`：
- `listReviews(repo, pr) → List<ExistingReview(id, body)>`
- `createReview(repo, pr, ReviewDraft(commitId, body, List<DraftComment(path, line, body)>)) → ExistingReview`

`GithubScmClient` 同时实现只读 `ScmClient` 和写端口；Spring 只创建一个具体类型 bean，避免只声明 `ScmClient` 导致写端口无法装配。
分页每页 100、最多 10 页；到达上限但仍有下一页时抛错，**不能把部分列表当作完整查重结果**。
写调用统一 `ScmException(statusCode)`，0 表示网络错误；秘密不进日志或异常。

GitHub 标记仅证明本任务已有已提交 review；匹配完整隐藏标记，并排除 pending review。
一个请求提交一个 review，不把这种批量接口描述为端到端 exactly-once 或数据库意义的分布式事务。

### 7.2 独立 PG schema

integration 使用 `integration` schema、自有 `flyway_schema_history`、`classpath:db/integration-migration`，
首个迁移 `V1__create_review_publication.sql`，表 `review_publication`。review 仍使用原 schema 及 V1~V5。
复用 PG 实例和环境变量，但仓储不跨 schema 读写其它服务的业务表。

记录：taskId 主键、原始事件内容与内容摘要、repo/pr/headSha、状态、发送模式、attemptToken、GitHub reviewId、
实际 inlineCount/degraded、时间戳与最后错误摘要。模式 `INLINE` / `SUMMARY_ONLY`。
状态：`READY`、`IN_FLIGHT`、`UNCERTAIN`、`PUBLISHED`。
同 taskId 只接收首次保存的发布内容；不同内容重投拒绝处理，不用后来的 payload 覆盖第一次请求。

### 7.3 发布算法

1. 加 Redis 锁：`codemind:publish-lock:<taskId>`，120s TTL，每 30s 校验 token 并续租。
   抢不到锁则抛异常；失锁后停止后续外部写入；finally compare-and-delete，只释放自己的锁。
2. 初始化或读取 PG 发布记录。已有 `PUBLISHED` 时，直接按保存的实际结果补发回执。
3. 查 GitHub 标记：找到已提交 review，则保存其 id 与实际发布模式对应的结果，补发回执。
   列表失败或未扫描完整时不得继续 POST。
4. 若记录为 `IN_FLIGHT` / `UNCERTAIN` 且查不到标记：保持/标记 `UNCERTAIN`，返回待核查结果，不再 POST。
   outbox 后续重投仍可只读核查，查到后自动补回执；人工未确认前不能重置。
5. 只有 `READY` 可用条件更新领取 attemptToken、提交 `IN_FLIGHT`；确认仍持锁后才调用 POST。
   进程在提交后、请求前退出也按不确定处理，宁可人工介入，不猜测“肯定没发”。
6. 明确成功：按 attemptToken 保存 `PUBLISHED` 的实际结果，然后发 `ReviewPublished`。
   保存失败或回执发送失败都不能抹掉已有的发送栅栏；重投查 GitHub / 本地成功结果补回执。
7. 明确 422 且为 INLINE：原子保存模式 `SUMMARY_ONLY` 并回到 `READY`，把所有行级意见加到总结，注明降级；
   再按同样的发前落库流程发送一次。重启后继续 SUMMARY_ONLY，不反复尝试原始行级请求。
   SUMMARY_ONLY 仍失败则不递归降级。
8. 明确未写入的认证/权限/不存在/校验拒绝等响应可恢复 `READY` 并抛异常让 MQ 重试；
   网络异常、超时、5xx 或无法判断是否写入的响应一律保留 `UNCERTAIN`，不盲目重发。

Redis 锁是并发优化，不是正确性的唯一依据；PG 条件领取确保 TTL 失效也不能启动第二次不确定发送。
所有状态变化按旧状态和 attemptToken 条件更新，不允许旧持有者覆盖别人已确认的成功结果。

### 7.4 人工核查

不新增无鉴权 internal HTTP 端点。提供运维说明：如何只读查看本地记录与 GitHub 标记、记录核查证据；
只有用户明确确认原请求已结束且允许重试后，才能以带状态条件的事务重置同一任务为 READY。
不提供定时“超过几分钟就重置”的后门。不确定任务长期不能确认时保持待确认，不标完成。

## 8. MQ 与配置

- topics：`codemind.mq.topic.review-completed` / `review-published`。
- integration：`ReviewCompletedListener`，消费组 `codemind-integration-review-completed`；现有发布器增加 `ReviewPublished` 映射。
- review：新增 `domain/event/DomainEventPublisher`、对应 MQ 适配器和 `ReviewPublishedListener`。
- report：门槛 / 条数可配置，默认 MAJOR / 10。
- outbox：扫描开关及间隔可配置，生产默认开；测试关闭后台扫描后直接调用服务验证。
- 生产仍可由正常 webhook 自动发布；`-Pgithub-write` 只控制 Maven 测试选择，不控制生产行为。

## 9. 测试、隔离和验收

### 9.1 纯单测（`*Test`）

- 报告：四种总结原因、3 合 1、重命名/删除/上下文行、门槛、上限、组内排序、乱序输入输出一致、统计守恒、降级提示。
- GitHub mock：POST body、评论路径原样入 JSON、分页、超过上限 fail-closed、错误与 token 不泄漏。
- 发布状态机：发送前落库、已发跳过但补回执、422 持久化降级、超时/5xx 不重发、重启 IN_FLIGHT 不重发、
  锁丢失、PG 写失败、回执失败后重投、payload 冲突、旧 attempt 不覆盖新结果。
- outbox：相同请求重投使用同一事件；MQ 失败不丢记录；待确认定时补发；回执后停止；发布请求保存后异常不退回 FAILED。

### 9.2 真实中间件 IT（默认 verify）

- outbox / 发布记录：真实 PG 验迁移、事务回滚、唯一性、条件领取、多实例竞争、实际结果持久化。
- Redis：续租与 token 校验、旧持有者不能删新锁。
- MQ：独立测试 topic 与消费者，GitHub 写端口替身；同消息重投只创建一次，保存和接收 `ReviewPublished`。
- review 回执：真实 MQ + PG 验 `AGGREGATING + outbox → PUBLISHED`、重复/乱序回执、outbox 确认事务。
- 恢复：有/无 outbox 的 AGGREGATING 分支，恢复抢占与请求注册的竞争。
- `RealPullRequestReviewIT`：真实只读 SCM + LLM，发布出口替身、后台投递关；不向真实发布 topic 投递。
- 所有普通测试上下文均使用隔离 topic，不能继承真实发布链路；回归包括原 M1/M7a IT，不能只隔离新测试。

### 9.3 显式真实写入验收

`RealPullRequestPublishIT` 仅 `-Pgithub-write` 纳入测试集；缺 token/key/目标等必须失败，不能 assumeTrue 跳过。
保留已有只读真实 IT 的环境 gate，但有 skipped 时逐条交代并标未验证。

前置：中间件就绪，integration **新 jar 副本**跑在 8081，review 无另一个生产实例抢验收消息；
`GITHUB_TOKEN` 只授 sandbox 的 Contents read / Pull requests write；密钥从仓库外环境文件读取、不回显。
确认测试 repo 固定为 sandbox、PR 当前 head 与输入匹配，避免未审查目标或版本上写入。

流程：
1. 只读列出待清理旧测试 reviews 和评论，先经用户确认。
2. 只删 token 用户、带 codemind 测试标记的 review 下的该用户行级评论；只改这些 review 的 body 为“已清理”。
   生产幂等标记与测试专用标记分开，不能清理本项目真实使用生成的 review。
3. 清理 DB 的上一轮 sandbox 任务时，先确保旧发布链路无未处理请求；不能删掉不确定发布记录或正在发送的任务。
4. 跑真实评审生成并持久化请求。打印待发目标、正文和行级数量，未确认不启用实际 outbox 投递。
5. 用户确认后，真实 MQ → integration → GitHub → 回执；等待 DB 到 PUBLISHED。
6. 回查恰好一个本 task 标记 review；行级 1~10 条且都在 diff 新侧；至少一条覆盖预埋 `i <= size()` 缺陷行；总结含统计；重投后不增加 review。
7. 最新一轮保留供用户目视验收，不在结束时删除。

## 10. 完成标准与非目标

完成：`mvn verify` 全绿、0 skipped；显式 profile 实跑通过；用户看到 sandbox PR 行级评论；
S6 固定点 `2a52baa`（M7a 合并后的稳定 master），标准/规格双轴独立评审通过。
只跑 stub、只过普通 verify、或不确定发布仍待人工核查，均不能标 M7b 完成。

不做：树形归约、跨 push 判重（M10）、通用死信治理（M9）、internal 全面鉴权、全系统首次评审抢占重构。
现有 internal 网关可达的缺口仍须在网关对外暴露前处理。

S8 同步 ARCHITECTURE：事件表、ReviewReport、DAG 外 outbox、AGGREGATING 恢复边界、integration 独立 PG 发布记录、
Redis/GitHub/PG 各自的保证边界。更新 PROGRESS 的分支、任务、命令、skipped 与遗留项；不自动提交。
