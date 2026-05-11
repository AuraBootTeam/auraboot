package com.auraboot.framework.aurabot.skill.provider;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.aurabot.skill.SkillRequest;
import com.auraboot.framework.aurabot.skill.error.SkillErrorCode;
import com.auraboot.framework.aurabot.skill.error.SkillSpiException;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Integration tests for {@link SkillToolExecutor} (C-5 Task 3 §4.2).
 *
 * <p>Exercises the chat-aware dispatch shim that wraps the skill SPI:
 * <ul>
 *     <li>LOW risk → inline execution via {@code dispatch(...)} returning {@code EXECUTED}.</li>
 *     <li>MEDIUM/HIGH risk → preview mint + token via {@code dispatch(...)} returning {@code PREVIEW_PENDING}.</li>
 *     <li>{@code confirm(...)} consumes the preview token and runs execute.</li>
 *     <li>Invalid token surfaces typed {@link SkillErrorCode#PREVIEW_TOKEN_INVALID}.</li>
 * </ul>
 *
 * <p>Real PG (port 25442) + real Redis (port 26389) via the
 * {@code skills-c2-test} profile. {@link UserPermissionService} +
 * {@link PermissionMapper} are mocked to avoid wiring full RBAC tables —
 * mirrors {@code AuraBotSkillToolProviderIntegrationTest}.
 */
@ActiveProfiles({"integration-test", "skills-c2-test"})
class SkillToolExecutorIntegrationTest extends BaseIntegrationTest {

    @Autowired SkillToolExecutor executor;
    @Autowired ObjectMapper objectMapper;
    @Autowired MetaModelService metaModelService;
    @Autowired DynamicDataMapper dynamicDataMapper;

    @MockBean private UserPermissionService userPermissionService;
    @MockBean private PermissionMapper permissionMapper;

    private final Set<String> currentPermissions = new HashSet<>();
    private String testModelCode;

    @BeforeEach
    void setUp() {
        currentPermissions.clear();
        // model:query has empty oneOf-required; we'll always supply modelCode.
        // model:create requires meta.model.update; tests grant as needed.
        MetaContext.setContext(getTestTenant().getId(), 1L, null, "it-c5-t3-user");

        when(userPermissionService.getUserPermissionIds(eq(1L)))
                .thenAnswer(inv -> Set.of(1L));
        when(permissionMapper.findByIds(any())).thenAnswer(inv ->
                currentPermissions.stream().map(code -> {
                    Permission p = new Permission();
                    p.setCode(code);
                    return p;
                }).toList());

        testModelCode = "it_c5t3_" + UniqueIdGenerator.generate().toLowerCase().substring(0, 8);
    }

    @AfterEach
    void tearDown() {
        // Best-effort cleanup of any model:create rows created during execute/confirm tests.
        // The DDL escapes the @Transactional rollback (alterTable is non-tx), so we
        // explicitly soft/hard-delete and drop the table to keep re-runs idempotent.
        try {
            MetaModelDTO m = metaModelService.findByCode(testModelCode);
            if (m != null) {
                metaModelService.delete(m.getPid());
            }
        } catch (ValidationException ignored) {
            // model never created — that's the no-op happy path
        } catch (RuntimeException ignored) {
            // tearDown must not mask the test's real assertion failure
        }
        try {
            dynamicDataMapper.alterTable("DROP TABLE IF EXISTS mt_" + testModelCode);
        } catch (RuntimeException ignored) {
            // best-effort
        }
        MetaContext.clear();
    }

    // ─── Case 1: LOW risk dispatch executes inline ────────────────────────────
    @Test
    @DisplayName("dispatch(LOW skill) executes inline and returns EXECUTED")
    void dispatchLow_executesInline() {
        currentPermissions.add("meta.model.read");

        // model:query (LOW) — keyword form so it doesn't require an existing row.
        ObjectNode params = objectMapper.createObjectNode().put("keyword", "no-such-model-xyz");
        SkillRequest req = SkillRequest.builder()
                .skillName("model:query")
                .params(params)
                .build();

        SkillToolExecutor.DispatchOutcome outcome = executor.dispatch("model:query", req);

        assertThat(outcome.kind()).isEqualTo(SkillToolExecutor.OutcomeKind.EXECUTED);
        assertThat(outcome.result()).isNotNull();
        assertThat(outcome.result().getSkillName()).isEqualTo("model:query");
        assertThat(outcome.previewToken()).isNull();
        assertThat(outcome.preview()).isNull();
    }

    // ─── Case 2: HIGH risk dispatch returns preview + pending ─────────────────
    @Test
    @DisplayName("dispatch(HIGH skill) returns PREVIEW_PENDING with preview + token")
    void dispatchHigh_returnsPreviewPending() {
        currentPermissions.add("meta.model.update");

        ObjectNode params = objectMapper.createObjectNode()
                .put("code", testModelCode)
                .put("displayName", "C5T3 Preview Model");
        SkillRequest req = SkillRequest.builder()
                .skillName("model:create")
                .params(params)
                .build();

        SkillToolExecutor.DispatchOutcome outcome = executor.dispatch("model:create", req);

        assertThat(outcome.kind()).isEqualTo(SkillToolExecutor.OutcomeKind.PREVIEW_PENDING);
        assertThat(outcome.previewToken()).isNotBlank();
        assertThat(outcome.preview()).isNotNull();
        assertThat(outcome.riskLevel()).isEqualTo("high");
        // dryRun must NOT have created the model — DDL is the execute path's job.
        // Probe ab_meta_model directly: findByCode's null-vs-throw contract
        // varies across cache states, raw SQL is the canonical absence check.
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQueryWithoutTenant(
                "SELECT pid FROM ab_meta_model WHERE code = #{params.c}",
                Map.of("c", testModelCode));
        assertThat(rows).as("dryRun must not persist ab_meta_model row").isEmpty();
    }

    // ─── Case 3: confirm() with valid token executes and persists ─────────────
    @Test
    @DisplayName("confirm(HIGH skill, valid token) executes and writes ab_meta_model row")
    void confirmHigh_executesAndReturnsResult() {
        currentPermissions.add("meta.model.update");

        ObjectNode params = objectMapper.createObjectNode()
                .put("code", testModelCode)
                .put("displayName", "C5T3 Confirm Model");
        SkillRequest dispatchReq = SkillRequest.builder()
                .skillName("model:create")
                .params(params)
                .build();

        SkillToolExecutor.DispatchOutcome pending = executor.dispatch("model:create", dispatchReq);
        assertThat(pending.kind()).isEqualTo(SkillToolExecutor.OutcomeKind.PREVIEW_PENDING);
        String token = pending.previewToken();
        assertThat(token).isNotBlank();

        // Confirm the same skill+params using the minted token.
        SkillRequest confirmReq = SkillRequest.builder()
                .skillName("model:create")
                .params(params)
                .build();

        SkillToolExecutor.DispatchOutcome confirmed = executor.confirm("model:create", confirmReq, token);

        assertThat(confirmed.kind()).isEqualTo(SkillToolExecutor.OutcomeKind.EXECUTED);
        assertThat(confirmed.result()).isNotNull();
        assertThat(confirmed.result().getSkillName()).isEqualTo("model:create");

        // Real ab_meta_model row must exist after confirm.
        // Re-establish MetaContext on the test thread because the platform's
        // tenant interceptor may have cleared it during commit.
        MetaContext.setContext(getTestTenant().getId(), 1L, null, "it-c5-t3-user");
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQueryWithoutTenant(
                "SELECT pid FROM ab_meta_model WHERE code = #{params.c}",
                Map.of("c", testModelCode));
        assertThat(rows).as("ab_meta_model row must be persisted").hasSize(1);
    }

    // ─── Case 4: confirm() with invalid token throws PREVIEW_TOKEN_INVALID ────
    @Test
    @DisplayName("confirm(invalid token) throws SkillSpiException PREVIEW_TOKEN_INVALID")
    void confirmInvalidToken_throws() {
        currentPermissions.add("meta.model.update");

        ObjectNode params = objectMapper.createObjectNode()
                .put("code", testModelCode)
                .put("displayName", "C5T3 Bogus Token Model");
        SkillRequest req = SkillRequest.builder()
                .skillName("model:create")
                .params(params)
                .build();

        String bogusToken = "px_does_not_exist_" + UniqueIdGenerator.generate();

        assertThatThrownBy(() -> executor.confirm("model:create", req, bogusToken))
                .isInstanceOf(SkillSpiException.class)
                .satisfies(t -> {
                    SkillSpiException e = (SkillSpiException) t;
                    assertThat(e.getErrorCode()).isEqualTo(SkillErrorCode.PREVIEW_TOKEN_INVALID);
                });
    }
}
