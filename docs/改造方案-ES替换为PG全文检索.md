# 改造方案：将 Elasticsearch 关键词检索替换为 PostgreSQL 全文检索

> 目标：移除 Elasticsearch 依赖，用 PostgreSQL 内置 `pg_trgm` 扩展实现关键词检索，
> 保留"向量检索 + 关键词检索 → RRF 融合"的混合检索能力，降低面试与部署复杂度。

---

## 1. 现状分析

### 1.1 ES 在项目中的角色

ES 目前承担两个职责：

| 职责 | 代码位置 | 方法 |
|------|---------|------|
| 写入索引 | `DocumentIngestionAsyncService.syncSearchIndex()` | `elasticsearchChunkIndexService.indexReadyChunks()` |
| 清理索引 | `DocumentIngestionAsyncService.cleanupProcessingArtifacts()` | `elasticsearchChunkIndexService.deleteDocumentChunks()` |
| 清理索引 | `DocumentUploadService.compensateExternalIndexes()` | `elasticsearchChunkIndexService.deleteDocumentChunks()` |
| 清理索引 | `DocumentDeleteService.softDeleteDocument()` | `elasticsearchChunkIndexService.deleteDocumentChunks()` |
| 关键词检索 | `HybridChunkRetrievalService.mergeKeywordHits()` | `elasticsearchChunkIndexService.search()` |

### 1.2 ES 检索逻辑详解

当前 ES 检索 DSL（`buildKeywordSearchRequestBody`）：

```
filter 层:  groupId = X AND status = READY AND deleted = false
should 层:  fileName (match_phrase boost 8, match boost 4)
           + chunkText (match_phrase boost 6, match boost 3)
rescore 层: 同样的 should 子句，match 加 operator=and 精排
结果:       topK 条，按 _score 降序
分数归一化:  normalizeKeywordScore = min(1, log1p(rawScore) / log1p(100))
```

### 1.3 关键数据结构：KeywordHit

```java
public record KeywordHit(
    Long documentId,      // 文档ID
    Long chunkId,         // 切片ID
    Integer chunkIndex,   // 切片序号
    String fileName,      // 文件名
    String chunkText,     // 切片文本
    double rawScore,      // 原始分数
    double normalizedScore // 归一化分数 [0, 1]
) {}
```

### 1.4 关键：KeywordHit 在 HybridChunkRetrievalService 中的消费方式

```java
// RetrievalCandidate.fromKeywordHit() —— 只用了 documentId, chunkId, chunkIndex
static RetrievalCandidate fromKeywordHit(KeywordHit hit) {
    return new RetrievalCandidate(hit.documentId(), hit.chunkId(), hit.chunkIndex());
}

// RetrievalCandidate.mergeKeywordHit() —— 用了 normalizedScore
void mergeKeywordHit(KeywordHit hit, int rank) {
    this.keywordMatched = true;
    this.keywordScore = Math.max(this.keywordScore, hit.normalizedScore());
    this.rankingScore += reciprocalRank(rank);
}
```

**结论**：新的检索服务只要能返回 `documentId + chunkId + chunkIndex + fileName + chunkText + normalizedScore`，就能无缝接入现有 RRF 融合流程。

---

## 2. 替换方案设计

### 2.1 技术选型：PostgreSQL `pg_trgm`

**为什么不选 `tsvector`**：
- `tsvector` 依赖分词器，中文需要 `zhparser` 等额外扩展
- `zhparser` 安装复杂，不是 PG 内置扩展

**为什么选 `pg_trgm`**：
- **PG 内置扩展**，`CREATE EXTENSION IF NOT EXISTS pg_trgm;` 一行搞定
- **任意语言通用**（中文、英文都支持），基于三字符组（trigram）相似度
- `similarity()` 函数返回 [0, 1]，天然归一化，不需要额外分数转换
- 支持 GIN 索引加速查询

### 2.2 架构变化

```
改造前:
  向量检索 → PGvector
  关键词检索 → ES (独立部署)
  ↓ RRF 融合

改造后:
  向量检索 → PGvector (同一PG实例)
  关键词检索 → PG pg_trgm (同一PG实例)
  ↓ RRF 融合
```

