# 开发进度（会话交接文件）

> 每个会话开始时先读本文件；结束前必须更新本文件。
> 只记录事实：做了什么、怎么验证的、结果如何。未验证的内容标注「未验证」。

## 当前状态

- 当前里程碑：M1 已完成，下一个是 M2
- 上次更新：2026-10-03
- 阻塞项：无

## 下一个会话要做的事

开始 M2：Diff 解析、AST 语义切片、Token 估算（纯 review-service domain 层，不碰 LLM）。

1. 引入 `java-diff-utils` 解析 unified diff，`JavaParser` 做 AST 切片；两个依赖的版本在根 `pom.xml` 的
   `dependencyManagement` 里锁死（SCA / Boot BOM 都不管它们，参考 M1 加 `rocketmq-spring-boot-starter` 的做法）
2. domain 层新增（纯 Java，不依赖 Spring）：
   - `DiffParser`：unified diff → 变更文件 + hunk + 行号映射
   - `SemanticChunker`：按 AST 方法边界切片，保证不截断方法
   - `TokenEstimator`：估算每片 token 数，单片不超预算（预算值见 ARCHITECTURE.md 6.3）
   - `ReviewChunk` 值对象，挂到 `ReviewTask` 聚合下
3. 准备样例 diff 测试资源放 `src/test/resources/diff/`，至少覆盖：单文件单方法、单文件多方法、
   跨文件、超大方法（单个方法就超预算的退化场景）
4. 验证点：单测断言切片结果符合预期、无方法被截断、每片不超预算
5. M1 遗留的建表事项：`review_task` 表和 `(repo, pr_number, head_sha)` 唯一索引已在 M1 建好
   （`V1__create_review_task.sql`），M2 只需追加 chunk 相关表的 `V2__*.sql`

注意：M2 的验证点是纯单测，不需要外部中间件；但 `mvn verify` 会跑 M1 的 IT，仍需先
`cd deploy && docker compose up -d`。

## 已完成里程碑

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
