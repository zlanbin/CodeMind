# CodeMind 智能代码评审平台 - 架构设计

> 本文件是项目架构的真值文件。设计变更必须先改这里，再改代码。

## 1. 项目定位

PR 提交后自动触发多 Agent 并行评审，按代码规范、安全、性能、测试四个维度给出行级评论。系统记住团队规范和开发者否决过的意见，越用越准。

### 1.1 范围

做：
- Webhook 接入、diff 解析、AST 语义切片
- 自研 DAG 工作流编排多 Agent 并行评审
- 大 PR 跨会话 Map-Reduce 评审
- 行级评论回写 + 总结报告
- 项目级长期记忆 + 反馈学习闭环
- 增量评审（同一 PR 多次 push 只审新增）

不做：
- 通用拖拽画布（DAG 用代码/YAML 定义）
- 自动修复 PR（README 中列为扩展项）
- 多租户计费
- 模型训练

### 1.2 假设

- 单人开发，周期 8~10 周
- 代码托管：GitHub（GitLab 作为扩展，接口层预留不实现）
- 评审语言：优先 Java（AST 切片依赖 JavaParser），其他语言按 hunk 切片降级

## 2. 技术栈

| 类别 | 选型 | 说明 |
|---|---|---|
| 基础 | Java 21（release 21）、Spring Boot 3.5.16、Spring Cloud 2025.0.3、Spring Cloud Alibaba 2025.0.0.0 | 虚拟线程用于 DAG 并行 |
| 注册/配置 | Nacos 3.0.x | |
| 网关 | Spring Cloud Gateway | |
| AI | Spring AI 1.1.8（OpenAI 兼容协议，可接 DeepSeek/通义） | 结构化输出。Embedding 暂未接（中转端点无 embedding 模型），memory 用本地特征哈希，见 7.3 |
| 文本检索 | pgvector 0.8（余弦）+ pg_trgm（三元组相似度） | memory 两路召回，见 7.3 |
| 数据库 | PostgreSQL 16 + pgvector | 关系与向量同库 |
| 缓存 | Redis 7 | 任务记忆、幂等键、限流 |
| MQ | RocketMQ 5.x | 领域事件 |
| 代码解析 | JavaParser 3.28.2（AST 方法边界 / 签名）、自写 `DiffParser`（unified diff） | java-diff-utils 实测解析真实 GitHub diff 不可靠，M2 起改自写，见 PROGRESS 决策日志 |
| Token 估算 | jtokkit 1.1.0（`cl100k_base`） | 真实 BPE 分词，非字符数启发式 |
| 可观测 | Micrometer + Prometheus + Grafana | Token 成本、节点耗时 |
| 部署 | Docker Compose | |

版本已锁定，选型理由见 `docs/PLAN.md` 第 5 节；M0 后以根 `pom.xml` 为准。

## 3. 微服务与限界上下文

| 服务 | 端口 | 限界上下文 | 职责 |
|---|---|---|---|
| `gateway` | 8080 | — | 路由、鉴权、限流 |
| `integration-service` | 8081 | 仓库接入域 | Webhook 验签/幂等、Git API 拉 diff、回写评论 |
| `review-service` | 8082 | 评审编排域（核心） | 评审任务生命周期、DAG 引擎、Agent 执行、聚合 |
| `memory-service` | 8083 | 记忆域（核心） | 分层记忆读写、混合检索、冲突消解、遗忘 |
| `rule-service` | 8084 | 规则知识域 | 团队规范、规则模板、规范文档 RAG |
| `admin-service` | 8085 | 报表域 | 项目配置、评审统计、Token 成本看板 |

### 3.1 工程结构

```
pingshen/
├─ pom.xml                       # 父 POM，统一版本
├─ codemind-common/              # 通用：统一响应、异常、事件定义、工具
├─ codemind-gateway/
├─ codemind-integration/
├─ codemind-review/
├─ codemind-memory/
├─ codemind-rule/
├─ codemind-admin/
├─ deploy/docker-compose.yml     # PG、Redis、Nacos、RocketMQ、Prometheus、Grafana
└─ docs/
```

