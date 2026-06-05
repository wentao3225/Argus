package com.argus.rag.document.service;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.document.mapper.DocumentMapper;
import com.argus.rag.document.mapper.DocumentUploadChunkMapper;
import com.argus.rag.document.mapper.DocumentUploadSessionMapper;
import com.argus.rag.document.model.dto.UploadInitRequest;
import com.argus.rag.document.model.entity.DocumentEntity;
import com.argus.rag.document.model.entity.DocumentUploadChunkEntity;
import com.argus.rag.document.model.entity.DocumentUploadSessionEntity;
import com.argus.rag.document.model.vo.UploadInitResponse;
import com.argus.rag.engine.storage.ObjectStorageService;
import com.argus.rag.group.service.GroupMembershipService;
import com.argus.rag.ingestion.vector.VectorIngestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;

import static com.argus.rag.document.constant.DocumentConstant.UPLOAD_STATUS_INIT;
import static com.argus.rag.document.constant.DocumentConstant.UPLOAD_STATUS_UPLOADING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link DocumentUploadService#initUpload} 单元测试。
 * <p>
 * 使用 {@link MockitoExtension} Mock 所有外部依赖（Mapper、ObjectStorage、EventPublisher），
 * 重点测试分片上传初始化的三大场景：秒传、断点续传、新建会话。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentUploadService 分片上传初始化测试")
class DocumentUploadServiceTest {

    // ─── Mock 依赖 ───────────────────────────────────────

    /**
     * 测试用固定用户
     */
    private static final CurrentUserService.CurrentUser FIXED_USER =
            new CurrentUserService.CurrentUser(1L, "user1", "测试用户",
                    com.argus.rag.common.enums.SystemRole.USER, false);
    /**
     * 测试用固定群组 ID
     */
    private static final Long GROUP_ID = 100L;
    /**
     * 测试用固定文件哈希
     */
    private static final String FILE_HASH = "abc123def456abc123def456abc123def456abc123def456abc123def456abcd";
    @Mock
    private DocumentMapper documentMapper;
    @Mock
    private DocumentUploadSessionMapper documentUploadSessionMapper;
    @Mock
    private DocumentUploadChunkMapper documentUploadChunkMapper;
    @Mock
    private GroupMembershipService groupMembershipService;

    // ─── 被测服务 ───────────────────────────────────────
    @Mock
    private ObjectStorageService objectStorageService;
    @Mock
    private VectorIngestionService vectorIngestionService;
    @Mock
    private ApplicationEventPublisher applicationEventPublisher;
    private DocumentUploadService documentUploadService;

    /**
     * 辅助方法：构造一个合法的 UploadInitRequest
     */
    private static UploadInitRequest buildValidRequest() {
        return new UploadInitRequest(
                GROUP_ID,
                "测试文档.pdf",
                20L * 1024 * 1024,    // 20MB
                "application/pdf",
                FILE_HASH,
                10L * 1024 * 1024,    // 每片 10MB
                2                      // 2 片
        );
    }

    /**
     * 辅助方法：构造一个数据库中已存在的 READY 状态文档
     */
    private static DocumentEntity buildReadyDocument() {
        DocumentEntity doc = new DocumentEntity();
        doc.setId(999L);
        doc.setGroupId(GROUP_ID);
        doc.setUploaderUserId(2L);
        doc.setFileName("已有文档.pdf");
        doc.setFileExt("pdf");
        doc.setContentType("application/pdf");
        doc.setFileSize(15L * 1024 * 1024);
        doc.setFileHash(FILE_HASH);
        doc.setStorageBucket("argus-documents");
        doc.setStorageObjectKey("groups/100/users/2/existing.pdf");
        doc.setStatus("READY");
        return doc;
    }

