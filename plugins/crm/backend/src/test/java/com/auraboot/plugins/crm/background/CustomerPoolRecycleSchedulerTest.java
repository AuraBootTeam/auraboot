package com.auraboot.plugins.crm.background;

import com.auraboot.framework.plugin.extension.BackgroundDataAccessor;
import com.auraboot.framework.plugin.extension.BackgroundTenantAccessor;
import com.auraboot.framework.plugin.extension.RecordShareAccessor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomerPoolRecycleSchedulerTest {

    @Test
    void skipsOnlyTheExactMissingCrmModelCondition() {
        assertTrue(CustomerPoolRecycleScheduler.isCrmModelAbsent(
                new IllegalStateException("wrapper",
                        new RuntimeException("Model not found: crm_customer_pool"))));
        assertFalse(CustomerPoolRecycleScheduler.isCrmModelAbsent(
                new RuntimeException("Model not found: crm_account_common")));
        assertFalse(CustomerPoolRecycleScheduler.isCrmModelAbsent(
                new RuntimeException("database unavailable")));
    }

    @Test
    void isolatesMissingModelsPerTenantWithoutTouchingOtherTenants() throws Exception {
        BackgroundDataAccessor data = mock(BackgroundDataAccessor.class);
        BackgroundTenantAccessor tenants = mock(BackgroundTenantAccessor.class);
        RecordShareAccessor shares = mock(RecordShareAccessor.class);
        CustomerPoolRecycleScheduler scheduler = scheduler(data, tenants, shares);
        when(tenants.listActiveTenantIds()).thenReturn(List.of(41L, 42L));
        when(data.query(eq(41L), anyString(), anyMap()))
                .thenThrow(new IllegalStateException("Model not found: crm_customer_pool"));
        when(data.query(eq(42L), anyString(), anyMap())).thenReturn(List.of());

        scheduler.recycleDueCustomers();

        verify(data).query(eq(41L), anyString(), anyMap());
        verify(data).query(eq(42L), anyString(), anyMap());
        verify(shares, never()).replaceReadSharesForUsers(eq(41L), anyString(), anyString(), eq(List.of()));
    }

    @Test
    void tenantDataAccessorDelegatesEveryOperationWithTheBoundTenant() {
        BackgroundDataAccessor delegate = mock(BackgroundDataAccessor.class);
        CustomerPoolRecycleScheduler.TenantDataAccessor data =
                new CustomerPoolRecycleScheduler.TenantDataAccessor(delegate, 73L);
        Map<String, Object> row = Map.of("id", "row-1");
        Map<String, Object> values = Map.of("field", "value");
        List<Map<String, Object>> rows = List.of(row);
        when(delegate.getById(73L, "model", "row-1")).thenReturn(row);
        when(delegate.query(73L, "model", values)).thenReturn(rows);
        when(delegate.create(73L, "model", values)).thenReturn(row);
        when(delegate.tryCreate(73L, "model", values)).thenReturn(Optional.of(row));
        when(delegate.update(73L, "model", "row-1", values)).thenReturn(row);
        when(delegate.compareAndSet(73L, "model", "row-1", "state", "old", "new")).thenReturn(true);
        when(delegate.compareAndSet(73L, "model", "row-1", "state", "old", values)).thenReturn(true);
        when(delegate.incrementWithinCap(73L, "model", "row-1", "counter", 1L, "cap"))
                .thenReturn(Optional.of(2L));
        when(delegate.queryIn(73L, "model", "id", List.of("row-1"))).thenReturn(rows);

        assertSame(row, data.getById("model", "row-1"));
        assertSame(rows, data.query("model", values));
        assertSame(row, data.create("model", values));
        assertTrue(data.tryCreate("model", values).isPresent());
        assertSame(row, data.update("model", "row-1", values));
        assertTrue(data.compareAndSet("model", "row-1", "state", "old", "new"));
        assertTrue(data.compareAndSet("model", "row-1", "state", "old", values));
        assertEquals(rows, data.batchCreate("model", List.of(values)));
        data.delete("model", "row-1");
        assertTrue(data.incrementWithinCap("model", "row-1", "counter", 1L, "cap").isPresent());
        assertSame(rows, data.queryIn("model", "id", List.of("row-1")));

        verify(delegate).delete(73L, "model", "row-1");
    }

    private static CustomerPoolRecycleScheduler scheduler(
            BackgroundDataAccessor data,
            BackgroundTenantAccessor tenants,
            RecordShareAccessor shares) throws Exception {
        CustomerPoolRecycleScheduler scheduler = new CustomerPoolRecycleScheduler();
        setField(scheduler, "data", data);
        setField(scheduler, "tenants", tenants);
        setField(scheduler, "shares", shares);
        setField(scheduler, "recycleLeaseTimeoutMs", 900_000L);
        return scheduler;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
