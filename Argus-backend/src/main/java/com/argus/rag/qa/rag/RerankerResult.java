package com.argus.rag.qa.rag;

/**
 * 重排序器的单条评分结果。
 *
 * @param candidateId 候选切片标识（使用 RRF 阶段分配的临时编号）
 * @param score       LLM 评定的相关性分数 [0.0, 1.0]
 * @param reason      相关性简短理由（≤10 字）
 */
public record RerankerResult(
        String candidateId,
        double score,
        String reason
) {
}