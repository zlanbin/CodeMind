# M7a 入口链路实施计划

> **给执行者：** 按 `docs/WORKFLOW.md` S5 选执行 skill。步骤用 `- [ ]` 跟踪。

**目标：** 让一个真实 GitHub PR 能自动走完「拉 diff → 拉文件源码 → 跨会话评审 → 状态到 `AGGREGATING` → 产出 Finding」，把 M3~M6 攒下的「DAG 从未接线」这笔债还掉。

**架构：** integration 侧新增 GitHub REST 客户端（`RestClient`），经两个 internal 端点对内暴露「拉整份 diff」与「批量拉文件新侧源码」；review 侧 domain 定义 `ScmContentPort` 出站端口，infrastructure 用 OpenFeign 适配到 integration；新增 `ReviewExecutionService` 把「取内容 → 建 `NodeContext` → 调 `DagOrchestrator.executeCrossSessionReview`」串起来，由 `PullRequestReceivedListener` 建完任务后异步触发。

**技术栈：** Spring Boot 3.5.16、Spring Cloud 2025.0.3（OpenFeign）、Spring `RestClient`、JDK 21 virtual threads。

**规格：** `docs/ROADMAP.md`（M7 行）、`docs/ARCHITECTURE.md` 3.3 / 4 / 5、`docs/PROGRESS.md`「下一个会话要做的事」第 1~2 项。M7 已拆 M7a（本计划，入口链路）/ M7b（回写 PR + `ReviewReport` + `PUBLISHED`）。

## 全局约束

- 基础包名 `com.codemind.<service>`，分层 `interfaces / application / domain / infrastructure`，依赖方向 `interfaces → application → domain ← infrastructure`
- domain 层不依赖 Spring 和任何基础设施。新增端口是纯接口，实现落 infrastructure，装配写 `@Configuration`
- `GITHUB_TOKEN` 只经环境变量注入，不写入仓库、不进日志、不进异常 message
- 测试命名：`*Test` 走 surefire（纯逻辑，`mvn test` 可跑），`*IT` 走 failsafe（依赖真实中间件或真实外部服务）
- 真实外部调用的 IT 用 `assumeTrue` gate（沿用 M4/M6 惯例）。被跳过的 IT 不算验证通过
- 行级评论、`ReviewReport`、`PUBLISHED` 状态、回写幂等表、噪声阈值**全部属 M7b**，本计划不碰
- 本计划只做 GitHub **读**调用，不往 PR 写任何东西

## 评审重点

规格暗示但没有任务天然覆盖的输入，按「最可能咬到人」排序，每条已挂到对应任务：

1. **PR 里没有 Java 文件**（只改 `.md` / `.yml`）：`newSources` 为空 Map，`SemanticChunker` 应降级为 hunk 切片正常出片，不能抛异常 —— 任务 5
2. **纯删除文件**：`DiffFile.changeType == DELETED` 时新侧不存在，绝不能去拉它的内容（GitHub 返 404 会让整个评审失败）—— 任务 5
3. **重命名文件**：必须用 `newPath` 拉内容，用 `oldPath` 会 404 —— 任务 5
4. **diff 超 GitHub 上限**：`application/vnd.github.v3.diff` 对超大 PR 返 406，必须给出可读错误而非 NPE —— 任务 1
5. **token 无效或仓库无权限**：GitHub 返 401/403，任务应落到 `FAILED` 并记明原因，不能静默挂住停在 `CREATED` —— 任务 5

---

### Task 1: integration 侧 GitHub 读客户端

**文件：**
- 新建 `codemind-integration/src/main/java/com/codemind/integration/application/port/ScmClient.java`
- 新建 `codemind-integration/src/main/java/com/codemind/integration/application/port/ScmException.java`
- 新建 `codemind-integration/src/main/java/com/codemind/integration/infrastructure/github/GithubScmClient.java`
- 改 `codemind-integration/src/main/java/com/codemind/integration/infrastructure/config/WebhookConfig.java`（加 `RestClient` bean 与 `GithubScmClient` 装配；若更合适可另建 `infrastructure/config/GithubConfig.java`）
- 改 `codemind-integration/src/main/resources/application.yml`
- 测试 `codemind-integration/src/test/java/com/codemind/integration/infrastructure/github/GithubScmClientTest.java`

