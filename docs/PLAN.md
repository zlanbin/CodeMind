# CodeMind 智能代码评审平台 - 整体方案

> 总入口文档。详细设计见 [ARCHITECTURE.md](ARCHITECTURE.md)，里程碑见 [ROADMAP.md](ROADMAP.md)，当前进度见 [PROGRESS.md](PROGRESS.md)。

## 1. 背景与选型

简历项目，硬性条件：

1. 基于 Java 的 AI 工作流项目
2. Spring Boot + 微服务 + DDD 作为基础架构
3. 具备智能体记忆
4. 补充约束：单个会话的上下文有限，产品和开发过程都要支持跨多个会话推进

候选方向有 5 个：工作流编排平台、智能客服、AI 面试官、代码评审、记忆助理。最终选择**代码评审智能体**，并内嵌一个精简版自研 DAG 引擎。理由如下：

- 评审流程天然是「解析 → 并行多 Agent → 聚合」的 DAG，自研引擎有真实落地场景
- 项目记忆解决的是「反复提无效意见」这个真实问题，不是硬加的
- 可以接自己的仓库跑出真实数据，简历指标经得起追问
- 面试官本身是研发，业务背景不需要额外解释

## 2. 一句话定位

PR 提交后自动触发多 Agent 并行评审，按代码规范、安全、性能、测试四个维度给出行级评论。系统记住团队规范和开发者否决过的意见，越用越准。

## 3. 「跨会话」的两层含义

| 层面 | 问题 | 方案 | 详见 |
|---|---|---|---|
| 产品层 | 大 PR 超出单次 LLM 上下文 | 全局变更地图 + AST 语义切片 + Token 预算装箱 + 子会话结构化摘要 + 全局归约 + checkpoint 续跑 | ARCHITECTURE.md 第 6 节 |
| 开发层 | AI 协作开发时，会话上下文不能跨会话保留 | 一个里程碑对应一个会话，用真值文件交接，固定 `/next`、`/handoff` 流程 | 本文第 6 节 |

两层用的是同一个思路：上下文有限时，把状态外置成结构化摘要，下一个会话只读摘要、不读全量。

## 4. 架构概览

- 服务：gateway + integration / review / memory / rule / admin 共 5 个业务服务
- 核心域：review-service 负责评审编排和 DAG 引擎，memory-service 负责分层记忆
- 通信：OpenFeign 同步调用，RocketMQ 传递领域事件
- 存储：PostgreSQL + pgvector（关系数据和向量同库），Redis
- 分层：`interfaces / application / domain / infrastructure`，domain 层不依赖 Spring

## 5. 版本锁定（2026 年 M0 前确认）

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK（本机） | 26.0.1 | 编译目标 `release 21`，语言特性按 21 使用 |
| Spring Boot | 3.5.16 | 未选 4.x，因为 Spring Cloud Alibaba 对 4.x 的适配刚发布，风险较高 |
| Spring Cloud | 2025.0.3 | 与 Boot 3.5 配套 |
| Spring Cloud Alibaba | 2025.0.0.0 | 内置 Nacos client 3.0.3、RocketMQ 5.3.1 |
| Spring AI | 1.1.8 | 1.x 线与 Boot 3.5 配套，M4 引入 |
| Nacos Server | 3.0.x | Docker 部署，注意 3.x 控制台端口和鉴权的变化 |
| PostgreSQL | 16 + pgvector | 镜像 `pgvector/pgvector:pg16` |
| Redis | 7 | |
| RocketMQ | 5.3.1 | |
| Maven | 3.9.16 | |

环境说明：

- 本机访问不了 Maven Central，阿里云镜像 `maven.aliyun.com` 可用，M0 中需要配置为项目仓库
- Docker 已配置国内镜像加速，能正常拉取镜像
- 风险：JDK 26 比 Boot 3.5 官方验证过的 JDK 版本新。如果 M0 遇到 Lombok 或 ByteBuddy 的兼容问题，就改为安装 JDK 21 来构建，并在 PROGRESS.md 中记录