### 3.2 服务内 DDD 分层

```
com.codemind.<service>
├─ interfaces/      # Controller、MQ Listener、DTO、Assembler
├─ application/     # 应用服务：编排用例、事务边界、发布事件
├─ domain/          # 聚合、实体、值对象、领域服务、仓储接口（不依赖 Spring）
└─ infrastructure/  # 仓储实现、Feign 客户端、MQ 生产者、LLM 适配器
```

依赖方向：interfaces → application → domain ← infrastructure。

### 3.3 服务间通信

同步（OpenFeign）：review → memory（召回记忆）、review → rule（查规则）、review → integration（拉 diff 与文件内容）

review → integration 已落地（M7a）：
- integration 是唯一持有 `GITHUB_TOKEN`、唯一直连 GitHub 的服务（`GithubScmClient`，Spring `RestClient`，
  只读：`pulls/{n}` 取 `application/vnd.github.v3.diff`，`contents/{path}?ref=` 取 `application/vnd.github.raw`）
- 经两个 internal 端点对内暴露：`GET /internal/pulls/diff`、`POST /internal/pulls/contents`（批量，单文件失败不拖垮整批）。
  GitHub 错误 → 502。**端点无鉴权。** 现状有缺口：网关开着 discovery locator（按服务名自动建路由），
  `/codemind-integration/internal/**` 经网关**可达**，等于把「用 integration 的 token 读仓库」开放给能访问网关的人。
  网关暴露到本机之外前必须封掉（显式路由排除 `/internal/**` 或服务间鉴权），已记入 PROGRESS 技术债
- review 侧 domain 定义出站端口 `ScmContentPort`，infrastructure 用 `@FeignClient(name = "codemind-integration")`
  按服务名经 Nacos 发现。两侧 DTO 各自定义、不跨模块共享

异步（RocketMQ 领域事件）：

| 事件 | 生产者 → 消费者 | 用途 |
|---|---|---|
| `PullRequestReceived` | integration → review | 创建评审任务 |
| `ReviewCompleted` | review → integration | 回写评论（M7b，topic `codemind-review-completed`） |
| `ReviewPublished` | integration → review | 回写成功回执，review 推进 `PUBLISHED`（M7b，topic `codemind-review-published`） |
| `FindingFeedbackReceived` | integration → memory | 反馈学习（M9a，topic `codemind-finding-feedback`，见 7.2） |
| `ReviewCompleted` | review → memory | 沉淀任务记忆 |

事件定义统一放在 `codemind-common` 的 `event` 包。`ReviewCompleted` 携带已渲染好的总结正文与
`List<LineComment(path, line>0, body)>`，不引用 review 的领域对象；integration 只负责发送，不再调 LLM。

回写链路（M7b，运维见 `docs/operations/M7b-publication.md`）：

- review 侧 **transactional outbox**（`review_publication_outbox`，V5）：评审到 `AGGREGATING` 后，锁 task 行、
  同事务登记请求；后台 dispatcher（30s 扫描、批量 50、租约 120s、未 ACK 每 60s 重投同一 eventId）在事务外发 MQ。
  收到 `ReviewPublished` 后，同事务把 task 推进 `PUBLISHED` 并 ACK outbox，锁序固定 task → outbox
- integration 侧**发送账本**（独立 schema `integration.review_publication`，自带 Flyway 历史，M7b 起 integration 依赖 PG）：
  `READY → IN_FLIGHT → PUBLISHED`，结果不明时 `UNCERTAIN`。**POST 前先 CAS 落 `IN_FLIGHT`**；每次先按隐藏标记
  `<!-- codemind:task=<id> -->` 分页查已有 review（>10 页拒绝判定），查到只补回执。`IN_FLIGHT/UNCERTAIN` 查不到标记也
  **不自动重发**，只能人工核查后重置。明确拒绝（4xx）回 `READY`；`INLINE` 遇 422 先持久化 `SUMMARY_ONLY` 再发一次
