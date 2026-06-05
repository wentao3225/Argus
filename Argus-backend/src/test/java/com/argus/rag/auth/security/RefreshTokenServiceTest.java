package com.argus.rag.auth.security;

import com.argus.rag.auth.config.AuthProperties;
import com.argus.rag.auth.mapper.UserRefreshTokenMapper;
import com.argus.rag.auth.model.entity.UserRefreshToken;
import com.argus.rag.auth.service.PasswordHasher;
import com.argus.rag.auth.service.RefreshTokenRecord;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static com.argus.rag.auth.constant.AuthConstant.TOKEN_SEPARATOR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link RefreshTokenService} 单元测试。
 * <p>
 * 使用 {@link MockitoExtension} 手动扩展，Mock 所有外部依赖（Mapper、Hasher、Clock），
 * 重点测试 Token 签发格式、有效性查找、吊销标记等核心安全逻辑。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RefreshTokenService 单元测试")
class RefreshTokenServiceTest {

    // ─── Mock 依赖 ───────────────────────────────────────

    /**
     * 固定的"当前时间"：2026-06-05 12:00:00
     */
    private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 6, 5, 12, 0, 0);
    /**
     * 将 FIXED_NOW 转为 Instant，用于构造 Clock
     */
    private static final Instant FIXED_INSTANT = FIXED_NOW.atZone(ZoneId.systemDefault()).toInstant();

    // ─── 固定时钟，用于精确控制时间 ───────────────────────
    /**
     * 默认 refresh token 过期天数
     */
    private static final int DEFAULT_EXPIRE_DAYS = 14;
    @Mock
    private UserRefreshTokenMapper tokenMapper;
    @Mock
    private PasswordHasher passwordHasher;

    // ─── 被测服务 ───────────────────────────────────────
    private Clock fixedClock;
    private RefreshTokenService refreshTokenService;

    @BeforeEach
    void setUp() {
        // 构造固定时钟，确保时间可控
        fixedClock = Clock.fixed(FIXED_INSTANT, ZoneId.systemDefault());

        // 构造 AuthProperties，设置 refresh token 过期天数为 14 天
        AuthProperties authProperties = new AuthProperties();
        authProperties.setRefreshTokenExpireDays(DEFAULT_EXPIRE_DAYS);

        // 用 @Mock 字段和固定时钟构造被测服务
        refreshTokenService = new RefreshTokenService(
                tokenMapper,
                passwordHasher,
                authProperties,
                fixedClock
        );
    }

    // ──────────────────────────────────────────────
    // 签发 Token 场景
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("签发 Token")
    class IssueToken {

        @Test
        @DisplayName("正常签发应返回 tokenId.secret 格式的 token")
        void issueToken_正常签发_返回token格式正确() {
            // 准备：Mock 密码哈希方法，返回固定哈希值
            when(passwordHasher.hash(anyString())).thenReturn("$2a$10$fakeHashValue");

            // 执行：调用签发方法
            RefreshTokenService.IssuedRefreshToken result = refreshTokenService.issueToken(1L);

            // 断言：返回的 refreshToken 不为空
            assertThat(result.refreshToken()).isNotBlank();

            // 断言：token 格式为 tokenId.secret，以 "." 分隔且恰好有 2 段
            String[] parts = result.refreshToken().split("\\.", 2);
            assertThat(parts).hasSize(2);
            // 断言：tokenId 部分不为空（UUID 去横线，长度为 32）
            assertThat(parts[0]).isNotBlank();
            assertThat(parts[0]).hasSize(32);
            // 断言：secret 部分不为空（Base64URL 编码）
            assertThat(parts[1]).isNotBlank();

            // 断言：record 中的 userId 正确
            assertThat(result.record().userId()).isEqualTo(1L);

            // 断言：record 中的 tokenId 与 token 的第一段一致
            assertThat(result.record().tokenId()).isEqualTo(parts[0]);
        }

        @Test
        @DisplayName("签发时应设置正确的过期时间——当前时间 + 14 天")
        void issueToken_过期时间正确() {
            // 准备：Mock 密码哈希方法
            when(passwordHasher.hash(anyString())).thenReturn("$2a$10$fakeHashValue");

            // 执行：调用签发方法
            RefreshTokenService.IssuedRefreshToken result = refreshTokenService.issueToken(1L);

            // 断言：过期时间 = FIXED_NOW + 14 天 = 2026-06-19 12:00:00
            LocalDateTime expectedExpiresAt = FIXED_NOW.plusDays(DEFAULT_EXPIRE_DAYS);
            assertThat(result.record().expiresAt()).isEqualTo(expectedExpiresAt);
        }

        @Test
        @DisplayName("签发时应将 token 哈希存入数据库")
        void issueToken_哈希存入数据库() {
            // 准备：Mock 密码哈希方法返回固定值
            String fakeHash = "$2a$10$fakeHashValue";
            when(passwordHasher.hash(anyString())).thenReturn(fakeHash);

            // 执行：调用签发方法
            refreshTokenService.issueToken(1L);

            // 断言：passwordHasher.hash() 被调用了一次
            verify(passwordHasher, times(1)).hash(anyString());

            // 断言：tokenMapper.insert() 被调用了一次
            verify(tokenMapper, times(1)).insert(any(UserRefreshToken.class));

            // 断言：捕获 insert 的实体参数，验证 tokenHash 字段正确
            ArgumentCaptor<UserRefreshToken> entityCaptor = ArgumentCaptor.forClass(UserRefreshToken.class);
            verify(tokenMapper).insert(entityCaptor.capture());
            UserRefreshToken savedEntity = entityCaptor.getValue();
            assertThat(savedEntity.getTokenHash()).isEqualTo(fakeHash);
        }

        @Test
        @DisplayName("签发时应设置 createdAt 为当前时间")
        void issueToken_创建时间正确() {
            // 准备：Mock 密码哈希方法
            when(passwordHasher.hash(anyString())).thenReturn("$2a$10$fakeHashValue");

            // 执行：调用签发方法
            RefreshTokenService.IssuedRefreshToken result = refreshTokenService.issueToken(1L);

            // 断言：createdAt 应等于固定当前时间
            assertThat(result.record().expiresAt()).isNotNull();
        }
    }

    // ──────────────────────────────────────────────
    // 查找有效 Token 场景
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("查找有效 Token")
    class FindActiveToken {

        /**
         * 辅助方法：构造一个有效的 refresh token 字符串
         */
        private String buildFakeRefreshToken(String tokenId, String secret) {
            return tokenId + TOKEN_SEPARATOR + secret;
        }

        /**
         * 辅助方法：构造一个数据库中的 token 实体
         */
        private UserRefreshToken buildTokenEntity(
                Long id, Long userId, String tokenId,
                LocalDateTime expiresAt, LocalDateTime revokedAt) {
            UserRefreshToken entity = new UserRefreshToken();
            entity.setId(id);
            entity.setUserId(userId);
            entity.setTokenId(tokenId);
            entity.setTokenHash("fakeHash");
            entity.setExpiresAt(expiresAt);
            entity.setRevokedAt(revokedAt);
            entity.setCreatedAt(FIXED_NOW.minusDays(1));
            return entity;
        }

        @Test
        @DisplayName("有效 token（未过期未吊销）应返回记录")
        void findActiveToken_有效token_返回Record() {
            // 准备：构造 token 数据
            String tokenId = "abc123def456abc123def456abc123de";
            String secret = "fakeSecretValue";
            String refreshToken = buildFakeRefreshToken(tokenId, secret);

            // 准备：构造数据库返回的实体（未过期、未吊销）
            LocalDateTime expiresAt = FIXED_NOW.plusDays(7); // 7 天后过期
            UserRefreshToken entity = buildTokenEntity(100L, 1L, tokenId, expiresAt, null);

            // 准备：Mock mapper 返回实体
            when(tokenMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(entity);

            // 准备：Mock 哈希比对返回 true（token 匹配）
            when(passwordHasher.matches(eq(refreshToken), anyString())).thenReturn(true);

            // 执行：查找有效 token
            Optional<RefreshTokenRecord> result = refreshTokenService.findActiveToken(refreshToken);

            // 断言：应返回非空结果
            assertThat(result).isPresent();

            // 断言：返回记录的字段正确
            RefreshTokenRecord record = result.get();
            assertThat(record.id()).isEqualTo(100L);
            assertThat(record.userId()).isEqualTo(1L);
            assertThat(record.tokenId()).isEqualTo(tokenId);
            assertThat(record.expiresAt()).isEqualTo(expiresAt);
            assertThat(record.revokedAt()).isNull();
        }

        @Test
        @DisplayName("已过期 token 应返回空——isActive 检查失败")
        void findActiveToken_已过期token_返回空() {
            // 准备：构造 token 数据
            String tokenId = "abc123def456abc123def456abc123de";
            String secret = "fakeSecretValue";
            String refreshToken = buildFakeRefreshToken(tokenId, secret);

            // 准备：构造数据库返回的实体（已过期：过期时间早于当前时间）
            LocalDateTime expiresAt = FIXED_NOW.minusDays(1); // 1 天前已过期
            UserRefreshToken entity = buildTokenEntity(100L, 1L, tokenId, expiresAt, null);

            // 准备：Mock mapper 返回实体
            when(tokenMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(entity);

            // 准备：Mock 哈希比对返回 true
            when(passwordHasher.matches(eq(refreshToken), anyString())).thenReturn(true);

            // 执行：查找有效 token
            Optional<RefreshTokenRecord> result = refreshTokenService.findActiveToken(refreshToken);

            // 断言：已过期，应返回空
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("已吊销 token 应返回空——isActive 检查失败")
        void findActiveToken_已吊销token_返回空() {
            // 准备：构造 token 数据
            String tokenId = "abc123def456abc123def456abc123de";
            String secret = "fakeSecretValue";
            String refreshToken = buildFakeRefreshToken(tokenId, secret);

            // 准备：构造数据库返回的实体（已吊销：revokedAt 不为 null）
            LocalDateTime expiresAt = FIXED_NOW.plusDays(7); // 未过期
            LocalDateTime revokedAt = FIXED_NOW.minusHours(1); // 但已吊销
            UserRefreshToken entity = buildTokenEntity(100L, 1L, tokenId, expiresAt, revokedAt);

            // 准备：Mock mapper 返回实体
            when(tokenMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(entity);

            // 准备：Mock 哈希比对返回 true
            when(passwordHasher.matches(eq(refreshToken), anyString())).thenReturn(true);

            // 执行：查找有效 token
            Optional<RefreshTokenRecord> result = refreshTokenService.findActiveToken(refreshToken);

            // 断言：已吊销，应返回空
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("哈希不匹配应返回空——疑似 token 篡改")
        void findActiveToken_哈希不匹配_返回空() {
            // 准备：构造 token 数据
            String tokenId = "abc123def456abc123def456abc123de";
            String secret = "fakeSecretValue";
            String refreshToken = buildFakeRefreshToken(tokenId, secret);

            // 准备：构造数据库返回的实体（未过期、未吊销）
            LocalDateTime expiresAt = FIXED_NOW.plusDays(7);
            UserRefreshToken entity = buildTokenEntity(100L, 1L, tokenId, expiresAt, null);

            // 准备：Mock mapper 返回实体
            when(tokenMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(entity);

            // 准备：Mock 哈希比对返回 false（token 不匹配，疑似篡改）
            when(passwordHasher.matches(eq(refreshToken), anyString())).thenReturn(false);

            // 执行：查找有效 token
            Optional<RefreshTokenRecord> result = refreshTokenService.findActiveToken(refreshToken);

            // 断言：哈希不匹配，应返回空
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("数据库中不存在该 tokenId 应返回空")
        void findActiveToken_tokenId不存在_返回空() {
            // 准备：构造 token 数据
            String tokenId = "nonexistent000000000000000000000";
            String secret = "fakeSecretValue";
            String refreshToken = buildFakeRefreshToken(tokenId, secret);

            // 准备：Mock mapper 返回 null（数据库中不存在）
            when(tokenMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            // 执行：查找有效 token
            Optional<RefreshTokenRecord> result = refreshTokenService.findActiveToken(refreshToken);

            // 断言：不存在，应返回空
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("null 输入应返回空——解析阶段拦截")
        void findActiveToken_null输入_返回空() {
            // 执行：传入 null
            Optional<RefreshTokenRecord> result = refreshTokenService.findActiveToken(null);

            // 断言：null 输入应返回空
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("格式错误的 token 应返回空——缺少分隔符")
        void findActiveToken_格式错误_返回空() {
            // 执行：传入不含 "." 分隔符的字符串
            Optional<RefreshTokenRecord> result = refreshTokenService.findActiveToken("invalidtokenwithoutseparator");

            // 断言：格式错误，应返回空
            assertThat(result).isEmpty();
        }
    }

    // ──────────────────────────────────────────────
    // 吊销 Token 场景
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("吊销 Token")
    class RevokeActiveTokens {

        @Test
        @DisplayName("正常吊销应标记 revokedAt 为当前时间")
        void revokeActiveTokens_正常吊销_标记revokedAt() {
            // 执行：吊销 userId=1 的所有有效 token
            refreshTokenService.revokeActiveTokens(1L);

            // 断言：tokenMapper.update() 应被调用一次
            verify(tokenMapper, times(1)).update(any(UserRefreshToken.class), any(LambdaQueryWrapper.class));

            // 断言：捕获 update 的第一个参数（更新实体），验证 revokedAt 字段
            ArgumentCaptor<UserRefreshToken> updateCaptor = ArgumentCaptor.forClass(UserRefreshToken.class);
            verify(tokenMapper).update(updateCaptor.capture(), any(LambdaQueryWrapper.class));

            UserRefreshToken updateEntity = updateCaptor.getValue();
            // 断言：revokedAt 应等于当前固定时间
            assertThat(updateEntity.getRevokedAt()).isEqualTo(FIXED_NOW);
        }

        @Test
        @DisplayName("吊销后应设置 revokedAt 而不影响其他字段")
        void revokeActiveTokens_仅设置revokedAt() {
            // 执行：吊销 userId=1 的所有有效 token
            refreshTokenService.revokeActiveTokens(1L);

            // 断言：捕获 update 实体，验证仅设置了 revokedAt
            ArgumentCaptor<UserRefreshToken> updateCaptor = ArgumentCaptor.forClass(UserRefreshToken.class);
            verify(tokenMapper).update(updateCaptor.capture(), any(LambdaQueryWrapper.class));

            UserRefreshToken updateEntity = updateCaptor.getValue();
            // 断言：revokedAt 被设置
            assertThat(updateEntity.getRevokedAt()).isNotNull();
            // 断言：userId、tokenId 等字段应为 null（仅更新 revokedAt）
            assertThat(updateEntity.getUserId()).isNull();
            assertThat(updateEntity.getTokenId()).isNull();
            assertThat(updateEntity.getTokenHash()).isNull();
        }

        @Test
        @DisplayName("对不存在的用户吊销不应报错——空操作")
        void revokeActiveTokens_不存在的用户_不报错() {
            // 准备：Mock update 返回 0（无受影响行）
            when(tokenMapper.update(any(UserRefreshToken.class), any(LambdaQueryWrapper.class)))
                    .thenReturn(0);

            // 执行：吊销一个不存在的用户
            refreshTokenService.revokeActiveTokens(999L);

            // 断言：update 仍应被调用（只是没有匹配行）
            verify(tokenMapper, times(1)).update(any(UserRefreshToken.class), any(LambdaQueryWrapper.class));
        }
    }
}
