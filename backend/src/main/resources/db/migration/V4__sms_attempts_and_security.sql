-- v2.2.4 安全加固：短信验证码错误尝试计数（单码输错上限锁定防暴力破解）
ALTER TABLE sms_verification_codes
    ADD COLUMN failed_attempts INT NOT NULL DEFAULT 0;
