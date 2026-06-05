package com.argus.rag.ingestion.service.pipeline.transformer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link StructureAwareChunkTransformer} 单元测试。
 * <p>
 * 结构感知切片器，基于 Markdown 标题边界将文档拆分为 token 预算分块。
 * 支持段落拆分、句子拆分、贪心合并、相邻重叠。
 * </p>
 */
@DisplayName("StructureAwareChunkTransformer 结构感知切片测试")
class StructureAwareChunkTransformerTest {

    // ─── 工具方法 ───────────────────────────────────────

    /**
     * 根据指定参数构造 ChunkingProperties
     */
    private static ChunkingProperties props(int targetTokens, int maxTokens, int overlapTokens) {
        return new ChunkingProperties(targetTokens, maxTokens, overlapTokens);
    }

    /**
     * 构造一个带文本的 Document（id 为 "doc-1"）
     */
    private static Document doc(String text) {
        return Document.builder().id("doc-1").text(text).build();
    }

    // ──────────────────────────────────────────────
    // 场景一：纯文本按固定长度切分
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("纯文本切分")
    class PlainText {

        @Test
        @DisplayName("无标题时应按 maxTokens 固定长度切分")
        void transform_纯文本_按固定长度切分() {
            // 准备：target=10, max=10, overlap=0（无重叠简化验证）
            //        纯文本 30 个字符（无任何标题），无标题时整个文本作为一个章节
            //        因为 30 <= maxTokens(10)，会走段落→句子→硬截断路径
            //        按段落拆分（无空行则整段），超限后按句子拆分（无标点则硬截断）
            StructureAwareChunkTransformer transformer =
                    new StructureAwareChunkTransformer(props(10, 10, 0));

            String text = "abcdefghij"    // 10 字符 —— 第 1 段
                    + "\n\n"               // 段落分隔
                    + "klmnopqrst";       // 10 字符 —— 第 2 段

            Document document = doc(text);

            // 执行：对单文档列表调用 apply
            List<Document> chunks = transformer.apply(List.of(document));

            // 断言：应产生 2 个分块（每个段落 10 字符，恰好等于 maxTokens）
            assertThat(chunks).hasSize(2);

            // 断言：第 1 个分块内容为 "abcdefghij"
            assertThat(chunks.get(0).getText()).isEqualTo("abcdefghij");

            // 断言：第 2 个分块内容为 "klmnopqrst"
            assertThat(chunks.get(1).getText()).isEqualTo("klmnopqrst");
        }

        @Test
        @DisplayName("空文档列表应返回空列表")
        void transform_空列表_返回空() {
            // 准备：空的文档列表
            StructureAwareChunkTransformer transformer =
                    new StructureAwareChunkTransformer(props(100, 200, 0));

            // 执行
            List<Document> chunks = transformer.apply(List.of());

            // 断言：应返回空列表
            assertThat(chunks).isEmpty();
        }

        @Test
        @DisplayName("null 输入应返回空列表")
        void transform_null输入_返回空() {
            // 准备
            StructureAwareChunkTransformer transformer =
                    new StructureAwareChunkTransformer(props(100, 200, 0));

            // 执行
            List<Document> chunks = transformer.apply(null);

            // 断言
            assertThat(chunks).isEmpty();
        }

        @Test
        @DisplayName("空白文本应返回空列表")
        void transform_空白文本_返回空() {
            // 准备：仅包含空白字符的文本
            StructureAwareChunkTransformer transformer =
                    new StructureAwareChunkTransformer(props(100, 200, 0));

            Document document = doc("   \n\n  \t  ");

            // 执行
            List<Document> chunks = transformer.apply(List.of(document));

            // 断言：空白文本不应产生分块
            assertThat(chunks).isEmpty();
        }
    }

    // ──────────────────────────────────────────────
    // 场景二：有标题按标题层级切分
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("标题切分")
    class HeadingSplit {

