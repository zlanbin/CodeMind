# 开发进度（会话交接文件）

> 每个会话开始时先读本文件；结束前必须更新本文件。
> 只记录事实：做了什么、怎么验证的、结果如何。未验证的内容标注「未验证」。

## 当前状态

- 当前里程碑：**M13 已完成**（文档、演示脚本、README 指标，ROADMAP M13 验证点「新环境按
  演示脚本从零跑通」已达成：`D:\daima\codeTest` 全新 clone 目录，`docs/demo/00~07*.sh` 九个
  脚本 + `docs/demo/WALKTHROUGH.md` 走完步骤 0~7，真实覆盖 M7b 回写、M9a/M9b 反馈闭环、M10
  增量评审、M11 规则召回、M12 可观测指标，全部真实验证通过，非 mock）。M0~M12 全部完成，
  均已合并进 `master`
- 上次更新：2026-10-24
- 分支：本次 M13 工作直接在 `master` 上进行（纯文档 + 脚本改动，`docs/demo/*`、`README.md`，
  未改任何服务代码），尚未提交
- 阻塞项：无
- 未验证项：无。全部 8 个步骤均在新 clone 目录里真实跑通，细节见下方「M13」小节

## 下一个会话要做的事

M13 是 ROADMAP 里程碑清单的最后一项（M0~M13 全部完成）。下一步看用户是否有新的里程碑需求；
若没有，CodeMind 已具备 ROADMAP 定义的全部能力。

M13 可直接复用：
- 全链路演示脚本在 `docs/demo/00~07*.sh` + `99-stop.sh`，`docs/demo/WALKTHROUGH.md` 是串讲
  文档，新环境验证直接照这个走，不用从头现想命令
- 演示用的 sandbox PR 固定在 `zlanbin/codemind-sandbox` PR #4（分支 `demo/m13-walkthrough`），
  装了规范违规方法名 `x()` + 整数溢出 bug，专用于反复验证，不影响 M7a/M7b/M9a/M10/M11 用过的
  PR #1/#2/#3
- 密钥管理：演示需要 `lan-bin` 账号的 **classic token**（`repo` scope），不能用 fine-grained
  token——fine-grained PAT 的 "Only select repositories" 只能选 token 持有者自己拥有的仓库，
  协作者身份（即使已接受邀请、`role_name=write`）也选不到别人账号下的仓库，这是 GitHub 的
  已知限制（M13 实测踩过，生成 classic token 后问题消失）
- RocketMQ 积压排查：`docs/operations/M7b-publication.md` 的「outbox 未 ACK」排查路径里没覆盖
  到"MQ topic 本身被历史积压消息堵死"这种情况——这种情况下 review 侧 outbox 会显示
  `attempts` 持续增长但 `acknowledged_at` 一直为空，integration 侧账本表里却完全查不到对应
  `task_id` 的记录（消息还没被消费到）。诊断用
  `mqadmin consumerProgress -n rocketmq-namesrv:9876 -g codemind-integration-review-completed`
  查 `Diff` 列，正常应在几十条以内；偏高（几千条）就是积压，用 `mqadmin resetOffsetByTime`
  把 `codemind-review-completed` 和 `%RETRY%codemind-integration-review-completed` 两个
  topic 的 consumer offset 都重置到 `now`，跳过积压
- **Grafana `rate(...[5m])` 查询时机陷阱的精确根因**（M12 报告记过"等太久会滑出窗口"，M13
  实测进一步定位到具体边界）：DAG 节点耗时打点发生在任务到达 `AGGREGATING`（`GLOBAL_REDUCE`
  在 `AGGREGATING` 阶段内完成）之前，但任务要到 `PUBLISHED`（outbox 重投 + 回写 GitHub）往往
  还要再等 5~8 分钟。**如果等任务 `PUBLISHED` 之后才去查 P95 面板，几乎必然滑出 5 分钟窗口
  拿到 NaN**——这不是限流、不是 Prometheus 抓取延迟（本里程碑实测排查时都怀疑过这两个方向，
  最后用 `query_range` 对比节点完成时刻与查询时刻的时间差才定位到根因）。正确做法是任务到
  `AGGREGATING` 就立刻查，不等 `PUBLISHED`
- 真实 GitHub token 限流：`zlanbin` 的 `GITHUB_TOKEN`（5000 次/小时）在反复验证同一条链路
  （多次触发评审 + 处理 MQ 积压期间的重试请求）时会在 1 小时内打满，打满后 integration 侧
  对真实 GitHub 调用全部返回 403，查 `curl -H "Authorization: Bearer $GITHUB_TOKEN"
  https://api.github.com/rate_limit` 的 `remaining`/`reset` 判断要不要等

M12 可直接复用：
- Token 成本埋点：`LlmClient.complete(systemPrompt, userPrompt, callSite)`，`callSite` 固定
  7 个值（`STYLE`/`SECURITY`/`PERFORMANCE`/`TEST`/`CHANGE_MAP`/`GLOBAL_REDUCE`/
  `FINDING_FILTER`——第 7 个是本里程碑执行中发现 `FindingFilter`（M9b）也是生产调用方，
  spec 原文漏列）。`SpringAiLlmClient` 读真实 `ChatResponse.getMetadata().getUsage()` 打点
  `codemind_llm_tokens_total`（tag `call_site`/`type`），usage 缺失按 0 记录 + 单次 warn，
  不抛异常
- 节点耗时埋点：`Node` 接口加 `nodeType()`（6 个固定值：`REVIEW_CHUNK`/`BUILD_CHANGE_MAP`/
  `CHUNK_REDUCE`/`GLOBAL_REDUCE`/`RECALL_RULES`/`PLAN_CHUNKS`），domain 新端口
  `MetricsRecorder`（`DagScheduler` 注入，成功/每次重试失败都各记一次），infrastructure
  `MicrometerMetricsRecorder` 用 `Timer.builder(...).publishPercentileHistogram().register(...)`
  （**不能用 `meterRegistry.timer(...)` 便捷方法**，那样不会发布 `_bucket` 系列，Grafana 的
  `histogram_quantile` 查询会永远返回 NaN——本里程碑真的踩过这个坑，Task 3/4 两次审查都没
  交叉核实到，被最终整体审查抓到）
- Prometheus + Grafana：`deploy/docker-compose.yml` 已接入两个容器，6 个服务都暴露
  `/actuator/prometheus`。Grafana provisioning 的 dashboard JSON 里 `datasource.uid`
  **不能写数据源名字的字面量**（如 `"Prometheus"`），后端 API 查询会 404（前端 UI 有
  by-name 回退能跑，但告警/快照等后端路径不会）；要写真实 uid（本机当前是
  `PBFA97CFB590B2093`，由 provisioning 的 datasource 名字推导出来，建议下次在
  `deploy/init/grafana/provisioning/datasources/prometheus.yml` 里显式钉 `uid` 字段，
  不要依赖隐式推导）
- 压测基础设施：`loadtest` Spring profile + `StubLlmClient`（`@Profile("loadtest")` 与
  `SpringAiLlmClient` 的 `@Profile("!loadtest")` 互斥），2~8 秒随机延迟模拟真实 LLM 调用，
  **压测替身只能挡住 LLM 调用，挡不住 review 经 integration 回头拉真实 GitHub diff 这条路径**
  （`loadtest` profile 没有替身 SCM 内容拉取），压测必须用真实存在的只读仓库/PR（本机用的是
  `zlanbin/codemind-sandbox` PR #1，环境变量 `CODEMIND_TEST_REPO`/`CODEMIND_TEST_PR`）
- `LoadTestRunner`（`codemind-review/src/test/java/.../loadtest/`，手动运行工具不参与
  `mvn verify`）：并发压测、HMAC 签名、PG 轮询用的是**真实 `updated_at` 列**而不是
  `Instant.now()`——**并发压测工具如果串行轮询多个任务，用 `Instant.now()` 记录"查到终态的
  时刻"会把延迟数据测成假象**（本里程碑真的踩过：第一轮报告显示 P50~P99 只差 0.1 秒，像是
  系统完全没有排队，实际是测量方法的伪影，真实离散度是 2~3 秒量级）。下次写类似压测工具
  直接用数据库时间戳，不要用发起查询时的本地时钟
