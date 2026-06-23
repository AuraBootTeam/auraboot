package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.service.IdempotencyService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-stack coverage IT for {@link IdempotencyServiceImpl} — checkIdempotency (blank id / unknown
 * key -> null; known key -> replayed outcome), recordOutcome (insert + blank-id no-op), and
 * cleanupExpired. Dedicated synthetic tenant; raw teardown.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("IdempotencyServiceImpl Coverage IT — check/record/cleanup")
class IdempotencyServiceImplCoverageIT {

    private static final long TENANT_ID = 991_800_001L;
    private final AtomicLong seq = new AtomicLong();

    @Autowired
    private IdempotencyService idempotencyService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(TENANT_ID, 991_800_002L, "idem-test-pid", "idem-test-user");
    }

    @AfterAll
    void cleanup() {
        try {
            jdbcTemplate.update("DELETE FROM ab_idempotency_record WHERE tenant_id = ?", TENANT_ID);
        } finally {
            MetaContext.clear();
        }
    }

    @Test
    @DisplayName("check returns null for blank/unknown; record then check replays the outcome")
    void checkRecordReplay() {
        assertNull(idempotencyService.checkIdempotency(null, TENANT_ID));
        assertNull(idempotencyService.checkIdempotency("", TENANT_ID));

        String reqId = "idem_" + seq.incrementAndGet();
        assertNull(idempotencyService.checkIdempotency(reqId, TENANT_ID));

        idempotencyService.recordOutcome(reqId, "demo:create",
                Map.of("name", "widget"), Map.of("status", "OK", "pid", "p1"), TENANT_ID);
        // blank-id record is a no-op
        idempotencyService.recordOutcome("", "demo:create", Map.of(), Map.of(), TENANT_ID);

        Map<String, Object> replayed = idempotencyService.checkIdempotency(reqId, TENANT_ID);
        assertNotNull(replayed);
        assertEquals("OK", String.valueOf(replayed.get("status")));
    }

    @Test
    @DisplayName("cleanupExpired runs and returns a non-negative count")
    void cleanupExpired() {
        assertTrue(idempotencyService.cleanupExpired() >= 0);
    }
}
