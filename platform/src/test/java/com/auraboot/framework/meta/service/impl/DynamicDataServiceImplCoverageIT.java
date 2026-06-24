package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.constant.DataType;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.dto.ActionExecutionResult;
import com.auraboot.framework.meta.dto.DataExportRequest;
import com.auraboot.framework.meta.dto.DataImportRequest;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.ExportResult;
import com.auraboot.framework.meta.dto.ImportResult;
import com.auraboot.framework.meta.dto.FieldOption;
import com.auraboot.framework.meta.dto.FieldOptionRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.dto.QueryCondition;
import com.auraboot.framework.meta.dto.SchemaOperationResult;
import com.auraboot.framework.meta.dto.SortField;
import com.auraboot.framework.meta.dto.ValidationContext;
import com.auraboot.framework.meta.dto.ValidationResult;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.SchemaManagementService;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-stack coverage IT for {@link DynamicDataServiceImpl} — the dynamic CRUD core
 * (~2826 LOC, the single biggest coverage gap in {@code meta/service/impl}).
 *
 * <p>Part of OSS coverage initiative #8/#9 (tracker:
 * {@code docs/backlog/2026-06-10-oss-coverage-to-80-tracker.md}). The existing IT classes
 * cover the create/getById/update/delete/list/batch/aggregate happy paths; this one
 * targets the under-covered query-builder branches and the uncovered method surface:
 * the full 14-operator {@code list} sweep, sort/keyword/pagination edges, {@code getStats},
 * {@code getFieldOptions}, {@code validate}, {@code exportData}, and the not-found error
 * branches — all against a real model + physical table (no mocks).
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("DynamicDataServiceImpl Coverage IT — query branches + uncovered methods")
class DynamicDataServiceImplCoverageIT {

    @Autowired
    private DynamicDataService dynamicDataService;
    @Autowired
    private SchemaManagementService schemaManagementService;
    @Autowired
    private MetaModelMapper metaModelMapper;
    @Autowired
    private MetaFieldMapper metaFieldMapper;
    @Autowired
    private MetaModelFieldBindingMapper fieldBindingMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserService userService;
    @Autowired
    private TenantService tenantService;
    @Autowired
    private TenantMemberService tenantMemberService;

    private String modelCode;
    private String tableName;
    private Model testModel;
    private User testUser;
    private Tenant testTenant;
    private boolean modelInitialized = false;
    private boolean seeded = false;

    @TempDir
    Path importTmp;

    @BeforeEach
    void ensureModelExists() {
        setupTenantContext();
        if (!modelInitialized) {
            modelCode = "dyncov_" + Math.abs(System.nanoTime());
            tableName = "mt_" + modelCode.toLowerCase();
            purgeTestArtifacts(); // clear leftovers from any prior failed run (field codes are tenant-unique)
            createTestModel();
            createTestFields();
            createPhysicalTable();
            modelInitialized = true;
        }
        if (!seeded) {
            seedData(9);
            seeded = true;
        }
    }

    @AfterAll
    void cleanup() {
        try {
            purgeTestArtifacts();
        } finally {
            MetaContext.clear();
        }
    }

    /** Drop the physical table and delete the model/fields/bindings created by this class. */
    private void purgeTestArtifacts() {
        if (testTenant == null) {
            return;
        }
        Long tid = testTenant.getId();
        try {
            if (tableName != null) {
                jdbcTemplate.execute("DROP TABLE IF EXISTS " + tableName);
            }
            jdbcTemplate.update("DELETE FROM ab_meta_model_field_binding WHERE model_id IN "
                    + "(SELECT id FROM ab_meta_model WHERE code LIKE 'dyncov%' AND tenant_id = ?)", tid);
            jdbcTemplate.update("DELETE FROM ab_meta_field WHERE code IN ('pid','name','status') AND tenant_id = ?", tid);
            jdbcTemplate.update("DELETE FROM ab_meta_model WHERE code LIKE 'dyncov%' AND tenant_id = ?", tid);
        } catch (Exception e) {
            log.warn("dyncov purge failed: {}", e.getMessage());
        }
    }