- Grafana 查询验证的时机坑：`rate(...[5m])` 这类滑动窗口查询，如果等太久再去验证（比如压测
  跑完等 5~6 分钟才查），流量会滑出窗口导致查询返回 NaN，容易误判成"配置错误"——验证要在
  流量窗口内完成，或者持续补一点流量进去再查
- 遗留：仓库里有两个未跟踪文件 `docs/workflow-architecture-分享版.html`、
  `docs/workflow-architecture-进化版.html`，与现有里程碑都无关，未处理，下次看到可以确认是否
  需要清理或归档

遗留：仓库里有两个未跟踪文件 `docs/workflow-architecture-分享版.html`、
`docs/workflow-architecture-进化版.html`，与 M11 及现有里程碑无关，未处理，下次看到可以确认是否
需要清理或归档。

M10 做完了什么（ROADMAP M10 验证点：第二次 push 只评审新 diff，不重复发评论）：
- `ScmClient`/`ScmContentPort` 加 `fetchCompareDiff(repo, oldHead, newHead) -> CompareResult`，
  GitHub compare API 两段式调用（先查 status，`ahead` 才取 diff 文本）
- 新表 `last_reviewed_head`（repo+pr_number 为主键）记录上一次成功发布的 base/head；
  `ReviewExecutionService.resolveDiff` 按它决定走 compare 增量还是 `fetchDiff` 全量，
  base 变化（rebase）、compare status 非 ahead、compare 调用本身失败都回退全量
- 新表 `fingerprint_registration_outbox` + `FingerprintRegistrationDispatcher`：发布成功后把
  本次 Finding 指纹登记到 memory 的 `task-fingerprints` 端点，下次评审用 `TaskFingerprintPort.published`
  过滤已发布过的指纹（叠在 M9b 的记忆拒绝过滤之后）
- **Final Review 修了一个关键设计坑（F1）**：指纹数据不能放在 `ReviewTask.report`（内存态，`markPublished`
  操作的是数据库 rehydrate 出来的全新对象，report 永远是 null）。改成指纹跟着 `ReviewCompleted` 事件走——
  事件本身就是存进 `review_publication_outbox` 的 JSON，`markPublished` 从那里读，不用新查询
- 真实验收（Task 8）：在 `zlanbin/codemind-sandbox` PR #1 走了两轮真实发布——baseline（head `9950568`，
  25 个 Finding/指纹，10 条行级评论）、increment（head `655b47e`，只对新 commit 的 diff 评审，5 个新指纹，
  3 条行级评论）。GitHub API 独立核实：3 个 review 分别带 9/10/3 条评论，互不重复
- 验收用的 IT 固定在 `codemind-review/src/test/java/.../application/IncrementalReviewSandboxIT.java`，
  跟 `RealPullRequestPublishIT` 一样走 `-Pgithub-write` profile、prepare/publish 分阶段人工确认，
  不在日常 `mvn verify` 里跑

M11 可直接复用：
- rule-service 的上传/召回入口：`POST /internal/rules/documents {project,title,content}` →
  `Result<UploadDocumentResponse>`（含 chunk 数）；`POST /internal/rules/recall {project,query,topK?}` →
  `Result<RecallResponse>`（每条带 `documentTitle/sectionPath/heading/content/similarity`）
- 版本替换：同 `project+title` 重新上传时，`RuleIngestionService.upload`（`@Transactional`）先把旧
  ACTIVE 文档转 ARCHIVED 再插入新文档+新切块；`rule.chunk` 本身没有 status 列，切块跟着所属文档的
  ACTIVE/ARCHIVED 走，召回 SQL 必须 join `rule.document` 过滤 ACTIVE（M11 T8 真实踩过：漏 join 会让
  旧版本内容和新版本一起被召回）
- review 侧接入：`RuleRecallPort`（domain 出站端口）→ `FeignRuleRecallAdapter`（经 Feign 调
  `codemind-rule`，`Result` 信封同 M9b 教训）→ `RecallRulesNode`（DAG 节点，按 chunk 粒度调用一次，
  4 个评审维度共享结果，不重复召回）→ `ReviewChunkNode`（新增 `recallRulesNodeId` 可选字段，从
  `context.completedResults()` 取上游结果）→ `ReviewAgent.review(chunk, changeMap, rules)`（新重载，
  `rules` 非空时 prompt 追加「团队规范条款」区块，要求引用形式为 `《文档标题》sectionPath`）
- `DagOrchestrator` 新增 `RuleRecallPort` 构造参数走向后兼容路线：保留 2 参/4 参构造器（转调
  `rulePort=null`），新增 5 参构造器；`rulePort` 与 `project`（来自 `task.repo()`）都非 null 时才建
  `RecallRulesNode`。**生产装配必须显式把这个参数传进 `DagOrchestrator` 的 `@Bean` 方法**——只把
  `RuleRecallPort` 注册成独立 bean 不会自动接上，Final Review 真的踩过这个坑（bean 存在但
  `DagOrchestrator.rulePort` 字段仍是 null，`RecallRulesNode` 永不创建，功能形同摆设），
  靠反射断言 `dagOrchestratorIsWiredWithRuleRecallPort` 才抓到
- 验收 IT 要证明「验证点在生产链路上端到端跑通」，不能在最后一步手工把召回结果塞给 Agent——
  即使前两步（上传、召回）各自验证过也不够，必须走完整 `DagOrchestrator.executeCrossSessionReview`
  （ChangeMap/切片阶段可用确定性 stub 节点，不需要陪跑真实 LLM，只有规则相关的节点需要真实）
- 真实验收要启动独立 `codemind-rule`（8084）进程，Feign 走 `codemind.rule.url` 静态覆盖（同
  M10 的 `codemind.integration.url` 做法），不能只关 Nacos discovery

M10 可直接复用：
- 增量 diff：`ScmContentPort.fetchCompareDiff(repo, oldHead, newHead) -> CompareResult(status, diffText)`，
  `isAhead()` 判定能不能用增量；两侧（integration/review）各自定义同形 DTO，不跨模块共享
- 判断走全量还是增量的决策树在 `ReviewExecutionService.resolveDiff`：无基准/base 变了/compare 非 ahead/
  compare 调用异常，四种情况都回退全量 diff，只有「有基准 + base 相同 + compare 返回 ahead」才走增量
- 指纹登记落地流程：`ReviewPublicationService.markPublished` 首次推进到 PUBLISHED 时，从 outbox 里
  `ReviewCompleted` 事件的 `fingerprints()` 字段取指纹（不是 `task.report()`——那个字段只在同一次
  `executeReview` 调用栈内有效，`markPublished` 操作的是数据库 rehydrate 出来的对象）
- `FingerprintRegistrationDispatcher.dispatchDue()` 要手动触发或等 `@Scheduled`（默认 30s 一轮，
  `codemind.fingerprint.dispatch-enabled=false` 可关）才会真的调 memory 的 `POST /internal/task-fingerprints`
- 真实验收要同时拉起 `codemind-integration`（8081）和 `codemind-memory`（8083）两个独立服务，
  Feign 走静态 URL 覆盖（`codemind.integration.url` / `codemind.memory.url`），不能只关 Nacos discovery——
  关了 discovery 但没给静态 URL，Feign 会直接报「No servers available」

清理状态（M10 新增，sandbox PR #1）：
- review `5636551096`（baseline，10 条行级评论）、`5636711855`（increment，3 条行级评论），
  都带 `codemind:test=github-write` 标记，可用 `SandboxReviewCleanup` 清理
- `review_publication_outbox` 历史行曾因缺 `fingerprints` 字段在消息重放时触发反序列化 NPE，
  已对所有历史行回填 `fingerprints: []`；新环境若看到同样报错先查这张表有没有漏填的行

