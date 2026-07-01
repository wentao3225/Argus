package com.argus.rag.qa.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * LLM 驱动的切片重排序服务。
 * <p>
 * 在 RRF 粗排之后，取 top-20 候选切片，用 LLM 评估
 * query 与每个候选切片内容的细粒度相关性，按相关性分数重新排序。
 * </p>
 *
 * <h3>性能策略</h3>
 * <ul>
 * <li>只重排 top-20 候选（不是全部 50+），减少 LLM 调用成本</li>
 * <li>每批 5 个候选，4 批内完成</li>
 * <li>重排失败时直接返回原始排序结果，不中断检索链路</li>
 * </ul>
 */
@Slf4j
@Service
@ConditionalOnProperty(value = "rag.qa.reranker.enabled", havingValue = "true", matchIfMissing = false)
public class ChunkRerankerService {

    /**
     * 进入重排器的最大候选数。
     * RRF 融合后可能有 50+ 候选，只取前 20 个给 LLM 打分。
     */
    private static final int RERANK_CANDIDATE_K = 20;

    /**
     * 每批发送给 LLM 的候选数。
     * 每批 5 个候选 + 1 个 query，控制 Prompt 总长度。
     */
    private static final int BATCH_SIZE = 5;

    private final ChatClient rerankerChatClient; // LLM 重排器Client
    private final PromptTemplate rerankerUserPromptTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 构造函数。
     *
     * @param rerankerChatClient         重排序专用的 ChatClient
     * @param rerankerUserPromptTemplate 重排序 Prompt 模板
     * @param objectMapper               JSON 解析
     */
    public ChunkRerankerService(
            @Qualifier("rerankerChatClient") ChatClient rerankerChatClient,
            @Qualifier("rerankerUserPromptTemplate") PromptTemplate rerankerUserPromptTemplate,
            ObjectMapper objectMapper) {
        this.rerankerChatClient = rerankerChatClient;
        this.rerankerUserPromptTemplate = rerankerUserPromptTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 对 RRF 粗排后的候选切片进行 LLM 重排序。
     * <p>
     * 流程：取前 20 候选 → 拼装 batch → 逐批调用 LLM 打分 → 汇总排序。
     * 整个过程不修改原有候选对象，只返回重新排序后的列表。
     * </p>
     *
     * @param question         用户原始问题
     * @param rankedCandidates RRF 粗排后的全部候选（已按 RRF 分数降序排列）
     * @return 重排后的候选列表（仍为原有候选对象，仅顺序改变）
     */
    public List<HybridChunkRetrievalService.RetrievalCandidate> rerank(
            String question,
            List<HybridChunkRetrievalService.RetrievalCandidate> rankedCandidates) {

        if (rankedCandidates == null || rankedCandidates.size() <= 1) {
            return rankedCandidates;
        }

        // 只取 top-RERANK_CANDIDATE_K 给 LLM 打分
        List<HybridChunkRetrievalService.RetrievalCandidate> toRerank =
                rankedCandidates.stream()
                        .limit(RERANK_CANDIDATE_K)
                        .toList();

        log.info("重排序开始: candidateCount={}, toRerank={}, batchSize={}",
                rankedCandidates.size(), toRerank.size(), BATCH_SIZE);

        // 逐批次调用 LLM，汇总所有得分
        Map<Long, RerankerResult> scoreById = new LinkedHashMap<>();
        long startNano = System.nanoTime();
        boolean anyBatchSucceeded = false;

        for (int batchStart = 0; batchStart < toRerank.size(); batchStart += BATCH_SIZE) {
            int batchEnd = Math.min(batchStart + BATCH_SIZE, toRerank.size());
            List<HybridChunkRetrievalService.RetrievalCandidate> batch =
                    toRerank.subList(batchStart, batchEnd);
            try {
                List<RerankerResult> batchResults = scoreBatch(question, batch);
                for (int i = 0; i < batch.size(); i++) {
                    scoreById.put(batch.get(i).chunkId(), batchResults.get(i));
                }
                anyBatchSucceeded = true;
            } catch (Exception e) {
                log.warn("重排序批次失败 (chunks {}-{}), 跳过此批次: {}",
                        batchStart, batchEnd - 1, e.getMessage());
            }
        }

        long elapsedMs = (System.nanoTime() - startNano) / 1_000_000;

        // 所有批次全部失败 → 维持原始 RRF 排序，避免默认分影响证据评估
        if (!anyBatchSucceeded) {
            log.warn("重排序所有批次均失败，跳过重排，维持原始 RRF 排序: candidateCount={}, elapsedMs={}",
                    rankedCandidates.size(), elapsedMs);
            return rankedCandidates;
        }

        // 按 LLM 分数降序排列（分数相同时保持 RRF 原始顺序）
        List<HybridChunkRetrievalService.RetrievalCandidate> reranked = new ArrayList<>(rankedCandidates);
        reranked.sort(Comparator
                .<HybridChunkRetrievalService.RetrievalCandidate, Double>comparing(
                        c -> scoreById.containsKey(c.chunkId())
                                ? scoreById.get(c.chunkId()).score()
                                : c.rankingScore())  // 不在 top-20 的用 RRF 分
                .reversed()
                .thenComparingDouble(HybridChunkRetrievalService.RetrievalCandidate::rankingScore)
                .reversed());

        log.info("重排序完成: totalCandidates={}, reranked={}, scored={}, elapsedMs={}",
                rankedCandidates.size(), reranked.size(), scoreById.size(), elapsedMs);

        return reranked;
    }

    /**
     * 对一个批次的候选切片调用 LLM 打分。
     * <p>
     * 构造 Prompt → 发送 → 解析 JSON → 返回分数列表。
     * 若 LLM 返回格式错误，给每个候选默认 0.5 分。
     * </p>
     *
     * @param question 用户问题
     * @param batch    本批次候选切片（1~5 个）
     * @return 本批次各候选的评分
     */
    private List<RerankerResult> scoreBatch(
            String question,
            List<HybridChunkRetrievalService.RetrievalCandidate> batch) {

        // 构造 "c{chunkId}: {前200字}" 格式的候选文本
        String candidatesText = batch.stream()
                .map(c -> String.format("c%d: %s",
                        c.chunkId(),
                        truncateText(c.chunkText())))
                .collect(Collectors.joining("\n\n"));

        // 填充 Prompt 模板
        String promptContent = rerankerUserPromptTemplate.create(
                Map.of("question", question, "candidates", candidatesText)
        ).getContents();

        // 调用 LLM
        String response = rerankerChatClient.prompt()
                .user(promptContent)
                .call()
                .content();

        // 解析 JSON 响应
        return parseResponse(response, batch);
    }

    /**
     * 解析 LLM 返回的 JSON，提取 scores 数组。
     * <p>
     * 支持两种格式：LLM 直接返回 JSON 对象，或返回含 Markdown 代码块包裹的 JSON。
     * 找不到合法 scores 数组时，给每个候选默认 0.5 分。
     * </p>
     */
    private List<RerankerResult> parseResponse(
            String rawResponse,
            List<HybridChunkRetrievalService.RetrievalCandidate> batch) {

        if (!StringUtils.hasText(rawResponse)) {
            log.warn("重排序 LLM 返回空内容，使用默认分");
            return batch.stream()
                    .map(c -> new RerankerResult("c" + c.chunkId(), 0.5, "LLM返回空"))
                    .toList();
        }

        // 如果 LLM 返回了 Markdown 代码块，提取 JSON 部分
        String json = rawResponse.trim();
        if (json.startsWith("```")) {
            json = json.replaceAll("```json\\s*", "")
                    .replaceAll("```\\s*", "")
                    .trim();
        }

        try {
            Map<String, List<RerankerResult>> parsed = objectMapper.readValue(
                    json, new TypeReference<>() {
                    });
            List<RerankerResult> scores = parsed.get("scores");
            if (scores == null || scores.isEmpty()) {
                throw new IllegalArgumentException("scores 为空");
            }
            return scores;
        } catch (Exception e) {
            log.warn("重排序 JSON 解析失败: {}. rawResponse前100字: {}",
                    e.getMessage(),
                    rawResponse.length() > 100 ? rawResponse.substring(0, 100) : rawResponse);
            // 解析失败给默认分
            return batch.stream()
                    .map(c -> new RerankerResult("c" + c.chunkId(), 0.5, "JSON解析失败"))
                    .toList();
        }
    }

    /**
     * 截断文本到指定最大长度。
     */
    private String truncateText(String text) {
        if (text == null) return "";
        return text.length() <= 200 ? text : text.substring(0, 200);
    }
}
