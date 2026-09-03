package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.exception.ConflictException;
import com.auraboot.framework.exception.CasVersionConflictException;
import com.auraboot.framework.exception.CasVersionRequiredException;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommandTargetVersionLockPhaseTest {

    @Mock private MetaModelService metaModelService;
    @Mock private DynamicDataMapper dynamicDataMapper;

    private CommandTargetVersionLockPhase phase;

    @BeforeEach
    void setUp() {
        phase = new CommandTargetVersionLockPhase(metaModelService, dynamicDataMapper);
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void locksTheTenantScopedPidAndRetainsTheAuthoritativeVersion() {
        givenPhysicalModel();
        when(dynamicDataMapper.selectByQueryWithoutTenant(anyString(), anyMap()))
                .thenReturn(List.of(Map.of("row_version", 7L)));
        CommandPipelineContext ctx = context(7);

        phase.execute(ctx);

        assertThat(ctx.getTargetRecordVersion()).isEqualTo(7L);
        verify(dynamicDataMapper).selectByQueryWithoutTenant(
                eq("SELECT row_version FROM dq_quote_request WHERE tenant_id = #{params.tenantId}"
                        + " AND pid = #{params.targetRecordPid} FOR SHARE"),
                eq(Map.of("tenantId", 41L, "targetRecordPid", "REQ-1")));
    }

    @Test
    void rejectsAStaleVersionAfterTheRowIsLocked() {
        givenPhysicalModel();
        when(dynamicDataMapper.selectByQueryWithoutTenant(anyString(), anyMap()))
                .thenReturn(List.of(Map.of("row_version", 8L)));

        assertThatThrownBy(() -> phase.execute(context(7)))
                .isInstanceOf(CasVersionConflictException.class)
                .hasMessageContaining("expected 7")
                .hasMessageContaining("current 8");
    }

    @Test
    void failsClosedWhenTheTargetDisappearsOrHasNoVersion() {
        givenPhysicalModel();
        when(dynamicDataMapper.selectByQueryWithoutTenant(anyString(), anyMap()))
                .thenReturn(List.of());

        assertThatThrownBy(() -> phase.execute(context(7)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("current unavailable");
    }

    @Test
    void refusesToPretendALockExistsOutsideATransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);

        assertThatThrownBy(() -> phase.execute(context(7)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active transaction");
        verify(dynamicDataMapper, never()).selectByQueryWithoutTenant(anyString(), anyMap());
    }

    @Test
    void omittedExpectedVersionDoesNotTakeARowLock() {
        CommandPipelineContext ctx = context(null);

        assertThat(phase.shouldSkip(ctx)).isTrue();
        verify(dynamicDataMapper, never()).selectByQueryWithoutTenant(anyString(), anyMap());
    }

    @Test
    void updateWithoutCasDeclaredProceedsVersionFree() {
        // Broad policy: CAS is opt-in per command; a plain update with no
        // casRequired executionConfig neither locks nor fails closed.
        CommandPipelineContext ctx = context(null);
        ctx.getRequest().setOperationType("UPDATE");

        assertThat(phase.shouldSkip(ctx)).isTrue();
        verify(dynamicDataMapper, never()).selectByQueryWithoutTenant(anyString(), anyMap());
    }

    @Test
    void strictUpdateWithoutExpectedVersionFailsClosedBeforeLocking() {
        CommandPipelineContext ctx = context(null);
        ctx.getCommand().setExecutionConfig("{\"casRequired\": true}");
        ctx.getRequest().setOperationType("UPDATE");

        assertThat(phase.shouldSkip(ctx)).isFalse();
        assertThatThrownBy(() -> phase.execute(ctx))
                .isInstanceOf(CasVersionRequiredException.class)
                .hasMessageContaining("requires expectedVersion")
                .satisfies(error -> assertThat(error)
                        .isInstanceOf(CasVersionRequiredException.class)
                        .extracting(item -> ((CasVersionRequiredException) item).getConflictCode())
                        .isEqualTo(ConflictException.ConflictCodes.CAS_VERSION_REQUIRED));
        verify(dynamicDataMapper, never()).selectByQueryWithoutTenant(anyString(), anyMap());
    }

    @Test
    void malformedCasConfigFailsOpenRatherThanBlockingCommands() {
        CommandPipelineContext ctx = context(null);
        ctx.getCommand().setExecutionConfig("not-json");
        ctx.getRequest().setOperationType("UPDATE");

        assertThat(phase.shouldSkip(ctx)).isTrue();
    }

    private void givenPhysicalModel() {
        when(metaModelService.getTableName("dq_quote_request")).thenReturn("dq_quote_request");
        when(metaModelService.getPrimaryKeyField("dq_quote_request")).thenReturn(
                FieldDefinition.builder().code("pid").columnName("pid").build());
    }

    private CommandPipelineContext context(Integer expectedVersion) {
        CommandDefinition command = new CommandDefinition();
        command.setModelCode("dq_quote_request");
        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setTargetRecordPid("REQ-1");
        request.setExpectedVersion(expectedVersion);
        CommandPipelineContext ctx = CommandPipelineContext.builder()
                .commandCode("dq:release")
                .command(command)
                .request(request)
                .tenantId(41L)
                .userId(9L)
                .build();
        return ctx;
    }
}