M9a 可直接复用：
- 反馈轮询：`POST /internal/feedback/poll?repo=&prNumber=`（integration）；定时器 `CODEMIND_FEEDBACK_POLL_ENABLED`
- 记忆里的否决规则形如 `[维度] 本项目允许 / 不要求……`，维度前缀是 M9b 判同类的线索
- sandbox PR #2 上有 review `5626224555`（行级评论 `4357950659`，带标记与测试标记）及 `lan-bin` 的 👎 与回复

M8 可直接复用：
- 写入 / 强化：`POST /internal/memories {project, kind, content, importance?}`，`kind=REJECTED_PATTERN` 就是 M9 的否决记忆；
  同内容再写 = 强化（importance 取大、刷新衰减起点、ARCHIVED 复活）
- 召回：`POST /internal/memories/recall {project, query, topK?, kind?, minScore?}`，每条带 similarity / decay / score。
  **`minScore` 默认 0 会带回低分无关记忆**，M9 的 Dedup & Filter 要定阈值（建议先用真实反馈样本校准）
- 指纹：`POST|GET /internal/task-fingerprints`（M10 增量去重，已接线，见上 M10 小节）
- review 侧还没有 memory 的 Feign 客户端，M9 新建；两侧 DTO 各自定义
- Embedding 是本地特征哈希（只认字面重叠）。M9 若判定「同类代码」需要语义相似，可能得换真实 embedding 模型

清理状态：
- sandbox PR #2 上有两个 CodeMind review：`5624353955`（无测试标记，工具清不掉）与 `5624674315`。留着不影响

M7 可直接复用：
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

### M13 文档、演示脚本、README 指标 —— 完成于 2026-10-24

纯文档 + 脚本里程碑，无独立设计输入文档，S1 头脑风暴定设计共识（脚本粒度：分步 shell 脚本 +
markdown walkthrough；覆盖深度：全链路含 M7~M12；sandbox PR：新开专用 PR；验证方式：新目录
`D:\daima\codeTest` clone 全新仓库跑通），按 bounded 任务走（跳过 S2~S4 计划文档），直接进 S5
实现 + 实测验证。

改动范围（只动文档和脚本，未改任何服务代码）：

- `docs/demo/00~07*.sh` + `99-stop.sh`（9 个脚本）+ `docs/demo/WALKTHROUGH.md`：密钥检查 →
  起基础设施 → 构建起 6 服务 → 上传规范文档 → 触发评审（真实 webhook + 真实 LLM + 真实回写）→
  反馈闭环（真实 👎 + 回复 + 轮询）→ 增量评审（第二次 push）→ 核实 Grafana 指标
- `README.md`：新增「核心能力」清单（多 Agent 并行评审/跨会话 Map-Reduce/行级回写/反馈学习
  闭环/增量评审/团队规范 RAG/可观测，覆盖 M8~M12 此前未写进 README 的能力）、「验证点与测试
  规模」指标小节、启动命令补 `CODEMIND_LLM_API_KEY`/`GITHUB_TOKEN` 说明、新增「全链路功能
  演示」小节指向 `docs/demo/WALKTHROUGH.md`

验证方式与结果（2026-10-24，`D:\daima\codeTest` 全新 clone 目录，真实中间件 + 真实
`CODEMIND_LLM_API_KEY`/`GITHUB_TOKEN`/`lan-bin` classic token）：

| 验证点 | 做法 | 结果 |
|---|---|---|
| **新环境按演示脚本从零跑通**（ROADMAP M13） | 在全新目录完整走完 `docs/demo/00~07*.sh` 步骤 0~7 | **通过**。密钥检查、起基础设施（容器复用同一套 compose 工程，具名卷数据不丢）、`mvn package` 构建 + 起 6 服务全 UP、上传规范文档（chunkCount=1）均成功 |
| 真实评审 + 规则召回 + 回写（M7b + M11） | sandbox PR #4（`lan-bin` 开，内含方法名 `x()` 违规 + 整数溢出 bug），真实 webhook → 真实 4 维度并行 LLM 评审 → 真实回写 | **通过**。行级评论命中第 14 行（引用《示例团队规范》3.2.1）、第 15 行（CRITICAL 整数溢出），review `5638253675` 由 `zlanbin` 发布（非 PR 作者，符合 422 规避约束） |
| 反馈闭环（M9a/M9b） | 对 STYLE 评论回复「内部临时脚手架代码，不对外暴露，不需要遵守命名规范」+ 👎，触发 `/internal/feedback/poll` | **通过**。LLM 正确抽取出方向正确的两条否决记忆（`[STYLE]`/`[DESIGN]` 均为"允许/不要求"而非误判方向），`importance=0.9` |
| 增量评审（M10） | 第二次 push（新增 `applyFixedFee` 方法），第二次 webhook（`action=synchronize`） | **通过**。`last_reviewed_head` 正确记录 base/head；第二次评审意见数从 6 条降到 2 条（均为新增 diff 的 TEST 维度意见）；否决过的命名违规意见在新评审中未再出现（`FindingFilter` 真实拦截） |
| Grafana 可观测指标（M12） | Token 用量面板（累计计数器，任意时刻查询）+ DAG 节点耗时 P95 面板（`rate(...[5m])`，需在任务到 `AGGREGATING` 后立刻查） | **通过**。Token 面板 7 个 call_site 全覆盖（含 `FINDING_FILTER`，证明反馈过滤真实被调用）；P95 面板 6 个 node_type 全部拿到非 NaN 真实数值（`REVIEW_CHUNK`=14.03s/`BUILD_CHANGE_MAP`=5.66s/`GLOBAL_REDUCE`=3.20s/`RECALL_RULES`=0.017s/`PLAN_CHUNKS`=0.022s/`CHUNK_REDUCE`=0.0055s） |

验证过程中发现并处理的真实环境问题（详见上方「M13 可直接复用」）：
1. `03-upload-rule.sh` 用不可用的 `python3`（Windows Store 占位程序）做 JSON 转义兜底，改用
   `node -e`；进一步发现 mingw 原生 `curl.exe` 处理含中文的命令行参数/变量展开会损坏 UTF-8
   字节（导致 Jackson 报「请求体无法解析」），统一改为写临时文件 + `curl --data-binary @file`
2. `lan-bin` 的 fine-grained token 怎么配置 Repository access 都查不到 `codemind-sandbox`
   （即使已是协作者）——根因是 fine-grained PAT 无法访问「协作者身份参与、非持有者拥有」的
   仓库，换成 classic token（`repo` scope）后问题消失
3. RocketMQ `codemind-review-completed` 系列 topic 积压 5000+ 条历史失败消息，把新事件的
   消费吞吐堵死，新评审卡在 `AGGREGATING` 不推进；用 `mqadmin resetOffsetByTime` 跳过积压解决
4. 演示脚本原定 150s 的 `PUBLISHED` 轮询超时在真实环境下不够（outbox 重投间隔 + 真实回写
   延迟），放宽到 500s
5. Grafana P95 面板反复查到 NaN，最终定位根因是查询时机选在了任务 `PUBLISHED`（全流程终态，
   比节点打点晚 5~8 分钟）之后，而不是节点指标真正产生的 `AGGREGATING` 阶段——用
   `query_range` 对比节点完成时刻与查询时刻的时间差才确认，排除了限流和 Prometheus 抓取延迟
   两个错误假设

安全事项（本会话中途发生，已处理）：过程中 `lan-bin` 的两个 GitHub token（一个 fine-grained、
一个 classic）先后被用户明文贴入对话，已提醒用户在演示结束后立即 revoke 并重新生成；
`LANBIN_GITHUB_TOKEN` 只写入本机密钥文件 `~/.codemind-secrets.env`，命令输出中未重复回显值。

与 ARCHITECTURE.md 的偏差：无需同步（不改设计、不改代码，纯文档与演示脚本）。

### M12 可观测与压测 —— 完成于 2026-10-24

