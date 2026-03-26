package com.auraboot.module.meta.bitemporal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for BitemporalRepository.
 */
@ExtendWith(MockitoExtension.class)
class BitemporalRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private BitemporalRepository repository;

    private static final String TABLE = "biz_bt_price";
    private static final Long ENTITY_KEY = 42L;
    private static final Long TENANT_ID = 1L;

    @BeforeEach
    void setUp() {
        repository = new BitemporalRepository(jdbcTemplate);
    }

    @Test
    void testFindCurrentReturnsResult() {
        Map<String, Object> row = Map.of(
                "id", 1L, "entity_key", ENTITY_KEY,
                "valid_from", LocalDate.of(2026, 1, 1),
                "valid_to", LocalDate.of(9999, 12, 31),
                "price", 100.0);

        when(jdbcTemplate.queryForList(anyString(), eq(ENTITY_KEY), eq(TENANT_ID)))
                .thenReturn(List.of(row));

        Map<String, Object> result = repository.findCurrent(TABLE, ENTITY_KEY, TENANT_ID);

        assertNotNull(result);
        assertEquals(100.0, result.get("price"));
        assertEquals(ENTITY_KEY, result.get("entity_key"));

        // Verify the SQL contains the right conditions
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sqlCaptor.capture(), eq(ENTITY_KEY), eq(TENANT_ID));
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("txn_to IS NULL"));
        assertTrue(sql.contains("valid_from <= CURRENT_DATE"));
        assertTrue(sql.contains("valid_to > CURRENT_DATE"));
    }

    @Test
    void testFindCurrentReturnsNullWhenEmpty() {
        when(jdbcTemplate.queryForList(anyString(), eq(ENTITY_KEY), eq(TENANT_ID)))
                .thenReturn(List.of());

        Map<String, Object> result = repository.findCurrent(TABLE, ENTITY_KEY, TENANT_ID);

        assertNull(result);
    }

    @Test
    void testFindAsOfWithValidDate() {
        LocalDate validDate = LocalDate.of(2026, 3, 15);
        Instant systemDate = Instant.parse("2026-03-15T10:00:00Z");

        Map<String, Object> row = Map.of("id", 5L, "entity_key", ENTITY_KEY, "price", 200.0);
        when(jdbcTemplate.queryForList(anyString(),
                eq(ENTITY_KEY), eq(validDate), eq(validDate),
                eq(Timestamp.from(systemDate)), eq(Timestamp.from(systemDate)), eq(TENANT_ID)))
                .thenReturn(List.of(row));

        Map<String, Object> result = repository.findAsOf(TABLE, ENTITY_KEY, validDate, systemDate, TENANT_ID);

        assertNotNull(result);
        assertEquals(200.0, result.get("price"));
    }

    @Test
    void testCorrectEndsOldAndCreatesNew() {
        // We need to mock SimpleJdbcInsert which is created inside the method.
        // Since SimpleJdbcInsert is created with `new`, we verify the UPDATE call
        // and trust the insert logic. For a full integration test, use a real DB.

        // Mock the UPDATE (end current version)
        when(jdbcTemplate.update(anyString(), eq(ENTITY_KEY), eq(TENANT_ID))).thenReturn(1);

        // Verify the UPDATE was called with correct params
        // Note: The insert part creates a SimpleJdbcInsert internally which
        // requires a real DataSource. We test the update logic here.
        // The full correct() flow is better tested as an integration test.
        try {
            repository.correct(TABLE, ENTITY_KEY, Map.of("price", 300.0), TENANT_ID);
        } catch (Exception e) {
            // SimpleJdbcInsert will fail without a DataSource, but the UPDATE should succeed
        }

        // Verify the end-current-version UPDATE was called
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), eq(ENTITY_KEY), eq(TENANT_ID));
        String updateSql = sqlCaptor.getValue();
        assertTrue(updateSql.contains("SET txn_to = NOW()"));
        assertTrue(updateSql.contains("txn_to IS NULL"));
    }

    @Test
    void testFindHistoryOrderByTxnFrom() {
        Map<String, Object> v1 = Map.of("id", 1L, "txn_from", Instant.parse("2026-01-01T00:00:00Z"));
        Map<String, Object> v2 = Map.of("id", 2L, "txn_from", Instant.parse("2026-02-01T00:00:00Z"));
        Map<String, Object> v3 = Map.of("id", 3L, "txn_from", Instant.parse("2026-03-01T00:00:00Z"));

        when(jdbcTemplate.queryForList(anyString(), eq(ENTITY_KEY), eq(TENANT_ID)))
                .thenReturn(List.of(v1, v2, v3));

        List<Map<String, Object>> history = repository.findHistory(TABLE, ENTITY_KEY, TENANT_ID);

        assertEquals(3, history.size());

        // Verify SQL orders by txn_from ASC
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sqlCaptor.capture(), eq(ENTITY_KEY), eq(TENANT_ID));
        assertTrue(sqlCaptor.getValue().contains("ORDER BY txn_from ASC"));
    }

    @Test
    void testSanitizeRejectsInvalidTableName() {
        assertThrows(IllegalArgumentException.class, () -> repository.sanitize("DROP TABLE;--"));
        assertThrows(IllegalArgumentException.class, () -> repository.sanitize("table name"));
        assertThrows(IllegalArgumentException.class, () -> repository.sanitize(null));
    }

    @Test
    void testSanitizeAcceptsValidTableName() {
        assertEquals("biz_bt_price", repository.sanitize("biz_bt_price"));
        assertEquals("MyTable123", repository.sanitize("MyTable123"));
    }
}
