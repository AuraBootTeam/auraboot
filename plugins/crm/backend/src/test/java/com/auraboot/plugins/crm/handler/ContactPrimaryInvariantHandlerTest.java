package com.auraboot.plugins.crm.handler;

import com.auraboot.framework.plugin.extension.CommandHandlerExtension.CommandContext;
import com.auraboot.framework.plugin.extension.DataAccessor;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContactPrimaryInvariantHandlerTest {

    @Test
    void primaryContactDemotesEveryOtherPrimaryForTheSameAccount() {
        DataAccessor db = mock(DataAccessor.class);
        when(db.getById("crm_contact_common", "contact-new")).thenReturn(Map.of(
                "pid", "contact-new",
                "crm_ct_account_id", "account-1",
                "crm_ct_status", "active",
                "crm_ct_is_primary", true));
        when(db.query("crm_contact_common", Map.of("crm_ct_account_id", "account-1"))).thenReturn(List.of(
                Map.of("pid", "contact-old", "crm_ct_is_primary", true),
                Map.of("pid", "contact-new", "crm_ct_is_primary", true),
                Map.of("pid", "contact-secondary", "crm_ct_is_primary", false)));

        Object result = new ContactPrimaryInvariantHandler().execute(context(db, "contact-new"));

        verify(db).update("crm_contact_common", "contact-old", primaryState(false, null));
        verify(db).update("crm_contact_common", "contact-new", primaryState(true, "account-1"));
        verify(db, never()).update("crm_contact_common", "contact-secondary", Map.of("crm_ct_is_primary", false));
        assertEquals(1, ((Map<?, ?>) result).get("demotedContactCount"));
    }

    @Test
    void inactiveContactCannotRemainPrimary() {
        DataAccessor db = mock(DataAccessor.class);
        when(db.getById("crm_contact_common", "contact-1")).thenReturn(Map.of(
                "pid", "contact-1",
                "crm_ct_account_id", "account-1",
                "crm_ct_status", "inactive",
                "crm_ct_is_primary", true));

        Object result = new ContactPrimaryInvariantHandler().execute(context(db, "contact-1"));

        verify(db).update("crm_contact_common", "contact-1", primaryState(false, null));
        assertTrue(Boolean.TRUE.equals(((Map<?, ?>) result).get("primaryContactNormalized")));
    }

    @Test
    void setPrimaryCommandPromotesTheSelectedActiveContactBeforeDemotingSiblings() {
        DataAccessor db = mock(DataAccessor.class);
        when(db.getById("crm_contact_common", "contact-new")).thenReturn(Map.of(
                "pid", "contact-new",
                "crm_ct_account_id", "account-1",
                "crm_ct_status", "active",
                "crm_ct_is_primary", false));
        when(db.query("crm_contact_common", Map.of("crm_ct_account_id", "account-1"))).thenReturn(List.of(
                Map.of("pid", "contact-old", "crm_ct_is_primary", true),
                Map.of("pid", "contact-new", "crm_ct_is_primary", false)));

        new ContactPrimaryInvariantHandler().execute(
                context(db, "contact-new", ContactPrimaryInvariantHandler.SET_PRIMARY));

        verify(db).update("crm_contact_common", "contact-new", primaryState(true, "account-1"));
        verify(db).update("crm_contact_common", "contact-old", primaryState(false, null));
    }

    @Test
    void ordinaryActiveContactDoesNotWriteSiblingRows() {
        DataAccessor db = mock(DataAccessor.class);
        when(db.getById("crm_contact_common", "contact-1")).thenReturn(Map.of(
                "pid", "contact-1",
                "crm_ct_account_id", "account-1",
                "crm_ct_status", "active",
                "crm_ct_is_primary", false));

        Object result = new ContactPrimaryInvariantHandler().execute(context(db, "contact-1"));

        verify(db, never()).query("crm_contact_common", Map.of("crm_ct_account_id", "account-1"));
        assertEquals(Map.of("primaryContactNormalized", false), result);
    }

    @Test
    void nonPrimaryContactClearsAStaleUniqueAccountKey() {
        DataAccessor db = mock(DataAccessor.class);
        when(db.getById("crm_contact_common", "contact-1")).thenReturn(Map.of(
                "pid", "contact-1",
                "crm_ct_account_id", "account-1",
                "crm_ct_status", "active",
                "crm_ct_is_primary", false,
                "crm_ct_primary_account_key", "account-1"));

        Object result = new ContactPrimaryInvariantHandler().execute(context(db, "contact-1"));

        verify(db).update("crm_contact_common", "contact-1", primaryState(false, null));
        assertTrue(Boolean.TRUE.equals(((Map<?, ?>) result).get("primaryContactNormalized")));
    }

    @Test
    void concurrentUniqueConflictReturnsAnActionableMessageWithoutSqlDetails() {
        DataAccessor db = mock(DataAccessor.class);
        when(db.getById("crm_contact_common", "contact-new")).thenReturn(Map.of(
                "pid", "contact-new",
                "crm_ct_account_id", "account-1",
                "crm_ct_status", "active",
                "crm_ct_is_primary", false));
        when(db.query("crm_contact_common", Map.of("crm_ct_account_id", "account-1")))
                .thenReturn(List.of());
        doThrow(new IllegalStateException(
                "duplicate key violates crm_ct_primary_account_key constraint; SQL hidden in cause"))
                .when(db).update("crm_contact_common", "contact-new", primaryState(true, "account-1"));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new ContactPrimaryInvariantHandler().execute(
                        context(db, "contact-new", ContactPrimaryInvariantHandler.SET_PRIMARY)));

        assertTrue(error.getMessage().contains("刷新后重试"));
        assertTrue(error.getMessage().contains("refresh and retry"));
        assertTrue(!error.getMessage().contains("duplicate key"));
        assertTrue(!error.getMessage().contains("SQL"));
    }

    @Test
    void contactWithoutAccountFailsClosed() {
        DataAccessor db = mock(DataAccessor.class);
        when(db.getById("crm_contact_common", "contact-1")).thenReturn(Map.of(
                "pid", "contact-1",
                "crm_ct_status", "active",
                "crm_ct_is_primary", false));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new ContactPrimaryInvariantHandler().execute(context(db, "contact-1")));

        assertTrue(error.getMessage().contains("must belong to an account"));
    }

    @Test
    void duplicateContactChannelWithinAccountFailsClosed() {
        DataAccessor db = mock(DataAccessor.class);
        when(db.getById("crm_contact_common", "contact-new")).thenReturn(Map.of(
                "pid", "contact-new",
                "crm_ct_account_id", "account-1",
                "crm_ct_status", "active",
                "crm_ct_is_primary", false,
                "crm_ct_email", "Owner@Example.com"));
        when(db.query("crm_contact_common", Map.of("crm_ct_account_id", "account-1"))).thenReturn(List.of(
                Map.of("pid", "contact-old", "crm_ct_email", "owner@example.com"),
                Map.of("pid", "contact-new", "crm_ct_email", "Owner@Example.com")));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new ContactPrimaryInvariantHandler().execute(context(db, "contact-new")));

        assertTrue(error.getMessage().contains("same contact detail"));
    }

    @Test
    void primaryContactCannotBeDeleted() {
        DataAccessor db = mock(DataAccessor.class);
        when(db.getById("crm_contact_common", "contact-1")).thenReturn(Map.of(
                "pid", "contact-1",
                "crm_ct_account_id", "account-1",
                "crm_ct_status", "active",
                "crm_ct_is_primary", true));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new ContactPrimaryInvariantHandler().execute(
                        context(db, "contact-1", ContactPrimaryInvariantHandler.DELETE)));

        assertTrue(error.getMessage().contains("cannot be deleted"));
        verify(db, never()).delete("crm_contact_common", "contact-1");
    }

    @Test
    void opportunityLinkedContactCannotBeDeleted() {
        DataAccessor db = mock(DataAccessor.class);
        when(db.getById("crm_contact_common", "contact-1")).thenReturn(Map.of(
                "pid", "contact-1",
                "crm_ct_account_id", "account-1",
                "crm_ct_status", "active",
                "crm_ct_is_primary", false));
        when(db.query("crm_opportunity_contact_common", Map.of("crm_oc_contact_id", "contact-1")))
                .thenReturn(List.of(Map.of("pid", "link-1")));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new ContactPrimaryInvariantHandler().execute(
                        context(db, "contact-1", ContactPrimaryInvariantHandler.DELETE)));

        assertTrue(error.getMessage().contains("linked to opportunities"));
        verify(db, never()).delete("crm_contact_common", "contact-1");
    }

    @Test
    void unlinkedSecondaryContactCanBeDeleted() {
        DataAccessor db = mock(DataAccessor.class);
        when(db.getById("crm_contact_common", "contact-1")).thenReturn(Map.of(
                "pid", "contact-1",
                "crm_ct_account_id", "account-1",
                "crm_ct_status", "active",
                "crm_ct_is_primary", false));
        when(db.query("crm_opportunity_contact_common", Map.of("crm_oc_contact_id", "contact-1")))
                .thenReturn(List.of());

        Object result = new ContactPrimaryInvariantHandler().execute(
                context(db, "contact-1", ContactPrimaryInvariantHandler.DELETE));

        verify(db).delete("crm_contact_common", "contact-1");
        assertEquals(Map.of("deletedContactId", "contact-1"), result);
    }

    private static Map<String, Object> primaryState(boolean primary, String accountKey) {
        Map<String, Object> values = new HashMap<>();
        values.put("crm_ct_is_primary", primary);
        values.put("crm_ct_primary_account_key", accountKey);
        return values;
    }

    private static CommandContext context(DataAccessor db, String recordId) {
        return context(db, recordId, ContactPrimaryInvariantHandler.UPDATE);
    }

    private static CommandContext context(DataAccessor db, String recordId, String commandType) {
        return new CommandContext(
                1L,
                "com.auraboot.crm",
                "crm",
                commandType,
                "crm_contact_common",
                recordId,
                Map.of(),
                Map.of("__dataAccessor", db),
                false);
    }
}
