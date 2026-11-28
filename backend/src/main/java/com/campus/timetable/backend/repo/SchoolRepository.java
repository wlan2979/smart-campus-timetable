package com.campus.timetable.backend.repo;

import com.campus.timetable.backend.entity.SchoolRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SchoolRepository extends JpaRepository<SchoolRecord, String> {
    List<SchoolRecord> findByNameContainingIgnoreCaseOrderByVerifiedDescNameAsc(String name);
    List<SchoolRecord> findAllByOrderByVerifiedDescNameAsc();
}