## 6. 跨会话开发流程

### 6.1 真值文件

| 文件 | 作用 | 更新时机 |
|---|---|---|
| `CLAUDE.md` | 项目约定和会话协议，会话启动时自动加载 | 约定变化时 |
| `docs/PLAN.md` | 整体方案（本文件） | 方向或范围变化时 |
| `docs/ARCHITECTURE.md` | 架构真值 | 设计变更时，先改文档再改代码 |
| `docs/ROADMAP.md` | 里程碑和验证点 | 拆分里程碑时 |
| `docs/PROGRESS.md` | 会话交接 | 每个会话结束前必须更新 |

### 6.2 每个会话的操作

```
cd /d D:\pingshen
claude
/next              # 读 PROGRESS → 复述目标和验证点 → 确认后实施
...开发...
/handoff           # 跑验证 → 更新 PROGRESS → 给出 commit 信息
git add ... && git commit
```

规则：

- 必须在 `D:\pingshen` 下启动，项目的 CLAUDE.md 和命令才会加载
- 一个会话只做一个里程碑。做不完就拆成 M?a / M?b，先 `/handoff` 再开新会话
- 验证不通过不进入下一个里程碑
- 只在本地提交 git，不添加远程仓库

## 7. 里程碑总览

| 阶段 | 里程碑 | 产出 |
|---|---|---|
| 最小可演示版 | M0~M7 | 能在自己的 GitHub PR 上看到多 Agent 行级评论，大 PR 走跨会话评审 |
| 记忆亮点 | M8~M10 | 分层记忆、反馈闭环、增量评审 |
| 加分项 | M11~M13 | 规范 RAG、可观测与压测、演示脚本 |

预计工期：M0~M7 约 4~5 周，全部 8~10 周。各里程碑的验证点见 ROADMAP.md。

## 8. 简历写法

数字必须用真实跑出来的结果，M12 的压测报告和 M13 的统计就是数字来源。

- 设计分层 Map-Reduce 多会话评审架构，基于 AST 语义切片和 Token 预算装箱，支持超出上下文窗口 N 倍的 PR 评审，跨文件问题检出率提升 X%
- 自研轻量 DAG 引擎，支持动态扇出和节点级 checkpoint 断点续跑，失败重试的 LLM 调用成本降低 X%
- 构建四层智能体记忆，结合反馈事件做冲突消解和时间衰减遗忘，无效评审意见率从 X% 降至 Y%
- 基于 DDD 划分 5 个限界上下文，通过 RocketMQ 领域事件解耦评审、回写和记忆学习链路

## 9. 面试高频追问

| 问题 | 回答要点 |
|---|---|
| 切片后跨 chunk 的问题怎么发现？ | ChangeMap 提供全局视角，子会话上交 crossChunkQuestions，由 GlobalReduce 统一回答 |
| 摘要会丢信息吗？怎么评估？ | 用标注过的 PR 集合，对比全量评审和分片评审的检出率 |
| 重复 webhook 怎么处理？ | Redis 幂等键 + `(repo, pr, headSha)` 唯一索引 |
| 记忆冲突和错误记忆怎么处理？ | ADD/UPDATE/DELETE 冲突消解、时间衰减，支持人工删除 |
| LLM 输出不合法怎么办？ | JSON Schema 校验，失败重试，最终降级为纯文本 |
| 为什么不用 Flowable / LangGraph4j？ | 需要动态扇出和 LLM 节点级 checkpoint 语义，自研范围可控 |
| 为什么 PG 同时存向量？ | 少一个组件，单人可运维，当前数据量级足够 |
| 并行 Agent 怎么控制成本和限流？ | Token 预算、并发信号量、checkpoint 避免重复调用，在 Grafana 看成本 |

## 10. 扩展方向（不在当前范围）

- GitLab 接入
- 自动生成修复 PR
- 可视化 DAG 编辑
- 多租户和计费
