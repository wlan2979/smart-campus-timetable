package com.campus.timetable.backend.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "sms_verification_codes")
public class SmsVerificationCodeRecord {
    @Id
    private String id;
    @Column(nullable = false)
    private String phone;
    @Column(name = "code_hash", nullable = false)
    private String codeHash;
    @Column(name = "expires_at", nullable = false)
    private long expiresAt;
    @Column(nullable = false)
    private boolean used;
    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts = 0;
    @Column(name = "created_at", nullable = false)
    private long createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getCodeHash() { return codeHash; }
    public void setCodeHash(String codeHash) { this.codeHash = codeHash; }
    public long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(long expiresAt) { this.expiresAt = expiresAt; }
    public boolean isUsed() { return used; }
    public void setUsed(boolean used) { this.used = used; }
    public int getFailedAttempts() { return failedAttempts; }
    public void setFailedAttempts(int failedAttempts) { this.failedAttempts = failedAttempts; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}
