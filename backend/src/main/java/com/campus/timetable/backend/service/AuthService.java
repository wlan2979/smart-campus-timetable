package com.campus.timetable.backend.service;

import com.campus.timetable.backend.entity.SmsVerificationCodeRecord;
import com.campus.timetable.backend.entity.UserRecord;
import com.campus.timetable.backend.repo.*;
import com.campus.timetable.backend.security.JwtService;
import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Service
public class AuthService {

    private static final Pattern CN_PHONE = Pattern.compile("^1[3-9]\\d{9}$");
    private static final int MAX_VERIFY_ATTEMPTS = 5;
    private static final long IP_WINDOW_MILLIS = 10 * 60 * 1000L;
    private static final int MAX_SEND_PER_IP = 10;
    private static final int MAX_VERIFY_PER_IP = 25;

    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, Deque<Long>> sendIpWindow = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> verifyIpWindow = new ConcurrentHashMap<>();

    private final UserRepository userRepository;
    private final SmsCodeRepository smsCodeRepository;
    private final SyncSnapshotRepository syncSnapshotRepository;
    private final SyncAuditLogRepository syncAuditLogRepository;
    private final SmsDispatchService smsDispatchService;
    private final JwtService jwtService;
    private final long codeTtlSeconds;
    private final long rateLimitSeconds;

    public AuthService(
            UserRepository userRepository,
            SmsCodeRepository smsCodeRepository,
            SyncSnapshotRepository syncSnapshotRepository,
            SyncAuditLogRepository syncAuditLogRepository,
            SmsDispatchService smsDispatchService,
            JwtService jwtService,
            @Value("${app.sms.code-ttl-seconds:300}") long codeTtlSeconds,
            @Value("${app.sms.rate-limit-seconds:60}") long rateLimitSeconds
    ) {
        this.userRepository = userRepository;
        this.smsCodeRepository = smsCodeRepository;
        this.syncSnapshotRepository = syncSnapshotRepository;
        this.syncAuditLogRepository = syncAuditLogRepository;
        this.smsDispatchService = smsDispatchService;
        this.jwtService = jwtService;
        this.codeTtlSeconds = codeTtlSeconds;
        this.rateLimitSeconds = rateLimitSeconds;
    }

    @Transactional
    public Map<String, Object> sendSmsCode(String rawPhone) {
        return sendSmsCode(rawPhone, null);
    }

    @Transactional
    public Map<String, Object> sendSmsCode(String rawPhone, String clientIp) {
        String phone = rawPhone == null ? "" : rawPhone.trim();
        if (!CN_PHONE.matcher(phone).matches()) {
            throw new IllegalArgumentException("请输入有效的 11 位中国大陆手机号");
        }
        long now = System.currentTimeMillis();
        checkIpRateLimit(sendIpWindow, clientIp, MAX_SEND_PER_IP, now, "当前网络请求验证码过于频繁，请稍后再试");

        Optional<SmsVerificationCodeRecord> latest = smsCodeRepository.findTopByPhoneOrderByCreatedAtDesc(phone);
        if (latest.isPresent() && (now - latest.get().getCreatedAt()) < rateLimitSeconds * 1000L) {
            throw new IllegalArgumentException("验证码发送过于频繁，请 " + rateLimitSeconds + " 秒后再试");
        }

        String code = String.format("%06d", secureRandom.nextInt(1_000_000));
        String dispatchMessage = smsDispatchService.sendVerificationCode(phone, code);

        SmsVerificationCodeRecord rec = new SmsVerificationCodeRecord();
        rec.setId(UUID.randomUUID().toString());
        rec.setPhone(phone);
        rec.setCodeHash(sha256(phone + ":" + code));
        rec.setExpiresAt(now + codeTtlSeconds * 1000L);
        rec.setUsed(false);
        rec.setFailedAttempts(0);
        rec.setCreatedAt(now);
        smsCodeRepository.save(rec);

        return Map.of(
                "success", true,
                "message", dispatchMessage,
                "expiresInSeconds", (int) codeTtlSeconds
        );
    }

    @Transactional(noRollbackFor = {IllegalArgumentException.class, RateLimitException.class})
    public Map<String, Object> verifySmsCode(String rawPhone, String rawCode) {
        return verifySmsCode(rawPhone, rawCode, null);
    }

