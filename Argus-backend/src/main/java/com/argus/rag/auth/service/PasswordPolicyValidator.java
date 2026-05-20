package com.argus.rag.auth.service;

import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.user.service.AccountService;

import java.nio.charset.StandardCharsets;

import static com.argus.rag.auth.constant.AuthConstant.*;

/**
 * 密码策略校验：最小长度、字母+数字组合、BCrypt 字节上限。
 * <p>
 * 供 {@link AuthService} 注册和 {@link AccountService} 修改密码共用。
 */
public class PasswordPolicyValidator {

    /**
     * 校验密码复杂度并检查 BCrypt 字节上限
     */
    public static void validate(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw new BusinessException(INVALID_MESSAGE);
        }
        if (password.length() > MAX_LENGTH) {
            throw new BusinessException("密码长度非法");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new BusinessException("密码长度超过安全上限，请控制在 72 字节以内");
        }
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < password.length(); i++) {
            char current = password.charAt(i);
            if (Character.isLetter(current)) {
                hasLetter = true;
            }
            if (Character.isDigit(current)) {
                hasDigit = true;
            }
        }
        if (!hasLetter || !hasDigit) {
            throw new BusinessException(INVALID_MESSAGE);
        }
    }
}
