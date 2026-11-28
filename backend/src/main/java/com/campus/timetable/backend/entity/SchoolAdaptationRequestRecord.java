package com.campus.timetable.backend.entity;

import jakarta.persistence.*;

/**
 * 用户提交的高校教务适配申请。严禁存储任何教务系统账号或密码。
 */
@Entity
@Table(name = "school_adaptation_requests")
public class SchoolAdaptationRequestRecord {

    @Id
    private String id;

    @Column(name = "school_name", nullable = false)
    private String schoolName;

    @Column(name = "edu_system_type", nullable = false)
    private String eduSystemType;

    @Column(name = "portal_url")
    private String portalUrl;

    @Column(name = "page_summary")
    private String pageSummary;

    @Column(name = "user_authorized", nullable = false)
    private boolean userAuthorized;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private long createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSchoolName() { return schoolName; }
    public void setSchoolName(String schoolName) { this.schoolName = schoolName; }
    public String getEduSystemType() { return eduSystemType; }
    public void setEduSystemType(String eduSystemType) { this.eduSystemType = eduSystemType; }
    public String getPortalUrl() { return portalUrl; }
    public void setPortalUrl(String portalUrl) { this.portalUrl = portalUrl; }
    public String getPageSummary() { return pageSummary; }
    public void setPageSummary(String pageSummary) { this.pageSummary = pageSummary; }
    public boolean isUserAuthorized() { return userAuthorized; }
    public void setUserAuthorized(boolean userAuthorized) { this.userAuthorized = userAuthorized; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}