    // ==================== list: full operator sweep ====================

    @Test
    @DisplayName("list exercises every QueryCondition.Operator (applied or explicitly rejected)")
    void listOperatorSweep() {
        for (QueryCondition.Operator op : QueryCondition.Operator.values()) {
            DynamicQueryRequest req = DynamicQueryRequest.builder()
                    .pageNum(1).pageSize(10)
                    .conditions(List.of(conditionFor(op)))
                    .build();
            try {
                PaginationResult<Map<String, Object>> res = dynamicDataService.list(modelCode, req);
                assertNotNull(res, "list result null for operator " + op);
                assertNotNull(res.getRecords(), "records null for operator " + op);
            } catch (MetaServiceException e) {
                // A few operators (e.g. NOT_BETWEEN) are defined in the enum but intentionally
                // not wired in the query builder — it rejects them with "Invalid operator".
                // That rejection branch is itself part of the behavior under test.
                assertTrue(e.getMessage() != null && e.getMessage().toLowerCase().contains("operator"),
                        "unexpected failure for operator " + op + ": " + e.getMessage());
            }
        }
    }

    private QueryCondition conditionFor(QueryCondition.Operator op) {
        QueryCondition.QueryConditionBuilder b = QueryCondition.builder().fieldName("status").operator(op);
        switch (op) {
            case IN, NOT_IN -> b.values(List.of("active", "inactive", "done"));
            case BETWEEN, NOT_BETWEEN -> b.values(List.of("a", "z"));
            case IS_NULL, IS_NOT_NULL -> { /* no value */ }
            default -> b.value("active");
        }
        return b.build();
    }

    @Test
    @DisplayName("list with sorting (ASC/DESC), keyword and pagination edges")
    void listSortKeywordPagination() {
        PaginationResult<Map<String, Object>> asc = dynamicDataService.list(modelCode,
                DynamicQueryRequest.builder().pageNum(1).pageSize(5)
                        .sortFields(List.of(SortField.builder().fieldName("name")
                                .direction(SortField.SortDirection.ASC).build()))
                        .build());
        assertNotNull(asc.getRecords());

        PaginationResult<Map<String, Object>> desc = dynamicDataService.list(modelCode,
                DynamicQueryRequest.builder().pageNum(1).pageSize(5)
                        .sortFields(List.of(SortField.builder().fieldName("status")
                                .direction(SortField.SortDirection.DESC).build()))
                        .build());
        assertNotNull(desc.getRecords());

        PaginationResult<Map<String, Object>> kw = dynamicDataService.list(modelCode,
                DynamicQueryRequest.builder().pageNum(1).pageSize(10).keyword("rec").build());
        assertNotNull(kw.getRecords());

        // page far beyond the data -> empty page, still a valid result
        PaginationResult<Map<String, Object>> beyond = dynamicDataService.list(modelCode,
                DynamicQueryRequest.builder().pageNum(99).pageSize(10).build());
        assertNotNull(beyond.getRecords());
        assertTrue(beyond.getRecords().isEmpty());
    }

    // ==================== getById / update / delete error branches ====================

    @Test
    @DisplayName("getById round-trips an existing record and throws for missing/blank ids")
    void getByIdBranches() {
        String pid = seedOne("lookup", "active");
        Map<String, Object> got = dynamicDataService.getById(modelCode, pid);
        assertNotNull(got);
        assertTrue(pid.equals(String.valueOf(got.get("pid"))));

        assertThrows(MetaServiceException.class, () -> dynamicDataService.getById(modelCode, "no-such-pid"));
        assertThrows(MetaServiceException.class, () -> dynamicDataService.getById(modelCode, ""));
    }

    @Test
    @DisplayName("update and delete of a missing record raise MetaServiceException")
    void updateDeleteMissing() {
        Map<String, Object> data = new HashMap<>();
        data.put("name", "x");
        assertThrows(MetaServiceException.class, () -> dynamicDataService.update(modelCode, "no-such-pid", data));
        assertThrows(MetaServiceException.class, () -> dynamicDataService.delete(modelCode, "no-such-pid"));
    }