    /**
     * 校验 6 位短信验证码：
     * - noRollbackFor 确保输错计数（failedAttempts）和锁定状态（used=true）真实落库，防止暴力枚举。
     */
    @Transactional(noRollbackFor = {IllegalArgumentException.class, RateLimitException.class})
    public Map<String, Object> verifySmsCode(String rawPhone, String rawCode, String clientIp) {
        String phone = rawPhone == null ? "" : rawPhone.trim();
        String code = rawCode == null ? "" : rawCode.trim();
        if (!CN_PHONE.matcher(phone).matches() || code.length() != 6) {
            throw new IllegalArgumentException("手机号或 6 位验证码格式不正确");
        }
        long now = System.currentTimeMillis();
        checkIpRateLimit(verifyIpWindow, clientIp, MAX_VERIFY_PER_IP, now, "验证尝试过于频繁，请稍后再试");

        SmsVerificationCodeRecord rec = smsCodeRepository.findTopByPhoneOrderByCreatedAtDesc(phone)
                .orElseThrow(() -> new IllegalArgumentException("请先获取短信验证码"));
        if (rec.isUsed() || rec.getExpiresAt() < now || rec.getFailedAttempts() >= MAX_VERIFY_ATTEMPTS) {
            throw new IllegalArgumentException("验证码已失效，请重新获取");
        }
        if (!MessageDigest.isEqual(
                rec.getCodeHash().getBytes(StandardCharsets.UTF_8),
                sha256(phone + ":" + code).getBytes(StandardCharsets.UTF_8)
        )) {
            int attempts = rec.getFailedAttempts() + 1;
            rec.setFailedAttempts(attempts);
            if (attempts >= MAX_VERIFY_ATTEMPTS) {
                rec.setUsed(true);
            }
            smsCodeRepository.save(rec);
            if (attempts >= MAX_VERIFY_ATTEMPTS) {
                throw new IllegalArgumentException("验证码错误次数过多，已自动作废，请重新获取");
            }
            throw new IllegalArgumentException("短信验证码不正确（剩余 " + (MAX_VERIFY_ATTEMPTS - attempts) + " 次机会）");
        }
        rec.setUsed(true);
        smsCodeRepository.save(rec);

        UserRecord user = userRepository.findByPhone(phone).orElseGet(() -> {
            UserRecord u = new UserRecord();
            u.setId("u-" + UUID.randomUUID());
            u.setPhone(phone);
            u.setNickname("同学" + phone.substring(7));
            u.setSyncVersion(0L);
            u.setCreatedAt(now);
            u.setUpdatedAt(now);
            return userRepository.save(u);
        });

        String accessToken = jwtService.generateAccessToken(user.getId(), user.getPhone());
        String refreshToken = jwtService.generateRefreshToken(user.getId(), user.getPhone());

        return Map.of(
                "userId", user.getId(),
                "phone", user.getPhone(),
                "nickname", user.getNickname(),
                "accessToken", accessToken,
                "refreshToken", refreshToken,
                "syncVersion", user.getSyncVersion()
        );
    }

    private void checkIpRateLimit(Map<String, Deque<Long>> store, String clientIp, int maxInWindow, long now, String message) {
        if (clientIp == null || clientIp.isBlank()) return;
        Deque<Long> q = store.computeIfAbsent(clientIp.trim(), k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && (now - q.peekFirst()) > IP_WINDOW_MILLIS) {
                q.pollFirst();
            }
            if (q.size() >= maxInWindow) {
                throw new RateLimitException(message);
            }
            q.addLast(now);
        }
    }

    public Map<String, Object> refreshToken(String refreshToken) {
        Claims claims = jwtService.parseClaims(refreshToken);
        if (!"refresh".equals(claims.get("type", String.class))) {
            throw new IllegalArgumentException("无效的刷新令牌");
        }
        String userId = claims.getSubject();
        UserRecord user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("账号不存在或已注销"));
        return Map.of(
                "accessToken", jwtService.generateAccessToken(user.getId(), user.getPhone()),
                "refreshToken", jwtService.generateRefreshToken(user.getId(), user.getPhone())
        );
    }

    @Transactional
    public void deleteAccountAndCloudData(String userId) {
        UserRecord user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("账号不存在"));
        syncSnapshotRepository.deleteById(userId);
        syncAuditLogRepository.deleteByUserId(userId);
        smsCodeRepository.deleteByPhone(user.getPhone());
        userRepository.deleteById(userId);
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
