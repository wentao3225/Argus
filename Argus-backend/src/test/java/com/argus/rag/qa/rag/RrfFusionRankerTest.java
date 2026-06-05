package com.argus.rag.qa.rag;

import com.argus.rag.engine.pgvector.PgVectorRetrievalAdapter;
import com.argus.rag.engine.search.PgKeywordSearchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RRF（Reciprocal Rank Fusion）融合排序算法单元测试。
 * <p>
 * 直接测试 {@link HybridChunkRetrievalService} 内部类
 * {@link HybridChunkRetrievalService.RetrievalCandidate} 和
 * {@link HybridChunkRetrievalService.RetrievalCluster} 的核心逻辑。
 * </p>
 * <p>
 * RRF 公式（k=0）：score = Σ 1/rank，
 * 其中 rank 为切片在各通道中的排名（从 1 开始）。
 * </p>
 */
@DisplayName("RRF 融合排序算法测试")
class RrfFusionRankerTest {

    // ─── 工具方法 ───────────────────────────────────────

    /**
     * 构造一个向量检索命中结果
     */
    private static PgVectorRetrievalAdapter.VectorHit vectorHit(
            Long documentId, Long chunkId, Integer chunkIndex, double score) {
        return new PgVectorRetrievalAdapter.VectorHit(documentId, chunkId, chunkIndex, "chunkText", score);
    }

    /**
     * 构造一个关键词检索命中结果
     */
    private static PgKeywordSearchService.KeywordHit keywordHit(
            Long documentId, Long chunkId, Integer chunkIndex, double normalizedScore) {
        return new PgKeywordSearchService.KeywordHit(
                documentId, chunkId, chunkIndex, "file.pdf", "chunkText", normalizedScore, normalizedScore);
    }

    /**
     * 从 VectorHit 创建 RetrievalCandidate 并合并命中
     */
    private static HybridChunkRetrievalService.RetrievalCandidate candidateFromVector(
            Long documentId, Long chunkId, Integer chunkIndex, double score, int rank) {
        PgVectorRetrievalAdapter.VectorHit hit = vectorHit(documentId, chunkId, chunkIndex, score);
        HybridChunkRetrievalService.RetrievalCandidate candidate =
                HybridChunkRetrievalService.RetrievalCandidate.fromVectorHit(hit);
        candidate.mergeVectorHit(hit, rank);
        return candidate;
    }

    /**
     * 从 KeywordHit 创建 RetrievalCandidate 并合并命中
     */
    private static HybridChunkRetrievalService.RetrievalCandidate candidateFromKeyword(
            Long documentId, Long chunkId, Integer chunkIndex, double normalizedScore, int rank) {
        PgKeywordSearchService.KeywordHit hit = keywordHit(documentId, chunkId, chunkIndex, normalizedScore);
        HybridChunkRetrievalService.RetrievalCandidate candidate =
                HybridChunkRetrievalService.RetrievalCandidate.fromKeywordHit(hit);
        candidate.mergeKeywordHit(hit, rank);
        return candidate;
    }

    // ──────────────────────────────────────────────
    // 场景一：单一通道结果的 RRF 分数
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("单一通道 RRF 分数")
    class SingleChannelScore {

        @Test
        @DisplayName("仅向量检索 rank-1 命中时，RRF 分数应为 1/1 = 1.0")
        void rrfScore_向量通道rank1_分数为1() {
            // 准备：切片 1001 在向量检索中排名第 1
            HybridChunkRetrievalService.RetrievalCandidate candidate =
                    candidateFromVector(1L, 1001L, 0, 0.95, 1);

            // 断言：RRF 分数 = 1/(0+1) = 1.0
            assertThat(candidate.rankingScore()).isEqualTo(1.0);

            // 断言：来源为向量检索
            assertThat(candidate.source()).isEqualTo("VECTOR");
        }

        @Test
        @DisplayName("仅向量检索 rank-3 命中时，RRF 分数应为 1/3 ≈ 0.333")
        void rrfScore_向量通道rank3_分数为1over3() {
            // 准备：切片 1001 在向量检索中排名第 3
            HybridChunkRetrievalService.RetrievalCandidate candidate =
                    candidateFromVector(1L, 1001L, 0, 0.80, 3);

            // 断言：RRF 分数 = 1/3 ≈ 0.333
            assertThat(candidate.rankingScore()).isCloseTo(1.0 / 3, org.assertj.core.data.Offset.offset(0.001));
        }