**接口：**
- 产出：`ScmClient.fetchPullRequestDiff(String repo, int prNumber) -> String`（整份 unified diff）；
  `ScmClient.fetchFileContent(String repo, String path, String ref) -> String`（新侧完整源码）；
  `ScmException extends RuntimeException`，带 `int statusCode`（0 表示非 HTTP 错误）与可读 message

**配置（`application.yml` 的 `codemind.scm` 下）：** `github-token: ${GITHUB_TOKEN:}`、`github-api-base-url: ${GITHUB_API_BASE_URL:https://api.github.com}`、`request-timeout: 20s`

- [ ] **步骤 1：写失败的测试**

`GithubScmClientTest`，用 `MockRestServiceServer` 绑到 `RestClient.Builder`（`MockRestServiceServer.bindTo(builder)`），5 个用例：

```java
@Test void fetchDiffSendsDiffAcceptHeaderAndBearerToken()
// 期望 GET /repos/owner/repo/pulls/7，Accept: application/vnd.github.v3.diff，
// Authorization: Bearer <token>，X-GitHub-Api-Version: 2022-11-28；返回体原样返回

@Test void fetchFileContentSendsRawAcceptHeaderAndRefQuery()
// 期望 GET /repos/owner/repo/contents/src/A.java?ref=abc123，Accept: application/vnd.github.raw

@Test void notFoundBecomesScmExceptionWith404()
// 404 → ScmException，statusCode==404，message 含 repo 与 path

@Test void oversizedDiffBecomesScmExceptionWith406()
// 406 → ScmException，statusCode==406，message 说明 diff 过大（评审重点 4）

@Test void tokenNeverAppearsInExceptionMessage()
// 401 → ScmException 的 message 与 toString 都不含 token 原文（全局约束：密钥不进异常）
```

- [ ] **步骤 2：跑测试确认失败**

Run: `mvn test -pl codemind-integration -Dtest=GithubScmClientTest`
Expected: FAIL，`ScmClient` / `GithubScmClient` 不存在，编译错误

- [ ] **步骤 3：实现 `ScmClient`、`ScmException`、`GithubScmClient`**

`GithubScmClient` 构造器接 `RestClient.Builder`、base-url、token、timeout。token 为空串时照常发请求（公开仓库只读可匿名），但要 `log.warn` 一次。用 `RestClient` 的 `onStatus(HttpStatusCode::isError, ...)` 把错误状态转成 `ScmException`。

- [ ] **步骤 4：跑测试确认通过**

Run: `mvn test -pl codemind-integration -Dtest=GithubScmClientTest`
Expected: PASS，5 个用例全绿

- [ ] **步骤 5：提交**

```bash
git add codemind-integration/src/main/java/com/codemind/integration/application/port codemind-integration/src/main/java/com/codemind/integration/infrastructure/github codemind-integration/src/main/java/com/codemind/integration/infrastructure/config codemind-integration/src/main/resources/application.yml codemind-integration/src/test/java/com/codemind/integration/infrastructure/github/GithubScmClientTest.java
git commit -m "M7a: integration 侧 GitHub 读客户端（拉 diff 与文件源码）"
```

---

### Task 2: internal 端点对内暴露 PR 内容

**文件：**
- 新建 `codemind-integration/src/main/java/com/codemind/integration/application/PullRequestContentService.java`
- 新建 `codemind-integration/src/main/java/com/codemind/integration/interfaces/web/PullRequestContentController.java`
- 新建 `codemind-integration/src/main/java/com/codemind/integration/interfaces/web/dto/FileContentsRequest.java`
- 新建 `codemind-integration/src/main/java/com/codemind/integration/interfaces/web/dto/FileContentsResponse.java`
- 改 `codemind-integration/src/main/java/com/codemind/integration/interfaces/web/WebhookExceptionHandler.java`（`ScmException` → 502，保留 M1 的 401/400 行为不变）
- 测试 `codemind-integration/src/test/java/com/codemind/integration/application/PullRequestContentServiceTest.java`

**接口：**
- 消费：任务 1 的 `ScmClient`
- 产出：`PullRequestContentService.diff(String repo, int prNumber) -> String`；
  `PullRequestContentService.fileContents(String repo, String ref, List<String> paths) -> Map<String, String>`（逐个调 `ScmClient`，**单个文件失败不拖垮整批**：跳过并记 warn，返回的 Map 里就没这个 key）；
  端点 `GET /internal/pulls/diff?repo={repo}&prNumber={n}` 返回 `text/plain`；
  `POST /internal/pulls/contents` 收 `FileContentsRequest(String repo, String ref, List<String> paths)` 返回 `FileContentsResponse(Map<String, String> contents)`

