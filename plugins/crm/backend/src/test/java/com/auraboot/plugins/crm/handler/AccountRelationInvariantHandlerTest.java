package com.auraboot.plugins.crm.handler;

import com.auraboot.framework.plugin.extension.CommandHandlerExtension.CommandContext;
import com.auraboot.framework.plugin.extension.DataAccessor;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountRelationInvariantHandlerTest {

    @Test
    void normalizesAValidDirectionalRelationshipKey() {
        DataAccessor db = mock(DataAccessor.class);
        when(db.getById("crm_account_relation_common", "relation-1")).thenReturn(relation(
                "account-a", "account-b", "partner", "2026-08-01", "2026-12-31"));
        when(db.getById("crm_account_common", "account-a")).thenReturn(Map.of("pid", "account-a"));
        when(db.getById("crm_account_common", "account-b")).thenReturn(Map.of("pid", "account-b"));

        Object result = new AccountRelationInvariantHandler().execute(context(db));

        verify(db).update("crm_account_relation_common", "relation-1",
                Map.of("crm_acr_pair_key", "account-a|account-b|partner"));
        assertEquals(true, ((Map<?, ?>) result).get("relationshipValidated"));
    }

    @Test
    void rejectsASelfRelationshipInsideTheCreateTransaction() {
        DataAccessor db = mock(DataAccessor.class);
        when(db.getById("crm_account_relation_common", "relation-1")).thenReturn(relation(
                "account-a", "account-a", "affiliate", "", ""));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new AccountRelationInvariantHandler().execute(context(db)));

        assertTrue(error.getMessage().contains("不能与自身"));
    }

    @Test
    void rejectsAnInvalidValidityWindow() {
        DataAccessor db = mock(DataAccessor.class);
        when(db.getById("crm_account_relation_common", "relation-1")).thenReturn(relation(
                "account-a", "account-b", "supplier", "2026-09-01", "2026-08-01"));
        when(db.getById("crm_account_common", "account-a")).thenReturn(Map.of("pid", "account-a"));
        when(db.getById("crm_account_common", "account-b")).thenReturn(Map.of("pid", "account-b"));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new AccountRelationInvariantHandler().execute(context(db)));

        assertTrue(error.getMessage().contains("不能早于"));
    }

    @Test
    void rejectsADeletedOrCrossTenantInvisibleEndpoint() {
        DataAccessor db = mock(DataAccessor.class);
        when(db.getById("crm_account_relation_common", "relation-1")).thenReturn(relation(
                "account-a", "account-missing", "customer", "", ""));
        when(db.getById("crm_account_common", "account-a")).thenReturn(Map.of("pid", "account-a"));
        when(db.getById("crm_account_common", "account-missing")).thenReturn(null);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new AccountRelationInvariantHandler().execute(context(db)));

        assertTrue(error.getMessage().contains("Related account not found"));
    }

    @Test
    void concurrentDuplicateReturnsAnActionableMessageWithoutSqlDetails() {
        DataAccessor db = mock(DataAccessor.class);
        when(db.getById("crm_account_relation_common", "relation-1")).thenReturn(relation(
                "account-a", "account-b", "partner", "", ""));
        when(db.getById("crm_account_common", "account-a")).thenReturn(Map.of("pid", "account-a"));
        when(db.getById("crm_account_common", "account-b")).thenReturn(Map.of("pid", "account-b"));
        doThrow(new IllegalStateException(
                "duplicate key violates crm_acr_pair_key unique constraint; SQL detail"))
                .when(db).update("crm_account_relation_common", "relation-1",
                        Map.of("crm_acr_pair_key", "account-a|account-b|partner"));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new AccountRelationInvariantHandler().execute(context(db)));

        assertTrue(error.getMessage().contains("编辑原关系"));
        assertTrue(!error.getMessage().contains("duplicate key"));
        assertTrue(!error.getMessage().contains("SQL"));
    }

    private static Map<String, Object> relation(
            String source, String target, String type, String from, String to) {
        return Map.of(
                "pid", "relation-1",
                "crm_acr_source_account_id", source,
                "crm_acr_target_account_id", target,
                "crm_acr_relation_type", type,
                "crm_acr_effective_from", from,
                "crm_acr_effective_to", to);
    }

    private static CommandContext context(DataAccessor db) {
        return new CommandContext(
                1L,
                "com.auraboot.crm",
                "crm",
                AccountRelationInvariantHandler.CREATE,
                "crm_account_relation_common",
                "relation-1",
                Map.of(),
                Map.of("__dataAccessor", db),
                false);
    }
}
