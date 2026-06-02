# Argus 会话上下文（2026-05-31 更新）

## 1. 项目定位

- Argus 已确定替代 RAG-ONE，作为新的主项目。
- 当前目标不是再找新项目，而是在离职前先系统吃透模块和主链路；离职后再集中整理成项目描述、主讲稿、README 口径、面试追问与回答等成果物。
- 这个项目的讲法要收口成：一个更完整的 RAG 知识平台、一个有后端工程味道的 AI 项目、一个 1 年经验也能稳定讲清的主项目。
- 不要把 Argus 包装成完整商业化企业平台负责人项目，也不要只堆技术名词。

## 2. 协作原则

- 先基于真实源码和真实链路理解项目，再决定是否需要轻量改造。
- 优先服务于简历和面试表达，重点是"可复述、可追问、能落到代码"。
- 输出时优先给主链路、关键类定位、工程取舍和可讲亮点，不把精力放在无边界扩功能或大量补测试上。

## 3. 已完成进度

### auth（认证授权） ✅
- 已系统梳理完毕，主讲点和能力边界清晰。
- 关键结论：access token（JWT 无状态，30min），refresh token（数据库 + httpOnly Cookie，14 天，一次性 + 重放检测）。
- 密码加密：BCryptPasswordEncoder，含 72 字节上限校验。
- 登录标识：大小写不敏感（`lower(username) = lower(loginId)`）。

### upload（分片上传） ✅
- 主链路已梳理完毕，三段式接口：init → chunk upload → complete。
- 秒传检测（SHA-256 哈希）、断点续传（session + chunk 表）。
- 完成上传后：文档元数据入库 + 发布 `DocumentIngestionRequestedEvent` 事件触发异步 ETL。

### ingestion（异步摄入） ✅
- 全链路已系统梳理，统一收口为 Spring 事件驱动异步摄入。
- parser 层：简单工厂 + 策略模式（`DocumentParser` 接口 + `Txt/Md/Pdf/DocxDocumentParser` 实现）。
- 切片：结构感知分层切片（标题 → 段落 → 句子 → 固定长度，overlap 32 字符）。
- 切片参数（dev）：target=240, max=320, overlap=32（字符近似 token）。
- `ingestion_jobs` 相关代码已彻底移除。
- 七步主链路：查找文档 → 读取（MinIO） → 解析（ParserFactory） → 清洗（TextCleanup） → 预览落库 → 切片（StructureAware） → 向量写入。

### qa / retrieval（问答与检索） ✅
- 全链路已系统梳理，主流程和关键类定位清晰。
- 查询规划：DIRECT / REWRITE / DECOMPOSE 三种策略，失败回退到 DIRECT。
- 双通道：pgvector 向量检索 + pg_trgm 关键词检索，CHANNEL_TOP_K = 50，关键词路故障时降级为空结果。
- RRF 融合：`RRF_K = 0`，按排名倒数累加排序。
- 窗口扩展：邻居窗口大小 1，补充命中 chunk 前后的上下文。
- 证据评估：NONE / WEAK / PARTIAL / SUFFICIENT 四级，NONE 触发硬编码拒答，其余通过 Prompt 约束。
- 预检索机制：先调 `retrieveEvidence()`，通过 `PREFETCHED_DOCUMENTS_CONTEXT_KEY` 传给 Advisor 避免重复查库。
- 引用：当前按文件级去重（`fileName`），`snippet` 固定留空。
- 同步 / 流式 Prompt 不一致：同步用 `system.st` 要求 JSON 输出，流式在代码里硬编码为纯文本。
- 做过一轮去重重构：抽取 `callLlm()` 公共方法，消除 `getStructuredAnswer` 和 `parseFallbackAnswer` 的重复代码。
- 发现 `QaAnswerParser.parse()` 实际和 `objectMapper.readValue()` 等效，兜底效果有限（已识别，未修复）。