    /**
     * 辅助方法：构造一个已有的上传会话（未完成）
     */
    private static DocumentUploadSessionEntity buildExistingSession() {
        DocumentUploadSessionEntity session = new DocumentUploadSessionEntity();
        session.setId(200L);
        session.setUploadId("existing-upload-id-001");
        session.setGroupId(GROUP_ID);
        session.setUploaderUserId(FIXED_USER.userId());
        session.setFileName("测试文档.pdf");
        session.setFileExt("pdf");
        session.setContentType("application/pdf");
        session.setFileSize(20L * 1024 * 1024);
        session.setFileHash(FILE_HASH);
        session.setChunkSize(10L * 1024 * 1024);
        session.setChunkCount(2);
        session.setStatus(UPLOAD_STATUS_UPLOADING);
        session.setStorageBucket("argus-documents");
        session.setExpiresAt(LocalDateTime.now().plusHours(23));
        return session;
    }

    /**
     * 辅助方法：构造一个已上传的分片记录
     */
    private static DocumentUploadChunkEntity buildUploadedChunk(String uploadId, int chunkIndex) {
        DocumentUploadChunkEntity chunk = new DocumentUploadChunkEntity();
        chunk.setId((long) chunkIndex + 1);
        chunk.setUploadId(uploadId);
        chunk.setChunkIndex(chunkIndex);
        chunk.setChunkSize(10L * 1024 * 1024);
        chunk.setChunkHash("chunkHash" + chunkIndex);
        chunk.setStorageBucket("argus-documents");
        chunk.setStorageObjectKey("chunks/" + uploadId + "/" + chunkIndex);
        chunk.setUploadedAt(LocalDateTime.now().minusMinutes(5));
        chunk.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        chunk.setUpdatedAt(LocalDateTime.now().minusMinutes(5));
        return chunk;
    }

    @BeforeEach
    void setUp() {
        documentUploadService = new DocumentUploadService(
                documentMapper,
                documentUploadSessionMapper,
                documentUploadChunkMapper,
                groupMembershipService,
                objectStorageService,
                vectorIngestionService,
                applicationEventPublisher
        );
    }

    // ──────────────────────────────────────────────
    // 场景一：新文件——创建全新上传会话
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("新建上传会话")
    class NewUploadSession {

        @Test
        @DisplayName("新文件应创建全新上传会话并返回 uploadId")
        void initUpload_新文件_创建uploadSession() throws Exception {
            // 准备：构造合法的上传初始化请求
            UploadInitRequest request = buildValidRequest();

            // 准备：Mock 权限校验——当前用户是群组 Owner
            when(groupMembershipService.requireGroupOwner(GROUP_ID)).thenReturn(FIXED_USER);

            // 准备：Mock 秒传检查——不存在相同哈希的 READY 文档
            when(documentMapper.selectByGroupIdAndFileHash(GROUP_ID, FILE_HASH)).thenReturn(null);

            // 准备：Mock 断点续传检查——不存在可复用的上传会话
            when(documentUploadSessionMapper.selectLatestReusableSession(GROUP_ID, FIXED_USER.userId(), FILE_HASH))
                    .thenReturn(null);

            // 准备：Mock 默认存储桶
            when(objectStorageService.getDefaultBucket()).thenReturn("argus-documents");

            // 准备：Mock insert 成功（MyBatis-Plus BaseMapper）
            when(documentUploadSessionMapper.insert(any(DocumentUploadSessionEntity.class))).thenReturn(1);

            // 执行：调用 initUpload
            UploadInitResponse response = documentUploadService.initUpload(request);

            // 断言：响应不是秒传
            assertThat(response.instantUpload()).isFalse();

            // 断言：返回了有效的 uploadId（非空、非空字符串）
            assertThat(response.uploadId()).isNotBlank();

            // 断言：返回了正确的分片参数
            assertThat(response.chunkSize()).isEqualTo(10L * 1024 * 1024);
            assertThat(response.chunkCount()).isEqualTo(2);

            // 断言：已上传分片列表为空（全新会话）
            assertThat(response.uploadedChunks()).isEmpty();

            // 断言：sessionMapper.insert 被调用了一次
            verify(documentUploadSessionMapper, times(1))
                    .insert(any(DocumentUploadSessionEntity.class));

            // 断言：捕获 insert 的实体参数，验证关键字段
            ArgumentCaptor<DocumentUploadSessionEntity> captor =
                    ArgumentCaptor.forClass(DocumentUploadSessionEntity.class);
            verify(documentUploadSessionMapper).insert(captor.capture());
            DocumentUploadSessionEntity savedSession = captor.getValue();

            // 断言：会话状态为 INIT
            assertThat(savedSession.getStatus()).isEqualTo(UPLOAD_STATUS_INIT);

            // 断言：会话归属正确
            assertThat(savedSession.getGroupId()).isEqualTo(GROUP_ID);
            assertThat(savedSession.getUploaderUserId()).isEqualTo(FIXED_USER.userId());

            // 断言：会话文件信息正确
            assertThat(savedSession.getFileName()).isEqualTo("测试文档.pdf");
            assertThat(savedSession.getFileHash()).isEqualTo(FILE_HASH);

            // 断言：会话过期时间已设置（应为当前时间 + 24 小时）
            assertThat(savedSession.getExpiresAt()).isNotNull();
            assertThat(savedSession.getExpiresAt()).isAfter(LocalDateTime.now());

            // 断言：存储桶已设置
            assertThat(savedSession.getStorageBucket()).isEqualTo("argus-documents");
        }
    }

