package com.campus.timetable.backend.repo;

import com.campus.timetable.backend.entity.SyncAuditLogRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncAuditLogRepository extends JpaRepository<SyncAuditLogRecord, String> {
    void deleteByUserId(String userId);
}
