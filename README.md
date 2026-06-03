<div align="center">

<img src="https://img.shields.io/badge/Java-21-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white" alt="Java 21"/>
<img src="https://img.shields.io/badge/Spring_Boot-3.5-6DB33F?style=for-the-badge&logo=springboot&logoColor=white" alt="Spring Boot 3.5"/>
<img src="https://img.shields.io/badge/Vue-3.5-4FC08D?style=for-the-badge&logo=vuedotjs&logoColor=white" alt="Vue 3.5"/>
<img src="https://img.shields.io/badge/PostgreSQL-16-4169E1?style=for-the-badge&logo=postgresql&logoColor=white" alt="PostgreSQL 16"/>

</div>

<br/>

<h1 align="center">Argus — RAG 知识库问答平台</h1>

<p align="center">
  <strong>Spring Boot + Vue 3 全栈项目 · 文档智能解析 · 混合检索 · 引用溯源</strong>
</p>

<p align="center">
  <a href="#-项目简介">项目简介</a> ·
  <a href="#-核心流程">核心流程</a> ·
  <a href="#-技术栈">技术栈</a> ·
  <a href="#-项目结构">项目结构</a> ·
  <a href="#-快速开始">快速开始</a> ·
  <a href="#-API-概览">API 概览</a>
</p>

<br/>

---

## 📖 项目简介

Argus 是一个基于 RAG（检索增强生成）技术的知识库问答平台。用户将文档上传到知识库群组后，系统自动完成解析、切片、向量化、索引的全流程处理；之后可以通过自然语言提问，系统基于混合检索召回相关文档内容，结合大语言模型生成带引用溯源的回答。

这是一个**个人全栈项目**，主要目的是系统学习并实践 RAG 工程落地的完整链路，涉及后端工程（Spring Boot、MyBatis-Plus、PostgreSQL）、AI 模型集成（Kimi Chat + DashScope Embedding）、前端交互（Vue 3 + Element Plus）等多个技术领域。

<br/>

## 🔁 核心流程

```
用户上传文档
    ↓
ETL 异步流水线：解析 → 清洗 → 切片 → 向量化 → 索引
    ↓
用户提问 → 查询规划（LLM 改写/拆分） → 混合检索
    ↓                      
    ├── pgvector 向量检索（HNSW + COSINE_DISTANCE）
    └── pg_trgm 关键词检索（三字符组相似度）
    ↓
RRF 融合排序 → 证据评估 → LLM 生成回答 → 返回引用溯源
```

### 关键设计

| 环节 | 做法 | 说明 |
|------|------|------|
| 文档上传 | 三段式分片（init → chunk → complete） | 支持断点续传、SHA-256 秒传检测 |
| 异步摄入 | Spring Event + `@Async` + `@Retryable` | 事件驱动，7 步自动化流水线 |
| 文档解析 | 简单工厂 + 策略模式 | PDF（PDFBox）、DOCX（POI）、MD/TXT |
| 切片策略 | 结构感知分层切片 | 标题 → 段落 → 句子 → 固定长度，overlap 32 |
| **混合检索** | **pgvector + pg_trgm 双通道** | 一套 PostgreSQL 搞定，无需额外中间件 |
| 排序融合 | RRF（Reciprocal Rank Fusion） | `RRF_K = 0`，按排名倒数累加 |
| 证据评估 | 四级充分度（NONE→WEAK→PARTIAL→SUFFICIENT） | 不足时主动拒答 |
| 查询规划 | LLM 分析问题 → DIRECT/REWRITE/DECOMPOSE | 可配置关闭（`query-planning-enabled`） |

<br/>

## 🛠️ 技术栈

### 后端

| 层次 | 技术 | 用途 |
|------|------|------|
| 语言 | Java 21 | Record 语法、模式匹配 |
| 框架 | Spring Boot 3.5.0 | Spring MVC |
| ORM | MyBatis-Plus | Lambda 查询 |
| 数据库 | PostgreSQL 16 | 主存储 |
| 向量检索 | pgvector（HNSW, 512 维） | 语义相似度搜索 |
| 关键词检索 | pg_trgm | 三字符组模糊匹配 |
| 对象存储 | MinIO | 文档文件持久化 |
| Chat | Kimi（`moonshot-v1-8k`） | OpenAI 兼容接口 |
| Embedding | DashScope `text-embedding-v3` | 512 维向量 |
| 认证 | JJWT（HMAC-SHA256） | JWT 双令牌 |
| 密码 | Spring Security Crypto BCrypt | 自适应哈希 |

### 前端

| 层次 | 技术 |
|------|------|
| 框架 | Vue 3（Composition API）+ TypeScript |
| 构建 | Vite |
| 状态管理 | Pinia |
| UI | Element Plus |
| HTTP | Axios |

<br/>

## 📁 项目结构

