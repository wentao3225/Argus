package com.argus.rag.auth.security;

import com.argus.rag.auth.config.AuthProperties;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

import static com.argus.rag.auth.constant.AuthConstant.COOKIE_PATH;
import static com.argus.rag.auth.constant.AuthConstant.SAME_SITE_POLICY;

/**
 * Refresh Token Cookie 读写工具。
 * <p>
 * Refresh token 存储为 httpOnly、Secure（生产）、SameSite=Lax 的 Cookie。
 */
@Component
@RequiredArgsConstructor
public class AuthCookieSupport {

    private final AuthProperties authProperties;

    /**
     * 写入 refresh token Cookie
     */
    public void writeRefreshTokenCookie(HttpServletResponse response, String refreshToken) {
        response.addHeader(
                HttpHeaders.SET_COOKIE,
                buildCookie(refreshToken, Duration.ofDays(authProperties.getRefreshTokenExpireDays())).toString()
        );
    }

    /**
     * 清除 refresh token Cookie（登出时调用）
     */
    public void clearRefreshTokenCookie(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, buildCookie("", Duration.ZERO).toString());
    }

    private ResponseCookie buildCookie(String value, Duration maxAge) {
        return ResponseCookie.from(authProperties.getRefreshCookieName(), value)
                .httpOnly(true)
                .secure(authProperties.isRefreshCookieSecure())
                .path(COOKIE_PATH)
                .sameSite(SAME_SITE_POLICY)
                .maxAge(maxAge)
                .build();
    }
}
