package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.exception.CasVersionConflictException;
import com.auraboot.framework.exception.CasVersionRequiredException;
import com.auraboot.framework.exception.ConflictException;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommandTargetVersionLockPhaseTest {

    @Mock private MetaModelService metaModelService;
    @Mock private DynamicDataMapper dynamicDataMapper;
    @Mock private ExtensionRegistry extensionRegistry;
    @Mock private ObjectProvider<ExtensionRegistry> extensionRegistryProvider;
    @Mock private CommandHandlerExtension pluginHandler;

    private CommandTargetVersionLockPhase phase;

    @BeforeEach
    void setUp() {
        phase = new CommandTargetVersionLockPhase(metaModelService, dynamicDataMapper, extensionRegistryProvider);
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void locksTheTenantScopedPidExclusivelyAndRetainsTheAuthoritativeVersion() {
        givenPhysicalModel();
        when(dynamicDataMapper.selectTargetVersionForUpdate("dq_quote_request", "pid", 41L, "REQ-1"))
                .thenReturn(List.of(Map.of("row_version", 7L)));
        CommandPipelineContext ctx = context(7);

        phase.execute(ctx);

        assertThat(ctx.getTargetRecordVersion()).isEqualTo(7L);
        verify(dynamicDataMapper).selectTargetVersionForUpdate("dq_quote_request", "pid", 41L, "REQ-1");
        verify(dynamicDataMapper, never()).selectByQueryWithoutTenant(anyString(), anyMap());
    }

    @Test
    void rejectsAStaleVersionAfterTheRowIsLocked() {
        givenPhysicalModel();
        when(dynamicDataMapper.selectTargetVersionForUpdate("dq_quote_request", "pid", 41L, "REQ-1"))
                .thenReturn(List.of(Map.of("row_version", 8L)));

        assertThatThrownBy(() -> phase.execute(context(7)))
                .isInstanceOf(CasVersionConflictException.class)
                .hasMessageContaining("expected 7")
                .hasMessageContaining("current 8");
    }

    @Test
    void failsClosedWhenTheTargetDisappearsOrHasNoVersion() {
        givenPhysicalModel();
        when(dynamicDataMapper.selectTargetVersionForUpdate("dq_quote_request", "pid", 41L, "REQ-1"))
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
        verify(dynamicDataMapper, never()).selectTargetVersionForUpdate(
                anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void omittedExpectedVersionDoesNotTakeARowLock() {
        CommandPipelineContext ctx = context(null);

        assertThat(phase.shouldSkip(ctx)).isTrue();
        verify(dynamicDataMapper, never()).selectTargetVersionForUpdate(
                anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void updateWithoutCasDeclaredProceedsVersionFree() {
        // Broad policy: CAS is opt-in per command; a plain update with no
        // casRequired executionConfig neither locks nor fails closed.
        CommandPipelineContext ctx = context(null);
        ctx.getRequest().setOperationType("UPDATE");

        assertThat(phase.shouldSkip(ctx)).isTrue();
        verify(dynamicDataMapper, never()).selectTargetVersionForUpdate(
                anyString(), anyString(), anyLong(), anyString());
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
        verify(dynamicDataMapper, never()).selectTargetVersionForUpdate(
                anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void malformedCasConfigFailsOpenRatherThanBlockingCommands() {
        CommandPipelineContext ctx = context(null);
        ctx.getCommand().setExecutionConfig("not-json");
        ctx.getRequest().setOperationType("UPDATE");

        assertThat(phase.shouldSkip(ctx)).isTrue();
    }

    @Test
    void currentStateIntentLocksExclusivelyAndUsesTheLatestVersion() {
        givenPhysicalModel();
        when(dynamicDataMapper.selectTargetVersionForUpdate("dq_quote_request", "pid", 41L, "REQ-1"))
                .thenReturn(List.of(Map.of("row_version", 12L)));
        CommandPipelineContext ctx = currentStateContext(7);

        assertThat(phase.shouldSkip(ctx)).isFalse();
        phase.execute(ctx);

        assertThat(ctx.getTargetRecordVersion()).isEqualTo(12L);
        verify(dynamicDataMapper).selectTargetVersionForUpdate("dq_quote_request", "pid", 41L, "REQ-1");
    }

    @Test
    void currentStateIntentStillLocksWhenTheClientOmitsItsVersion() {
        givenPhysicalModel();
        when(dynamicDataMapper.selectTargetVersionForUpdate("dq_quote_request", "pid", 41L, "REQ-1"))
                .thenReturn(List.of(Map.of("row_version", 12L)));
        CommandPipelineContext ctx = currentStateContext(null);
        assertThat(phase.shouldSkip(ctx)).isFalse();
        phase.execute(ctx);
        assertThat(ctx.getTargetRecordVersion()).isEqualTo(12L);
    }

    @Test
    void currentStateIntentFailsClosedWhenTheTargetDisappears() {
        givenPhysicalModel();
        when(dynamicDataMapper.selectTargetVersionForUpdate("dq_quote_request", "pid", 41L, "REQ-1"))
                .thenReturn(List.of());
        assertThatThrownBy(() -> phase.execute(currentStateContext(null)))
                .isInstanceOf(CasVersionConflictException.class);
    }

    @Test
    void currentStatePolicyCannotBypassVersionChecksOnAnOrdinaryEdit() {
        CommandPipelineContext ctx = context(7);
        ctx.getCommand().setExecutionConfig("""
                {"type":"update","options":{"targetVersionPolicy":"current_state"}}
                """);
        assertThatThrownBy(() -> phase.execute(ctx))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("state_transition");
        verify(dynamicDataMapper, never()).selectTargetVersionForUpdate(
                anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void currentStatePolicyRequiresExplicitStateBoundaries() {
        CommandPipelineContext ctx = context(7);
        ctx.getCommand().setExecutionConfig("""
                {"type":"state_transition","options":{"targetVersionPolicy":"current_state"}}
                """);
        assertThatThrownBy(() -> phase.execute(ctx))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fromStates");
        verify(dynamicDataMapper, never()).selectTargetVersionForUpdate(
                anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void unsupportedSuppliedVersionRejectsInShouldSkipAndDirectExecuteBeforeAnyDependency() {
        var ctx = unsupportedContext(7);
        ctx.getRequest().setOperationType("UPDATE");
        assertThatThrownBy(() -> phase.shouldSkip(ctx))
                .isInstanceOf(BusinessException.class)
                .hasMessage("$i18n:command.expected_version.unsupported")
                .satisfies(
                        error ->
                                assertThat(((BusinessException) error).getResponseCode())
                                        .isEqualTo(ResponseCode.BadParam));
        assertThatThrownBy(() -> phase.execute(ctx))
                .isInstanceOf(BusinessException.class)
                .hasMessage("$i18n:command.expected_version.unsupported");
        ctx.getRequest().setOperationType("QUERY");
        assertThatThrownBy(() -> phase.shouldSkip(ctx)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(
                metaModelService,
                dynamicDataMapper,
                extensionRegistry,
                extensionRegistryProvider,
                pluginHandler);
    }

    @Test
    void unsupportedWithoutVersionPreservesUpdateHintAndSkipsOnlyTheOuterTargetLock()
            throws Exception {
        var ctx = unsupportedContext(null);
        ctx.getRequest().setOperationType("UPDATE");
        when(extensionRegistryProvider.getIfAvailable()).thenReturn(extensionRegistry);
        when(extensionRegistry.getCommandHandler("dq:release"))
                .thenReturn(java.util.Optional.of(pluginHandler));
        assertThat(phase.shouldSkip(ctx)).isTrue();
        phase.execute(ctx);
        assertThat(ctx.getRequest().getOperationType()).isEqualTo("UPDATE");
        assertThat(ctx.getTargetRecordVersion()).isNull();
        verifyNoInteractions(metaModelService, dynamicDataMapper);
        verify(pluginHandler, never()).execute(any());
    }

    @Test
    void unsupportedCannotUseMissingChainedOrDslPersistingHandlers() throws Exception {
        var ctx = unsupportedContext(null);
        when(extensionRegistryProvider.getIfAvailable()).thenReturn(extensionRegistry);
        assertThatThrownBy(() -> phase.shouldSkip(ctx))
                .isInstanceOf(BusinessException.class)
                .hasMessage("$i18n:command.target_version_policy.invalid");
        when(extensionRegistry.getSecondaryCommandHandlers("dq:release"))
                .thenReturn(List.of(pluginHandler));
        assertThatThrownBy(() -> phase.execute(ctx)).isInstanceOf(BusinessException.class);
        when(extensionRegistry.getSecondaryCommandHandlers("dq:release")).thenReturn(List.of());
        when(extensionRegistry.getCommandHandler("dq:release"))
                .thenReturn(java.util.Optional.of(pluginHandler));
        when(pluginHandler.requiresDslPersistence(eq("dq:release"), any(), eq(ctx.getRequest())))
                .thenReturn(true);
        assertThatThrownBy(() -> phase.shouldSkip(ctx)).isInstanceOf(BusinessException.class);
        verify(pluginHandler, never()).execute(any());
        verifyNoInteractions(metaModelService, dynamicDataMapper);
    }

    @Test
    void unsupportedRejectsRuntimeBindingRulesAndAllNoncanonicalPolicyTypes() {
        var ctx = unsupportedContext(null);
        ctx.setRulesByType(
                Map.of("field_map", List.of(new com.auraboot.framework.meta.entity.BindingRule())));
        assertThatThrownBy(() -> phase.execute(ctx)).isInstanceOf(BusinessException.class);
        for (String value : List.of("true", "1", "null", "\"unknown\"")) {
            var invalid = unsupportedContext(null);
            invalid.getCommand()
                    .setExecutionConfig(
                            "{\"type\":\"custom\",\"handler\":\"dq:release\",\"options\":{\"targetVersionPolicy\":"
                                    + value
                                    + "}}");
            assertThatThrownBy(() -> phase.shouldSkip(invalid))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("$i18n:command.target_version_policy.invalid");
        }
        verifyNoInteractions(
                metaModelService,
                dynamicDataMapper,
                extensionRegistry,
                extensionRegistryProvider,
                pluginHandler);
    }

    @Test
    void unsupportedRejectsRuntimeCasMutationAsyncAndMalformedOptionsBeforeLocking() {
        for (String tail :
                List.of(
                        ",\"casRequired\":true",
                        ",\"casRequired\":\"false\"",
                        ",\"autoSetFields\":{}",
                        ",\"inputFields\":[]",
                        ",\"fieldMapping\":{}",
                        ",\"preActions\":[]",
                        ",\"stateField\":\"state\"",
                        ",\"handlerParams\":{\"async\":true}")) {
            var ctx = context(null);
            ctx.getCommand()
                    .setExecutionConfig(
                            "{\"type\":\"custom\",\"handler\":\"dq:release\",\"options\":{\"targetVersionPolicy\":\"unsupported\"}"
                                    + tail
                                    + "}");
            assertThatThrownBy(() -> phase.execute(ctx)).isInstanceOf(BusinessException.class);
        }
        var ctx = context(null);
        ctx.getCommand().setExecutionConfig("{\"options\":false}");
        assertThatThrownBy(() -> phase.shouldSkip(ctx)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(
                metaModelService,
                dynamicDataMapper,
                extensionRegistry,
                extensionRegistryProvider,
                pluginHandler);
    }

    @Test
    void unsupportedWithoutPluginRegistryFailsClosedWhileOrdinaryCasDoesNotNeedTheRegistry() {
        assertThat(phase.shouldSkip(context(null))).isTrue();
        verifyNoInteractions(extensionRegistryProvider);
        var ctx = unsupportedContext(null);
        assertThatThrownBy(() -> phase.shouldSkip(ctx))
                .isInstanceOf(BusinessException.class)
                .hasMessage("$i18n:command.target_version_policy.invalid");
        verifyNoInteractions(metaModelService, dynamicDataMapper, extensionRegistry, pluginHandler);
    }

    private CommandPipelineContext unsupportedContext(Integer version) {
        var ctx = context(version);
        ctx.getCommand()
                .setExecutionConfig(
                        "{\"type\":\"custom\",\"handler\":\"dq:release\",\"options\":{\"targetVersionPolicy\":\"unsupported\"}}");
        return ctx;
    }

    private CommandPipelineContext currentStateContext(Integer expectedVersion) {
        CommandPipelineContext ctx = context(expectedVersion);
        ctx.getCommand().setExecutionConfig("""
                {"type":"state_transition","stateField":"status",
                 "fromStates":["pending","running"],"toState":"cancelled",
                 "options":{"targetVersionPolicy":"current_state"}}
                """);
        return ctx;
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
