---
description: 结束当前里程碑，运行验证并更新交接文件
---

按 CLAUDE.md 的「会话结束」流程收尾：

1. 运行当前里程碑在 docs/ROADMAP.md 中的验证点，展示实际输出
2. 验证未通过时，不标记完成，在 PROGRESS.md 中记录失败原因和下一步
3. 更新 docs/PROGRESS.md：当前状态、已完成里程碑一节、决策与偏差日志、「下一个会话要做的事」（写到新会话不看聊天记录也能直接开工的程度）
4. 设计有变更时同步 docs/ARCHITECTURE.md
5. 列出建议的 git commit 信息，不要自动提交

$ARGUMENTS