- Redis 续租锁 `codemind:publish-lock:<taskId>`（TTL 120s / 30s 续租）只降并发，正确性靠账本 CAS
- 写端口 `ScmReviewPublisher`（application/port）与读端口共用同一个 `GithubScmClient` 实例：
  `POST pulls/{n}/reviews`（`event=COMMENT`、`side=RIGHT`、`commit_id` 必须是 40 位完整 SHA）
- 保证边界：至少一次投递 + 标记查重，**不承诺端到端 exactly-once**。GitHub 不允许 token 用户评审自己开的 PR（422），
  发布账号须与 PR 作者不同

## 4. 核心领域模型（review-service）

```
ReviewTask (聚合根)
 ├─ taskId, repo, prNumber, headSha, baseSha, status
 ├─ ChangeMap (值对象)            # 全局变更地图（含 AST 确定性提取的 List<SignatureChange>）
 ├─ List<ReviewChunk>             # 切片，每片对应一个 LLM 子会话（规划阶段产物）
 │    └─ chunkId, files, changedSymbols, diffContent, tokenEstimate, oversized
 ├─ Map<chunkId, ChunkSummary>    # 各片结构化摘要（评审阶段产物，按 chunkId 关联，见 6.4）
 ├─ List<Finding>                 # 片级去重后 + GlobalReduce 的跨文件意见
 │    └─ file, line, severity, category, message, suggestion, fingerprint
 └─ ReviewReport (值对象)          # M7b：inline 组 + summary 项 + 降级信息
      └─ List<InlineComment>(path, line, 合并的 Findings), List<SummaryItem>(Finding, Reason)
```

`ReviewReportBuilder`（纯领域）按 `ReportPolicy`（默认 MAJOR 门槛、最多 10 组，`CODEMIND_MIN_INLINE_SEVERITY` /
`CODEMIND_MAX_INLINE`）把 Findings 分流：同文件同行合并成一组，门槛按组最高严重度判。进总结的都记 `Reason`：
`NO_LINE`（`line=0`）、`OUTSIDE_DIFF`（行不在新侧 hunk 内、文件不在 diff 或已删除）、`BELOW_THRESHOLD`、`OVER_LIMIT`。
排序稳定，同一输入产出同一报告。markdown 渲染在 application 层。

方法限定名格式：`外层类名#方法名(参数类型, ...)`，如 `OrderService#cancel(int)`。带参数类型是 M6 的
必要条件——否则无法区分重载，也无法判断跨文件签名是否一致。不含包名、不消解嵌套类同名歧义。

状态机：`CREATED → PLANNING → REVIEWING → AGGREGATING → PUBLISHED`，任一阶段可转 `FAILED`。
恢复走 `FAILED → PLANNING` 重跑整条流程，「回到原阶段」靠 DAG checkpoint 实现：`node_run` 里已完成的节点
直接取结果，不重复调 LLM（M7a 实现；状态本身不记原阶段）。

`chunkId` 只在任务内唯一（`SemanticChunker` 每个任务从 `chunk-0` 编号），`review_chunk` 主键为 `(task_id, chunk_id)`（V4）。

`fingerprint = hash(file + category + normalize(codeSnippet))`，用于去重和增量评审判重。

幂等：`(repo, prNumber, headSha)` 唯一索引 + Redis 幂等键。

## 5. 评审工作流（DAG）

```
BuildChangeMap → PlanChunks
                      │
      ┌───────────────┼───────────────┐   按 chunk 动态 fan-out
      ▼               ▼               ▼
 RecallContext   RecallContext   RecallContext   召回调用链代码 + 记忆 + 规则（留 M8/M11）
      │
     ┌────┬───┴┬────┐                            按维度并行，各注入 ChangeMap
   Style Security Perf Test
     └────┴───┬┴────┘
          ChunkReduce  → 去重 Findings + 合并 ChunkSummary
              └───────────────┬───────────────┘
                         GlobalReduce   只读 ChangeMap + 各片摘要，不读原始 diff
                              │
                     Dedup & Filter（记忆过滤，留 M9）
                              │
                     Report → Outbox → Publish（M7b，DAG 外，见 3.3）
```

