package com.auraboot.framework.aurabot.skill.builtin;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.aurabot.skill.SkillRequest;
import com.auraboot.framework.aurabot.skill.SkillResult;
import com.auraboot.framework.aurabot.skill.error.SkillErrorCode;
import com.auraboot.framework.aurabot.skill.error.SkillSpiException;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link ModelCreateSkill} dryRun + execute paths.
 *
 * <p>Runs against the C-2 docker isolated stack (PG :25442 / Redis :26389) via
 * {@link BaseIntegrationTest}. Each test allocates a unique modelCode so PG
 * state cannot collide across re-runs.
 */
@ActiveProfiles({"integration-test", "skills-c2-test"})
class ModelCreateSkillIntegrationTest extends BaseIntegrationTest {

    @Autowired ModelCreateSkill skill;
    @Autowired MetaModelService metaModelService;
    @Autowired DynamicDataMapper dynamicDataMapper;
    @Autowired ObjectMapper objectMapper;

    private String testCode;

    @BeforeEach
    void setUpModelCreate() {
        testCode = "it_mc_" + UniqueIdGenerator.generate().toLowerCase().substring(0, 8);
        // Provide tenant context for skill calls (execute requires non-null tenantId).
        MetaContext.setContext(getTestTenant().getId(), 1L, null, "it-mc-user");
    }

    @AfterEach
    void tearDownModelCreate() {
        // Best-effort cleanup so re-runs are idempotent.
        try {
            MetaModelDTO m = metaModelService.findByCode(testCode);
            if (m != null) {
                metaModelService.delete(m.getPid());
            }
        } catch (RuntimeException ignored) {
            // best-effort: tearDown must not mask the test's real assertion failure
        }
        MetaContext.clear();
    }

    @Test
    @DisplayName("dryRun returns NEEDS_CONFIRM preview and persists no rows")
    void dryRun_returnsPreview_andDoesNotPersist() {
        SkillRequest req = req(testCode);

        SkillResult result = skill.dryRun(req);

        assertThat(result.getStatus()).isEqualTo(SkillResult.Status.NEEDS_CONFIRM);
        JsonNode preview = objectMapper.valueToTree(result.getPreview());
        assertThat(preview.get("modelCode").asText()).isEqualTo(testCode);
        assertThat(preview.get("willCreateTable").asText()).isEqualTo("mt_" + testCode);
        assertThat(preview.get("defaultFields")).hasSize(7);

        // No persistence side-effects. MetaModelService.findByCode() throws
        // ValidationException("模型不存在") for missing codes (see
        // MetaModelServiceImpl L657), so absence == that exception, not null.
        assertThat(findByCodeOrNull(testCode)).isNull();
    }

    /** Mirror the skill's nullable lookup so absence is expressible in assertions. */
    private MetaModelDTO findByCodeOrNull(String code) {
        try {
            return metaModelService.findByCode(code);
        } catch (ValidationException e) {
            return null;
        }
    }

    @Test
    @DisplayName("execute persists ab_meta_model row and creates mt_<code> table")
    void execute_persistsAndCreatesTable() {
        SkillRequest req = req(testCode);

        SkillResult result = skill.execute(req);

        assertThat(result.getStatus()).isEqualTo(SkillResult.Status.SUCCESS);
        JsonNode payload = objectMapper.valueToTree(result.getPayload());
        assertThat(payload.get("modelCode").asText()).isEqualTo(testCode);
        assertThat(payload.get("tableName").asText()).isEqualTo("mt_" + testCode);
        assertThat(payload.get("defaultFieldCount").asInt()).isEqualTo(7);
        assertThat(payload.get("modelPid").asText()).isNotBlank();

        // Real PG table exists (DDL ran via MetaModelService.create autoPublish path).
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQueryWithoutTenant(
                "SELECT 1 AS x FROM information_schema.tables WHERE table_name = #{params.t}",
                Map.of("t", "mt_" + testCode));
        assertThat(rows).as("mt_<code> table must exist in information_schema").hasSize(1);

        // Meta row exists.
        assertThat(findByCodeOrNull(testCode)).isNotNull();
    }

    @Test
    @DisplayName("execute on duplicate code throws PARAMS_INVALID with /code fieldPath")
    void execute_duplicateCode_throwsParamsInvalid() {
        skill.execute(req(testCode));

        assertThatThrownBy(() -> skill.execute(req(testCode)))
                .isInstanceOf(SkillSpiException.class)
                .satisfies(t -> {
                    SkillSpiException e = (SkillSpiException) t;
                    assertThat(e.getErrorCode()).isEqualTo(SkillErrorCode.PARAMS_INVALID);
                    assertThat(e.getFieldPath()).isEqualTo("/code");
                });
    }

    private SkillRequest req(String code) {
        SkillRequest r = new SkillRequest();
        r.setSkillName("model:create");
        Map<String, Object> p = new HashMap<>();
        p.put("code", code);
        p.put("displayName", "IT " + code);
        p.put("modelCategory", "ENTITY");
        p.put("dataSensitivity", "INTERNAL");
        r.setParams(objectMapper.valueToTree(p));
        r.setIdempotencyKey("it-" + code);
        return r;
    }
}
