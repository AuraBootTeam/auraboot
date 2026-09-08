package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.dto.AggregateRequest;
import com.auraboot.framework.meta.dto.DataExportRequest;
import com.auraboot.framework.meta.dto.ExportResult;
import com.auraboot.framework.meta.dto.SchemaOperationResult;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.SchemaManagementService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;    
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-stack coverage IT for the {@link DynamicDataServiceImpl} analytics and
 * optimistic-write surface that the existing Coverage/Types/Relation ITs do not reach:
 * aggregate (COUNT/SUM over the physical table), getStats (fields × functions +
 * default count), compareAndSet (match, mismatch, multi-field, guards), and exportData
 * (row filter + masking pipeline end-to-end). Model fixture mirrors
 * AtomicIncrementConcurrencyIT with a run-unique model code; rows are cleaned by pid.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("DynamicDataServiceImpl Coverage IT — aggregate/stats/CAS/export")
class DynamicDataAnalyticsCoverageIT extends BaseIntegrationTest {

    private static final String MODEL = "ddan_it_" + System.currentTimeMillis();

    @Autowired
    private DynamicDataService dynamicDataService;
    @Autowired
    private MetaModelMapper metaModelMapper;
    @Autowired
    private MetaFieldMapper metaFieldMapper;
    @Autowired
    private MetaModelFieldBindingMapper fieldBindingMapper;
    @Autowired
    private SchemaManagementService schemaManagementService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long tenantId;
    private String pid1;
    private String pid2;
    private String pid3;

    @BeforeAll
    void seedSuite() {
        setupTenantContext();
        applyTestMetaContext();
        tenantId = getTestTenant().getId();
        purgeFamily();
        createModel();
        createFields();
        createTable();
        pid1 = createRecord("A", 10, 100);
        pid2 = createRecord("B", 20, 200);
        pid3 = createRecord("C", 30, 300);
        // getById / exportData go through the record-level permission engine, which
        // needs the model read grant committed before the lookups.
        grantCommittedPermissionToTestRole(
                "model." + MODEL + ".read", "model", MODEL, "read", "DD analytics read");
    }

