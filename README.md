# CodeMind 智能代码评审平台

PR 提交后自动触发多 Agent 并行评审，按代码规范、安全、性能、测试四个维度给出行级评论。系统记住团队规范和开发者否决过的意见，越用越准。

方案见 [docs/PLAN.md](docs/PLAN.md)，架构见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)，进度见 [docs/PROGRESS.md](docs/PROGRESS.md)。

## 环境要求

JDK 21+、Maven 3.9+、Docker。

## 启动

```bash
# 1. 基础设施（PostgreSQL+pgvector、Redis、Nacos、RocketMQ）
cd deploy && docker compose up -d

# 2. 构建（集成测试跑在上面这些真实中间件上，所以要先起容器）
mvn verify
# 只跑不依赖中间件的单测：mvn test

# 3. 启动服务（integration 必须有 GITHUB_WEBHOOK_SECRET，否则启动失败）
export GITHUB_WEBHOOK_SECRET=<你的 webhook secret>

java -jar codemind-gateway/target/codemind-gateway-0.1.0-SNAPSHOT.jar
java -jar codemind-integration/target/codemind-integration-0.1.0-SNAPSHOT.jar
java -jar codemind-review/target/codemind-review-0.1.0-SNAPSHOT.jar
java -jar codemind-memory/target/codemind-memory-0.1.0-SNAPSHOT.jar
java -jar codemind-rule/target/codemind-rule-0.1.0-SNAPSHOT.jar
java -jar codemind-admin/target/codemind-admin-0.1.0-SNAPSHOT.jar
```

## 端口

| 服务 | 端口 | 说明 |
|---|---|---|
| gateway | 8080 | 按服务名路由，如 `/codemind-review/**` |
| integration | 8081 | 仓库接入域 |
| review | 8082 | 评审编排域（核心） |
| memory | 8083 | 记忆域（核心） |
| rule | 8084 | 规则知识域 |
| admin | 8085 | 报表域 |
| Nacos | 8848 | 控制台 http://127.0.0.1:8848/nacos |
| PostgreSQL | 5432 | 库 `codemind`，用户 `codemind` |
| Redis | 6379 | |
| RocketMQ | 9876 | NameServer；Proxy 在 18080 |

## 验证

```bash
mvn verify      # 需先 docker compose up -d
curl http://127.0.0.1:8082/actuator/health
curl http://127.0.0.1:8080/codemind-review/actuator/health
```

发一个带正确签名的 webhook，确认评审任务落库：

```bash
BODY='{"action":"opened","repository":{"full_name":"codemind/demo"},"pull_request":{"number":1,"head":{"sha":"h1"},"base":{"sha":"b1"}}}'
SIG="sha256=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$GITHUB_WEBHOOK_SECRET" | awk '{print $2}')"
curl -s -X POST http://127.0.0.1:8081/webhook/github -H "Content-Type: application/json" \
  -H "X-GitHub-Event: pull_request" -H "X-GitHub-Delivery: $(date +%s)" \
  -H "X-Hub-Signature-256: $SIG" -d "$BODY"

docker exec codemind-postgres psql -U codemind -d codemind \
  -c "select repo, pr_number, head_sha, status from review_task;"
```

签名错误返回 401；同一 `X-GitHub-Delivery` 重放只会生成一个评审任务。新建 topic 首次投递需等约 20s
（consumer 路由刷新），之后在 4s 内落库。

## 说明

本地开发的 Nacos 关闭了鉴权，仅适用于开发环境。LLM API Key、GitHub Token、Webhook Secret 等密钥通过环境变量注入，不写入仓库。
