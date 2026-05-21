package com.argus.rag.document.constant;

import java.util.Set;

/**
 * 文档常量
 */
public interface DocumentConstant {

    /**
     * 文件名最大长度
     */
    int MAX_FILE_NAME_LENGTH = 255;
    /**
     * Content-Type 最大长度
     */
    int MAX_CONTENT_TYPE_LENGTH = 128;
    /**
     * 文件哈希最大长度
     */
    int MAX_FILE_HASH_LENGTH = 128;
    /**
     * 文件扩展名最大长度
     */
    int MAX_FILE_EXT_LENGTH = 16;
    /**
     * 分片上传最大文件大小：256MB
     */
    long MAX_FILE_SIZE = 256L * 1024 * 1024;
    /**
     * 单个分片的最大大小：10MB
     */
    long MAX_CHUNK_SIZE = 10L * 1024 * 1024;
    /**
     * 上传会话过期时长：24 小时
     */
    long SESSION_EXPIRE_HOURS = 24L;
    /**
     * 支持的上传文件格式
     */
    Set<String> SUPPORTED_EXTENSIONS = Set.of("txt", "md", "pdf", "docx");
    /**
     * 上传会话状态：已初始化
     */
    String UPLOAD_STATUS_INIT = "INIT";
    /**
     * 上传会话状态：上传中
     */
    String UPLOAD_STATUS_UPLOADING = "UPLOADING";
    /**
     * 上传会话状态：正在合并
     */
    String UPLOAD_STATUS_COMPLETING = "COMPLETING";
    /**
     * 上传会话状态：已完成
     */
    String UPLOAD_STATUS_COMPLETED = "COMPLETED";
    /**
     * 分片默认 MIME 类型
     */
    String OCTET_STREAM = "application/octet-stream";
    /**
     * 直接上传最大文件大小：10MB
     */
    long MAX_DIRECT_FILE_SIZE = 10L * 1024 * 1024;
}
