-- 学校适配申请（第二轮新增）：用户主动提交的高校教务适配需求
-- 安全约束：绝不接收、存储任何教务系统账号密码；user_authorized 必须为 TRUE 才会落库。

CREATE TABLE IF NOT EXISTS school_adaptation_requests (
    id VARCHAR(64) PRIMARY KEY,
    school_name VARCHAR(200) NOT NULL,
    edu_system_type VARCHAR(64) NOT NULL DEFAULT 'UNKNOWN',
    portal_url VARCHAR(512),
    page_summary TEXT,
    user_authorized BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(32) NOT NULL DEFAULT 'SUBMITTED',
    created_at BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_adapt_request_created ON school_adaptation_requests(created_at);
