package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.dto.AddFieldRequest;
import com.auraboot.framework.meta.dto.AddFieldResult;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.dto.RemoveFieldRequest;
import com.auraboot.framework.meta.exception.ColumnHasDataException;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.MetaFieldService;
import com.auraboot.framework.meta.service.MetaModelService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Integration tests for {@link MetaFieldService#addToModel(AddFieldRequest)} —
 * the Layer-1 SoT for adding a single field to an existing published model.
 *
 * <p>Spec coverage:
 * <ul>
 *   <li>§3.1 — happy path adds field + DDL ALTER TABLE landed</li>
 *   <li>§3.2 — modelCode unknown rejects via {@link ValidationException}</li>
 *   <li>§3.3 — duplicate code on same model rejects</li>
 *   <li>§3.4 — invalid dataType rejected (whitelist of 9 abstract types)</li>
 * </ul>
 *
 * <p>Real PostgreSQL ({@code skills-c4-test} profile @ port 35442) — no mocks
 * for DB or Redis. {@code NOT_SUPPORTED} propagation because DDL cannot roll
 * back inside a Spring transaction in PostgreSQL.
 */
@Slf4j
@ActiveProfiles({"integration-test", "skills-c4-test"})
@DisplayName("MetaFieldService.addToModel Integration Test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
public class MetaFieldServiceAddToModelIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MetaFieldService metaFieldService;

    @Autowired
    private MetaModelService metaModelService;

    @Autowired
    private MetaModelMapper metaModelMapper;

    @Autowired
    private MetaFieldMapper metaFieldMapper;

    @Autowired
    private MetaModelFieldBindingMapper bindingMapper;

    @Autowired
    private DynamicDataMapper dynamicDataMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String testModelCode;
    private String tableName;
    private Model model;
    private Field seedField;

    @BeforeEach
    @Override
    public void setupTenantContext() {
        super.setupTenantContext();

        // C-3 F-1 workaround: a published model needs >= 1 bound field, else
        // publish() emits an empty CREATE TABLE that the platform rejects.
        // Seed a "name" field, bind it, publish — table now exists with the
        // seed column; addToModel under test layers a second column on top.
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase();
        testModelCode = "it_c4_m_" + suffix;
        tableName = "mt_" + testModelCode;

        model = buildMetaModel(testModelCode, Status.DRAFT);
        metaModelMapper.insert(model);

        String seedFieldCode = "it_c4_seed_" + suffix;
        seedField = buildField(seedFieldCode, "string");
        metaFieldMapper.insert(seedField);
        bindingMapper.insert(buildBinding(model.getId(), seedField.getId(), 1));

        MetaModelDTO published = metaModelService.publish(model.getPid(), "C-4 IT seed publish");
        assertNotNull(published, "model publish must return non-null");
        assertThat(published.getStatus()).isEqualTo("published");
        log.info("seed model published: code={}, table={}", testModelCode, tableName);
    }

    @AfterEach
    public void cleanup() {
        // Drop table first (DDL), then bindings/fields/model rows.
        try {
            dynamicDataMapper.alterTable("DROP TABLE IF EXISTS " + tableName);
        } catch (Exception e) {
            log.debug("drop {} skipped: {}", tableName, e.getMessage());
        }
        if (model != null) {
            try {
                bindingMapper.deleteByModelId(model.getId());
            } catch (Exception e) {
                log.debug("binding delete skipped: {}", e.getMessage());
            }
        }
        if (seedField != null) {
            try {
                metaFieldMapper.deleteById(seedField.getId());
            } catch (Exception e) {
                log.debug("seed field delete skipped: {}", e.getMessage());
            }
        }
        if (model != null) {
            try {
                metaModelMapper.deleteById(model.getId());
            } catch (Exception e) {
                log.debug("model delete skipped: {}", e.getMessage());
            }
        }
    }

    @Test
    @DisplayName("Spec §3.1 — addToModel adds field + binding + DDL ADD COLUMN landed")
    public void testAddToModel_happyPath_columnAddedToInformationSchema() {
        String fieldCode = "it_c4_phone_" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase();

        AddFieldRequest req = AddFieldRequest.builder()
                .modelCode(testModelCode)
                .code(fieldCode)
                .dataType("string")
                .displayName("Customer Phone")
                .maxLength(64)
                .comment("contact phone for the customer")
                .build();

        AddFieldResult result = metaFieldService.addToModel(req);

        assertThat(result).as("addToModel must return non-null result").isNotNull();
        assertThat(result.getFieldPid()).as("fieldPid").isNotBlank();
        assertThat(result.getStorageCode())
                .as("spec §3.1 storageCode = '<modelCode>_<code>'")
                .isEqualTo(testModelCode + "_" + fieldCode);
        assertThat(result.getTableName()).as("tableName").isEqualTo(tableName);
        assertThat(result.getColumnName()).as("columnName").isEqualTo(fieldCode);
        assertThat(result.getPgColumnType())
                .as("pgColumnType for string(64) must read varchar(64) from information_schema")
                .isEqualTo("varchar(64)");
        assertThat(result.getAddedAt()).as("addedAt").isNotNull();
        assertThat(result.getAddedAt()).isBefore(Instant.now().plusSeconds(5));

        // Independent verification: the column actually exists on the table.
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
                Integer.class, tableName, fieldCode);
        assertThat(count).as("information_schema must report exactly 1 matching column").isEqualTo(1);
    }

    @Test
    @DisplayName("Spec §3.2 — modelCode unknown rejects with ValidationException")
    public void testAddToModel_unknownModel_rejects() {
        AddFieldRequest req = AddFieldRequest.builder()
                .modelCode("it_c4_does_not_exist_" + UUID.randomUUID().toString().substring(0, 8))
                .code("it_c4_age")
                .dataType("int")
                .build();

        assertThatThrownBy(() -> metaFieldService.addToModel(req))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("modelCode not found");
    }

    @Test
    @DisplayName("Spec §3.3 — duplicate field code on same model rejects")
    public void testAddToModel_duplicateCode_rejects() {
        String fieldCode = "it_c4_dup_" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase();

        AddFieldRequest first = AddFieldRequest.builder()
                .modelCode(testModelCode)
                .code(fieldCode)
                .dataType("string")
                .displayName("First Add")
                .build();

        AddFieldResult firstResult = metaFieldService.addToModel(first);
        assertThat(firstResult.getFieldPid()).isNotBlank();

        AddFieldRequest second = AddFieldRequest.builder()
                .modelCode(testModelCode)
                .code(fieldCode)
                .dataType("string")
                .displayName("Second Add — should fail")
                .build();

        assertThatThrownBy(() -> metaFieldService.addToModel(second))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("already");
    }

    @Test
    @DisplayName("Spec §3.4 — dataType outside whitelist rejects")
    public void testAddToModel_invalidDataType_rejects() {
        AddFieldRequest req = AddFieldRequest.builder()
                .modelCode(testModelCode)
                .code("it_c4_bogus")
                .dataType("UnsupportedXYZType") // not in the 9-type whitelist
                .build();

        assertThatThrownBy(() -> metaFieldService.addToModel(req))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("dataType not in whitelist");
    }

    // ==================== removeFromModel ====================

    @Test
    @DisplayName("Spec §3.6 — removeFromModel on empty column drops cleanly")
    public void removeFromModel_emptyColumn_dropsCleanly() {
        String fieldCode = "it_c4_rm_e_" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase();

        AddFieldRequest addReq = AddFieldRequest.builder()
                .modelCode(testModelCode)
                .code(fieldCode)
                .dataType("string")
                .displayName("To Remove")
                .maxLength(32)
                .build();
        AddFieldResult addRes = metaFieldService.addToModel(addReq);
        assertThat(addRes.getColumnName()).isEqualTo(fieldCode);

        // Sanity: column landed
        Integer before = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
                Integer.class, tableName, fieldCode);
        assertThat(before).as("column must exist after addToModel").isEqualTo(1);

        RemoveFieldRequest rmReq = RemoveFieldRequest.builder()
                .modelCode(testModelCode)
                .storageCode(addRes.getStorageCode())
                .refuseIfDataExists(true)
                .build();
        metaFieldService.removeFromModel(rmReq);

        // Verify: column gone from information_schema
        Integer after = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
                Integer.class, tableName, fieldCode);
        assertThat(after).as("column must be removed by DROP COLUMN").isZero();

        // Verify: ab_meta_field row hard-deleted (so same code can be re-added)
        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_meta_field WHERE code = ?",
                Integer.class, fieldCode);
        assertThat(rowCount).as("ab_meta_field row hard-deleted").isZero();
    }

    @Test
    @DisplayName("Spec §3.7 #6 — removeFromModel rejects when column has data and refuseIfDataExists=true")
    public void removeFromModel_dataPresent_throwsColumnHasDataException() {
        String fieldCode = "it_c4_rm_d_" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase();

        AddFieldRequest addReq = AddFieldRequest.builder()
                .modelCode(testModelCode)
                .code(fieldCode)
                .dataType("string")
                .displayName("Has Data")
                .maxLength(32)
                .build();
        AddFieldResult addRes = metaFieldService.addToModel(addReq);

        // Insert one real data row carrying a non-null value in the new column.
        // We use minimal columns; the table has standard mt_* columns including pid + tenant_id.
        // Use information_schema to discover required columns? Simpler: rely on default-having.
        // Post-T3: just UPDATE — but the table is empty. Insert a synthetic row.
        Long tenantId = getTestTenant().getId();
        String pid = UniqueIdGenerator.generate();
        // Dynamic mt_* tables don't carry deleted_flag (no soft-delete) — see AGENTS.md.
        jdbcTemplate.update(
                "INSERT INTO " + tableName + " (pid, tenant_id, " + fieldCode + ") VALUES (?, ?, ?)",
                pid, tenantId, "non-null-value");

        RemoveFieldRequest rmReq = RemoveFieldRequest.builder()
                .modelCode(testModelCode)
                .storageCode(addRes.getStorageCode())
                .refuseIfDataExists(true)
                .build();

        try {
            assertThatThrownBy(() -> metaFieldService.removeFromModel(rmReq))
                    .isInstanceOf(ColumnHasDataException.class)
                    .hasMessageContaining("non-null rows");

            // Column must still exist; service must NOT have dropped it.
            Integer stillThere = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns "
                            + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
                    Integer.class, tableName, fieldCode);
            assertThat(stillThere).as("column must NOT be dropped when refused").isEqualTo(1);
        } finally {
            // Clean the data row so @AfterEach DROP TABLE does not leak; also
            // allow the column to be dropped by the cascading table teardown.
            try {
                jdbcTemplate.update("DELETE FROM " + tableName + " WHERE pid = ?", pid);
            } catch (Exception ignore) {
                // best effort
            }
        }
    }

    @Test
    @DisplayName("Spec §3.7 #7 — F-5 regression: removeFromModel allows same code to be re-added")
    public void removeFromModel_thenAddSameCode_succeeds() {
        String fieldCode = "it_c4_rm_r_" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase();

        // Add
        AddFieldRequest addReq1 = AddFieldRequest.builder()
                .modelCode(testModelCode)
                .code(fieldCode)
                .dataType("string")
                .displayName("Cycle 1")
                .maxLength(32)
                .build();
        AddFieldResult first = metaFieldService.addToModel(addReq1);
        assertThat(first.getFieldPid()).isNotBlank();

        // Remove
        metaFieldService.removeFromModel(RemoveFieldRequest.builder()
                .modelCode(testModelCode)
                .storageCode(first.getStorageCode())
                .refuseIfDataExists(true)
                .build());

        // Re-add same code → must NOT throw "already exists"
        AddFieldRequest addReq2 = AddFieldRequest.builder()
                .modelCode(testModelCode)
                .code(fieldCode)
                .dataType("string")
                .displayName("Cycle 2")
                .maxLength(64)
                .build();
        AddFieldResult second = metaFieldService.addToModel(addReq2);

        assertThat(second.getFieldPid())
                .as("second add must produce a new fieldPid")
                .isNotBlank()
                .isNotEqualTo(first.getFieldPid());
        assertThat(second.getStorageCode()).isEqualTo(first.getStorageCode());
        assertThat(second.getColumnName()).isEqualTo(fieldCode);
        assertThat(second.getPgColumnType())
                .as("re-added column should respect new maxLength=64")
                .isEqualTo("varchar(64)");

        // Verify column is on the table again (with new shape).
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
                Integer.class, tableName, fieldCode);
        assertThat(count).isEqualTo(1);
    }

    // ==================== fixtures ====================

    private Model buildMetaModel(String code, Status status) {
        Model m = new Model();
        m.setPid(UniqueIdGenerator.generate());
        m.setTenantId(getTestTenant().getId());
        m.setCode(code);
        m.setVersion(1);
        m.setIsCurrent(true);
        m.setStatus(status.getCode());
        m.setCreatedAt(Instant.now());
        m.setUpdatedAt(Instant.now());
        m.setDeletedFlag(false);

        ExtensionBean extension = new ExtensionBean();
        Map<String, Object> ext = new HashMap<>();
        ext.put("displayName", "C-4 addToModel IT model");
        ext.put("description", "transient fixture model for MetaFieldService addToModel ITs");
        ext.put("modelType", "entity");
        extension.setExtension(ext);
        m.setExtension(extension);

        return m;
    }

    private Field buildField(String code, String dataType) {
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

        ExtensionBean extension = new ExtensionBean();
        Map<String, Object> ext = new HashMap<>();
        ext.put("displayName", code);
        ext.put("description", code + " seed field");
        extension.setExtension(ext);
        f.setExtension(extension);

        return f;
    }

    private ModelFieldBinding buildBinding(Long modelId, Long fieldId, int order) {
        ModelFieldBinding b = new ModelFieldBinding();
        b.setTenantId(getTestTenant().getId());
        b.setModelId(modelId);
        b.setFieldId(fieldId);
        b.setFieldOrder(order);
        return b;
    }
}