### 2.3 命中文件清单

| 操作类型 | 文件 | 说明 |
|----------|------|------|
| ➕ 新增 | `engine/search/PgKeywordSearchService.java` | PG 关键词检索服务 |
| ➕ 新增 | `engine/search/KeywordHit.java` | 关键词命中记录（从 ES 包迁出） |
| ✏️ 修改 | `qa/rag/HybridChunkRetrievalService.java` | 将 `ElasticsearchChunkIndexService` 替换为 `PgKeywordSearchService` |
| ✏️ 修改 | `ingestion/service/DocumentIngestionAsyncService.java` | 移除 `syncSearchIndex()`，简化 `cleanupProcessingArtifacts()` |
| ✏️ 修改 | `document/service/DocumentUploadService.java` | 移除 `compensateExternalIndexes()` 中的 ES 调用 |
| ✏️ 修改 | `document/service/DocumentDeleteService.java` | 移除 ES 删除调用 |
| ✏️ 修改 | `application-dev.yml` | 移除 `elasticsearch` 配置块 |
| ✏️ 修改 | `sql/schema.sql` | 添加 `pg_trgm` 扩展 + 索引 |
| 🗑️ 删除 | `engine/elasticsearch/ElasticsearchChunkIndexService.java` | 整个文件 |
| 🗑️ 删除 | `engine/elasticsearch/` 包（如果只剩这一个文件） | 清理空包 |
| 🗑️ 删除 | `deploy/docker/elasticsearch/` | ES Docker 配置 |

---

## 3. 详细改造步骤

### 第一阶段：数据库准备

#### Step 1: 启用 pg_trgm 扩展

连接到 PostgreSQL 数据库，执行：

```sql
-- 启用 pg_trgm 扩展（需要超级用户权限，通常在创建数据库时已做）
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 在 document_chunks 表的 chunk_text 列上创建 GIN 索引
CREATE INDEX IF NOT EXISTS idx_chunk_text_trgm 
    ON document_chunks 
    USING gin (chunk_text gin_trgm_ops);
```

#### Step 2: 更新 schema.sql

在 `sql/schema.sql` 文件末尾（或 `document_chunks` 建表语句附近）添加：

```sql
-- 启用 pg_trgm 扩展，用于文档切片关键词检索
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 文档切片关键词检索索引（三字符组相似度）
CREATE INDEX IF NOT EXISTS idx_chunk_text_trgm 
    ON document_chunks 
    USING gin (chunk_text gin_trgm_ops);
```

建议插在 `idx_chunk_group` 索引定义之后：

```sql
-- 索引：按群组+状态查询可向量化的切片
CREATE INDEX IF NOT EXISTS idx_chunk_group ON document_chunks (group_id);

-- 索引：关键词检索（pg_trgm 三字符组相似度匹配）
CREATE INDEX IF NOT EXISTS idx_chunk_text_trgm 
    ON document_chunks 
    USING gin (chunk_text gin_trgm_ops);
```

---

### 第二阶段：新建 PG 检索服务

#### Step 3: 创建 `PgKeywordSearchService`

新建文件路径：
```
Argus-backend/src/main/java/com/argus/rag/engine/search/PgKeywordSearchService.java
```

同时将 `KeywordHit` record 从 `ElasticsearchChunkIndexService` 迁出，作为独立类或内嵌 record。

完整代码：

