package com.auraboot.framework.aurabot.skill;

import com.auraboot.framework.aurabot.skill.entity.SkillRun;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository-level IT for {@code ab_aurabot_skill_run} (Plan Step 3).
 *
 * <p>Targets the isolated docker stack on host ports 25442 / 26389 — see
 * {@code application-skills-c2-test.yml}. All persisted IDs / keys are
 * prefixed {@code it-aurabot-} so parallel suites can co-exist.
 *
 * <p>Each test uses {@link Propagation#NOT_SUPPORTED} to escape the
 * {@code BaseIntegrationTest} class-level {@code @Transactional} boundary;
 * the {@code uq_aurabot_skill_idemp} unique index check requires a
 * committed prior row, which the auto-rollback wrapper would hide.
 */
@ActiveProfiles({"integration-test", "skills-c2-test"})
@DisplayName("SkillRun repository — DB layer (real Postgres on :25442)")
class SkillRunRepositoryIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private SkillRunRepository repository;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Tests run with {@code MetaContext.getCurrentTenantId() = testTenant.id}
     * (set up by {@link BaseIntegrationTest#setupTenantContext()}). The
     * MyBatis-Plus {@code TenantLineInnerInterceptor} auto-injects
     * {@code tenant_id = <metaContext>} into every read query — using any
     * other tenantId would cause the interceptor's filter to mask our rows.
     *
     * <p>Per-test isolation comes from a fresh {@code skillName} on every
     * invocation, not a fresh tenantId.
     */
    private long testTenantId() {
        return getTestTenant().getId();
    }

    private String freshSkillName(String prefix) {
        return prefix + "-" + UniqueIdGenerator.generate().toLowerCase();
    }

    private SkillRun newRun(long tenantId, String skillName, String idempotencyKey) {
        ObjectNode params = objectMapper.createObjectNode().put("text", "it-aurabot-payload");
        return SkillRun.builder()
                .tenantId(tenantId)
                .skillName(skillName)
                .paramsJson(params)
                .idempotencyKey(idempotencyKey)
                .createdBy("it-aurabot-user")
                .build();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("insertAndFindByIdempotency — within window returns the same row")
    void insertAndFindByIdempotency_withinWindow() {
        long tenant = testTenantId();
        String skill = freshSkillName("it-aurabot-echo");
        String idem = "it-aurabot-idem-" + UniqueIdGenerator.generate();

        SkillRun inserted = repository.insert(
                newRun(tenant, skill, idem),
                SkillRunStatus.SUCCESS,
                RiskLevel.LOW);

        try {
            assertThat(inserted.getPid()).startsWith("usr_");
            assertThat(inserted.getStatus()).isEqualTo("success"); // .code() — not enum.name()
            assertThat(inserted.getRiskLevel()).isEqualTo("low");
            assertThat(inserted.getCreatedAt()).isNotNull();

            Optional<SkillRun> hit = repository.findByIdempotency(
                    tenant, skill, idem, Duration.ofMinutes(5));

            assertThat(hit).isPresent();
            SkillRun row = hit.get();
            assertThat(row.getPid()).isEqualTo(inserted.getPid());
            assertThat(row.getTenantId()).isEqualTo(tenant);
            assertThat(row.getSkillName()).isEqualTo(skill);
            assertThat(row.getIdempotencyKey()).isEqualTo(idem);
            assertThat(row.getStatus()).isEqualTo(SkillRunStatus.SUCCESS.code());
            assertThat(row.getRiskLevel()).isEqualTo(RiskLevel.LOW.code());
            assertThat(row.getDeletedFlag()).isFalse();
            assertThat(row.getParamsJson()).isNotNull();
            assertThat(row.getParamsJson().path("text").asText()).isEqualTo("it-aurabot-payload");
        } finally {
            // Manual cleanup since we opted out of @Transactional rollback.
            repository.markUndone(inserted.getPid());
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("findByIdempotency — outside the configured window returns empty")
    void findByIdempotency_outsideWindow() {
        long tenant = testTenantId();
        String skill = freshSkillName("it-aurabot-stale");
        String idem = "it-aurabot-stale-" + UniqueIdGenerator.generate();

        // Backdate the row 10 minutes so a 1-minute lookup window cannot
        // see it. Manually building the row (not via insert overload) is
        // intentional — we need control over createdAt.
        SkillRun stale = newRun(tenant, skill, idem);
        stale.setStatus(SkillRunStatus.SUCCESS.code());
        stale.setRiskLevel(RiskLevel.LOW.code());
        stale.setCreatedAt(Instant.now().minus(Duration.ofMinutes(10)));
        SkillRun inserted = repository.insert(stale);

        try {
            // Sanity: a generous window finds it.
            Optional<SkillRun> wide = repository.findByIdempotency(
                    tenant, skill, idem, Duration.ofMinutes(30));
            assertThat(wide).isPresent();
            assertThat(wide.get().getPid()).isEqualTo(inserted.getPid());

            // Tight window misses it.
            Optional<SkillRun> tight = repository.findByIdempotency(
                    tenant, skill, idem, Duration.ofMinutes(1));
            assertThat(tight).as("row >= window must not match").isEmpty();
        } finally {
            repository.markUndone(inserted.getPid());
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("markUndone — flips status to undone and stamps undone_at")
    void markUndone_changesStatus() {
        long tenant = testTenantId();
        String skill = freshSkillName("it-aurabot-undo");
        String idem = "it-aurabot-undo-" + UniqueIdGenerator.generate();

        SkillRun inserted = repository.insert(
                newRun(tenant, skill, idem),
                SkillRunStatus.SUCCESS,
                RiskLevel.MEDIUM);

        boolean ok = repository.markUndone(inserted.getPid());
        assertThat(ok).isTrue();

        // Re-read directly through the mapper view — the row should now
        // carry status=undone and a non-null undone_at timestamp.
        Optional<SkillRun> after = repository.findByIdempotency(
                tenant, skill, idem, Duration.ofMinutes(5));

        assertThat(after).isPresent();
        SkillRun row = after.get();
        assertThat(row.getStatus()).isEqualTo(SkillRunStatus.UNDONE.code());
        assertThat(SkillRunStatus.fromCode(row.getStatus())).isEqualTo(SkillRunStatus.UNDONE);
        assertThat(row.getUndoneAt()).isNotNull();
        assertThat(row.getUndoneAt()).isAfter(row.getCreatedAt().minusSeconds(1));

        // Idempotent: a second markUndone is a no-op (status already undone,
        // undone_at re-stamps but row count stays 1).
        boolean again = repository.markUndone(inserted.getPid());
        assertThat(again).isTrue();
    }
}