- [ ] **步骤 1：写失败的测试**

`PullRequestContentServiceTest`，stub `ScmClient`，3 个用例：

```java
@Test void fetchesEachRequestedPathAtGivenRef()
// 3 个 path → ScmClient 被调 3 次，ref 透传；返回 Map 含 3 条

@Test void singleFileFailureDoesNotFailWholeBatch()
// 中间那个 path 抛 ScmException(404) → 返回 Map 含另 2 条，不抛异常

@Test void emptyPathListSkipsScmEntirely()
// paths 为空 → ScmClient 零调用，返回空 Map（评审重点 1 的下游保证）
```

- [ ] **步骤 2：跑测试确认失败**

Run: `mvn test -pl codemind-integration -Dtest=PullRequestContentServiceTest`
Expected: FAIL，`PullRequestContentService` 不存在

- [ ] **步骤 3：实现服务、两个 DTO、控制器，并在 `WebhookExceptionHandler` 加 `ScmException` → 502**

控制器只做参数转发与 DTO 包装，不含业务判断。两个 DTO 用 record。

- [ ] **步骤 4：跑测试确认通过**

Run: `mvn test -pl codemind-integration -Dtest=PullRequestContentServiceTest`
Expected: PASS，3 个用例全绿

- [ ] **步骤 5：提交**

```bash
git add codemind-integration/src/main/java/com/codemind/integration codemind-integration/src/test/java/com/codemind/integration/application/PullRequestContentServiceTest.java
git commit -m "M7a: internal 端点对内暴露 PR diff 与文件源码"
```

---

### Task 3: review 侧 ScmContentPort 与 Feign 适配

**文件：**
- 新建 `codemind-review/src/main/java/com/codemind/review/domain/scm/ScmContentPort.java`
- 新建 `codemind-review/src/main/java/com/codemind/review/domain/scm/ScmContentException.java`
- 新建 `codemind-review/src/main/java/com/codemind/review/infrastructure/scm/IntegrationScmFeignClient.java`
- 新建 `codemind-review/src/main/java/com/codemind/review/infrastructure/scm/FeignScmContentAdapter.java`
- 改 `codemind-review/pom.xml`（加 `spring-cloud-starter-openfeign`，版本由根 POM 的 Spring Cloud BOM 管，不自行锁版本）
- 改 `codemind-review/src/main/java/com/codemind/review/ReviewApplication.java`（加 `@EnableFeignClients`）
- 测试 `codemind-review/src/test/java/com/codemind/review/infrastructure/scm/FeignScmContentAdapterTest.java`

**接口：**
- 产出：`ScmContentPort.fetchDiff(String repo, int prNumber) -> String`；
  `ScmContentPort.fetchNewSources(String repo, String ref, List<String> paths) -> Map<String, String>`；
  `ScmContentException extends RuntimeException`（domain 层异常，不泄漏 Feign 类型）
- 消费：任务 2 的两个 internal 端点。Feign 客户端 `@FeignClient(name = "codemind-integration")`，按服务名经 Nacos 发现

- [ ] **步骤 1：写失败的测试**

`FeignScmContentAdapterTest`，stub `IntegrationScmFeignClient`，2 个用例：

```java
@Test void mapsFeignResponseToPathToSourceMap()
// Feign 返回 review 侧自己的响应 record（Map.of("A.java", "src")）→ 适配器返回同内容 Map

@Test void feignFailureBecomesScmContentException()
// Feign 抛 RuntimeException → 适配器抛 ScmContentException，cause 保留原异常
```

- [ ] **步骤 2：跑测试确认失败**

Run: `mvn test -pl codemind-review -Dtest=FeignScmContentAdapterTest`
Expected: FAIL，`ScmContentPort` / 适配器不存在

- [ ] **步骤 3：实现端口、异常、Feign 客户端接口与适配器**

Feign 客户端的请求/响应 DTO 在 review 的 `infrastructure/scm` 下各自定义（record），**不跨模块共享 DTO** —— 两个服务独立演进，共享 DTO 会把 integration 的接口变更直接传染给 review 的 domain。

- [ ] **步骤 4：跑测试确认通过**

Run: `mvn test -pl codemind-review -Dtest=FeignScmContentAdapterTest`
Expected: PASS，2 个用例全绿

