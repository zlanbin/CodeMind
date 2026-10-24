# CodeMind 全链路演示（M13）

验证 ROADMAP M13 的验证点：**新环境按演示脚本从零跑通**。每一步标注是「只读/本地」
还是「真实写 GitHub，需人工确认」。真实写入统一发生在 sandbox 仓库
`zlanbin/codemind-sandbox`（专用于本次演示的新 PR，不复用 M7a/M7b/M9a/M10 已用过的
PR #1/#2/#3）。

## 前置条件

- JDK 21+、Maven 3.9+、Docker、Docker Compose（见根 README）
- 已 clone 本仓库到一个干净目录（本次验证用 `D:\daima\codeTest`；`D:\daima\ssyx` 已是另一个
  不相关项目，不要往里面 clone）
- 已有 sandbox 仓库的写权限：`GITHUB_TOKEN`（`pull_requests: write`，账号与下面开 PR
  的账号不同，建议用 `lan-bin`），`CODEMIND_LLM_API_KEY`（中转端点真实 key）
- 密钥文件只在本机环境变量里 `source`，不贴进脚本或对话

```bash
source ~/.codemind-secrets.env   # GITHUB_TOKEN / CODEMIND_LLM_API_KEY
```

## 步骤 0：密钥检查（只读/本地）

```bash
bash docs/demo/00-check-secrets.sh
```

## 步骤 1：起基础设施（只读/本地）

```bash
bash docs/demo/01-infra.sh
```

起 PostgreSQL+pgvector、Redis、Nacos、RocketMQ、Prometheus、Grafana 六个容器，等
PG/Redis/Nacos 健康检查通过。

## 步骤 2：构建并起 6 个服务（只读/本地）

```bash
bash docs/demo/02-build-and-start.sh
```

`mvn package -DskipTests` 构建全部模块，起 gateway(8080)/integration(8081)/
review(8082)/memory(8083)/rule(8084)/admin(8085)，等 `/actuator/health` 全 UP。
日志在 `/tmp/codemind-demo/<service>.log`。结束演示后用
`bash docs/demo/99-stop.sh` 停止（不动基础设施容器）。

## 步骤 3：上传团队规范文档（只读/本地）

```bash
export CODEMIND_DEMO_REPO=zlanbin/codemind-sandbox
bash docs/demo/03-upload-rule.sh
```

把 `codemind-review/src/test/resources/rule/sample-convention.md`（3.2.1 方法命名
规则）上传到 rule-service。后续评审会在 `RECALL_RULES` 节点自动召回这条规则。

## 步骤 4：在 sandbox 开 PR 并触发评审（真实写 GitHub，需人工确认）

**人工操作（在 GitHub 网页上做）**：
1. 用 `lan-bin` 账号在 `zlanbin/codemind-sandbox` 新开一个 PR，改动里包含一个违反
   「公开方法命名必须用名词+动词结构」的方法名（比如 `calc(int x)`），方便观察评审
   是否引用了刚上传的规范条款
2. 记录 PR 号、base SHA、head SHA（`git rev-parse HEAD` 与 base 分支的 SHA，都要
   完整 40 位）

```bash
export CODEMIND_DEMO_PR=<PR 号>
export CODEMIND_DEMO_BASE_SHA=<base 完整 SHA>
export CODEMIND_DEMO_HEAD_SHA=<head 完整 SHA>
bash docs/demo/04-trigger-review.sh
```

脚本发真实 webhook 并轮询 `review_task` 状态直到 `PUBLISHED`（预留 500s：评审本身到
`AGGREGATING` 约 30~90s，`outbox` dispatcher 下一轮扫描发布最多再等约 60s，回写 GitHub
走一次 Feign + REST 调用）。完成后打开 PR 页面，应该能看到行级评论，其中命名相关的一条
应引用 `《示例团队规范》3.2.1`。

**这一步会真实调用 LLM（消耗 token）并真实发布 PR 评论，发布前请确认 PR 号正确。**

**2026-10-24 实测踩过的坑（新环境验证时留意）：**
- `GITHUB_TOKEN`（评审回写用的账号）配额是 5000 次/小时，本次演示在约 1 小时内触发了
  5~6 次评审会打满；打满后 integration 侧报 403，`/internal/feedback/poll` 和真实
  diff/compare 调用都会失败。查 `curl -H "Authorization: Bearer $GITHUB_TOKEN"
  https://api.github.com/rate_limit` 确认 `remaining`，打满了只能等 `reset` 时间
