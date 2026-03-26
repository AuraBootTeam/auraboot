package com.auraboot.module.meta.bitemporal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Repository for bitemporal data access.
 *
 * <p>Uses JdbcTemplate directly because bitemporal tables have special
 * temporal columns (valid_from, valid_to, txn_from, txn_to) that are
 * not compatible with the standard DynamicDataMapper pattern.
 * This is an accepted exception to the "no JdbcTemplate" rule.
 *
 * @author AuraBoot Team
 * @since 2.5.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BitemporalRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ConcurrentHashMap<String, SimpleJdbcInsert> insertCache = new ConcurrentHashMap<>();

    /**
     * Find the current version of an entity (active in both valid-time and txn-time).
     *
     * @param tableName table name (must be a safe identifier)
     * @param entityKey logical entity key
     * @param tenantId  tenant ID
     * @return the current row, or null if not found
     */
    public Map<String, Object> findCurrent(String tableName, Long entityKey, Long tenantId) {
        String sql = "SELECT * FROM " + sanitize(tableName)
                + " WHERE entity_key = ? AND txn_to IS NULL"
                + " AND valid_from <= CURRENT_DATE AND valid_to > CURRENT_DATE"
                + " AND tenant_id = ?";
        List<Map<String, Object>> results = jdbcTemplate.queryForList(sql, entityKey, tenantId);
        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * Find the version of an entity as of a specific valid date and system date.
     *
     * @param tableName  table name
     * @param entityKey  logical entity key
     * @param validDate  the date in valid-time to query
     * @param systemDate the instant in transaction-time to query
     * @param tenantId   tenant ID
     * @return the matching row, or null if not found
     */
    public Map<String, Object> findAsOf(String tableName, Long entityKey,
                                         LocalDate validDate, Instant systemDate, Long tenantId) {
        String sql = "SELECT * FROM " + sanitize(tableName)
                + " WHERE entity_key = ? AND valid_from <= ? AND valid_to > ?"
                + " AND txn_from <= ? AND (txn_to IS NULL OR txn_to > ?)"
                + " AND tenant_id = ?";
        List<Map<String, Object>> results = jdbcTemplate.queryForList(
                sql, entityKey, validDate, validDate,
                Timestamp.from(systemDate), Timestamp.from(systemDate), tenantId);
        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * Correct an entity: end the current txn-time version and insert a corrected one.
     *
     * <p><b>Concurrency note:</b> Uses SELECT FOR UPDATE to prevent concurrent corrections
     * of the same entity. The target table MUST have the GiST exclusion constraint generated
     * by {@link BitemporalDdlHelper#generateExclusionConstraint(String)} as a safety net.
     *
     * @param tableName     table name
     * @param entityKey     logical entity key
     * @param correctedData the corrected field values (must include valid_from, valid_to)
     * @param tenantId      tenant ID
     * @return the new row ID
     */
    @Transactional
    public Long correct(String tableName, Long entityKey, Map<String, Object> correctedData, Long tenantId) {
        String safe = sanitize(tableName);

        // Lock the current version to prevent concurrent corrections
        String lockSql = "SELECT id FROM " + safe
                + " WHERE entity_key = ? AND txn_to IS NULL AND tenant_id = ? FOR UPDATE";
        jdbcTemplate.queryForList(lockSql, entityKey, tenantId);

        // End the current version by setting txn_to = NOW()
        String endSql = "UPDATE " + safe
                + " SET txn_to = NOW() WHERE entity_key = ? AND txn_to IS NULL AND tenant_id = ?";
        int updated = jdbcTemplate.update(endSql, entityKey, tenantId);
        log.debug("Ended {} current version(s) for entity_key={} in {}", updated, entityKey, tableName);

        // Insert corrected version
        Map<String, Object> insertData = new HashMap<>(correctedData);
        insertData.put("entity_key", entityKey);
        insertData.put("tenant_id", tenantId);
        insertData.put("txn_from", Timestamp.from(Instant.now()));
        // txn_to = NULL means current

        return insertAndReturnId(safe, insertData);
    }

    /**
     * Find the full history of an entity (all versions, ordered by txn_from).
     *
     * @param tableName table name
     * @param entityKey logical entity key
     * @param tenantId  tenant ID
     * @return list of all versions, ordered by transaction time
     */
    public List<Map<String, Object>> findHistory(String tableName, Long entityKey, Long tenantId) {
        String sql = "SELECT * FROM " + sanitize(tableName)
                + " WHERE entity_key = ? AND tenant_id = ? ORDER BY txn_from ASC";
        return jdbcTemplate.queryForList(sql, entityKey, tenantId);
    }

    /**
     * Validate that a table name contains only safe characters to prevent SQL injection.
     */
    String sanitize(String tableName) {
        if (tableName == null || !tableName.matches("[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("Invalid table name: " + tableName);
        }
        return tableName;
    }

    /**
     * Insert a row and return its generated ID.
     * Caches SimpleJdbcInsert instances per table to avoid repeated metadata introspection.
     */
    private Long insertAndReturnId(String tableName, Map<String, Object> data) {
        SimpleJdbcInsert insert = insertCache.computeIfAbsent(tableName,
                name -> new SimpleJdbcInsert(jdbcTemplate)
                        .withTableName(name)
                        .usingGeneratedKeyColumns("id"));
        Number key = insert.executeAndReturnKey(data);
        return key.longValue();
    }
}
