package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.exception.ColumnHasDataException;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FieldDeletionGuardTest {
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final FieldDeletionGuard guard = new FieldDeletionGuard(jdbc);
    final ModelDefinition model = ModelDefinition.builder().code("invoice").tableName("mt_invoice").build();
    final FieldDefinition field = FieldDefinition.builder().code("amount").columnName("amount").build();
    @BeforeEach void context() {
        MetaContext.setSystemTenantContext(42L); TransactionSynchronizationManager.setActualTransactionActive(true);
        when(jdbc.queryForObject(contains("ab_tenant_application_binding"),eq(Boolean.class),eq("mt_invoice"))).thenReturn(false);
    }
    @AfterEach void clear() { MetaContext.clear(); TransactionSynchronizationManager.clear(); }
    @Test void missingTransactionNeverProbesOrMutates() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThatThrownBy(() -> guard.requireSafeDrop(model,field)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(jdbc);
    }
    @Test void sharedColumnCannotBeDropped() {
        when(jdbc.queryForObject(anyString(),eq(Boolean.class),eq("mt_invoice"),eq("amount"),eq("invoice"),eq(42L))).thenReturn(true);
        assertThatThrownBy(() -> guard.requireSafeDrop(model,field)).isInstanceOf(RuntimeException.class);
        verify(jdbc,never()).queryForObject(contains("IS NOT NULL"),eq(Boolean.class));
    }
    @Test void existingValuesAreProtectedAcrossAllTenants() {
        when(jdbc.queryForObject(anyString(),eq(Boolean.class),eq("mt_invoice"),eq("amount"),eq("invoice"),eq(42L))).thenReturn(false);
        when(jdbc.queryForObject(contains("IS NOT NULL"),eq(Boolean.class))).thenReturn(true);
        assertThatThrownBy(() -> guard.requireSafeDrop(model,field)).isInstanceOf(ColumnHasDataException.class);
        verify(jdbc).execute("LOCK TABLE mt_invoice IN ACCESS EXCLUSIVE MODE");
    }
    @Test void probeFailureIsNotTreatedAsEmpty() {
        when(jdbc.queryForObject(anyString(),eq(Boolean.class),eq("mt_invoice"),eq("amount"),eq("invoice"),eq(42L))).thenReturn(false);
        when(jdbc.queryForObject(contains("IS NOT NULL"),eq(Boolean.class))).thenThrow(new IllegalStateException("database unavailable"));
        assertThatThrownBy(() -> guard.requireSafeDrop(model,field)).isInstanceOf(IllegalStateException.class);
    }
}
