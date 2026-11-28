package com.campus.timetable.backend.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "user_sync_snapshots")
public class UserSyncSnapshotRecord {
    @Id
    @Column(name = "user_id")
    private String userId;
    @Column(name = "sync_version", nullable = false)
    private long syncVersion;
    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;
    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;
    @Column(name = "last_device_id")
    private String lastDeviceId;
    @Column(name = "updated_at", nullable = false)
    private long updatedAt;

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public long getSyncVersion() { return syncVersion; }
    public void setSyncVersion(long syncVersion) { this.syncVersion = syncVersion; }
    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public String getLastDeviceId() { return lastDeviceId; }
    public void setLastDeviceId(String lastDeviceId) { this.lastDeviceId = lastDeviceId; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}
