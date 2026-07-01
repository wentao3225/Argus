# PostgreSQL 索引优化指南

## 1. 索引类型概述

PostgreSQL 支持多种索引类型，每种索引适用于不同的查询场景。选择正确的索引类型是数据库性能优化的第一步。常见的索引类型包括 B-tree、Hash、GiST、SP-GiST、GIN 和 BRIN。

### 1.1 B-tree 索引

B-tree 是 PostgreSQL 默认的索引类型，也是最常用的索引。它适用于等值查询（=）、范围查询（<、<=、>、>=）以及排序操作（ORDER BY）。B-tree 索引支持所有可比较的数据类型，包括整数、字符串、日期等。

B-tree 索引的时间复杂度为 O(log n)，在百万级数据量下通常能在 3-5 次磁盘 I/O 内定位到目标记录。对于高选择性列（如主键、唯一标识符），B-tree 索引的效果最佳。

创建 B-tree 索引的语法：
```sql
CREATE INDEX idx_users_email ON users USING btree (email);
```

当列的基数（不同值的数量）很高时，B-tree 索引的效率最高。例如用户表中的 email 列，几乎每行都有不同的值，此时 B-tree 索引的选择性接近 1。

### 1.2 GIN 索引

GIN（Generalized Inverted Index，通用倒排索引）适用于包含复合值的数据类型，如数组、JSONB、全文搜索向量等。GIN 索引的核心思想是将复合值拆分为多个键，然后为每个键建立倒排列表。

GIN 索引特别适合以下场景：
- JSONB 字段的 `@>`、`?`、`?|`、`?&` 操作符
- 数组字段的 `&&`（重叠）、`@>`（包含）操作符
- 全文搜索的 `@@` 操作符
- pg_trgm 扩展的 `LIKE` 和正则匹配查询

```sql
-- 为 JSONB 字段创建 GIN 索引
CREATE INDEX idx_metadata_gin ON documents USING gin (metadata);

-- 为全文搜索创建 GIN 索引
CREATE INDEX idx_content_fts ON chunks USING gin (to_tsvector('chinese', content));
```

GIN 索引的更新开销比 B-tree 大，因为每次插入或更新都需要修改倒排列表。PostgreSQL 提供了 `fastupdate` 参数来优化批量插入的性能，但在高写入场景下仍需注意性能影响。

### 1.3 GiST 索引

GiST（Generalized Search Tree，通用搜索树）是一种平衡树结构的索引，支持多种数据类型的近似匹配和范围查询。GiST 索引常用于地理空间数据（PostGIS）、范围类型（range types）和全文搜索。

GiST 索引的一个重要特性是支持"有损"索引，即索引条目可能匹配多个数据项，查询时需要额外的精确检查。这使得 GiST 索引在处理复杂数据类型时更加灵活。

### 1.4 BRIN 索引

BRIN（Block Range Index，块范围索引）是一种轻量级索引，适用于数据与物理存储顺序相关的大表。BRIN 索引存储每个数据块范围内列值的最大值和最小值，因此索引体积极小。

BRIN 索引最适合以下场景：
- 时间序列数据（按时间顺序插入的日志表、监控表）
- 自增 ID 列的范围查询
- 数据量极大但查询范围较宽的场景

```sql
-- 为时间序列数据创建 BRIN 索引
CREATE INDEX idx_logs_created_at ON system_logs USING brin (created_at) WITH (pages_per_range = 32);
```

BRIN 索引的体积极小，通常只有 B-tree 索引的 1/100 到 1/10。但它的查询效率取决于数据的相关性——如果数据完全随机分布，BRIN 索引几乎无效。

## 2. 索引维护与监控

### 2.1 索引膨胀

PostgreSQL 使用 MVCC（多版本并发控制）机制，UPDATE 操作不会原地修改数据，而是创建新版本的元组。这导致索引中会积累大量"死元组"（dead tuples），造成索引膨胀（index bloat）。

检测索引膨胀的方法：
```sql
SELECT
    schemaname || '.' || tablename AS table_name,
    indexname,
    pg_size_pretty(pg_relation_size(indexrelid)) AS index_size,
    round(100 * pg_relation_size(indexrelid) / NULLIF(pg_relation_size(indrelid), 0), 1) AS index_ratio
FROM pg_stat_user_indexes
JOIN pg_index USING (indexrelid)
ORDER BY pg_relation_size(indexrelid) DESC;
```

处理索引膨胀的传统方法是 `REINDEX`，但该操作会锁定索引，阻塞所有使用该索引的查询。PostgreSQL 12+ 引入了 `REINDEX CONCURRENTLY`，允许在不阻塞查询的情况下重建索引。

### 2.2 未使用索引的识别

每个索引都会增加写操作的开销（INSERT、UPDATE、DELETE），因此应该定期清理未使用的索引。通过 `pg_stat_user_indexes` 视图可以查看索引的使用统计：

```sql
SELECT
    schemaname || '.' || relname AS table,
    indexrelname AS index,
    pg_size_pretty(pg_relation_size(i.indexrelid)) AS index_size,
    idx_scan AS times_used
FROM pg_stat_user_indexes i
JOIN pg_index USING (indexrelid)
WHERE idx_scan = 0
ORDER BY pg_relation_size(i.indexrelid) DESC;
```

对于长期未使用的索引（idx_scan = 0 且运行时间超过一个月），建议先标记为无效（`ALTER INDEX ... SET (n_distinct = 0)`），观察一段时间后再删除。

### 2.3 部分索引

部分索引（Partial Index）只索引表中满足特定条件的行，可以显著减小索引体积并提高查询效率。部分索引特别适用于：
- 只查询活跃记录的场景（`WHERE status = 'active'`）
- 只索引非空值的场景（`WHERE email IS NOT NULL`）
- 分区表中只索引热点数据的场景

