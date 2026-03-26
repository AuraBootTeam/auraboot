package com.auraboot.framework.integration.mobile;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.mobile.mapper.MobileClientLogMapper;
import com.auraboot.framework.mobile.service.MobileLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for MobileLogController.
 * Verifies structured log ingestion (DB-backed), legacy file upload, and diagnostic bundle upload.
 */
@Slf4j
@DisplayName("MobileLogController Integration Tests")
class MobileLogControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MobileClientLogMapper mobileClientLogMapper;

    @Autowired
    private MobileLogService mobileLogService;

    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        Filter metaContextFilter = (request, response, chain) -> {
            try {
                MetaContext.setContext(
                        getTestTenant().getId(),
                        getTestUser().getId(),
                        getTestUser().getPid(),
                        getTestUser().getUserName()
                );
                chain.doFilter(request, response);
            } finally {
                MetaContext.clear();
            }
        };
        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .addFilter(metaContextFilter, "/*")
                .build();
    }

    // ─────────── POST /api/mobile/logs (structured, DB-backed) ───────────

    @Test
    @DisplayName("ML-10: POST /api/mobile/logs accepts structured logs and persists to DB")
    void ingestLogs_persistsToDB() throws Exception {
        String sessionId = "sess_test_" + System.currentTimeMillis();
        Map<String, Object> request = Map.of(
                "sessionId", sessionId,
                "deviceModel", "iPhone 17 Pro",
                "appVersion", "1.2.0",
                "platform", "ios",
                "osVersion", "iOS 19.0",
                "logs", List.of(
                        Map.of("timestamp", "2026-03-25T10:00:00Z", "level", "error",
                                "category", "network", "message", "401 on /api/inbox",
                                "traceId", "trace_xyz",
                                "fields", Map.of("url", "/api/inbox", "statusCode", 401)),
                        Map.of("timestamp", "2026-03-25T10:00:01Z", "level", "info",
                                "category", "auth", "message", "Token refresh triggered")
                )
        );

        mockMvc.perform(post("/api/mobile/logs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.accepted").value(2));

        // Verify data persisted in DB
        int count = mobileClientLogMapper.countBySession(getTestTenant().getId(), sessionId);
        org.assertj.core.api.Assertions.assertThat(count).isEqualTo(2);

        log.info("ML-10: uploaded 2 structured log entries, verified in DB");
    }

    @Test
    @DisplayName("ML-11: POST /api/mobile/logs rejects empty logs array")
    void ingestLogs_emptyLogs_returnsValidationError() throws Exception {
        Map<String, Object> request = Map.of(
                "sessionId", "sess_empty_" + System.currentTimeMillis(),
                "platform", "android",
                "logs", List.of()
        );

        mockMvc.perform(post("/api/mobile/logs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        log.info("ML-11: empty logs array rejected with validation error");
    }

    @Test
    @DisplayName("ML-12: POST /api/mobile/logs rejects missing sessionId")
    void ingestLogs_missingSessionId_returnsValidationError() throws Exception {
        Map<String, Object> request = Map.of(
                "platform", "ios",
                "logs", List.of(
                        Map.of("timestamp", "2026-03-25T10:00:00Z", "level", "info",
                                "message", "Test message")
                )
        );

        mockMvc.perform(post("/api/mobile/logs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        log.info("ML-12: missing sessionId rejected");
    }

    @Test
    @DisplayName("ML-13: POST /api/mobile/logs rejects more than 100 entries")
    void ingestLogs_tooManyEntries_returnsValidationError() throws Exception {
        List<Map<String, Object>> logs = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            logs.add(Map.of("timestamp", "2026-03-25T10:00:00Z", "level", "info",
                    "message", "Log entry " + i));
        }

        Map<String, Object> request = new HashMap<>();
        request.put("sessionId", "sess_overflow_" + System.currentTimeMillis());
        request.put("platform", "ios");
        request.put("logs", logs);

        mockMvc.perform(post("/api/mobile/logs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        log.info("ML-13: 101 entries rejected by validation");
    }

    @Test
    @DisplayName("ML-14: POST /api/mobile/logs handles logs with null optional fields")
    void ingestLogs_nullOptionalFields_succeeds() throws Exception {
        String sessionId = "sess_sparse_" + System.currentTimeMillis();
        Map<String, Object> request = Map.of(
                "sessionId", sessionId,
                "logs", List.of(
                        Map.of("timestamp", "2026-03-25T10:00:00Z", "level", "info",
                                "message", "Minimal log entry")
                )
        );

        mockMvc.perform(post("/api/mobile/logs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.accepted").value(1));

        int count = mobileClientLogMapper.countBySession(getTestTenant().getId(), sessionId);
        org.assertj.core.api.Assertions.assertThat(count).isEqualTo(1);

        log.info("ML-14: sparse log entry (no deviceModel, platform, etc.) accepted");
    }

    @Test
    @DisplayName("ML-15: POST /api/mobile/logs rate limit enforced after 10 rapid requests")
    void ingestLogs_rateLimitEnforced() throws Exception {
        // Clear rate limit state from other tests in this class
        mobileLogService.clearRateLimits();

        // Send 10 valid requests to exhaust the rate limit
        for (int i = 0; i < 10; i++) {
            Map<String, Object> request = Map.of(
                    "sessionId", "sess_ratelimit_" + System.currentTimeMillis() + "_" + i,
                    "platform", "ios",
                    "logs", List.of(
                            Map.of("timestamp", "2026-03-25T10:00:00Z", "level", "info",
                                    "message", "Rate limit test " + i)
                    )
            );

            mockMvc.perform(post("/api/mobile/logs")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("0"));
        }

        // 11th request should be rate limited
        Map<String, Object> request = Map.of(
                "sessionId", "sess_ratelimit_overflow_" + System.currentTimeMillis(),
                "platform", "ios",
                "logs", List.of(
                        Map.of("timestamp", "2026-03-25T10:00:00Z", "level", "info",
                                "message", "Should be rate limited")
                )
        );

        mockMvc.perform(post("/api/mobile/logs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", not("0")));

        log.info("ML-15: rate limit enforced after 10 requests");
    }

    // ─────────── Legacy endpoints (kept for backward compatibility) ───────────

    @Test
    @DisplayName("ML-01: POST /api/mobile/logs/upload accepts log entries and returns accepted count")
    void uploadLogs_acceptsEntries() throws Exception {
        List<Map<String, Object>> entries = List.of(
                Map.of("level", "info", "message", "App launched", "timestamp", System.currentTimeMillis()),
                Map.of("level", "warn", "message", "Slow network detected", "timestamp", System.currentTimeMillis()),
                Map.of("level", "error", "message", "API timeout", "timestamp", System.currentTimeMillis())
        );

        String body = objectMapper.writeValueAsString(entries);

        mockMvc.perform(post("/api/mobile/logs/upload")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.accepted").value(3));

        log.info("ML-01: uploaded 3 log entries successfully");
    }

    @Test
    @DisplayName("ML-02: POST /api/mobile/logs/upload with empty array returns accepted=0")
    void uploadLogs_emptyArray_returnsZero() throws Exception {
        mockMvc.perform(post("/api/mobile/logs/upload")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.accepted").value(0));

        log.info("ML-02: empty array returned accepted=0");
    }

    @Test
    @DisplayName("ML-03: POST /api/mobile/logs/diagnostic accepts a zip file and returns filename")
    void uploadDiagnostic_acceptsZipFile() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            ZipEntry entry = new ZipEntry("app_info.json");
            zos.putNextEntry(entry);
            zos.write("{\"version\":\"1.0.0\",\"build\":42}".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            ZipEntry logEntry = new ZipEntry("recent_logs.ndjson");
            zos.putNextEntry(logEntry);
            zos.write("{\"level\":\"INFO\",\"msg\":\"test\"}\n".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        MockMultipartFile zipFile = new MockMultipartFile(
                "file",
                "diagnostic_test.zip",
                "application/zip",
                baos.toByteArray()
        );

        mockMvc.perform(multipart("/api/mobile/logs/diagnostic").file(zipFile))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.filename", startsWith("diag_")))
                .andExpect(jsonPath("$.data.status").value("stored"));

        log.info("ML-03: diagnostic zip uploaded successfully");
    }

    @Test
    @DisplayName("ML-04: POST /api/mobile/logs/diagnostic rejects empty file")
    void uploadDiagnostic_emptyFile_returnsError() throws Exception {
        MockMultipartFile emptyFile = new MockMultipartFile(
                "file",
                "empty.zip",
                "application/zip",
                new byte[0]
        );

        mockMvc.perform(multipart("/api/mobile/logs/diagnostic").file(emptyFile))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", not("0")));

        log.info("ML-04: empty diagnostic file rejected as expected");
    }
}
