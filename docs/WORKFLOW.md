# 开发工作流（M7~M13 每个里程碑按此执行）

本文件由项目 `CLAUDE.md` 用 `@` 导入，每个会话无条件加载，不依赖 skill 触发。
里程碑清单与验证点见 `ROADMAP.md`；当前进度与下个会话的任务见 `PROGRESS.md`。

## 九级流程

每一级有出口产物。拿不到产物不进下一级。

| 级 | 做什么 | 出口产物 | 用什么 |
|---|---|---|---|
| S0 需求解析 | 读 PROGRESS「下一个会话要做的事」、ROADMAP 本里程碑验证点、ARCHITECTURE 相关章节、本里程碑的设计输入文档 | 向用户复述目标与验证点，等确认 | 直接读文件；`.docx` 用 `docx` |
| S1 头脑风暴 | 定设计里没写明的细节 | 设计共识 | `brainstorming` |
| S2 制定方案 | 落成可执行计划 | `docs/plans/M<n>.md` | `writing-plans` |
| S3 判定门 | 需求是否明确 | 明确进 S4；不明确停下 | 见下「判定门」 |
| S4 需求拆分 | 拆成带验证测试的任务 | `M<n>.md` 里的任务清单 | `writing-plans` |
| S5 代码编写 | 实现 | 测试绿 | 见下「S5 选哪个」 |
| S6 代码评审 | 独立评审 | 双轴评审报告 | `code-review` |
| S7 提交 | 过验证门后提交 | commit + 推送 | `verification-before-completion` → `github-commit` |
| S8 沉淀 | 按保质期分流落盘 | 记忆 / PROGRESS / ARCHITECTURE 更新 | `knowledge-consolidation` |

S8 结束后开新会话回到 S0，进下一个里程碑。一个会话只做一个里程碑。

## 三个硬门禁

### 判定门（S3）

需求有不明确、或与 ARCHITECTURE 冲突的地方：**停下**，列出具体是哪几点，提示用户敲 `/grill-me`。
`grill-me` 带 `disable-model-invocation`，模型无法自动调用，只能由用户触发。
grill 完回到 S2 改方案。不要自己替用户拍板然后继续往下写。

### 验证门（S7）

**跳过的测试不算通过。** `mvn verify` 输出里出现 skipped 时，必须逐条说明是哪些用例、为什么跳、
对应的验证点是否因此失去覆盖。缺 key 导致真实 LLM IT 被 `assumeTrue` 跳过时，相关验证点标
「未验证」，该里程碑**不标完成**。

> M4 踩过一次：Docker 不可用，标「代码完成、待验证」，做法正确，后续会话补跑才标完成。
> M6 又踩一次：3 个真实 LLM IT 全跳，仍标了完成。同类第二次，故成文。

前置命令：

```bash
cd deploy && docker compose up -d        # 等 PG / Redis / Nacos healthy
export CODEMIND_LLM_API_KEY=<key>       # 不设则真实 LLM IT 跳过，等于没验
mvn verify
```

key 用 `! export ...` 在会话里设，不要贴进对话正文。

### 评审门（S6）

自己写的代码自己验，看不见分层被悄悄破坏、边界条件被漏掉这类问题。`code-review` 固定点用
**上一个里程碑的 commit**，双轴跑：标准轴对 `CLAUDE.md` 的代码约定（包名分层、domain 不依赖
Spring、领域事件放 common、密钥只走环境变量），规格轴对 ROADMAP 验证点和 `docs/plans/M<n>.md`。

评审发现严重问题回 S5 修，不要在 S7 顺手打补丁。

> M6 跳过了这一级：从未被不带实现上下文的评审者看过。

## S5 选哪个执行 skill

| 任务形态 | 用 |
|---|---|
| 相互独立且 ≥3 个 | `subagent-driven-development`（底层 `dispatching-parallel-agents`） |
| 链式依赖，或 ≤2 个 | `executing-plans` |

两者都配 `test-driven-development`。测试失败走 `systematic-debugging` 找根因，不许改症状。

需要隔离时先 `using-git-worktrees`，合并时 `finishing-a-development-branch`。

## 往外部系统写东西的里程碑要额外确认

M7（往真实 PR 发行级评论）、M9（反馈闭环）、M11（上传规范文档）会在外部系统留下痕迹，
做错了要手工清理。这几个里程碑在首次真实调用前停下来，把将要写什么、写到哪个仓库说清楚，
等用户确认。先在自己的测试仓库跑通，再碰有意义的仓库。

## 快车道

单文件小改、修 typo、补一个测试：S1 判定为小改动后直接进 S5，跳过 S2 S3 S4，S6 S7 照走。
里程碑级别的工作不走快车道。

## 开场句模板

```
进 M<n>，按 docs/WORKFLOW.md 走。
```

流程已随 `CLAUDE.md` 加载，不需要在开场句里重复各级内容。设计输入文档如果不在
`docs/` 下，在开场句里给出路径。

