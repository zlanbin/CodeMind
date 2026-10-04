# 开发进度（会话交接文件）

> 每个会话开始时先读本文件；结束前必须更新本文件。
> 只记录事实：做了什么、怎么验证的、结果如何。未验证的内容标注「未验证」。

## 当前状态

- 当前里程碑：M2 已完成，下一个是 M3
- 上次更新：2026-10-04
- 阻塞项：无

## 下一个会话要做的事

开始 M3：DAG 引擎（拓扑调度、并行、超时重试、动态扇出、checkpoint），review-service domain + infra。

1. domain 层新增 DAG 抽象：`Node` 接口（`execute(NodeContext)`、超时、重试策略）、拓扑排序调度器、
   虚拟线程并行执行入度为 0 的节点、动态扇出（`PlanChunks` 节点运行时按 chunk 数产出下游子图）
2. checkpoint：节点完成后结果写 `node_run` 表，失败后从断点续跑，已完成节点不重复调用；
   需追加 `V3__create_node_run.sql`
3. M2 产出的 `SemanticChunker` 会作为 `PlanChunks` 节点的核心逻辑接入：chunker 已就绪，
   M3 只需把它包进一个 Node，并用 `ReviewTask.assignChunks(...)` 把结果挂回聚合
4. `ReviewTaskStatus` 的 `updated_at` 到 M3 才有真正的更新路径（M1 遗留项）；状态流转
   `CREATED → PLANNING → REVIEWING ...` 在 DAG 推进时落库
5. 验证点（ROADMAP M3）：单测——注入节点失败，后续节点仍跑、已完成节点不重复执行；
   并行节点总耗时≈最慢节点

注意：M3 若有 checkpoint 落库的 IT，需先 `cd deploy && docker compose up -d`；纯调度逻辑用 `*Test`。

M2 可直接复用的东西：
- 切片入口 `SemanticChunker.chunk(List<DiffFile>, Map<path,newSource>, ChunkBudget)`，
  源码（new 侧）由调用方（integration 拉文件内容）提供，domain 不自己读文件
- `ChunkBudget.ofDefault()` = 128k×30% diff 预算；`ofDiffBudget(n)` 直接给 diff 上限（测试友好）
- `review_chunk` 表已建（V2），但**持久化接线（JdbcChunkRepository）留到 M3**，M2 没写

## 已完成里程碑

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