    // ──────────────────────────────────────────────
    // 场景二：秒传——相同哈希文件已存在
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("秒传复用")
    class InstantUpload {

        @Test
        @DisplayName("相同哈希的 READY 文档应触发秒传，返回已有 documentId")
        void initUpload_相同哈希文件_触发秒传() {
            // 准备：构造合法的上传初始化请求
            UploadInitRequest request = buildValidRequest();

            // 准备：Mock 权限校验——当前用户是群组 Owner
            when(groupMembershipService.requireGroupOwner(GROUP_ID)).thenReturn(FIXED_USER);

            // 准备：Mock 秒传检查——返回已有的 READY 文档
            DocumentEntity existingDoc = buildReadyDocument();
            when(documentMapper.selectByGroupIdAndFileHash(GROUP_ID, FILE_HASH))
                    .thenReturn(existingDoc);

            // 准备：Mock 文档插入成功，并模拟 MyBatis-Plus 自增 ID 回填
            //        persistAndFinalizeUploadedDocument 插入后直接读 document.getId()，
            //        但 mock 的 insert() 不会自动设置自增 ID，需要用 doAnswer 模拟回填
            doAnswer(invocation -> {
                DocumentEntity entity = invocation.getArgument(0);
                entity.setId(888L); // 模拟数据库自增生成的 ID
                return 1;
            }).when(documentMapper).insert(any(DocumentEntity.class));

            // 执行：调用 initUpload
            UploadInitResponse response = documentUploadService.initUpload(request);

            // 断言：响应为秒传
            assertThat(response.instantUpload()).isTrue();

            // 断言：返回了已有文档关联的新 documentId（由 doAnswer 模拟的自增 ID）
            assertThat(response.documentId()).isEqualTo(888L);

            // 断言：秒传不应返回 uploadId（因为无需上传）
            assertThat(response.uploadId()).isNull();

            // 断言：秒传不应返回分片参数
            assertThat(response.chunkSize()).isNull();
            assertThat(response.chunkCount()).isNull();

            // 断言：文档插入被调用（秒传会创建新的文档记录）
            verify(documentMapper, times(1)).insert(any(DocumentEntity.class));

            // 断言：事件发布器被调用（触发异步 ETL），且传入了正确的 documentId
            ArgumentCaptor<DocumentIngestionRequestedEvent> eventCaptor =
                    ArgumentCaptor.forClass(DocumentIngestionRequestedEvent.class);
            verify(applicationEventPublisher, times(1)).publishEvent(eventCaptor.capture());
            assertThat(eventCaptor.getValue().documentId()).isEqualTo(888L);

            // 断言：断点续传检查未被调用（秒传优先级更高，命中后直接返回）
            verify(documentUploadSessionMapper, never())
                    .selectLatestReusableSession(anyLong(), anyLong(), anyString());
        }
    }

    // ──────────────────────────────────────────────
    // 场景三：断点续传——已有未完成会话
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("断点续传")
    class ResumeUpload {

