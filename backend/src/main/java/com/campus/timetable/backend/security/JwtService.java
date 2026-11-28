package com.campus.timetable.backend.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

@Service
public class JwtService {

    private final SecretKey key;
    private final long accessExpSeconds;
    private final long refreshExpSeconds;

    public JwtService(
            Environment environment,
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.access-expiration-seconds:7200}") long accessExpSeconds,
            @Value("${app.jwt.refresh-expiration-seconds:2592000}") long refreshExpSeconds
    ) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT_SECRET 长度必须至少为 32 字节");
        }
        boolean isProd = environment != null && Arrays.stream(environment.getActiveProfiles())
                .anyMatch(p -> "prod".equalsIgnoreCase(p) || "production".equalsIgnoreCase(p));
        String lower = secret.toLowerCase(Locale.ROOT);
        if (isProd && (lower.contains("dev-only") || lower.contains("insecure") || lower.contains("change-me") || lower.contains("change_me"))) {
            throw new IllegalStateException("生产环境严禁使用默认/占位 JWT_SECRET，请通过环境变量注入至少 32 字节的高熵随机密钥");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessExpSeconds = accessExpSeconds;
        this.refreshExpSeconds = refreshExpSeconds;
    }

    public String generateAccessToken(String userId, String phone) {
        return generateToken(userId, phone, "access", accessExpSeconds);
    }

    public String generateRefreshToken(String userId, String phone) {
        return generateToken(userId, phone, "refresh", refreshExpSeconds);
    }

    private String generateToken(String userId, String phone, String type, long ttlSeconds) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId)
                .claim("phone", phone)
                .claim("type", type)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(key)
                .compact();
    }

    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