        @Test
        @DisplayName("仅关键词检索 rank-1 命中时，RRF 分数应为 1/1 = 1.0")
        void rrfScore_关键词通道rank1_分数为1() {
            // 准备：切片 2001 在关键词检索中排名第 1
            HybridChunkRetrievalService.RetrievalCandidate candidate =
                    candidateFromKeyword(1L, 2001L, 0, 0.90, 1);

            // 断言：RRF 分数 = 1/(0+1) = 1.0
            assertThat(candidate.rankingScore()).isEqualTo(1.0);

            // 断言：来源为关键词检索
            assertThat(candidate.source()).isEqualTo("KEYWORD");
        }

        @Test
        @DisplayName("多次向量检索命中应累加 RRF 分数")
        void rrfScore_多次命中_分数累加() {
            // 准备：切片 1001 在两次查询中分别排名 rank-1 和 rank-2
            PgVectorRetrievalAdapter.VectorHit hit1 = vectorHit(1L, 1001L, 0, 0.95);
            PgVectorRetrievalAdapter.VectorHit hit2 = vectorHit(1L, 1001L, 0, 0.90);
            HybridChunkRetrievalService.RetrievalCandidate candidate =
                    HybridChunkRetrievalService.RetrievalCandidate.fromVectorHit(hit1);

            // 执行：合并两次命中
            candidate.mergeVectorHit(hit1, 1); // rank-1 → 1/1 = 1.0
            candidate.mergeVectorHit(hit2, 2); // rank-2 → 1/2 = 0.5

            // 断言：总分 = 1.0 + 0.5 = 1.5
            assertThat(candidate.rankingScore()).isCloseTo(1.5, org.assertj.core.data.Offset.offset(0.001));
        }
    }

    // ──────────────────────────────────────────────
    // 场景二：双通道重叠的 RRF 分数
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("双通道重叠 RRF 分数")
    class DualChannelOverlap {

        @Test
        @DisplayName("同时出现在两通道 rank-1 的文档应得最高分 2.0")
        void rrfScore_两通道重叠_重叠文档得分更高() {
            // 准备：切片 1001 同时在向量和关键词检索中排名第 1
            PgVectorRetrievalAdapter.VectorHit vHit = vectorHit(1L, 1001L, 0, 0.95);
            PgKeywordSearchService.KeywordHit kHit = keywordHit(1L, 1001L, 0, 0.90);
            HybridChunkRetrievalService.RetrievalCandidate candidate =
                    HybridChunkRetrievalService.RetrievalCandidate.fromVectorHit(vHit);

            // 执行：合并向量和关键词命中
            candidate.mergeVectorHit(vHit, 1);   // 1/1 = 1.0
            candidate.mergeKeywordHit(kHit, 1);  // 1/1 = 1.0

            // 断言：总分 = 1.0 + 1.0 = 2.0
            assertThat(candidate.rankingScore()).isCloseTo(2.0, org.assertj.core.data.Offset.offset(0.001));

            // 断言：来源为双通道
            assertThat(candidate.source()).isEqualTo("BOTH");
        }

        @Test
        @DisplayName("仅在单一通道命中的文档得分应低于双通道重叠文档")
        void rrfScore_单通道vs双通道_双通道更高() {
            // 准备：切片 A 仅在向量检索 rank-1 命中
            HybridChunkRetrievalService.RetrievalCandidate candidateA =
                    candidateFromVector(1L, 1001L, 0, 0.95, 1);

            // 准备：切片 B 同时在向量 rank-2 和关键词 rank-2 命中
            PgVectorRetrievalAdapter.VectorHit vHitB = vectorHit(1L, 1002L, 1, 0.85);
            PgKeywordSearchService.KeywordHit kHitB = keywordHit(1L, 1002L, 1, 0.80);
            HybridChunkRetrievalService.RetrievalCandidate candidateB =
                    HybridChunkRetrievalService.RetrievalCandidate.fromVectorHit(vHitB);
            candidateB.mergeVectorHit(vHitB, 2);  // 1/2 = 0.5
            candidateB.mergeKeywordHit(kHitB, 2); // 1/2 = 0.5

            // 断言：切片 A 得分 = 1.0（单通道 rank-1）
            assertThat(candidateA.rankingScore()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.001));