        @Test
        @DisplayName("有标题时应在标题边界处切分，每个标题章节独立成为分块")
        void transform_有标题_按标题层级切分() {
            // 准备：target=30, max=30, overlap=0
            //        文档包含 2 个标题，每个章节内容较短（均 <= maxTokens）
            StructureAwareChunkTransformer transformer =
                    new StructureAwareChunkTransformer(props(30, 30, 0));

            String text = "# 第一章\n这是第一章的内容。\n\n# 第二章\n这是第二章的内容。";
            Document document = doc(text);

            // 执行
            List<Document> chunks = transformer.apply(List.of(document));

            // 断言：应产生 2 个分块（每个标题章节一个）
            assertThat(chunks).hasSize(2);

            // 断言：第 1 个分块包含 "第一章" 章节路径
            assertThat(chunks.get(0).getMetadata().get("sectionPath"))
                    .asString()
                    .contains("第一章");

            // 断言：第 2 个分块包含 "第二章" 章节路径
            assertThat(chunks.get(1).getMetadata().get("sectionPath"))
                    .asString()
                    .contains("第二章");
        }

        @Test
        @DisplayName("标题前的前导文本应作为独立分块")
        void transform_前导文本_作为独立分块() {
            // 准备：target=50, max=50, overlap=0
            //        标题前有一段前导文本
            StructureAwareChunkTransformer transformer =
                    new StructureAwareChunkTransformer(props(50, 50, 0));

            String text = "这是前导文本。\n\n# 标题一\n这是标题下的内容。";
            Document document = doc(text);

            // 执行
            List<Document> chunks = transformer.apply(List.of(document));

            // 断言：应产生 2 个分块（前导文本 + 标题章节）
            assertThat(chunks).hasSize(2);

            // 断言：第 1 个分块是前导文本（sectionPath 为空字符串）
            assertThat(chunks.get(0).getText()).contains("前导文本");

            // 断言：第 2 个分块是标题下的内容
            assertThat(chunks.get(1).getText()).contains("标题下的内容");
        }

        @Test
        @DisplayName("代码块内的 # 不应被识别为标题")
        void transform_代码块内标题_忽略() {
            // 准备：target=200, max=200, overlap=0
            //        代码块内的 # 不应被当作标题切分
            StructureAwareChunkTransformer transformer =
                    new StructureAwareChunkTransformer(props(200, 200, 0));

            String text = "正文开始。\n\n```\n# 这不是标题\n```\n\n正文结束。";
            Document document = doc(text);

            // 执行
            List<Document> chunks = transformer.apply(List.of(document));

            // 断言：整个文本应作为一个分块（代码块内的 # 被忽略）
            assertThat(chunks).hasSize(1);
            assertThat(chunks.getFirst().getText()).contains("# 这不是标题");
        }
    }

    // ──────────────────────────────────────────────
    // 场景三：切片 token 数不超过 max
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("Max Token 上限验证")
    class MaxTokenLimit {

        @Test
        @DisplayName("任何分块的 token 数不应超过 maxTokens=320")
        void transform_切片token数_不超过max() {
            // 准备：target=100, max=320, overlap=0
            //        构造一段超过 320 字符的长文本，确保被切分
            StructureAwareChunkTransformer transformer =
                    new StructureAwareChunkTransformer(props(100, 320, 0));

            // 生成 500 个字符的纯文本（无标题，无段落分隔）
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 500; i++) {
                sb.append("a");
            }
            Document document = doc(sb.toString());

            // 执行
            List<Document> chunks = transformer.apply(List.of(document));

            // 断言：应产生至少 2 个分块
            assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);

