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

- auth、upload、ingestion 阶段已收口，主讲点和能力边界清晰。
- ingestion 主链路：上传事务 → 事件驱动异步 ETL → 状态化失败收口 → chunk 资产化 → 向量写入。关键词检索通过 PG `pg_trgm` 索引自动生效。
- parser：简单工厂 + 策略模式；切片：结构感知分层切片。
- retrieval 体系：双通道混合检索（pgvector 向量 + pg_trgm 关键词）→ RRF 融合。

## 6. 下一步优先级

- ES→PG 改造已完成，retrieval 体系已稳定为最终态。
- 下一步建议：
  1. 继续梳理 retrieval（检索/召回）主链路——基于最终态代码理解 `HybridChunkRetrievalService` + `PgKeywordSearchService`。
  2. 阅读 `docs/qa/问答模块整体流程与代码定位.md`，串联"上传→摄入→检索→问答"全链路。
  3. 梳理前端问答主链路（`QaView.vue` + SSE 流式输出）。
  4. 如需面试/讲解，优先准备 ingestion + retrieval 的主链路讲稿和追问口径。

> 阶段性结论：ingestion 端已收口，检索体系已稳定，retrieval 是下一个主线。

## 7. 新对话接手建议

- 默认从 QA/retrieval 相关代码继续，不必回到 auth/upload/ingestion 细节。
- 优先以当前态模块文档为准；带 `V1.0`、`V2.0` 的文档默认视为历史阶段记录。
- retrieval 表达以"双通道混合检索（pgvector + pg_trgm）、RRF 融合、查询规划、证据评估"为主。
- 参考 `docs/qa/问答模块整体流程与代码定位.md` 作为 QA 模块入口。
- 参考 `docs/改造方案-ES替换为PG全文检索.md` 理解检索体系变更。
