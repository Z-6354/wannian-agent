package com.wannian.server.app.manage;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 只拦截 {@code /api/manage/**}。本机回环地址不校验口令；其余来源必须带配置文件或环境变量里的口令。
 * 不信任 {@code X-Forwarded-For}。口令用常量时间比较。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ManageAuthFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;
    private final String configuredToken;

    public ManageAuthFilter(ObjectMapper objectMapper, ManageTokenFile tokenFile) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        String token = Objects.requireNonNull(tokenFile, "tokenFile").token();
        this.configuredToken = token == null ? "" : token;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || !path.startsWith("/api/manage/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        switch (decide(request.getRemoteAddr(), request.getHeader("Authorization"), configuredToken)) {
            case ALLOW -> filterChain.doFilter(request, response);
            case UNCONFIGURED ->
                    write(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, ManageReason.MANAGE_UNCONFIGURED, "管理口令未配置");
            case UNAUTHENTICATED ->
                    write(response, HttpServletResponse.SC_UNAUTHORIZED, ManageReason.UNAUTHENTICATED, "管理口令不正确");
        }
    }

    static Decision decide(String remoteAddr, String authorization, String configuredToken) {
        if (isLoopback(remoteAddr)) {
            return Decision.ALLOW;
        }
        if (configuredToken == null || configuredToken.isBlank()) {
            return Decision.UNCONFIGURED;
        }
        String presented = bearer(authorization);
        if (presented == null || !constantTimeEquals(configuredToken, presented)) {
            return Decision.UNAUTHENTICATED;
        }
        return Decision.ALLOW;
    }

    static boolean isLoopback(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return false;
        }
        String host = remoteAddr;
        int zone = host.indexOf('%');
        if (zone >= 0) {
            host = host.substring(0, zone);
        }
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (UnknownHostException ex) {
            return false;
        }
    }

    enum Decision {
        ALLOW,
        UNCONFIGURED,
        UNAUTHENTICATED
    }

    private static String bearer(String header) {
        if (header == null) {
            return null;
        }
        String prefix = "Bearer ";
        if (!header.startsWith(prefix) || header.length() == prefix.length()) {
            return null;
        }
        return header.substring(prefix.length());
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private void write(HttpServletResponse response, int status, String code, String detail) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), new ManageBodies.ErrorBody(code, detail));
    }
}
