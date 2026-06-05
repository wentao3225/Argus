package com.argus.rag.auth.service;

import com.argus.rag.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.argus.rag.auth.constant.AuthConstant.INVALID_MESSAGE;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PasswordPolicyValidator} 单元测试。
 * <p>
 * 纯静态方法，零依赖，不需要 Spring 容器，不需要 Mock。
 * 覆盖密码复杂度（字母+数字）、最小长度、BCrypt 72 字节上限等校验逻辑。
 * </p>
 */
@DisplayName("PasswordPolicyValidator 密码策略校验测试")
class PasswordPolicyValidatorTest {

    // ──────────────────────────────────────────────
    // 合法密码场景
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("合法密码")
    class ValidPasswords {

        @Test
        @DisplayName("包含字母和数字的 8 位密码应通过校验")
        void validate_正常密码_不抛异常() {
            // 准备：8 位密码，同时包含字母和数字，满足所有校验条件
            String password = "abc12345";

            // 执行 & 断言：不应抛出任何异常
            assertThatCode(() -> PasswordPolicyValidator.validate(password))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("包含大小写字母和数字的长密码应通过校验")
        void validate_混合大小写_不抛异常() {
            // 准备：混合大小写字母 + 数字，长度适中
            String password = "AbC12345";

            // 执行 & 断言：不应抛出任何异常
            assertThatCode(() -> PasswordPolicyValidator.validate(password))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("仅含英文字母和数字的 72 字符密码应通过校验——每个字符 1 字节，恰好等于上限")
        void validate_72个ASCII字符_不抛异常() {
            // 准备：72 个 ASCII 字符（字母+数字），每个字符 UTF-8 占 1 字节，总计 72 字节
            //        交替排列字母和数字以同时满足复杂度要求
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 36; i++) {
                sb.append("a");  // 字母
                sb.append("1");  // 数字
            }
            String password = sb.toString(); // 长度=72，字节数=72

            // 执行 & 断言：72 个 ASCII 字符 = 72 字节，恰好等于上限，应通过校验
            assertThatCode(() -> PasswordPolicyValidator.validate(password))
                    .doesNotThrowAnyException();
        }
    }

    // ──────────────────────────────────────────────
    // 缺少字母或数字场景
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("缺少字母或数字")
    class MissingLetterOrDigit {

        @Test
        @DisplayName("纯数字密码应抛异常——缺少字母")
        void validate_纯数字_抛异常() {
            // 准备：8 位纯数字，满足长度但不包含字母
            String password = "12345678";

            // 执行 & 断言：应抛出 BusinessException，提示需包含字母和数字
            assertThatThrownBy(() -> PasswordPolicyValidator.validate(password))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage(INVALID_MESSAGE);
        }

        @Test
        @DisplayName("纯字母密码应抛异常——缺少数字")
        void validate_纯字母_抛异常() {
            // 准备：8 位纯字母，满足长度但不包含数字
            String password = "abcdefgh";

            // 执行 & 断言：应抛出 BusinessException，提示需包含字母和数字
            assertThatThrownBy(() -> PasswordPolicyValidator.validate(password))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage(INVALID_MESSAGE);
        }
    }

    // ──────────────────────────────────────────────
    // 长度校验场景
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("长度校验")
    class LengthValidation {

        @Test
        @DisplayName("密码低于最小长度 8 位应抛异常")
        void validate_过短_抛异常() {
            // 准备：仅 2 个字符，远低于 MIN_LENGTH=8
            String password = "a1";

            // 执行 & 断言：应抛出 BusinessException，提示密码格式不合法
            assertThatThrownBy(() -> PasswordPolicyValidator.validate(password))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage(INVALID_MESSAGE);
        }

