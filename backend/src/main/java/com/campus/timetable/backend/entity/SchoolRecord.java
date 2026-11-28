package com.campus.timetable.backend.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "schools")
public class SchoolRecord {
    @Id
    private String id;
    @Column(nullable = false, unique = true)
    private String code;
    @Column(nullable = false)
    private String name;
    @Column(nullable = false)
    private String province;
    @Column(nullable = false)
    private String city;
    @Column(name = "school_level", nullable = false)
    private String schoolLevel;
    @Column(name = "system_type", nullable = false)
    private String systemType;
    @Column(name = "adapter_id")
    private String adapterId;
    @Column(name = "portal_url")
    private String portalUrl;
    @Column(nullable = false)
    private boolean verified;
    @Column(name = "status_note", nullable = false)
    private String statusNote;
    @Column(name = "updated_at", nullable = false)
    private long updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getProvince() { return province; }
    public void setProvince(String province) { this.province = province; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public String getSchoolLevel() { return schoolLevel; }
    public void setSchoolLevel(String schoolLevel) { this.schoolLevel = schoolLevel; }
    public String getSystemType() { return systemType; }
    public void setSystemType(String systemType) { this.systemType = systemType; }
    public String getAdapterId() { return adapterId; }
    public void setAdapterId(String adapterId) { this.adapterId = adapterId; }
    public String getPortalUrl() { return portalUrl; }
    public void setPortalUrl(String portalUrl) { this.portalUrl = portalUrl; }
    public boolean isVerified() { return verified; }
    public void setVerified(boolean verified) { this.verified = verified; }
    public String getStatusNote() { return statusNote; }
    public void setStatusNote(String statusNote) { this.statusNote = statusNote; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}
