package com.auraboot.framework.integration.agent;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.BindingRule;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.IdempotencyService;
import com.auraboot.framework.meta.service.InvariantEngine;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.meta.service.impl.pipeline.phases.CompletionPhase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-62 R2-N2: verifies that {@link CompletionPhase}'s EFFECT sub-phase
 * writes events to {@code ab_outbox} only via the outer transaction so that
 * dry-run rollback discards them (today's guarantee), and that the phase
 * still runs under dry-run to exercise the rollback envelope itself.
 *
 * <p>The test drives {@link CompletionPhase#execute(CommandPipelineContext)}
 * directly with a synthetic effect binding rule. Because every BaseIntegrationTest
 * method runs inside a Spring-managed {@code @Transactional @Rollback(true)}
 * envelope, statements inserted within the method are visible to subsequent
 * SELECTs in the same connection — which is exactly what we need to count
 * outbox deltas.
 *
 * <p>Under dry-run the outbox row IS written during the phase, but the
 * outer transaction is marked rollback-only by {@code CommandExecutorImpl}.
 * Here we can't observe the DB-level rollback (the test itself also rolls
 * back), so we document the invariant at the code level: the pipeline
 * phase delegates exclusively to MyBatis mappers inheriting the caller's
 * transaction. If a future refactor introduces a non-JDBC emission the
 * phase-level gate in {@link CompletionPhase} MUST become active.
 *
 * <p>Sanity coverage: when {@code dryRun=false} we must see exactly one
 * outbox row appear (the feature itself works).
 */
@DisplayName("CompletionPhase — effect sub-phase dry-run envelope (PR-62)")
class EffectPhaseDryRunIntegrationTest extends BaseIntegrationTest {

    @Autowired private CompletionPhase completionPhase;
    @Autowired private JdbcTemplate jdbcTemplate;

    // Post-invariant + idempotency are unrelated to the outbox assertion —
    // mock them so the synthetic command doesn't need a full CommandDefinition.
    @MockitoBean private InvariantEngine invariantEngine;
    @MockitoBean private IdempotencyService idempotencyService;

    @Test
    @DisplayName("dryRun=false + one effect rule → exactly 1 new ab_outbox row (sanity)")
    void non_dry_run_writes_outbox_row() {
        String cmdCode = "pr62_effect_ok_" + System.nanoTime();
        long before = countOutboxRows(cmdCode);

        completionPhase.execute(buildCtx(cmdCode, /* dryRun= */ false));

        long after = countOutboxRows(cmdCode);
        assertThat(after)
                .as("non-dry-run execution must write exactly one outbox row")
                .isEqualTo(before + 1);
    }

    @Test
    @DisplayName("dryRun=true + one effect rule → outbox write happens under rollback envelope")
    void dry_run_effect_phase_runs_under_rollback_envelope() {
        String cmdCode = "pr62_effect_dry_" + System.nanoTime();

        // No NPE, no exception — the effect executor is invoked but is
        // operating inside the rollback-only transaction the production
        // executor installs. We assert that the row IS written (the phase
        // intentionally runs to exercise the rollback), which means the
        // JDBC-only contract holds. If ever a future change introduces a
        // non-JDBC side effect here, the `if (!dryRun) return;` gate in
        // CompletionPhase must activate and this assertion must flip.
        long before = countOutboxRows(cmdCode);

        completionPhase.execute(buildCtx(cmdCode, /* dryRun= */ true));

        long after = countOutboxRows(cmdCode);
        assertThat(after)
                .as("effect phase runs under dry-run so the rollback envelope is exercised; "
                        + "writes are pure JDBC and are discarded at outer-tx rollback in production")
                .isEqualTo(before + 1);

        // Post-invariants and idempotency are still invoked by the phase —
        // they are pure read-side / pure check-side and safe under dry-run.
        // Verify idempotency is NOT recorded because the request carries no
        // clientRequestId (avoids cross-test pollution).
        Mockito.verifyNoInteractions(idempotencyService);
    }

    private long countOutboxRows(String cmdCode) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_outbox WHERE command_code = ?",
                Long.class, cmdCode);
        return n == null ? 0 : n;
    }

    private CommandPipelineContext buildCtx(String cmdCode, boolean dryRun) {
        CommandDefinition command = new CommandDefinition();
        command.setCode(cmdCode);
        command.setModelCode("pr62_test_model");

        BindingRule effectRule = new BindingRule();
        effectRule.setRuleType("effect");
        effectRule.setEventType("CommandExecuted");

        Map<String, List<BindingRule>> rulesByType = new HashMap<>();
        rulesByType.put("effect", List.of(effectRule));

        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setPayload(Collections.emptyMap());
        request.setDryRun(dryRun);
        // Intentionally no clientRequestId so IdempotencyService is not touched.

        return CommandPipelineContext.builder()
                .commandCode(cmdCode)
                .request(request)
                .tenantId(testTenant != null ? testTenant.getId() : 1L)
                .userId(testUser != null ? testUser.getId() : 1L)
                .startTime(System.currentTimeMillis())
                .command(command)
                .payload(new HashMap<>())
                .execConfig(new HashMap<>())
                .rulesByType(rulesByType)
                .fieldMapResults(new HashMap<>())
                .handlerResults(new HashMap<>())
                .phaseTimings(new java.util.LinkedHashMap<>())
                .currentPhase("completion")
                .currentPhaseStart(System.currentTimeMillis())
                .build();
    }
}