- [ ] **步骤 5：提交**

```bash
git add codemind-review/pom.xml codemind-review/src/main/java/com/codemind/review/domain/scm codemind-review/src/main/java/com/codemind/review/infrastructure/scm codemind-review/src/main/java/com/codemind/review/ReviewApplication.java codemind-review/src/test/java/com/codemind/review/infrastructure/scm
git commit -m "M7a: review 侧 ScmContentPort 与 Feign 适配到 integration"
```

---

### Task 4: `ReviewTaskRepository.findById`

**文件：**
- 改 `codemind-review/src/main/java/com/codemind/review/domain/task/ReviewTaskRepository.java`
- 改 `codemind-review/src/main/java/com/codemind/review/infrastructure/persistence/JdbcReviewTaskRepository.java`
- 测试 `codemind-review/src/test/java/com/codemind/review/infrastructure/persistence/ReviewTaskRepositoryIT.java`（加用例）

**接口：**
- 产出：`ReviewTaskRepository.findById(String taskId) -> Optional<ReviewTask>`。异步执行时只有 taskId 在手，必须能按它重建聚合

实现复用已有的 `mapRow`，`taskId` 入参要 `UUID.fromString` 转换（与 `updateStatus` 一致）。

- [ ] **步骤 1：写失败的测试**

`ReviewTaskRepositoryIT` 加 2 个用例：

```java
@Test void findByIdReturnsRehydratedTask()
// insertIfAbsent 后按 taskId 查回：repo / prNumber / headSha / baseSha / status 与写入一致

@Test void findByIdReturnsEmptyForUnknownId()
// 随机 UUID → Optional.empty()，不抛异常
```

- [ ] **步骤 2：跑测试确认失败**

Run: `mvn verify -pl codemind-review -Dit.test=ReviewTaskRepositoryIT`
Expected: FAIL，`findById` 未定义（编译错误）

- [ ] **步骤 3：在端口加 `findById(String taskId)`，在 `JdbcReviewTaskRepository` 实现**

- [ ] **步骤 4：跑测试确认通过**

Run: `mvn verify -pl codemind-review -Dit.test=ReviewTaskRepositoryIT`
Expected: PASS。前置：PG 已起（`netstat` 探 5432），宿主机原生 `postgresql-x64-16` 须先停

- [ ] **步骤 5：提交**

```bash
git add codemind-review/src/main/java/com/codemind/review/domain/task/ReviewTaskRepository.java codemind-review/src/main/java/com/codemind/review/infrastructure/persistence/JdbcReviewTaskRepository.java codemind-review/src/test/java/com/codemind/review/infrastructure/persistence/ReviewTaskRepositoryIT.java
git commit -m "M7a: ReviewTaskRepository 补 findById"
```

---

### Task 5: `ReviewExecutionService` 串入口链路

**文件：**
- 新建 `codemind-review/src/main/java/com/codemind/review/application/ReviewExecutionService.java`
- 测试 `codemind-review/src/test/java/com/codemind/review/application/ReviewExecutionServiceTest.java`

**接口：**
- 消费：任务 3 的 `ScmContentPort`、任务 4 的 `findById`、已有的 `DagOrchestrator.executeCrossSessionReview(Node, Node, ReviewTask, NodeContext)`、已有的 `BuildChangeMapNode(String, ChangeMapAgent)` 与 `PlanChunksNode(String, SemanticChunker, ChunkRepository, ReviewTaskRepository, ChunkBudget)`
- 产出：`ReviewExecutionService.executeReview(String taskId) -> CrossSessionReviewResult`（同步，IT 可直接调）；
  `ReviewExecutionService.sourcePathsOf(List<DiffFile> files) -> List<String>`（包私有静态，纯函数，单测直打）

执行流程：`findById` → 状态不是 `CREATED` 或 `FAILED` 则记日志直接返回 null（防 MQ 重投重跑）→ `fetchDiff` → `new DiffParser().parse(diffText)` → `sourcePathsOf` → `fetchNewSources(repo, headSha, paths)` → `NodeContext.create(taskId).withSharedState("diffText", ...).withSharedState("newSources", ...)` → `executeCrossSessionReview`。任何异常：`task.transitionTo(FAILED)` + `updateStatus` + 原异常包进 `IllegalStateException` 抛出（评审重点 5）。

`sourcePathsOf` 规则：只保留 `isJava()` 且 `changeType != DELETED` 的文件，取 `newPath`（评审重点 2、3）。

