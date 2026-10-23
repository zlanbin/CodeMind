# M7b 回写 PR 运维说明

评审完成到 GitHub 出现 review 的链路、各处状态怎么查、卡住时怎么处理。设计依据见 `docs/plans/M7b-design.md`。

## 链路与保证边界

```
review: DAG → AGGREGATING → 报告 → review_publication_outbox（同事务登记）
        → 后台 dispatcher 每 30s 扫描到期记录 → MQ codemind-review-completed
integration: ReviewCompletedListener → ReviewPublishService
        → Redis 锁 codemind:publish-lock:<taskId> → integration.review_publication 账本
        → 发前置 IN_FLIGHT → POST /pulls/{n}/reviews → PUBLISHED → MQ codemind-review-published
review: ReviewPublishedListener → 同事务 review_task=PUBLISHED + outbox.acknowledged_at
```

- outbox 在 ACK 前每 60s 重投同一事件（eventId 不变），不承诺 exactly-once。
- integration 每次先按隐藏标记 `<!-- codemind:task=<taskId> -->` 查 PR 已有 review，查到就只补回执，不再 POST。
- **结果不确定（超时、5xx、进程在 POST 前后退出）的记录停在 `IN_FLIGHT` / `UNCERTAIN`，查不到标记也不会自动重发**。
  没有超时自动重置，只能人工处理（见下）。
- 401/403/404/422/429 等明确拒绝会回到 `READY`，等下次重投再试；`INLINE` 模式遇 422 先落 `SUMMARY_ONLY` 再发一次（行级意见并入总结）。

## 存储位置

| 位置 | 内容 |
|---|---|
| `public.review_task` | 任务状态，终态 `PUBLISHED` |
| `public.review_publication_outbox` | review 侧待投递请求（V5），`acknowledged_at` 非空表示已收到成功回执 |
| `integration.review_publication` | integration 侧发送账本（独立 schema，Flyway 历史表也在该 schema） |
| Redis `codemind:publish-lock:<taskId>` | 发布锁，TTL 120s、每 30s 续租；只降并发，正确性靠账本栅栏 |

integration 从 M7b 起启动依赖 PostgreSQL。

## 状态查询

```bash
PSQL='docker exec codemind-postgres psql -U codemind -d codemind -c'

# 任务 + outbox
$PSQL "select t.task_id, t.status, o.attempts, o.next_attempt_at, o.lease_until, o.acknowledged_at, o.last_error
       from review_task t left join review_publication_outbox o using (task_id)
       where t.task_id = '<taskId>';"

# 发送账本
$PSQL "select task_id, state, mode, attempt_token, review_id, inline_count, degraded, last_error, updated_at
       from integration.review_publication where task_id = '<taskId>';"

# 积压概览
$PSQL "select count(*) filter (where acknowledged_at is null) as pending,
              count(*) filter (where acknowledged_at is null and attempts >= 5) as retried_5plus
       from review_publication_outbox;"
$PSQL "select state, count(*) from integration.review_publication group by state;"
```

## 常见情形

| 现象 | 含义 | 处理 |
|---|---|---|
| outbox 未 ACK、账本无记录 | 事件没到 integration（topic 配错、integration 没起、MQ 不通） | 查 integration 日志与 topic 配置；修好后 dispatcher 会自动重投 |
| 账本 `READY`、`last_error=SCM rejected: 4xx` | GitHub 明确拒绝 | 按状态码修环境（token 权限、PR 已关闭等），重投自动重试。422 先查：token 用户不能是 PR 作者；`commit_id` 必须是 40 位完整 SHA |
| 账本 `PUBLISHED`、outbox 未 ACK | 回执丢了 | 无需人工：下次重投时 integration 直接补发回执 |
| 账本 `IN_FLIGHT` / `UNCERTAIN` | 不确定是否已写入 GitHub | 先做下面的只读核查，**不要直接改状态** |

## UNCERTAIN / IN_FLIGHT 只读核查

```bash
# token 只从环境变量取，不贴进命令历史。逐页读到空页为止，只看第一页不能下结论
for page in 1 2 3 4 5 6 7 8 9 10; do
  body=$(curl -s -H "Authorization: Bearer $GITHUB_TOKEN" -H "X-GitHub-Api-Version: 2022-11-28" \
    "https://api.github.com/repos/<owner>/<repo>/pulls/<n>/reviews?per_page=100&page=$page")
  [ "$body" = "[]" ] && { echo "读完，共 $((page-1)) 页"; break; }
  printf '%s' "$body" | grep -c 'codemind:task=<taskId>'
done
```