无独立设计输入文档，ARCHITECTURE.md 技术栈表已点名选型（`Micrometer + Prometheus + Grafana`：
Token 成本、节点耗时），本会话头脑风暴定设计共识，写成 spec
`docs/superpowers/specs/2026-10-24-m12-observability-loadtest-design.md`，按 `writing-plans`
落成 `docs/plans/2026-10-24-m12-observability-loadtest.md`（T1~T6，链式依赖）。6 个任务全部
`subagent-driven-development` 执行（独立任务 ≥3 个），每个任务独立审查，完成后整条分支最终审查
（opus，跨任务一致性检查）。隔离开发用 `using-git-worktrees`
（`.worktrees/feat-m12-observability-loadtest`，分支 `feat/m12-observability-loadtest`）。

改动范围（review-service + 6 服务配置 + deploy）：

- T1：`LlmClient.complete()` 接口签名加 `callSite` 参数，三处 spec 原文点名的调用方
  （`ReviewAgent`/`ChangeMapAgent`/`GlobalReduceAgent`）传入固定字面量。执行中发现第 4 处
  生产调用方 `FindingFilter.java`（M9b 否决判定）被 spec 漏列，裁决计入 Token 成本指标，
  `callSite` 固定值从 6 个扩展到 7 个（补 `FINDING_FILTER`）
- T2：`SpringAiLlmClient` 改用 `.call().chatResponse()` 读取真实 `ChatResponse.getMetadata().getUsage()`，
  按 `promptTokens`/`completionTokens` 打点 `codemind_llm_tokens_total`（tag `call_site`/`type`），
  usage 缺失按 0 记录 + 单次 `log.warn`，不抛异常不中断调用链
- T3：`Node` 接口加 `nodeType()` 默认方法（6 个具体实现类覆写固定常量：`REVIEW_CHUNK`/
  `BUILD_CHANGE_MAP`/`CHUNK_REDUCE`/`GLOBAL_REDUCE`/`RECALL_RULES`/`PLAN_CHUNKS`），domain
  新增端口 `MetricsRecorder`，`DagScheduler` 注入并在成功/每次重试失败时都打点（不只是最终
  失败才记），infrastructure `MicrometerMetricsRecorder` 实现打点 `codemind_dag_node_duration`
- T4：`deploy/docker-compose.yml` 接入 Prometheus + Grafana 两个容器，6 个服务（gateway/
  integration/review/memory/rule/admin）加 `micrometer-registry-prometheus` 依赖、暴露
  `/actuator/prometheus`。Grafana provisioning 预置 dashboard（两个面板：Token 用量按
  call_site、节点耗时 P95 按 node_type）
- T5：新增 `loadtest` Spring profile，`StubLlmClient`（`@Profile("loadtest")`）与
  `SpringAiLlmClient`（`@Profile("!loadtest")`）互斥，用 2~8 秒随机延迟 + 固定合法样例 JSON
  模拟真实 LLM 调用。执行中发现 `FINDING_FILTER` 落 default 分支会导致 `FindingFilter` 在
  压测时走降级路径（耗时数据失真），裁决加专门分支返回 `{"filtered": false}`
- T6：`LoadTestRunner`（手动运行压测工具，不参与 `mvn verify`）+ 真实跑一轮 10 并发×30 任务
  压测 + 压测报告 `docs/reports/M12-loadtest.md`。压测过程中发现两处环境约束（`loadtest`
  profile 没有替身 SCM 内容拉取路径，需用真实只读仓库；integration-service 需要真实
  `GITHUB_TOKEN` 避免匿名限流），均为必要修正记入报告
- Final Review 修复：整条分支最终审查发现两处更早任务遗留的真实缺口（`StubLlmClient` 没打
  Token 指标；`MicrometerMetricsRecorder` 用普通 Timer 没开 histogram，Grafana P95 面板永远
  查不到数据）导致 ROADMAP 验证点未真正达成，裁决回 S5 补齐（不接受"压测本身跑通就算完成"）。
  修复后发现 `LoadTestRunner` 串行轮询 + `Instant.now()` 记录"查询时刻"而非"真实完成时刻"的
  方法论缺陷（延迟数据被测成假象），一并修复并重跑 4 轮压测验证。最后一轮 scoped re-review
  又发现报告里一处 token 累计数字翻倍且自相矛盾，修正后全部 clean

验证命令与结果（2026-10-24，真实中间件，review-service 跑 `loadtest` profile + integration-service
跑默认 profile）：

| 验证点 | 命令 / 用例 | 结果 |
|---|---|---|
| **Grafana 可见指标**（ROADMAP M12） | Grafana 后端 API 查询两个面板对应 PromQL | **通过**。Token 用量面板（`sum by (call_site) (codemind_llm_tokens_total)`）6 个 call_site 非空非 NaN；节点耗时 P95 面板（`histogram_quantile(0.95, sum by (le, node_type) (rate(codemind_dag_node_duration_seconds_bucket[5m])))`）6 个 node_type 非空非 NaN，与压测报告记录的数值在特定时间戳三位有效数字吻合（复审独立验证） |
| **压测报告存入 `docs/reports/`**（ROADMAP M12） | `LoadTestRunner`（10 并发×30 任务） | **通过**。4 轮压测均 30/30 成功 0 失败 0 超时；修复后真实延迟 P50=33.3s/P95=36.0s/P99=36.7s（真实离散度 2~3 秒量级，修复前因测量方法缺陷显示的"P50到P99只差0.1秒"已纠正，原始记录保留未删改）；吞吐 48.83~70.32 任务/分钟（随压测轮次推进因本机资源持续承压下降，符合预期）；call_site 维度 token 分布 6 个值全覆盖 |
| review-service 全仓回归 | `mvn test -pl codemind-review` | **通过**。237 测试 0 失败 0 错误 0 跳过，相对 M11 完成时的基线无回归 |

S6 评审（6 个任务各自独立 general-purpose 子代理审查 + 1 次整条分支最终审查，opus，固定点
`12ce9a7` 即 M11 完成点）：

- 单任务审查：Task 1~6 均 Approved，0 Critical，少量 Minor（均已记入 deferred 或已修复）
- 最终整体审查发现 2 项 Important（均已修复并经 scoped re-review 确认 ADDRESSED）+ 复审又
  发现 1 项新 Important（报告数字错误，一行修正）：
  1. `LoadTestRunner` 延迟测量方法论缺陷（串行轮询 + `Instant.now()` 导致数据失真）——改用
     数据库 `updated_at` 列，RED（重算得到真实分布 P50=26.7s 对比报告写的 25.4s）→GREEN
  2. 压测报告未跟上指标修复后的状态（仍写"未验证通过"与分支实际相反）——追加"修复后复测"
     节，原始记录保留不删改
  3.（复审新发现）报告里 token 累计数字翻倍且与同段话另一数字自相矛盾——一行纠正
- 4 项跨任务一致性检查全部通过：7 个 callSite 值无遗漏无拼写偏差；两条指标名/tag 名在
  生产代码/测试/dashboard 三方完全一致；domain 层（`LlmClient`/`Node`/`MetricsRecorder`/
  `DagScheduler`）grep 确认零 Micrometer/Spring 依赖；bean 装配无歧义（`DagScheduler` 新参数
  有 `Objects.requireNonNull` 守卫，不会出现 M11 踩过的"bean 在但构造参数是 null"陷阱）

与 ARCHITECTURE.md 的偏差：**无需同步**（技术栈表 2 已列 Micrometer/Prometheus/Grafana「M12
引入」，本次按此落地，设计本身未变；本里程碑不改 DAG 流程图、领域模型或服务边界，只加观测性
埋点与基础设施）。

### M11 rule-service：规范文档 RAG —— 完成于 2026-10-24

设计输入文档路径（`docs/design/M11-rule-upload.docx`）在仓库中不存在，经用户确认跳过 S0 文档比对，
直接头脑风暴定设计共识，写成 spec `docs/superpowers/specs/2026-10-24-m11-rule-upload-design.md`，
再按 `writing-plans` 落成 `docs/plans/M11.md`（T1~T16，链式依赖，`executing-plans` 顺序执行，配
TDD）。隔离开发用 `using-git-worktrees`（`.worktrees/feat-m11-rule-upload`，分支
`feat/m11-rule-upload`）。执行账本 `.superpowers/sdd/M11/progress.md`。

改动范围（rule-service 新落地 + review-service 接入）：