```java
package com.argus.rag.engine.search;

import com.argus.rag.ingestion.mapper.DocumentChunkMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 基于 PostgreSQL pg_trgm 扩展的关键词检索服务。
 *
 * <p>替代原有的 {@code ElasticsearchChunkIndexService}，使用三字符组（trigram）
 * 相似度匹配实现关键词检索，无需额外的搜索引擎基础设施。
 *
 * <h3>检索流程</h3>
 * <ol>
 *   <li>使用 {@code similarity(chunk_text, query)} 计算相似度</li>
 *   <li>JOIN documents 表过滤：status=READY 且 deleted=false</li>
 *   <li>按相似度降序排列，取 topK 条</li>
 *   <li>相似度即为归一化分数 [0, 1]，无需额外转换</li>
 * </ol>
 *
 * <h3>降级策略</h3>
 * <p>检索失败时记录 WARN 日志并返回空列表，保证 QA 主链路不中断。</p>
 *
 * @author Argus-RAG Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PgKeywordSearchService {

    private final DocumentChunkMapper documentChunkMapper;

    /**
     * 执行关键词检索，返回与问题文本最相似的 topK 个文档切片。
     *
     * @param groupId  群组 ID
     * @param question 检索关键词 / 问题文本
     * @param topK     返回的最大结果数
     * @return 关键词匹配的切片列表（含归一化分数），无结果或失败时返回空列表
     */
    public List<KeywordHit> search(Long groupId, String question, int topK) {
        if (groupId == null || groupId <= 0 || !StringUtils.hasText(question) || topK <= 0) {
            return List.of();
        }
        long startNano = System.nanoTime();
        try {
            List<Map<String, Object>> rows = documentChunkMapper.searchByKeywordSimilarity(
                    groupId, question, topK);
            List<KeywordHit> hits = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                Map<String, Object> row = rows.get(i);
                hits.add(new KeywordHit(
                        toLong(row.get("documentId")),
                        toLong(row.get("chunkId")),
                        toInt(row.get("chunkIndex")),
                        toString(row.get("fileName")),
                        toString(row.get("chunkText")),
                        toDouble(row.get("similarity")),
                        toDouble(row.get("similarity"))  // pg_trgm 相似度已是 [0,1]，直接作为归一化分数
                ));
            }
            long elapsedMs = (System.nanoTime() - startNano) / 1_000_000;
            log.info("PG关键词检索完成: groupId={}, topK={}, hitCount={}, elapsedMs={}",
                    groupId, topK, hits.size(), elapsedMs);
            return List.copyOf(hits);
        } catch (RuntimeException exception) {
            long elapsedMs = (System.nanoTime() - startNano) / 1_000_000;
            log.warn(
                    "PG关键词检索失败，降级为空结果: groupId={}, question='{}', elapsedMs={}, reason={}",
                    groupId,
                    abbreviate(question),
                    elapsedMs,
                    exception.getMessage()
            );
            return List.of();
        }
    }

    private long toLong(Object value) {
        if (value instanceof Number num) return num.longValue();
        return 0L;
    }

    private int toInt(Object value) {
        if (value instanceof Number num) return num.intValue();
        return 0;
    }

    private double toDouble(Object value) {
        if (value instanceof Number num) return num.doubleValue();
        return 0D;
    }

    private String toString(Object value) {
        return value == null ? "" : value.toString();
    }

    private String abbreviate(String text) {
        if (!StringUtils.hasText(text)) return "";
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120) + "...";
    }

    /**
     * PG 关键词检索的命中结果记录。
     *
     * <p>不可变 record，可安全用于下游排序、融合和 LLM 上下文组装。
     */
    public record KeywordHit(
            Long documentId,
            Long chunkId,
            Integer chunkIndex,
            String fileName,
            String chunkText,
            double rawScore,
            double normalizedScore
    ) {
    }
}
```

#### Step 4: 在 `DocumentChunkMapper` 中新增检索方法

文件：`Argus-backend/src/main/java/com/argus/rag/ingestion/mapper/DocumentChunkMapper.java`

在接口末尾添加：

```java
    /**
     * 基于 pg_trgm 相似度匹配的关键词检索。
     *
     * <p>对 chunk_text 做 trigram 相似度计算，JOIN documents 表确保只检索
     * 已摄入完成且未删除的文档切片。</p>
     *
     * @param groupId  群组 ID
     * @param query    检索关键词
     * @param limit    最大返回数
     * @return 命中切片列表（含相似度分数 similarity）
     */
    List<Map<String, Object>> searchByKeywordSimilarity(
            @Param("groupId") Long groupId,
            @Param("query") String query,
            @Param("limit") int limit
    );
```

#### Step 5: 在 MyBatis XML 中添加检索 SQL

