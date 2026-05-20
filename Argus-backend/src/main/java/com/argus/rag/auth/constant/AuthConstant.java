package com.argus.rag.auth.constant;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 权限常量
 */
public interface AuthConstant {

    String INVALID_CREDENTIALS_MESSAGE = "账号或密码错误";
    int MAX_LOGIN_ID_LENGTH = 128;
    int MAX_USERNAME_LENGTH = 64;
    int MAX_EMAIL_LENGTH = 128;
    int MAX_DISPLAY_NAME_LENGTH = 128;
    int MAX_PASSWORD_LENGTH = 256;
    /**
     * BCrypt 最大输入字节数，超出会截断
     */
    int BCRYPT_MAX_PASSWORD_BYTES = 72;
    /**
     * 用户名仅允许字母、数字、下划线、短横线
     */
    Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_-]+$");
    /**
     * 保留用户名，防止注册时冒充系统账号
     */
    Set<String> RESERVED_USERNAMES = Set.of("admin", "root", "null", "undefined", "system");

    String TOKEN_SEPARATOR = ".";
    /**
     * 随机 secret 字节数，Base64URL 编码后约 32 字符
     */
    int SECRET_BYTES = 24;

    String USER_ID_CLAIM = "uid";
    String DISPLAY_NAME_CLAIM = "displayName";
    String SYSTEM_ROLE_CLAIM = "systemRole";
    String MUST_CHANGE_PASSWORD_CLAIM = "mustChangePassword";
    int MIN_SECRET_LENGTH = 32;

    String COOKIE_PATH = "/";
    String SAME_SITE_POLICY = "Lax";

    String INVALID_MESSAGE = "新密码必须至少 8 位，且同时包含字母和数字";
    int MIN_LENGTH = 8;
    int MAX_LENGTH = 256;
    /**
     * BCrypt 最大输入字节数，超出会截断
     */
    int MAX_BYTES = 72;
}