### Chat 模型切换（2026-06-01 完成）
- 从 DashScope（通义千问 qwen-plus）切换到 Kimi（月之暗面 moonshot-v1 / kimi-k2.6）。
- 移除 `spring-ai-alibaba-starter-dashscope` 依赖，Chat 改用 `spring-ai-starter-model-openai` 指向 Kimi 的 `https://api.moonshot.cn/v1`。
- Embedding 保留 DashScope `text-embedding-v3`（通过 OpenAI 兼容接口）。
- Agent 框架 `spring-ai-alibaba-agent-framework` 保留不受影响（只依赖 `ChatModel` 抽象）。
- 纯配置改造，零 Java 代码变更。

### group / permission（群组与权限） ✅
- 已过完模块文档，核心概念和代码落点清晰。
- 权限不是独立 ACL 系统，收口在 `group_memberships.role` + `GroupMembershipService`。
- 当前角色只有 OWNER / MEMBER 两种，没有中间层角色。
- 两条入组路径：OWNER 邀请（邀请码）vs 用户按 groupCode 申请加入，互斥。
- 邀请/申请均采用"只允许从 PENDING 成功转出"的条件更新防重处理。
- `requireGroupReadable()` / `requireGroupOwner()` 是全局权限边界，document / qa / assistant 统一复用。
- 已配合确认：`GroupMembership.java` 实体类未实际使用（Mapper XML 全用 `resultType="map"`），`group_memberships` 表本身在用。
- 文档已同步口径：不要把当前实现讲成细粒度权限平台，本质是群组级角色控制。

## 4. ES → PG 全文检索改造（2026-05-31 完成）

- 已完成 Elasticsearch → PostgreSQL `pg_trgm` 的完整替换。
- 关键词检索从 ES（IK 分词 + BM25）迁移到 PG 内置 `pg_trgm` 三字符组相似度匹配。
- **新增**：`engine/search/PgKeywordSearchService.java`
- **修改**：`HybridChunkRetrievalService`、`DocumentIngestionAsyncService`（移除 `syncSearchIndex`）、`DocumentDeleteService`、`DocumentUploadService`
- **删除**：`engine/elasticsearch/ElasticsearchChunkIndexService.java`
- **配置**：`application-dev.yml` 移除 `elasticsearch` 配置块
- ingestion 主链路简化：切片落库后关键词索引通过 PG 索引自动生效，不再需要独立的 `syncSearchIndex()` 步骤。
- 改造方案详见：`docs/改造方案-ES替换为PG全文检索.md`

> 核心结论：retrieval 端依赖已精简为纯 PG 体系（pgvector + pg_trgm），面试可讲"一套 PG 搞定向量检索 + 关键词检索"。

## 5. 当前阶段结论

- auth、upload、ingestion、qa/retrieval、group 五个阶段已全部收口，主讲点和能力边界清晰。
- ingestion 主链路：上传事务 → 事件驱动异步 ETL → 状态化失败收口 → chunk 资产化 → 向量写入。关键词检索通过 PG `pg_trgm` 索引自动生效。
- parser：简单工厂 + 策略模式；切片：结构感知分层切片。
- retrieval 体系：查询规划 → 双通道混合检索（pgvector + pg_trgm）→ RRF 融合 → 窗口扩展 → 证据评估 → 生成回答。
- Chat 模型：Kimi（月之暗面），Embedding：DashScope text-embedding-v3。
- 权限模型：群组级角色控制，OWNER / MEMBER 两级，收口在 `GroupMembershipService`。

## 6. 下一步优先级

- 六个主模块已全部过完：auth → upload → ingestion → qa/retrieval → group → （E2E 改造）。
- 下一步建议：
  1. 如果继续深度学习，可以梳理 assistant（AI 助手）模块——ReactAgent、短期记忆、会话管理。
  2. 如果需要收口面试材料，可以开始整理主链路讲稿和追问口径。

> 阶段性结论：项目主链路已全部过完，后续可选 assistant 模块或直接进入面试材料整理阶段。

## 7. 新对话接手建议

- 六个主模块已全部过完，后续可选：
  - 继续深入 assistant（ReactAgent / 记忆 / 会话管理）
  - 进入面试材料整理阶段（讲稿 / 追问 / README 口径）
- 优先以当前态模块文档为准；带 `V1.0`、`V2.0` 的文档默认视为历史阶段记录。