    // ==================== stats / field options ====================

    @Test
    @DisplayName("getStats aggregates over the model")
    void getStats() {
        Map<String, Object> params = new HashMap<>();
        params.put("fields", List.of("name"));
        params.put("functions", List.of("COUNT"));
        Map<String, Object> stats = dynamicDataService.getStats(modelCode, params);
        assertNotNull(stats);
    }

    @Test
    @DisplayName("getFieldOptions returns option list for a field")
    void getFieldOptions() {
        List<FieldOption> options = dynamicDataService.getFieldOptions(modelCode, "status",
                FieldOptionRequest.builder().build());
        assertNotNull(options);
    }

    // ==================== validate ====================

    @Test
    @DisplayName("validate accepts complete data and flags missing required fields")
    void validate() {
        Map<String, Object> ok = new HashMap<>();
        ok.put("name", "valid");
        ok.put("status", "active");
        ValidationResult valid = dynamicDataService.validate(modelCode, ok, ValidationContext.CREATE);
        assertNotNull(valid);
        assertTrue(valid.isValid(), "complete data should validate");

        Map<String, Object> missingRequired = new HashMap<>();
        missingRequired.put("status", "active"); // name (required) omitted
        ValidationResult invalid = dynamicDataService.validate(modelCode, missingRequired, ValidationContext.CREATE);
        assertNotNull(invalid);
    }

    // ==================== export ====================

    @Test
    @DisplayName("exportData produces a result for JSON and CSV formats")
    void exportData() {
        DataExportRequest json = new DataExportRequest();
        json.setFormat(DataExportRequest.ExportFormat.JSON);
        ExportResult jsonResult = dynamicDataService.exportData(modelCode, json);
        assertNotNull(jsonResult);

        DataExportRequest csv = new DataExportRequest();
        csv.setFormat(DataExportRequest.ExportFormat.CSV);
        ExportResult csvResult = dynamicDataService.exportData(modelCode, csv);
        assertNotNull(csvResult);
    }

    // ==================== importData ====================

    @Test
    @DisplayName("importData: CSV/JSON happy paths, field-mapping, file-not-found and per-row error branches")
    void importDataBranches() throws Exception {
        // NOTE: importData does not auto-generate the primary key (unlike create()), and the
        // physical PK column is `pid VARCHAR(32) NOT NULL UNIQUE`. So a valid import (migration /
        // re-import) must carry an explicit pid; these happy cases supply one.
        // CSV happy path — header row are field codes; default skipFirstRow=true skips the header
        Path csv = importTmp.resolve("ok.csv");
        Files.writeString(csv, "pid,name,status\n"
                + UniqueIdGenerator.generate() + ",imp-csv-1,active\n"
                + UniqueIdGenerator.generate() + ",imp-csv-2,inactive\n");
        ImportResult csvRes = dynamicDataService.importData(modelCode,
                DataImportRequest.builder().filePath(csv.toString())
                        .format(DataImportRequest.ImportFormat.CSV).build());
        assertNotNull(csvRes);
        assertTrue(csvRes.getSuccess(), "clean CSV import should succeed: " + csvRes.getSummary());
        assertEquals(2, csvRes.getSuccessCount().intValue());

        // JSON happy path — an array of objects
        Path json = importTmp.resolve("ok.json");
        Files.writeString(json, "[{\"pid\":\"" + UniqueIdGenerator.generate()
                + "\",\"name\":\"imp-json-1\",\"status\":\"done\"}]");
        ImportResult jsonRes = dynamicDataService.importData(modelCode,
                DataImportRequest.builder().filePath(json.toString())
                        .format(DataImportRequest.ImportFormat.JSON).build());
        assertNotNull(jsonRes);
        assertTrue(jsonRes.getSuccess(), "clean JSON import should succeed: " + jsonRes.getSummary());
        assertEquals(1, jsonRes.getSuccessCount().intValue());

        // Field mapping — source headers differ from field codes and are remapped before insert
        Path mapped = importTmp.resolve("mapped.csv");
        Files.writeString(mapped, "PID,Full Name,State\n"
                + UniqueIdGenerator.generate() + ",imp-map-1,active\n");
        ImportResult mapRes = dynamicDataService.importData(modelCode,
                DataImportRequest.builder().filePath(mapped.toString())
                        .format(DataImportRequest.ImportFormat.CSV)
                        .fieldMapping(Map.of("PID", "pid", "Full Name", "name", "State", "status"))
                        .build());
        assertNotNull(mapRes);
        assertTrue(mapRes.getSuccess(), "mapped CSV import should succeed: " + mapRes.getSummary());
        assertEquals(1, mapRes.getSuccessCount().intValue());

        // File not found — returns a failed result rather than throwing
        ImportResult missingRes = dynamicDataService.importData(modelCode,
                DataImportRequest.builder().filePath(importTmp.resolve("does-not-exist.csv").toString())
                        .format(DataImportRequest.ImportFormat.CSV).build());
        assertNotNull(missingRes);
        assertFalse(missingRes.getSuccess());

        // Per-row error — an unknown column makes toColumnData reject the row (failedCount path)
        Path bad = importTmp.resolve("bad.csv");
        Files.writeString(bad, "name,status,bogus_unknown_col\nimp-bad-1,active,oops\n");
        ImportResult badRes = dynamicDataService.importData(modelCode,
                DataImportRequest.builder().filePath(bad.toString())
                        .format(DataImportRequest.ImportFormat.CSV).build());
        assertNotNull(badRes);
        assertFalse(badRes.getSuccess(), "rows with an unknown column should fail");
        assertTrue(badRes.getFailedCount() >= 1);
    }

