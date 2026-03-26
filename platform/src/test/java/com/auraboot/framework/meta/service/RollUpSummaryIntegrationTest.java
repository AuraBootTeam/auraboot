package com.auraboot.framework.meta.service;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.constant.DataType;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.dto.SchemaOperationResult;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.impl.RollUpFieldRegistry;
import com.auraboot.framework.meta.service.impl.RollUpSummaryService;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Roll-Up Summary feature.
 *
 * <p>Creates real parent + child models with physical tables, inserts data,
 * and verifies that RollUpSummaryService correctly aggregates child records
 * into the parent's roll-up field.
 *
 * <p>Covers:
 * <ul>
 *   <li>RU-01: SUM aggregation — 3 child rows</li>
 *   <li>RU-02: Recalculate after child INSERT</li>
 *   <li>RU-03: Recalculate after child DELETE (value decreases)</li>
 *   <li>RU-04: COUNT aggregation</li>
 *   <li>RU-05: AVG aggregation</li>
 *   <li>RU-06: childFilter excludes rows</li>
 *   <li>RU-07: Empty child set → ZERO</li>
 *   <li>RU-08: Batch recalculate updates all parents</li>
 *   <li>RU-09: RollUpFieldRegistry discovers fields from DB</li>
 * </ul>
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RollUpSummaryIntegrationTest {

    @Autowired private RollUpSummaryService rollUpSummaryService;
    @Autowired private RollUpFieldRegistry rollUpFieldRegistry;
    @Autowired private DynamicDataMapper dynamicDataMapper;
    @Autowired private MetaModelMapper metaModelMapper;
    @Autowired private MetaFieldMapper metaFieldMapper;
    @Autowired private MetaModelFieldBindingMapper fieldBindingMapper;
    @Autowired private SchemaManagementService schemaManagementService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private UserService userService;
    @Autowired private TenantService tenantService;
    @Autowired private TenantMemberService tenantMemberService;

    private final String runId = String.valueOf(System.currentTimeMillis());
    private String parentModelCode;
    private String childModelCode;
    private String parentTable;
    private String childTable;
    private String pf; // parent field prefix (e.g. "rp1234_")
    private String cf; // child field prefix (e.g. "rc1234_")
    private Model parentModel;
    private Model childModel;
    private User testUser;
    private Tenant testTenant;
    private Long tenantId;

    // Pids for parent records
    private String parentPid1;
    private String parentPid2;

    @BeforeAll
    void setupModels() {
        parentModelCode = "ru_parent_" + runId;
        childModelCode = "ru_child_" + runId;
        parentTable = "mt_" + parentModelCode;
        childTable = "mt_" + childModelCode;
        pf = "rp" + runId.substring(runId.length() - 4) + "_";
        cf = "rc" + runId.substring(runId.length() - 4) + "_";

        setupTenantContext();
        tenantId = testTenant.getId();

        cleanupPreviousRun();

        // Create parent model with a rollUp field
        // Field codes use the model prefix to avoid uniqueness conflicts across test runs
        pf = parentModelCode + "_";
        cf = childModelCode + "_";
        parentModel = createModel(parentModelCode, "RollUp Test Parent");
        createField(parentModel, pf + "name", DataType.STRING.getCode(), false, true, null, 0);
        createDecimalField(parentModel, pf + "total_amount", buildRollUpConfig("sum", null, cf), 1);
        createDecimalField(parentModel, pf + "line_count", buildRollUpConfig("count", null, cf), 2);
        createPhysicalTable(parentModelCode);

        // Create child model with FK to parent + amount field
        childModel = createModel(childModelCode, "RollUp Test Child");
        createField(childModel, cf + "parent_id", DataType.STRING.getCode(), false, true, null, 0);
        createDecimalFieldPlain(childModel, cf + "amount", 1);
        createField(childModel, cf + "status", DataType.STRING.getCode(), false, false, null, 2);
        createPhysicalTable(childModelCode);

        // Invalidate registry so it picks up the new fields
        rollUpFieldRegistry.invalidate();

        log.info("Test models created: parent={}, child={}", parentModelCode, childModelCode);
    }

    @AfterAll
    void cleanup() {
        try {
            setupTenantContext();
            jdbcTemplate.execute("DROP TABLE IF EXISTS " + childTable);
            jdbcTemplate.execute("DROP TABLE IF EXISTS " + parentTable);
            // Cleanup meta rows
            cleanupMetaForModel(parentModelCode);
            cleanupMetaForModel(childModelCode);
            log.info("Test tables and meta cleaned up");
        } catch (Exception e) {
            log.warn("Cleanup failed (non-critical): {}", e.getMessage());
        }
    }

    @BeforeEach
    void ensureContext() {
        setupTenantContext();
    }

    // ── RU-01: SUM aggregation ────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("RU-01: SUM aggregation — 3 child rows sum to parent field")
    void sumAggregation() {
        parentPid1 = UniqueIdGenerator.generate();
        insertParent(parentPid1, "Order-A", BigDecimal.ZERO, BigDecimal.ZERO);

        insertChild(UniqueIdGenerator.generate(), parentPid1, new BigDecimal("100.50"), "active");
        insertChild(UniqueIdGenerator.generate(), parentPid1, new BigDecimal("200.25"), "active");
        insertChild(UniqueIdGenerator.generate(), parentPid1, new BigDecimal("50.00"), "active");

        rollUpSummaryService.recalculate(
                parentModelCode, pf + "total_amount", parentPid1,
                childModelCode, cf + "amount", cf + "parent_id",
                "sum", null, tenantId);

        BigDecimal total = readParentDecimalField(parentPid1, pf + "total_amount");
        assertThat(total).isEqualByComparingTo(new BigDecimal("350.75"));
        log.info("RU-01 PASSED: SUM = {}", total);
    }

    // ── RU-02: Recalculate after child INSERT ─────────────────────────────────

    @Test
    @Order(2)
    @DisplayName("RU-02: Recalculate after adding a new child row")
    void recalculateAfterInsert() {
        insertChild(UniqueIdGenerator.generate(), parentPid1, new BigDecimal("49.25"), "active");

        rollUpSummaryService.recalculate(
                parentModelCode, pf + "total_amount", parentPid1,
                childModelCode, cf + "amount", cf + "parent_id",
                "sum", null, tenantId);

        BigDecimal total = readParentDecimalField(parentPid1, pf + "total_amount");
        assertThat(total).isEqualByComparingTo(new BigDecimal("400.00"));
        log.info("RU-02 PASSED: SUM after insert = {}", total);
    }

    // ── RU-03: Recalculate after child DELETE ─────────────────────────────────

    @Test
    @Order(3)
    @DisplayName("RU-03: Recalculate after deleting a child row — value decreases")
    void recalculateAfterDelete() {
        String childToDelete = UniqueIdGenerator.generate();
        insertChild(childToDelete, parentPid1, new BigDecimal("100.00"), "to_delete");

        rollUpSummaryService.recalculate(
                parentModelCode, pf + "total_amount", parentPid1,
                childModelCode, cf + "amount", cf + "parent_id",
                "sum", null, tenantId);

        BigDecimal before = readParentDecimalField(parentPid1, pf + "total_amount");
        assertThat(before).isEqualByComparingTo(new BigDecimal("500.00"));

        dynamicDataMapper.delete(childTable, Map.of("pid", childToDelete, "tenant_id", tenantId));

        rollUpSummaryService.recalculate(
                parentModelCode, pf + "total_amount", parentPid1,
                childModelCode, cf + "amount", cf + "parent_id",
                "sum", null, tenantId);

        BigDecimal after = readParentDecimalField(parentPid1, pf + "total_amount");
        assertThat(after).isEqualByComparingTo(new BigDecimal("400.00"));
        log.info("RU-03 PASSED: SUM after delete = {} (was {})", after, before);
    }

    // ── RU-04: COUNT aggregation ──────────────────────────────────────────────

    @Test
    @Order(4)
    @DisplayName("RU-04: COUNT aggregation — counts child rows")
    void countAggregation() {
        rollUpSummaryService.recalculate(
                parentModelCode, pf + "line_count", parentPid1,
                childModelCode, cf + "amount", cf + "parent_id",
                "count", null, tenantId);

        BigDecimal count = readParentDecimalField(parentPid1, pf + "line_count");
        assertThat(count).isEqualByComparingTo(new BigDecimal("4")); // 3 original + 1 from RU-02
        log.info("RU-04 PASSED: COUNT = {}", count);
    }

    // ── RU-05: AVG aggregation ────────────────────────────────────────────────

    @Test
    @Order(5)
    @DisplayName("RU-05: AVG aggregation — average of child amounts")
    void avgAggregation() {
        rollUpSummaryService.recalculate(
                parentModelCode, pf + "total_amount", parentPid1,
                childModelCode, cf + "amount", cf + "parent_id",
                "avg", null, tenantId);

        BigDecimal avg = readParentDecimalField(parentPid1, pf + "total_amount");
        // 4 children: 100.50 + 200.25 + 50.00 + 49.25 = 400.00 / 4 = 100.0000
        assertThat(avg).isEqualByComparingTo(new BigDecimal("100.0000"));
        log.info("RU-05 PASSED: AVG = {}", avg);
    }

    // ── RU-06: childFilter excludes rows ──────────────────────────────────────

    @Test
    @Order(6)
    @DisplayName("RU-06: childFilter excludes CANCELLED rows from SUM")
    void childFilterExcludes() {
        insertChild(UniqueIdGenerator.generate(), parentPid1, new BigDecimal("999.99"), "cancelled");

        rollUpSummaryService.recalculate(
                parentModelCode, pf + "total_amount", parentPid1,
                childModelCode, cf + "amount", cf + "parent_id",
                "sum", cf + "status != 'cancelled'", tenantId);

        BigDecimal total = readParentDecimalField(parentPid1, pf + "total_amount");
        assertThat(total).isEqualByComparingTo(new BigDecimal("400.00"));
        log.info("RU-06 PASSED: SUM with filter = {} (CANCELLED excluded)", total);
    }

    // ── RU-07: Empty child set → ZERO ─────────────────────────────────────────

    @Test
    @Order(7)
    @DisplayName("RU-07: Empty child set results in ZERO")
    void emptyChildSetZero() {
        parentPid2 = UniqueIdGenerator.generate();
        insertParent(parentPid2, "Order-B-Empty", new BigDecimal("999"), BigDecimal.ZERO);

        rollUpSummaryService.recalculate(
                parentModelCode, pf + "total_amount", parentPid2,
                childModelCode, cf + "amount", cf + "parent_id",
                "sum", null, tenantId);

        BigDecimal total = readParentDecimalField(parentPid2, pf + "total_amount");
        assertThat(total).isEqualByComparingTo(BigDecimal.ZERO);
        log.info("RU-07 PASSED: Empty children → {}", total);
    }

    // ── RU-08: Batch recalculate ──────────────────────────────────────────────

    @Test
    @Order(8)
    @DisplayName("RU-08: Batch recalculate updates all parent records")
    void batchRecalculate() {
        dynamicDataMapper.update(parentTable,
                Map.of(pf + "total_amount", new BigDecimal("999")),
                Map.of("pid", parentPid2, "tenant_id", tenantId));

        int updated = rollUpSummaryService.batchRecalculate(
                parentModelCode, pf + "total_amount",
                childModelCode, cf + "amount", cf + "parent_id",
                "sum", null, tenantId);

        assertThat(updated).isGreaterThanOrEqualTo(2);

        BigDecimal total2 = readParentDecimalField(parentPid2, pf + "total_amount");
        assertThat(total2).isEqualByComparingTo(BigDecimal.ZERO);
        log.info("RU-08 PASSED: Batch updated {} parents", updated);
    }

    // ── RU-09: RollUpFieldRegistry discovers fields ───────────────────────────

    @Test
    @Order(9)
    @DisplayName("RU-09: RollUpFieldRegistry discovers rollUp fields from metadata")
    void registryDiscovery() {
        rollUpFieldRegistry.invalidate();

        List<RollUpFieldRegistry.RollUpTarget> targets = rollUpFieldRegistry.getTargets(childModelCode);

        // Should find at least 2 targets (total_amount + line_count)
        assertThat(targets).hasSizeGreaterThanOrEqualTo(2);
        assertThat(targets).extracting(RollUpFieldRegistry.RollUpTarget::getParentModelCode)
                .allMatch(code -> code.equals(parentModelCode));
        assertThat(targets).extracting(RollUpFieldRegistry.RollUpTarget::getFunction)
                .contains("sum", "count");
        log.info("RU-09 PASSED: Registry found {} targets for child model '{}'", targets.size(), childModelCode);
    }

    // ══════════════════════════ Helper Methods ═══════════════════════════════

    private void setupTenantContext() {
        if (testUser == null) {
            String email = "rollup-test@auraboot.com";
            testUser = userService.findByEmail(email);
            if (testUser == null) {
                testUser = userService.signUp(email, "test-password-123");
            }
        }
        if (testTenant == null) {
            String tenantName = "rollup-test-tenant";
            testTenant = tenantService.findByName(tenantName);
            if (testTenant == null) {
                Tenant t = new Tenant();
                t.setPid(UniqueIdGenerator.generate());
                t.setName(tenantName);
                t.setDisplayName("RollUp Test Tenant");
                t.setStatus("active");
                t.setContactEmail("admin@rollup-test.com");
                t.setDeletedFlag(false);
                t.setCreatedAt(Instant.now());
                t.setUpdatedAt(Instant.now());
                testTenant = tenantService.createTenant(t);
            }
            TenantMember member = tenantMemberService.findByTenantIdAndUserId(testTenant.getId(), testUser.getId());
            if (member == null) {
                tenantMemberService.addMember(testUser.getId(), testTenant.getId(), "active");
            }
        }
        MetaContext.setContext(testTenant.getId(), testUser.getId(), testUser.getPid(), testUser.getUserName());
    }

    private Model createModel(String code, String displayName) {
        Model model = new Model();
        model.setPid(UniqueIdGenerator.generate());
        model.setTenantId(testTenant.getId());
        model.setCode(code);
        model.setVersion(1);
        model.setIsCurrent(true);
        model.setStatus(Status.PUBLISHED.getCode());
        model.setCreatedAt(Instant.now());
        model.setUpdatedAt(Instant.now());
        model.setDeletedFlag(false);

        ExtensionBean ext = new ExtensionBean();
        Map<String, Object> map = new HashMap<>();
        map.put("displayName", displayName);
        map.put("modelType", "entity");
        ext.setExtension(map);
        model.setExtension(ext);

        metaModelMapper.insert(model);
        return model;
    }

    private void createField(Model model, String code, String dataType,
                              boolean primaryKey, boolean required,
                              FieldFeatureBean.RollUpConfig rollUp, int order) {
        Field field = new Field();
        field.setPid(UniqueIdGenerator.generate());
        field.setTenantId(testTenant.getId());
        field.setCode(code);
        field.setDataType(dataType);
        field.setVersion(1);
        field.setIsCurrent(true);
        field.setStatus(Status.PUBLISHED.getCode());
        field.setCreatedAt(Instant.now());
        field.setUpdatedAt(Instant.now());
        field.setDeletedFlag(false);

        FieldFeatureBean feature = new FieldFeatureBean();
        feature.setRequired(required);
        feature.setUnique(primaryKey);
        if (rollUp != null) {
            feature.setRollUp(rollUp);
            feature.setReadonly(true);
        }
        field.setFeature(feature);

        ExtensionBean ext = new ExtensionBean();
        Map<String, Object> map = new HashMap<>();
        map.put("displayName", code);
        if (primaryKey) map.put("primaryKey", true);
        ext.setExtension(map);
        field.setExtension(ext);

        metaFieldMapper.insert(field);
        ModelFieldBinding binding = new ModelFieldBinding();
        binding.setTenantId(testTenant.getId());
        binding.setModelId(model.getId());
        binding.setFieldId(field.getId());
        binding.setFieldOrder(order);
        fieldBindingMapper.insert(binding);
    }

    private void createDecimalField(Model model, String code, FieldFeatureBean.RollUpConfig rollUp, int order) {
        createField(model, code, DataType.DECIMAL.getCode(), false, false, rollUp, order);
    }

    private void createDecimalFieldPlain(Model model, String code, int order) {
        createField(model, code, DataType.DECIMAL.getCode(), false, false, null, order);
    }

    private FieldFeatureBean.RollUpConfig buildRollUpConfig(String function, String filter, String childFieldPrefix) {
        FieldFeatureBean.RollUpConfig config = new FieldFeatureBean.RollUpConfig();
        config.setChildModel(childModelCode);
        config.setChildField(childFieldPrefix + "amount");
        config.setChildFk(childFieldPrefix + "parent_id");
        config.setFunction(function);
        config.setChildFilter(filter);
        return config;
    }

    private void createPhysicalTable(String modelCode) {
        SchemaOperationResult result = schemaManagementService.createTableByModel(modelCode);
        if (!result.isSuccess()) {
            throw new RuntimeException("Failed to create table for " + modelCode + ": " + result.getErrorMessage());
        }
        log.info("Physical table created: {}", result.getTableName());
    }

    private void insertParent(String pid, String name, BigDecimal totalAmount, BigDecimal lineCount) {
        Map<String, Object> data = new HashMap<>();
        data.put("pid", pid);  // system column
        data.put(pf + "name", name);
        data.put(pf + "total_amount", totalAmount);
        data.put(pf + "line_count", lineCount);
        data.put("tenant_id", tenantId);
        data.put("created_at", Instant.now());
        data.put("created_by", testUser.getId());
        data.put("updated_at", Instant.now());
        data.put("updated_by", testUser.getId());
        dynamicDataMapper.insert(parentTable, data);
    }

    private void insertChild(String pid, String parentId, BigDecimal amount, String status) {
        Map<String, Object> data = new HashMap<>();
        data.put("pid", pid);  // system column
        data.put(cf + "parent_id", parentId);
        data.put(cf + "amount", amount);
        data.put(cf + "status", status);
        data.put("tenant_id", tenantId);
        data.put("created_at", Instant.now());
        data.put("created_by", testUser.getId());
        data.put("updated_at", Instant.now());
        data.put("updated_by", testUser.getId());
        dynamicDataMapper.insert(childTable, data);
    }

    private BigDecimal readParentDecimalField(String pid, String colName) {
        String sql = "SELECT " + colName + " FROM " + parentTable
                + " WHERE pid = #{params.pid} AND tenant_id = #{params.tenantId}";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(
                sql, Map.of("pid", pid, "tenantId", tenantId));
        assertThat(rows).isNotEmpty();
        Object val = rows.get(0).get(colName);
        if (val instanceof BigDecimal bd) return bd;
        if (val instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        if (val != null) return new BigDecimal(val.toString());
        return BigDecimal.ZERO;
    }

    private void cleanupPreviousRun() {
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS " + childTable);
            jdbcTemplate.execute("DROP TABLE IF EXISTS " + parentTable);
            cleanupMetaForModel(parentModelCode);
            cleanupMetaForModel(childModelCode);
        } catch (Exception e) {
            log.debug("No previous run to clean up");
        }
    }

    private void cleanupMetaForModel(String modelCode) {
        try {
            jdbcTemplate.update(
                    "DELETE FROM ab_meta_model_field_binding WHERE model_id IN " +
                            "(SELECT id FROM ab_meta_model WHERE code = ? AND tenant_id = ?)",
                    modelCode, tenantId);
            jdbcTemplate.update(
                    "DELETE FROM ab_meta_model WHERE code = ? AND tenant_id = ?",
                    modelCode, tenantId);
        } catch (Exception ignored) {
        }
    }
}
