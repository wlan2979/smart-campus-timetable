package com.campus.timetable.backend.repo;

import com.campus.timetable.backend.entity.UserSyncSnapshotRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncSnapshotRepository extends JpaRepository<UserSyncSnapshotRecord, String> {
}