- rule-service（T1~T8）：pom 加 jdbc/postgresql/flyway（同 memory 做法，独立 schema `rule`）；
  `V1__create_rule.sql` 建 `rule.document`/`rule.chunk` 两表（版本替换用部分唯一索引
  `(project,title) where status='ACTIVE'`，`rule.chunk` 本身无 status 列，跟着所属文档走）；
  domain `rule` 包：`RuleDocument`/`RuleChunk`/`RuleChunkCandidate`/`ScoredRuleChunk`（record）、
  `RuleDocumentStatus` 枚举、`Embedder`/`RuleDocumentRepository`/`RuleChunkRepository` 端口、
  `RuleDocumentChunker`（按 Markdown 标题层级切块，纯函数）、`RuleRanker`（简化版 `MemoryRanker`，
  `score=clamp(similarity)`，无 importance/decay）；infrastructure：`HashingEmbedder`（从 memory
  复制算法独立维护）、`JdbcRuleDocumentRepository`/`JdbcRuleChunkRepository`（两路候选 SQL，join
  document 过滤 ACTIVE）；application：`RuleIngestionService`（`@Transactional`，切块→embedding→
  版本替换）、`RuleRecallService`（校验+召回+排序截断）；interfaces：`RuleController`+`RuleDtos`+
  `RuleExceptionHandler`；`RuleConfig` 装配 bean
- review-service 接入（T9~T15）：domain `rule` 包（独立定义不跨模块共享）：`RuleRecallPort`/
  `RecalledRule`/`RuleRecallException`；infrastructure `rule` 包：`RuleFeignClient`（`Result`
  信封，同 M9b 教训）+ `FeignRuleRecallAdapter`；新 DAG 节点 `RecallRulesNode`（按 chunk 粒度召回
  一次，4 维度共享，召回失败静默返回空列表不升级为 degradation）；`ReviewChunkNode` 新增可选
  `recallRulesNodeId` 字段（向后兼容既有三/四参构造器）；`ReviewAgent` 新增三参重载
  `review(chunk, changeMap, rules)`（向后兼容既有两个重载）；`DagOrchestrator` 新增
  `RuleRecallPort` 构造参数（向后兼容：保留 2/4 参构造器转调 null，新增 5 参构造器）；
  `ReviewDagConfig.dagOrchestrator` 接上 `RuleRecallPort` bean
- 真实验收（T16）：固定资产样例 `src/test/resources/rule/sample-convention.md`（§3.2.1 方法命名
  规则）+ 违规 diff；`RuleRecallAndCiteIT` 真实启动独立 `codemind-rule`（8084）进程 + 真实 PG，走
  `DagOrchestrator.executeCrossSessionReview` 生产链路（ChangeMap/切片用确定性 stub，4 维度+
  全局归约用真实 LLM）

验证命令与结果（2026-10-24，真实中间件 + 真实 `CODEMIND_LLM_API_KEY`，rule-service 跑在 8084）：

| 验证点 | 命令 / 用例 | 结果 |
|---|---|---|
| **上传规范文档后，评审意见能引用对应条款**（ROADMAP M11） | `RuleRecallAndCiteIT`（真实 LLM，走生产 `DagOrchestrator.executeCrossSessionReview` 链路） | **通过**（15.7s 实跑）。上传 §3.2.1 方法命名规则样例文档 → `rules-chunk-order` 节点真实在 4 个评审维度节点之前执行（日志证实）→ 4 维度并行真实调用真实 LLM → `ChunkReduceNode` 归约出 6 条去重 Finding → 全局归约 → 断言其中至少一条 `message` 包含 `"3.2.1"` 通过 |
| rule-service 全仓回归 | `mvn verify -pl codemind-rule` | **通过**。40 测试 0 失败 0 错误 0 跳过 |
| review-service 全仓回归 | `mvn verify -pl codemind-review` | **通过**。287 测试，1 error（`RealPullRequestReviewIT`，integration-service 本次未启动，环境原因与 M11 无关，M7a 既有 IT）、1 skipped（`MemoryFeignClientIT`，memory-service 未启动，M8/M9b 既有 IT，与 M11 无关），均非本次改动引入 |

S6 评审（双轴独立 general-purpose 子代理，固定点 `46f44ec`，标准轴对 `CLAUDE.md` 约定、规格轴对
`docs/plans/M11.md` 与 spec）：

- 标准轴 0 硬违规；规格轴 1 Critical + 3 Important（两轴各发现部分重叠，去重后 3 条进 Fix Pass）
- **Critical**（规格轴发现）：真实验收 IT 第 3 步原手工调用 `ReviewAgent.review(chunk, null, rules)`，
  绕过了 `RecallRulesNode`/`ReviewChunkNode`/`DagOrchestrator` 整条生产链路——ROADMAP 验证点从未在
  生产路径上端到端验证过。改走 `DagOrchestrator.executeCrossSessionReview`，RED→GREEN 真实跑通过
- **Important**（两轴独立发现同一处）：`RuleIngestionService.upload` 三次独立写（archive→insert→
  insertAll）无事务边界（spec D7 要求但未落地）。加 `@Transactional`，用 `@TestBean` 换故意失败的
  仓储替身验证 RED（未加事务时 archivedCount=1，应为 0）→GREEN
- **Important**（两轴独立发现同一处）：`RuleRecallService.recall` 不校验 `project`、`query` 无长度
  上限（同模块内 `upload` 路径已校验但 `recall` 没有，不一致）。补 `MAX_PROJECT_LENGTH=255`、
  `MAX_QUERY_LENGTH=8000`（同 memory 既定上限）
- 已判定为既有模式非缺陷（不改代码）：验收 IT 需要手动起 rule-service 才跑——`RealPullRequestReviewIT`
  等既有真实 IT 同样如此，是 WORKFLOW.md 验证门既定口径（跳过时不标完成）的自然结果

遗留问题 / 技术债（S6 Minor，不处理）：

- 空标题测试用例字面与计划 Review Focus 所列字面不完全一致（结论无误，仅输入文本差异，`"#"` 与
  `"# "` 经 `stripTrailing()` 后行为相同）
- `RecallRulesNode` 只捕获 `RuleRecallException` 不是 `RuntimeException`——当前唯一实现
  `FeignRuleRecallAdapter` 已把所有异常包装，无实际风险，但换端口实现时有漏包风险
- `codemind.rule.default-top-k` 配置项未绑定到任何代码，是死配置（实际默认值是
  `RuleRecallService` 里的私有常量）
- `JdbcRuleChunkRepository.insertAll` 逐条 insert，大文档切块数等于往返次数，未用批量
- `RuleDocumentChunker` 的层级栈注释写"[level, headingText]"但实际 `int[]` 只存 level
- `ReviewAgent` system prompt 里"违反了上方给出的团队规范条款"的"上方"指向错误——规则条款实际
  注入在 user prompt，不在 system prompt
- `ReviewAgent` 风格零碎：内联全限定 `java.util.stream.Collectors.joining`、`truncate` 的 500
  是裸魔数无常量名
- `/internal/rules/**` 端点无鉴权，同 M7a/M8/M9a 既有缺口，非本次引入

与 ARCHITECTURE.md 的偏差：**已同步更新**（见下次 S8 提交的 ARCHITECTURE.md 改动）。7.4 新增
rule-service 落地范围；5 DAG 图补 `RecallRulesNode`；8 加三条决策。

### M9b Dedup & Filter 过滤生效 —— 完成于 2026-10-23

计划 `docs/plans/M9.md` 文末「M9b 实施计划（S2）」一节，Task 1~5 按 `executing-plans` 顺序执行，Task 6（真实验收）
单独向用户确认后执行。执行账本 `.superpowers/sdd/M9/progress.md`（已按完成清理）。

改动范围（只动 `codemind-review`）：

- domain：`domain/memory/{MemoryRecallPort, RecalledMemory, MemoryRecallException}`（召回端口）；
  `domain/finding/{FindingFilter, FilterOutcome}`（Dedup & Filter 核心：对每条 Finding 召回同项目否决记忆候选
  + LLM 判定同类，D3 方案 A；召回失败/无候选/LLM 判定失败一律放行，不压制真缺陷）