文件：`Argus-backend/src/main/resources/mappers/ingestion/DocumentChunkMapper.xml`

在 `</mapper>` 闭合标签前添加：

```xml
    <select id="searchByKeywordSimilarity" resultType="map">
        select chunks.id              as "chunkId",
               chunks.document_id     as "documentId",
               chunks.chunk_index     as "chunkIndex",
               chunks.chunk_text      as "chunkText",
               documents.file_name    as "fileName",
               similarity(chunks.chunk_text, #{query}) as "similarity"
        from document_chunks chunks
                 join documents documents
                      on documents.id = chunks.document_id
                          and documents.group_id = chunks.group_id
        where chunks.group_id = #{groupId}
          and documents.status = 'READY'
          and documents.deleted = false
          and similarity(chunks.chunk_text, #{query}) > 0
        order by similarity(chunks.chunk_text, #{query}) desc
        limit #{limit}
    </select>
```

SQL 关键点说明：
- `similarity()` 是 `pg_trgm` 扩展提供的函数，返回 [0, 1] 的三字符组相似度
- `similarity(...) > 0` 作为 WHERE 条件过滤掉完全不相关的切片
- JOIN `documents` 表过滤 `status = 'READY' AND deleted = false`，确保只检索有效文档
- GIN 索引 `idx_chunk_text_trgm` 会加速此查询

---

### 第三阶段：修改现有代码

#### Step 6: 修改 `HybridChunkRetrievalService`

文件：`Argus-backend/src/main/java/com/argus/rag/qa/rag/HybridChunkRetrievalService.java`

**6.1 替换 import**

```java
// 删除这行
import com.argus.rag.engine.elasticsearch.ElasticsearchChunkIndexService;

// 新增这行
import com.argus.rag.engine.search.PgKeywordSearchService;
```

**6.2 替换字段声明**

```java
// 改为
private final PgKeywordSearchService pgKeywordSearchService;
```

**6.3 替换构造函数参数**

找到构造函数中的 `ElasticsearchChunkIndexService elasticsearchChunkIndexService` 参数，改为 `PgKeywordSearchService pgKeywordSearchService`，并同步修改赋值语句。

**6.4 修改 `mergeKeywordHits` 方法**

```java
// 将
List<ElasticsearchChunkIndexService.KeywordHit> keywordHits = elasticsearchChunkIndexService.search(groupId,
        query, CHANNEL_TOP_K);

// 改为
List<PgKeywordSearchService.KeywordHit> keywordHits = pgKeywordSearchService.search(groupId,
        query, CHANNEL_TOP_K);
```

**6.5 修改 `fromKeywordHit` 和 `mergeKeywordHit` 中的类型引用**

```java
// 将 ElasticsearchChunkIndexService.KeywordHit
// 改为 PgKeywordSearchService.KeywordHit
static RetrievalCandidate fromKeywordHit(PgKeywordSearchService.KeywordHit hit) {
    return new RetrievalCandidate(hit.documentId(), hit.chunkId(), hit.chunkIndex());
}

void mergeKeywordHit(PgKeywordSearchService.KeywordHit hit, int rank) {
    this.keywordMatched = true;
    this.keywordScore = Math.max(this.keywordScore, hit.normalizedScore());
    this.rankingScore += reciprocalRank(rank);
}
```

#### Step 7: 修改 `DocumentIngestionAsyncService`

文件：`Argus-backend/src/main/java/com/argus/rag/ingestion/service/DocumentIngestionAsyncService.java`

**7.1 移除 ES 相关 import 和字段**

```java
// 删除
import com.argus.rag.engine.elasticsearch.ElasticsearchChunkIndexService;

// 删除字段
private final ElasticsearchChunkIndexService elasticsearchChunkIndexService;
```

**7.2 简化 `cleanupProcessingArtifacts` 方法**

