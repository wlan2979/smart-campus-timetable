package com.campus.timetable.backend.service;

import com.campus.timetable.backend.entity.SchoolAdaptationRequestRecord;
import com.campus.timetable.backend.repo.SchoolAdaptationRequestRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全国高校目录下发与学校适配申请受理服务。
 * 目录文件与 Android 端内置 assets/schools_catalog_v2.json 完全一致（教育部大陆高校名单 2741 所 + 诚实标注的适配状态），
 * 通过 GET /api/v1/schools 与 GET /api/v1/schools/catalog 统一对外提供，消除两套口径分裂。
 */
@Service
public class SchoolCatalogService {

    private static final String CATALOG_RESOURCE = "schools_catalog_v2.json";
    private static final int MAX_PAGE_SUMMARY_LEN = 2000;
    private static final long ADAPT_IP_WINDOW_MILLIS = 60 * 60 * 1000L;
    private static final int MAX_ADAPT_PER_IP_PER_HOUR = 10;

    private final SchoolAdaptationRequestRepository adaptationRequestRepository;
    private final String catalogJson;
    private final List<JsonNode> catalogSchools;
    private final Map<String, Deque<Long>> adaptRateLimits = new ConcurrentHashMap<>();

    public SchoolCatalogService(
            SchoolAdaptationRequestRepository adaptationRequestRepository,
            ObjectMapper objectMapper
    ) throws IOException {
        this.adaptationRequestRepository = adaptationRequestRepository;
        ClassPathResource resource = new ClassPathResource(CATALOG_RESOURCE);
        try (InputStream in = resource.getInputStream()) {
            this.catalogJson = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        JsonNode root = objectMapper.readTree(this.catalogJson);
        List<JsonNode> list = new ArrayList<>();
        JsonNode arr = root.path("schools");
        if (arr.isArray()) {
            for (JsonNode n : arr) {
                list.add(n);
            }
        }
        Collator zhCollator = Collator.getInstance(Locale.CHINA);
        list.sort(Comparator.<JsonNode, String>comparing(
                        n -> {
                            String py = n.path("pinyin").asText("").trim().toLowerCase(Locale.ROOT);
                            return py.isEmpty() ? n.path("name").asText("") : py;
                        })
                .thenComparing(n -> n.path("name").asText(""), zhCollator));
        this.catalogSchools = List.copyOf(list);
    }

    public String getCatalogJson() {
        return catalogJson;
    }

    /** 统一检索 2741 所权威高校目录（支持全称/简称/别名/拼音/省份，最多返回 100 条）。 */
    public List<JsonNode> searchSchools(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode s : catalogSchools) {
            if (q.isEmpty() || matchesQuery(s, q)) {
                out.add(s);
                if (out.size() >= 100) break;
            }
        }
        return out;
    }

    private boolean matchesQuery(JsonNode s, String q) {
        return s.path("name").asText("").toLowerCase(Locale.ROOT).contains(q)
                || s.path("shortName").asText("").toLowerCase(Locale.ROOT).contains(q)
                || s.path("aliases").asText("").toLowerCase(Locale.ROOT).contains(q)
                || s.path("pinyin").asText("").toLowerCase(Locale.ROOT).contains(q)
                || s.path("pinyinInitials").asText("").toLowerCase(Locale.ROOT).contains(q)
                || s.path("province").asText("").toLowerCase(Locale.ROOT).contains(q)
                || s.path("city").asText("").toLowerCase(Locale.ROOT).contains(q);
    }

    @Transactional
    public Map<String, Object> submitAdaptationRequest(JsonNode body) {
        return submitAdaptationRequest(body, null);
    }

    /**
     * 受理用户主动提交的学校适配申请。
     * 安全规则：必须 userAuthorized=true；拒绝任何携带密码参数的请求；限制长度与匿名 IP 频率；服务端重新生成 id 与状态。
     */
    @Transactional
    public Map<String, Object> submitAdaptationRequest(JsonNode body, String clientIp) {
        checkAdaptRateLimit(clientIp);
        String schoolName = body.path("schoolName").asText("").trim();
        if (schoolName.isEmpty()) {
            throw new IllegalArgumentException("请填写学校全称");
        }
        if (!body.path("userAuthorized").asBoolean(false)) {
            throw new IllegalArgumentException("必须经用户明确授权后才能提交学校适配申请");
        }
        String portalUrl = body.path("portalUrl").asText("").trim();
        if (!portalUrl.isEmpty() && !portalUrl.startsWith("http://") && !portalUrl.startsWith("https://")) {
            throw new IllegalArgumentException("公开教务网址需以 http:// 或 https:// 开头");
        }
        String pageSummary = body.path("pageSummary").asText("").trim();
        if (pageSummary.length() > MAX_PAGE_SUMMARY_LEN) {
            throw new IllegalArgumentException("页面说明过长，最多支持 " + MAX_PAGE_SUMMARY_LEN + " 个字符");
        }
        String combined = (portalUrl + " " + pageSummary).toLowerCase(Locale.ROOT);
        if (combined.contains("password=") || combined.contains("passwd=") || combined.contains("pwd=")) {
            throw new IllegalArgumentException("安全策略禁止上传任何教务系统密码参数，请仅提交公开入口或页面结构说明");
        }

        SchoolAdaptationRequestRecord record = new SchoolAdaptationRequestRecord();
        record.setId("adapt_" + UUID.randomUUID().toString().replace("-", ""));
        record.setSchoolName(schoolName.length() > 200 ? schoolName.substring(0, 200) : schoolName);
        record.setEduSystemType(body.path("eduSystemType").asText("UNKNOWN"));
        record.setPortalUrl(portalUrl.length() > 512 ? portalUrl.substring(0, 512) : portalUrl);
        record.setPageSummary(pageSummary);
        record.setUserAuthorized(true);
        record.setStatus("SUBMITTED");
        record.setCreatedAt(System.currentTimeMillis());
        adaptationRequestRepository.save(record);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", record.getId());
        result.put("status", record.getStatus());
        result.put("message", "适配申请已受理，我们将在完成真实校园账号验证后更新该学校的适配状态");
        return result;
    }

    private void checkAdaptRateLimit(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) return;
        long now = System.currentTimeMillis();
        Deque<Long> q = adaptRateLimits.computeIfAbsent(clientIp.trim(), k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && (now - q.peekFirst()) > ADAPT_IP_WINDOW_MILLIS) {
                q.pollFirst();
            }
            if (q.size() >= MAX_ADAPT_PER_IP_PER_HOUR) {
                throw new RateLimitException("提交适配申请过于频繁，每小时最多提交 " + MAX_ADAPT_PER_IP_PER_HOUR + " 条，请稍后再试");
            }
            q.addLast(now);
        }
    }
}
