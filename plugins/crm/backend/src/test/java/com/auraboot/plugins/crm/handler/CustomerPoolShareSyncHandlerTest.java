package com.auraboot.plugins.crm.handler;

import com.auraboot.framework.plugin.extension.CommandHandlerExtension.CommandContext;
import com.auraboot.framework.plugin.extension.DataAccessor;
import com.auraboot.framework.plugin.extension.RecordShareAccessor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomerPoolShareSyncHandlerTest {

    @Test
    void createAndUpdateSynchronizePoolAvailableOwnedAndHistoryShares() {
        DataAccessor db = mock(DataAccessor.class);
        RecordShareAccessor shares = mock(RecordShareAccessor.class);
        when(db.getById("crm_customer_pool_common", "pool-1")).thenReturn(Map.of(
                "pid", "pool-1",
                "crm_cp_member_user_ids", "member-a,member-b",
                "crm_cp_admin_user_ids", "manager"));
        when(db.query("crm_customer_pool_item_common", Map.of("crm_cpi_pool_id", "pool-1"))).thenReturn(List.of(
                Map.of("pid", "item-available", "crm_cpi_status", "available"),
                Map.of("pid", "item-owned", "crm_cpi_status", "assigned", "crm_cpi_claimed_by", "member-b")));
        when(db.query("crm_customer_owner_history_common", Map.of("crm_coh_pool_id", "pool-1"))).thenReturn(List.of(
                Map.of("pid", "history-1")));

        CustomerPoolShareSyncHandler handler = new CustomerPoolShareSyncHandler();
        Object result = handler.execute(new CommandContext(
                1L, "com.auraboot.crm", "crm", CustomerPoolShareSyncHandler.CREATE,
                "crm_customer_pool_common", "pool-1", Map.of(), Map.of(
                        "__dataAccessor", db,
                        RecordShareAccessor.SETTINGS_KEY, shares), false));

        Set<String> poolUsers = Set.of("member-a", "member-b", "manager");
        assertEquals(Map.of("poolSharesSynchronized", true), result);
        verify(shares).replaceReadSharesForUsers(1L, "crm_customer_pool_common", "pool-1", poolUsers);
        verify(shares).replaceReadUpdateSharesForUsers(
                1L, "crm_customer_pool_item_common", "item-available", poolUsers);
        verify(shares).replaceReadUpdateSharesForUsers(
                1L, "crm_customer_pool_item_common", "item-owned", Set.of("member-b"));
        verify(shares).replaceReadSharesForUsers(
                1L, "crm_customer_owner_history_common", "history-1", poolUsers);
    }
}
