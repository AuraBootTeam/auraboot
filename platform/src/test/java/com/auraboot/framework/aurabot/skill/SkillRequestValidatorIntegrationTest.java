package com.auraboot.framework.aurabot.skill;

import com.auraboot.framework.aurabot.skill.entity.SkillRun;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Integration test for {@link SkillRequestValidator} — real Redis + real PG.
 * Mocks only the {@link AuraBotSkillRegistry} (which is bootstrap-once and
 * cannot accept new skills mid-test); every other collaborator is a real
 * Spring bean wired against the {@code auraboot-skills-c2} docker stack
 * (PG :25442 / Redis :26389).
 *
 * <p>The bootstrap-test javadoc explains why we cannot publish a real
 * {@link AuraBotSkill} {@code @Component} from a test fixture — it would
 * poison every IT in the same JVM via {@code TestApplication}'s explicit
 * {@code @ComponentScan}. Mocking the registry in this IT keeps the failure
 * isolation intact while still exercising the Step 3 (Redis idempotency +
 * PG row lookup) and Step 5 (preview token consume) end-to-end paths.
 */
@ActiveProfiles({"integration-test", "skills-c2-test"})
@DisplayName("SkillRequestValidator — IT (real Redis + real PG, mocked registry)")
class SkillRequestValidatorIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private SkillRequestValidator validator;

    @Autowired
    private SkillRunRepository repository;

    @Autowired
    private SkillIdempotencyStore idempotencyStore;

    @Autowired
    private PreviewTokenStore previewTokenStore;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Registry is the only mocked collaborator. The validator delegates to
     * its {@code get} / {@code getCompiledSchema} hooks; a real registry
     * cannot register ad-hoc test skills without polluting the JVM-wide
     * bean set.
     */
    @MockBean
    private AuraBotSkillRegistry registry;

    @AfterEach
    void cleanRedis() {
        // Wipe any residual idemp / preview keys across the entire skills-c2
        // namespace so re-runs and parallel suites stay independent.
        Set<String> idemp = redisTemplate.keys(SkillIdempotencyStore.KEY_PREFIX + "*");
        if (idemp != null && !idemp.isEmpty()) {
            redisTemplate.delete(idemp);
        }
        Set<String> preview = redisTemplate.keys(PreviewTokenStore.KEY_PREFIX + "*");
        if (preview != null && !preview.isEmpty()) {
            redisTemplate.delete(preview);
        }
    }

    private long testTenantId() {
        return getTestTenant().getId();
    }

    private JsonSchema acceptAllSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schema);
    }

    private AuraBotSkill mockSkill(String name, RiskLevel risk) {
        AuraBotSkill skill = org.mockito.Mockito.mock(AuraBotSkill.class);
        when(skill.name()).thenReturn(name);
        when(skill.riskLevel()).thenReturn(risk);
        when(skill.requiredPermissions()).thenReturn(Set.of());
        return skill;
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("idempotencyReplay — end-to-end: Redis claim + PG row → shortCircuit")
    void idempotencyReplay_endToEnd() {
        long tenant = testTenantId();
        String skillName = "it-validator-echo-" + UniqueIdGenerator.generate().toLowerCase();
        String idemKey = "it-validator-idem-" + UniqueIdGenerator.generate();

        AuraBotSkill skill = mockSkill(skillName, RiskLevel.LOW);
        when(registry.get(skillName)).thenReturn(Optional.of(skill));
        when(registry.getCompiledSchema(skillName)).thenReturn(Optional.of(acceptAllSchema()));

        // Pre-seed a SkillRun row that represents the prior winner.
        ObjectNode after = objectMapper.createObjectNode().put("ok", true).put("count", 7);
        SkillRun seed = SkillRun.builder()
                .tenantId(tenant)
                .skillName(skillName)
                .idempotencyKey(idemKey)
                .paramsJson(objectMapper.createObjectNode().put("k", "v"))
                .afterSnapshot(after)
                .createdBy("it-aurabot-validator")
                .build();
        SkillRun inserted = repository.insert(seed,
                SkillRunStatus.SUCCESS, RiskLevel.LOW);

        // Claim the Redis ledger with the seeded pid so a second caller sees a duplicate.
        Optional<String> firstClaim = idempotencyStore.tryClaim(
                tenant, skillName, idemKey, inserted.getPid());
        assertThat(firstClaim).as("seed pid must win the claim").isEmpty();

        try {
            // Now run the validator as a "second caller" — should observe the
            // duplicate claim, find the DB row, and short-circuit.
            SkillRequest req = SkillRequest.builder()
                    .skillName(skillName)
                    .params(objectMapper.createObjectNode())
                    .idempotencyKey(idemKey)
                    .build();

            SkillRequestValidator.ValidatedRequest out = validator.validate(
                    req, Set.of(), tenant,
                    SkillRequestValidator.ValidationMode.EXECUTE);

            assertThat(out.shortCircuit())
                    .as("duplicate claim with DB row must short-circuit")
                    .isPresent();
            SkillResult replay = out.shortCircuit().orElseThrow();
            assertThat(replay.getStatus()).isEqualTo(SkillResult.Status.SUCCESS);
            assertThat(replay.getSkillName()).isEqualTo(skillName);
            assertThat(replay.getRiskLevel()).isEqualTo(RiskLevel.LOW);
            // afterSnapshot is round-tripped onto SkillResult.payload as a JsonNode.
            assertThat(replay.getPayload()).isNotNull();
            JsonNode payload = (JsonNode) replay.getPayload();
            assertThat(payload.path("ok").asBoolean()).isTrue();
            assertThat(payload.path("count").asInt()).isEqualTo(7);

            // No preview side-channel for a LOW-risk replay.
            assertThat(out.preview()).isEmpty();
        } finally {
            repository.markUndone(inserted.getPid());
            idempotencyStore.release(tenant, skillName, idemKey);
        }
    }

    @Test
    @DisplayName("previewToken — consumeMatch returns ValidatedRequest.preview filled")
    void previewToken_consumeMatch_returnsPreview() {
        long tenant = testTenantId();
        String skillName = "it-validator-risky-" + UniqueIdGenerator.generate().toLowerCase();

        AuraBotSkill skill = mockSkill(skillName, RiskLevel.MEDIUM);
        when(registry.get(skillName)).thenReturn(Optional.of(skill));
        when(registry.getCompiledSchema(skillName)).thenReturn(Optional.of(acceptAllSchema()));

        // Mint a preview token bound to (tenant, skill, params).
        ObjectNode params = objectMapper.createObjectNode().put("victim", "rec-001");
        ObjectNode previewBody = objectMapper.createObjectNode()
                .put("affectedRows", 3)
                .put("warning", "destructive");

        String token = previewTokenStore.save(tenant, skillName, params, previewBody);
        assertThat(token).isNotBlank();

        // Now run validate(EXECUTE) with the matching token + same params.
        SkillRequest req = SkillRequest.builder()
                .skillName(skillName)
                .params(params)
                .previewToken(token)
                // No idempotency key → step 3 is a no-op.
                .build();

        SkillRequestValidator.ValidatedRequest out = validator.validate(
                req, Set.of(), tenant,
                SkillRequestValidator.ValidationMode.EXECUTE);

        assertThat(out.shortCircuit()).isEmpty();
        assertThat(out.preview())
                .as("matching preview token must hydrate ValidatedRequest.preview")
                .isPresent();
        PreviewTokenStore.PreviewPayload preview = out.preview().orElseThrow();
        assertThat(preview.tenantId()).isEqualTo(tenant);
        assertThat(preview.skillName()).isEqualTo(skillName);
        assertThat(preview.payload().path("affectedRows").asInt()).isEqualTo(3);
        assertThat(preview.payload().path("warning").asText()).isEqualTo("destructive");

        // Token must be consumed (one-shot semantics) — a second validate
        // with the same token should now fail at step 5.
        SkillRequest replay = SkillRequest.builder()
                .skillName(skillName)
                .params(params)
                .previewToken(token)
                .build();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        validator.validate(replay, Set.of(), tenant,
                                SkillRequestValidator.ValidationMode.EXECUTE))
                .isInstanceOf(com.auraboot.framework.aurabot.skill.error.SkillSpiException.class);
    }
}