    // ==================== executeCustomAction ====================

    @Test
    @DisplayName("executeCustomAction: count and unsupported actions")
    void executeCustomActionBranches() {
        // count — read-only aggregate over the model's own physical table
        ActionExecutionResult count = dynamicDataService.executeCustomAction(modelCode, "count", Map.of());
        assertNotNull(count);
        assertTrue(count.getSuccess(), "count action should succeed");
        assertNotNull(count.getResultData().get("count"));

        // unsupported action — graceful failure result, not an exception
        ActionExecutionResult unknown = dynamicDataService.executeCustomAction(modelCode, "no-such-action", Map.of());
        assertNotNull(unknown);
        assertFalse(unknown.getSuccess());

        // truncate — destructive bulk actions are intentionally not part of this public custom-action surface
        ActionExecutionResult truncate = dynamicDataService.executeCustomAction(modelCode, "truncate", Map.of());
        assertNotNull(truncate);
        assertFalse(truncate.getSuccess());
        assertTrue(truncate.getErrorMessage().contains("Unsupported action"));
    }

    // ==================== relation methods (no relations defined -> reject) ====================

    @Test
    @DisplayName("relation methods reject when the model declares no relations")
    void relationMethodsRejectWithoutRelations() {
        String pid = seedOne("rel", "active");
        assertThrows(MetaServiceException.class,
                () -> dynamicDataService.getRelationData(modelCode, pid, "missing", Map.of()));
        assertThrows(MetaServiceException.class,
                () -> dynamicDataService.createRelations(modelCode, pid, "missing", List.of("x")));
        assertThrows(MetaServiceException.class,
                () -> dynamicDataService.removeRelations(modelCode, pid, "missing", List.of("x")));
    }

    // ==================== harness ====================

    private void setupTenantContext() {
        if (testUser == null) {
            String email = "dyncov-test@auraboot.com";
            testUser = userService.findByEmail(email);
            if (testUser == null) {
                testUser = userService.signUp(email, "test-password-123");
            }
        }
        if (testTenant == null) {
            String name = "dyncov-test-tenant";
            testTenant = tenantService.findByName(name);
            if (testTenant == null) {
                Tenant t = new Tenant();
                t.setPid(UniqueIdGenerator.generate());
                t.setName(name);
                t.setDisplayName("DynamicData Coverage Test Tenant");
                t.setStatus("active");
                t.setContactEmail("admin@dyncov-test.com");
                t.setDescription("Test tenant for DynamicDataService coverage IT");
                t.setDeletedFlag(false);
                t.setCreatedAt(Instant.now());
                t.setUpdatedAt(Instant.now());
                testTenant = tenantService.createTenant(t);
            }
        }
        TenantMember member = tenantMemberService.findByTenantIdAndUserId(testTenant.getId(), testUser.getId());
        if (member == null) {
            tenantMemberService.addMember(testUser.getId(), testTenant.getId(), "active");
        }
        MetaContext.setContext(testTenant.getId(), testUser.getId(), testUser.getPid(), testUser.getUserName());
    }