- [ ] **步骤 1：写失败的测试**

`ReviewExecutionServiceTest`。`DagOrchestrator` 是 final 类，用真实实例 + 本测试内自建的 stub（`ScriptedLlmClient` 风格的桩 `LlmClient`、内存版 `ChunkRepository` / `NodeRunRepository` / `ReviewTaskRepository`；`CrossSessionMapReduceTest` 里的同名桩是私有嵌套类，不可复用，照其写法重建）。7 个用例：

```java
@Test void skipsJavaDeletedFilesWhenCollectingSourcePaths()
// DiffFile(DELETED, "Gone.java") 不在 sourcePathsOf 结果里（评审重点 2）

@Test void usesNewPathForRenamedFiles()
// DiffFile(RENAMED, oldPath="Old.java", newPath="New.java") → 结果只含 "New.java"（评审重点 3）

@Test void ignoresNonJavaFiles()
// README.md / config.yml 不在结果里

@Test void pullRequestWithoutJavaFilesStillProducesChunks()
// 只改 .md 的 diff → fetchNewSources 收到空 paths，评审仍跑完、chunkResults 非空（评审重点 1）

@Test void fetchesSourcesAtHeadSha()
// 断言传给 ScmContentPort 的 ref 等于 task.headSha()，不是 baseSha

@Test void alreadyRunningTaskIsSkipped()
// 任务状态为 REVIEWING → 返回 null，ScmContentPort 零调用

@Test void scmFailureMarksTaskFailed()
// fetchDiff 抛 ScmContentException → 抛 IllegalStateException，
// 且 taskRepository 收到 updateStatus(taskId, FAILED)（评审重点 5）
```

- [ ] **步骤 2：跑测试确认失败**

Run: `mvn test -pl codemind-review -Dtest=ReviewExecutionServiceTest`
Expected: FAIL，`ReviewExecutionService` 不存在

- [ ] **步骤 3：实现 `ReviewExecutionService`**

构造器注入 `ScmContentPort`、`ReviewTaskRepository`、`DagOrchestrator`、`ChangeMapAgent`、`SemanticChunker`、`ChunkRepository`、`ChunkBudget`。两个节点在 `executeReview` 内按 taskId 新建（节点持有 chunk 预算等每任务状态，不做成单例 bean）。

- [ ] **步骤 4：跑测试确认通过**

Run: `mvn test -pl codemind-review -Dtest=ReviewExecutionServiceTest`
Expected: PASS，7 个用例全绿

- [ ] **步骤 5：提交**

```bash
git add codemind-review/src/main/java/com/codemind/review/application/ReviewExecutionService.java codemind-review/src/test/java/com/codemind/review/application/ReviewExecutionServiceTest.java
git commit -m "M7a: ReviewExecutionService 串起取内容到跨会话评审"
```

---

### Task 6: Spring 装配与异步触发

**文件：**
- 新建 `codemind-review/src/main/java/com/codemind/review/infrastructure/config/ReviewDagConfig.java`
- 改 `codemind-review/src/main/java/com/codemind/review/interfaces/mq/PullRequestReceivedListener.java`
- 改 `codemind-review/src/main/resources/application.yml`
- 测试 `codemind-review/src/test/java/com/codemind/review/infrastructure/config/ReviewDagWiringIT.java`

**接口：**
- 产出：bean `DagScheduler`（超时与重试从配置读）、`SemanticChunker`、`ChunkBudget`、`DagOrchestrator`、`ExecutorService reviewExecutor`（`Executors.newVirtualThreadPerTaskExecutor()`）；
  `ReviewExecutionService.submitAsync(String taskId)`（提交给 `reviewExecutor`，异常只记日志不外抛 —— MQ 消费线程不能被 LLM 的几十秒阻塞，也不该因评审失败而重投）

**配置（`codemind.dag` 下）：** `node-timeout: ${CODEMIND_DAG_NODE_TIMEOUT:120s}`、`node-retries: ${CODEMIND_DAG_NODE_RETRIES:1}`、`diff-budget: ${CODEMIND_DIFF_BUDGET:0}`（0 表示用 `ChunkBudget.ofDefault()`）

- [ ] **步骤 1：写失败的测试**

`ReviewDagWiringIT`（`@SpringBootTest`，关 Nacos discovery、`spring.ai.openai.api-key=placeholder-no-key`，沿用 `CrossSessionMapReduceIT` 的属性写法）：

