package com.argus.rag.auth.service;

import com.argus.rag.auth.config.AuthConfiguration;
import com.argus.rag.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PasswordHasher} 单元测试。
 * <p>
 * 直接复制 {@link AuthConfiguration#defaultPasswordHasher()} 的 Bean 创建逻辑，
 * 无需 Spring 容器，纯单元测试。
 * </p>
 */
@DisplayName("PasswordHasher BCrypt 密码哈希测试")
class PasswordHasherTest {

    /**
     * 被测实例：复刻 AuthConfiguration 中的 Bean 创建逻辑
     */
    private PasswordHasher passwordHasher;

    @BeforeEach
    void setUp() {
        // 复刻 AuthConfiguration.defaultPasswordHasher() 的逻辑
        // 创建真实的 BCryptPasswordEncoder 并包装为 PasswordHasher
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        passwordHasher = new PasswordHasher() {
            private static final int BCRYPT_MAX_INPUT_BYTES = 72;

            @Override
            public String hash(String rawPassword) {
                validateInputLength(rawPassword);
                return encoder.encode(rawPassword);
            }

            @Override
            public boolean matches(String rawPassword, String passwordHash) {
                validateInputLength(rawPassword);
                return encoder.matches(rawPassword, passwordHash);
            }

            private void validateInputLength(String rawValue) {
                if (rawValue == null || rawValue.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_INPUT_BYTES) {
                    throw new BusinessException("密码长度超过安全上限，请控制在 72 字节以内");
                }
            }
        };
    }

    // ──────────────────────────────────────────────
    // BCrypt 加盐特性场景
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("BCrypt 加盐特性")
    class SaltFeature {

        @Test
        @DisplayName("同一密码每次哈希结果应不同——BCrypt 自动加随机盐")
        void hash_同一密码_每次哈希不同() {
            // 准备：同一个明文密码
            String rawPassword = "mySecurePassword123";

            // 执行：连续哈希两次
            String hash1 = passwordHasher.hash(rawPassword);
            String hash2 = passwordHasher.hash(rawPassword);

            // 断言：两次哈希结果不同（因为每次生成不同的随机盐）
            assertThat(hash1).isNotEqualTo(hash2);

            // 断言：两个哈希值都以 "$2" 开头（BCrypt 标识）
            assertThat(hash1).startsWith("$2");
            assertThat(hash2).startsWith("$2");

            // 断言：两个哈希值都能正确匹配原始密码
            assertThat(passwordHasher.matches(rawPassword, hash1)).isTrue();
            assertThat(passwordHasher.matches(rawPassword, hash2)).isTrue();
        }

        @Test
        @DisplayName("不同密码的哈希结果应不同")
        void hash_不同密码_哈希结果不同() {
            // 执行：对两个不同密码分别哈希
            String hash1 = passwordHasher.hash("password_A_123");
            String hash2 = passwordHasher.hash("password_B_456");

            // 断言：哈希结果不同
            assertThat(hash1).isNotEqualTo(hash2);
        }
    }

    // ──────────────────────────────────────────────
    // matches 配对验证场景
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("matches 配对验证")
    class MatchesValidation {

        @Test
        @DisplayName("正确密码应返回 true——hash 与 matches 配对验证")
        void matches_正确密码_返回true() {
            // 准备：先对密码做哈希
            String rawPassword = "correctPassword1";
            String storedHash = passwordHasher.hash(rawPassword);

            // 执行：用正确密码验证
            boolean result = passwordHasher.matches(rawPassword, storedHash);

            // 断言：应返回 true
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("错误密码应返回 false")
        void matches_错误密码_返回false() {
            // 准备：存储的哈希来自 "realPassword1"
            String storedHash = passwordHasher.hash("realPassword1");

            // 执行：用错误密码验证
            boolean result = passwordHasher.matches("wrongPassword1", storedHash);

            // 断言：应返回 false
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("空字符串密码验证应返回 false——不匹配任何哈希")
        void matches_空字符串密码_返回false() {
            // 准备：存储的哈希来自正常密码
            String storedHash = passwordHasher.hash("somePassword1");

            // 执行：用空字符串验证
            boolean result = passwordHasher.matches("", storedHash);

            // 断言：应返回 false
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("大小写不同的密码应返回 false——BCrypt 区分大小写")
        void matches_大小写不同_返回false() {
            // 准备：存储的哈希来自 "MyPassword1"
            String storedHash = passwordHasher.hash("MyPassword1");

            // 执行：用大小写不同的密码验证
            boolean result = passwordHasher.matches("mypassword1", storedHash);

            // 断言：大小写不同，应返回 false
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("多次验证同一密码应始终返回 true——哈希稳定性")
        void matches_多次验证_始终返回true() {
            // 准备：先对密码做哈希
            String rawPassword = "stablePassword1";
            String storedHash = passwordHasher.hash(rawPassword);

            // 执行 & 断言：连续验证 10 次，每次都应返回 true
            for (int i = 0; i < 10; i++) {
                assertThat(passwordHasher.matches(rawPassword, storedHash))
                        .as("第 %d 次验证应返回 true", i + 1)
                        .isTrue();
            }
        }
    }

    // ──────────────────────────────────────────────
    // 边界与异常场景
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("边界与异常")
    class EdgeCases {

        @Test
        @DisplayName("null 输入 hash 应抛出 BusinessException")
        void hash_null输入_抛异常() {
            // 执行 & 断言：null 输入应抛出异常
            assertThatThrownBy(() -> passwordHasher.hash(null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("密码长度超过安全上限，请控制在 72 字节以内");
        }

        @Test
        @DisplayName("超长输入（>72 字节）hash 应抛出 BusinessException")
        void hash_超长输入_抛异常() {
            // 准备：25 个中文字符 × 3 字节 = 75 字节 > 72
            String longPassword = "中".repeat(25);

            // 执行 & 断言：超过 72 字节应抛出异常
            assertThatThrownBy(() -> passwordHasher.hash(longPassword))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("密码长度超过安全上限，请控制在 72 字节以内");
        }

        @Test
        @DisplayName("null 输入 matches 应抛出 BusinessException")
        void matches_null输入_抛异常() {
            // 准备：一个有效的哈希值
            String validHash = passwordHasher.hash("password1");

            // 执行 & 断言：null 输入应抛出异常
            assertThatThrownBy(() -> passwordHasher.matches(null, validHash))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("密码长度超过安全上限，请控制在 72 字节以内");
        }

        @Test
        @DisplayName("恰好 72 字节的密码应正常哈希——边界值通过")
        void hash_恰好72字节_正常哈希() {
            // 准备：24 个中文字符 × 3 字节 = 72 字节，恰好等于上限
            String password = "中".repeat(24);

            // 执行：应正常哈希，不抛异常
            String hash = passwordHasher.hash(password);

            // 断言：哈希值有效，能正确匹配
            assertThat(hash).isNotBlank();
            assertThat(passwordHasher.matches(password, hash)).isTrue();
        }

        @Test
        @DisplayName("73 字节的密码应抛出 BusinessException——超出 1 字节")
        void hash_73字节_抛异常() {
            // 准备：24 个中文 (72字节) + 1 个英文 'a' (1字节) = 73 字节
            String password = "中".repeat(24) + "a";

            // 执行 & 断言：超出 1 字节也应被拦截
            assertThatThrownBy(() -> passwordHasher.hash(password))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("密码长度超过安全上限，请控制在 72 字节以内");
        }
    }
}
