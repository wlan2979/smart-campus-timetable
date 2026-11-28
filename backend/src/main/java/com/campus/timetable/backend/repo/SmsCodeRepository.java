package com.campus.timetable.backend.repo;

import com.campus.timetable.backend.entity.SmsVerificationCodeRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface SmsCodeRepository extends JpaRepository<SmsVerificationCodeRecord, String> {
    Optional<SmsVerificationCodeRecord> findTopByPhoneOrderByCreatedAtDesc(String phone);
    void deleteByPhone(String phone);
}