```java
@Test void contextLoadsWithAllDagBeans()
// 断言 DagOrchestrator / DagScheduler / ReviewExecutionService / ScmContentPort 四个 bean 都在
// （M5 曾因只改 Config 不验装配而首次 verify 失败，这个 IT 就是为此设的）
```

- [ ] **步骤 2：跑测试确认失败**

Run: `mvn verify -pl codemind-review -Dit.test=ReviewDagWiringIT`
Expected: FAIL，`DagOrchestrator` 无 bean 定义

- [ ] **步骤 3：实现 `ReviewDagConfig`，并在 `PullRequestReceivedListener` 里建完任务后调 `submitAsync`**

listener 只多一行 `submitAsync(task.taskId())`，判重逻辑在 `ReviewExecutionService` 里（任务 5 已实现），listener 不做状态判断。

- [ ] **步骤 4：跑测试确认通过**

Run: `mvn verify -pl codemind-review -Dit.test=ReviewDagWiringIT`
Expected: PASS

- [ ] **步骤 5：提交**

```bash
git add codemind-review/src/main/java/com/codemind/review/infrastructure/config codemind-review/src/main/java/com/codemind/review/interfaces/mq/PullRequestReceivedListener.java codemind-review/src/main/java/com/codemind/review/application/ReviewExecutionService.java codemind-review/src/main/resources/application.yml codemind-review/src/test/java/com/codemind/review/infrastructure/config/ReviewDagWiringIT.java
git commit -m "M7a: DAG 装配成 bean，建任务后异步触发评审"
```

---

### Task 7: 真实 PR 端到端验证

**文件：**
- 新建 `codemind-review/src/test/java/com/codemind/review/application/RealPullRequestReviewIT.java`

**接口：**
- 消费：任务 1~6 的全部产物。按 `CODEMIND_TEST_REPO` + `GITHUB_TOKEN` + `CODEMIND_LLM_API_KEY` 三者齐备才跑，否则 `assumeTrue` 跳过

**前置（人工）：** 新建专用测试仓库（如 `zlanbin/codemind-sandbox`），推一个改了 2~3 个 Java 文件的 PR，细粒度 token 只授该仓库 `pull_requests: write`（M7a 只读，M7b 要写）。环境变量 `CODEMIND_TEST_REPO=zlanbin/codemind-sandbox`、`CODEMIND_TEST_PR=<n>`。

- [ ] **步骤 1：写失败的测试**

```java
@Test void realPullRequestFlowsThroughToAggregating()
// 用真实 repo / prNumber / headSha 建 ReviewTask 并落库 → executeReview(taskId)
// 断言：① 真实拉到的 diff 非空且含 "diff --git"
//       ② chunkResults 非空
//       ③ task.status() == AGGREGATING
//       ④ task.findings() 非空（真实 LLM 至少给一条意见）
//       ⑤ 每条 Finding 的 file 都在 diff 的文件列表里（防 LLM 编造文件名 —— M7b 回写靠这个字段定位）
// 打印全部 Finding 的 file:line / severity / message，供人工核对行号是否落在 diff 范围内
```

headSha 从 `GET /repos/{repo}/pulls/{n}` 读，或人工填入环境变量 `CODEMIND_TEST_HEAD_SHA`。

- [ ] **步骤 2：跑测试确认失败（或跳过）**

Run: `mvn verify -pl codemind-review -Dit.test=RealPullRequestReviewIT`
Expected: 三个环境变量未齐时 skipped；齐备时 FAIL（代码未就绪）

- [ ] **步骤 3：补齐实现直到通过**

本任务不应需要新产品代码。若失败，按 `systematic-debugging` 找根因，不改症状。

- [ ] **步骤 4：跑全仓回归**

Run: `mvn verify`
Expected: 8 模块 BUILD SUCCESS。**逐条清点 skipped**：按验证门，被跳过的 IT 对应的验证点标「未验证」

- [ ] **步骤 5：提交**

```bash
git add codemind-review/src/test/java/com/codemind/review/application/RealPullRequestReviewIT.java
git commit -m "M7a: 真实 PR 端到端 IT（拉 diff 到 AGGREGATING）"
```

---

### Task 8: 卡死任务恢复驱动

进程在评审中途重启后，任务会停在 `PLANNING` / `REVIEWING` / `AGGREGATING` 永不推进。checkpoint 已在 `node_run` 表里、`DagScheduler` 本身支持续跑，缺的只是「谁来触发」。

