package com.campus.timetable.backend.service;

import com.campus.timetable.backend.entity.SyncAuditLogRecord;
import com.campus.timetable.backend.entity.UserRecord;
import com.campus.timetable.backend.entity.UserSyncSnapshotRecord;
import com.campus.timetable.backend.repo.SyncAuditLogRepository;
import com.campus.timetable.backend.repo.SyncSnapshotRepository;
import com.campus.timetable.backend.repo.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class SyncService {

    private final UserRepository userRepository;
    private final SyncSnapshotRepository snapshotRepository;
    private final SyncAuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public SyncService(
            UserRepository userRepository,
            SyncSnapshotRepository snapshotRepository,
            SyncAuditLogRepository auditLogRepository,
            ObjectMapper objectMapper
    ) {
        this.userRepository = userRepository;
        this.snapshotRepository = snapshotRepository;
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> pull(String userId) throws Exception {
        Optional<UserSyncSnapshotRecord> existing = snapshotRepository.findById(userId);
        Map<String, Object> resp = new LinkedHashMap<>();
        if (existing.isEmpty()) {
            resp.put("hasSnapshot", false);
            resp.put("syncVersion", 0L);
            resp.put("updatedAt", 0L);
            resp.put("bundle", null);
            return resp;
        }
        UserSyncSnapshotRecord rec = existing.get();
        resp.put("hasSnapshot", true);
        resp.put("syncVersion", rec.getSyncVersion());
        resp.put("updatedAt", rec.getUpdatedAt());
        resp.put("bundle", objectMapper.readTree(rec.getPayloadJson()));
        return resp;
    }

    @Transactional
    public Map<String, Object> pushOrMerge(
            String userId,
            String deviceId,
            long baseVersion,
            JsonNode bundleNode,
            boolean isGuestMerge
    ) throws Exception {
        if (bundleNode == null || bundleNode.isNull()) {
            throw new IllegalArgumentException("同步数据包 bundle 不能为空");
        }
        // 安全校验：严禁客户端向云端提交教务系统密码字段
        String payload = objectMapper.writeValueAsString(bundleNode);
        if (payload.contains("\"eduPassword\"") || payload.contains("\"academicPassword\"")) {
            throw new IllegalArgumentException("隐私策略禁止将教务系统密码上传至云端");
        }

        long now = System.currentTimeMillis();
        UserRecord user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        Optional<UserSyncSnapshotRecord> existingOpt = snapshotRepository.findById(userId);

        long currentServerVersion = existingOpt.map(UserSyncSnapshotRecord::getSyncVersion).orElse(0L);
        if (!isGuestMerge && existingOpt.isPresent() && baseVersion < currentServerVersion) {
            // 乐观锁冲突：返回云端最新版本供客户端合并后再提交，绝不粗暴覆盖
            saveAudit(userId, deviceId, "PUSH", "CONFLICT", baseVersion, currentServerVersion, "版本落后，返回云端快照供合并");
            Map<String, Object> conflictResp = new LinkedHashMap<>();
            conflictResp.put("status", "CONFLICT");
            conflictResp.put("syncVersion", currentServerVersion);
            conflictResp.put("serverUpdatedAt", existingOpt.get().getUpdatedAt());
            conflictResp.put("serverBundle", objectMapper.readTree(existingOpt.get().getPayloadJson()));
            conflictResp.put("message", "云端存在更新版本，客户端需合并后重传");
            return conflictResp;
        }

        long nextVersion = currentServerVersion + 1;
        UserSyncSnapshotRecord snap = existingOpt.orElseGet(UserSyncSnapshotRecord::new);
        snap.setUserId(userId);
        snap.setSyncVersion(nextVersion);
        snap.setSchemaVersion(bundleNode.path("schemaVersion").asInt(1));
        snap.setPayloadJson(payload);
        snap.setLastDeviceId(deviceId);
        snap.setUpdatedAt(now);
        snapshotRepository.save(snap);

        user.setSyncVersion(nextVersion);
        user.setUpdatedAt(now);
        userRepository.save(user);

        String dir = isGuestMerge ? "MERGE" : "PUSH";
        saveAudit(userId, deviceId, dir, "SUCCESS", baseVersion, nextVersion, "同步完成，版本 v" + nextVersion);

        Map<String, Object> okResp = new LinkedHashMap<>();
        okResp.put("status", "SUCCESS");
        okResp.put("syncVersion", nextVersion);
        okResp.put("serverUpdatedAt", now);
        okResp.put("serverBundle", bundleNode);
        okResp.put("message", "云端同步成功");
        return okResp;
    }

    private void saveAudit(String userId, String deviceId, String dir, String status, long clientV, long serverV, String summary) {
        SyncAuditLogRecord log = new SyncAuditLogRecord();
        log.setId(UUID.randomUUID().toString());
        log.setUserId(userId);
        log.setDeviceId(deviceId);
        log.setDirection(dir);
        log.setStatus(status);
        log.setClientVersion(clientV);
        log.setServerVersion(serverV);
        log.setSummary(summary);
        log.setCreatedAt(System.currentTimeMillis());
        auditLogRepository.save(log);
    }
}