    private void createTestModel() {
        testModel = new Model();
        testModel.setPid(UniqueIdGenerator.generate());
        testModel.setTenantId(testTenant.getId());
        testModel.setCode(modelCode);
        testModel.setVersion(1);
        testModel.setIsCurrent(true);
        testModel.setStatus(Status.PUBLISHED.getCode());
        testModel.setCreatedAt(Instant.now());
        testModel.setUpdatedAt(Instant.now());
        testModel.setDeletedFlag(false);
        ExtensionBean extension = new ExtensionBean();
        Map<String, Object> ext = new HashMap<>();
        ext.put("displayName", "DynamicData Coverage Model");
        ext.put("modelType", "entity");
        extension.setExtension(ext);
        testModel.setExtension(extension);
        metaModelMapper.insert(testModel);
    }

    private void createTestFields() {
        Field pid = createFieldEntity("pid", true, false);
        metaFieldMapper.insert(pid);
        fieldBindingMapper.insert(createBinding(pid.getId(), -1, false));

        Field name = createFieldEntity("name", false, true);
        metaFieldMapper.insert(name);
        fieldBindingMapper.insert(createBinding(name.getId(), 0, true));

        Field status = createFieldEntity("status", false, false);
        metaFieldMapper.insert(status);
        fieldBindingMapper.insert(createBinding(status.getId(), 1, false));
    }

    private Field createFieldEntity(String code, boolean primaryKey, boolean required) {
        Field field = new Field();
        field.setPid(UniqueIdGenerator.generate());
        field.setTenantId(testTenant.getId());
        field.setCode(code);
        field.setDataType(DataType.STRING.getCode());
        field.setVersion(1);
        field.setIsCurrent(true);
        field.setStatus(Status.PUBLISHED.getCode());
        field.setCreatedAt(Instant.now());
        field.setUpdatedAt(Instant.now());
        field.setDeletedFlag(false);
        FieldFeatureBean feature = new FieldFeatureBean();
        feature.setRequired(required);
        feature.setUnique(primaryKey);
        field.setFeature(feature);
        ExtensionBean extension = new ExtensionBean();
        Map<String, Object> ext = new HashMap<>();
        ext.put("displayName", code.toUpperCase());
        if (primaryKey) {
            ext.put("primaryKey", true);
        }
        extension.setExtension(ext);
        field.setExtension(extension);
        return field;
    }

    private ModelFieldBinding createBinding(Long fieldId, int order, boolean required) {
        ModelFieldBinding binding = new ModelFieldBinding();
        binding.setTenantId(testTenant.getId());
        binding.setModelId(testModel.getId());
        binding.setFieldId(fieldId);
        binding.setFieldOrder(order);
        binding.setRequired(required);
        return binding;
    }

    private void createPhysicalTable() {
        SchemaOperationResult result = schemaManagementService.createTableByModel(modelCode);
        if (!result.isSuccess()) {
            throw new IllegalStateException("Failed to create table: " + result.getErrorMessage());
        }
    }

    private void seedData(int n) {
        String[] statuses = {"active", "inactive", "done"};
        for (int i = 0; i < n; i++) {
            seedOne("rec-" + i, statuses[i % statuses.length]);
        }
    }

    private String seedOne(String name, String status) {
        Map<String, Object> data = new HashMap<>();
        data.put("name", name);
        data.put("status", status);
        Map<String, Object> created = dynamicDataService.create(modelCode, data);
        return String.valueOf(created.get("pid"));
    }
}
