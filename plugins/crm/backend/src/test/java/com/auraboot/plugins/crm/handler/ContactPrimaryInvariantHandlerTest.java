package com.auraboot.plugins.crm.handler;

import com.auraboot.framework.plugin.extension.CommandHandlerExtension.CommandContext;
import com.auraboot.framework.plugin.extension.DataAccessor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

        verify(db).update("crm_contact_common", "contact-old", Map.of("crm_ct_is_primary", false));
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

        verify(db).update("crm_contact_common", "contact-1", Map.of("crm_ct_is_primary", false));
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

        verify(db).update("crm_contact_common", "contact-new", Map.of("crm_ct_is_primary", true));
        verify(db).update("crm_contact_common", "contact-old", Map.of("crm_ct_is_primary", false));
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
