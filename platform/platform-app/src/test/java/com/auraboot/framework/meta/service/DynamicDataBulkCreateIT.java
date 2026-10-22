package com.auraboot.framework.meta.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.dto.SchemaOperationResult;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link DynamicDataService#bulkCreate} — the batch-insert fast path.
 *
 * <p>Asserts the three contracts the quote BOM import relies on:
 * <ol>
 *   <li>All N rows are persisted via a single multi-row INSERT (single transaction).</li>
 *   <li>The returned list carries the generated primary keys, in input order — so callers
 *       can correlate ids without a per-row select-back.</li>
 *   <li>JSONB host columns round-trip (the {@code batchInsertWithJsonb} {@code ::jsonb} cast),
 *       i.e. no "column is of type jsonb but expression is of type character varying".</li>
 * </ol>
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("DynamicDataService.bulkCreate — batch insert fast path")
class DynamicDataBulkCreateIT extends BaseIntegrationTest {

    @Autowired private DynamicDataService dynamicDataService;
    @Autowired private MetaModelMapper metaModelMapper;
    @Autowired private MetaFieldMapper metaFieldMapper;
    @Autowired private MetaModelFieldBindingMapper fieldBindingMapper;
    @Autowired private SchemaManagementService schemaManagementService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private String modelCode;
    private String nameField;
    private String cellsField; // jsonb
    private String qtyField;   // integer
    private boolean modelReady = false;
    private final List<String> createdPids = Collections.synchronizedList(new ArrayList<>());

    @BeforeAll
    void initModelCode() {
        String suffix = Long.toString(System.currentTimeMillis(), 36);
        modelCode = "bulk_" + suffix;
        nameField = "bulk_name_" + suffix;
        cellsField = "bulk_cells_" + suffix;
        qtyField = "bulk_qty_" + suffix;
    }

    @BeforeEach
    void ensureModel() {
        setupTenantContext();
        if (!modelReady) {
            dropIfExists();
            createModel();
            bindField(nameField, "string", 1);
            bindField(cellsField, "jsonb", 2);
            bindField(qtyField, "integer", 3);
            createPhysicalTable();
            modelReady = true;
        }
    }

    @AfterEach
    void cleanRows() {
        for (String pid : new ArrayList<>(createdPids)) {
            try { dynamicDataService.delete(modelCode, pid); } catch (Exception ignored) {}
        }
        createdPids.clear();
    }

    @AfterAll
    void teardown() {
        dropIfExists();
    }

    @Test
    @Order(1)
    @DisplayName("bulkCreate N rows with a jsonb column — all persist, pids returned in order, jsonb round-trips")
    void bulkCreate_persists_all_rows_with_pids_in_order_and_jsonb_roundtrips() {
        int n = 12;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String, Object> row = new HashMap<>();
            row.put(nameField, "row-" + i);
            row.put(qtyField, i + 1);
            // jsonb value (a List) — must be cast ::jsonb by batchInsertWithJsonb
            row.put(cellsField, List.of("cell-" + i + "-a", "cell-" + i + "-b"));
            rows.add(row);
        }

        List<Map<String, Object>> created = dynamicDataService.bulkCreate(modelCode, rows);

        // (2) returned list carries generated pids, in input order
        assertThat(created).as("returns one record per input row").hasSize(n);
        for (int i = 0; i < n; i++) {
            Map<String, Object> rec = created.get(i);
            String pid = Objects.toString(rec.get("pid"), "");
            assertThat(pid).as("row %s has a generated pid", i).isNotBlank();
            createdPids.add(pid);
            assertThat(Objects.toString(rec.get(nameField)))
                    .as("returned order matches input order at %s", i)
                    .isEqualTo("row-" + i);
        }
        assertThat(createdPids.stream().distinct().count())
                .as("all pids are distinct").isEqualTo((long) n);

        // (1) all N rows actually persisted (single batch insert) — verified at the DB level
        Integer dbCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mt_" + modelCode, Integer.class);
        assertThat(dbCount).as("all %s rows present in the physical table", n).isEqualTo(n);

