package com.auraboot.plugins.crm.handler;

import com.auraboot.framework.plugin.extension.CommandHandlerExtension.CommandContext;
import com.auraboot.framework.plugin.extension.DataAccessor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SaveAccountRelationHandlerTest {

    @Test
    void createsTheFirstRelationshipFact() {
        DataAccessor db = accounts();
        when(db.query("crm_account_relation_common", Map.of("crm_acr_pair_key", "a|b|partner")))
                .thenReturn(List.of());
        when(db.tryCreate(eq("crm_account_relation_common"), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(Optional.of(Map.of("pid", "relation-1")));

        Map<?, ?> result = (Map<?, ?>) new SaveAccountRelationHandler().execute(context(db));

        assertEquals("created", result.get("operation"));
        assertEquals("relation-1", result.get("relationshipId"));
    }

    @Test
    void repeatedSaveUpdatesTheExistingFactInsteadOfCreatingADuplicate() {
        DataAccessor db = accounts();
        when(db.query("crm_account_relation_common", Map.of("crm_acr_pair_key", "a|b|partner")))
                .thenReturn(List.of(Map.of("pid", "relation-1")));

        Map<?, ?> result = (Map<?, ?>) new SaveAccountRelationHandler().execute(context(db));

        verify(db).update(eq("crm_account_relation_common"), eq("relation-1"),
                org.mockito.ArgumentMatchers.argThat(values ->
                        "renewed".equals(values.get("crm_acr_notes"))
                                && "a|b|partner".equals(values.get("crm_acr_pair_key"))));
        assertEquals("updated", result.get("operation"));
        assertEquals("relation-1", result.get("relationshipId"));
    }

    @Test
    void concurrentCreateConflictConvergesOnTheSingleCommittedFact() {
        DataAccessor db = accounts();
        when(db.query("crm_account_relation_common", Map.of("crm_acr_pair_key", "a|b|partner")))
                .thenReturn(List.of(), List.of(Map.of("pid", "relation-winner")));
        when(db.tryCreate(eq("crm_account_relation_common"), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(Optional.empty());

        Map<?, ?> result = (Map<?, ?>) new SaveAccountRelationHandler().execute(context(db));

        assertEquals("updated", result.get("operation"));
        assertEquals("relation-winner", result.get("relationshipId"));
    }

    private static DataAccessor accounts() {
        DataAccessor db = mock(DataAccessor.class);
        when(db.getById("crm_account_common", "a")).thenReturn(Map.of("pid", "a"));
        when(db.getById("crm_account_common", "b")).thenReturn(Map.of("pid", "b"));
        return db;
    }

    private static CommandContext context(DataAccessor db) {
        return new CommandContext(1L, "com.auraboot.crm", "crm",
                SaveAccountRelationHandler.COMMAND, "crm_account_relation_common", null,
                Map.of(
                        "crm_acr_source_account_id", "a",
                        "crm_acr_target_account_id", "b",
                        "crm_acr_relation_type", "partner",
                        "crm_acr_notes", "renewed"),
                Map.of("__dataAccessor", db, "__currentUserPid", "owner-1"), false);
    }
}
