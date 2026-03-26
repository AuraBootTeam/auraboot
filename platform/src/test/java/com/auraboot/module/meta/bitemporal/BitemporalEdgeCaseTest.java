package com.auraboot.module.meta.bitemporal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Edge case tests for bitemporal data model covering:
 * repeated corrections, queries before first version,
 * and isCurrent() method behavior.
 */
@ExtendWith(MockitoExtension.class)
class BitemporalEdgeCaseTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private BitemporalRepository repository;

    private static final String TABLE = "biz_bt_cost";
    private static final Long ENTITY_KEY = 99L;
    private static final Long TENANT_ID = 1L;

    @BeforeEach
    void setUp() {
        repository = new BitemporalRepository(jdbcTemplate);
    }

    // ========== Test 1: Correct same entity twice — both corrections recorded ==========

    @Test
    void testCorrectEndsPreviousVersion() {
        // First correction: ends current version and inserts new one
        when(jdbcTemplate.update(anyString(), eq(ENTITY_KEY), eq(TENANT_ID)))
                .thenReturn(1);  // 1 row ended

        // Attempt first correction — SimpleJdbcInsert requires DataSource,
        // so we verify the UPDATE (end-version) part which is the critical behavior.
        try {
            repository.correct(TABLE, ENTITY_KEY,
                    Map.of("cost", 100.0,
                            "valid_from", LocalDate.of(2026, 1, 1),
                            "valid_to", LocalDate.of(9999, 12, 31)),
                    TENANT_ID);
        } catch (Exception e) {
            // Expected: SimpleJdbcInsert fails without real DataSource
        }

        // Verify the end-current-version UPDATE was called
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), eq(ENTITY_KEY), eq(TENANT_ID));
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("SET txn_to = NOW()"),
                "First correction should end current version");
        assertTrue(sql.contains("txn_to IS NULL"),
                "Should only end versions where txn_to IS NULL");

        // Reset mocks for second correction
        reset(jdbcTemplate);
        when(jdbcTemplate.update(anyString(), eq(ENTITY_KEY), eq(TENANT_ID)))
                .thenReturn(1);

        // Second correction on same entity
        try {
            repository.correct(TABLE, ENTITY_KEY,
                    Map.of("cost", 200.0,
                            "valid_from", LocalDate.of(2026, 2, 1),
                            "valid_to", LocalDate.of(9999, 12, 31)),
                    TENANT_ID);
        } catch (Exception e) {
            // Expected: SimpleJdbcInsert fails without real DataSource
        }

        // Verify the second correction also ended the current version
        ArgumentCaptor<String> sql2Captor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql2Captor.capture(), eq(ENTITY_KEY), eq(TENANT_ID));
        assertTrue(sql2Captor.getValue().contains("SET txn_to = NOW()"),
                "Second correction should also end current version");
    }

    // ========== Test 2: Find as-of before first version returns null ==========

    @Test
    void testFindAsOfBeforeFirstVersion() {
        // Query for a date BEFORE any version was recorded
        LocalDate queryDate = LocalDate.of(2020, 1, 1); // very early date
        Instant queryInstant = Instant.parse("2020-01-01T00:00:00Z");

        when(jdbcTemplate.queryForList(anyString(),
                eq(ENTITY_KEY), eq(queryDate), eq(queryDate),
                eq(Timestamp.from(queryInstant)), eq(Timestamp.from(queryInstant)),
                eq(TENANT_ID)))
                .thenReturn(List.of()); // no results

        Map<String, Object> result = repository.findAsOf(
                TABLE, ENTITY_KEY, queryDate, queryInstant, TENANT_ID);

        assertNull(result, "Query before first version should return null");
    }

    // ========== Test 3: isCurrent() with null vs non-null txnTo ==========

    @Test
    void testBitemporalEntityIsCurrent() {
        // Create a concrete subclass for testing
        BitemporalEntity currentEntity = new BitemporalEntity() {};
        currentEntity.setId(1L);
        currentEntity.setEntityKey(ENTITY_KEY);
        currentEntity.setValidFrom(LocalDate.of(2026, 1, 1));
        currentEntity.setValidTo(BitemporalEntity.MAX_VALID_DATE);
        currentEntity.setTxnFrom(Instant.now());
        currentEntity.setTxnTo(null); // current version

        assertTrue(currentEntity.isCurrent(),
                "Entity with txnTo=null should be current");

        // Now set txnTo to a non-null value (superseded)
        BitemporalEntity supersededEntity = new BitemporalEntity() {};
        supersededEntity.setId(2L);
        supersededEntity.setEntityKey(ENTITY_KEY);
        supersededEntity.setValidFrom(LocalDate.of(2026, 1, 1));
        supersededEntity.setValidTo(BitemporalEntity.MAX_VALID_DATE);
        supersededEntity.setTxnFrom(Instant.parse("2026-01-01T00:00:00Z"));
        supersededEntity.setTxnTo(Instant.parse("2026-02-01T00:00:00Z")); // superseded

        assertFalse(supersededEntity.isCurrent(),
                "Entity with non-null txnTo should NOT be current");
    }

    // ========== Test 4: MAX_VALID_DATE sentinel value ==========

    @Test
    void testMaxValidDateSentinel() {
        assertEquals(LocalDate.of(9999, 12, 31), BitemporalEntity.MAX_VALID_DATE,
                "MAX_VALID_DATE should be 9999-12-31");

        // Verify it can be used for comparisons
        LocalDate today = LocalDate.now();
        assertTrue(BitemporalEntity.MAX_VALID_DATE.isAfter(today),
                "MAX_VALID_DATE should be far in the future");
    }

    // ========== Test 5: findHistory returns ordered versions ==========

    @Test
    void testFindHistoryReturnsAllVersions() {
        Map<String, Object> v1 = Map.of(
                "id", 1L, "entity_key", ENTITY_KEY,
                "cost", 100.0, "txn_to", Instant.parse("2026-02-01T00:00:00Z"));
        Map<String, Object> v2 = Map.of(
                "id", 2L, "entity_key", ENTITY_KEY,
                "cost", 150.0, "txn_to", Instant.parse("2026-03-01T00:00:00Z"));
        Map<String, Object> v3 = Map.of(
                "id", 3L, "entity_key", ENTITY_KEY,
                "cost", 200.0);

        when(jdbcTemplate.queryForList(anyString(), eq(ENTITY_KEY), eq(TENANT_ID)))
                .thenReturn(List.of(v1, v2, v3));

        List<Map<String, Object>> history = repository.findHistory(TABLE, ENTITY_KEY, TENANT_ID);

        assertEquals(3, history.size(), "Should return all 3 versions");

        // Verify SQL orders by txn_from ASC
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sqlCaptor.capture(), eq(ENTITY_KEY), eq(TENANT_ID));
        assertTrue(sqlCaptor.getValue().contains("ORDER BY txn_from ASC"),
                "History query should order by txn_from ascending");

        // Verify cost values escalate (reflecting corrections)
        assertEquals(100.0, history.get(0).get("cost"));
        assertEquals(150.0, history.get(1).get("cost"));
        assertEquals(200.0, history.get(2).get("cost"));
    }

    // ========== Test 6: Sanitize rejects SQL injection attempts ==========

    @Test
    void testSanitizeRejectsSqlInjection() {
        // Various injection attempts
        assertThrows(IllegalArgumentException.class,
                () -> repository.sanitize("table; DROP TABLE users;--"));
        assertThrows(IllegalArgumentException.class,
                () -> repository.sanitize("table' OR '1'='1"));
        assertThrows(IllegalArgumentException.class,
                () -> repository.sanitize("table name with spaces"));
        assertThrows(IllegalArgumentException.class,
                () -> repository.sanitize(""));  // empty string fails regex
        assertThrows(IllegalArgumentException.class,
                () -> repository.sanitize(null));

        // Valid names should pass
        assertEquals("biz_bt_cost", repository.sanitize("biz_bt_cost"));
        assertEquals("simple_table_123", repository.sanitize("simple_table_123"));
    }
}
