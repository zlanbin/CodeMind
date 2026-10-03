# 开发进度（会话交接文件）

> 每个会话开始时先读本文件；结束前必须更新本文件。
> 只记录事实：做了什么、怎么验证的、结果如何。未验证的内容标注「未验证」。

## 当前状态

- 当前里程碑：M0 已完成，下一个是 M1
- 上次更新：2026-10-03
- 阻塞项：无

## 下一个会话要做的事

开始 M1：Webhook 接入（验签、幂等、发 `PullRequestReceived` 事件）。

1. 在 `codemind-common` 的 `event` 包定义 `PullRequestReceived`，实现 `DomainEvent`
2. integration-service 新增 `POST /webhook/github`：
   - 校验 `X-Hub-Signature-256`（HMAC-SHA256，密钥从环境变量 `GITHUB_WEBHOOK_SECRET` 读取），失败返回 401
   - 只处理 `pull_request` 事件的 `opened` / `synchronize` 动作，其余直接 200 忽略
   - 幂等：Redis key `webhook:{deliveryId}` + 后续 M2 建表时加 `(repo, pr_number, head_sha)` 唯一索引
   - 发送 `PullRequestReceived` 到 RocketMQ
3. review-service 加 MQ Listener，收到事件后建 `ReviewTask`（M1 只落库到 `CREATED` 状态，不做评审）
4. integration 需要新增依赖：`spring-boot-starter-data-redis`、`rocketmq-spring-boot-starter`（版本由 SCA BOM 管理）
5. 验证点：同一 webhook 重放 2 次只生成 1 个 ReviewTask；签名错误返回 401

注意：M1 要引入 Redis 和 RocketMQ 连接，`application.yml` 里补对应配置，容器已经起好了。

## 已完成里程碑

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

## 环境与常用命令

已确认的环境：
- JDK 26.0.1、Maven 3.9.16、Docker 29.8.1、Docker Compose v5.5.1、Git 2.54
- Maven Central 不可达，但 Maven 全局 `settings.xml` 已配阿里云镜像，pom 中无需额外配置
- Docker 已配国内镜像加速，所需镜像均已拉到本地
- 版本锁定见 `docs/PLAN.md` 第 5 节

常用命令（详见 `README.md`）：

```bash
cd deploy && docker compose up -d                              # 起基础设施
mvn verify                                                     # 构建 + 测试
java -jar codemind-<module>/target/codemind-<module>-0.1.0-SNAPSHOT.jar

curl http://127.0.0.1:8082/actuator/health                     # 直连
curl http://127.0.0.1:8080/codemind-review/actuator/health     # 经网关
curl -H "codemind: codemind" "http://127.0.0.1:8848/nacos/v3/admin/ns/service/list?pageNo=1&pageSize=20"
```

Windows 下停服务（本环境无 pkill）：

```bash
netstat -ano | grep ":8082 .*LISTENING"    # 查 PID
taskkill //F //PID <pid>
```

配置位置：各服务 `src/main/resources/application.yml`。Nacos 地址通过 `NACOS_ADDR` 覆盖。密钥一律用环境变量，不写入仓库。
