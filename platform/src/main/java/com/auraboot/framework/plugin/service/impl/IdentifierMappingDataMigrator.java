package com.auraboot.framework.plugin.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Identifier-mapping data migration contract (plugin rename upgrades).
 *
 * <p>When a plugin family is renamed (for example jiejia-* to customer-pcba-*),
 * existing installations keep their business rows in the physical dynamic-model
 * tables of the OLD identifiers (mt_jiejia_*). Importing the renamed plugin
 * creates the NEW metadata resources and NEW empty physical tables. This
 * migrator closes that gap: when an import request carries an identifier
 * mapping file (the same artifacts the plugins repo ships under
 * <code>migrations/*.json</code>), every mapped model pair whose OLD and NEW
 * physical tables both exist after import gets its rows copied from the old
 * table into the new table.</p>
 *
 * <p>Contract v1 (minimal, deliberately conservative):</p>
 * <ul>
 *   <li>Copy only; never delete or rename the legacy table in place.</li>
 *   <li>Preserve business identity: <code>pid</code> is copied verbatim, so
 *       cross-model references (stored as pid values) survive the upgrade.</li>
 *   <li>Preserve row ownership: <code>tenant_id</code>, audit fields, soft-delete
 *       marker and <code>row_version</code> are copied when present in both tables.</li>
 *   <li>Restrict to the importing tenant's rows — a tenant that has not adopted
 *       the renamed plugin keeps reading its rows through the legacy plugin.</li>
 *   <li>Column names are remapped through the same mapping (field codes were
 *       renamed together with model codes); columns with no new-table counterpart
 *       are dropped and reported.</li>
 *   <li>Idempotent: rows whose pid already exists in the new table are skipped,
 *       so re-running the upgrade import does not duplicate data.</li>
 * </ul>
 *
 * <p>This is a metadata-and-physical-table migration executed by the import
 * chain — it never issues hand-written UPDATEs against business tables outside
 * a declared mapping, and it never rewrites the legacy table.</p>
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class IdentifierMappingDataMigrator {

    /** Physical identifiers must be plain snake_case tokens before SQL interpolation. */
    private static final Pattern SAFE_SQL_IDENTIFIER = Pattern.compile("^[a-z0-9_]+$");

    /** Columns shared by every dynamic-model table; copied verbatim when present. */
    private static final Set<String> INFRASTRUCTURE_COLUMNS = Set.of(
            "pid", "created_at", "created_by", "updated_at", "updated_by",
            "tenant_id", "row_version", "deleted_flag");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /**
     * One migrated model pair. Part of the public import result so upgrade
     * receipts can be audited.
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ModelDataMigration {
        private String fromModel;
        private String toModel;
        private String sourceTable;
        private String targetTable;
        private long rowsMigrated;
        private long rowsAlreadyPresent;
        private int columnsRenamed;
        private List<String> droppedSourceColumns;
        private List<String> unmappedNewColumns;
    }

    /**
     * Load the mapping file and migrate every applicable model pair.
     *
     * @param mappingPath absolute path to an identifiers mapping JSON
     *                    ({"identifiers":[{"from":..,"to":..},...]})
     * @param tenantId    importing tenant; only this tenant's rows are migrated
     * @return per-model-pair migration receipts (only pairs that were migrated)
     */
    public List<ModelDataMigration> migrate(Path mappingPath, Long tenantId) {
        Map<String, String> identifierMap = loadMapping(mappingPath);
        if (identifierMap.isEmpty()) {
            log.warn("Identifier mapping file has no identifier pairs, skipping data migration: {}", mappingPath);
            return List.of();
        }

        List<ModelDataMigration> receipts = new ArrayList<>();
        // Column remap runs legacy->renamed, so lookups go through the inverted
        // (renamed -> legacy) view of the mapping.
        Map<String, String> reverseMap = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : identifierMap.entrySet()) {
            reverseMap.putIfAbsent(entry.getValue(), entry.getKey());
        }
        for (Map.Entry<String, String[]> pair : resolveModelPairs(identifierMap, tenantId).entrySet()) {
            String fromModel = pair.getKey();
            String toModel = pair.getValue()[0];
            String sourceTable = pair.getValue()[1];
            String targetTable = pair.getValue()[2];
            receipts.add(migrateModelPair(reverseMap, fromModel, toModel, sourceTable, targetTable, tenantId));
        }
        return receipts;
    }

    private Map<String, String> loadMapping(Path mappingPath) {
        Path normalized = mappingPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new IllegalArgumentException("Identifier mapping file does not exist: " + normalized);
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(normalized.toFile());
        } catch (Exception e) {
            throw new IllegalArgumentException("Identifier mapping file is not valid JSON: " + normalized, e);
        }
        JsonNode identifiers = root.get("identifiers");
        if (identifiers == null || !identifiers.isArray() || identifiers.isEmpty()) {
            throw new IllegalArgumentException(
                    "Identifier mapping file lacks a non-empty identifiers array: " + normalized);
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (JsonNode entry : identifiers) {
            String from = entry.path("from").asText(null);
            String to = entry.path("to").asText(null);
            if (from == null || to == null || from.isBlank() || to.isBlank() || from.equals(to)) {
                continue;
            }
            String previous = map.put(from, to);
            if (previous != null && !previous.equals(to)) {
                log.warn("Identifier mapping overrides {} : {} replaces {}", from, to, previous);
            }
        }
        return map;
    }

    /**
     * A mapping pair is a migratable model pair when metadata rows exist for
     * both codes and both resolved physical tables exist in the database.
     */
    private Map<String, String[]> resolveModelPairs(Map<String, String> identifierMap, Long tenantId) {
        Map<String, String[]> pairs = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : identifierMap.entrySet()) {
            String fromModel = entry.getKey();
            String toModel = entry.getValue();
            if (!isSafeIdentifier(fromModel) || !isSafeIdentifier(toModel)) {
                continue;
            }
            String sourceTable = resolvePhysicalTable(tenantId, fromModel);
            String targetTable = resolvePhysicalTable(tenantId, toModel);
            if (sourceTable == null || targetTable == null) {
                continue;
            }
            if (!tableExists(sourceTable) || !tableExists(targetTable)) {
                continue;
            }
            pairs.put(fromModel, new String[]{toModel, sourceTable, targetTable});
        }
        return pairs;
    }

    /** Resolved table name of an existing model, or null when the model is unknown. */
    private String resolvePhysicalTable(Long tenantId, String modelCode) {
        List<String> tableNames = jdbcTemplate.queryForList(
                "SELECT coalesce(nullif(table_name, ''), ?) FROM ab_meta_model "
                        + "WHERE code = ? AND tenant_id = ? AND deleted_flag = false ORDER BY pid LIMIT 1",
                String.class, DYNAMIC_TABLE_PREFIX + modelCode.toLowerCase(), modelCode, tenantId);
        return tableNames.isEmpty() ? null : tableNames.get(0);
    }

    private static final String DYNAMIC_TABLE_PREFIX = "mt_";

    private boolean tableExists(String tableName) {
        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM information_schema.tables "
                        + "WHERE table_schema = current_schema() AND table_name = ?)",
                Boolean.class, tableName);
        return Boolean.TRUE.equals(exists);
    }

    private boolean isSafeIdentifier(String value) {
        return value != null && SAFE_SQL_IDENTIFIER.matcher(value).matches();
    }

    private ModelDataMigration migrateModelPair(Map<String, String> legacyByRenamed,
                                                String fromModel, String toModel,
                                                String sourceTable, String targetTable,
                                                Long tenantId) {
        Set<String> sourceColumns = new LinkedHashSet<>(listColumns(sourceTable));
        Set<String> targetColumns = new LinkedHashSet<>(listColumns(targetTable));

        // Build the insert column list over the NEW table: for every target
        // column, source from the reverse-mapped legacy column when it exists,
        // otherwise from the identically named legacy column.
        List<String> insertColumns = new ArrayList<>();
        List<String> sourceSelectColumns = new ArrayList<>();
        List<String> droppedSourceColumns = new ArrayList<>();
        List<String> unmappedNewColumns = new ArrayList<>();
        int renamedCount = 0;
        Set<String> consumedSourceColumns = new LinkedHashSet<>();

        for (String targetColumn : targetColumns) {
            String legacyColumn = legacyByRenamed.getOrDefault(targetColumn, targetColumn);
            if (!sourceColumns.contains(legacyColumn)) {
                // No legacy source: the column stays at its new-table default.
                unmappedNewColumns.add(targetColumn);
                continue;
            }
            insertColumns.add(targetColumn);
            sourceSelectColumns.add(legacyColumn);
            consumedSourceColumns.add(legacyColumn);
            if (!legacyColumn.equals(targetColumn)) {
                renamedCount++;
            }
        }
        for (String sourceColumn : sourceColumns) {
            if (!consumedSourceColumns.contains(sourceColumn)) {
                droppedSourceColumns.add(sourceColumn);
            }
        }

        if (insertColumns.isEmpty() || !insertColumns.contains("pid")) {
            log.warn("Identifier mapping data migration skipped for {} -> {}: no common pid column",
                    fromModel, toModel);
            return ModelDataMigration.builder()
                    .fromModel(fromModel).toModel(toModel)
                    .sourceTable(sourceTable).targetTable(targetTable)
                    .rowsMigrated(0).rowsAlreadyPresent(0)
                    .columnsRenamed(0)
                    .droppedSourceColumns(List.copyOf(droppedSourceColumns))
                    .unmappedNewColumns(List.copyOf(unmappedNewColumns))
                    .build();
        }

        String columnList = String.join(", ", quoteAll(insertColumns));
        String selectList = String.join(", ", quoteAll(sourceSelectColumns));
        // Idempotency: skip rows whose pid the new table already holds, and
        // migrate only the importing tenant's rows (contract v1).
        String insertSql = "INSERT INTO " + quote(targetTable) + " (" + columnList + ") "
                + "SELECT " + selectList + " FROM " + quote(sourceTable) + " s "
                + "WHERE s.tenant_id = ? "
                + "AND NOT EXISTS (SELECT 1 FROM " + quote(targetTable) + " t WHERE t.pid = s.pid)";

        int inserted = jdbcTemplate.update(insertSql, tenantId);

        Long alreadyPresent = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + quote(sourceTable) + " s "
                        + "WHERE s.tenant_id = ? "
                        + "AND EXISTS (SELECT 1 FROM " + quote(targetTable) + " t WHERE t.pid = s.pid)",
                Long.class, tenantId);

        log.info("Identifier mapping data migration: {} -> {} ({} -> {}): migrated={}, alreadyPresent={}, renamedColumns={}",
                fromModel, toModel, sourceTable, targetTable, inserted, alreadyPresent, renamedCount);
        if (!droppedSourceColumns.isEmpty()) {
            log.info("Identifier mapping data migration dropped source columns for {} -> {}: {}",
                    fromModel, toModel, droppedSourceColumns);
        }

        return ModelDataMigration.builder()
                .fromModel(fromModel).toModel(toModel)
                .sourceTable(sourceTable).targetTable(targetTable)
                .rowsMigrated(inserted)
                .rowsAlreadyPresent(alreadyPresent == null ? 0 : alreadyPresent)
                .columnsRenamed(renamedCount)
                .droppedSourceColumns(List.copyOf(droppedSourceColumns))
                .unmappedNewColumns(List.copyOf(unmappedNewColumns))
                .build();
    }

    private List<String> listColumns(String tableName) {
        return jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() AND table_name = ? ORDER BY ordinal_position",
                String.class, tableName);
    }

    private static String quote(String identifier) {
        return "\"" + identifier + "\"";
    }

    private List<String> quoteAll(List<String> identifiers) {
        List<String> quoted = new ArrayList<>(identifiers.size());
        for (String identifier : identifiers) {
            if (!isSafeIdentifier(identifier)) {
                throw new IllegalArgumentException("Unsafe SQL identifier in migration: " + identifier);
            }
            quoted.add(quote(identifier));
        }
        return quoted;
    }
}
