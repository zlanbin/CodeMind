# CodeMind 智能代码评审平台

PR 提交后自动触发多 Agent 并行评审，按代码规范、安全、性能、测试四个维度给出行级评论。系统记住团队规范和开发者否决过的意见，越用越准。

方案见 [docs/PLAN.md](docs/PLAN.md)，架构见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)，进度见 [docs/PROGRESS.md](docs/PROGRESS.md)。
全链路演示脚本见 [docs/demo/WALKTHROUGH.md](docs/demo/WALKTHROUGH.md)。

## 核心能力

- **多 Agent 并行评审**：diff 解析 + AST 语义切片后，STYLE / SECURITY / PERFORMANCE / TEST
  四个维度的 Agent 并行跑在自研 DAG 引擎上，同一问题被多个维度命中按 fingerprint 去重
- **跨会话 Map-Reduce**：大 PR 超出单次 LLM 上下文时，按调用关系切片分发到多个子会话，
  产出结构化摘要后做全局归约，能发现跨文件的签名不一致问题
- **行级回写**：评审完成后带着「判定理由 + 隐藏追溯标记」回写到 PR，总结与行级评论分流
- **反馈学习闭环**：开发者对某条意见 👎 并说明理由后，同类意见后续评审不再出现，经 LLM
  抽取 + 冲突消解写入项目级长期记忆
- **增量评审**：同一 PR 第二次 push 时只评审新增 diff，不重复发评论
- **团队规范 RAG**：上传 Markdown 规范文档后，评审意见能引用具体条款（如 `《团队规范》3.2.1`）
- **可观测**：Token 成本按调用场景、DAG 节点耗时按类型分组，Prometheus + Grafana 可查

## 验证点与测试规模

各里程碑的验证点与达成情况见 [docs/PROGRESS.md](docs/PROGRESS.md#已完成里程碑)（M0~M13 全部
完成，逐条记录了验证命令与真实结果，不含跳过项）。

| 维度 | 数字 |
|---|---|
| 里程碑完成度 | M0~M13 / 13（全部完成） |
| 微服务数 | 6（gateway、integration、review、memory、rule、admin） |
| 评审维度 | 4（STYLE / SECURITY / PERFORMANCE / TEST）+ 1 个跨文件全局归约 |
| 真实验收覆盖 | 真实 GitHub PR 回写、真实 👎 反馈学习、真实增量评审、真实规则召回、真实压测（10 并发 × 30 任务，0 失败）均在 sandbox 仓库跑过，非纯 mock；M13 在全新 clone 的干净目录里完整复测过一遍全链路 |

## 环境要求

JDK 21+、Maven 3.9+、Docker。

## 启动

```bash
# 1. 基础设施（PostgreSQL+pgvector、Redis、Nacos、RocketMQ）
cd deploy && docker compose up -d

# 2. 构建（集成测试跑在上面这些真实中间件上，所以要先起容器）
mvn verify
# 只跑不依赖中间件的单测：mvn test

# 3. 启动服务（integration 必须有 GITHUB_WEBHOOK_SECRET，否则启动失败；
#    review 要真实评审（而非降级）需要 CODEMIND_LLM_API_KEY；
#    integration 要回写 PR / 拉真实 diff 需要 GITHUB_TOKEN，否则走匿名低限流）
export GITHUB_WEBHOOK_SECRET=<你的 webhook secret>
export GITHUB_TOKEN=<你的 GitHub token，pull_requests: write>
export CODEMIND_LLM_API_KEY=<你的 LLM key>

java -jar codemind-gateway/target/codemind-gateway-0.1.0-SNAPSHOT.jar
java -jar codemind-integration/target/codemind-integration-0.1.0-SNAPSHOT.jar
java -jar codemind-review/target/codemind-review-0.1.0-SNAPSHOT.jar
java -jar codemind-memory/target/codemind-memory-0.1.0-SNAPSHOT.jar
java -jar codemind-rule/target/codemind-rule-0.1.0-SNAPSHOT.jar
java -jar codemind-admin/target/codemind-admin-0.1.0-SNAPSHOT.jar
```

也可以直接用 [docs/demo/](docs/demo/) 下的脚本完成以上两步并做健康检查，见下方「全链路功能演示」。

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

### 回写 PR（M7b 起）

评审完成后 review 经 outbox + MQ 交给 integration，由它向 PR 提交总结与行级评论，成功后任务进入 `PUBLISHED`。

- integration 启动起依赖 PostgreSQL，发送账本在独立 schema `integration`
- 发布用的 `GITHUB_TOKEN` 需要 `pull_requests: write`，且账号不能是 PR 作者（GitHub 对自评 review 返回 422）
- 行级评论门槛与上限：`CODEMIND_MIN_INLINE_SEVERITY`（默认 `MAJOR`）、`CODEMIND_MAX_INLINE`（默认 10）

普通 `mvn verify` 不写真实 GitHub。真实写验收用 `-Pgithub-write` 分 prepare / publish 两阶段跑，
命令、状态查询和卡住时的处理见 [docs/operations/M7b-publication.md](docs/operations/M7b-publication.md)。

## 全链路功能演示（M8~M12）

反馈学习闭环、增量评审、团队规范 RAG、可观测指标这几项（M8~M12）涉及多服务协作和真实
GitHub 往返，不适合塞进上面的快速验证。完整的分步脚本 + 操作说明见
[docs/demo/WALKTHROUGH.md](docs/demo/WALKTHROUGH.md)，从起基础设施到核实 Grafana 指标
一步步走完，标注了哪些步骤是只读本地操作、哪些步骤会真实写入 GitHub 需要人工确认。

## 说明

本地开发的 Nacos 关闭了鉴权，仅适用于开发环境。LLM API Key、GitHub Token、Webhook Secret 等密钥通过环境变量注入，不写入仓库。
