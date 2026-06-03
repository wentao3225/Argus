package com.argus.rag.ingestion.service.pipeline.transformer;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 文档分块配置属性，绑定前缀 {@code ingestion.chunking}。
 */
@ConfigurationProperties(prefix = "ingestion.chunking")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ChunkingProperties {

    /**
     * 目标 token 数
     */
    private int targetTokens = 500;
    /**
     * 最大 token 数
     */
    private int maxTokens = 800;
    /**
     * 重叠 token 数
     */
    private int overlapTokens = 80;
}
