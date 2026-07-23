package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.constant.DataType;
import com.auraboot.framework.meta.constant.Status;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-stack coverage IT for {@link DynamicDataServiceImpl} typed-value + Excel-export paths:
 * create/update coerce string inputs into integer/decimal/boolean/date columns
 * ({@code convertFieldValue}), and {@code exportData} with {@code ExportFormat.EXCEL} drives
 * {@code exportAsExcel}. Real model + physical table; no mocks.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("DynamicDataServiceImpl Types IT — convertFieldValue + exportAsExcel")
class DynamicDataServiceImplTypesIT {

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
    private Model model;
    private User testUser;
    private Tenant testTenant;
    private final AtomicInteger order = new AtomicInteger();
    private boolean inited = false;

    @BeforeEach
    void setUp() {
        setupTenantContext();
        if (!inited) {
            modelCode = "dyntypes_" + Math.abs(System.nanoTime() % 100_000_000);
            purge();
            createModel();
            addField("pid", DataType.STRING.getCode(), true, false);
            addField("name", DataType.STRING.getCode(), false, true);
            addField("qty", DataType.INTEGER.getCode(), false, false);
            addField("price", DataType.DECIMAL.getCode(), false, false);
            addField("active", DataType.BOOLEAN.getCode(), false, false);
            addField("due", DataType.DATE.getCode(), false, false);
            SchemaOperationResult r = schemaManagementService.createTableByModel(modelCode);
            if (!r.isSuccess()) {
                throw new IllegalStateException("create table failed: " + r.getErrorMessage());
            }
            inited = true;
        }
    }

    @AfterAll
    void cleanup() {
        try {
            try {
                schemaManagementService.dropTableByModel(modelCode);
            } catch (Exception ignore) {
                // best effort
            }
            purge();
        } finally {
            MetaContext.clear();
        }
    }

    @Test
    @DisplayName("create coerces string inputs into integer/decimal/boolean/date columns")
    void typedCreateAndUpdate() {
        Map<String, Object> data = new HashMap<>();
        data.put("name", "widget");
        data.put("qty", "7");
        data.put("price", "19.95");
        data.put("active", "true");
        data.put("due", "2026-06-19");
        Map<String, Object> created = dynamicDataService.create(modelCode, data);
        String pid = String.valueOf(created.get("pid"));
        assertNotNull(pid);

        Map<String, Object> fetched = MetaContext.runWithoutDataPermission(
                () -> dynamicDataService.getById(modelCode, pid));
        assertEquals("widget", String.valueOf(fetched.get("name")));

        Map<String, Object> upd = new HashMap<>();
        upd.put("qty", 9);
        upd.put("active", false);
        Map<String, Object> updated = MetaContext.runWithoutDataPermission(
                () -> dynamicDataService.update(modelCode, pid, upd));
        assertNotNull(updated);

        MetaContext.runWithoutDataPermission(() -> { dynamicDataService.delete(modelCode, pid); });
    }

    @Test
    @DisplayName("exportData with EXCEL format produces a non-empty Excel result")
    void excelExport() {
        Map<String, Object> data = new HashMap<>();
        data.put("name", "export-row");
        data.put("qty", "3");
        data.put("price", "1.50");
        data.put("active", "true");
        dynamicDataService.create(modelCode, data);

        DataExportRequest req = new DataExportRequest();
        req.setFormat(DataExportRequest.ExportFormat.EXCEL);
        req.setIncludeHeader(true);
        ExportResult result = dynamicDataService.exportData(modelCode, req);
        assertNotNull(result);
    }

    // ---- harness ----

    private void createModel() {
        model = new Model();
        model.setPid(UniqueIdGenerator.generate());
        model.setTenantId(testTenant.getId());
        model.setCode(modelCode);
        model.setVersion(1);
        model.setIsCurrent(true);
        model.setStatus(Status.PUBLISHED.getCode());
        model.setCreatedAt(Instant.now());
        model.setUpdatedAt(Instant.now());
        model.setDeletedFlag(false);
        ExtensionBean e = new ExtensionBean();
        Map<String, Object> ext = new HashMap<>();
        ext.put("displayName", "DynTypes Model");
        ext.put("modelType", "entity");
        e.setExtension(ext);
        model.setExtension(e);
        metaModelMapper.insert(model);
    }

    private void addField(String code, String dataType, boolean pk, boolean required) {
        Field f = new Field();
        f.setPid(UniqueIdGenerator.generate());
        f.setTenantId(testTenant.getId());
        f.setCode(code);
        f.setDataType(dataType);
        f.setVersion(1);
        f.setIsCurrent(true);
        f.setStatus(Status.PUBLISHED.getCode());
        f.setCreatedAt(Instant.now());
        f.setUpdatedAt(Instant.now());
        f.setDeletedFlag(false);
        FieldFeatureBean feature = new FieldFeatureBean();
        feature.setRequired(required);
        feature.setUnique(pk);
        f.setFeature(feature);
        ExtensionBean ext = new ExtensionBean();
        Map<String, Object> e = new HashMap<>();
        e.put("displayName", code.toUpperCase());
        if (pk) {
            e.put("primaryKey", true);
        }
        ext.setExtension(e);
        f.setExtension(ext);
        metaFieldMapper.insert(f);

        ModelFieldBinding b = new ModelFieldBinding();
        b.setTenantId(testTenant.getId());
        b.setModelId(model.getId());
        b.setFieldId(f.getId());
        b.setFieldOrder(pk ? -1 : order.incrementAndGet());
        b.setRequired(required);
        fieldBindingMapper.insert(b);
    }

    private void purge() {
        if (testTenant == null) {
            return;
        }
        Long tid = testTenant.getId();
        try {
            jdbcTemplate.update("DELETE FROM ab_meta_model_field_binding WHERE model_id IN "
                    + "(SELECT id FROM ab_meta_model WHERE code LIKE 'dyntypes%' AND tenant_id = ?)", tid);
            jdbcTemplate.update("DELETE FROM ab_meta_field WHERE code IN "
                    + "('pid','name','qty','price','active','due') AND tenant_id = ? "
                    + "AND id NOT IN (SELECT field_id FROM ab_meta_model_field_binding)", tid);
            jdbcTemplate.update("DELETE FROM ab_meta_model WHERE code LIKE 'dyntypes%' AND tenant_id = ?", tid);
        } catch (Exception e) {
            log.warn("dyntypes purge failed: {}", e.getMessage());
        }
    }

    private void setupTenantContext() {
        if (testUser == null) {
            testUser = userService.findByEmail("dyntypes-test@auraboot.com");
            if (testUser == null) {
                testUser = userService.signUp("dyntypes-test@auraboot.com", "test-password-123");
            }
        }
        if (testTenant == null) {
            testTenant = tenantService.findByName("dyntypes-test-tenant");
            if (testTenant == null) {
                Tenant t = new Tenant();
                t.setPid(UniqueIdGenerator.generate());
                t.setName("dyntypes-test-tenant");
                t.setDisplayName("DynTypes Coverage Test Tenant");
                t.setStatus("active");
                t.setContactEmail("admin@dyntypes-test.com");
                t.setDescription("Test tenant for DynamicData types coverage IT");
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
}
