package com.auraboot.plugins.crm.handler;

import com.auraboot.framework.plugin.extension.CommandHandlerExtension.CommandContext;
import com.auraboot.framework.plugin.extension.DataAccessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MergeAccountHandlerTest {
    private final DataAccessor db = mock(DataAccessor.class);
    private final CommandContext context = mock(CommandContext.class);
    private final MergeAccountHandler handler = new MergeAccountHandler();

    @BeforeEach
    void setUp() {
        when(context.dataAccessor()).thenReturn(db);
        when(context.recordId()).thenReturn("source");
        when(context.payload()).thenReturn(Map.of("targetAccountId", "target"));
        when(db.getById("crm_account_common", "source"))
                .thenReturn(Map.of("pid", "source", "crm_acc_phone", "13800000000"));
        when(db.getById("crm_account_common", "target"))
                .thenReturn(Map.of("pid", "target", "crm_acc_phone", ""));
        when(db.query(org.mockito.ArgumentMatchers.anyString(), anyMap())).thenReturn(List.of());
    }

    @Test
    void movesChildrenFillsBlankProfileAndDeletesSource() {
        when(db.query("crm_contact_common", Map.of("crm_ct_account_id", "source")))
                .thenReturn(List.of(Map.of("pid", "contact", "crm_ct_is_primary", true)));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) handler.execute(context);

        verify(db).update("crm_account_common", "target", Map.of("crm_acc_phone", "13800000000"));
        verify(db).update("crm_contact_common", "contact", Map.of("crm_ct_account_id", "target"));
        verify(db).delete("crm_account_common", "source");
        assertEquals(1, result.get("movedRecordCount"));
    }

    @Test
    void demotesMovedPrimaryWhenTargetAlreadyHasOne() {
        when(db.query("crm_contact_common",
                Map.of("crm_ct_account_id", "target", "crm_ct_is_primary", true)))
                .thenReturn(List.of(Map.of("pid", "target-primary")));
        when(db.query("crm_contact_common", Map.of("crm_ct_account_id", "source")))
                .thenReturn(List.of(Map.of("pid", "source-primary", "crm_ct_is_primary", true)));

        handler.execute(context);

        ArgumentCaptor<Map<String, Object>> values = ArgumentCaptor.forClass(Map.class);
        verify(db).update(org.mockito.ArgumentMatchers.eq("crm_contact_common"),
                org.mockito.ArgumentMatchers.eq("source-primary"), values.capture());
        assertEquals("target", values.getValue().get("crm_ct_account_id"));
        assertEquals(false, values.getValue().get("crm_ct_is_primary"));
    }

    @Test
    void rejectsSelfMergeWithoutWrites() {
        when(context.payload()).thenReturn(Map.of("targetAccountId", "source"));
        assertThrows(IllegalArgumentException.class, () -> handler.execute(context));
        verify(db, never()).delete(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void rejectsPooledSourceWithoutDeletingIt() {
        when(db.query("crm_customer_pool_item_common", Map.of("crm_cpi_account_id", "source")))
                .thenReturn(List.of(Map.of("pid", "pool-item")));
        assertThrows(IllegalArgumentException.class, () -> handler.execute(context));
        verify(db, never()).delete("crm_account_common", "source");
    }
}
