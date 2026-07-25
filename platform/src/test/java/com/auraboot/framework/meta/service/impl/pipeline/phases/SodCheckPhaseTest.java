package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.SodViolationException;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.SodCheckResult;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.impl.SodService;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPermitPlan;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SodCheckPhaseTest {

    @Mock
    private SodService sodService;

    private SodCheckPhase phase;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(99L, 10L, "usr_10", "Alice");
        phase = new SodCheckPhase();
        ReflectionTestUtils.setField(phase, "sodService", sodService);
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void executePassesNonNumericTargetRecordIdAsEntityPid() {
        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setTargetRecordId("pur_01KPID");

        CommandDefinition command = new CommandDefinition();
        command.setModelCode("mkt_purchase");

        CommandPipelineContext ctx = CommandPipelineContext.builder()
                .commandCode("mkt:approve_purchase")
                .request(request)
                .tenantId(99L)
                .userId(10L)
                .startTime(System.currentTimeMillis())
                .build();
        ctx.setCommand(command);

        phase.execute(ctx);

        verify(sodService).checkSod(
                "mkt:approve_purchase",
                10L,
                "Alice",
                "mkt_purchase",
                null,
                "pur_01KPID");
    }

    // ---------- permit-plan accumulator (phase-1 §11.15 step 2, gate) ----------

    @Test
    void recordsADenyPhaseDecisionWhenSodIsViolatedBeforeRethrowing() {
        when(sodService.checkSod(anyString(), any(), any(), anyString(), any(), any()))
                .thenThrow(new SodViolationException("SoD conflict", null));
        CommandPipelineContext ctx = sodContext("mkt_purchase", "pur_01KPID");

        assertThatThrownBy(() -> phase.execute(ctx)).isInstanceOf(SodViolationException.class);

        assertThat(ctx.getPhaseDecisions()).singleElement().satisfies(d -> {
            assertThat(d.decision()).isEqualTo(CommandPermitPlan.Decision.DENY);
            assertThat(d.reasonCode()).isEqualTo(SodCheckPhase.REASON_SOD_VIOLATION);
            assertThat(d.phaseName()).isEqualTo("sod_check");
        });
    }

    /** A pass abstains, never permits: SoD is a gate, so it cannot turn an undeclared command into a permit. */
    @Test
    void abstainsRatherThanPermitsWhenSodPasses() {
        when(sodService.checkSod(anyString(), any(), any(), anyString(), any(), any()))
                .thenReturn(SodCheckResult.passed());
        CommandPipelineContext ctx = sodContext("mkt_purchase", "pur_01KPID");

        phase.execute(ctx);

        assertThat(ctx.getPhaseDecisions()).singleElement()
                .extracting(CommandPermitPlan.PhaseDecision::decision)
                .isEqualTo(CommandPermitPlan.Decision.ABSTAIN);
    }

    private CommandPipelineContext sodContext(String modelCode, String targetRecordId) {
        CommandDefinition command = new CommandDefinition();
        command.setModelCode(modelCode);
        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setTargetRecordId(targetRecordId);

        CommandPipelineContext ctx = CommandPipelineContext.builder()
                .commandCode("mkt:approve_purchase")
                .request(request)
                .tenantId(99L)
                .userId(10L)
                .startTime(System.currentTimeMillis())
                .build();
        ctx.setCommand(command);
        return ctx;
    }
}