M6 已落地：`BuildChangeMap → PlanChunks → 4N 个子会话 → N 个 ChunkReduce → GlobalReduce`，
状态随之推进 `PLANNING → REVIEWING → AGGREGATING`（`PUBLISHED` 的入口是回写 PR，M7b 落地）。
`BuildChangeMap` 排在 `PlanChunks` 之前（原图为之后）：两者无数据依赖（地图要的是全量文件列表与签名，
与切片结果无关），但地图先行可在任何子会话启动前就落进共享状态。diff 解析不单列节点，
由两个节点各自调用 `DiffParser`（纯函数，无副作用）。

M7a 接上入口链路（此前 DAG 只在测试里被调用）：`PullRequestReceivedListener` 建完任务后
`ReviewExecutionService.submitAsync(taskId)` 投给虚拟线程池（MQ 消费线程不被 LLM 阻塞，评审失败不触发重投）→
`executeReview`：只接 `CREATED` / `FAILED` 状态 → 经 `ScmContentPort` 拉 diff 与 head 侧 Java 源码
（跳过删除文件、重命名用 newPath、非 Java 不拉）→ `executeCrossSessionReview`。任何异常落 `FAILED`。

卡死恢复（M7a）：进程中途死掉的任务停在 `PLANNING / REVIEWING / AGGREGATING`。`StaleTaskRecoveryService`
定时扫「`updated_at` 与该任务最新 `node_run` 活动**两者都**早于阈值（默认 15m）」的任务，单条 SQL 原子抢占
（置 `FAILED`，多实例只有一个抢到）后重提。默认关（`CODEMIND_RECOVERY_ENABLED`）。判定是启发式的，
真慢的任务会被重启一次，代价由 checkpoint 兜住；更准的节点级心跳未做。
M7b 起 `AGGREGATING` 不再等于「还在跑」：**已有 outbox 的任务已完成交接**，`findStaleInProgress` 与
`claimForRecovery` 都带 `NOT EXISTS outbox` 条件，执行服务的失败回退也只走 `markFailedIfUnpublished`，
不会把已交接或已发布的任务抢回 `FAILED`。空 diff 早退路径同样推进到 `AGGREGATING` 再交接。

Checkpoint 序列化：节点结果以 `Object` 存取，用开了 default typing（`EVERYTHING`，record 是 final 类）的
**私有** `ObjectMapper` 把具体类型写进 JSON，类型白名单只放 `com.codemind.` 与 `java.util.`。
它**不能**注册成 Spring bean，否则 Boot 的 Jackson 自动配置退让、它成为全局 mapper。只序列化 record 组件，
派生的 `isXxx()` 不进 JSON。

### 5.1 DAG 引擎范围

做：
- `Node` 接口：`execute(NodeContext)`，配置超时、重试策略
- 拓扑排序调度，虚拟线程并行执行入度为 0 的节点
- 动态扇出：节点运行时产出下游子图（PlanChunks 决定 chunk 数）
- Checkpoint：节点完成后结果写 `node_run` 表，失败后从断点续跑，已完成节点不重复调用 LLM

不做：循环节点、可视化编辑、跨服务分布式调度。

## 6. 跨会话评审设计（核心亮点）

### 6.1 问题

大 PR（如 40 文件 / 3000 行）加上下文代码、规则、记忆后远超单次上下文窗口；即使塞得进，长上下文下评审质量也会下降。

### 6.2 分层 Map-Reduce

1. **全局变更地图**（1 个轻量会话）：只输入文件列表、方法签名变更、diff 统计，输出 `ChangeMap`（PR 意图、模块划分、高风险文件）。之后每个子会话都注入它。
   方法签名变更由 AST + diff 删除行**确定性**提取（`ChangedSignatureExtractor`），不依赖 LLM；
   因此 LLM 部分降级时，`ChangeMap` 的签名变更依然完整。
2. **语义切片**（纯算法，不调 LLM）：文件 → hunk → 方法，按 AST 边界切，不截断方法；有调用关系的变更合进同一 chunk；按 Token 预算装箱。
   「合进同一 chunk」是**软约束**：聚合体整体超预算时沿合并缝拆回原子单元（见 6.3）。