```java
private void cleanupProcessingArtifacts(Long documentId) {
    log.info("开始清理上次处理中间产物: documentId={}", documentId);
    try {
        documentChunkMapper.deleteByDocumentId(documentId);
    } catch (RuntimeException exception) {
        log.warn("清理旧 chunk 失败: documentId={}, reason={}", documentId, exception.getMessage());
    }
    try {
        vectorIngestionService.deleteDocumentVectors(documentId);
    } catch (RuntimeException exception) {
        log.warn("清理旧向量失败: documentId={}, reason={}", documentId, exception.getMessage());
    }
    log.info("中间产物清理完成: documentId={}", documentId);
}
```

**7.3 删除 `syncSearchIndex` 方法**

整个 `syncSearchIndex()` 方法删除——因为切片数据已经在 `document_chunks` 表中，`pg_trgm` 索引会自动生效，不需要额外的同步步骤。

**7.4 修改 `ingestDocument` 方法**

删除 `syncSearchIndex(document);` 这一行调用：

```java
@Transactional
public void ingestDocument(Long documentId, Long groupId) {
    DocumentEntity document = requireDocument(documentId, groupId);
    log.info("开始异步执行文档ETL: documentId={}, groupId={}", documentId, groupId);
    cleanupProcessingArtifacts(documentId);
    documentIngestionProcessor.process(documentId, groupId);
    // syncSearchIndex(document);  ← 删除这行
    markDocumentStatus(documentId, groupId, DocumentStatus.READY.name(), null, LocalDateTime.now());
    log.info("异步文档ETL完成: documentId={}, groupId={}, status={}", documentId, groupId, DocumentStatus.READY.name());
}
```

**7.5 更新类注释**

将类注释中的 `<li>将分块索引同步至 Elasticsearch</li>` 删除。

#### Step 8: 修改 `DocumentDeleteService`

文件：`Argus-backend/src/main/java/com/argus/rag/document/service/DocumentDeleteService.java`

**8.1 移除 ES 相关 import 和字段**

```java
// 删除
import com.argus.rag.engine.elasticsearch.ElasticsearchChunkIndexService;

// 删除字段
private final ElasticsearchChunkIndexService elasticsearchChunkIndexService;
```

**8.2 修改 `softDeleteDocument` 方法**

删除 `elasticsearchChunkIndexService.deleteDocumentChunks(documentId);` 调用。由于 chunks 数据本身在 PG 中，文档软删除后通过 `documents.deleted = true` 已经在检索 SQL 中被过滤掉，不需要额外清理。

```java
public void softDeleteDocument(Long groupId, Long documentId) {
    requireGroupId(groupId);
    groupMembershipService.requireGroupOwner(groupId);
    if (documentId == null || documentId <= 0) {
        throw new BusinessException("文档ID非法");
    }
    log.info("开始软删除文档: groupId={}, documentId={}", groupId, documentId);
    if (documentMapper.markDeleted(documentId, groupId) == 0) {
        throw new BusinessException("文档不存在或已删除");
    }
    vectorIngestionService.deleteDocumentVectors(documentId);
    // elasticsearchChunkIndexService.deleteDocumentChunks(documentId);  ← 删除这行
    log.info("文档软删除完成: groupId={}, documentId={}", groupId, documentId);
}
```

#### Step 9: 修改 `DocumentUploadService`

文件：`Argus-backend/src/main/java/com/argus/rag/document/service/DocumentUploadService.java`

**9.1 移除 ES 相关字段和 import**

```java
// 删除
private final com.argus.rag.engine.elasticsearch.ElasticsearchChunkIndexService elasticsearchChunkIndexService;
```

**9.2 修改 `compensateExternalIndexes` 方法**

删除 ES 补偿调用块：

```java
private void compensateExternalIndexes(DocumentEntity document) {
    if (document == null || document.getId() == null) return;
    try {
        vectorIngestionService.deleteDocumentVectors(document.getId());
    } catch (RuntimeException exception) {
        log.warn("文档失败补偿时删除向量失败: documentId={}, reason={}",
                document.getId(), exception.getMessage());
    }
    // 删除以下 try 块（ES 补偿不再需要）
    // try {
    //     elasticsearchChunkIndexService.deleteDocumentChunks(document.getId());
    // } catch (RuntimeException exception) {
    //     log.warn("文档失败补偿时删除 ES 索引失败: documentId={}, reason={}",
    //             document.getId(), exception.getMessage());
    // }
}
```