- infrastructure：`infrastructure/memory/{MemoryFeignClient, RecallRequestDto, RecallResponseDto, FeignMemoryRecallAdapter}`
  （Feign 适配器，调 memory 的 `POST /internal/memories/recall`，`kind=REJECTED_PATTERN`，`project=task.repo()`）；
  `infrastructure/config/FindingFilterConfig`（Bean 装配，复用既有 `codemind.llm.max-retries`）
- 接线：`ReviewExecutionService` 构造器新增 `FindingFilter` 参数，在 `reportBuilder.build(...)` 之前插入过滤，
  过滤降级说明并入 `allDegradations()`
- minScore 沿用 M9a 冲突消解的 `0.05`，topK=5（硬编码常量，风格与 `FeedbackLearningService.CONFLICT_MIN_SCORE` 一致）

S6 评审（独立 general-purpose 子代理，因本环境无专用 code-reviewer agent 类型；固定点 `23df653` 即 M9a 完成点）
发现 1 项 Critical + 3 项 Important + 2 项 Minor，已全部回 S5 修复并重跑验证：

- **Critical**：`MemoryFeignClient.recall` 返回类型漏了 memory 侧统一的 `Result` 信封（真实响应是
  `{code,message,data:{memories:[...]}}`，不是裸 `{memories:[...]}}`），导致 Feign 解码后 `memories` 恒为
  `null`，过滤器在生产环境形同空操作，ROADMAP M9 验证点无法通过。根源是计划草稿对契约的描述有误（照抄了
  `IntegrationScmFeignClient` 的裸返回写法，但 integration 的 internal 端点本身不套信封，两者不同）。
  修复：改为 `Result<RecallResponseDto>` 并在适配器里取 `.data()`；另加真实 HTTP 端到端 IT
  `MemoryFeignClientIT`（对真实运行中的 memory 服务写入 + 召回，证明契约真的对得上，不是靠 stub 自证）
- **Important**：`FeignMemoryRecallAdapter` 的 `item.memory()` 解引用在 try 块外，脏数据触发的 NPE 会绕过
  `MemoryRecallException` 直接让整个评审任务失败，违反「召回失败必须放行」的约束——已移进 try 块
- **Important**：`FindingFilter` 过滤掉 Finding 时零日志，无法追溯；memory 不可用时每条 Finding 各产生一条
  降级说明，会在公开 PR 评论里刷出重复的内部错误文本——已加 `log.info`（命中 fingerprint/category/规则内容），
  召回失败的降级说明按根因合并成一条
- **Minor**：LLM 判定失败后降级文本不含失败原因（已补 `lastError`）；「多候选只命中一条，不影响其他 Finding」
  缺测试覆盖（已补 `onlyFindingMatchingRecalledRuleIsFiltered`）
- Deferred minor（未修）：`FindingFilter` 逐条串行调 LLM，意见多的大 PR 会线性拉长评审时延，风格与 DAG 内
  并行的 `ReviewAgent` 不一致

验证命令与结果（2026-10-23，真实中间件 + 真实 `CODEMIND_LLM_API_KEY` / `GITHUB_TOKEN`，integration 8081 + memory 8083 + review 8082）：

| 验证点 | 命令 / 用例 | 结果 |
|---|---|---|
| **真实 PR 上 👎 过的意见不再出现，真缺陷照常出现**（ROADMAP M9） | sandbox 新 PR #3（作者 `lan-bin`，head `bbcf401`）：新增 `VerificationTarget.java` 含 SECURITY/PERFORMANCE 同类代码 + 1 处真实 NPE（`remainingStock` 对 null 调 `intValue()`）；真实 webhook 触发评审 | **通过**。review 日志证明过滤器真实生效（无降级）：1 条 SECURITY Finding 命中 `[SECURITY] 不要求对内部来源的 sku 做 null/empty 校验` 被过滤、2 条 PERFORMANCE Finding 命中 `[PERFORMANCE] 允许单线程场景用普通 HashMap` 被过滤；真实发布到 GitHub 的评论（review `5630225538`）里确认：被过滤的两类意见原文均未出现（`null/empty` 字样仅出现在 diff 代码上下文和一条无关的 TEST 类"缺测试覆盖"意见里，经比对非否决意见复活）；`remainingStock` 的 NPE 风险作为 BLOCKER 行级意见正常出现（**但 LLM 把它归类为 `TEST` 而非预期的 `CORRECTNESS`**，过滤器按真实 category 召回判定，TEST 类不会命中 SECURITY/PERFORMANCE 的否决记忆，结果仍正确，只是分类与预想不同——记为观察项，非缺陷） |
| 契约修复不是臆造 | `MemoryFeignClientIT`（对真实运行中的 memory 服务写入 + 召回） | **通过**，非跳过。修复前用同样的真实 wire format 反序列化会得到 `memories=null`（已用临时实验验证） |
| 全仓回归 | `mvn verify`（真实中间件 + 真实 key，两轮：Task 5 装配验证 + Final Review 修复后复验） | **通过**。均为 8 模块 BUILD SUCCESS，0 Failures 0 Errors **0 Skipped**。真实 LLM/GitHub IT 全部实跑（`RealPullRequestReviewIT`、`CrossSessionMapReduceIT`、`MultiAgentIntegrationIT`、`ReviewAgentIT`、`FeedbackExtractorIT`），无跳过需说明 |

遗留问题 / 技术债：

- `FindingFilter` 逐条串行调用召回 + LLM，大 PR 意见多时会线性拉长评审时延（见上方 deferred minor）
- Review Focus「CORRECTNESS 真缺陷不被语义相近的否决记忆误杀」的真实 LLM 语义判定能力，本次只靠 Task 6
  这一次真实验收证明（1 个真缺陷样本），未补独立的 `FindingFilterIT` 做更系统的真实 LLM 回归；
  若后续同类误杀复现（参考 M9a T6 的方向性错误教训），应补专门的真实 LLM IT
- 真实验收用的 sandbox PR #3（`zlanbin/codemind-sandbox`）已发布评审，未清理（同 M8/M9a 的做法，留着不影响）
- `minScore=0.05`、`topK=5` 仍是硬编码常量，与 M9a `FeedbackLearningService` 风格一致，但两处常量各自独立定义，
  未来若要统一调整需要改两个地方

与 ARCHITECTURE.md 的偏差：无需同步（7.2 第 5 步「Dedup & Filter 节点过滤同类意见」在 M9a 落地时已写明
「留 M9b」，本次是把这句话实现出来，设计本身未变）。

### M9a 反馈进来（标记 → 轮询 👎 / 回复 → LLM 抽取 + 冲突消解）—— 完成于 2026-10-23

M9 按 ROADMAP「里程碑过大时」拆成 M9a / M9b（计划 `docs/plans/M9.md`，D1~D5 用户在判定门拍板，D15~D17 在 T6 实测后拍板）。

改动范围：

- common：`event/FindingMarker`（行级评论隐藏标记的格式与解析）、`event/FindingFeedbackReceived`
- review：`ReviewMarkdownRenderer.renderInline` 每条 Finding 追加标记
- integration：端口 `ScmFeedbackReader`；`GithubScmClient` 加 `listReviewComments` / `listThumbsDown`（共用 `listAllPages`，Link 分页 fail-closed）/ `canWrite`；
  `V3__create_finding_feedback.sql`（PENDING / SENT / FAILED / SUPERSEDED）、domain `feedback`、`JdbcFindingFeedbackRepository`；
  `FeedbackCollectionService`（轮询、作者鉴权、回复取代 👎、投递）；`FeedbackConfig` 定时器、`ClockConfig`；
  `FeedbackController` `POST /internal/feedback/poll` + `FeedbackExceptionHandler`；MQ topic `codemind-finding-feedback`
- memory：加 rocketmq + spring-ai 依赖；domain `feedback`（`FeedbackExtractor`、`ConflictResolver`、`Lessons`、`ConflictDecision`、
  `FeedbackInput`、`LlmJson`、端口 `LlmClient` / `FeedbackEventRepository`）；`V2__create_feedback_event.sql`；
  application `FeedbackLearningService`；`infrastructure/llm/SpringAiLlmClient`、`JdbcFeedbackEventRepository`；`interfaces/mq/FindingFeedbackListener`

