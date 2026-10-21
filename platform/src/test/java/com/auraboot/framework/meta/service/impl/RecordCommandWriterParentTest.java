package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Hermetic policy checks; real PostgreSQL writer outcomes are covered by the adjacent IT. */
class RecordCommandWriterParentTest {
    private final DynamicDataMapper mapper = mock(DynamicDataMapper.class);
    static ModelDefinition parentModel(String table) {
        return ModelDefinition.builder().code("order").tableName(table)
            .fields(List.of(FieldDefinition.builder().code("managed").columnName("managed_flag")
                .dataType("boolean").immutable(true).build()))
            .extension(Map.of("recordCommandWriters", Map.of("field", "managed", "commands",
                Map.of("create", List.of("app:create"), "update", List.of("app:save"), "delete", List.of())))).build();
    }
    static ModelDefinition childModel(String table) {
        var reference = new FieldDefinition.RefTarget();
        reference.setTargetEntity("order"); reference.setValueField("pid");
        return ModelDefinition.builder().code("line").tableName(table)
            .fields(List.of(FieldDefinition.builder().code("managed").columnName("managed_flag")
                .dataType("boolean").immutable(true).build(),
                FieldDefinition.builder().code("order_link").columnName("order_pid").refTarget(reference).build()))
            .extension(Map.of("parentModel", "order", "parentField", "order_link",
                "recordCommandWriters", Map.of("field", "managed", "commands",
                Map.of("create", List.of("app:create"), "update", List.of("app:save", "app:line_only"), "delete", List.of())))).build();
    }
    private final ModelDefinition parent = parentModel("mt_parent");
    private final ModelDefinition child = childModel("mt_child");
    @BeforeEach void context() {
        MetaContext.setContext(1L, 7L, null, "system");
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }
    @AfterEach void clear() { MetaContext.clear(); TransactionSynchronizationManager.setActualTransactionActive(false); }
    private void input(Map<String,Object> values) { RecordCommandWriterGuard.guardParentInput(mapper, child, code -> parent, values); }
    @Test void unmarkedChildCannotAttachToManagedParent() {
        when(mapper.selectRecordWriterTargetsForUpdate(anyString(), anyString(), anyString(), anyMap()))
            .thenReturn(List.of(Map.of("managed", true)));
        assertThatThrownBy(() -> input(Map.of("order_link", "owned", "managed", false))).hasMessageContaining("RECORD_WRITER_DENIED");
        verify(mapper).selectRecordWriterTargetsForUpdate("mt_parent", "managed_flag", "managed", Map.of("tenant_id", 1L, "pid", "owned"));
    }
    @Test void ordinaryParentUsesExactTenantPidWithoutSoftDeleteColumn() {
        when(mapper.selectRecordWriterTargetsForUpdate(anyString(), anyString(), anyString(), anyMap()))
            .thenReturn(List.of(Map.of("managed", false)));
        input(Map.of("order_pid", "ordinary"));
        verify(mapper).selectRecordWriterTargetsForUpdate("mt_parent", "managed_flag", "managed", Map.of("tenant_id", 1L, "pid", "ordinary"));
    }
    @Test void nonexistentOrForeignParentFailsBeforeMutation() {
        when(mapper.selectRecordWriterTargetsForUpdate(anyString(), anyString(), anyString(), anyMap())).thenReturn(List.of());
        assertThatThrownBy(() -> input(Map.of("order_pid", "foreign"))).hasMessageContaining("current tenant");
    }
    @Test void aliasesCannotHideManagedParentTransfer() {
        assertThatThrownBy(() -> input(Map.of("order_link", "ordinary", "order_pid", "owned"))).hasMessageContaining("Conflicting");
        verifyNoInteractions(mapper);
    }
    @Test void numericAndDisplayReferenceDeclarationsAreRejected() {
        assertThatThrownBy(() -> input(Map.of("order_pid", 12L))).hasMessageContaining("stored PID");
        child.getFields().get(1).getRefTarget().setValueField("name");
        assertThatThrownBy(() -> input(Map.of("order_pid", "label"))).hasMessageContaining("physical PID");
        verifyNoInteractions(mapper);
    }
    @Test void storedParentProtectionIsCorrelatedEvenWhenChildIsOrdinary() {
        var sql = new StringBuilder("UPDATE mt_child SET amount=1 WHERE tenant_id=1");
        RecordCommandWriterGuard.appendStoredPredicate(sql, child, "update", code -> parent);
        assertThat(sql.toString()).contains("managed_flag IS DISTINCT FROM TRUE", "AND NOT EXISTS",
            "record_writer_parent.pid = mt_child.order_pid", "record_writer_parent.tenant_id = mt_child.tenant_id",
            "record_writer_parent.managed_flag IS TRUE");
    }
    @Test void missingResolverFailsClosed() {
        assertThatThrownBy(() -> RecordCommandWriterGuard.appendStoredPredicate(new StringBuilder(), child, "update"))
            .hasMessageContaining("model resolution");
    }
    @Test void childWriterDoesNotInheritParentPermission() {
        MetaContext.runWithCommandPermitPlan("ALL", null, "line", "line-a", () ->
            MetaContext.runWithAuthorizedCommandCode("app:line_only", () -> {
                var sql = new StringBuilder();
                RecordCommandWriterGuard.appendStoredPredicate(sql, child, "update", code -> parent);
                assertThat(sql.toString()).contains("NOT EXISTS").doesNotContain("IS DISTINCT FROM TRUE");
            }));
    }
    @Test void exactParentWriterCanAttachAndUpdate() {
        when(mapper.selectRecordWriterTargetsForUpdate(anyString(), anyString(), anyString(), anyMap()))
            .thenReturn(List.of(Map.of("managed", true)));
        MetaContext.runWithCommandPermitPlan("ALL", null, "order", "owned", () ->
            MetaContext.runWithAuthorizedCommandCode("app:save", () -> {
                input(Map.of("order_pid", "owned"));
                var sql = new StringBuilder();
                RecordCommandWriterGuard.appendStoredPredicate(sql, child, "update", code -> parent);
                assertThat(sql.toString()).isEmpty();
            }));
    }
    @Test void parentLookupRequiresActualTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThatThrownBy(() -> input(Map.of("order_pid", "ordinary"))).hasMessageContaining("active transaction");
        verifyNoInteractions(mapper);
    }
}
