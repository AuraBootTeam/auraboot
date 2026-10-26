package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.IdempotencyService;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPermitPlan;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipeline;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.permission.engine.model.PermissionResult;
import com.auraboot.framework.permission.service.PermissionFacade;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommandTargetScopePhaseTest {

    private static final Map<String, Object> RECORD =
            Map.of("pid", "REC-1", "owner_id", 7L, "row_version", 13L);

    @Mock private DynamicDataService dynamicDataService;
    @Mock private ApplicationContext applicationContext;
    @Mock private PermissionFacade permissionFacade;
    @Mock private TenantMemberService tenantMemberService;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private TransactionStatus transactionStatus;
    @Mock private IdempotencyService idempotencyService;
    @Mock private ExtensionRegistry extensionRegistry;
    @Mock private CommandHandlerExtension commandHandler;

    /**
     * A cached response is data too. Revoking the caller's row scope must stop the request before
     * the idempotency ledger can reveal the old result.
     */
    @Test
    void unreadableTargetCannotReplayACachedResult() {
        CommandTargetScopePhase targetScope = phase();
        givenRecordIsReadable(false);
        CommandPipelineContext ctx = context("qo_quote_common", "REC-1");
        ctx.getRequest().setClientRequestId("known-key");
        CommandPipeline pipeline = new CommandPipeline(
                List.of(targetScope),
                List.of(new IdempotencyPhase(idempotencyService)));

        assertThatThrownBy(() -> {
            if (pipeline.executePreGuardPhases(ctx) == null) {
                pipeline.executeGuardedPhases(ctx);
            }
        }).isInstanceOf(BusinessException.class)
                .hasMessageContaining("do not have permission to view this record");

        verifyNoInteractions(idempotencyService);
    }

    @Test
    void targetDenialIsAlwaysEnforced() {
        CommandTargetScopePhase phase = phase();
        givenRecordIsReadable(false);
        CommandPipelineContext ctx = context("qo_quote_common", "REC-1");

        assertThatThrownBy(() -> phase.execute(ctx))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("do not have permission to view this record");

        assertThat(ctx.getTargetRecordReadable()).isFalse();
    }

    @Test
    void recordsThatTheCallerCanSeeTheirOwnTarget() {
        CommandTargetScopePhase phase = phase();
        givenRecordIsReadable(true);
        CommandPipelineContext ctx = context("qo_quote_common", "REC-1");

        phase.execute(ctx);

        assertThat(ctx.getTargetRecordReadable()).isTrue();
        assertThat(ctx.getTargetRecordVersion())
                .as("D5 uses the server-loaded target version, not the client's payload")
                .isEqualTo(13L);
    }

    @Test
    void defersAStaleClientVersionUntilAfterEveryAuthorizationGate() {
        CommandTargetScopePhase phase = phase();
        givenRecordIsReadable(true);
        CommandPipelineContext ctx = context("qo_quote_common", "REC-1");
        ctx.getRequest().setExpectedVersion(12);

        phase.execute(ctx);

        assertThat(ctx.getTargetRecordVersion()).isEqualTo(13L);
    }

    @Test
    void acceptsAMatchingClientVersionAndRetainsTheAuthoritativeVersion() {
        CommandTargetScopePhase phase = phase();
        givenRecordIsReadable(true);
        CommandPipelineContext ctx = context("qo_quote_common", "REC-1");
        ctx.getRequest().setExpectedVersion(13);

        phase.execute(ctx);

        assertThat(ctx.getTargetRecordVersion()).isEqualTo(13L);
    }

    @Test
    void leavesAnUnversionedBoundaryReadForTheTransactionalLockToReject() {
        CommandTargetScopePhase phase = phase();
        when(dynamicDataService.getById(anyString(), anyString()))
                .thenReturn(Map.of("pid", "REC-1", "owner_id", 7L));
        givenPermission(true);
        CommandPipelineContext ctx = context("qo_quote_common", "REC-1");
        ctx.getRequest().setExpectedVersion(1);

        phase.execute(ctx);

        assertThat(ctx.getTargetRecordVersion()).isNull();
    }

    /**
     * The boundary has to SEE the row to judge it. Reading it through the very projection under
     * evaluation would deny the read for exactly the callers this check exists to evaluate, and the
     * phase would answer "unreadable" for everyone — an assertion that is true for the wrong reason.
     */
    @Test
    void readsTheTargetOutsideTheCallersOwnProjection() {
        CommandTargetScopePhase phase = phase();
        AtomicBoolean allPermitDuringRead = new AtomicBoolean(false);
        when(dynamicDataService.getById(anyString(), anyString())).thenAnswer(invocation -> {
            allPermitDuringRead.set("ALL".equals(MetaContext.getCommandPermitScope()));
            return RECORD;
        });
        givenPermission(true);

        phase.execute(context("qo_quote_common", "REC-1"));

        assertThat(allPermitDuringRead).isTrue();
        assertThat(MetaContext.getCommandPermitScope())
                .as("the explicit ALL plan must not outlive the boundary read")
                .isNull();
    }

    @Test
    void skipsWhenTheRequestNamesNoTargetRecord() {
        CommandTargetScopePhase phase = phase();

        assertThat(phase.shouldSkip(context("qo_quote_common", null))).isTrue();
    }

    @Test
    void skipsWhenTheCommandHasNoModel() {
        CommandTargetScopePhase phase = phase();

        assertThat(phase.shouldSkip(context(null, "REC-1"))).isTrue();
    }

    @Test
    void targetedCommandCannotSkipTheGate() {
        CommandTargetScopePhase phase = phase();

        assertThat(phase.shouldSkip(context("qo_quote_common", "REC-1"))).isFalse();
        verifyNoInteractions(dynamicDataService);
    }

    @Test
    void skipsDynamicTargetGateWhenTheHandlerOwnsPersistence() {
        CommandTargetScopePhase phase = phase();
        CommandPipelineContext ctx = context("framework_record", "REC-1");
        when(extensionRegistry.getCommandHandler(ctx.getCommandCode()))
                .thenReturn(Optional.of(commandHandler));
        when(commandHandler.requiresDslPersistence(
                ctx.getCommandCode(), ctx.getExecConfig(), ctx.getRequest())).thenReturn(false);

        assertThat(phase.shouldSkip(ctx)).isTrue();
        assertThat(ctx.isHasPluginHandler()).isTrue();
        assertThat(ctx.isPluginRequiresDslPersistence()).isFalse();
        verifyNoInteractions(dynamicDataService);
    }

    /**
     * An authorization read that cannot be evaluated must fail closed and roll back its isolated
     * transaction before the error reaches the outer command transaction.
     */
    @Test
    void lookupFailureFailsClosedAndRollsBackTheBoundaryRead() {
        CommandTargetScopePhase phase = phase();
        givenMember();
        when(dynamicDataService.getById(anyString(), anyString()))
                .thenThrow(new BusinessException("Record not found: 1 in model: dsl_t_1"));
        CommandPipelineContext ctx = context("qo_quote_common", "1");

        assertThatThrownBy(() -> phase.execute(ctx)).isInstanceOf(BusinessException.class);

        assertThat(ctx.getTargetRecordReadable()).isNull();
        verify(transactionManager).rollback(transactionStatus);
    }

    @Test
    void evaluatesTheBoundaryReadInARequiresNewTransaction() {
        CommandTargetScopePhase phase = phase();
        givenRecordIsReadable(true);

        phase.execute(context("qo_quote_common", "REC-1"));

        verify(transactionManager).getTransaction(argThat(definition ->
                definition.getPropagationBehavior()
                        == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        verify(transactionManager).commit(transactionStatus);
    }

    /** A missing record is a "not found" answer for a later phase, not an authorization verdict. */
    @Test
    void leavesTheVerdictUnsetWhenTheTargetDoesNotExist() {
        CommandTargetScopePhase phase = phase();
        when(dynamicDataService.getById(anyString(), anyString())).thenReturn(null);
        givenMember();
        CommandPipelineContext ctx = context("qo_quote_common", "REC-1");

        phase.execute(ctx);

        assertThat(ctx.getTargetRecordReadable()).isNull();
    }

    // ---------- permit-plan accumulator (phase-1 §11.15 step 2, shadow) ----------

    @Test
    void recordsADenyPhaseDecisionWhenTheCallerCannotSeeTheTarget() {
        CommandTargetScopePhase phase = phase();
        givenRecordIsReadable(false);
        CommandPipelineContext ctx = context("qo_quote_common", "REC-1");

        assertThatThrownBy(() -> phase.execute(ctx)).isInstanceOf(BusinessException.class);

        assertThat(ctx.getPhaseDecisions()).singleElement().satisfies(d -> {
            assertThat(d.decision()).isEqualTo(CommandPermitPlan.Decision.DENY);
            assertThat(d.reasonCode()).isEqualTo(CommandTargetScopePhase.REASON_TARGET_NOT_READABLE);
            assertThat(d.phaseName()).isEqualTo("target_scope");
        });
    }

    /** The decision is recorded before the throw aborts the pipeline. */
    @Test
    void recordsTheDenyPhaseDecisionBeforeThrowing() {
        CommandTargetScopePhase phase = phase();
        givenRecordIsReadable(false);
        CommandPipelineContext ctx = context("qo_quote_common", "REC-1");

        assertThatThrownBy(() -> phase.execute(ctx)).isInstanceOf(BusinessException.class);

        assertThat(ctx.getPhaseDecisions()).singleElement()
                .extracting(CommandPermitPlan.PhaseDecision::decision)
                .isEqualTo(CommandPermitPlan.Decision.DENY);
    }

    /**
     * A readable target makes this phase abstain, not permit: it is a gate, not a granter. Only the
     * RBAC capability phase grants. If a readable target permitted here, an undeclared command on any
     * record the caller can see would combine to an authorized plan — conflating "can see" with "may
     * act", the projection the 2026-07-22 incident came from.
     */
    @Test
    void abstainsRatherThanPermitsWhenTheCallerCanSeeTheTarget() {
        CommandTargetScopePhase phase = phase();
        givenRecordIsReadable(true);
        CommandPipelineContext ctx = context("qo_quote_common", "REC-1");

        phase.execute(ctx);

        assertThat(ctx.getPhaseDecisions()).singleElement()
                .extracting(CommandPermitPlan.PhaseDecision::decision)
                .isEqualTo(CommandPermitPlan.Decision.ABSTAIN);
    }

    @Test
    void recordsAnAbstainPhaseDecisionWhenTheTargetDoesNotExist() {
        CommandTargetScopePhase phase = phase();
        when(dynamicDataService.getById(anyString(), anyString())).thenReturn(null);
        givenMember();
        CommandPipelineContext ctx = context("qo_quote_common", "REC-1");

        phase.execute(ctx);

        assertThat(ctx.getPhaseDecisions()).singleElement()
                .extracting(CommandPermitPlan.PhaseDecision::decision)
                .isEqualTo(CommandPermitPlan.Decision.ABSTAIN);
    }

    @Test
    void authenticatedCallerWithoutTenantMembershipFailsClosed() {
        CommandTargetScopePhase phase = phase();
        when(dynamicDataService.getById(anyString(), anyString())).thenReturn(RECORD);
        when(applicationContext.getBean(TenantMemberService.class)).thenReturn(tenantMemberService);
        when(tenantMemberService.findByTenantIdAndUserId(1L, 42L)).thenReturn(null);

        assertThatThrownBy(() -> phase.execute(context("qo_quote_common", "REC-1")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("active tenant membership is required");

        verifyNoInteractions(permissionFacade);
    }

    @Test
    void explicitWritePolicyRejectsReadOnlyShareBeforeHandlerDispatch() {
        MetaContext.setContext(1L, 42L, "caller-pid", "Caller");
        CommandTargetScopePhase phase = phase();
        givenRecordIsReadable(true);
        var scope = org.mockito.Mockito.mock(com.auraboot.framework.permission.engine.evaluator.DataScopeEvaluator.class);
        var shares = org.mockito.Mockito.mock(com.auraboot.framework.permission.service.RecordShareService.class);
        when(applicationContext.getBean(com.auraboot.framework.permission.engine.evaluator.DataScopeEvaluator.class)).thenReturn(scope);
        when(applicationContext.getBean(com.auraboot.framework.permission.service.RecordShareService.class)).thenReturn(shares);
        when(scope.evaluate(eq(99L), eq("qo_quote_common"), eq("read"), any())).thenReturn(
                new com.auraboot.framework.permission.engine.model.EvaluationStep("DataScope",
                        com.auraboot.framework.permission.engine.model.EvaluationVerdict.DENY, "Not owner"));
        var ctx = context("qo_quote_common", "REC-1");
        ctx.setExecConfig(Map.of("handlerParams", Map.of("targetRecordAction", "update")));
        assertThat(phase.shouldSkip(ctx)).isFalse();
        assertThatThrownBy(() -> phase.execute(ctx)).isInstanceOf(BusinessException.class);
        when(shares.isSharedByPid(eq(1L), eq("qo_quote_common"), eq("REC-1"), eq(99L), any(), eq("update")))
                .thenReturn(true);
        phase.execute(ctx);
        assertThat(ctx.getTargetRecordReadable()).isTrue();
    }

    @Test
    void explicitReadPolicyDoesNotRequireAnUpdateShare() {
        CommandTargetScopePhase phase = phase();
        givenRecordIsReadable(true);
        var ctx = context("qo_quote_common", "REC-1");
        ctx.setExecConfig(Map.of("handlerParams", Map.of("targetRecordAction", "read")));
        phase.execute(ctx);
        assertThat(ctx.getTargetRecordReadable()).isTrue();
    }

    @Test void declaredWriteScopeIsCheckedBeforeIdempotentReplayEvenWhenReadScopeIsAll() {
        MetaContext.setContext(1L,42L,"caller-pid","Caller");
        givenRecordIsReadable(true);
        var scope=org.mockito.Mockito.mock(com.auraboot.framework.permission.engine.evaluator.DataScopeEvaluator.class);
        when(applicationContext.getBean(com.auraboot.framework.permission.engine.evaluator.DataScopeEvaluator.class)).thenReturn(scope);
        when(applicationContext.getBean(com.auraboot.framework.permission.service.RecordShareService.class))
                .thenReturn(org.mockito.Mockito.mock(com.auraboot.framework.permission.service.RecordShareService.class));
        when(scope.evaluate(eq(99L),eq("qo_quote_common"),eq("read"),any())).thenReturn(
                new com.auraboot.framework.permission.engine.model.EvaluationStep("DataScope",
                        com.auraboot.framework.permission.engine.model.EvaluationVerdict.NOT_APPLICABLE,"Scope all"));
        when(scope.evaluate(eq(99L),eq("qo_quote_common"),eq("update"),any())).thenReturn(
                new com.auraboot.framework.permission.engine.model.EvaluationStep("DataScope",
                        com.auraboot.framework.permission.engine.model.EvaluationVerdict.DENY,"Update scope none"));
        var ctx=context("qo_quote_common","REC-1");
        ctx.setExecConfig(Map.of("handlerParams",Map.of("targetRecordAction","update")));
        ctx.getRequest().setClientRequestId("previously-successful-key");
        var pipeline=new CommandPipeline(List.of(phase()),List.of(new IdempotencyPhase(idempotencyService)));
        assertThatThrownBy(() -> {
            if(pipeline.executePreGuardPhases(ctx)==null) pipeline.executeGuardedPhases(ctx);
        }).isInstanceOf(BusinessException.class);
        verifyNoInteractions(idempotencyService);
    }

    @Test void unavailableWriteScopeCannotBecomeAnAllowDecision() {
        givenRecordIsReadable(true);
        when(applicationContext.getBean(com.auraboot.framework.permission.engine.evaluator.DataScopeEvaluator.class))
                .thenReturn(org.mockito.Mockito.mock(com.auraboot.framework.permission.engine.evaluator.DataScopeEvaluator.class));
        var ctx=context("qo_quote_common","REC-1");
        ctx.setExecConfig(Map.of("handlerParams",Map.of("targetRecordAction","update")));
        assertThatThrownBy(() -> phase().execute(ctx)).isInstanceOf(BusinessException.class);
    }

    @Test void readShareAuthorizationCannotAuthorizeAnExplicitUpdateTarget() {
        givenRecordIsReadable(true);
        var ctx=context("qo_quote_common","REC-1");
        ctx.setExecConfig(Map.of("handlerParams",Map.of("targetRecordAction","update")));
        ctx.setAuthorizationVerdict(com.auraboot.framework.meta.service.impl.pipeline.CommandAuthorizationVerdict.authorized(
                "record-share:qo_quote_common:read"));
        assertThatThrownBy(() -> phase().execute(ctx)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(permissionFacade);
    }

    @Test void updateShareAuthorizationCannotAuthorizeAnExplicitDeleteTarget() {
        givenRecordIsReadable(true);
        var ctx=context("qo_quote_common","REC-1");
        ctx.setExecConfig(Map.of("handlerParams",Map.of("targetRecordAction","delete")));
        ctx.setAuthorizationVerdict(com.auraboot.framework.meta.service.impl.pipeline.CommandAuthorizationVerdict.authorized(
                "record-share:qo_quote_common:update"));
        assertThatThrownBy(() -> phase().execute(ctx)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(permissionFacade);
    }

    @org.junit.jupiter.api.AfterEach
    void clearSharingContext() { MetaContext.clear(); }

    private CommandTargetScopePhase phase() {
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(transactionStatus);
        return new CommandTargetScopePhase(
                dynamicDataService, applicationContext, transactionManager, extensionRegistry);
    }

    private CommandPipelineContext handlerTarget() {
        givenMember();
        when(dynamicDataService.getById(anyString(), anyString())).thenReturn(RECORD);
        var ctx=context("supplier_quote", "REC-1");
        ctx.setExecConfig(Map.of("handlerParams", Map.of("targetRecordAction","update",
                "targetRecordAuthorization","handler")));
        ctx.setAuthorizationVerdict(com.auraboot.framework.meta.service.impl.pipeline.CommandAuthorizationVerdict.authorized("supplier.use"));
        when(extensionRegistry.getCommandHandler(ctx.getCommandCode())).thenReturn(Optional.of(commandHandler));
        when(commandHandler.requiresDslPersistence(ctx.getCommandCode(),ctx.getExecConfig(),ctx.getRequest())).thenReturn(false);
        MetaContext.setContext(1L,42L,"supplier-user","supplier");
        return ctx;
    }

    @Test void handlerTargetAuthorizationKeepsVersionWithoutGrantingRawModelRead() {
        var ctx=handlerTarget();
        when(commandHandler.authorizeTarget(any())).thenAnswer(invocation -> {
            var target=(CommandHandlerExtension.TargetAuthorizationContext)invocation.getArgument(0);
            assertThat(target.currentUserPid()).isEqualTo("supplier-user");
            assertThat(target.tenantId()).isEqualTo(1L);
            assertThat(target.action()).isEqualTo("update");
            assertThat(target.recordId()).isEqualTo("REC-1");
            assertThat(target.records()).isNotInstanceOf(com.auraboot.framework.plugin.extension.DataAccessor.class);
            assertThat(target.records().getById("supplier","company-1")).isEqualTo(RECORD);
            return true;
        });
        phase().execute(ctx);
        assertThat(ctx.getTargetRecordVersion()).isEqualTo(13L);
        assertThat(ctx.getTargetRecordReadable()).isTrue();
        verifyNoInteractions(permissionFacade);
    }

    @Test void revokedHandlerTargetCannotReplayKnownRequest() {
        var ctx=handlerTarget();ctx.getRequest().setClientRequestId("known-supplier-key");
        when(commandHandler.authorizeTarget(any())).thenThrow(new SecurityException("membership revoked"));
        var pipeline=new CommandPipeline(List.of(phase()),List.of(new IdempotencyPhase(idempotencyService)));
        assertThatThrownBy(() -> {
            if(pipeline.executePreGuardPhases(ctx)==null) pipeline.executeGuardedPhases(ctx);
        }).isInstanceOf(BusinessException.class);
        verifyNoInteractions(idempotencyService);
        assertThat(ctx.getTargetRecordReadable()).isFalse();
    }

    @Test void handlerTargetRequiresDeclaredPermissionAndActiveHostIdentity() {
        var ctx=handlerTarget();ctx.setAuthorizationVerdict(null);
        assertThatThrownBy(() -> phase().execute(ctx)).isInstanceOf(BusinessException.class);
        verify(commandHandler,org.mockito.Mockito.never()).authorizeTarget(any());
    }

    @Test void missingHandlerTargetFailsBeforeReplay() {
        var ctx=handlerTarget();when(dynamicDataService.getById(anyString(),anyString())).thenReturn(null);
        assertThatThrownBy(() -> phase().execute(ctx)).isInstanceOf(BusinessException.class);
        verify(commandHandler,org.mockito.Mockito.never()).authorizeTarget(any());
        verifyNoInteractions(idempotencyService);
    }

    @Test void tenantScopedMissingHandlerTargetUsesTheSameForbiddenBoundaryAsNull() {
        var ctx=handlerTarget();
        when(dynamicDataService.getById(anyString(),anyString())).thenThrow(
                new com.auraboot.framework.meta.exception.MetaRecordNotFoundException("s2p_bid","foreign-quote"));
        assertThatThrownBy(() -> phase().execute(ctx)).isInstanceOf(BusinessException.class)
                .hasMessage("Access denied: you do not have permission to view this record");
        assertThat(ctx.getTargetRecordReadable()).isFalse();
        verify(commandHandler,org.mockito.Mockito.never()).authorizeTarget(any());
        verifyNoInteractions(permissionFacade,idempotencyService);
    }

    @Test void ordinaryMissingRecordPreservesItsOriginalLookupFailure() {
        var ctx=context("qo_quote_common","missing");
        var missing=new com.auraboot.framework.meta.exception.MetaRecordNotFoundException("qo_quote_common","missing");
        when(dynamicDataService.getById(anyString(),anyString())).thenThrow(missing);
        assertThatThrownBy(() -> phase().execute(ctx)).isSameAs(missing);
        assertThat(ctx.getTargetRecordReadable()).isNull();
        verify(transactionManager).rollback(transactionStatus);
    }

    @Test void handlerTargetStorageFailureCannotFallBackToOrdinaryRead() {
        var ctx=handlerTarget();when(commandHandler.authorizeTarget(any())).thenThrow(new IllegalStateException("storage offline"));
        assertThatThrownBy(() -> phase().execute(ctx)).isInstanceOf(IllegalStateException.class).hasMessage("storage offline");
        verifyNoInteractions(permissionFacade);
    }

    @Test
    void machineTargetUsesItsExplicitDecisionRegardlessOfHumanMembership() {
        givenRecordIsReadable(false);
        MetaContext.setContext(1L, null, "token", "machine");
        MetaContext.setMemberId(99L);
        var principal = new com.auraboot.framework.application.security.ExternalMachineAuthenticator.MachinePrincipal(
                1L, "token", "app", java.util.Set.of(), "app", "install", "staging", "token");
        var decision = new com.auraboot.framework.application.security.MachineDataAccessContext.Decision(
                1L, "install", "qo_quote_common", java.util.Set.of("update"), java.util.Set.of("pid", "name"), java.util.Set.of("name"),
                List.of(new com.auraboot.framework.application.security.MachineDataAccessContext.Constraint("pid", "pid", List.of("REC-1"))));
        try (var machine = com.auraboot.framework.application.security.MachineDataAccessContext.begin(principal);
             var approved = com.auraboot.framework.application.security.MachineDataAccessContext.authorize(Map.of("qo_quote_common", decision))) {
            var ctx = context("qo_quote_common", "REC-1"); ctx.setExecConfig(Map.of("type", "state_transition"));
            phase().execute(ctx);
            assertThat(ctx.getTargetRecordReadable()).isTrue();
            assertThat(ctx.getTargetRecordVersion()).isEqualTo(13L);
            verifyNoInteractions(permissionFacade);
        }
    }

    @Test
    void springOwnerReauthorizesTombstoneBeforeReplay() {
        var owner = nativeOwner();
        var ctx = nativeTarget();
        ctx.getRequest().setPayload(Map.of("snapshotToken", "bound-capture"));
        when(owner.authorizeTarget(any())).thenReturn(4L);
        phase().execute(ctx);
        assertThat(ctx.getTargetRecordVersion()).isEqualTo(4L);
        assertThat(ctx.getTargetRecordReadable()).isTrue();
        verify(owner).authorizeTarget(argThat(input -> input.getTargetRecordId().equals("REC-1")
                && input.getTenantId().equals(1L) && input.getUserId().equals(42L)
                && input.getPayload().equals(Map.of("snapshotToken", "bound-capture"))));
        verifyNoInteractions(dynamicDataService);
        MetaContext.clear();
    }

    @Test
    void springOwnerRevocationStopsCachedReplay() {
        var owner = nativeOwner();
        var ctx = nativeTarget();
        when(owner.authorizeTarget(any())).thenThrow(new org.springframework.security.access.AccessDeniedException("revoked"));
        var pipeline = new CommandPipeline(List.of(phase()),List.of(new IdempotencyPhase(idempotencyService)));
        try {
            assertThatThrownBy(() -> {
                if (pipeline.executePreGuardPhases(ctx) == null) pipeline.executeGuardedPhases(ctx);
            }).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            verifyNoInteractions(idempotencyService, dynamicDataService);
        } finally { MetaContext.clear(); }
    }

    private NativeTargetOwner nativeOwner() {
        NativeTargetOwner owner=org.mockito.Mockito.mock(NativeTargetOwner.class);
        when(applicationContext.getBeansOfType(com.auraboot.framework.meta.service.CommandHandler.class))
                .thenReturn(Map.of("nativeOwner",owner));
        return owner;
    }
    private CommandPipelineContext nativeTarget() {
        MetaContext.setContext(1L,42L,"user-42","author");
        var ctx=context("ab_announcement","REC-1");
        var rule=new com.auraboot.framework.meta.entity.BindingRule();rule.setHandlerClass("nativeOwner");
        ctx.setRulesByType(Map.of("handler",List.of(rule)));
        ctx.setAuthorizationVerdict(com.auraboot.framework.meta.service.impl.pipeline.CommandAuthorizationVerdict.authorized("notice.delete"));
        ctx.getRequest().setClientRequestId("committed-operation");
        return ctx;
    }
    interface NativeTargetOwner extends com.auraboot.framework.meta.service.CommandHandler,
            com.auraboot.framework.meta.service.CommandTargetAuthorizer {}

    private void givenRecordIsReadable(boolean readable) {
        when(dynamicDataService.getById(anyString(), anyString())).thenReturn(RECORD);
        givenPermission(readable);
    }

    private void givenPermission(boolean granted) {
        givenMember();
        when(applicationContext.getBean(PermissionFacade.class)).thenReturn(permissionFacade);
        when(permissionFacade.canOperate(eq(99L), anyString(), eq("read"), any()))
                .thenReturn(granted
                        ? PermissionResult.allow(List.of())
                        : PermissionResult.deny("Scope: self", List.of()));
    }

    private void givenMember() {
        TenantMember member = new TenantMember();
        member.setId(99L);
        when(applicationContext.getBean(TenantMemberService.class)).thenReturn(tenantMemberService);
        when(tenantMemberService.findByTenantIdAndUserId(1L, 42L)).thenReturn(member);
    }

    private CommandPipelineContext context(String modelCode, String targetRecordId) {
        CommandDefinition command = new CommandDefinition();
        command.setModelCode(modelCode);
        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setTargetRecordId(targetRecordId);

        CommandPipelineContext ctx = CommandPipelineContext.builder()
                .commandCode("qo_quote_common:batch_source_prices")
                .request(request)
                .tenantId(1L)
                .userId(42L)
                .startTime(System.currentTimeMillis())
                .build();
        ctx.setCommand(command);
        return ctx;
    }
}
