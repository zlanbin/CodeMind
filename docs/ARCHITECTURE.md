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
| AI | Spring AI 1.1.8（OpenAI 兼容协议，可接 DeepSeek/通义） | 结构化输出、Embedding |
| 数据库 | PostgreSQL 16 + pgvector | 关系与向量同库 |
| 缓存 | Redis 7 | 任务记忆、幂等键、限流 |
| MQ | RocketMQ 5.x | 领域事件 |
| 代码解析 | JavaParser、java-diff-utils | |
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

同步（OpenFeign）：review → memory（召回记忆）、review → rule（查规则）、review → integration（拉文件内容）

异步（RocketMQ 领域事件）：

| 事件 | 生产者 → 消费者 | 用途 |
|---|---|---|
| `PullRequestReceived` | integration → review | 创建评审任务 |
| `ReviewCompleted` | review → integration | 回写评论 |
| `FindingFeedbackReceived` | integration → memory | 反馈学习 |
| `ReviewCompleted` | review → memory | 沉淀任务记忆 |

事件定义统一放在 `codemind-common` 的 `event` 包。

## 4. 核心领域模型（review-service）

```
ReviewTask (聚合根)
 ├─ taskId, repo, prNumber, headSha, baseSha, status
 ├─ ChangeMap (值对象)        # 全局变更地图
 ├─ List<ReviewChunk>         # 切片，每片对应一个 LLM 子会话
 │    └─ chunkId, files, tokenEstimate, ChunkSummary(值对象), status
 ├─ List<Finding>
 │    └─ file, line, severity, category, message, suggestion, fingerprint
 └─ ReviewReport (值对象)
```

状态机：`CREATED → PLANNING → REVIEWING → AGGREGATING → PUBLISHED`，任一阶段可转 `FAILED`，`FAILED` 可从 checkpoint 恢复回原阶段。

`fingerprint = hash(file + category + normalize(codeSnippet))`，用于去重和增量评审判重。

幂等：`(repo, prNumber, headSha)` 唯一索引 + Redis 幂等键。

## 5. 评审工作流（DAG）

```
ParseDiff → PlanChunks → BuildChangeMap
                              │
              ┌───────────────┼───────────────┐   按 chunk 动态 fan-out
              ▼               ▼               ▼
         RecallContext   RecallContext   RecallContext   召回调用链代码 + 记忆 + 规则
              │
     ┌────┬───┴┬────┐                                    按维度并行
   Style Security Perf Test
     └────┴───┬┴────┘
          ChunkReduce  → ChunkSummary + Findings
              └───────────────┬───────────────┘
                         GlobalReduce   只读摘要，不读原始代码
                              │
                     Dedup & Filter（记忆过滤）
                              │
                           Publish
```

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
2. **语义切片**（纯算法，不调 LLM）：文件 → hunk → 方法，按 AST 边界切，不截断方法；有调用关系的变更合进同一 chunk；按 Token 预算装箱。
3. **子会话评审**（N 个并行会话）：输出 Findings + 结构化 `ChunkSummary`。
4. **全局归约**：只读所有 ChunkSummary，回答跨 chunk 问题，发现跨文件问题；摘要过多时树形递归归约。
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

### 6.4 ChunkSummary 结构

```json
{
  "changedSymbols": ["OrderService#cancel"],
  "intent": "取消订单时增加库存回滚",
  "risks": ["回滚未在事务内"],
  "crossChunkQuestions": ["InventoryClient#rollback 是否幂等？"]
}
```

`crossChunkQuestions`：子会话把自己看不到的问题显式上交给 GlobalReduce。

## 7. 智能体记忆（memory-service）

### 7.1 分层

| 层 | 内容 | 存储 | 生命周期 |
|---|---|---|---|
| L0 工作记忆 | 当前子会话 prompt 组装结果 | 内存 | 单次 LLM 调用 |
| L1 任务记忆 | ChangeMap、ChunkSummary、已发布 Finding 指纹 | Redis + PG | 单个 PR 生命周期 |
| L2 项目长期记忆 | 团队规范、被否决的意见模式、常见缺陷 | PgVector | 长期，带衰减 |
| L3 开发者画像 | 常犯错误、偏好的评审风格 | PG | 长期 |

### 7.2 反馈学习闭环

1. 开发者在评论下回复 👎 或「不适用，因为…」
2. integration 发出 `FindingFeedbackReceived`
3. memory 用 LLM 抽取否决记忆（例：「本项目允许 Controller 直接返回实体」）
4. 与已有记忆比对，执行 ADD / UPDATE / DELETE 冲突消解
5. 下次评审在 Dedup & Filter 节点过滤同类意见

### 7.3 检索与遗忘

- 召回：向量召回 + PG 全文检索混合
- 打分：`score = similarity × importance × decay(Δt)`，取 top-k 注入 prompt
- 遗忘：score 长期低于阈值的记忆归档；支持人工删除错误记忆

## 8. 设计决策记录

| 决策 | 理由 |
|---|---|
| 自研 DAG 而非 Flowable / LangGraph4j | 需要动态扇出和 LLM 节点级 checkpoint 语义，自研范围可控 |
| PG + pgvector 而非独立向量库 | 少一个组件，单人可运维，数据量级足够 |
| 服务控制在 5+1 个 | 单人可完成，边界讲得清 |
| GlobalReduce 只读摘要 | 控制上下文规模，摘要质量通过标注 PR 集合评估 |
