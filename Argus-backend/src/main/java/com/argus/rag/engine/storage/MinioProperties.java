package com.argus.rag.engine.storage;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * MinIO 对象存储配置属性，前缀 {@code storage.minio}。
 */
@Validated
@ConfigurationProperties(prefix = "storage.minio")
@Data
public class MinioProperties {

    @NotBlank
    private String endpoint;

    @NotBlank
    private String accessKey;

    @NotBlank
    private String secretKey;

    private String bucket = "argus-rag-documents";
}
