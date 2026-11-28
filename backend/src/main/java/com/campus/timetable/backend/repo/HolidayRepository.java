package com.campus.timetable.backend.repo;

import com.campus.timetable.backend.entity.HolidayRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface HolidayRepository extends JpaRepository<HolidayRecord, String> {
    List<HolidayRecord> findByHolidayYearOrderByHolidayDateAsc(int holidayYear);
    void deleteByHolidayYear(int holidayYear);
}