```
Argus/
├── Argus-backend/                       # Spring Boot 后端
│   └── src/main/java/com/argus/rag/
│       ├── auth/                        # JWT 双令牌认证
│       ├── user/                        # 用户管理
│       ├── group/                       # 群组 + 角色（OWNER/MEMBER）
│       ├── document/                    # 分片上传 + 预览 + 删除
│       ├── ingestion/                   # ETL 异步流水线
│       │   └── service/pipeline/
│       │       ├── reader/              #   文档读取
│       │       ├── parser/              #   格式解析（PDF/DOCX/MD/TXT）
│       │       └── transformer/         #   清洗 + 切片
│       ├── qa/                          # RAG 问答（检索 + 生成）
│       │   └── rag/                     #   混合检索引擎
│       └── engine/                      # 基础设施适配
│           ├── pgvector/                #   PGvector 检索
│           ├── search/                  #   pg_trgm 关键词检索
│           └── storage/                 #   MinIO 存储
│
├── Argus-frontend/                      # Vue 3 前端
│   └── src/
│       ├── api/                         # API 封装
│       ├── views/                       # 页面
│       ├── stores/                      # Pinia 状态
│       └── components/                  # 公共组件
│
├── docs/                                # 模块文档
└── sql/
    └── schema.sql                       # 建表 DDL
```

<br/>

## 🚀 快速开始

### 环境要求

- JDK 21
- Node.js ≥ 20
- PostgreSQL 16+（须安装 `pgvector` 和 `pg_trgm` 扩展）
- MinIO（可选，可配置关闭）

### 1️⃣ 数据库初始化

```bash
psql -h <host> -U <user> -d <database> -c "CREATE EXTENSION IF NOT EXISTS vector;"
psql -h <host> -U <user> -d <database> -c "CREATE EXTENSION IF NOT EXISTS pg_trgm;"
psql -h <host> -U <user> -d <database> -f sql/schema.sql
```

### 2️⃣ 配置

编辑 `Argus-backend/src/main/resources/application-local.yml`：

```yaml
datasource:
  url: jdbc:postgresql://localhost:5432/argus
  username: your_username
  password: your_password

chat:
  api-key: your_kimi_api_key
  base-url: https://api.moonshot.cn/v1
  model: moonshot-v1-8k

embedding:
  api-key: your_dashscope_api_key
  base-url: https://dashscope.aliyuncs.com/compatible-mode

minio:
  endpoint: http://localhost:9000
  access-key: minioadmin
  secret-key: minioadmin
  bucket: argus-rag-documents

rag:
  auth:
    jwt-secret: your_jwt_secret_key
```

### 3️⃣ 启动

```bash
# 后端
cd Argus-backend
mvn spring-boot:run        # 端口 10001

# 前端
cd Argus-frontend
npm install
npm run dev                # 端口 5173
```

开发环境（`--spring.profiles.active=dev`）自动创建管理员：`admin` / `admin123`

<br/>

## 📡 API 概览

### 认证 · `/api/auth`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/auth/register` | 注册 |
| POST | `/api/auth/login` | 登录 |
| POST | `/api/auth/refresh` | 刷新令牌 |
| POST | `/api/auth/logout` | 登出 |
| GET | `/api/auth/me` | 当前用户 |

### 群组 · `/api/groups`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/groups` | 创建群组 |
| GET | `/api/groups` | 群组列表 |
| POST | `/api/groups/{id}/invitations` | 创建邀请 |
| POST | `/api/groups/{id}/join-request` | 申请加入 |
| POST | `/api/groups/invitations/{id}/accept` | 接受邀请 |

### 文档 · `/api/documents`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/documents/upload/init` | 初始化分片上传 |
| POST | `/api/documents/upload/chunks` | 上传分片 |
| POST | `/api/documents/upload/{id}/complete` | 完成上传，触发 ETL |
| POST | `/api/documents/upload` | 小文件直传（≤10MB） |
| GET | `/api/documents` | 文档列表 |
| GET | `/api/documents/{id}/preview` | 预览 |
| DELETE | `/api/documents/{id}` | 软删除 |

### 问答 · `/api/qa`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/qa/ask` | 提交问题，获取 AI 回答 + 引用 |
| GET | `/api/qa/stream-ask`（SSE） | 流式问答 |

<details>
<summary><b>请求/响应示例</b></summary>

```json
// POST /api/qa/ask
// Request
{ "groupId": 1, "question": "文档上传流程是什么？" }

// Response
{
  "answered": true,
  "answer": "文档上传分为三个阶段：初始化、分片上传、完成合并...",
  "citations": [
    {
      "documentId": 1,
      "chunkId": 15,
      "fileName": "使用手册.pdf",
      "score": 0.97,
      "snippet": "上传流程包括三个步骤：首先调用初始化接口获取上传会话 ID..."
    }
  ]
}
```
</details>

<br/>

## 📖 文档

各模块的详细说明在 [`docs/`](docs/) 目录下，包括模块链路解析、架构设计决策等。

<br/>

---

<p align="center">
  个人项目 · 欢迎交流
</p>
