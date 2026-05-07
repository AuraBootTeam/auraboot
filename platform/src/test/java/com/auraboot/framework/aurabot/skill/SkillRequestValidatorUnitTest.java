package com.auraboot.framework.aurabot.skill;

import com.auraboot.framework.aurabot.skill.entity.SkillRun;
import com.auraboot.framework.aurabot.skill.error.SkillErrorCode;
import com.auraboot.framework.aurabot.skill.error.SkillSpiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit test for {@link SkillRequestValidator} — no Spring boot, no
 * real Redis / PG. Each step of the five-step pipeline (Plan §6) is
 * exercised in isolation by mocking the four collaborators (registry,
 * repo, idempotency store, preview store).
 *
 * <p>Integration coverage (real Redis + real PG) lives in
 * {@code SkillRequestValidatorIntegrationTest}; this file focuses on
 * branch correctness and the fail-open Redis behaviour, which is awkward
 * to provoke against a live Redis container.
 */
@DisplayName("SkillRequestValidator — unit (mocked collaborators)")
class SkillRequestValidatorUnitTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long TENANT = 4242L;

    private AuraBotSkillRegistry registry;
    private SkillRunRepository repository;
    private SkillIdempotencyStore idempotencyStore;
    private PreviewTokenStore previewStore;
    private SkillRequestValidator validator;

    @BeforeEach
    void setUp() {
        registry = mock(AuraBotSkillRegistry.class);
        repository = mock(SkillRunRepository.class);
        idempotencyStore = mock(SkillIdempotencyStore.class);
        previewStore = mock(PreviewTokenStore.class);
        validator = new SkillRequestValidator(
                registry, repository, idempotencyStore, previewStore, MAPPER);
    }

    /** Build an AuraBotSkill mock with the given name / risk / perms. */
    private AuraBotSkill mockSkill(String name, RiskLevel risk, Set<String> perms) {
        AuraBotSkill skill = mock(AuraBotSkill.class);
        when(skill.name()).thenReturn(name);
        when(skill.riskLevel()).thenReturn(risk);
        when(skill.requiredPermissions()).thenReturn(perms);
        return skill;
    }

    /** Compile a JSON schema requiring an integer field {@code n} (>= 0). */
    private JsonSchema integerNSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.putArray("required").add("n");
        ObjectNode props = schema.putObject("properties");
        ObjectNode n = props.putObject("n");
        n.put("type", "integer");
        n.put("minimum", 0);
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schema);
    }

    private JsonSchema acceptAllSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schema);
    }

    private JsonNode params(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Step 1

    @Test
    @DisplayName("step1 — unknown skill throws SKILL_NOT_FOUND (404)")
    void step1_unknownSkill_throws404() {
        when(registry.get("ghost")).thenReturn(Optional.empty());
        SkillRequest req = SkillRequest.builder().skillName("ghost").build();

        assertThatThrownBy(() -> validator.validate(
                req, Set.of(), TENANT, SkillRequestValidator.ValidationMode.EXECUTE))
                .isInstanceOf(SkillSpiException.class)
                .satisfies(t -> assertThat(((SkillSpiException) t).getErrorCode())
                        .isEqualTo(SkillErrorCode.SKILL_NOT_FOUND));

        // No further collaborators called — short-circuit at step 1.
        verify(idempotencyStore, never()).tryClaim(anyLong(), anyString(), anyString(), anyString());
        verify(repository, never()).findByIdempotency(anyLong(), anyString(), anyString(), any(Duration.class));
    }

    // ────────────────────────────────────────────────────────────────────────
    // Step 2

    @Test
    @DisplayName("step2 — missing required permission throws PERMISSION_DENIED (403)")
    void step2_missingPerm_throws403() {
        AuraBotSkill skill = mockSkill("admin:reset", RiskLevel.HIGH, Set.of("system:admin"));
        when(registry.get("admin:reset")).thenReturn(Optional.of(skill));

        SkillRequest req = SkillRequest.builder()
                .skillName("admin:reset")
                .params(params("{}"))
                .build();

        assertThatThrownBy(() -> validator.validate(
                req, Set.of("user:read"), TENANT, SkillRequestValidator.ValidationMode.EXECUTE))
                .isInstanceOf(SkillSpiException.class)
                .satisfies(t -> {
                    SkillSpiException e = (SkillSpiException) t;
                    assertThat(e.getErrorCode()).isEqualTo(SkillErrorCode.PERMISSION_DENIED);
                    assertThat(e.getMessage()).contains("system:admin");
                });

        // Step 3 / 4 / 5 must not run.
        verify(idempotencyStore, never()).tryClaim(anyLong(), anyString(), anyString(), anyString());
        verify(registry, never()).getCompiledSchema(anyString());
    }

    // ────────────────────────────────────────────────────────────────────────
    // Step 3 — replay short-circuit

    @Test
    @DisplayName("step3 — duplicate idempotency claim with DB row returns shortCircuit replay")
    void step3_idempotencyReplay_returnsShortCircuit() {
        AuraBotSkill skill = mockSkill("echo", RiskLevel.LOW, Set.of());
        when(registry.get("echo")).thenReturn(Optional.of(skill));

        // Duplicate Redis claim — prior pid present.
        when(idempotencyStore.tryClaim(eq(TENANT), eq("echo"), eq("idem-1"), anyString()))
                .thenReturn(Optional.of("usr_prior"));

        // DB row found within window.
        SkillRun priorRow = SkillRun.builder()
                .pid("usr_prior")
                .tenantId(TENANT)
                .skillName("echo")
                .idempotencyKey("idem-1")
                .status(SkillRunStatus.SUCCESS.code())
                .riskLevel(RiskLevel.LOW.code())
                .afterSnapshot(MAPPER.createObjectNode().put("ok", true))
                .build();
        when(repository.findByIdempotency(eq(TENANT), eq("echo"), eq("idem-1"), any(Duration.class)))
                .thenReturn(Optional.of(priorRow));

        SkillRequest req = SkillRequest.builder()
                .skillName("echo")
                .params(params("{}"))
                .idempotencyKey("idem-1")
                .build();

        SkillRequestValidator.ValidatedRequest out = validator.validate(
                req, Set.of(), TENANT, SkillRequestValidator.ValidationMode.EXECUTE);

        assertThat(out.shortCircuit()).isPresent();
        SkillResult replay = out.shortCircuit().orElseThrow();
        assertThat(replay.getStatus()).isEqualTo(SkillResult.Status.SUCCESS);
        assertThat(replay.getSkillName()).isEqualTo("echo");
        assertThat(replay.getRiskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(replay.getPayload()).isNotNull();

        // Step 4 (schema) must NOT have been touched on a replay short-circuit.
        verify(registry, never()).getCompiledSchema(anyString());
        // Stale-claim release must NOT have fired since the DB row hit.
        verify(idempotencyStore, never()).release(anyLong(), anyString(), anyString());
    }

    // ────────────────────────────────────────────────────────────────────────
    // Step 3 — Redis fail-open

    @Test
    @DisplayName("step3 — Redis down → fail-open (validate proceeds, no short-circuit)")
    void step3_redisDown_failsOpen_andProceeds() {
        AuraBotSkill skill = mockSkill("echo", RiskLevel.LOW, Set.of());
        when(registry.get("echo")).thenReturn(Optional.of(skill));
        when(registry.getCompiledSchema("echo")).thenReturn(Optional.of(acceptAllSchema()));

        // Redis SETNX throws — should be swallowed (fail-open).
        doThrow(new RedisConnectionFailureException("Redis unreachable"))
                .when(idempotencyStore)
                .tryClaim(anyLong(), anyString(), anyString(), anyString());

        SkillRequest req = SkillRequest.builder()
                .skillName("echo")
                .params(params("{}"))
                .idempotencyKey("idem-down")
                .build();

        SkillRequestValidator.ValidatedRequest out = validator.validate(
                req, Set.of(), TENANT, SkillRequestValidator.ValidationMode.EXECUTE);

        // No short-circuit; pipeline proceeded to Step 4 (compiled schema lookup).
        assertThat(out.shortCircuit()).isEmpty();
        assertThat(out.preview()).isEmpty();
        assertThat(out.candidatePid()).startsWith("usr_");
        verify(registry, times(1)).getCompiledSchema("echo");
        // No release attempt — it was a primary connection failure, not an orphan claim.
        verify(idempotencyStore, never()).release(anyLong(), anyString(), anyString());
    }

    // ────────────────────────────────────────────────────────────────────────
    // Step 4

    @Test
    @DisplayName("step4 — params violating schema throws PARAMS_INVALID (400) with fieldPath")
    void step4_paramsInvalid_throws400_withFieldPath() {
        AuraBotSkill skill = mockSkill("echo", RiskLevel.LOW, Set.of());
        when(registry.get("echo")).thenReturn(Optional.of(skill));
        when(registry.getCompiledSchema("echo")).thenReturn(Optional.of(integerNSchema()));
        // No idempotency key → step 3 is a no-op.

        // params: n is a string instead of integer.
        SkillRequest req = SkillRequest.builder()
                .skillName("echo")
                .params(params("{\"n\":\"oops\"}"))
                .build();

        assertThatThrownBy(() -> validator.validate(
                req, Set.of(), TENANT, SkillRequestValidator.ValidationMode.EXECUTE))
                .isInstanceOf(SkillSpiException.class)
                .satisfies(t -> {
                    SkillSpiException e = (SkillSpiException) t;
                    assertThat(e.getErrorCode()).isEqualTo(SkillErrorCode.PARAMS_INVALID);
                    // fieldPath is JSON-pointer-ish ("$.n" or "/n" depending on version).
                    assertThat(e.getFieldPath()).isNotNull().contains("n");
                    assertThat(e.getMessage()).containsIgnoringCase("invalid");
                });
    }

    // ────────────────────────────────────────────────────────────────────────
    // Step 5

    @Test
    @DisplayName("step5 — risk≥MEDIUM with no preview token throws CONFIRM_REQUIRED (422)")
    void step5_riskMedium_noToken_throws422_CONFIRM_REQUIRED() {
        AuraBotSkill skill = mockSkill("admin:reset", RiskLevel.MEDIUM, Set.of());
        when(registry.get("admin:reset")).thenReturn(Optional.of(skill));
        when(registry.getCompiledSchema("admin:reset")).thenReturn(Optional.of(acceptAllSchema()));

        SkillRequest req = SkillRequest.builder()
                .skillName("admin:reset")
                .params(params("{}"))
                // No previewToken set.
                .build();

        assertThatThrownBy(() -> validator.validate(
                req, Set.of(), TENANT, SkillRequestValidator.ValidationMode.EXECUTE))
                .isInstanceOf(SkillSpiException.class)
                .satisfies(t -> assertThat(((SkillSpiException) t).getErrorCode())
                        .isEqualTo(SkillErrorCode.CONFIRM_REQUIRED));

        // Should never have reached the preview store consume call.
        verify(previewStore, never()).consume(anyString(), anyString(), any());
    }
}