3. **子会话评审**（N 个并行会话）：输出 Findings + 结构化 `ChunkSummary`（同一次 LLM 调用产出）。
   摘要解析失败只降级摘要，不连带丢弃同次已解析成功的 Findings。
4. **全局归约**：只读 `ChangeMap` + 所有 ChunkSummary（**不读原始 diff 与源码**），回答跨 chunk 问题，
   发现跨文件问题，输出与 chunk 级同结构的 Finding（category 以 `CORRECTNESS` 为主），
   由编排器直接追加到任务级 Findings，不额外加 MergeFindings 节点；摘要过多时树形递归归约（留 M7，M6 只做一层）。
5. **增量与续跑**：再次 push 只对新 diff 切片，旧摘要从任务记忆复用，Finding 按 fingerprint 去重；失败从 checkpoint 续跑。

### 6.3 Token 预算（单个子会话）

| 部分 | 占比 |
|---|---|
| 系统提示 + 输出格式 | 10% |
| ChangeMap | 5% |
| 规则 + 记忆 | 15% |
| 召回上下文代码 | 25% |
| diff 本体 | 30% |
| 输出预留 | 15% |

超预算降级顺序：裁剪上下文代码 → 压缩记忆条数 → 拆分 chunk。

拆分 chunk 的边界（M6 落地）：拆的是**调用关系聚合**，不是方法。聚合单元超 diff 预算时沿合并缝
拆回原子单元（一个原子单元 = 一个完整方法的全部 hunk，或非 Java / 解析失败时的单个 hunk）；
单个原子单元自身超预算时整片输出并标 `oversized`，**不截断、不丢弃**——「方法不被截断」是硬不变量，
排在预算之前。被拆开的调用关系由 GlobalReduce 跨片兜（它只读摘要，正为此准备）。

前两步（裁剪上下文代码、压缩记忆）依赖 RecallContext 与记忆召回，分别留给 M8 之后的里程碑；
M6 只实现了第三步。

### 6.4 ChunkSummary 结构

```json
{
  "changedSymbols": ["OrderService#cancel"],
  "intent": "取消订单时增加库存回滚",
  "risks": ["回滚未在事务内"],
  "crossChunkQuestions": ["InventoryClient#rollback 是否幂等？"]
}
```

`crossChunkQuestions`：子会话把自己看不到的问题显式上交给 GlobalReduce。约束为「本片调用或被调用的
接口定义在别的文件、本片看不到」这类问题，不做万能兜底（否则 GlobalReduce 负担过重且信噪比下降）。

`changedSymbols` 必须带参数类型（`OrderService#cancel(Long)`），否则 GlobalReduce 无从判断调用方与
定义方是否对得上。两重保障：prompt 明确要求，且 `ChangeMap` 里有 AST 确定性提取的签名可交叉校验。

完整性校验：`ChunkReduceNode` 合并同一 chunk 各维度的摘要（符号 / 风险 / 跨片问题取并集，
intent 取首个非空），并校验 `changedSymbols` 与 `intent` 非空；不完整则记入该片的降级信息——
否则 GlobalReduce 会因摘要为空而静默失效。

`ChunkSummary` 按 `chunkId` 关联在 `ReviewTask` 上（而非塞进 `ReviewChunk` 值对象）：切片是规划阶段
产物、摘要是评审阶段产物，分开存不必为挂摘要重建切片，也不动 `review_chunk` 表结构。

## 7. 智能体记忆（memory-service）

### 7.1 分层

| 层 | 内容 | 存储 | 生命周期 |
|---|---|---|---|
| L0 工作记忆 | 当前子会话 prompt 组装结果 | 内存 | 单次 LLM 调用 |
| L1 任务记忆 | ChangeMap、ChunkSummary、已发布 Finding 指纹 | Redis + PG | 单个 PR 生命周期 |
| L2 项目长期记忆 | 团队规范、被否决的意见模式、常见缺陷 | PgVector | 长期，带衰减 |
| L3 开发者画像 | 常犯错误、偏好的评审风格 | PG | 长期 |