**文件：**
- 新建 `codemind-review/src/main/java/com/codemind/review/application/StaleTaskRecoveryService.java`
- 新建 `codemind-review/src/main/java/com/codemind/review/infrastructure/config/RecoverySchedulingConfig.java`
- 改 `codemind-review/src/main/java/com/codemind/review/domain/task/ReviewTaskRepository.java`
- 改 `codemind-review/src/main/java/com/codemind/review/infrastructure/persistence/JdbcReviewTaskRepository.java`
- 改 `codemind-review/src/main/resources/application.yml`
- 测试 `codemind-review/src/test/java/com/codemind/review/application/StaleTaskRecoveryServiceTest.java`
- 测试 `codemind-review/src/test/java/com/codemind/review/infrastructure/persistence/StaleTaskRecoveryIT.java`

**接口：**
- 产出：`ReviewTaskRepository.findStaleInProgress(Duration idleFor) -> List<String>`（返回 taskId；
  条件：`status in (PLANNING, REVIEWING, AGGREGATING)` 且 `review_task.updated_at` 与该任务名下
  `node_run` 的 `max(coalesce(completed_at, started_at))` **两者都**早于 `now() - idleFor`；
  无 node_run 记录的任务只看 `updated_at`）；
  `ReviewTaskRepository.claimForRecovery(String taskId) -> boolean`（单条 SQL 原子抢占：
  `update review_task set status='FAILED', updated_at=now() where task_id=? and status in (...)`，
  按受影响行数返回，多实例同时扫只有一个抢到）；
  `StaleTaskRecoveryService.recoverStaleTasks() -> int`（返回重新提交的任务数，供测试直接调）
- 消费：任务 5 的 `ReviewExecutionService.submitAsync`

**为什么先置 `FAILED` 再重提：** `executeReview` 只接 `CREATED` / `FAILED` 两种状态（任务 5），而 `FAILED → PLANNING` 是状态机允许的流转（`ReviewTaskStatus.allowedNext`）。进程确实死了，标 `FAILED` 是事实陈述，不是变通。重跑时 `DagScheduler` 从 checkpoint 加载已完成节点，**不会重复调 LLM**。

**配置（`codemind.recovery` 下）：** `enabled: ${CODEMIND_RECOVERY_ENABLED:false}`、`idle-threshold: ${CODEMIND_RECOVERY_IDLE_THRESHOLD:15m}`、`scan-interval: ${CODEMIND_RECOVERY_SCAN_INTERVAL:5m}`

> 默认关：开着会让所有现有 IT 的上下文里多一个定时任务在扫库。恢复逻辑本身由直接调 `recoverStaleTasks()` 的测试覆盖，不依赖定时触发。真实演示与生产要设 `CODEMIND_RECOVERY_ENABLED=true`，M13 的演示脚本里要带上这一条。

- [ ] **步骤 1：写失败的测试**

`StaleTaskRecoveryServiceTest`（纯单测，stub 仓储），4 个用例：

```java
@Test void resubmitsEachClaimedStaleTask()
// 仓储返回 2 个 stale taskId，claimForRecovery 都成功 → submitAsync 被调 2 次，返回 2

@Test void skipsTaskLostToAnotherInstance()
// claimForRecovery 返回 false → 该任务不提交（多实例竞争下只跑一次），返回 0

@Test void returnsZeroWhenNothingStale()
// findStaleInProgress 返回空 → submitAsync 零调用

@Test void oneTaskFailureDoesNotStopTheRest()
// 第一个任务 submitAsync 抛异常 → 第二个仍被提交（一个坏任务不能卡住整轮扫描）
```

`StaleTaskRecoveryIT`（需真实 PG，验 SQL 的判定逻辑——这是本任务最容易写错的地方），4 个用例：

```java
@Test void findsTaskIdleBeyondThreshold()
// 插 REVIEWING 任务并把 updated_at 回拨 30 分钟，无 node_run → idleFor=15m 能查到

@Test void ignoresTaskWithRecentNodeActivity()
// 同样回拨 updated_at 30 分钟，但插一条 started_at=now() 的 RUNNING node_run
// → 查不到（正在跑的任务绝不能被判卡死，这是本任务的核心风险）

@Test void ignoresTerminalAndCreatedTasks()
// PUBLISHED / FAILED / CREATED 状态的任务即使很旧也查不到

@Test void claimForRecoverySucceedsOnlyOnce()
// 对同一 taskId 连调 claimForRecovery 两次 → 第一次 true、第二次 false，状态为 FAILED
```

