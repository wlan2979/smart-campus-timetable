package com.campus.timetable.backend.controller;

import com.campus.timetable.backend.service.AuthService;
import com.campus.timetable.backend.service.HolidayAndWeatherService;
import com.campus.timetable.backend.service.RateLimitException;
import com.campus.timetable.backend.service.SchoolCatalogService;
import com.campus.timetable.backend.service.SyncService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class ApiController {

    private final AuthService authService;
    private final SyncService syncService;
    private final HolidayAndWeatherService holidayAndWeatherService;
    private final SchoolCatalogService schoolCatalogService;

    public ApiController(
            AuthService authService,
            SyncService syncService,
            HolidayAndWeatherService holidayAndWeatherService,
            SchoolCatalogService schoolCatalogService
    ) {
        this.authService = authService;
        this.syncService = syncService;
        this.holidayAndWeatherService = holidayAndWeatherService;
        this.schoolCatalogService = schoolCatalogService;
    }

    public record SmsSendReq(String phone) {}
    public record SmsVerifyReq(String phone, String code) {}
    public record RefreshReq(String refreshToken) {}
    public record SyncPushReq(String deviceId, long baseVersion, JsonNode bundle) {}

    @PostMapping("/auth/sms/send")
    public Map<String, Object> sendSms(@RequestBody SmsSendReq req, HttpServletRequest httpReq) {
        return authService.sendSmsCode(req.phone(), resolveClientIp(httpReq));
    }

    @PostMapping("/auth/sms/verify")
    public Map<String, Object> verifySms(@RequestBody SmsVerifyReq req, HttpServletRequest httpReq) {
        return authService.verifySmsCode(req.phone(), req.code(), resolveClientIp(httpReq));
    }

    @PostMapping("/auth/refresh")
    public Map<String, Object> refresh(@RequestBody RefreshReq req) {
        return authService.refreshToken(req.refreshToken());
    }

    @DeleteMapping("/account")
    public Map<String, Object> deleteAccount(Authentication auth) {
        authService.deleteAccountAndCloudData(auth.getName());
        return Map.of("deleted", true, "message", "账号及所有云端个人课表数据已彻底删除");
    }

    @GetMapping("/sync/pull")
    public Map<String, Object> pullSync(Authentication auth) throws Exception {
        return syncService.pull(auth.getName());
    }

    @PostMapping("/sync/push")
    public Map<String, Object> pushSync(Authentication auth, @RequestBody SyncPushReq req) throws Exception {
        return syncService.pushOrMerge(auth.getName(), req.deviceId(), req.baseVersion(), req.bundle(), false);
    }

    @PostMapping("/sync/merge-guest")
    public Map<String, Object> mergeGuest(Authentication auth, @RequestBody SyncPushReq req) throws Exception {
        return syncService.pushOrMerge(auth.getName(), req.deviceId(), req.baseVersion(), req.bundle(), true);
    }

    @GetMapping("/holidays/{year}")
    public Map<String, Object> getHolidays(
            @PathVariable int year,
            @RequestParam(defaultValue = "false") boolean refresh
    ) {
        return holidayAndWeatherService.getHolidaysForYear(year, refresh);
    }

    @GetMapping("/schools")
    public List<JsonNode> listSchools(@RequestParam(required = false) String q) {
        return schoolCatalogService.searchSchools(q);
    }

    /** 全国高校目录远程下发：客户端无需升级即可热更新学校目录（教育部大陆高校名单 2741 所） */
    @GetMapping(value = "/schools/catalog", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> schoolCatalog() {
        return ResponseEntity.ok()
                .contentType(new MediaType("application", "json", StandardCharsets.UTF_8))
                .body(schoolCatalogService.getCatalogJson());
    }

    /** 用户授权后提交高校适配申请（绝不接收教务密码，限制匿名 IP 频率与文本长度） */
    @PostMapping("/schools/adapt-requests")
    public ResponseEntity<Map<String, Object>> submitAdaptationRequest(
            @RequestBody JsonNode body,
            HttpServletRequest httpReq
    ) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(schoolCatalogService.submitAdaptationRequest(body, resolveClientIp(httpReq)));
    }

    @GetMapping(value = "/weather/proxy", produces = MediaType.APPLICATION_JSON_VALUE)
    public String weatherProxy(@RequestParam double lat, @RequestParam double lon) throws Exception {
        return holidayAndWeatherService.proxyQWeather(lat, lon);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("code", "BAD_REQUEST", "message", ex.getMessage()));
    }

    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimit(RateLimitException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of("code", "TOO_MANY_REQUESTS", "message", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleConfigOrState(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("code", "SERVICE_UNAVAILABLE", "message", ex.getMessage()));
    }

    private String resolveClientIp(HttpServletRequest req) {
        if (req == null) return "";
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma >= 0 ? xff.substring(0, comma) : xff).trim();
        }
        return req.getRemoteAddr() == null ? "" : req.getRemoteAddr();
    }
}
