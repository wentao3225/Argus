# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Argus is a RAG (Retrieval-Augmented Generation) knowledge platform — Spring Boot backend + Vue 3 frontend. Users upload documents into groups, an async ETL pipeline processes them (parse → chunk → vectorize → index), then a hybrid retrieval engine (vector + keyword) powers Q&A and an AI assistant (ReactAgent).

## Build & Run Commands

```bash
# Set JDK 21 (required — record syntax, virtual threads)
export JAVA_HOME="D:\Develop\DevelopTool\StudyEnvironment\PhpWebStudy-Data\app\openjdk-21.0.9"

# ── Backend (from Argus-backend/) ──
cd Argus-backend && ./mvnw clean compile        # compile
./mvnw test                                       # run all tests
./mvnw test -Dtest=ClassName#methodName           # run single test
./mvnw clean package -DskipTests                  # package jar
./mvnw spring-boot:run                            # start (local profile, port 10001)

# ── Frontend (from Argus-frontend/) ──
cd Argus-frontend && npm install                  # install deps
npm run dev                                       # dev server (port 5173, proxies /api → 10001)
npm run build                                     # type-check + production build
npm run lint                                      # oxlint + eslint with auto-fix
```

## Infrastructure Dependencies

| Service | Purpose | Key detail |
|---------|---------|------------|
| PostgreSQL 16+ | Relational store + pgvector | Requires `CREATE EXTENSION vector;` — schema at `sql/schema.sql` |
| Elasticsearch 8.x | Keyword search (IK tokenizer) | Two-phase bool + rescore BM25 scoring |
| MinIO | S3-compatible object storage | Conditional via `@ConditionalOnProperty`, can be disabled |
| DashScope (Alibaba) | LLM Chat (Qwen) + Embedding (text-embedding-v3, 512-dim) | Also uses OpenAI-compatible API for embedding |

## Backend Architecture

```
com.argus.rag
├── common/              # ApiResponse record, enums, GlobalExceptionHandler, @OperationLog AOP
├── auth/                # JWT dual-token auth (Access + Refresh), JwtAuthenticationFilter
│                        # AuthController: /api/auth/{login,register,refresh,logout,me}
├── user/                # User entity, AccountController (/api/account), AdminUserController (/api/admin/users)
├── group/               # Group CRUD, invitations, join-requests, role management (Owner/Manager/Member)
│                        # Controllers: GroupManagement, GroupQuery, InvitationDecision, GroupJoinRequest
├── document/            # Chunked upload protocol (init→chunk→complete), preview, download, soft-delete
│                        # DocumentController: /api/documents/*
├── ingestion/           # Async ETL pipeline (Spring Event → @Async → @Retryable)
│   └── service/pipeline/
│       ├── reader/      # Document read from MinIO
│       ├── parser/      # Simple factory + strategy: Txt/Md/Pdf/DocxDocumentParser
│       └── transformer/ # StructureAwareChunkTransformer (hierarchical chunking, 240/320/32 tokens)
│   └── vector/          # PGvector + Elasticsearch dual-index writer
├── qa/                  # RAG Q&A: query planning (LLM) → hybrid retrieval → RRF fusion → evidence evaluation
│   └── rag/             # VectorSearchService, KeywordSearchService, RrfFusionRanker
├── assistant/           # ReactAgent (Spring AI Alibaba) — CHAT / KB_SEARCH modes
│   ├── agent/           # Agent factory + knowledge-base retrieval tool
│   ├── memory/          # 3-level compression: L1 session memory → L2 compact summary → L3 truncation
│   ├── service/         # Chat orchestration, session management, SSE streaming
│   └── support/config/  # Assistant configuration
├── engine/              # Infrastructure adapters
│   ├── elasticsearch/   # ES client (JDK HttpClient, not RestHighLevelClient)
│   ├── pgvector/        # Spring AI vector store integration
│   └── storage/         # MinIO S3 client wrapper
└── metrics/             # LLM call tracking, token/cost accounting
```

## Frontend Architecture