M8 落地范围（计划 `docs/plans/M8.md`）：L2 写入 / 混合召回 / 归档 / 人工删除；L1 只落了**已发布指纹**
（PG `memory.task_fingerprint`，按 `(repo, pr_number)`，M10 增量去重用）。ChangeMap / ChunkSummary 的跨 push 复用
与 Redis 层留 M10，L3 未做。review 侧 RecallContext 接线与 Dedup & Filter 留 M9。

存储：独立 schema `memory`（Flyway `db/memory-migration`，同 integration 做法）。L2 表 `memory.memory`：
`(project, kind, content_hash)` 唯一，`kind ∈ {CONVENTION, REJECTED_PATTERN, COMMON_DEFECT}`，`status ∈ {ACTIVE, ARCHIVED}`，
`embedding vector(256)`。同内容再次写入 = 强化：importance 取较大者、`reinforced_at` 刷新、ARCHIVED 复活。

对内端点（**无鉴权**，与 3.3 的 integration internal 端点同一缺口）：`POST /internal/memories`、
`POST /internal/memories/recall`、`DELETE /internal/memories/{id}`、`POST /internal/memories/archive?project=`、
`POST|GET /internal/task-fingerprints`。

### 7.2 反馈学习闭环

1. 开发者在评论下回复 👎 或「不适用，因为…」
2. integration 发出 `FindingFeedbackReceived`
3. memory 用 LLM 抽取否决记忆（例：「本项目允许 Controller 直接返回实体」）
4. 与已有记忆比对，执行 ADD / UPDATE / DELETE 冲突消解
5. 下次评审在 Dedup & Filter 节点过滤同类意见

M9a 落地（1~4 步，计划 `docs/plans/M9.md`；第 5 步留 M9b）：

- **对回 Finding**：review 渲染行级评论时，每条合并进来的 Finding 追加隐藏标记
  `<!-- codemind:finding fp=<sha256> cat=<CATEGORY> -->`，格式与解析在 `codemind-common` 的 `FindingMarker`，写读两侧共用。
  M9 前发布的评论、SUMMARY_ONLY 降级发布的评论没有行级标记，收不到反馈
- **拿反馈**：GitHub 没有 reaction webhook，integration 定时（默认 5m）轮询账本里 14 天内 `PUBLISHED` 的 review：
  `pulls/{n}/comments` 取属于该 review 且带标记的评论，再取每条的 👎（`reactions?content=-1`）和对它的回复。
  另有手动入口 `POST /internal/feedback/poll?repo=&prNumber=`（只读 GitHub、发 MQ）
- **谁的反馈算数**：只收对仓库有写权限的账号（`collaborators/{login}/permission` 为 admin/maintain/write），
  可配白名单 `codemind.feedback.trusted-logins`；查不到权限按不可信。公开仓库外人 👎 不能压掉真缺陷
- **取舍**：同一人对同一条评论既 👎 又回复，只学回复（👎 记 `SUPERSEDED`）；合并了多条意见的评论只有 👎，
  memory 记 `AMBIGUOUS` 不学——猜不出否决的是哪条，T6 实测猜错时连真缺陷一起学成否决
- **可靠投递**：integration 账本 `integration.finding_feedback`（V3），`(repo, source_type, source_id)` 唯一，
  `PENDING → SENT`，坏 payload 或失败 10 次转 `FAILED`；重投用同一 eventId。memory 用 `memory.feedback_event`（V2）
  按 eventId 只学一次，事件登记是写记忆事务的第一步
- **抽取**：memory 用 LLM 把反馈写成「本项目允许 / 不要求……」的否决规则（方向必须与原意见相反），
  维度只能取自评论里出现的；回复只为它点名的维度写规则。LLM 不可用时 👎 降级为模板（outcome 带 `DEGRADED:`），
  回复抛出等 MQ 重投
- **冲突消解**：召回相关记忆（≤5 条、score ≥ 0.05）交给 LLM 选 ADD / UPDATE / DELETE / NOOP，硬约束：
  NOOP / UPDATE 只能落在 REJECTED_PATTERN；DELETE 只能删 COMMON_DEFECT；违反一律退回 ADD。
  结果里总有一条带 `[维度]` 前缀的 REJECTED_PATTERN。importance：👎 0.8，带理由的回复 0.9
