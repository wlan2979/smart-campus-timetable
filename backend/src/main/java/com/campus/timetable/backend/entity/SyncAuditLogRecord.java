package com.campus.timetable.backend.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "sync_audit_logs")
public class SyncAuditLogRecord {
    @Id
    private String id;
    @Column(name = "user_id", nullable = false)
    private String userId;
    @Column(name = "device_id")
    private String deviceId;
    @Column(nullable = false)
    private String direction;
    @Column(nullable = false)
    private String status;
    @Column(name = "client_version", nullable = false)
    private long clientVersion;
    @Column(name = "server_version", nullable = false)
    private long serverVersion;
    private String summary;
    @Column(name = "created_at", nullable = false)
    private long createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public long getClientVersion() { return clientVersion; }
    public void setClientVersion(long clientVersion) { this.clientVersion = clientVersion; }
    public long getServerVersion() { return serverVersion; }
    public void setServerVersion(long serverVersion) { this.serverVersion = serverVersion; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}
