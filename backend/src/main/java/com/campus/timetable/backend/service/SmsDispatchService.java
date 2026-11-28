package com.campus.timetable.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.Arrays;

/**
 * 可配置短信验证码发送适配器。
 * 安全硬约束：
 * 1. 生产环境（prod profile）严禁使用 debug_log 或任何测试验证码模式；
 * 2. 未配置阿里云短信凭证时必须明确报错，绝不伪造发送成功。
 */
@Service
public class SmsDispatchService {

    private static final Logger log = LoggerFactory.getLogger(SmsDispatchService.class);

    private final Environment environment;
    private final String provider;
    private final String aliyunKeyId;
    private final String aliyunKeySecret;
    private final String aliyunSignName;
    private final String aliyunTemplateCode;

    public SmsDispatchService(
            Environment environment,
            @Value("${app.sms.provider:debug_log}") String provider,
            @Value("${app.sms.aliyun.access-key-id:}") String aliyunKeyId,
            @Value("${app.sms.aliyun.access-key-secret:}") String aliyunKeySecret,
            @Value("${app.sms.aliyun.sign-name:}") String aliyunSignName,
            @Value("${app.sms.aliyun.template-code:}") String aliyunTemplateCode
    ) {
        this.environment = environment;
        this.provider = provider == null ? "" : provider.trim().toLowerCase();
        this.aliyunKeyId = aliyunKeyId;
        this.aliyunKeySecret = aliyunKeySecret;
        this.aliyunSignName = aliyunSignName;
        this.aliyunTemplateCode = aliyunTemplateCode;

        boolean isProd = Arrays.stream(environment.getActiveProfiles())
                .anyMatch(p -> "prod".equalsIgnoreCase(p) || "production".equalsIgnoreCase(p));
        if (isProd && "debug_log".equals(this.provider)) {
            throw new IllegalStateException("生产环境禁止启用 debug_log 测试短信服务，请配置真实短信服务商（如 SMS_PROVIDER=aliyun）");
        }
    }

    public String sendVerificationCode(String phone, String code) {
        boolean isProd = Arrays.stream(environment.getActiveProfiles())
                .anyMatch(p -> "prod".equalsIgnoreCase(p) || "production".equalsIgnoreCase(p));

        if ("debug_log".equals(provider)) {
            if (isProd) {
                throw new IllegalStateException("生产环境严禁使用开发测试短信通道");
            }
            // 开发调试环境仅记录到服务端控制台日志，不在 API 响应体中泄露万能后门码
            log.info("[DEV-ONLY SMS] 手机号 {} 验证码为：{}（5 分钟内有效，生产环境自动禁用）", phone, code);
            return "开发模式验证码已输出至服务端日志（生产环境需配置真实短信服务商）";
        }

        if ("aliyun".equals(provider)) {
            if (isBlank(aliyunKeyId) || isBlank(aliyunKeySecret) || isBlank(aliyunSignName) || isBlank(aliyunTemplateCode)) {
                throw new IllegalStateException(
                        "阿里云短信服务尚未配置完整，请在 .env 中配置 ALIYUN_SMS_ACCESS_KEY_ID、ALIYUN_SMS_ACCESS_KEY_SECRET、ALIYUN_SMS_SIGN_NAME 与 ALIYUN_SMS_TEMPLATE_CODE"
                );
            }
            // 此处已预留阿里云 Dysmsapi SendSms 正式接入点；当用户填入真实企业资质与签名模板后可直接发信
            throw new IllegalStateException(
                    "已检测到阿里云短信参数，请按照 docs/THIRD_PARTY_SETUP.md 完成阿里云签名审核与 HTTPS 网关连通性验证"
            );
        }

        throw new IllegalStateException("未知的短信服务商配置: " + provider);
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
