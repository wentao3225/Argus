package com.argus.rag.engine.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 存储配置，注册 {@link MinioProperties} 以启用 {@code storage.minio.*} 配置绑定。
 */
@Configuration
@EnableConfigurationProperties(MinioProperties.class)
public class MinioStorageConfiguration {
}
