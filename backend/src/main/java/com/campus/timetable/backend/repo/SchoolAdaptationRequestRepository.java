package com.campus.timetable.backend.repo;

import com.campus.timetable.backend.entity.SchoolAdaptationRequestRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SchoolAdaptationRequestRepository extends JpaRepository<SchoolAdaptationRequestRecord, String> {
}
