# Argus 知识库平台技术架构

## 整体架构

Argus 采用前后端分离架构，后端基于 Spring Boot 3.5，前端基于 Vue 3。

### 后端分层

后端代码按功能模块分为以下层次：

#### 1. 认证层（auth）

负责用户认证和授权。基于 JWT 双令牌机制实现：

- Access Token：JWT 无状态令牌，有效期 30 分钟
- Refresh Token：存储在数据库中的一次性令牌，有效期 14 天，支持重放检测
- 使用 BCryptPasswordEncoder 进行密码加密

#### 2. 用户层（user）

管理用户基本信息，包括用户名、邮箱、显示名称等。支持用户注册、信息修改和密码变更。

#### 3. 群组层（group）

实现知识库群组的创建和管理。群组内角色分为 OWNER 和 MEMBER 两级。支持两种入组方式：

- OWNER 发起邀请（生成邀请码）
- 用户主动申请加入（按 groupCode）

#### 4. 文档层（document）

处理文档上传、预览、下载和删除。上传支持直接上传和分片上传两种模式：

- 分片上传：三阶段协议（init → chunk → complete）
- 秒传检测：通过 SHA-256 文件哈希比较
- 断点续传：记录已上传分片列表

上传完成后通过 Spring Event 触发异步 ETL。

#### 5. 摄入层（ingestion）

实现文档的异步解析与向量化。使用简单工厂 + 策略模式选择解析器：

- TXT 解析器：直接读取文本
- MD 解析器：解析 Markdown 格式
- PDF 解析器：基于 Apache PDFBox
- DOCX 解析器：基于 Apache POI

切片采用结构感知分层策略：先按标题切分，再按段落切分，最后按固定长度切分，支持 32 字符的 overlap。

#### 6. 问答层（qa）

实现 RAG 问答的核心逻辑：

- 查询规划：LLM 分析问题，选择 DIRECT、REWRITE 或 DECOMPOSE 策略
- 混合检索：pgvector 向量检索 + pg_trgm 关键词检索双通道并行
- RRF 融合排序：按排名倒数累加排序
- 邻居窗口扩展：补充命中 chunk 前后的上下文
- 证据评估：四级充分度（NONE/WEAK/PARTIAL/SUFFICIENT）
- 回答生成：调用 Kimi moonshot-v1-8k 模型生成带引用的回答

### 数据存储

- PostgreSQL 16：主数据库
- pgvector 扩展：向量检索（HNSW 索引，512 维，cosine_distance）
- pg_trgm 扩展：关键词模糊匹配
- MinIO：文档文件的对象存储

### AI 模型

- Chat 模型：Kimi moonshot-v1-8k（通过 OpenAI 兼容接口）
- Embedding 模型：DashScope text-embedding-v3（512 维向量）

## 关键技术决策

### 为什么选择 PostgreSQL 而非 Elasticsearch？

在 V2.0 阶段，关键词检索使用 Elasticsearch 8.x（IK 分词 + BM25 打分）。在 V4.0 阶段，将检索引擎统一为 PostgreSQL：

- 减少中间件依赖，部署更简单
- 向量检索（pgvector）和关键词检索（pg_trgm）使用同一数据库
- PgVector 支持 HNSW 索引，检索性能良好
- 面试可讲"一套 PG 搞定向量检索 + 关键词检索"

### 为什么选择 Kimi 而非其他模型？

切换经历：DashScope（通义千问）→ Kimi kimi-k2.6（思考模式导致延迟过高）→ Kimi moonshot-v1-8k（最终选择）。

moonshot-v1-8k 响应速度快，8K 上下文对 RAG 问答场景足够使用。