        // (3) jsonb round-trips at the DB level — proves the batchInsertWithJsonb ::jsonb cast
        String cellsJson = jdbcTemplate.queryForObject(
                "SELECT " + cellsField + "::text FROM mt_" + modelCode + " WHERE pid = ?",
                String.class, createdPids.get(3));
        assertThat(cellsJson)
                .as("jsonb value round-trips (no varchar→jsonb cast error)")
                .contains("cell-3-a");
        String name3 = jdbcTemplate.queryForObject(
                "SELECT " + nameField + " FROM mt_" + modelCode + " WHERE pid = ?",
                String.class, createdPids.get(3));
        assertThat(name3).as("row content persisted in input order").isEqualTo("row-3");
    }

    @Test
    @Order(2)
    @DisplayName("bulkCreate with a single row also works")
    void bulkCreate_single_row() {
        Map<String, Object> row = new HashMap<>();
        row.put(nameField, "solo");
        row.put(qtyField, 7);
        row.put(cellsField, List.of("only"));

        List<Map<String, Object>> created = dynamicDataService.bulkCreate(modelCode, List.of(row));
        assertThat(created).hasSize(1);
        String pid = Objects.toString(created.get(0).get("pid"), "");
        assertThat(pid).isNotBlank();
        createdPids.add(pid);

        Map<String, Object> row2 = jdbcTemplate.queryForMap(
                "SELECT " + nameField + ", " + qtyField + " FROM mt_" + modelCode + " WHERE pid = ?", pid);
        assertThat(Objects.toString(row2.get(nameField))).isEqualTo("solo");
        assertThat(Objects.toString(row2.get(qtyField))).isEqualTo("7");
    }

    // ── Helpers (mirror DynamicDataJsonbUpdateIT) ──────────────────────────────

    private void dropIfExists() {
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS mt_" + modelCode);
        } catch (Exception e) {
            log.debug("[bulk-it] dropIfExists table: {}", e.getMessage());
        }
        try {
            Long tenantId = getTestTenant().getId();
            jdbcTemplate.update(
                    "DELETE FROM ab_meta_model_field_binding WHERE model_id IN "
                    + "(SELECT id FROM ab_meta_model WHERE code = ? AND tenant_id = ?)",
                    modelCode, tenantId);
            jdbcTemplate.update(
                    "DELETE FROM ab_meta_field WHERE tenant_id = ? AND code IN (?, ?, ?)",
                    tenantId, nameField, cellsField, qtyField);
            jdbcTemplate.update(
                    "DELETE FROM ab_meta_model WHERE code = ? AND tenant_id = ?",
                    modelCode, tenantId);
        } catch (Exception e) {
            log.debug("[bulk-it] dropIfExists meta: {}", e.getMessage());
        }
    }

    private void createModel() {
        Model m = new Model();
        m.setPid(UniqueIdGenerator.generate());
        m.setTenantId(getTestTenant().getId());
        m.setCode(modelCode);
        m.setVersion(1);
        m.setIsCurrent(true);
        m.setStatus(Status.PUBLISHED.getCode());
        m.setCreatedAt(Instant.now());
        m.setUpdatedAt(Instant.now());
        m.setDeletedFlag(false);
        ExtensionBean ext = new ExtensionBean();
        Map<String, Object> extMap = new HashMap<>();
        extMap.put("displayName", "Bulk Create IT Model");
        extMap.put("modelType", "entity");
        ext.setExtension(extMap);
        m.setExtension(ext);
        metaModelMapper.insert(m);
    }

    private void bindField(String code, String dataType, int order) {
        Field f = new Field();
        f.setPid(UniqueIdGenerator.generate());
        f.setTenantId(getTestTenant().getId());
        f.setCode(code);
        f.setDataType(dataType);
        f.setVersion(1);
        f.setIsCurrent(true);
        f.setStatus(Status.PUBLISHED.getCode());
        f.setCreatedAt(Instant.now());
        f.setUpdatedAt(Instant.now());
        f.setDeletedFlag(false);

        FieldFeatureBean feature = new FieldFeatureBean();
        feature.setRequired(false);
        feature.setUnique(false);
        f.setFeature(feature);

        ExtensionBean ext = new ExtensionBean();
        Map<String, Object> extMap = new HashMap<>();
        extMap.put("displayName", code.toUpperCase());
        ext.setExtension(extMap);
        f.setExtension(ext);

        metaFieldMapper.insert(f);

        Long tenantId = getTestTenant().getId();
        Long modelId = metaModelMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Model>()
                        .eq("code", modelCode).eq("tenant_id", tenantId)
        ).get(0).getId();

        ModelFieldBinding binding = new ModelFieldBinding();
        binding.setTenantId(tenantId);
        binding.setModelId(modelId);
        binding.setFieldId(f.getId());
        binding.setFieldOrder(order);
        fieldBindingMapper.insert(binding);
    }

    private void createPhysicalTable() {
        SchemaOperationResult result = schemaManagementService.createTableByModel(modelCode);
        if (!result.isSuccess()) {
            throw new RuntimeException("Physical table creation failed: " + result.getErrorMessage());
        }
    }
}
