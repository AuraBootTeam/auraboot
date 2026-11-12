package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.security.NativeBusinessCommandContract;
import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.meta.service.impl.pipeline.*;
import com.auraboot.framework.meta.service.impl.pipeline.phases.IdempotencyPhase;
import com.auraboot.framework.permission.engine.model.*;
import com.auraboot.framework.permission.service.*;
import com.auraboot.framework.permission.service.impl.PermissionSnapshotCache;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NativeBusinessCreateBoundaryTest {
    static final String PID = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
    final DynamicDataService data = mock(DynamicDataService.class);
    final MetaModelService metadata = mock(MetaModelService.class);
    final FieldPermissionService fields = mock(FieldPermissionService.class);
    final DataPermissionEngine policies = mock(DataPermissionEngine.class);
    final BusinessCommandRecordAuthorization authorization = mock(BusinessCommandRecordAuthorization.class);
    final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    final ExtensionRegistry extensions = mock(ExtensionRegistry.class);
    final CommandResultProjectionService projection = new CommandResultProjectionService(metadata, fields, policies,
            mock(FieldMaskService.class), mock(PermissionSnapshotCache.class));
    final NativeBusinessCreateBoundary boundary = new NativeBusinessCreateBoundary(data, metadata, fields,
            policies, authorization, projection, extensions, transactions);
    CommandPipelineContext context;

    @BeforeEach void fixture() {
        MetaContext.setContext(7L, 20L, "actor", "Actor"); MetaContext.setMemberId(30L);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        var command = new CommandDefinition(); command.setCode("orders:create"); command.setModelCode("orders");
        var request = new CommandExecuteRequest(); request.setClientRequestId("one-create"); request.setPayload(Map.of("remark", "new"));
        context = CommandPipelineContext.builder().commandCode(command.getCode()).command(command).request(request)
                .tenantId(7L).userId(20L).payload(request.getPayload()).build();
        context.setExecConfig(Map.of("type", "create", "permissions", List.of("orders.manage"), "inputFields", List.of("remark"),
                "handlerParams", Map.of("targetRecordAuthorization", "business", "targetRecordAction", "create")));
        context.setAuthorizationVerdict(CommandAuthorizationVerdict.authorized("orders.manage"));
        context.setNativeCreationContract(NativeBusinessCommandContract.require(context, extensions));
        when(data.getById("orders", PID)).thenReturn(Map.of("pid", PID, "tenant_id", 7L, "created_by", 20L,
                "remark", "new", "db_default", 42));
        when(metadata.getModelFields("orders")).thenReturn(List.of(FieldDefinition.builder().code("remark").columnName("remark").build()));
        when(fields.getFieldPermissions(30L, "orders")).thenReturn(FieldPermissionSet.allAllowed(Set.of("remark")));
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        grant(true, true);
    }
    @AfterEach void clear() { MetaContext.clear(); TransactionSynchronizationManager.clear(); }
    void grant(boolean allowed, boolean writable) {
        var result = new BusinessGrantDecision(allowed ? PermissionResult.allow(List.of()) : PermissionResult.deny("revoked", List.of()),
                7L, 20L, 30L, "orders", PID, allowed ? List.of(new BusinessGrantDecision.Grant(1L, 2L, "orders.manage",
                new BusinessGrantDecision.Fields(false, Set.of(), writable ? Set.of("remark") : Set.of()))) : List.of());
        when(authorization.resolveNative(eq(30L), eq(List.of("orders.manage")), eq("orders"), anyMap(), eq("create"))).thenReturn(result);
    }
    Map<String, Object> outcome() { return Map.of("recordPid", PID, "orders_inserted", 1); }

    @Test void usesTheHostRowIncludingDatabaseDefaultsAndOnlyReturnsBoundedIdentity() {
        boundary.authorizeOutcome(context, outcome());
        verify(authorization).resolveNative(eq(30L), eq(List.of("orders.manage")), eq("orders"),
                argThat(row -> row.get("db_default").equals(42) && row.get("created_by").equals(20L)), eq("create"));
        verify(transactions).getTransaction(argThat(tx -> tx.isReadOnly()
                && tx.getIsolationLevel() == TransactionDefinition.ISOLATION_REPEATABLE_READ
                && tx.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        assertThat(context.getRequest().getTargetRecordId()).isEqualTo(PID);
        assertThat(projection.project(context, outcome())).isEqualTo(outcome());
        assertThat(MetaContext.hasCommandPermitScope()).isFalse();
    }
    @Test void deniedGrantOrFieldCannotProduceACompletedCreationWitness() {
        grant(false, false);
        assertThatThrownBy(() -> boundary.authorizeRecord(context, PID)).isInstanceOf(AccessDeniedException.class);
        assertThat(context.getBusinessTargetAuthorization()).isNull();
        grant(true, false);
        assertThatThrownBy(() -> boundary.authorizeRecord(context, PID)).isInstanceOf(AccessDeniedException.class);
        assertThat(context.getBusinessTargetAuthorization()).isNull();
    }
    @Test void sharedFieldRevocationCannotBeBypassedByAnOtherwiseMatchingGrant() {
        when(fields.getFieldPermissions(30L, "orders")).thenReturn(new FieldPermissionSet(Set.of("remark"), Set.of(), Set.of()));
        assertThatThrownBy(() -> boundary.authorizeRecord(context, PID)).isInstanceOf(AccessDeniedException.class);
    }
    @Test void replayRechecksTheCurrentRecordAndCompleteGrantBeforeShortCircuiting() {
        var idempotency = mock(IdempotencyService.class);
        when(idempotency.claimScopedIdempotency(anyString(), anyString(), anyMap(), eq(7L))).thenReturn(outcome());
        var phase = new IdempotencyPhase(idempotency); ReflectionTestUtils.setField(phase, "nativeCreation", boundary);
        grant(false, false);
        assertThatThrownBy(() -> phase.execute(context)).isInstanceOf(AccessDeniedException.class);
        assertThat(context.isShortCircuited()).isFalse();
        grant(true, true); phase.execute(context);
        assertThat(context.isShortCircuited()).isTrue();
        assertThat(context.getShortCircuitResult().getData()).isEqualTo(outcome());
    }
    @Test void missingBoundaryCannotReplayEvenAValidCachedCreation() {
        var idempotency = mock(IdempotencyService.class);
        when(idempotency.claimScopedIdempotency(anyString(), anyString(), anyMap(), eq(7L))).thenReturn(outcome());
        assertThatThrownBy(() -> new IdempotencyPhase(idempotency).execute(context)).isInstanceOf(AccessDeniedException.class);
        assertThat(context.isShortCircuited()).isFalse();
    }
    @Test void finalValidationDoesNotReuseAnEarlierAllowedDecision() {
        boundary.authorizeRecord(context, PID); grant(false, false);
        assertThatThrownBy(() -> boundary.authorizeOutcome(context, outcome())).isInstanceOf(AccessDeniedException.class);
    }
    @Test void extraOutputWrongCountsAndUnknownInputsFailClosed() {
        assertThatThrownBy(() -> boundary.authorizeOutcome(context, Map.of("recordPid", PID, "orders_inserted", 2)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> boundary.authorizeOutcome(context, Map.of("recordPid", PID, "orders_inserted", 1, "cost", 99)))
                .isInstanceOf(AccessDeniedException.class);
        context.setPayload(Map.of("created_by", 999L));
        assertThatThrownBy(() -> boundary.authorizeRecord(context, PID)).isInstanceOf(AccessDeniedException.class);
    }
    @Test void publishedCreationInputsCannotExposeSystemOwnershipFields() {
        var config = new HashMap<>(context.getExecConfig()); config.put("inputFields", List.of("created_by"));
        context.setExecConfig(config);
        assertThatThrownBy(() -> NativeBusinessCommandContract.require(context, extensions))
                .isInstanceOf(com.auraboot.framework.exception.BusinessException.class);
    }
    @Test void noWriteTransactionOrAnInheritedBroadPermitCannotAuthorizeCreation() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThatThrownBy(() -> boundary.authorizeRecord(context, PID)).isInstanceOf(AccessDeniedException.class);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> MetaContext.runWithCommandPermitScope("ALL", () -> {
            boundary.authorizeRecord(context, PID); return true;
        })).isInstanceOf(AccessDeniedException.class);
    }
}