- **找到标记**：确认已写入。不需要改库，下次 outbox 重投（最长约 60s + 扫描间隔）时 integration 会查到标记、
  记 `PUBLISHED` 并发回执。
- **没找到标记**：大概率没写入，但不能排除 GitHub 延迟。10 页仍没读完（超过 1000 条 review）时不能判定，
  integration 自身遇到这种情况也会拒绝发送。确认无误后按下面人工重置。

## 人工重置为 READY（会导致再次 POST）

前置条件，缺一不可：

1. 确认没有正在运行的发送方：所有 integration 实例都已停止。Redis 锁只降并发，锁不存在不能证明没有发送方
2. 已按上节核查 GitHub 上没有该 task 标记，并保存核查输出作为证据
3. 用户明确确认重发

```sql
-- 带上核查时看到的 state 和 attempt_token，任何一项变了都不会命中
update integration.review_publication
set state = 'READY', attempt_token = null,
    last_error = 'manual reset after verified absence on <date> by <who>', updated_at = now()
where task_id = '<taskId>'
  and state = '<IN_FLIGHT 或 UNCERTAIN>'
  and attempt_token = '<核查时的 attempt_token>';
-- 期望受影响行数 = 1；为 0 说明状态已变，停下重新核查
```

重置后不必改 outbox，下一轮重投会触发发送。本文所有 SQL 都不会自动执行。

## 真实写验收（`zlanbin/codemind-sandbox`）

普通 `mvn verify` 不跑 `RealPullRequestPublishIT`（failsafe 精确排除），只有 `-Pgithub-write` 才跑。
分三个阶段，每个阶段之间停下人工确认：

- prepare：只读 GitHub、跑真实 LLM、写 outbox（正文带测试标记 `<!-- codemind:test=github-write -->`），
  打印待发内容和可清理的旧测试评论。行级评论不在新侧 diff 内、或没命中 `expect-line` 指定的预埋缺陷，
  就判失败并撤掉这条 outbox
- cleanup（可选）：只删用户确认过的那批评论 id，重新计算的候选与确认不一致就整体放弃；
  发送结果未定（`IN_FLIGHT`/`UNCERTAIN`）的任务不在候选里
- publish：只投递已确认的 taskId，回查 GitHub，再重投一次，等到新回执后确认 review 仍只有一个

缺 token、LLM key、PR 号、完整 head SHA 时直接失败，不跳过。PR 当前 head 与 `CODEMIND_TEST_HEAD_SHA` 不一致也会停下。

前置：

- 中间件 healthy
- integration 用新 jar 副本启动，读生产 topic
- 没有别的 review 实例在跑
- PR 作者与 token 用户不同
- `CODEMIND_TEST_HEAD_SHA` 用完整 SHA

```bash
source ~/.codemind-secrets.env   # GITHUB_TOKEN / CODEMIND_LLM_API_KEY / CODEMIND_TEST_PR / CODEMIND_TEST_HEAD_SHA
W='mvn verify -pl codemind-review -am -Pgithub-write -Dit.test=RealPullRequestPublishIT -Dfailsafe.failIfNoSpecifiedTests=false'
$W -Dcodemind.github-write.phase=prepare -Dcodemind.github-write.expect-line=InventoryClient.java:11
# 有旧测试评论且确认要删时
$W -Dcodemind.github-write.phase=cleanup -Dcodemind.github-write.confirm-delete=<prepare 打印的 id 列表>
# 确认待发内容后
$W -Dcodemind.github-write.phase=publish -Dcodemind.github-write.task-id=<prepare 打印的 taskId> \
  -Dcodemind.github-write.expect-line=InventoryClient.java:11
```

`expect-line` 是目标 PR 预埋缺陷的 `文件名:新侧行号`。sandbox PR #2 只有 `InventoryClient.java:11` 一处。

`-Dit.test=` 会绕过 excludes，所以上面命令即使不加 profile 也会跑到该类。区分"默认不跑"要用不带过滤的裸 `mvn verify` 验。
