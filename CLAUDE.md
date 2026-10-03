# CodeMind 项目约定

## 会话协议（跨会话开发）

本项目按里程碑分会话开发，上下文不跨会话保留，以文件为准。

会话开始：
1. 读 `docs/PROGRESS.md`，确认当前里程碑和「下一个会话要做的事」
2. 读 `docs/ROADMAP.md` 中当前里程碑的验证点（整体方案与版本锁定见 `docs/PLAN.md`）
3. 只读 `docs/ARCHITECTURE.md` 中与当前里程碑相关的章节
4. 复述本会话目标和验证点，再开始改代码

会话中：
- 一个会话只做一个里程碑（或其拆分子项）
- 设计偏离 ARCHITECTURE.md 时，先停下说明，确认后同步修改文档

会话结束（验证完成后）：
1. 运行当前里程碑的验证点，如实记录结果
2. 更新 `docs/PROGRESS.md`：已完成项、验证命令与结果、遗留问题、下一个会话要做的事
3. 如有设计变更，同步 `docs/ARCHITECTURE.md`
4. 提示用户提交 git commit（不自动提交）

## 代码约定

- 基础包名：`com.codemind.<service>`，分层 `interfaces / application / domain / infrastructure`
- domain 层不依赖 Spring 和任何基础设施
- 领域事件定义放在 `codemind-common` 的 `event` 包
- 密钥（LLM API Key、GitHub Token、Webhook Secret）只通过环境变量注入，不写入仓库
- 测试命令：`mvn verify`（M0 落地后如有变化在此更新）