```sql
-- 只索引活跃用户的 email
CREATE INDEX idx_active_users_email ON users (email) WHERE status = 'active';

-- 只索引未删除的文档
CREATE INDEX idx_documents_not_deleted ON documents (id) WHERE deleted_at IS NULL;
```

部分索引的优势在于：索引体积更小，维护开销更低，查询时如果 WHERE 条件与索引谓词匹配，可以直接使用索引。但部分索引的缺点是只能被特定查询模式使用。

## 3. EXPLAIN 分析技巧

### 3.1 基本用法

`EXPLAIN` 是 PostgreSQL 中最重要的查询分析工具。它显示查询规划器选择的执行计划，包括扫描方式、连接策略、排序方法等。

```sql
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT * FROM users WHERE email = 'test@example.com';
```

`ANALYZE` 选项会实际执行查询并显示真实的运行时间。`BUFFERS` 选项显示缓冲区的使用情况，包括共享缓冲区命中数和读取数。

### 3.2 关键指标

在 EXPLAIN 输出中，需要关注以下关键指标：
- **actual time**：每个节点的实际执行时间（毫秒），第一个数字是返回第一行的时间，第二个数字是返回所有行的时间
- **rows**：估计行数 vs 实际行数，差距过大说明统计信息过时
- **loops**：节点被执行的次数，嵌套循环连接时 loop 次数很重要
- **Buffers: shared hit**：缓冲区命中次数，反映内存使用效率
- **Buffers: shared read**：磁盘读取次数，过高说明需要增加 shared_buffers 或优化索引

### 3.3 常见问题

**全表扫描（Seq Scan）出现在不该出现的地方**：通常是因为缺少合适的索引，或者统计信息过时导致规划器选择了错误的计划。解决方法是创建索引并运行 `ANALYZE` 更新统计信息。

**Nested Loop 效率低**：当内表没有索引时，Nested Loop 的复杂度为 O(n*m)，其中 n 是外表行数，m 是内表行数。解决方法是确保连接条件上有索引。

**Hash Join 内存不足**：当 Hash 表超过 `work_mem` 限制时，会溢写到磁盘，严重影响性能。解决方法是增加 `work_mem` 或优化查询减少参与 Hash 的数据量。

## 4. 向量索引与 pgvector

### 4.1 pgvector 概述

pgvector 是 PostgreSQL 的向量检索扩展，支持存储和查询高维向量数据。它提供了 IVFFlat 和 HNSW 两种索引类型，用于加速近似最近邻（ANN）搜索。

pgvector 的核心优势在于：
- 与关系型数据共存，无需维护单独的向量数据库
- 支持 SQL 过滤条件与向量搜索的组合
- 支持 L2 距离、余弦相似度和内积三种距离度量

### 4.2 IVFFlat 索引

IVFFlat（Inverted File with Flat Quantization）索引将向量空间划分为若干聚类（list），查询时只搜索最近的几个聚类。创建 IVFFlat 索引前需要先插入数据，因为聚类中心是基于已有数据计算的。

```sql
-- 创建 IVFFlat 索引
CREATE INDEX ON chunks USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
```

`lists` 参数决定了聚类数量。经验法则是：数据量小于 100 万时，lists = rows / 1000；数据量大于 100 万时，lists = sqrt(rows)。

### 4.3 HNSW 索引

HNSW（Hierarchical Navigable Small World）索引是基于图的索引结构，构建一个多层的近邻图。查询时从顶层开始逐层向下搜索，最终定位到最近邻节点。

```sql
-- 创建 HNSW 索引
CREATE INDEX ON chunks USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 200);
```

HNSW 索引的两个关键参数：
- `m`：每个节点的最大连接数，越大图越稠密，查询越快但构建越慢
- `ef_construction`：构建时的搜索范围，越大索引质量越高但构建越慢

查询时通过 `SET hnsw.ef_search = 100` 控制搜索精度。值越大召回率越高，但查询速度越慢。

### 4.4 向量索引与关键词搜索的混合策略

在 RAG（检索增强生成）系统中，纯向量搜索擅长语义匹配但可能遗漏精确关键词，纯关键词搜索擅长精确匹配但无法理解语义。混合检索策略结合两者的优势：

1. **向量通道**：使用 pgvector 进行语义相似度搜索，返回 top-K 个结果
2. **关键词通道**：使用 pg_trgm 或全文搜索进行关键词匹配，返回 top-K 个结果
3. **融合排序**：使用 RRF（Reciprocal Rank Fusion）算法将两个通道的结果合并

RRF 融合公式：`RRF_score(d) = Σ 1/(k + rank_i(d))`，其中 k 通常取 60，rank_i(d) 是文档 d 在第 i 个通道中的排名。

## 5. 性能调优参数

### 5.1 shared_buffers

`shared_buffers` 是 PostgreSQL 最重要的内存参数，决定了数据库用于缓存数据页的内存大小。推荐设置为系统内存的 25%。例如 16GB 内存的服务器，建议设置 `shared_buffers = 4GB`。

### 5.2 effective_cache_size

`effective_cache_size` 不直接分配内存，而是告诉查询规划器操作系统和 PostgreSQL 缓存中可用的总内存大小。推荐设置为系统内存的 50%-75%。

### 5.3 work_mem

`work_mem` 控制每个排序操作和哈希表可用的内存大小。需要注意的是，一个复杂查询可能同时使用多个 `work_mem`，因此不宜设置过大。推荐从 4MB 开始，根据实际情况调整。

### 5.4 maintenance_work_mem

`maintenance_work_mem` 用于 VACUUM、CREATE INDEX 等维护操作。较大的值可以加速索引创建和清理操作。推荐设置为 256MB 到 1GB。
