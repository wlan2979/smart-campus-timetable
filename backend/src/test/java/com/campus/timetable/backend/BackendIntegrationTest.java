package com.campus.timetable.backend;

import com.campus.timetable.backend.entity.SmsVerificationCodeRecord;
import com.campus.timetable.backend.repo.SmsCodeRepository;
import com.campus.timetable.backend.repo.SyncSnapshotRepository;
import com.campus.timetable.backend.repo.UserRepository;
import com.campus.timetable.backend.security.JwtService;
import com.campus.timetable.backend.service.SmsDispatchService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BackendIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private SmsCodeRepository smsCodeRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private SyncSnapshotRepository syncSnapshotRepository;

    @Test
    void fullAuthGuestMergeSyncConflictAndAccountDeletionLifecycle() throws Exception {
        String phone = "13800138000";

        // 1. 发送验证码（生成随机码并哈希存库）
        mockMvc.perform(post("/api/v1/auth/sms/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // 测试环境下用已知测试码覆写哈希以完成端到端验证码登录校验
        SmsVerificationCodeRecord rec = smsCodeRepository.findTopByPhoneOrderByCreatedAtDesc(phone).orElseThrow();
        String testCode = "619482";
        rec.setCodeHash(sha256(phone + ":" + testCode));
        smsCodeRepository.save(rec);

        MvcResult loginRes = mockMvc.perform(post("/api/v1/auth/sms/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\",\"code\":\"" + testCode + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn();

        JsonNode authJson = objectMapper.readTree(loginRes.getResponse().getContentAsString());
        String accessToken = authJson.get("accessToken").asText();
        String userId = authJson.get("userId").asText();

        // 2. 游客数据迁移合并至正式账号
        String guestBundle = """
                {
                  "deviceId": "android-device-A",
                  "baseVersion": 0,
                  "bundle": {
                    "schemaVersion": 1,
                    "semesters": [{"id": "sem-1", "name": "2026秋"}],
                    "courses": [{"id": "c-1", "name": "高等数学"}]
                  }
                }
                """;
        mockMvc.perform(post("/api/v1/sync/merge-guest")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(guestBundle))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.syncVersion").value(1));

        // 3. 换机/多设备拉取恢复数据
        mockMvc.perform(get("/api/v1/sync/pull")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasSnapshot").value(true))
                .andExpect(jsonPath("$.syncVersion").value(1))
                .andExpect(jsonPath("$.bundle.courses[0].name").value("高等数学"));

        // 4. 过期版本提交触发乐观锁冲突保护（返回云端快照不粗暴覆盖）
        String stalePush = """
                {
                  "deviceId": "android-device-B",
                  "baseVersion": 0,
                  "bundle": {"schemaVersion": 1, "courses": []}
                }
                """;
        mockMvc.perform(post("/api/v1/sync/push")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(stalePush))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFLICT"))
                .andExpect(jsonPath("$.syncVersion").value(1));

        // 5. 隐私保护：禁止上传教务系统密码字段
        String forbiddenPush = """
                {
                  "deviceId": "android-device-A",
                  "baseVersion": 1,
                  "bundle": {"schemaVersion": 1, "eduPassword": "secret"}
                }
                """;
        mockMvc.perform(post("/api/v1/sync/push")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forbiddenPush))
                .andExpect(status().isBadRequest());

        // 6. 注销账号并彻底删除云端个人数据
        mockMvc.perform(delete("/api/v1/account")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));

        assertTrue(userRepository.findById(userId).isEmpty());
        assertTrue(syncSnapshotRepository.findById(userId).isEmpty());
    }

    @Test
    void schoolCatalogNeverMarksUnverifiedSchoolsAsVerified() throws Exception {
        MvcResult allRes = mockMvc.perform(get("/api/v1/schools"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode allArr = objectMapper.readTree(allRes.getResponse().getContentAsString());
        assertTrue(allArr.isArray() && allArr.size() >= 10);
        for (int i = 1; i < allArr.size(); i++) {
            String prev = allArr.get(i - 1).path("pinyin").asText("").toLowerCase(java.util.Locale.ROOT);
            String cur = allArr.get(i).path("pinyin").asText("").toLowerCase(java.util.Locale.ROOT);
            assertTrue(prev.compareTo(cur) <= 0, "后端高校检索应按拼音 A-Z 排序: " + prev + " <= " + cur);
        }

        MvcResult res = mockMvc.perform(get("/api/v1/schools").param("q", "qhdx"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode arr = objectMapper.readTree(res.getResponse().getContentAsString());
        assertTrue(arr.isArray() && !arr.isEmpty());
        assertTrue(arr.toString().contains("清华大学"));
        for (JsonNode item : arr) {
            assertFalse(item.get("verified").asBoolean());
        }
    }

    @Test
    void remoteSchoolCatalogServesAllSchoolsAndAdaptationRequestsEnforcePrivacy() throws Exception {
        // 1. 远程高校目录：完整下发 2741 所教育部大陆高校，且不谎称任何学校已验证
        MvcResult catalogRes = mockMvc.perform(get("/api/v1/schools/catalog"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode catalog = objectMapper.readTree(catalogRes.getResponse().getContentAsString());
        assertEquals(2741, catalog.path("totalCount").asInt());
        assertEquals(2741, catalog.path("schools").size());
        for (JsonNode school : catalog.path("schools")) {
            assertFalse(school.path("verified").asBoolean(),
                    "未经实校验证不得标记 verified=true：" + school.path("name").asText());
        }

        // 2. 用户明确授权后可提交适配申请
        mockMvc.perform(post("/api/v1/schools/adapt-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "schoolName": "测试职业技术学院",
                                  "eduSystemType": "ZHENGFANG",
                                  "portalUrl": "https://jw.example.edu.cn",
                                  "pageSummary": "正方教务 2024 版课表页",
                                  "userAuthorized": true
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));

        // 3. 未授权必须拒绝
        mockMvc.perform(post("/api/v1/schools/adapt-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"schoolName": "测试大学", "userAuthorized": false}
                                """))
                .andExpect(status().isBadRequest());

        // 4. 任何携带密码参数的申请必须拒绝
        mockMvc.perform(post("/api/v1/schools/adapt-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "schoolName": "测试大学",
                                  "portalUrl": "https://jw.example.edu.cn?password=oops",
                                  "userAuthorized": true
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void productionProfileStrictlyBlocksDebugSmsProvider() {
        MockEnvironment prodEnv = new MockEnvironment().withProperty("spring.profiles.active", "prod");
        prodEnv.setActiveProfiles("prod");
        assertThrows(IllegalStateException.class, () ->
                new SmsDispatchService(prodEnv, "debug_log", "", "", "", "")
        );
    }

    @Test
    void productionProfileStrictlyBlocksDefaultWeakJwtSecret() {
        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");
        assertThrows(IllegalStateException.class, () ->
                new JwtService(prodEnv, "dev-only-insecure-secret-change-in-production-at-least-32-chars", 7200, 2592000)
        );
        // 高熵 32+ 字节密钥可正常初始化
        assertDoesNotThrow(() ->
                new JwtService(prodEnv, "9f8c7a6e5b4d3c2a1f0e9d8c7b6a5f4e3d2c1b0a", 7200, 2592000)
        );
    }

    @Test
    void smsVerifyLocksOutCodeAfterFiveWrongAttemptsAndRejectsOversizedAdaptRequest() throws Exception {
        String phone = "13900139001";
        mockMvc.perform(post("/api/v1/auth/sms/send")
                        .header("X-Forwarded-For", "198.51.100.10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\"}"))
                .andExpect(status().isOk());

        SmsVerificationCodeRecord rec = smsCodeRepository.findTopByPhoneOrderByCreatedAtDesc(phone).orElseThrow();
        String realCode = "246810";
        rec.setCodeHash(sha256(phone + ":" + realCode));
        smsCodeRepository.save(rec);

        // 连续输错 5 次，第 5 次后验证码自动作废并持久化
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/auth/sms/verify")
                            .header("X-Forwarded-For", "198.51.100.10")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phone\":\"" + phone + "\",\"code\":\"00000" + i + "\"}"))
                    .andExpect(status().isBadRequest());
        }
        // 第 6 次即便输入正确验证码也必须被拒绝
        mockMvc.perform(post("/api/v1/auth/sms/verify")
                        .header("X-Forwarded-For", "198.51.100.10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\",\"code\":\"" + realCode + "\"}"))
                .andExpect(status().isBadRequest());

        // 适配申请拒绝超长说明与非 http/https 协议
        String tooLongSummary = "a".repeat(2100);
        mockMvc.perform(post("/api/v1/schools/adapt-requests")
                        .header("X-Forwarded-For", "198.51.100.20")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolName\":\"测试高校\",\"portalUrl\":\"https://jw.test.edu.cn\",\"pageSummary\":\"" + tooLongSummary + "\",\"userAuthorized\":true}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/schools/adapt-requests")
                        .header("X-Forwarded-For", "198.51.100.20")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolName\":\"测试高校\",\"portalUrl\":\"javascript:alert(1)\",\"userAuthorized\":true}"))
                .andExpect(status().isBadRequest());
    }

    private String sha256(String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
    }
}
