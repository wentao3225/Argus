# PostgreSQL pgvector 使用指南

## 什么是 pgvector

pgvector 是 PostgreSQL 的开源向量相似度搜索扩展，支持高效存储和查询向量数据。

## 安装与配置

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

安装后即可创建包含向量字段的表。

## 创建向量表

```sql
CREATE TABLE embeddings (
    id UUID PRIMARY KEY,
    content TEXT,
    metadata JSONB,
    embedding vector(512)
);
```

## 创建索引

pgvector 支持三种索引类型：

### IVFFlat 索引（倒排文件索引）

```sql
CREATE INDEX ON embeddings USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
```

### HNSW 索引（分层可导航小世界图）

```sql
CREATE INDEX ON embeddings USING hnsw (embedding vector_cosine_ops);
```

HNSW 索引比 IVFFlat 查询速度更快，但构建速度和索引大小略大。

## 支持的向量距离类型

- 余弦距离（cosine_distance）：适用于文本相似度搜索
- 欧氏距离（l2_distance）：适用于图像相似度搜索
- 内积距离（inner_product）：适用于语义搜索

## 相似度查询示例

```sql
-- 查找与目标向量最相似的 10 条记录
SELECT content, 1 - (embedding <=> '[0.1, 0.2, ...]') AS similarity
FROM embeddings
ORDER BY embedding <=> '[0.1, 0.2, ...]'
LIMIT 10;
```

`<=>` 运算符计算余弦距离，`1 - 距离` 即为余弦相似度。

## pg_trgm 扩展简介

pg_trgm 是 PostgreSQL 的另一个扩展，用于文本模糊匹配：

```sql
CREATE EXTENSION IF NOT EXISTS pg_trgm;
```

### 三字符组相似度

pg_trgm 将文本拆分为连续的三字符组（trigram），通过比较两组 trigram 的重叠比例计算相似度。

```sql
-- 相似度查询
SELECT similarity('hello world', 'hello word');
-- 带索引的模糊搜索
SELECT * FROM documents
WHERE similarity(file_name, '技术架') > 0.3
ORDER BY similarity(file_name, '技术架') DESC;
```

### 与 Gin 索引配合

```sql
CREATE INDEX ON documents USING gin (file_name gin_trgm_ops);
```

## 在 Argus 中的使用

Argus 使用 pgvector 和 pg_trgm 实现混合检索：

1. 文档切片通过 DashScope text-embedding-v3 模型转为 512 维向量，存入 vector_store 表
2. 用户提问时，先将问题转为向量，在 vector_store 中做 HNSW 余弦距离搜索
3. 同时将问题原文在 document_chunks 表中做 pg_trgm 相似度搜索
4. 两路结果通过 RRF 算法融合排序，取 Top-K 作为最终证据
