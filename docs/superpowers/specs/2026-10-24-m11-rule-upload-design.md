# M11 rule-service：规范文档 RAG 设计

> 对应 ROADMAP M11：「上传规范文档后，评审意见能引用对应条款」。
> 设计输入文档路径（`docs/design/M11-rule-upload.docx`）在仓库中不存在，本设计经用户确认改为直接头脑风暴产出设计共识，无外部设计输入文档可比对。

## 1. 架构总览

```
RuleUploadService (rule-service, interfaces/application)
  POST /internal/rules/documents {project, title, content}
    → 按标题层级切块 → RuleChunk[] → 本地特征哈希 embedding → 写库（版本替换旧 ACTIVE）

RuleRecallService (rule-service)
  POST /internal/rules/recall {project, query, topK?}
    → 向量余弦 ∪ pg_trgm 两路候选 → 打分排序 → 返回 topK 条带 section 路径的 RuleChunk

review-service
  RuleRecallPort (domain 出站端口) → FeignRuleRecallAdapter (infrastructure)
  RecallRulesNode (dag)：按 chunk 的 changedSymbols/files 拼查询文本 → 召回 → 结果挂到共享状态
  ReviewAgent prompt 注入召回到的规则条款（含 section 路径），要求引用时带上路径
```

rule-service 独立落地（不并入 memory-service，不做 rule→memory 的检索代理），复用 M8 已验证的技术路径：独立 schema（`rule`，同 integration/memory 做法）、本地特征哈希 Embedder、两路候选（向量余弦 + pg_trgm）+ 打分、按 project 隔离、`/internal/*` 无鉴权（技术债，同 M7a/M8/M9a 口径记录，不在本里程碑解决）。

与 M8 memory 的本质差异：memory 存"记忆条目"（会被否决、会衰减遗忘），rule 存"文档切块"（只有 ACTIVE/ARCHIVED 两态，没有衰减维度），多了切块与章节路径这一层。两侧端口、DTO、Embedder 实现各自独立定义，不跨模块共享类（延续 M8/M9b 决策记录的既定原则）。

## 2. 领域模型（rule-service）

```
RuleDocument (聚合根)
 ├─ documentId, project, title, status (ACTIVE/ARCHIVED), version, uploadedAt
 └─ List<RuleChunk>（通过 documentId 关联，不嵌套持有，与 M6 ChunkSummary 按 chunkId 关联 ReviewTask 的做法同理）

RuleChunk (实体)
 ├─ chunkId, documentId, project
 ├─ sectionPath   # 标题文本按层级拼接，如 "接口设计规范 > 方法命名" 或文档本身写了编号的 "3.2.1 方法命名"
 ├─ heading       # 该块最近的标题文本
 ├─ content       # 该块正文（不含子标题块的内容，严格按层级边界切）
 └─ embedding vector(256)   # 维度与 M8 HashingEmbedder 一致
```

### 切块规则

按 Markdown 标题（`#`~`######`）切，每个标题到下一个同级或更高级标题之间的正文为一块。`sectionPath` 直接取标题文本拼接层级路径，不强制要求文档写死数字编号——很多规范文档标题本身带编号（如"3.2.1 方法命名"），直接取标题文本作路径最通用，不需要额外解析编号格式。空块（纯标题无正文）跳过，不生成 `RuleChunk`。

### 版本替换

同 `project + title` 视为同一文档。重新上传时，旧文档整体转 `ARCHIVED`（连同其全部 `RuleChunk`），插入新文档 + 新 chunk 全部为 `ACTIVE`，整个过程在一个事务内完成。召回只查 `ACTIVE` 状态的 chunk。

## 3. 存储与端口（rule-service）

```
db/rule-migration/V1__create_rule.sql（独立 schema `rule`）
  rule.document: (id, project, title, status, version, uploaded_at)
    部分唯一索引 (project, title) where status='ACTIVE'  -- 保证同项目同标题只有一个 ACTIVE 版本
  rule.chunk: (id, document_id, project, section_path, heading, content, embedding vector(256))
    索引 (project, document_id)

domain/rule（纯领域，不依赖 Spring，不跨模块共享类）
  RuleDocument / RuleChunk / RuleChunkCandidate（召回候选，带 similarity）
  端口：RuleDocumentRepository、RuleChunkRepository、Embedder
  recall 子包：ScoringPolicy / RuleRanker（结构参照 M8 的 ScoringPolicy/MemoryRanker，但不做衰减——
    规范条款不像记忆会"过时遗忘"，只有 ACTIVE/ARCHIVED 两态）

application
  RuleIngestionService：上传 → 切块 → embedding → 事务内（旧文档转 ARCHIVED + 新文档插入）
  RuleRecallService：recall(project, query, topK) → 两路候选 → 打分（score = max(余弦, pg_trgm)，
    无 importance/decay 因子，比 M8 公式简化）→ topK

infrastructure
  HashingEmbedder（算法与 M8 一致，各自模块独立维护一份实现，不跨模块共享类）
  JdbcRuleDocumentRepository / JdbcRuleChunkRepository
  RuleConfig / RuleProperties

interfaces
  RuleController：POST /internal/rules/documents、POST /internal/rules/recall
  RuleExceptionHandler
```