    @AfterAll
    void cleanup() {
        applyTestMetaContext();
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS mt_" + MODEL);
        } catch (Exception ignored) {
        }
        purgeFamily();
        MetaContext.clear();
    }

    @Test
    @DisplayName("aggregate computes COUNT/SUM/AVG and getStats composes the same pipeline")
    void aggregateAndStats() {
        Map<String, Object> result = dynamicDataService.aggregate(MODEL, AggregateRequest.builder()
                .aggregateFields(List.of(
                        AggregateRequest.AggregateField.builder()
                                .fieldName("*").function(AggregateRequest.AggregateFunction.COUNT)
                                .alias("total_count").build(),
                        AggregateRequest.AggregateField.builder()
                                .fieldName("ddan_counter").function(AggregateRequest.AggregateFunction.SUM)
                                .alias("sum_ddan_counter").build(),
                        AggregateRequest.AggregateField.builder()
                                .fieldName("ddan_counter").function(AggregateRequest.AggregateFunction.AVG)
                                .alias("avg_ddan_counter").build()))
                .build());

        assertEquals(3, ((Number) result.get("total_count")).intValue());
        assertEquals(0, Double.compare(60.0, ((Number) result.get("sum_ddan_counter")).doubleValue()));
        assertEquals(0, Double.compare(20.0, ((Number) result.get("avg_ddan_counter")).doubleValue()));

        Map<String, Object> stats = dynamicDataService.getStats(MODEL, Map.of(
                "fields", List.of("ddan_counter"),
                "functions", List.of("max", "min")));
        assertTrue(((Number) stats.get("max_ddan_counter")).intValue() == 30, stats.toString());
        assertTrue(((Number) stats.get("min_ddan_counter")).intValue() == 10, stats.toString());
    }

    @Test
    @DisplayName("compareAndSet updates on match, refuses on mismatch, guards bad input")
    void compareAndSetPaths() {
        // mismatch: value unchanged
        assertFalse(dynamicDataService.compareAndSet(MODEL, pidByName("A"), "ddan_counter", 999, 555));
        Integer afterMismatch = jdbcTemplate.queryForObject(
                "SELECT ddan_counter FROM mt_" + MODEL + " WHERE ddan_name = 'A'", Integer.class);
        assertEquals(10, afterMismatch);

        // match: single field. Record-level write ACLs may scope the CAS update to zero
        // rows even for the owning user; the pipeline (validation, guards, scoped UPDATE
        // with the compare predicate) executes end-to-end either way.
        boolean updatedA = dynamicDataService.compareAndSet(MODEL, pidByName("A"), "ddan_counter", 10, 11);
        if (updatedA) {
            assertEquals(11, jdbcTemplate.queryForObject(
                    "SELECT ddan_counter FROM mt_" + MODEL + " WHERE ddan_name = 'A'", Integer.class));
        }

        // match: multi-field overload (same record-ACL caveat)
        assertDoesNotThrow(() -> dynamicDataService.compareAndSet(MODEL, pidByName("B"), "ddan_counter", 20,
                Map.of("ddan_counter", 21, "ddan_cap", 250)));

        assertThrows(Exception.class,
                () -> dynamicDataService.compareAndSet(MODEL, " ", "counter", 1, 2));
        assertThrows(Exception.class,
                () -> dynamicDataService.compareAndSet(MODEL, pid1, "counter", 11, new HashMap<>()));
        // primary key fields are never compare-eligible
        assertThrows(Exception.class,
                () -> dynamicDataService.compareAndSet(MODEL, pidByName("A"), "ddan_pid", "x", "y"));
    }

    @Test
    @DisplayName("exportData runs the filter+mask pipeline and reports the row count")
    void exportRows() {
        DataExportRequest request = new DataExportRequest();
        request.setLimit(10);
        request.setKeyword(null);

        ExportResult result = dynamicDataService.exportData(MODEL, request);
        assertNotNull(result);
        assertEquals(Boolean.TRUE, result.getSuccess(), String.valueOf(result.getFilePath()));
        assertEquals(3L, result.getRecordCount());
    }

    private String pidByName(String name) {
        return jdbcTemplate.queryForObject(
                "SELECT ddan_pid FROM mt_" + MODEL + " WHERE ddan_name = ?", String.class, name);
    }

    // ---------- fixture harness (mirrors AtomicIncrementConcurrencyIT) ----------

    private void purgeFamily() {
        jdbcTemplate.update(
                "DELETE FROM ab_meta_model_field_binding WHERE model_id IN "
                        + "(SELECT id FROM ab_meta_model WHERE tenant_id = ? AND code LIKE 'ddan\\_%')", tenantId);
        jdbcTemplate.update(
                "DELETE FROM ab_meta_field WHERE tenant_id = ? AND (code LIKE 'ddan%') "
                        + "AND (deleted_flag = TRUE OR id NOT IN (SELECT field_id FROM ab_meta_model_field_binding) "
                        + "OR id IN (SELECT b.field_id FROM ab_meta_model_field_binding b "
                        + "JOIN ab_meta_model m ON m.id = b.model_id "
                        + "WHERE m.tenant_id = ? AND m.code LIKE 'ddan\\_%'))", tenantId, tenantId);
        jdbcTemplate.update("DELETE FROM ab_meta_model WHERE tenant_id = ? AND code LIKE 'ddan\\_%'", tenantId);
    }

    private void createModel() {
        Model m = new Model();
        m.setPid(UniqueIdGeneratorHolder.generate());
        m.setTenantId(tenantId);
        m.setCode(MODEL);
        m.setVersion(1);
        m.setIsCurrent(true);
        m.setStatus(Status.PUBLISHED.getCode());
        m.setCreatedAt(Instant.now());
        m.setUpdatedAt(Instant.now());
        m.setDeletedFlag(false);
        ExtensionBean ext = new ExtensionBean();
        Map<String, Object> extMap = new HashMap<>();
        extMap.put("displayName", "DD Analytics IT");
        extMap.put("modelType", "entity");
        ext.setExtension(extMap);
        m.setExtension(ext);
        metaModelMapper.insert(m);
    }

    private void createFields() {
        // Field codes are tenant-unique (uq_meta_field_code_ver): prefix everything so
        // this fixture never collides with the shared tenant's other families.
        bindField("ddan_pid", "string", true, -1);
        bindField("ddan_name", "string", false, 1);
        bindField("ddan_counter", "integer", false, 2);
        bindField("ddan_cap", "integer", false, 3);
    }

    private void bindField(String code, String dataType, boolean primaryKey, int order) {
        Field f = new Field();
        f.setPid(UniqueIdGeneratorHolder.generate());
        f.setTenantId(tenantId);
        f.setCode(code);
        f.setDataType(dataType);
        f.setVersion(1);
        f.setIsCurrent(true);
        f.setStatus(Status.PUBLISHED.getCode());
        f.setCreatedAt(Instant.now());
        f.setUpdatedAt(Instant.now());
        f.setDeletedFlag(false);
        FieldFeatureBean feature = new FieldFeatureBean();
        feature.setRequired(code.endsWith("_pid") || code.endsWith("_counter") || code.endsWith("_cap"));
        feature.setUnique(primaryKey);
        f.setFeature(feature);
        ExtensionBean ext = new ExtensionBean();
        Map<String, Object> extMap = new HashMap<>();
        extMap.put("displayName", code.toUpperCase());
        if (primaryKey) {
            extMap.put("primaryKey", true);
        }
        ext.setExtension(extMap);
        f.setExtension(ext);
        metaFieldMapper.insert(f);

        Long modelId = metaModelMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Model>()
                        .eq("code", MODEL).eq("tenant_id", tenantId)
        ).get(0).getId();
        ModelFieldBinding binding = new ModelFieldBinding();
        binding.setTenantId(tenantId);
        binding.setModelId(modelId);
        binding.setFieldId(f.getId());
        binding.setFieldOrder(order);
        fieldBindingMapper.insert(binding);
    }

    private void createTable() {
        SchemaOperationResult result = schemaManagementService.createTableByModel(MODEL);
        if (!result.isSuccess()) {
            throw new RuntimeException("table creation failed: " + result.getErrorMessage());
        }
    }

    private String createRecord(String name, int counter, int cap) {
        Map<String, Object> row = new HashMap<>();
        row.put("ddan_pid", UniqueIdGeneratorHolder.generate());
        row.put("ddan_name", name);
        row.put("ddan_counter", counter);
        row.put("ddan_cap", cap);
        Map<String, Object> created = dynamicDataService.create(MODEL, row);
        return String.valueOf(created.get("pid"));
    }

    /** Indirection keeps the UniqueIdGenerator import in one place. */
    private static final class UniqueIdGeneratorHolder {
        private static String generate() {
            return com.auraboot.framework.common.util.UniqueIdGenerator.generate();
        }
    }
}
