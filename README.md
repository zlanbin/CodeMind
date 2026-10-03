# CodeMind 智能代码评审平台

PR 提交后自动触发多 Agent 并行评审，按代码规范、安全、性能、测试四个维度给出行级评论。系统记住团队规范和开发者否决过的意见，越用越准。

方案见 [docs/PLAN.md](docs/PLAN.md)，架构见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)，进度见 [docs/PROGRESS.md](docs/PROGRESS.md)。

## 环境要求

JDK 21+、Maven 3.9+、Docker。

## 启动

```bash
# 1. 基础设施（PostgreSQL+pgvector、Redis、Nacos、RocketMQ）
cd deploy && docker compose up -d

# 2. 构建
mvn verify

# 3. 启动服务
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
mvn verify
curl http://127.0.0.1:8082/actuator/health
curl http://127.0.0.1:8080/codemind-review/actuator/health
```

## 说明

本地开发的 Nacos 关闭了鉴权，仅适用于开发环境。LLM API Key、GitHub Token 等密钥通过环境变量注入，不写入仓库。
