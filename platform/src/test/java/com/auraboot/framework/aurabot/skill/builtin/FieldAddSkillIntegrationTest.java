package com.auraboot.framework.aurabot.skill.builtin;

import com.auraboot.framework.aurabot.skill.SkillRequest;
import com.auraboot.framework.aurabot.skill.SkillResult;
import com.auraboot.framework.aurabot.skill.error.SkillErrorCode;
import com.auraboot.framework.aurabot.skill.error.SkillSpiException;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Integration tests for {@link FieldAddSkill#execute(SkillRequest)} (Spec
 * §5.4 / §6.2 cases 1-4).
 *
 * <p>Real PostgreSQL ({@code skills-c4-test} profile) — no mocks for DB or
 * Redis. The skill delegates to {@link com.auraboot.framework.meta.service.MetaFieldService}
 * which performs DDL inside {@code NOT_SUPPORTED} so this test class also
 * runs outside any wrapping transaction.
 *
 * <p>Each test seeds a fresh published model (with one bound seed field —
 * required because publish() emits an empty CREATE TABLE for fieldless
 * models) and tears down its own table + rows in {@link #cleanup()}.
 */
@Slf4j
@ActiveProfiles({"integration-test", "skills-c4-test"})
@DisplayName("FieldAddSkill.execute Integration Test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
public class FieldAddSkillIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private FieldAddSkill skill;

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

    @Autowired
    private ObjectMapper objectMapper;

    private String testModelCode;
    private String tableName;
    private Model model;
    private Field seedField;

    @BeforeEach
    @Override
    public void setupTenantContext() {
        super.setupTenantContext();

        // Seed a published model with one bound field — publish() rejects an
        // empty CREATE TABLE, so a fieldless model cannot be promoted to
        // PUBLISHED. Layered tests then add a second column on top.
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase();
        testModelCode = "it_fas_m_" + suffix;
        tableName = "mt_" + testModelCode;

        model = buildMetaModel(testModelCode, Status.DRAFT);
        metaModelMapper.insert(model);

        String seedFieldCode = "it_fas_seed_" + suffix;
        seedField = buildField(seedFieldCode, "string");
        metaFieldMapper.insert(seedField);
        bindingMapper.insert(buildBinding(model.getId(), seedField.getId(), 1));

        MetaModelDTO published = metaModelService.publish(model.getPid(), "C-4 T8 IT seed publish");
        assertNotNull(published, "model publish must return non-null");
        assertThat(published.getStatus()).isEqualTo("published");
        log.info("seed model published: code={}, table={}", testModelCode, tableName);
    }

    @AfterEach
    public void cleanup() {
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
    @DisplayName("Spec §6.2 #1 — dryRun returns NEEDS_CONFIRM preview without persisting any column")
    public void dryRun_returnsPreview_andDoesNotPersist() {
        String fieldCode = "it_fas_phone_" + shortHex();
        SkillRequest req = buildRequest(testModelCode, fieldCode, "string", node -> {
            node.put("displayName", "Customer Phone");
            node.put("maxLength", 64);
        });

        SkillResult result = skill.dryRun(req);

        assertThat(result.getStatus()).isEqualTo(SkillResult.Status.NEEDS_CONFIRM);
        assertThat(result.getSkillName()).isEqualTo("field:add");

        // Preview must NOT commit to a concrete pgColumnType — that's only
        // resolved at execute time by addToModel (Spec §5.3).
        assertThat(result.getPreview()).isNotNull();
        ObjectNode preview = (ObjectNode) result.getPreview();
        assertThat(preview.has("pgColumnType"))
                .as("preview must NOT include pgColumnType (only execute resolves storage type)")
                .isFalse();
        assertThat(preview.get("modelCode").asText()).isEqualTo(testModelCode);
        assertThat(preview.get("fieldCode").asText()).isEqualTo(fieldCode);
        assertThat(preview.get("dataType").asText()).isEqualTo("string");
        assertThat(preview.get("storageCode").asText()).isEqualTo(testModelCode + "_" + fieldCode);

        // Verify nothing landed in DB: getFieldDefinition either throws or
        // returns null for an absent field (impl currently throws).
        AtomicReference<FieldDefinition> def = new AtomicReference<>();
        assertThatThrownBy(() -> def.set(metaModelService.getFieldDefinition(testModelCode, fieldCode)))
                .as("getFieldDefinition must throw or return null for absent field")
                .isInstanceOf(RuntimeException.class);
        assertThat(def.get()).isNull();

        // Independent verification: column NOT on the table.
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
                Integer.class, tableName, fieldCode);
        assertThat(count).as("dryRun must not ADD COLUMN").isZero();
    }

    @Test
    @DisplayName("Spec §6.2 #2 — execute adds column + payload carries pgColumnType")
    public void execute_addsColumn_andPersistsField() {
        String fieldCode = "it_fas_email_" + shortHex();
        SkillRequest req = buildRequest(testModelCode, fieldCode, "string", node -> {
            node.put("displayName", "Customer Email");
            node.put("maxLength", 128);
        });

        SkillResult result = skill.execute(req);

        assertThat(result.getStatus()).isEqualTo(SkillResult.Status.SUCCESS);
        assertThat(result.getSkillName()).isEqualTo("field:add");

        ObjectNode payload = (ObjectNode) result.getPayload();
        assertThat(payload).as("execute payload").isNotNull();
        assertThat(payload.get("fieldPid").asText()).isNotBlank();
        assertThat(payload.get("modelCode").asText()).isEqualTo(testModelCode);
        assertThat(payload.get("fieldCode").asText()).isEqualTo(fieldCode);
        assertThat(payload.get("storageCode").asText()).isEqualTo(testModelCode + "_" + fieldCode);
        assertThat(payload.get("columnName").asText()).isEqualTo(fieldCode);
        assertThat(payload.get("tableName").asText()).isEqualTo(tableName);
        assertThat(payload.get("pgColumnType").asText())
                .as("pgColumnType must be populated post-execute (Spec §5.4)")
                .isNotBlank()
                .isEqualTo("varchar(128)");
        assertThat(payload.get("addedAt").asText()).isNotBlank();

        // Independent verification: column actually on the table.
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
                Integer.class, tableName, fieldCode);
        assertThat(count).as("information_schema must report the new column").isEqualTo(1);
    }

    @Test
    @DisplayName("Spec §6.2 #3 — unknown modelCode → SkillSpiException PARAMS_INVALID /modelCode")
    public void execute_unknownModel_throwsParamsInvalid() {
        String ghost = "it_fas_ghost_" + shortHex();
        SkillRequest req = buildRequest(ghost, "phone", "string", n -> { });

        assertThatThrownBy(() -> skill.execute(req))
                .isInstanceOf(SkillSpiException.class)
                .satisfies(t -> {
                    SkillSpiException sse = (SkillSpiException) t;
                    assertThat(sse.getErrorCode()).isEqualTo(SkillErrorCode.PARAMS_INVALID);
                    assertThat(sse.getFieldPath())
                            .as("modelCode-not-found must map to /modelCode")
                            .isEqualTo("/modelCode");
                });
    }

    @Test
    @DisplayName("Spec §6.2 #4 — duplicate code → SkillSpiException PARAMS_INVALID /code")
    public void execute_duplicateCode_throwsParamsInvalid() {
        String fieldCode = "it_fas_dup_" + shortHex();
        SkillRequest first = buildRequest(testModelCode, fieldCode, "string", n -> n.put("displayName", "First"));
        SkillResult firstRes = skill.execute(first);
        assertThat(firstRes.getStatus()).isEqualTo(SkillResult.Status.SUCCESS);

        SkillRequest second = buildRequest(testModelCode, fieldCode, "string", n -> n.put("displayName", "Second"));

        assertThatThrownBy(() -> skill.execute(second))
                .isInstanceOf(SkillSpiException.class)
                .satisfies(t -> {
                    SkillSpiException sse = (SkillSpiException) t;
                    assertThat(sse.getErrorCode()).isEqualTo(SkillErrorCode.PARAMS_INVALID);
                    assertThat(sse.getFieldPath())
                            .as("duplicate field code must map to /code")
                            .isEqualTo("/code");
                });
    }

    // ==================== helpers ====================

    private SkillRequest buildRequest(String modelCode, String code, String dataType,
                                      java.util.function.Consumer<ObjectNode> extras) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("modelCode", modelCode);
        params.put("code", code);
        params.put("dataType", dataType);
        extras.accept(params);
        return SkillRequest.builder()
                .skillName("field:add")
                .params(params)
                .build();
    }

    private static String shortHex() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase();
    }

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
        ext.put("displayName", "C-4 T8 IT model");
        ext.put("description", "transient fixture model for FieldAddSkill ITs");
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
