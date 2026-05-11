package com.auraboot.framework.aurabot.skill.builtin;

import com.auraboot.framework.aurabot.skill.AuraBotSkillRegistry;
import com.auraboot.framework.aurabot.skill.RiskLevel;
import com.auraboot.framework.aurabot.skill.SkillMeta;
import com.auraboot.framework.aurabot.skill.SkillRequest;
import com.auraboot.framework.aurabot.skill.SkillResult;
import com.auraboot.framework.aurabot.skill.SkillRunRepository;
import com.auraboot.framework.aurabot.skill.SkillRunStatus;
import com.auraboot.framework.aurabot.skill.entity.SkillRun;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    @Autowired
    private SkillRunRepository skillRunRepository;

    @Autowired
    private AuraBotSkillRegistry skillRegistry;

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

    // ==================== T9: undo + permission filter ====================

    @Test
    @DisplayName("Spec §6.2 #5 — undo on empty column drops column + payload reports droppedColumn")
    public void undo_emptyColumn_succeeds() {
        // 1) Add a real column via the skill so afterSnapshot points at a
        //    truly existing storage_code; otherwise removeFromModel would
        //    fail at the binding lookup before exercising the undo path.
        String fieldCode = "it_fas_undo_e_" + shortHex();
        SkillRequest add = buildRequest(testModelCode, fieldCode, "string", n -> n.put("displayName", "Undo Empty"));
        SkillResult addRes = skill.execute(add);
        assertThat(addRes.getStatus()).isEqualTo(SkillResult.Status.SUCCESS);

        ObjectNode addPayload = (ObjectNode) addRes.getPayload();
        String fieldPid = addPayload.get("fieldPid").asText();
        String storageCode = addPayload.get("storageCode").asText();

        // 2) Synthesize a SkillRun row carrying afterSnapshot — the
        //    controller normally writes this on the success path of
        //    /execute; this IT exercises only the undo SPI in isolation.
        String undoToken = "tok_it_fas_undoe_" + shortHex();
        SkillRun seeded = persistSkillRun(undoToken, addPayload);
        assertThat(seeded.getPid()).isNotBlank();

        // 3) Invoke undo and verify column is gone.
        SkillResult undoRes = skill.undo(undoToken);

        assertThat(undoRes.getStatus()).isEqualTo(SkillResult.Status.SUCCESS);
        assertThat(undoRes.getSkillName()).isEqualTo("field:add");
        ObjectNode undoPayload = (ObjectNode) undoRes.getPayload();
        assertThat(undoPayload.get("droppedColumn").asText()).isEqualTo(storageCode);
        assertThat(undoPayload.get("modelCode").asText()).isEqualTo(testModelCode);
        assertThat(undoPayload.get("undoneFieldPid").asText()).isEqualTo(fieldPid);

        // 4) Independent verification: column is gone from the table.
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
                Integer.class, tableName, fieldCode);
        assertThat(count).as("undo must DROP COLUMN").isZero();
    }

    @Test
    @DisplayName("Spec §6.2 #6 — undo refuses when column has data (refuseIfDataExists=true)")
    public void undo_columnWithData_refuses() {
        String fieldCode = "it_fas_undo_d_" + shortHex();
        SkillRequest add = buildRequest(testModelCode, fieldCode, "string",
                n -> n.put("displayName", "Undo With Data"));
        SkillResult addRes = skill.execute(add);
        assertThat(addRes.getStatus()).isEqualTo(SkillResult.Status.SUCCESS);

        ObjectNode addPayload = (ObjectNode) addRes.getPayload();
        String storageCode = addPayload.get("storageCode").asText();

        // Insert a real data row so removeFromModel(refuseIfDataExists=true)
        // throws ColumnHasDataException — undo must rewrap as
        // SKILL_INTERNAL_ERROR with a "has data" hint.
        Long tenantId = getTestTenant().getId();
        String pid = UniqueIdGenerator.generate();
        jdbcTemplate.update(
                "INSERT INTO " + tableName + " (pid, tenant_id, " + fieldCode + ") VALUES (?, ?, ?)",
                pid, tenantId, "non-null-undo");

        String undoToken = "tok_it_fas_undod_" + shortHex();
        persistSkillRun(undoToken, addPayload);

        try {
            assertThatThrownBy(() -> skill.undo(undoToken))
                    .isInstanceOf(SkillSpiException.class)
                    .satisfies(t -> {
                        SkillSpiException sse = (SkillSpiException) t;
                        assertThat(sse.getErrorCode()).isEqualTo(SkillErrorCode.SKILL_INTERNAL_ERROR);
                        assertThat(sse.getMessage()).contains("has data");
                        assertThat(sse.getMessage()).contains(storageCode);
                    });

            // Column must still be present — refuse ≠ drop.
            Integer stillThere = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns "
                            + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
                    Integer.class, tableName, fieldCode);
            assertThat(stillThere).as("column must remain when undo refused").isEqualTo(1);
        } finally {
            // Tear down the synthetic data row so @AfterEach DROP TABLE works
            // cleanly (and the next test's mt_* CREATE doesn't trip an
            // unexpected residue).
            try {
                jdbcTemplate.update("DELETE FROM " + tableName + " WHERE pid = ?", pid);
            } catch (Exception ignore) {
                // best effort
            }
        }
    }

    @Test
    @DisplayName("Spec §6.2 #7 — undo, then re-execute same field code on the model succeeds")
    public void undo_thenRecreate_works() {
        String fieldCode = "it_fas_undo_r_" + shortHex();

        // First execute.
        SkillRequest first = buildRequest(testModelCode, fieldCode, "string",
                n -> n.put("displayName", "Cycle 1"));
        SkillResult firstRes = skill.execute(first);
        assertThat(firstRes.getStatus()).isEqualTo(SkillResult.Status.SUCCESS);
        ObjectNode firstPayload = (ObjectNode) firstRes.getPayload();

        // Undo.
        String undoToken = "tok_it_fas_undor_" + shortHex();
        persistSkillRun(undoToken, firstPayload);
        SkillResult undoRes = skill.undo(undoToken);
        assertThat(undoRes.getStatus()).isEqualTo(SkillResult.Status.SUCCESS);

        // Re-execute the SAME code on the SAME model — the F-5 fix
        // (binding hard-deleted on remove) means the bind table no longer
        // carries a (model, field_code) collision.
        SkillRequest second = buildRequest(testModelCode, fieldCode, "string",
                n -> n.put("displayName", "Cycle 2"));
        SkillResult secondRes = skill.execute(second);
        assertThat(secondRes.getStatus()).isEqualTo(SkillResult.Status.SUCCESS);

        ObjectNode secondPayload = (ObjectNode) secondRes.getPayload();
        assertThat(secondPayload.get("fieldCode").asText()).isEqualTo(fieldCode);
        // fieldPid must be a fresh row (hard-delete on remove + new insert).
        assertThat(secondPayload.get("fieldPid").asText())
                .as("re-executed field gets a fresh fieldPid")
                .isNotEqualTo(firstPayload.get("fieldPid").asText());

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
                Integer.class, tableName, fieldCode);
        assertThat(count).as("column re-added after undo").isEqualTo(1);
    }

    @Test
    @DisplayName("Spec §6.2 #8 — registry.list filters out field:add when caller lacks meta.model.update+meta.field.update")
    public void permission_missing_excludesFromDiscovery() {
        // Caller missing meta.field.update — must NOT see field:add.
        Set<String> insufficient = Set.of("meta.model.update");
        List<SkillMeta> visibleInsufficient = skillRegistry.list(insufficient);
        assertThat(visibleInsufficient)
                .as("missing meta.field.update → field:add hidden")
                .extracting(SkillMeta::getName)
                .doesNotContain("field:add");

        // Empty perms — must NOT see field:add.
        List<SkillMeta> visibleEmpty = skillRegistry.list(Set.of());
        assertThat(visibleEmpty)
                .as("empty perms → field:add hidden")
                .extracting(SkillMeta::getName)
                .doesNotContain("field:add");

        // Both perms present — must see field:add (positive control).
        Set<String> sufficient = Set.of("meta.model.update", "meta.field.update");
        List<SkillMeta> visibleOk = skillRegistry.list(sufficient);
        assertThat(visibleOk)
                .as("meta.model.update + meta.field.update → field:add visible")
                .extracting(SkillMeta::getName)
                .contains("field:add");
    }

    // ==================== helpers ====================

    /**
     * Build + persist a synthetic {@link SkillRun} carrying the supplied
     * {@code afterSnapshot} so {@link FieldAddSkill#undo(String)} can resolve
     * the token in isolation (the controller normally does this on /execute
     * success — but undo IT focuses on the SPI alone).
     */
    private SkillRun persistSkillRun(String undoToken, ObjectNode afterSnapshot) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("modelCode", afterSnapshot.get("modelCode").asText());
        params.put("code", afterSnapshot.get("fieldCode").asText());
        params.put("dataType", "string");

        SkillRun run = SkillRun.builder()
                .tenantId(getTestTenant().getId())
                .skillName("field:add")
                .paramsJson(params)
                .afterSnapshot(afterSnapshot)
                .undoToken(undoToken)
                .status(SkillRunStatus.SUCCESS.code())
                .riskLevel(RiskLevel.MEDIUM.code())
                .createdBy("it_fas")
                .build();
        return skillRunRepository.insert(run);
    }


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