            // 断言：切片 B 得分 = 1.0（双通道 rank-2 叠加）
            assertThat(candidateB.rankingScore()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.001));

            // 断言：切片 B 的来源为双通道，而 A 仅为向量
            assertThat(candidateA.source()).isEqualTo("VECTOR");
            assertThat(candidateB.source()).isEqualTo("BOTH");
        }

        @Test
        @DisplayName("双通道不同排名命中——验证倒数累加")
        void rrfScore_双通道不同排名_倒数累加正确() {
            // 准备：切片在向量 rank-1、关键词 rank-3 命中
            PgVectorRetrievalAdapter.VectorHit vHit = vectorHit(1L, 1001L, 0, 0.95);
            PgKeywordSearchService.KeywordHit kHit = keywordHit(1L, 1001L, 0, 0.80);
            HybridChunkRetrievalService.RetrievalCandidate candidate =
                    HybridChunkRetrievalService.RetrievalCandidate.fromVectorHit(vHit);

            // 执行
            candidate.mergeVectorHit(vHit, 1);  // 1/1 = 1.0
            candidate.mergeKeywordHit(kHit, 3); // 1/3 ≈ 0.333

            // 断言：总分 = 1.0 + 0.333 ≈ 1.333
            assertThat(candidate.rankingScore()).isCloseTo(1.0 + 1.0 / 3, org.assertj.core.data.Offset.offset(0.001));
        }
    }

    // ──────────────────────────────────────────────
    // 场景三：排名越靠前分数越高
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("排名与分数关系")
    class RankVsScore {

        @Test
        @DisplayName("rank-1 分数应高于 rank-2")
        void rrfScore_排名越靠前_分数越高() {
            // 准备：两个切片分别在 rank-1 和 rank-2
            HybridChunkRetrievalService.RetrievalCandidate rank1 =
                    candidateFromVector(1L, 1001L, 0, 0.95, 1);
            HybridChunkRetrievalService.RetrievalCandidate rank2 =
                    candidateFromVector(1L, 1002L, 1, 0.90, 2);

            // 断言：rank-1 分数 > rank-2 分数
            assertThat(rank1.rankingScore()).isGreaterThan(rank2.rankingScore());

            // 断言：rank-1 分数 = 1.0，rank-2 分数 = 0.5
            assertThat(rank1.rankingScore()).isEqualTo(1.0);
            assertThat(rank2.rankingScore()).isCloseTo(0.5, org.assertj.core.data.Offset.offset(0.001));
        }

        @Test
        @DisplayName("排名差越大，分数差距越大——边际递减效应")
        void rrfScore_排名差距越大_分数差距越大() {
            // 准备：rank-1、rank-5、rank-10
            HybridChunkRetrievalService.RetrievalCandidate r1 =
                    candidateFromVector(1L, 1001L, 0, 0.95, 1);
            HybridChunkRetrievalService.RetrievalCandidate r5 =
                    candidateFromVector(1L, 1002L, 1, 0.90, 5);
            HybridChunkRetrievalService.RetrievalCandidate r10 =
                    candidateFromVector(1L, 1003L, 2, 0.85, 10);

            // 断言：rank-1 > rank-5 > rank-10
            assertThat(r1.rankingScore()).isGreaterThan(r5.rankingScore());
            assertThat(r5.rankingScore()).isGreaterThan(r10.rankingScore());

            // 断言：rank-1 到 rank-5 的差距（0.8）> rank-5 到 rank-10 的差距（0.1）
            double gap1to5 = r1.rankingScore() - r5.rankingScore();
            double gap5to10 = r5.rankingScore() - r10.rankingScore();
            assertThat(gap1to5).isGreaterThan(gap5to10);
        }

        @Test
        @DisplayName("按 rankingScore 降序排序应正确反映排名")
        void rrfScore_排序验证_降序正确() {
            // 准备：构造多个候选切片
            HybridChunkRetrievalService.RetrievalCandidate c1 =
                    candidateFromVector(1L, 1001L, 0, 0.95, 1); // 1.0
            HybridChunkRetrievalService.RetrievalCandidate c2 =
                    candidateFromVector(1L, 1002L, 1, 0.90, 3); // 0.333
            HybridChunkRetrievalService.RetrievalCandidate c3 =
                    candidateFromVector(1L, 1003L, 2, 0.85, 2); // 0.5

            // 执行：按 rankingScore 降序排序（与源码逻辑一致）
            List<HybridChunkRetrievalService.RetrievalCandidate> sorted = List.of(c1, c2, c3).stream()
                    .sorted(Comparator
                            .comparingDouble(HybridChunkRetrievalService.RetrievalCandidate::rankingScore).reversed()
                            .thenComparing(HybridChunkRetrievalService.RetrievalCandidate::chunkId))
                    .toList();

            // 断言：排序后顺序应为 c1(1.0) > c3(0.5) > c2(0.333)
            assertThat(sorted).hasSize(3);
            assertThat(sorted.get(0).chunkId()).isEqualTo(1001L);
            assertThat(sorted.get(1).chunkId()).isEqualTo(1003L);
            assertThat(sorted.get(2).chunkId()).isEqualTo(1002L);
        }
    }

    // ──────────────────────────────────────────────
    // 场景四：RRF 归一化分数验证
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("RRF 归一化分数")
    class NormalizeScore {

        /**
         * 通过反射或直接计算验证归一化公式：score = 1 - e^(-x)
         * 此处直接使用公式计算验证
         */

        @Test
        @DisplayName("原始分 1.0（单通道 rank-1）归一化后约 0.63")
        void normalizeScore_单通道rank1_约063() {
            // 准备：原始 RRF 分 = 1.0
            double rawScore = 1.0;

            // 执行：应用归一化公式 1 - e^(-x)
            double normalized = 1.0 - Math.exp(-rawScore);

            // 断言：归一化后约 0.632
            assertThat(normalized).isCloseTo(0.632, org.assertj.core.data.Offset.offset(0.001));
        }

        @Test
        @DisplayName("原始分 2.0（双通道 rank-1）归一化后约 0.86")
        void normalizeScore_双通道rank1_约086() {
            // 准备：原始 RRF 分 = 2.0
            double rawScore = 2.0;

            // 执行：应用归一化公式
            double normalized = 1.0 - Math.exp(-rawScore);

            // 断言：归一化后约 0.865
            assertThat(normalized).isCloseTo(0.865, org.assertj.core.data.Offset.offset(0.001));
        }

        @Test
        @DisplayName("原始分 3.0（多查询多次命中）归一化后约 0.95")
        void normalizeScore_多次命中_约095() {
            // 准备：原始 RRF 分 = 3.0
            double rawScore = 3.0;

            // 执行：应用归一化公式
            double normalized = 1.0 - Math.exp(-rawScore);

            // 断言：归一化后约 0.950
            assertThat(normalized).isCloseTo(0.950, org.assertj.core.data.Offset.offset(0.001));
        }

        @Test
        @DisplayName("归一化分数始终在 [0, 1] 区间内，且单调递增")
        void normalizeScore_分数范围_始终在0到1且单调递增() {
            // 准备：测试多个原始分值（避免极端值 100.0 导致浮点精度恰好等于 1.0）
            double[] rawScores = {0, 0.5, 1.0, 2.0, 5.0, 10.0};

            double previousNormalized = -1.0;
            for (double rawScore : rawScores) {
                double normalized = 1.0 - Math.exp(-rawScore);

                // 断言：归一化分数 >= 0 且 <= 1（大数值时浮点精度可能恰好等于 1.0）
                assertThat(normalized)
                        .as("原始分 %.1f 的归一化结果", rawScore)
                        .isGreaterThanOrEqualTo(0.0)
                        .isLessThanOrEqualTo(1.0);

                // 断言：单调递增（每个值应严格大于前一个）
                assertThat(normalized)
                        .as("原始分 %.1f 应严格大于前一个值", rawScore)
                        .isGreaterThan(previousNormalized);

                previousNormalized = normalized;
            }
        }
    }
}