- 已知限制：撤销 👎 / 删除回复不回收已学记忆；memory 消费重试耗尽进死信后反馈丢失（integration 已标 SENT）

### 7.3 检索与遗忘

- 召回：向量召回 + PG 全文检索混合
- 打分：`score = similarity × importance × decay(Δt)`，取 top-k 注入 prompt
- 遗忘：score 长期低于阈值的记忆归档；支持人工删除错误记忆

M8 的具体取法：

- 候选：本 project 的 ACTIVE 记忆中，向量余弦最近的 N 条 ∪ pg_trgm `similarity()` 最高的 N 条（`N = max(topK, 50)`），
  两路相似度都带回。两路都是按 `(project, status)` 过滤后的**精确扫描**：不建 HNSW（全表近似索引带过滤会拿不满 N），
  不用 `%`/GIN（默认阈值 0.3 会滤掉中文近义句）。「全文检索」用三元组而非 `tsvector`：默认分词切不开中文
- `similarity = max(clamp(向量余弦), clamp(三元组))`，clamp 到 [0,1]、NaN 按 0
- `importance ∈ (0, 1]` 由写入方给，缺省 0.5；`decay(Δt) = 0.5^(Δt / 半衰期)`，半衰期默认 30 天，
  Δt 从 `reinforced_at` 算起，**召回不刷新它**（读不改状态，排序可复现）
- 排序：score 降序 → importance 降序 → id 升序；过滤 `score ≥ minScore`（默认 0），取 top-k（≤ 50）。
  打分与排序是 domain 纯函数 `MemoryRanker`，返回值带 similarity / decay / score 分项
- 遗忘：`retention = importance × decay(Δt)` 与查询无关，低于阈值（默认 0.05）转 ARCHIVED。手动触发，无定时任务
- Embedding：domain 端口 `Embedder`，当前实现 `HashingEmbedder`（字符 bigram + ASCII 词特征哈希，256 维，确定性）。
  中转端点没有 embedding 模型；它只认字面重叠，不懂同义改写。换真实模型 = 换适配器 + 新迁移改列维度并重算

## 8. 设计决策记录

| 决策 | 理由 |
|---|---|
| 自研 DAG 而非 Flowable / LangGraph4j | 需要动态扇出和 LLM 节点级 checkpoint 语义，自研范围可控 |
| PG + pgvector 而非独立向量库 | 少一个组件，单人可运维，数据量级足够 |
| 服务控制在 5+1 个 | 单人可完成，边界讲得清 |
| GlobalReduce 只读摘要 | 控制上下文规模，摘要质量通过标注 PR 集合评估 |
| 只有 integration 直连 GitHub，review 经 internal 端点取内容 | GitHub Token 只在一个服务里；review 不感知 SCM 平台，换 GitLab 只动 integration |
| 回写走 review outbox + integration 独立 schema 账本，结果不明只核查不重发（M7b） | 外部写入做错要手工清理：宁可停在 `UNCERTAIN` 等人工，也不重复发评论。两个服务各管自己的表，不跨服务事务 |
| 报告规则与渲染在 review，integration 只发送 | 噪声控制（合并、门槛、上限）是评审语义；换 SCM 平台不用重做 |
| 记忆打分与排序放 domain 纯函数，SQL 只取候选（M8） | 「排序符合公式」可单测直证；换存储或索引不动公式 |
| 记忆召回用三元组代替 PG 全文分词、精确扫描代替近似索引（M8） | 中文分词不可用；HNSW 带项目过滤会漏候选。单项目数万条后再上 `hnsw.iterative_scan` / GIN |
| memory 先用本地特征哈希 embedding（M8） | 现有中转端点无 embedding 模型；端口隔离，换模型只动适配器与列维度 |
| 反馈靠轮询而非 webhook，只学有写权限者的反馈（M9a） | GitHub 没有 reaction webhook；公开仓库任何人都能 👎 |
| 冲突消解的 LLM 决定外加硬约束（M9a） | LLM 会把两条否决当矛盾互删、把 NOOP 落到缺陷上；硬约束保证结果里总有否决记忆、不误删规范 |