#### Step 10: 清理配置文件

文件：`Argus-backend/src/main/resources/application-dev.yml`

删除整个 `elasticsearch` 配置块：

```yaml
# 删除以下几行：
elasticsearch:
  host: ${ES_HOST:localhost}
  port: ${ES_PORT:9200}
  scheme: ${ES_SCHEME:http}
  index-name: dd_rag_document_chunks
```

---

### 第四阶段：清理旧代码

#### Step 11: 删除 ES 服务类

删除文件：
```
Argus-backend/src/main/java/com/argus/rag/engine/elasticsearch/ElasticsearchChunkIndexService.java
```

如果 `engine/elasticsearch/` 包下没有其他文件，删除整个目录。

#### Step 12: 清理 ES Docker 配置（可选）

如果不再需要 ES 容器：
```
删除 deploy/docker/elasticsearch/ 目录
```

---

## 4. 改造后验证

### 4.1 编译验证

```bash
cd Argus-backend
./mvnw clean compile
```

预期：编译通过，无 `ElasticsearchChunkIndexService` 相关的未解析引用错误。

### 4.2 数据库验证

```sql
-- 确认扩展已安装
SELECT * FROM pg_extension WHERE extname = 'pg_trgm';

-- 确认索引已创建
SELECT indexname FROM pg_indexes WHERE tablename = 'document_chunks' AND indexname = 'idx_chunk_text_trgm';

-- 手动测试相似度查询
SELECT similarity('今天天气怎么样', '今天的天气非常好');
-- 预期返回 0 到 1 之间的数值
```

### 4.3 功能验证

1. **上传一个文档** → 等待 ingestion 完成 → 确认不再报 ES 连接错误
2. **在 QA 页面提问** → 确认能正常返回结果，引用正常显示
3. **删除文档** → 确认不会报 ES 相关异常

### 4.4 检索效果对比（建议）

改造前后用同一个问题测试，对比返回的引用列表：
- 向量检索结果理论上不变（同套 embedding）
- 关键词检索结果可能略有差异，但 `pg_trgm` 的 trigram 匹配对中文友好，效果通常可接受
- RRF 融合后如果向量路稳定，最终结果影响很小

---

## 5. 改造优势总结

| 维度 | 改造前 (ES) | 改造后 (PG pg_trgm) |
|------|-----------|-------------------|
| 基础设施 | ES 独立部署 | 零新增，复用 PG |
| 面试复杂度 | 需解释 ES + IK 分词 + 集群 | 一句话：PG 内置 trigram |
| 数据一致性 | 双写可能不一致 | 同一数据库，天然一致 |
| 分数归一化 | `log1p(x)/log1p(100)` 手动计算 | `similarity()` 直接返回 [0,1] |
| 写入链路 | 同步 ES 索引（额外步骤） | 无需额外写入（索引自动生效） |
| 配置项 | `elasticsearch.host/port/scheme/index-name` 4项 | 0 项 |
| Java 代码量 | `ElasticsearchChunkIndexService` 约 600 行 | `PgKeywordSearchService` 约 100 行 |

---

## 6. 面试讲法

改造完成后，检索链路的讲法：

> "混合检索走双通道——向量路由 PGvector 做余弦相似度检索，关键词路用 PostgreSQL 内置的 `pg_trgm` 做 trigram 相似度匹配。pg_trgm 对中英文都有效，相似度天然在 [0,1] 之间，两条路的命中去重后按 RRF 公式融合排序。整套检索只需要一个 PostgreSQL 实例，不需要额外搜索引擎。"

---

## 7. 备注

- 如果未来数据量增长到百万级以上，`pg_trgm` 的 GIN 索引可能不如 ES 的 BM25 高效。届时可以在 `PgKeywordSearchService` 中增加缓存层或考虑引入更专业的搜索引擎。对于当前项目规模，`pg_trgm` 完全够用。
- `pg_trgm` 的 `similarity()` 函数不区分中英文分词，对混合语言文档也友好。
