package com.argus.rag.qa.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

/**
 * 重排序器配置类。
 * <p>
 * 通过 {@code rag.qa.reranker.enabled=true} 启用。
 * 仅在启用时才创建 ChatClient 和 PromptTemplate Bean，
 * 此时 {@link com.argus.rag.qa.rag.ChunkRerankerService} 也会被激活。
 * </p>
 */
@Configuration
@ConditionalOnProperty(value = "rag.qa.reranker.enabled", havingValue = "true")
public class RerankerConfiguration {

    /**
     * 创建重排序专用的 ChatClient。
     * <p>与 QA 用同一个模型（Kimimoonshot-v1-8k），但独立配置便于后续更换。</p>
     *
     * @param chatClientBuilder Spring AI 提供的 ChatClient 构造器
     * @return 重排序专用的 ChatClient 实例
     */
    @Bean("rerankerChatClient")
    public ChatClient rerankerChatClient(ChatClient.Builder chatClientBuilder) {
        return chatClientBuilder.build();
    }

    /**
     * 加载重排序 Prompt 模板。
     * <p>模板文件路径：{@code prompts/reranker/user.st}</p>
     *
     * @return 重排序 Prompt 模板
     */
    @Bean("rerankerUserPromptTemplate")
    public PromptTemplate rerankerUserPromptTemplate() {
        return PromptTemplate.builder()
                .resource(new ClassPathResource("prompts/reranker/user.st"))
                .build();
    }
}