```
Argus-frontend/src/
├── api/                 # Axios HTTP clients: auth, document, group, qa, assistant, metrics, admin-user
├── views/
│   ├── HomeView.vue     # Landing page
│   ├── LoginView.vue    # Login/register
│   ├── documents/       # Document list + upload dialog + filters
│   ├── groups/          # Group cards, join/manage modals, invitations
│   ├── qa/              # Knowledge Q&A (SSE streaming, citation rail)
│   ├── assistant/       # AI assistant (mode switcher, sidebar, SSE stream)
│   ├── admin/           # User management + LLM metrics dashboard
│   └── settings/        # Account password change
├── stores/              # Pinia stores: auth.ts (login state), app.ts (global state)
├── components/          # Shared: LoginModal, DocumentPreviewModal, EmptyState, layout
└── router/              # Vue Router with auth guards
```

## Key Patterns

### API Response & Error Handling
- Every controller returns `ApiResponse<T>` (record: `success`, `data`, `message`)
- Throw `BusinessException`(400), `ForbiddenException`(403), `UnauthorizedException`(401) — mapped by `GlobalExceptionHandler`

### MyBatis-Plus
- All DB access via `BaseMapper` + `LambdaQueryWrapper`; never JdbcTemplate
- Entities use `@TableName` / `@TableId(type = IdType.AUTO)`, no base class
- Complex queries (FOR UPDATE, joins) in `src/main/resources/mappers/**/*.xml`
- Enums stored as VARCHAR via `EnumTypeHandler` (`.name()` strings)

### Auth Flow
- `JwtAuthenticationFilter` (OncePerRequestFilter) extracts Bearer token → sets `AuthenticatedUser` as request attribute
- `CurrentUserService` reads attribute → `CurrentUser` record
- Controllers call `currentUserService.getRequiredCurrentUser(request)` or `requireSystemAdmin(request)`
- Refresh tokens: DB table `user_refresh_tokens`, sent as httpOnly cookie

### Ingestion Pipeline (Event-Driven)
- Document upload completes → fires Spring `ApplicationEvent`
- `@Async` listener picks up event → runs 7-step pipeline: read → parse → clean → chunk → embed → index (PGvector + ES) → mark status
- `@Retryable` with `@Recover` for transient failures
- Parser selection: `DocumentParserFactory` uses simple factory + strategy pattern (extension-based)
- Chunking: `StructureAwareChunkTransformer` — preserves document structure, hierarchical splitting, overlap (32 tokens)

### RAG Retrieval (Hybrid)
- Query planning via LLM: DIRECT / REWRITE / DECOMPOSE strategies
- Dual-channel: PGvector cosine similarity + Elasticsearch BM25
- RRF (Reciprocal Rank Fusion) merges results
- Evidence levels: NONE → WEAK → PARTIAL → SUFFICIENT (insufficient → reject answer)
- Citation tracing: each answer includes source document, chunk ID, relevance score

### AI Assistant (ReactAgent)
- Spring AI Alibaba Agent framework — graph-based execution: think → tool call → respond
- Two modes in same session: CHAT (pure conversation) / KB_SEARCH (retrieval-augmented)
- BEFORE_MODEL hook auto-injects context: compact summary → session memory → recent messages
- SSE streaming: Delta dedup + AGENT_MODEL_FINISHED fallback
- Prompt templates at `src/main/resources/prompts/{qa,assistant,query-planning}/*.st` (StringTemplate format)

## Configuration

- **Active profile**: `local` (default in `application.yml`)
- **Server port**: 10001, context path: `/api`
- **Auth properties prefix**: `ddrag.auth` (maps to `AuthProperties`)
- **MyBatis-Plus XML mappers**: `classpath*:/mappers/**/*.xml`
- **Config files**: `application.yml` (base), `application-dev.yml` (dev profile), `application-local.yml` (local overrides, not in git)

## Dev Environment

- Dev profile (`--spring.profiles.active=dev`) auto-seeds admin: `admin` / `admin123`
- Frontend dev server proxies `/api` to `http://localhost:10001` (configurable via `VITE_DEV_PROXY_TARGET`)
- API docs (Knife4j): `http://localhost:10001/doc.html`