验证命令与结果（2026-10-23，真实中间件 + 真实 `CODEMIND_LLM_API_KEY` / `GITHUB_TOKEN`）：

| 验证点 | 命令 / 用例 | 结果 |
|---|---|---|
| **真实 PR 上 👎 后 memory 出现对应 REJECTED_PATTERN**（M9a） | T6：`-Pgithub-write` prepare → publish 发 review `5626224555` 到 sandbox PR #2；`lan-bin` 对行级评论 `4357950659` 👎 并回复「SECURITY 和 PERFORMANCE 不适用…」；本机 integration 8081 + memory 8083 生产 topic，`POST /internal/feedback/poll` | **通过**（第二轮）。账本：回复 `SENT`、👎 `SUPERSEDED`；memory outcome `ADD#289;ADD#290`（无 `DEGRADED:`）；记忆 `[SECURITY] 本项目不要求对内部来源的 sku 参数进行 null/empty 校验`、`[PERFORMANCE] 本项目允许在单线程访问场景下使用普通 HashMap…`，importance 0.9；回复没点名的 CORRECTNESS / TEST 未学 |
| GitHub 侧契约 | T6 只读回查 | **通过**。评论正文 4 个标记原样保留；`pull_request_review_id` = 账本 `review_id`；`content=-1` 过滤可用；`lan-bin` 权限 `write` |
| 重复轮询不重复学习 | T6 再次 poll | **通过**。`registered=0`，记忆 2 条、事件 1 条、账本 2 条均不变 |
| 抽取方向与维度（真实 LLM） | `FeedbackExtractorIT`（4 例，54s 实跑） | **通过**。单条 👎 写成「本项目允许…不要求…」；合并评论的回复只学点名维度；「谢谢已改」不否决 |
| 冲突消解硬约束 | `ConflictResolverTest`（8 例） | **通过**。NOOP/UPDATE 只落 REJECTED_PATTERN；DELETE 只删 COMMON_DEFECT；否决不互删；UPDATE 保留维度前缀 |
| 学习事务、幂等、降级、AMBIGUOUS | `FeedbackLearningIT`（8 例，真实 PG + MQ + 脚本 LLM） | **通过** |
| 收集、鉴权、取代、投递 | `FeedbackCollectionServiceTest`（8 例）、`FindingFeedbackRepositoryIT`（6 例）、`FeedbackPollIT`（2 例，真实 MQ） | **通过** |
| 全仓回归 | `mvn verify`（不带过滤） | **通过**。8 模块 BUILD SUCCESS，**496 测试 0 失败 0 错误 0 跳过**（common 11；gateway 1；integration 140 + 36 IT；review 175 + 51 IT；memory 50 + 29 IT；rule / admin 各 1）。真实 LLM/GitHub IT 均实跑 |

T6 第一轮验收**发现严重缺陷**（链路通、内容错），已修复后重跑通过：
- 👎 与回复都学：👎 的抽取把 4 条原意见复述成「本项目要求校验 / 必须线程安全」——方向与否决相反（含真缺陷 NPE）；
  冲突消解再以「矛盾」为由 DELETE 掉回复学到的正确规则。照这些记忆过滤会把真缺陷压掉
- 修法（用户确认）：D15 同一人既回复又 👎 只学回复；D16 合并评论只有 👎 记 AMBIGUOUS 不学；D17 抽取 prompt 规定方向并给正反例，
  👎 直接告诉模型已确定否决（否则无回复时模型答 rejected=false，走模板）；DELETE 只能删 COMMON_DEFECT
- 第一轮错误记忆（id 207~210）与事件登记已按用户同意删除；V3 迁移因改动本地重置后重新应用

S6 评审（固定点 `3a26ee0`，标准轴 / 规格轴两个独立子代理）：标准轴 0 硬违规；规格轴 1 严重（NOOP 落在 COMMON_DEFECT）。
连同 10 余条判断题回 S5 修复，明细见 `docs/plans/M9.md` S6 节。T6 后的修复未再过独立评审，由新增单测 / IT / 真实验收覆盖。

本里程碑踩到的环境问题（非代码缺陷）：
- 中转端点偶发 HTTP 524，Spring AI 自动重试，单次调用最长约 150s
- 全仓 verify 期间机器睡眠，`PullRequestReceivedListenerIT` 显示耗时 12082s 后超时报错；单独重跑 11s 全绿，全仓重跑通过
- 单独 `mvn -pl <模块>` 不带 `-am` 时会用本地仓库里旧的 common jar，编译报找不到新类；先 `mvn install -pl codemind-common`
- `codemind-review-completed-test` 上有一条残留消息反复重投，触发 integration IT 上下文里真实 GitHub 读（M7b 记过的「普通 IT 未替换 GitHub 端口」）

遗留问题 / 技术债：
- 撤销 👎 / 删除回复不回收已学记忆；先 👎 后（下一轮轮询）才回复时，👎 已被学过，回复不会取代它
- memory 消费重试耗尽进死信后反馈丢失（integration 已标 SENT，不再重投）；模板降级（`DEGRADED:`）无自动补学
- 每轮轮询 N+1 调 GitHub（每条评论查一次 reactions、每个新作者查一次权限）；匿名 token 时仍默认开启、403/429 无退避；
  评论超过 1000 条的 PR 每轮 fail-closed 收不到反馈
- NOOP 时目标若被并发删除会被写回；`collect(repo, pr)` 在 Java 里过滤而非下推 SQL；手动 poll 会投递全部 PENDING
- 定时器在 `infrastructure.config`，与 memory 的 `interfaces/mq` 驱动方位置不统一
- M9b 尚未开始：review 侧不读否决记忆，**目前学到的记忆还不影响评审结果**

与 ARCHITECTURE.md 的偏差：**已同步更新**。3.3 事件表注明 M9a topic；7.2 补对回 Finding、拿反馈、鉴权、取舍、可靠投递、抽取、冲突消解约束与已知限制；8 加两条决策。

### M8 memory-service（L1/L2 记忆、混合检索）—— 完成于 2026-10-23

计划与设计共识 `docs/plans/M8.md`（D1~D13；用户授权「以推荐为准」，S1/S3 未单独停）。

改动范围（只动 `codemind-memory`）：

- pom 加 jdbc / postgresql / flyway（+ postgresql 模块）/ failsafe；`application.yml` 加数据源、独立 schema `memory` 的 Flyway、
  `codemind.memory.*`（半衰期 30d、归档阈值 0.05、候选数 50、默认 top-k 5）；测试 properties（Hikari 池 2、关 Nacos）
- `db/memory-migration/V1__create_memory.sql`：`memory.memory`（vector(256)、`(project, kind, content_hash)` 唯一、
  `(project, status)` 索引，**无 HNSW / GIN**）、`memory.task_fingerprint`
- domain `memory`：`Memory` / `MemoryKind` / `MemoryStatus` / `MemoryContent`（归一化 + SHA-256）/ `MemoryCandidate` /
  端口 `MemoryRepository`、`Embedder`；`recall`：`ScoringPolicy`（decay / score / retention / archiveCutoff）/ `MemoryRanker` /
  `ScoredMemory`；`fingerprint`：端口 `TaskFingerprintRepository`
- application：`MemoryService`（write / recall / archiveStale / delete，注入 `Clock` 截到微秒）、`MemorySettings`、`TaskFingerprintService`
- infrastructure：`HashingEmbedder`、`JdbcMemoryRepository`（两路候选 union、`xmax = 0` 判新建、按 `reinforced_at` 条件归档）、
  `JdbcTaskFingerprintRepository`、`MemoryConfig` / `MemoryProperties`
- interfaces：`MemoryController`、`TaskFingerprintController`、`MemoryExceptionHandler`、`dto/MemoryDtos`
- `MemoryApplicationTest` 改名 `MemoryApplicationIT`（M8 起依赖 PG）