            // 断言：每个分块的文本长度不超过 maxTokens（即 320 字符）
            for (Document chunk : chunks) {
                assertThat(chunk.getText().length())
                        .as("分块文本长度不应超过 maxTokens=320，实际: %d", chunk.getText().length())
                        .isLessThanOrEqualTo(320);
            }
        }

        @Test
        @DisplayName("短文本不应被切分——不超过 maxTokens 时保持完整")
        void transform_短文本_保持完整() {
            // 准备：target=100, max=200, overlap=0
            //        50 字符的短文本，远低于 maxTokens
            StructureAwareChunkTransformer transformer =
                    new StructureAwareChunkTransformer(props(100, 200, 0));

            String text = "短文本内容，不会超过最大 token 限制。";
            Document document = doc(text);

            // 执行
            List<Document> chunks = transformer.apply(List.of(document));

            // 断言：应保持为 1 个完整分块
            assertThat(chunks).hasSize(1);
            assertThat(chunks.getFirst().getText()).isEqualTo(text);
        }
    }

    // ──────────────────────────────────────────────
    // 场景四：相邻切片有 overlap
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("相邻重叠验证")
    class Overlap {

        @Test
        @DisplayName("相邻分块应有 overlapTokens 个字符的重叠")
        void transform_相邻切片_有overlap() {
            // 准备：target=10, max=10, overlap=5
            //        两段文本通过段落分隔，max=10 时每个段落恰好 10 字符不会被切分
            //        但相邻分块应有 overlap=5 个字符的重叠
            StructureAwareChunkTransformer transformer =
                    new StructureAwareChunkTransformer(props(10, 10, 5));

            String text = "abcdefghij"    // 10 字符
                    + "\n\n"               // 段落分隔
                    + "klmnopqrst";       // 10 字符

            Document document = doc(text);

            // 执行
            List<Document> chunks = transformer.apply(List.of(document));

            // 断言：应产生 2 个分块
            assertThat(chunks).hasSize(2);

            // 断言：第 1 个分块内容为 "abcdefghij"
            assertThat(chunks.get(0).getText()).isEqualTo("abcdefghij");

            // 断言：第 2 个分块的起始部分应包含重叠内容
            //        第 2 段从索引 12 开始（"klmnopqrst"），overlap=5 意味着从索引 7 开始
            //        即 "hij" + "klmnopqrst" = "hijklmnopqrst"
            assertThat(chunks.get(1).getText()).startsWith("hij");

            // 断言：第 2 个分块应包含 "klmnopqrst"（第 2 段的完整内容）
            assertThat(chunks.get(1).getText()).contains("klmnopqrst");
        }

        @Test
        @DisplayName("overlap=0 时相邻分块不应有重叠")
        void transform_overlap为0_无重叠() {
            // 准备：target=10, max=10, overlap=0
            StructureAwareChunkTransformer transformer =
                    new StructureAwareChunkTransformer(props(10, 10, 0));

            String text = "abcdefghij"    // 10 字符
                    + "\n\n"               // 段落分隔
                    + "klmnopqrst";       // 10 字符

            Document document = doc(text);

            // 执行
            List<Document> chunks = transformer.apply(List.of(document));

            // 断言：应产生 2 个分块
            assertThat(chunks).hasSize(2);

            // 断言：第 2 个分块不应包含第 1 段的内容
            assertThat(chunks.get(1).getText()).doesNotContain("abcdefghij");

            // 断言：第 2 个分块精确为 "klmnopqrst"
            assertThat(chunks.get(1).getText()).isEqualTo("klmnopqrst");
        }
    }

    // ──────────────────────────────────────────────
    // 场景五：元数据与 ID 验证
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("元数据验证")
    class Metadata {

        @Test
        @DisplayName("每个分块应包含正确的元数据字段")
        void transform_分块_包含元数据() {
            // 准备：target=10, max=10, overlap=0
            StructureAwareChunkTransformer transformer =
                    new StructureAwareChunkTransformer(props(10, 10, 0));

            String text = "abcdefghij\n\nklmnopqrst";
            Document document = doc(text);

            // 执行
            List<Document> chunks = transformer.apply(List.of(document));

            // 断言：每个分块都应包含指定的元数据字段
            for (int i = 0; i < chunks.size(); i++) {
                Document chunk = chunks.get(i);

                // 断言：chunkStrategy 标识
                assertThat(chunk.getMetadata().get("chunkStrategy"))
                        .isEqualTo("structure-aware-token-budget-v1");

                // 断言：charStart 和 charEnd 存在
                assertThat(chunk.getMetadata()).containsKey("charStart");
                assertThat(chunk.getMetadata()).containsKey("charEnd");

                // 断言：charStart < charEnd
                int charStart = (int) chunk.getMetadata().get("charStart");
                int charEnd = (int) chunk.getMetadata().get("charEnd");
                assertThat(charStart).isLessThan(charEnd);
            }
        }

        @Test
        @DisplayName("分块 ID 应为 sourceId:index 格式")
        void transform_分块ID_格式正确() {
            // 准备
            StructureAwareChunkTransformer transformer =
                    new StructureAwareChunkTransformer(props(10, 10, 0));

            String text = "abcdefghij\n\nklmnopqrst";
            Document document = doc(text);

            // 执行
            List<Document> chunks = transformer.apply(List.of(document));

            // 断言：分块 ID 应为 "doc-1:0" 和 "doc-1:1"
            assertThat(chunks.get(0).getId()).isEqualTo("doc-1:0");
            assertThat(chunks.get(1).getId()).isEqualTo("doc-1:1");
        }
    }
}