        @Test
        @DisplayName("已有未完成会话应恢复上传，返回已上传分片列表")
        void initUpload_已有未完成会话_触发断点续传() {
            // 准备：构造合法的上传初始化请求
            UploadInitRequest request = buildValidRequest();

            // 准备：Mock 权限校验——当前用户是群组 Owner
            when(groupMembershipService.requireGroupOwner(GROUP_ID)).thenReturn(FIXED_USER);

            // 准备：Mock 秒传检查——不存在相同哈希的 READY 文档
            when(documentMapper.selectByGroupIdAndFileHash(GROUP_ID, FILE_HASH)).thenReturn(null);

            // 准备：构造已有的未完成上传会话
            DocumentUploadSessionEntity existingSession = buildExistingSession();
            when(documentUploadSessionMapper.selectLatestReusableSession(GROUP_ID, FIXED_USER.userId(), FILE_HASH))
                    .thenReturn(existingSession);

            // 准备：Mock 查询已上传分片——假设第 0 片已上传
            DocumentUploadChunkEntity uploadedChunk = buildUploadedChunk(existingSession.getUploadId(), 0);
            when(documentUploadChunkMapper.selectByUploadId(existingSession.getUploadId()))
                    .thenReturn(List.of(uploadedChunk));

            // 执行：调用 initUpload
            UploadInitResponse response = documentUploadService.initUpload(request);

            // 断言：响应不是秒传
            assertThat(response.instantUpload()).isFalse();

            // 断言：返回了已有的 uploadId（而非新建）
            assertThat(response.uploadId()).isEqualTo(existingSession.getUploadId());

            // 断言：返回了已上传分片列表——第 0 片
            assertThat(response.uploadedChunks()).containsExactly(0);

            // 断言：返回了正确的分片参数
            assertThat(response.chunkSize()).isEqualTo(existingSession.getChunkSize());
            assertThat(response.chunkCount()).isEqualTo(existingSession.getChunkCount());

            // 断言：sessionMapper.insert 未被调用（复用已有会话，未创建新会话）
            verify(documentUploadSessionMapper, never())
                    .insert(any(DocumentUploadSessionEntity.class));

            // 断言：selectLatestReusableSession 被正确调用
            verify(documentUploadSessionMapper, times(1))
                    .selectLatestReusableSession(GROUP_ID, FIXED_USER.userId(), FILE_HASH);

            // 断言：selectByUploadId 被正确调用（查询已上传分片）
            verify(documentUploadChunkMapper, times(1))
                    .selectByUploadId(existingSession.getUploadId());
        }

        @Test
        @DisplayName("续传时所有分片均已上传应返回完整分片列表")
        void initUpload_所有分片已上传_返回完整列表() {
            // 准备：构造合法的上传初始化请求
            UploadInitRequest request = buildValidRequest();

            // 准备：Mock 权限校验
            when(groupMembershipService.requireGroupOwner(GROUP_ID)).thenReturn(FIXED_USER);

            // 准备：Mock 秒传检查——不存在
            when(documentMapper.selectByGroupIdAndFileHash(GROUP_ID, FILE_HASH)).thenReturn(null);

            // 准备：构造已有的未完成上传会话
            DocumentUploadSessionEntity existingSession = buildExistingSession();
            when(documentUploadSessionMapper.selectLatestReusableSession(GROUP_ID, FIXED_USER.userId(), FILE_HASH))
                    .thenReturn(existingSession);

            // 准备：Mock 查询已上传分片——两个分片都已上传
            DocumentUploadChunkEntity chunk0 = buildUploadedChunk(existingSession.getUploadId(), 0);
            DocumentUploadChunkEntity chunk1 = buildUploadedChunk(existingSession.getUploadId(), 1);
            when(documentUploadChunkMapper.selectByUploadId(existingSession.getUploadId()))
                    .thenReturn(List.of(chunk0, chunk1));

            // 执行：调用 initUpload
            UploadInitResponse response = documentUploadService.initUpload(request);

            // 断言：返回了两个已上传分片索引
            assertThat(response.uploadedChunks()).containsExactly(0, 1);
        }
    }
}
