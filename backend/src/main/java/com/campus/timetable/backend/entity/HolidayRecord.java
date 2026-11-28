package com.campus.timetable.backend.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "holidays")
public class HolidayRecord {
    @Id
    private String id;
    @Column(name = "holiday_year", nullable = false)
    private int holidayYear;
    @Column(name = "holiday_date", nullable = false, unique = true)
    private String holidayDate;
    @Column(nullable = false)
    private String name;
    @Column(name = "holiday_type", nullable = false)
    private String holidayType;
    @Column(name = "is_off_day", nullable = false)
    private boolean offDay;
    @Column(nullable = false)
    private String source;
    @Column(nullable = false)
    private String version;
    @Column(name = "updated_at", nullable = false)
    private long updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public int getHolidayYear() { return holidayYear; }
    public void setHolidayYear(int holidayYear) { this.holidayYear = holidayYear; }
    public String getHolidayDate() { return holidayDate; }
    public void setHolidayDate(String holidayDate) { this.holidayDate = holidayDate; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getHolidayType() { return holidayType; }
    public void setHolidayType(String holidayType) { this.holidayType = holidayType; }
    public boolean isOffDay() { return offDay; }
    public void setOffDay(boolean offDay) { this.offDay = offDay; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}
