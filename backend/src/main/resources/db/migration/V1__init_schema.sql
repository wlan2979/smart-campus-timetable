-- 智能校园课表服务端初始数据库结构 (PostgreSQL 16 / H2 PostgreSQL 兼容模式)

CREATE TABLE IF NOT EXISTS users (
    id VARCHAR(64) PRIMARY KEY,
    phone VARCHAR(32) NOT NULL UNIQUE,
    nickname VARCHAR(100) NOT NULL,
    sync_version BIGINT NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS sms_verification_codes (
    id VARCHAR(64) PRIMARY KEY,
    phone VARCHAR(32) NOT NULL,
    code_hash VARCHAR(128) NOT NULL,
    expires_at BIGINT NOT NULL,
    used BOOLEAN NOT NULL DEFAULT FALSE,
    created_at BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sms_phone_created ON sms_verification_codes(phone, created_at);

CREATE TABLE IF NOT EXISTS user_sync_snapshots (
    user_id VARCHAR(64) PRIMARY KEY,
    sync_version BIGINT NOT NULL DEFAULT 0,
    schema_version INT NOT NULL DEFAULT 1,
    payload_json TEXT NOT NULL,
    last_device_id VARCHAR(128),
    updated_at BIGINT NOT NULL,
    CONSTRAINT fk_snapshot_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS sync_audit_logs (
    id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(128),
    direction VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    client_version BIGINT NOT NULL,
    server_version BIGINT NOT NULL,
    summary VARCHAR(512),
    created_at BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sync_audit_user ON sync_audit_logs(user_id, created_at);

CREATE TABLE IF NOT EXISTS holidays (
    id VARCHAR(64) PRIMARY KEY,
    holiday_year INT NOT NULL,
    holiday_date VARCHAR(16) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    holiday_type VARCHAR(32) NOT NULL,
    is_off_day BOOLEAN NOT NULL,
    source VARCHAR(255) NOT NULL,
    version VARCHAR(64) NOT NULL,
    updated_at BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_holidays_year ON holidays(holiday_year);

CREATE TABLE IF NOT EXISTS schools (
    id VARCHAR(64) PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(150) NOT NULL,
    province VARCHAR(64) NOT NULL,
    city VARCHAR(64) NOT NULL,
    school_level VARCHAR(32) NOT NULL,
    system_type VARCHAR(32) NOT NULL,
    adapter_id VARCHAR(64),
    portal_url VARCHAR(255),
    verified BOOLEAN NOT NULL DEFAULT FALSE,
    status_note VARCHAR(512) NOT NULL,
    updated_at BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_schools_name ON schools(name);
