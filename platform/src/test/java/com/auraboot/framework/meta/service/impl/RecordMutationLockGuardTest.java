package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.security.RecordMutationLockDeclaration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Hermetic guard checks; database concurrency and runtime publication are separate gates. */
class RecordMutationLockGuardTest {
    private final DynamicDataMapper mapper = mock(DynamicDataMapper.class);
    private final ModelDefinition root = root();
    private final ModelDefinition child = child();
    private static ModelDefinition root() {
        return ModelDefinition.builder().code("receipt").tableName("mt_receipt")
                .fields(List.of(FieldDefinition.builder().code("request").columnName("request_pid").dataType("reference").build()))
                .extension(Map.of("recordMutationLock", Map.of("field", "request", "commands",
                        Map.of("create", List.of(), "update", List.of("receipt:request"), "delete", List.of())))).build();
    }
    private static ModelDefinition child() {
        var reference = new FieldDefinition.RefTarget();
        reference.setTargetEntity("receipt"); reference.setValueField("pid");
        return ModelDefinition.builder().code("receipt_line").tableName("mt_receipt_line")
                .fields(List.of(FieldDefinition.builder().code("receipt").columnName("receipt_pid")
                        .dataType("reference").refTarget(reference).build()))
                .extension(Map.of("parentModel", "receipt", "parentField", "receipt", "inheritParentMutationLock", true)).build();
    }
    @BeforeEach void context() {
        MetaContext.setContext(1L, 7L, null, "system");
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }
    @AfterEach void clear() {
        MetaContext.clear(); TransactionSynchronizationManager.setActualTransactionActive(false);
    }
    private void rootRow(Object claim) {
        Map<String,Object> row = new HashMap<>(); row.put("pid", "receipt-a"); row.put("mutation_lock_value", claim);
        when(mapper.selectRecordWriterTargetsForUpdate(eq("mt_receipt"), eq("request_pid"), anyString(), anyMap()))
                .thenReturn(List.of(row));
    }
    private void childRow() {
        when(mapper.selectRecordWriterTargetsForUpdate(eq("mt_receipt_line"), eq("receipt_pid"), anyString(), anyMap()))
                .thenReturn(List.of(Map.of("pid", "line-a", "mutation_lock_value", "receipt-a")));
    }
    private void existing(ModelDefinition model, String operation, Map<String,Object> input) {
        RecordMutationLockGuard.guardTargets(mapper, model, code -> root, operation, Map.of("pid", "target"), input);
    }
    @Test void storedClaimDeniesHeaderUpdateDeleteAndCannotBeClearedByPayload() {
        rootRow("request-a");
        for (String operation : List.of("update", "delete")) {
            assertThatThrownBy(() -> existing(root, operation, Map.of())).hasMessageContaining("RECORD_MUTATION_LOCKED");
        }
        Map<String,Object> clearing = new HashMap<>(); clearing.put("request", null);
        assertThatThrownBy(() -> existing(root, "update", clearing)).hasMessageContaining("RECORD_MUTATION_LOCKED");
    }
    @Test void unclaimedHeaderKeepsNormalMutationButCallerCannotAcquireClaim() {
        rootRow(null);
        assertThatCode(() -> existing(root, "update", Map.of())).doesNotThrowAnyException();
        assertThatCode(() -> existing(root, "delete", Map.of())).doesNotThrowAnyException();
        assertThatThrownBy(() -> RecordMutationLockGuard.assertInputAllowed(root, Map.of("request_pid", "forged"), "update"))
                .hasMessageContaining("RECORD_MUTATION_LOCKED");
    }
    @Test void ownerRequiresBothExactCommandAndPermit() {
        rootRow("request-a");
        MetaContext.runWithCommandPermitPlan("ALL", null, "receipt", "target", () -> {
            MetaContext.runWithAuthorizedCommandCode("receipt:other", () ->
                    assertThatThrownBy(() -> existing(root, "update", Map.of())).hasMessageContaining("RECORD_MUTATION_LOCKED"));
            MetaContext.runWithAuthorizedCommandCode("receipt:request", () -> {
                existing(root, "update", Map.of("request_pid", "request-a"));
                assertThatThrownBy(() -> existing(root, "delete", Map.of())).hasMessageContaining("RECORD_MUTATION_LOCKED");
            });
        });
    }
    @Test void claimedParentDeniesNewChildAndStoredChildUpdateDelete() {
        rootRow("request-a"); childRow();
        assertThatThrownBy(() -> RecordMutationLockGuard.guardParentInput(mapper, child, code -> root,
                Map.of("receipt", "receipt-a"))).hasMessageContaining("RECORD_MUTATION_LOCKED");
        for (String operation : List.of("update", "delete"))
            assertThatThrownBy(() -> existing(child, operation, Map.of())).hasMessageContaining("RECORD_MUTATION_LOCKED");
        verify(mapper, atLeastOnce()).selectRecordWriterTargetsForUpdate("mt_receipt", "request_pid", "mutation_lock_value",
                Map.of("tenant_id", 1L, "pid", "receipt-a"));
    }
    @Test void unclaimedParentAllowsChildAndReparentingChecksProposedParent() {
        rootRow(null); childRow();
        existing(child, "update", Map.of());
        RecordMutationLockGuard.guardParentInput(mapper, child, code -> root, Map.of("receipt_pid", "receipt-a"));
        rootRow("request-a");
        assertThatThrownBy(() -> existing(child, "update", Map.of("receipt", "receipt-b")))
                .hasMessageContaining("RECORD_MUTATION_LOCKED");
    }
    @Test void transactionTenantAndParentResolutionCannotBeOmitted() {
        rootRow(null);
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThatThrownBy(() -> existing(root, "update", Map.of())).hasMessageContaining("active tenant transaction");
        assertThatThrownBy(() -> RecordMutationLockGuard.guardParentInput(mapper, child, null, Map.of("receipt", "receipt-a")))
                .hasMessageContaining("Invalid recordMutationLock");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> RecordMutationLockGuard.guardTargets(mapper, root, code -> root, "update",
                Map.of("tenant_id", 2L, "pid", "target"), Map.of())).hasMessageContaining("RECORD_MUTATION_LOCKED");
    }
    @Test void physicalAliasesAndReferenceTargetAreValidated() {
        assertThatThrownBy(() -> RecordMutationLockGuard.guardParentInput(mapper, child, code -> root,
                Map.of("receipt", "a", "receipt_pid", "b"))).hasMessageContaining("Invalid recordMutationLock");
        child.getFields().get(0).getRefTarget().setValueField("id");
        assertThatThrownBy(() -> existing(child, "delete", Map.of())).hasMessageContaining("Invalid recordMutationLock");
    }
    @Test void malformedOrUnboundPoliciesFailAndOrdinaryModelsRemainUnaffected() {
        assertThatThrownBy(() -> RecordMutationLockDeclaration.validate(Map.of("field", "request")))
                .hasMessageContaining("Invalid recordMutationLock");
        root.getFields().get(0).setDataType("boolean");
        assertThatThrownBy(() -> RecordMutationLockGuard.validatePolicy(root)).hasMessageContaining("Invalid recordMutationLock");
        existing(ModelDefinition.builder().code("ordinary").build(), "update", Map.of());
        verifyNoInteractions(mapper);
    }
    @Test void missingStoredParentFailsClosedAndRelationsUseSameFrozenTargetCheck() {
        childRow();
        when(mapper.selectRecordWriterTargetsForUpdate(eq("mt_receipt"), anyString(), anyString(), anyMap()))
                .thenReturn(List.of());
        assertThatThrownBy(() -> existing(child, "delete", Map.of())).hasMessageContaining("RECORD_MUTATION_LOCKED");
        rootRow("request-a");
        assertThatThrownBy(() -> RecordMutationLockGuard.guardRelationTargets(mapper, child, code -> root,
                List.of("line-a"), "update")).hasMessageContaining("RECORD_MUTATION_LOCKED");
    }
}
