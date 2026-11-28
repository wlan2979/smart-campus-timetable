package com.campus.timetable.backend.service;

import com.campus.timetable.backend.entity.HolidayRecord;
import com.campus.timetable.backend.entity.SchoolRecord;
import com.campus.timetable.backend.repo.HolidayRepository;
import com.campus.timetable.backend.repo.SchoolRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@Service
public class HolidayAndWeatherService {

    private final HolidayRepository holidayRepository;
    private final SchoolRepository schoolRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String holidayRemoteUrlTemplate;
    private final String qweatherHost;
    private final String qweatherKey;

    public HolidayAndWeatherService(
            HolidayRepository holidayRepository,
            SchoolRepository schoolRepository,
            ObjectMapper objectMapper,
            @Value("${app.holiday.remote-url-template:https://raw.githubusercontent.com/NateScarlet/holiday-cn/master/{year}.json}") String holidayRemoteUrlTemplate,
            @Value("${app.weather.qweather-api-host:}") String qweatherHost,
            @Value("${app.weather.qweather-api-key:}") String qweatherKey
    ) {
        this.holidayRepository = holidayRepository;
        this.schoolRepository = schoolRepository;
        this.objectMapper = objectMapper;
        this.holidayRemoteUrlTemplate = holidayRemoteUrlTemplate;
        this.qweatherHost = qweatherHost;
        this.qweatherKey = qweatherKey;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Transactional
    public Map<String, Object> getHolidaysForYear(int year, boolean forceRefresh) {
        List<HolidayRecord> cached = holidayRepository.findByHolidayYearOrderByHolidayDateAsc(year);
        if (!cached.isEmpty() && !forceRefresh) {
            return buildHolidayResponse(year, true, cached.get(0).getSource(), cached.get(0).getVersion(),
                    cached.get(0).getUpdatedAt(), cached, "已返回服务端缓存的国务院节假日安排");
        }

        List<String> urls = candidateHolidayUrls(year);
        Map<String, Object> lastFailure = null;
        for (String url : urls) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(12))
                        .header("User-Agent", "TaokeTimetableBackend/2.2.4")
                        .GET()
                        .build();
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 404) {
                    return buildHolidayResponse(year, false, url, "unpublished", null, Collections.emptyList(),
                            year + "年国务院办公厅法定节假日安排尚未公布，暂不虚构数据。");
                }
                if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                    lastFailure = buildHolidayResponse(year, !cached.isEmpty(), url, "http_" + resp.statusCode(),
                            cached.isEmpty() ? null : cached.get(0).getUpdatedAt(), cached,
                            "拉取外部节假日源失败（HTTP " + resp.statusCode() + "）");
                    continue;
                }

                JsonNode root = objectMapper.readTree(resp.body());
                String source = root.path("papers").isArray() && !root.path("papers").isEmpty()
                        ? "国务院办公厅通知 (" + root.path("papers").get(0).asText() + ")"
                        : "国务院公开节假日数据源 (" + url + ")";
                JsonNode days = root.path("days");
                long now = System.currentTimeMillis();
                String version = year + "-cnt" + days.size();

                List<HolidayRecord> records = new ArrayList<>();
                if (days.isArray()) {
                    for (JsonNode d : days) {
                        HolidayRecord rec = new HolidayRecord();
                        String dateStr = d.path("date").asText();
                        boolean off = d.path("isOffDay").asBoolean(true);
                        rec.setId("holiday_" + dateStr);
                        rec.setHolidayYear(year);
                        rec.setHolidayDate(dateStr);
                        rec.setName(d.path("name").asText("法定节假日"));
                        rec.setHolidayType(off ? "LEGAL_HOLIDAY" : "STATUTORY_WORKDAY");
                        rec.setOffDay(off);
                        rec.setSource(source);
                        rec.setVersion(version);
                        rec.setUpdatedAt(now);
                        records.add(rec);
                    }
                }
                holidayRepository.deleteByHolidayYear(year);
                holidayRepository.saveAll(records);
                return buildHolidayResponse(year, !records.isEmpty(), source, version, now, records,
                        "已同步 " + year + "年国务院办公厅节假日安排");
            } catch (Exception e) {
                lastFailure = buildHolidayResponse(year, !cached.isEmpty(), url, "network_error",
                        cached.isEmpty() ? null : cached.get(0).getUpdatedAt(), cached,
                        "获取远程节假日失败：" + e.getMessage());
            }
        }
        return lastFailure != null ? lastFailure : buildHolidayResponse(year, !cached.isEmpty(),
                holidayRemoteUrlTemplate, "network_error", null, cached, "无法连接节假日数据源");
    }

    private List<String> candidateHolidayUrls(int year) {
        String primary = holidayRemoteUrlTemplate.replace("{year}", String.valueOf(year));
        if (!holidayRemoteUrlTemplate.contains("raw.githubusercontent.com/NateScarlet/holiday-cn")) {
            return List.of(primary);
        }
        List<String> list = new ArrayList<>();
        list.add(primary);
        list.add("https://cdn.jsdelivr.net/gh/NateScarlet/holiday-cn@master/" + year + ".json");
        list.add("https://fastly.jsdelivr.net/gh/NateScarlet/holiday-cn@master/" + year + ".json");
        return list;
    }

    public List<SchoolRecord> listSchools(String query) {
        if (query == null || query.trim().isEmpty()) {
            return schoolRepository.findAllByOrderByVerifiedDescNameAsc();
        }
        return schoolRepository.findByNameContainingIgnoreCaseOrderByVerifiedDescNameAsc(query.trim());
    }

    public String proxyQWeather(double lat, double lon) throws Exception {
        if (qweatherHost == null || qweatherHost.isBlank() || qweatherKey == null || qweatherKey.isBlank()) {
            throw new IllegalStateException("服务端尚未配置和风天气凭证（QWEATHER_API_HOST / QWEATHER_API_KEY）");
        }
        String loc = String.format(Locale.US, "%.2f,%.2f", lon, lat);
        String url = qweatherHost.replaceAll("/+$", "") + "/v7/weather/7d?location=" + loc;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("X-QW-Api-Key", qweatherKey)
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException("上游和风天气返回 HTTP " + resp.statusCode());
        }
        return resp.body();
    }

    private Map<String, Object> buildHolidayResponse(
            int year, boolean published, String source, String version, Long updatedAt,
            List<HolidayRecord> holidays, String notice
    ) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("year", year);
        map.put("published", published);
        map.put("source", source);
        map.put("version", version);
        map.put("updatedAt", updatedAt);
        map.put("notice", notice);
        map.put("holidays", holidays);
        return map;
    }
}