- RocketMQ 的 `codemind-review-completed` 和 `%RETRY%codemind-integration-review-completed`
  两个 topic 如果积压了大量历史失败消息（比如之前会话遗留的、指向已失效 PR 的消息），会
  把新消息的消费吞吐堵死，新评审卡在 `AGGREGATING` 迟迟不到 `PUBLISHED`。用
  `mqadmin consumerProgress -n rocketmq-namesrv:9876 -g codemind-integration-review-completed`
  查 `Diff` 列是否异常偏高（正常应在几十条以内），偏高就用
  `mqadmin resetOffsetByTime -n rocketmq-namesrv:9876 -g codemind-integration-review-completed
  -t <topic名> -s now` 把两个 topic 的 consumer offset 都重置到当前时间，跳过积压

## 步骤 5：反馈闭环（真实写 GitHub，需人工确认）

**人工操作（在 GitHub 网页上做）**：
1. 找到上一步发布的评论里一条你想否决的意见（比如一条 STYLE/PERFORMANCE 类的提醒）
2. 用 `lan-bin` 账号回复「本项目不需要这个校验，这是预期行为」
3. 对同一条评论点 👎

```bash
bash docs/demo/05-feedback-loop.sh
```

脚本触发反馈轮询，再查 memory 召回确认是否学到了 `REJECTED_PATTERN`。

**这一步是真实 GitHub 读写往返，回复和 👎 需要人工在网页上操作。**

## 步骤 6：第二次 push，验证增量评审（真实写 GitHub，需人工确认）

**人工操作（在本地 git 里做）**：
在步骤 4 的 PR 分支上新增一个不相关的小改动（比如加一行注释）并 push，记下新 commit
的完整 SHA。

```bash
bash docs/demo/06-incremental-push.sh <新 head sha>
```

脚本发第二次 webhook（`action=synchronize`），验证：
- `last_reviewed_head` 表已记录第一次评审的 base/head
- 第二次评审走 compare 增量路径，只评审新增的 diff
- PR 页面上第二次评审的行级评论数明显少于第一次（不是重新评审一遍整个 PR）
- 步骤 5 否决过的那类意见即使在新 diff 里出现类似代码，也不会再被评论（`FindingFilter`
  生效）

## 步骤 7：核实 Grafana 可观测指标（只读/本地）

```bash
bash docs/demo/07-check-metrics.sh
```

查 Grafana 后端 API（走同一条 Prometheus 数据源 proxy），确认 Token 用量面板和 DAG
节点耗时 P95 面板都有非空非 NaN 数据。

**查询时机是关键，2026-10-24 实测在此反复踩坑：** P95 面板依赖最近 5 分钟内的真实流量
（`rate(...[5m])` 滑动窗口），但 DAG 节点耗时的打点**只发生在任务到达 `AGGREGATING`
之前**（`BUILD_CHANGE_MAP`/`REVIEW_CHUNK`/`RECALL_RULES`/`PLAN_CHUNKS`/`CHUNK_REDUCE`
在 `REVIEWING→AGGREGATING` 之前就已完成打点，`GLOBAL_REDUCE` 在 `AGGREGATING` 阶段跑完）。
而整个任务到 `PUBLISHED`（outbox 重投 + 回写 GitHub）通常还要再等 5~8 分钟。**如果等
`06-incremental-push.sh`/`04-trigger-review.sh` 跑完显示 `PUBLISHED` 之后才来跑本脚本，
节点指标早就滑出 5 分钟窗口了，查到的几乎必然是 NaN**——这不是配置错误，是验证时机选晚了。

正确做法：另开一个终端 `tail -f /tmp/codemind-demo/review.log | grep "状态 → AGGREGATING\|跨会话评审完成"`，
看到这两行其中任意一行出现后立刻跑 `07-check-metrics.sh`，不必等对应的 `04`/`06` 脚本显示
`PUBLISHED`。如果确实错过了窗口，重新触发一次评审（提交一个新 commit 走 `06-incremental-push.sh`），
这次在它还在跑的时候就守着日志查，别等脚本自己退出。

## 清理

演示结束后：

```bash
bash docs/demo/99-stop.sh          # 停 6 个服务进程
cd deploy && docker compose down   # 如果不再需要基础设施容器
```

sandbox 上发布的 review 评论不会自动清理，留着不影响其他里程碑的历史记录（同 M7b~M11
一贯做法）。如需清理可用 `codemind-review` 测试资源里的 `SandboxReviewCleanup`
工具（见 `docs/operations/M7b-publication.md`）。