打分公式比 M8 简化：规则条款没有"重要性"和"衰减"概念（不会被否决、不会过时失效，只有版本替换这一种"失效"方式），故只用 `max(余弦相似度, pg_trgm similarity)` 排序取 topK，不引入 importance/decay 因子。

## 4. review 侧接入

```
domain/rule（review 侧，独立定义，不跨模块共享）
  RuleRecallPort（出站端口）：recall(project, query, topK) -> List<RecalledRule>
  RecalledRule（section 路径 + 内容 + similarity）
  RuleRecallException

infrastructure/rule
  RuleFeignClient（@FeignClient(name = "codemind-rule")，按服务名经 Nacos 发现，同 M9b FeignMemoryRecallAdapter 做法）
  FeignRuleRecallAdapter

dag/RecallRulesNode
  依赖 PlanChunks，按 chunk 粒度调用一次（4 个维度的评审节点共享同一个 chunk 的召回结果，
  不重复召回 4 次——4 个维度查询文本相同，没必要分别调用）
  查询文本 = chunk.changedSymbols 拼接 + chunk 涉及文件路径（不传 diff 本体，与 ChangeMap 的
    「不投喂 diff 本体」原则一致）
  降级策略：召回失败 / 规则服务不可用 / 无候选命中 → 静默返回空列表，不记入 degradations。
    这与 FindingFilter「过滤失败必须放行」不同严重级别：规则缺失不代表评审质量下降（本来就可能没有
    匹配的规则条款），只有 Feign 调用异常时记一条 debug 日志，不升级为用户可见的降级说明

ReviewAgent prompt 扩展
  召回到规则时，system prompt 追加「团队规范条款」区块：每条格式为
    "《文档标题》sectionPath: content（截断）"
  要求模型发现违反规则的问题时，Finding.message 里带上来源，格式：
    "违反《文档标题》sectionPath：原因"
  召回为空时 prompt 不出现该区块（不占位、不提示"无规则"字样，保持 prompt 干净）
```

引用形式采用 `Finding.message` 文本内嵌条款号，不新增结构化字段（如 `ruleRef`）。改动小，验证点只需要断言 message 文本包含 sectionPath，不需要改动 `Finding` 值对象、解析器、数据库列、对外展示格式。

## 5. 验证点与验收样例

ROADMAP M11 验证点：「上传规范文档后，评审意见能引用对应条款」。

### 真实 LLM 验收 IT（固定资产样例，命名待定，如 `RuleRecallAndCiteIT`）

延续 M4~M10 的既定口径：缺 `CODEMIND_LLM_API_KEY` 时显式判失败，不用 `assumeTrue` 静默跳过；跳过的测试不算通过。

1. **规范文档样例**：一份简短 Markdown，含一条明确、可机器判定违反的规则，例如：
   ```markdown
   ## 3.2 接口设计规范
   ### 3.2.1 方法命名
   公开方法命名必须用「名词+动词」结构，禁止使用单字母或缩写作为方法名。
   ```
2. **配套 diff 样例**：新增一个违反该规则的方法（如单字母命名 `public void x() {...}`）。
3. **流程**：`POST /internal/rules/documents` 上传样例文档（project 用测试专属值）→ 真实走
   `RecallRulesNode` + `ReviewAgent` 评审样例 diff → 断言：
   - 返回的 `Finding` 列表中至少一条 `message` 包含 `3.2.1`（sectionPath）
   - 该 Finding 的 category 合理（STYLE 或 CORRECTNESS 均可接受，不强求具体哪个，由真实 LLM
     判定；参考 M9a「分类与预想不同但结果仍正确」的既有判例，不因分类偏差判失败）
4. **对照样例（可选，视时间决定是否纳入本里程碑，否则记入技术债）**：不命中规则的 diff（如规范的
   命名）不应被错误引用，避免模型编造条款号。

### 单测覆盖（纯逻辑，不依赖真实 LLM）

- `RuleDocumentChunker`（切块）：标题层级切块、空块跳过、多级标题路径拼接
- 版本替换：重复上传同 project+title，旧文档转 ARCHIVED、新文档为 ACTIVE，召回只返回新版本
- `RuleRanker`（打分排序）：参照 M8 `MemoryRankerTest` 的验证思路（候选排序符合公式）
- `RecallRulesNode`：召回失败 / 空候选时静默返回空列表、不产生 degradation

## 6. 已知范围边界（本里程碑不做）

- `/internal/rules/*` 端点无鉴权（同 M7a/M8/M9a 既有技术债，不在本里程碑解决）
- 不支持 docx/PDF 等格式，仅 Markdown/纯文本
- 不做前端或 CLI 上传工具，仅 REST 接口
- Finding 不新增结构化 `ruleRef` 字段，引用仅为 message 文本内嵌
- 规则不做衰减/遗忘，只有 ACTIVE/ARCHIVED 两态
- 对照样例（规则未命中时不应编造引用）视时间决定是否纳入，否则记入技术债