- [ ] **步骤 2：跑测试确认失败**

Run: `mvn verify -pl codemind-review -Dtest=StaleTaskRecoveryServiceTest -Dit.test=StaleTaskRecoveryIT`
Expected: FAIL，`findStaleInProgress` / `StaleTaskRecoveryService` 不存在

- [ ] **步骤 3：实现两个仓储方法、`StaleTaskRecoveryService`、`RecoverySchedulingConfig`**

`RecoverySchedulingConfig` 用 `@ConditionalOnProperty(name = "codemind.recovery.enabled", havingValue = "true")` + `@EnableScheduling`，内含一个 `@Scheduled(fixedDelayString = "${codemind.recovery.scan-interval}")` 方法调 `recoverStaleTasks()`。服务本身**始终**是 bean（不带条件），测试才能直接调。

- [ ] **步骤 4：跑测试确认通过**

Run: `mvn verify -pl codemind-review -Dtest=StaleTaskRecoveryServiceTest -Dit.test=StaleTaskRecoveryIT`
Expected: PASS，4 单测 + 4 IT 全绿

- [ ] **步骤 5：提交**

```bash
git add codemind-review/src/main/java/com/codemind/review/application/StaleTaskRecoveryService.java codemind-review/src/main/java/com/codemind/review/infrastructure/config/RecoverySchedulingConfig.java codemind-review/src/main/java/com/codemind/review/domain/task/ReviewTaskRepository.java codemind-review/src/main/java/com/codemind/review/infrastructure/persistence/JdbcReviewTaskRepository.java codemind-review/src/main/resources/application.yml codemind-review/src/test/java/com/codemind/review/application/StaleTaskRecoveryServiceTest.java codemind-review/src/test/java/com/codemind/review/infrastructure/persistence/StaleTaskRecoveryIT.java
git commit -m "M7a: 卡死任务恢复驱动（扫表 + 原子抢占 + 从 checkpoint 续跑）"
```

---

## 验证点（M7a 独立验证点，ROADMAP 要求每个子项自带）

| 验证点 | 命令 | 判定 |
|---|---|---|
| 真实 PR 能自动拉到 diff 与文件源码 | `RealPullRequestReviewIT` 断言 ① | diff 含 `diff --git`，Java 文件源码非空 |
| 评审链路跑到 `AGGREGATING` 并产出 Finding | `RealPullRequestReviewIT` 断言 ③④ | 状态 `AGGREGATING`，`findings()` 非空 |
| Finding 的 file 落在真实 diff 文件列表内 | `RealPullRequestReviewIT` 断言 ⑤ | 无编造文件名（M7b 回写的前置） |
| 删除/重命名/非 Java 文件不误拉内容 | `ReviewExecutionServiceTest` 4 个用例 | 纯单测，不需中间件 |
| 新 bean 在真实容器装配正确 | `ReviewDagWiringIT` | 上下文加载成功 |
| 卡死任务能从 checkpoint 续跑，正在跑的不被误判 | `StaleTaskRecoveryIT` 4 个用例 + `StaleTaskRecoveryServiceTest` 4 个用例 | 回拨 `updated_at` 的任务被捞到；有新鲜 `node_run` 活动的任务不被捞 |
| 全仓回归 | `mvn verify` | 8 模块绿，skipped 逐条交代 |

## 已知不做（留 M7b 或更后）

- 回写 PR、`ReviewReport`、`PUBLISHED`、回写幂等表（V4）、噪声阈值 —— 全部 M7b
- 恢复驱动默认关（`CODEMIND_RECOVERY_ENABLED=false`），真实运行要显式开，M13 演示脚本需带上
- 恢复的判定是启发式的：15 分钟无任何节点活动即判卡死。真卡了 15 分钟的慢任务会被重启一次，
  但 checkpoint 保证已完成节点不重复调 LLM，代价可控。更准的做法是节点级心跳，M7a 不做
- 两条并发投递同时看到 `CREATED` 时可能双跑（`findById` 判状态不是原子的；恢复路径已用
  `claimForRecovery` 做成原子抢占，但首次触发路径没有）。M7b 的回写幂等表会兜住「重复回写」
  这个真正有外部副作用的后果
- 一个 PR N 个 Java 文件就是 N 次 `contents` 调用，未做并发与缓存
