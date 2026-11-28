package com.campus.timetable.backend.repo;

import com.campus.timetable.backend.entity.UserRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserRepository extends JpaRepository<UserRecord, String> {
    Optional<UserRecord> findByPhone(String phone);
}