        @Test
        @DisplayName("null 密码应抛异常")
        void validate_null密码_抛异常() {
            // 准备：password 为 null
            String password = null;

            // 执行 & 断言：null 输入应被长度校验拦截，抛出 BusinessException
            assertThatThrownBy(() -> PasswordPolicyValidator.validate(password))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage(INVALID_MESSAGE);
        }

        @Test
        @DisplayName("空字符串密码应抛异常")
        void validate_空字符串_抛异常() {
            // 准备：空字符串，长度为 0，低于 MIN_LENGTH=8
            String password = "";

            // 执行 & 断言：空字符串应被长度校验拦截
            assertThatThrownBy(() -> PasswordPolicyValidator.validate(password))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage(INVALID_MESSAGE);
        }

        @Test
        @DisplayName("恰好 7 字符的密码应抛异常——差一位不满足最小长度")
        void validate_差一位_抛异常() {
            // 准备：7 个字符，恰好比 MIN_LENGTH=8 少 1
            String password = "abc1234";

            // 执行 & 断言：差一位应被长度校验拦截
            assertThatThrownBy(() -> PasswordPolicyValidator.validate(password))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage(INVALID_MESSAGE);
        }

        @Test
        @DisplayName("超过 256 字符应抛异常——长度非法")
        void validate_超长字符数_抛异常() {
            // 准备：257 个字符，超过 MAX_LENGTH=256
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 257; i++) {
                sb.append(i % 2 == 0 ? "a" : "1");
            }
            String password = sb.toString();

            // 执行 & 断言：长度超过上限，应抛出 "密码长度非法"
            assertThatThrownBy(() -> PasswordPolicyValidator.validate(password))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("密码长度非法");
        }
    }

    // ──────────────────────────────────────────────
    // BCrypt 72 字节上限场景
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("BCrypt 72 字节上限")
    class BcryptByteLimit {

        @Test
        @DisplayName("超长中文密码应抛异常——UTF-8 字节数超过 72")
        void validate_超长字节_抛异常() {
            // 准备：中文字符在 UTF-8 下占 3 字节
            //        每个字符 3 字节 × 25 个 = 75 字节 > 72 字节上限
            //        同时追加数字以满足字母+数字的复杂度要求，确保只命中字节校验
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 25; i++) {
                sb.append("中");
            }
            sb.append("abc1234"); // 追加字母+数字，确保复杂度校验通过
            String password = sb.toString();

            // 执行 & 断言：UTF-8 字节数 = 25×3 + 7 = 82 > 72，应抛出字节上限异常
            assertThatThrownBy(() -> PasswordPolicyValidator.validate(password))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("密码长度超过安全上限，请控制在 72 字节以内");
        }

        @Test
        @DisplayName("23 个中文 + 'ab1' 的密码应通过字节校验——恰好 72 字节")
        void validate_恰好72字节_不抛异常() {
            // 准备：23 个中文字符 × 3 字节 = 69 字节
            //        + "ab1" (字母+数字) = 3 字节
            //        总计 UTF-8 字节数 = 69 + 3 = 72 字节，恰好等于上限
            //        总字符数 = 23 + 3 = 26 < 256，长度校验也通过
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 23; i++) {
                sb.append("中");
            }
            sb.append("ab1");
            String password = sb.toString();

            // 执行 & 断言：72 字节恰好等于上限，应通过校验
            assertThatCode(() -> PasswordPolicyValidator.validate(password))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("25 个中文字符的密码应抛异常——75 字节超出 1 字节")
        void validate_多1字节_抛异常() {
            // 准备：25 个中文字符 × 3 字节 = 75 字节，仅超出 1 字节
            //        追加字母+数字确保复杂度校验通过
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 25; i++) {
                sb.append("中");
            }
            sb.append("a1");
            String password = sb.toString();

            // 执行 & 断言：75 字节超出上限 1 字节，应抛出字节上限异常
            assertThatThrownBy(() -> PasswordPolicyValidator.validate(password))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("密码长度超过安全上限，请控制在 72 字节以内");
        }
    }
}