验证命令与结果（2026-10-23，真实中间件 + 真实 `CODEMIND_LLM_API_KEY` / `GITHUB_TOKEN`，integration 跑在 8081）：

| 验证点 | 命令 / 用例 | 结果 |
|---|---|---|
| **召回排序符合打分公式**（ROADMAP M8，纯单测） | `MemoryRankerTest`（10 例）、`ScoringPolicyTest`（8 例） | **通过**。含 40 条随机候选按测试内独立重算的公式排序比对、importance 压过 similarity、衰减把旧记忆排后、逐路 clamp（含 NaN）、合并两路、并列规则、topK / minScore、ARCHIVED 不参与 |
| **写入**符合语义（去重 / 强化 / 复活） | `MemoryContentTest`（5 例，纯单测）+ `JdbcMemoryRepositoryIT`（9 例） | **通过**。归一化同 hash；importance 取大、`reinforced_at` 不倒退、ARCHIVED 复活、同内容不同 kind 分开 |
| 端到端：真实 PG 上召回顺序与分数 = 公式 | `MemoryControllerIT.recallOrderAndScoresFollowTheFormula` | **通过**。可控时钟下 60 天前写入的记忆 decay=0.25 排到新记忆后；每条 score 与测试独立计算的 `max(余弦, pg_trgm) × importance × decay` 差 < 1e-5 |
| 两路召回互相独立 | `JdbcMemoryRepositoryIT.textChannelReturnsRowsTheVectorLimitWouldCut` | **通过**。构造「向量近、三元组 0」干扰项，limit=1 时目标只能经三元组一路进入 |
| 归档 / 人工删除 / 参数校验 / 指纹 | `MemoryControllerIT` 其余 5 例、`MemoryServiceTest`（6）、`TaskFingerprintServiceTest`（3）、`JdbcTaskFingerprintRepositoryIT`（1） | **通过** |
| 全仓回归 | `mvn verify`（不带过滤） | **通过**。8 模块 BUILD SUCCESS，**450 测试 0 失败 0 错误 0 跳过**（common 6；gateway 1；integration 127 + 28 IT；review 174 + 51 IT；memory 37 + 17 IT；rule / admin 各 1）。真实 LLM/GitHub IT 均实跑：RealPullRequestReviewIT 45s、CrossSessionMapReduceIT 70s、MultiAgentIntegrationIT 11s、ReviewAgentIT 11s |

S6 评审（固定点 `e94223e`，标准轴 / 规格轴两个独立子代理）：标准轴 0 硬违规；规格轴 2 严重
（HNSW 带 project 过滤拿不满候选、`Math.max(NaN, x)` 吞另一路），连同 10 余条判断题已回 S5 修复，明细见 `docs/plans/M8.md` S6 节。

遗留问题 / 技术债：
- `DELETE /internal/memories/{id}` 不按 project 限定；全部 internal 端点无鉴权（同 M7a 缺口）
- `minScore` 默认 0：向量一路总返回最近 N 条，低分无关记忆也会回来，M9 注入 prompt 前要定阈值
- Embedding 是本地特征哈希，不懂同义改写；维度写死在 V1，换模型要新迁移重算
- 两路召回都是精确扫描（单项目数万条后需 `hnsw.iterative_scan` / GIN + `set local` 阈值）
- 指纹逐条 insert（单批 ≤ 1000）；归档无定时任务（手动端点）
- L1 的 ChangeMap / ChunkSummary 复用、Redis 层、L3 画像未做；review 侧未接 memory

与 ARCHITECTURE.md 的偏差：**已同步更新**。2 技术栈（Embedding 暂未接、pgvector + pg_trgm）；7.1 补 M8 落地范围、存储、端点；
7.3 补候选、similarity 融合、decay、排序、遗忘、Embedding 的具体取法；8 加三条决策。

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
| 2026-10-23 | M8 | 本地特征哈希 embedding，不接 Spring AI Embedding | 中转端点 `/v1/models` 只有 Claude 系列，无 embedding 模型（实测）；端口隔离，换模型只动适配器 + 列维度 |
| 2026-10-23 | M8 | 「全文检索」用 pg_trgm `similarity() > 0`，不用 tsvector、不建 GIN | 默认分词切不开中文；`%` 默认阈值 0.3 滤掉中文近义句（实测 0.19） |
| 2026-10-23 | M8 | 向量一路不建 HNSW，按 project 过滤后精确扫描 | 全表 HNSW 带过滤先取全局 ef_search=40 近邻再过滤，小项目拿不满候选（S6 双轴） |
| 2026-10-23 | M8 | 召回不刷新 `reinforced_at`，衰减只从写入（强化）算起 | 读不改状态，排序可复现；M9 反馈强化走写入路径 |
| 2026-10-23 | M8 | L1 只做已发布指纹（PG），ChangeMap/ChunkSummary 复用与 Redis 留 M10 | 没有 M10 的调用方就无从验证；指纹要比 Redis 更持久 |
| 2026-10-23 | M9 | 拆成 M9a（反馈进来）/ M9b（过滤生效） | 跨三个服务，一个会话做不完；M9a 有独立验证点 |
| 2026-10-23 | M9a | 反馈靠轮询 + 手动端点，不用 webhook | GitHub 无 reaction webhook，本机 8081 外网不可达（判定门确认） |
| 2026-10-23 | M9a | 只学有写权限者的反馈，可配白名单 | 公开仓库外人 👎 可压掉真缺陷（S6 标准轴） |
| 2026-10-23 | M9a | 同一人既回复又 👎 只学回复；合并评论只有 👎 不学；DELETE 只删 COMMON_DEFECT | T6 第一轮实测学反了且删掉了正确规则（用户确认修法） |
| 2026-10-24 | M12 | `callSite` 固定值从 spec 原定的 6 个扩展到 7 个，补 `FINDING_FILTER` | T1 执行中发现 `FindingFilter.java`（M9b 否决判定）也是 `LlmClient` 的生产调用方，spec 原文漏列；它和其余 6 个一样真实消耗 token，排除会让指标系统性漏记 |
| 2026-10-24 | M12 | `StubLlmClient` 给 `FINDING_FILTER` 加专门分支返回 `{"filtered": false}`，不落 default 分支 | 落 default 分支会导致 `FindingFilter` 在压测场景每次走"LLM 判定失败→放行"的降级路径，1 次调用就放行，比真实评审类调用耗时更短，会让压测测出的端到端延迟比真实生产场景偏低 |
| 2026-10-24 | M12 | Grafana dashboard JSON 文件路径从 `deploy/init/grafana/dashboards/` 改到 `deploy/init/grafana/provisioning/dashboards/files/` | Grafana 11.3.1 镜像不允许在一个已经整体只读挂载的目录内部再挂第二个挂载点；挪进 provisioning 目录树本身可以只用一个 volume |
| 2026-10-24 | M12 | brief/spec 要求的"浏览器打开核实面板渲染"用 Grafana REST API 查询等价替代 | Lightpanda（本项目浏览器联调规范指定的工具）没有图形渲染引擎，不能做视觉核实；API 查询 dashboard 结构 + 实跑 PromQL 确认查询链路通，是这个限制下能做到的最强验证 |
| 2026-10-24 | M12 | 压测跑完后发现两处指标缺口（`StubLlmClient` 未打点、`MicrometerMetricsRecorder` 未开 histogram），裁决回 S5 补齐，不接受"压测本身跑通就算完成" | ROADMAP M12 的核心验证点是"Grafana 可见指标"，两处缺口导致该验证点实际未达成；比照 WORKFLOW.md 验证门"跳过的测试不算通过"的精神，指标可见性不是锦上添花 |
| 2026-10-24 | M12 | `LoadTestRunner` 改用数据库 `updated_at` 列记录任务完成时刻，不用 `Instant.now()` | 串行轮询下用"查询时刻"记录完成时间，会让延迟数据退化成"真实完成时刻的滚动最大值"，几乎没有真实离散度，是测量方法的伪影不是系统真实表现（整条分支最终审查发现，实测重算证明差异达 5 秒量级） |

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
