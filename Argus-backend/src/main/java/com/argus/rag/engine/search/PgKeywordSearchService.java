package com.argus.rag.engine.search;

import com.argus.rag.ingestion.mapper.DocumentChunkMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 基于 PostgreSQL pg_trgm 扩展的关键词检索服务。
 * 使用三字符组（trigram）
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
            return Collections.emptyList();
        }
        long startNano = System.nanoTime();
        try {
            List<Map<String, Object>> rows = documentChunkMapper.searchByKeywordSimilarity(
                    groupId, question, topK);
            List<KeywordHit> hits = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                hits.add(new KeywordHit(
                        toLong(row.get("documentId")),
                        toLong(row.get("chunkId")),
                        toInt(row.get("chunkIndex")),
                        toString(row.get("fileName")),
                        toString(row.get("chunkText")),
                        toDouble(row.get("similarity")),
                        toDouble(row.get("similarity"))
                ));
            }
            long elapsedNano = (System.nanoTime() - startNano) / 1_000_000;
            log.info("PG关键词检索完成: groupId={}, topK={}, hitCount={}, elapsedNano={}",
                    groupId, topK, hits.size(), elapsedNano);
            return List.copyOf(hits);
        } catch (RuntimeException exception) {
            long elapsedNano = (System.nanoTime() - startNano) / 1_000_000;
            log.warn(
                    "PG关键词检索失败，降级为空结果: groupId={}, question='{}', elapsedNano={}, reason={}",
                    groupId,
                    abbreviate(question),
                    elapsedNano,
                    exception.getMessage()
            );
            return Collections.emptyList();
        }
    }

    private String abbreviate(String text) {
        if (!StringUtils.hasText(text)) return "";
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120) + "...";
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
