package com.argus.rag.qa.rag;

import com.argus.rag.document.mapper.DocumentMapper;
import com.argus.rag.document.model.entity.DocumentEntity;
import com.argus.rag.engine.pgvector.PgVectorRetrievalAdapter;
import com.argus.rag.engine.search.PgKeywordSearchService;
import com.argus.rag.ingestion.mapper.DocumentChunkMapper;
import com.argus.rag.ingestion.model.entity.DocumentChunkEntity;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * RAG 混合检索效果评估测试（基于人工标注 Ground Truth）。
 * <p>
 * 评估流程：
 * <ol>
 *   <li>从 {@code docs/test-samples/ground-truth.json} 加载人工标注的测试用例</li>
 *   <li>查询已通过正常上传流程入库的测试文档及其 chunks（不自行入库）</li>
 *   <li>通过文本片段匹配，将 Ground Truth 映射到实际 chunk ID</li>
 *   <li>对比纯向量、纯关键词、混合检索三种方法的 Recall@K / Precision@K / MRR</li>
 * </ol>
 * </p>
 * <p>
 * <b>运行前提：</b>
 * <ul>
 *   <li>需要 PostgreSQL（pgvector + pg_trgm）环境</li>
 *   <li>需已通过前端/接口将 {@code docs/test-samples/} 下的 5 份测试文档上传至
 *       groupId={@link #EVAL_GROUP_ID} 的群组，并完成 ETL（状态为 READY）</li>
 * </ul>
 * </p>
 */
@Slf4j
@SpringBootTest
@ActiveProfiles("dev")
@DisplayName("RAG 混合检索效果评估（人工标注 Ground Truth）")
class RagRetrievalEvaluationTest {

    // ─── 配置常量 ─────────────────────────────────────

    /**
     * 评估用的群组 ID（测试文档已上传到此群组）
     */
    private static final Long EVAL_GROUP_ID = 4L;

    /**
     * 被评估方法的 top-K
     */
    private static final int EVAL_TOP_K = 5;

    /**
     * Ground Truth JSON 文件绝对路径
     */
    private static final String GROUND_TRUTH_PATH =
            "e:/Desktop/Files/improve/Argus/docs/test-samples/ground-truth.json";

    // ─── 注入依赖 ─────────────────────────────────────
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Autowired
    private PgVectorRetrievalAdapter vectorRetrievalAdapter;
    @Autowired
    private PgKeywordSearchService keywordSearchService;
    @Autowired
    private DocumentChunkMapper documentChunkMapper;
    @Autowired
    private HybridChunkRetrievalService hybridChunkRetrievalService;

    // ─── 测试数据加载 ─────────────────────────────────────
    @Autowired
    private DocumentMapper documentMapper;
    /**
     * 所有测试 chunk（chunkId → chunkText），用于 Ground Truth 匹配
     */
    private Map<Long, String> allTestChunks = new LinkedHashMap<>();

    /**
     * 加载已上传的测试文档及其 chunks，构建 chunk 文本索引。
     * <p>
     * 前置条件：5 份测试文档已通过正常上传流程上传至 groupId={@link #EVAL_GROUP_ID} 的群组,
     * 且文档状态为 READY（ETL 完成）。
     */
    @BeforeEach
    void setUp() throws Exception {
        log.info("═══ 评估数据加载开始（groupId={}）═══", EVAL_GROUP_ID);

        // 1. 加载 Ground Truth JSON，获取文档名列表
        Path gtPath = Paths.get(GROUND_TRUTH_PATH);
        JsonNode root = objectMapper.readTree(gtPath.toFile());
        JsonNode docsNode = root.get("documents");

        // 2. 按文件名查询已上传的文档及其 chunks
        allTestChunks = new LinkedHashMap<>();
        int totalChunks = 0;
        for (JsonNode docNode : docsNode) {
            String fileName = docNode.asText();

            // 按 groupId + fileName 查询文档（已上传、未删除）
            DocumentEntity doc = documentMapper.selectOne(new LambdaQueryWrapper<DocumentEntity>()
                    .eq(DocumentEntity::getGroupId, EVAL_GROUP_ID)
                    .eq(DocumentEntity::getFileName, fileName)
                    .eq(DocumentEntity::getDeleted, false)
                    .last("LIMIT 1"));

            if (doc == null) {
                log.error("文档未找到或未上传: fileName={}, groupId={}。请先通过前端上传该文档。", fileName, EVAL_GROUP_ID);
                throw new IllegalStateException("文档未上传: " + fileName);
            }

            if (!"READY".equals(doc.getStatus())) {
                log.warn("文档状态非 READY: fileName={}, status={}, failureReason={}",
                        fileName, doc.getStatus(), doc.getFailureReason());
            }

            // 查询该文档的 READY 活跃切片
            List<DocumentChunkEntity> chunks = documentChunkMapper
                    .selectReadyActiveChunksByDocumentId(EVAL_GROUP_ID, doc.getId());

            for (DocumentChunkEntity chunk : chunks) {
                allTestChunks.put(chunk.getId(), chunk.getChunkText());
            }
            totalChunks += chunks.size();

            log.info("文档加载: fileName={}, documentId={}, status={}, chunks={}",
                    fileName, doc.getId(), doc.getStatus(), chunks.size());
        }

        log.info("═══ 评估数据加载完成: {} 篇文档, {} 个 chunk ═══",
                docsNode.size(), totalChunks);

        if (allTestChunks.isEmpty()) {
            throw new IllegalStateException("未加载到任何 chunk，请确认文档已上传且 ETL 完成（status=READY）");
        }
    }

    // ─── Ground Truth 匹配 ─────────────────────────────────────

    /**
     * 根据 JSON 中的 expectedChunks 定义，匹配实际 chunk ID。
     * 匹配规则：expectedText 是 chunk 内容的子串即视为匹配。
     */
    private Set<Long> matchGroundTruth(JsonNode expectedChunksNode) {
        Set<Long> matchedIds = new LinkedHashSet<>();
        for (JsonNode expected : expectedChunksNode) {
            String text = expected.get("text").asText();
            for (var entry : allTestChunks.entrySet()) {
                if (entry.getValue().contains(text)) {
                    matchedIds.add(entry.getKey());
                }
            }
        }
        return matchedIds;
    }

    // ─── 评估执行 ─────────────────────────────────────

    @Test
    @DisplayName("运行 RAG 混合检索效果评估")
    void evaluateRetrieval() throws Exception {
        log.info("═══════════════════════════════════════════════");
        log.info("  RAG 混合检索效果评估开始（人工标注 Ground Truth）");
        log.info("  groupId={}, evalTopK={}", EVAL_GROUP_ID, EVAL_TOP_K);
        log.info("═══════════════════════════════════════════════");

        // 加载 Ground Truth JSON
        Path gtPath = Paths.get(GROUND_TRUTH_PATH);
        JsonNode root = objectMapper.readTree(gtPath.toFile());
        JsonNode testCasesNode = root.get("testCases");

        List<EvalResult> results = new ArrayList<>();

        for (JsonNode tc : testCasesNode) {
            String id = tc.get("id").asText();
            String type = tc.get("type").asText();
            String query = tc.get("query").asText();
            String description = tc.get("description").asText();
            JsonNode expectedChunks = tc.get("expectedChunks");

            // 匹配 Ground Truth chunk ID
            Set<Long> groundTruthIds = matchGroundTruth(expectedChunks);

            if (groundTruthIds.isEmpty()) {
                log.warn("  [{}] Ground Truth 匹配为空，跳过。query={}", id, query);
                results.add(EvalResult.empty(id, type, query, description));
                continue;
            }

            log.info("评估 Query: [{}] {} (GT chunk数={})", id, query, groundTruthIds.size());

            // 纯向量检索 top-K
            List<Long> vectorResultIds = vectorRetrievalAdapter
                    .search(EVAL_GROUP_ID, query, EVAL_TOP_K).stream()
                    .map(PgVectorRetrievalAdapter.VectorHit::chunkId)
                    .toList();

            // 纯关键词检索 top-K
            List<Long> keywordResultIds = keywordSearchService
                    .search(EVAL_GROUP_ID, query, EVAL_TOP_K).stream()
                    .map(PgKeywordSearchService.KeywordHit::chunkId)
                    .toList();

            // 混合检索 top-K
            RetrievedEvidenceBundle bundle = hybridChunkRetrievalService
                    .retrieve(EVAL_GROUP_ID, query, EVAL_TOP_K);
            List<Long> hybridResultIds = bundle.documents().stream()
                    .map(doc -> {
                        Object chunkId = doc.getMetadata().get("chunkId");
                        return chunkId instanceof Number ? ((Number) chunkId).longValue() : null;
                    })
                    .filter(Objects::nonNull)
                    .toList();

            // 计算指标
            EvalResult result = new EvalResult(
                    id, type, query, description,
                    groundTruthIds.size(),
                    vectorResultIds, keywordResultIds, hybridResultIds,
                    recallAtK(vectorResultIds, groundTruthIds),
                    recallAtK(keywordResultIds, groundTruthIds),
                    recallAtK(hybridResultIds, groundTruthIds),
                    precisionAtK(vectorResultIds, groundTruthIds),
                    precisionAtK(keywordResultIds, groundTruthIds),
                    precisionAtK(hybridResultIds, groundTruthIds),
                    mrr(vectorResultIds, groundTruthIds),
                    mrr(keywordResultIds, groundTruthIds),
                    mrr(hybridResultIds, groundTruthIds)
            );
            results.add(result);

            log.info("  [{}] vectorRecall={}, keywordRecall={}, hybridRerankRecall={}", id, String.format("%.3f", result.vectorRecall()),
                    String.format("%.3f", result.keywordRecall()),
                    String.format("%.3f", result.hybridRecall()));
        }

        // 汇总统计
        EvalSummary summary = computeSummary(results);
        log.info("═══════════════════════════════════════════════");
        log.info("  评估汇总（有效 Query 数: {}）", summary.validCount());
        log.info("  纯向量  — 平均 Recall@5: {}, Precision@5: {}, MRR: {}",
                fmt(summary.avgVectorRecall()), fmt(summary.avgVectorPrecision()), fmt(summary.avgVectorMrr()));
        log.info("  纯关键词 — 平均 Recall@5: {}, Precision@5: {}, MRR: {}",
                fmt(summary.avgKeywordRecall()), fmt(summary.avgKeywordPrecision()), fmt(summary.avgKeywordMrr()));
        log.info("  混合检索+重排 — 平均 Recall@5: {}, Precision@5: {}, MRR: {}",
                fmt(summary.avgHybridRecall()), fmt(summary.avgHybridPrecision()), fmt(summary.avgHybridMrr()));
        log.info("  混合+重排 vs 向量 Recall 提升: {}%", fmt(summary.recallImprovementPercent()));
        log.info("═══════════════════════════════════════════════");

        // 输出 Markdown 报告
        String report = generateMarkdownReport(results, summary);
        Path reportPath = Paths.get("docs", "RAG-评估报告.md");
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, report);
        log.info("评估报告已写入: {}", reportPath.toAbsolutePath());
    }

    // ─── 指标计算 ─────────────────────────────────────

    private double recallAtK(List<Long> resultIds, Set<Long> groundTruthIds) {
        if (groundTruthIds.isEmpty()) return 0.0;
        long hitCount = resultIds.stream().filter(groundTruthIds::contains).count();
        return (double) hitCount / groundTruthIds.size();
    }

    private double precisionAtK(List<Long> resultIds, Set<Long> groundTruthIds) {
        if (resultIds.isEmpty()) return 0.0;
        long hitCount = resultIds.stream().filter(groundTruthIds::contains).count();
        return (double) hitCount / resultIds.size();
    }

    private double mrr(List<Long> resultIds, Set<Long> groundTruthIds) {
        if (groundTruthIds.isEmpty() || resultIds.isEmpty()) return 0.0;
        double sumRR = 0;
        for (Long gtId : groundTruthIds) {
            int rank = resultIds.indexOf(gtId);
            if (rank >= 0) {
                sumRR += 1.0 / (rank + 1);
            }
        }
        return sumRR / groundTruthIds.size();
    }

    // ─── 汇总统计 ─────────────────────────────────────

    private EvalSummary computeSummary(List<EvalResult> results) {
        List<EvalResult> valid = results.stream()
                .filter(r -> r.groundTruthCount() > 0)
                .toList();
        int count = valid.size();
        if (count == 0) return EvalSummary.empty();

        double avgVR = avg(valid, EvalResult::vectorRecall);
        double avgKR = avg(valid, EvalResult::keywordRecall);
        double avgHR = avg(valid, EvalResult::hybridRecall);
        double avgVP = avg(valid, EvalResult::vectorPrecision);
        double avgKP = avg(valid, EvalResult::keywordPrecision);
        double avgHP = avg(valid, EvalResult::hybridPrecision);
        double avgVM = avg(valid, EvalResult::vectorMrr);
        double avgKM = avg(valid, EvalResult::keywordMrr);
        double avgHM = avg(valid, EvalResult::hybridMrr);

        double improvement = avgVR > 0 ? (avgHR - avgVR) / avgVR * 100 : 0;

        return new EvalSummary(count,
                avgVR, avgKR, avgHR, avgVP, avgKP, avgHP, avgVM, avgKM, avgHM,
                improvement);
    }

    private double avg(List<EvalResult> list, java.util.function.ToDoubleFunction<EvalResult> fn) {
        return list.stream().mapToDouble(fn).average().orElse(0);
    }

    private String fmt(double v) {
        return String.format("%.4f", v);
    }

    // ─── Markdown 报告生成 ─────────────────────────────────────

    private String generateMarkdownReport(List<EvalResult> results, EvalSummary summary) {
        StringBuilder sb = new StringBuilder();
        sb.append("# RAG 混合检索效果评估报告\n\n");

        sb.append("## 评估方法\n\n");
        sb.append("**Ground Truth 定义**：基于 5 份人工编写的测试文档（共覆盖 PostgreSQL 索引优化、");
        sb.append("微服务架构、Docker 部署、消息队列选型、API 网关设计 5 个技术领域），");
        sb.append("由人工标注每个测试 query 应命中的文档段落（expectedChunks）。");
        sb.append("测试文档通过正常上传流程入库后，通过文本子串匹配将标注映射到实际 chunk ID。\n\n");

        sb.append("**评估指标**：\n");
        sb.append("- **Recall@5**：Ground Truth 中有多少比例出现在各方法的 top-5 结果中\n");
        sb.append("- **Precision@5**：各方法 top-5 结果中有多少比例属于 Ground Truth\n");
        sb.append("- **MRR**（Mean Reciprocal Rank）：Ground Truth 文档在各方法结果中的平均排名倒数\n\n");

        sb.append("## 汇总结果\n\n");
        sb.append("| 指标 | 纯向量 | 纯关键词 | 混合检索+重排 |\n");
        sb.append("|------|--------|---------|----------|\n");
        sb.append(String.format("| 平均 Recall@5 | %s | %s | **%s** |\n",
                fmt(summary.avgVectorRecall()), fmt(summary.avgKeywordRecall()), fmt(summary.avgHybridRecall())));
        sb.append(String.format("| 平均 Precision@5 | %s | %s | **%s** |\n",
                fmt(summary.avgVectorPrecision()), fmt(summary.avgKeywordPrecision()), fmt(summary.avgHybridPrecision())));
        sb.append(String.format("| 平均 MRR | %s | %s | **%s** |\n",
                fmt(summary.avgVectorMrr()), fmt(summary.avgKeywordMrr()), fmt(summary.avgHybridMrr())));
        sb.append("\n");
        sb.append(String.format("**混合检索（含 LLM 重排）vs 纯向量 Recall 提升：%.1f%%**\n\n", summary.recallImprovementPercent()));

        sb.append("## 逐 Query 明细\n\n");
        sb.append("| # | 类型 | Query | GT数量 | 向量R | 关键词R | 混合+重排R |\n");
        sb.append("|---|------|-------|--------|-------|---------|-------|\n");
        for (EvalResult r : results) {
            sb.append(String.format("| %s | %s | %s | %d | %.2f | %.2f | **%.2f** |\n",
                    r.id(), r.type(), r.query(), r.groundTruthCount(),
                    r.vectorRecall(), r.keywordRecall(), r.hybridRecall()));
        }

        sb.append("\n## 分类分析\n\n");
        Map<String, List<EvalResult>> byType = results.stream()
                .filter(r -> r.groundTruthCount() > 0)
                .collect(Collectors.groupingBy(EvalResult::type));

        sb.append("| 类型 | 向量平均R | 关键词平均R | 混合+重排平均R | 混合+重排提升 |\n");
        sb.append("|------|----------|------------|----------|----------|\n");
        for (var entry : byType.entrySet()) {
            double avgV = avg(entry.getValue(), EvalResult::vectorRecall);
            double avgK = avg(entry.getValue(), EvalResult::keywordRecall);
            double avgH = avg(entry.getValue(), EvalResult::hybridRecall);
            double imp = avgV > 0 ? (avgH - avgV) / avgV * 100 : 0;
            sb.append(String.format("| %s | %s | %s | **%s** | +%.1f%% |\n",
                    entry.getKey(), fmt(avgV), fmt(avgK), fmt(avgH), imp));
        }

        sb.append("\n## 测试文档清单\n\n");
        sb.append("| 文档 | 主题 | 覆盖检索场景 |\n");
        sb.append("|------|------|-------------|\n");
        sb.append("| 01-PostgreSQL索引优化指南.md | 数据库索引 | 精确关键词(B-tree/GIN/BRIN)、语义理解(减小存储空间)、多条件(pgvector+HNSW参数) |\n");
        sb.append("| 02-微服务架构设计原则与实践.md | 微服务架构 | 语义理解(数据一致性→Saga)、多条件(熔断器+Resilience4j) |\n");
        sb.append("| 03-Docker容器化部署最佳实践.md | 容器化部署 | 多条件(多阶段构建+Java)、语义理解(OOM→内存配置)、精确关键词(healthcheck) |\n");
        sb.append("| 04-消息队列技术选型与应用场景.md | 消息队列 | 精确关键词(Kafka acks/RabbitMQ Exchange)、语义理解(重复处理→幂等消费) |\n");
        sb.append("| 05-API网关设计与流量治理.md | API网关 | 精确关键词(令牌桶)、语义理解(追踪请求→链路追踪) |\n");

        sb.append("\n## 局限性\n\n");
        sb.append("- 测试文档为 Markdown 格式，与生产环境的 PDF/Word 文档切片效果可能不同\n");
        sb.append("- Ground Truth 基于文本子串匹配，若 chunk 切片边界恰好切分了标注文本，可能导致匹配遗漏\n");
        sb.append("- 评估 Query 数量有限（15 个），统计显著性有限\n");

        return sb.toString();
    }

    // ─── 数据结构 ─────────────────────────────────────

    record EvalResult(
            String id,
            String type,
            String query,
            String description,
            int groundTruthCount,
            List<Long> vectorResultIds,
            List<Long> keywordResultIds,
            List<Long> hybridResultIds,
            double vectorRecall,
            double keywordRecall,
            double hybridRecall,
            double vectorPrecision,
            double keywordPrecision,
            double hybridPrecision,
            double vectorMrr,
            double keywordMrr,
            double hybridMrr
    ) {
        static EvalResult empty(String id, String type, String query, String description) {
            return new EvalResult(id, type, query, description, 0,
                    List.of(), List.of(), List.of(),
                    0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    record EvalSummary(
            int validCount,
            double avgVectorRecall,
            double avgKeywordRecall,
            double avgHybridRecall,
            double avgVectorPrecision,
            double avgKeywordPrecision,
            double avgHybridPrecision,
            double avgVectorMrr,
            double avgKeywordMrr,
            double avgHybridMrr,
            double recallImprovementPercent
    ) {
        static EvalSummary empty() {
            return new EvalSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }
}
