package com.auraboot.framework.automation.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.automation.dto.AutomationUpdateRequest;
import com.auraboot.framework.automation.entity.Automation;
import com.auraboot.framework.automation.mapper.AutomationLogMapper;
import com.auraboot.framework.automation.mapper.AutomationMapper;
import com.auraboot.framework.automation.trigger.AutomationTriggerService;
import com.auraboot.framework.exception.ValidationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the cross-tenant IDOR fix on user-facing automation operations.
 *
 * ab_automation is excluded from the global TenantLineInnerInterceptor (so the scheduler
 * can scan across tenants), which made findByPid-based reads/writes return any tenant's
 * automation by pid. These tests assert that user-facing mutations now enforce tenant
 * ownership and fail closed when there is no tenant context.
 *
 * @author AuraBoot Team
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AutomationServiceImpl — cross-tenant IDOR guard")
class AutomationServiceImplTenantIsolationTest {

    private static final long CURRENT_TENANT = 1L;
    private static final long OTHER_TENANT = 2L;

    @Mock
    private AutomationMapper automationMapper;
    @Mock
    private AutomationLogMapper automationLogMapper;
    @Mock
    private AutomationTriggerService automationTriggerService;
    @Mock
    private com.auraboot.framework.automation.bpm.AutomationProcessRuntime automationProcessRuntime;

    private AutomationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AutomationServiceImpl(automationMapper, automationLogMapper, automationTriggerService,
                automationProcessRuntime);
        MetaContext.setContext(CURRENT_TENANT, 10L, "user-1", "tester");
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    private Automation automationOwnedBy(long tenantId) {
        Automation a = new Automation();
        a.setId(99L);
        a.setPid("auto-x");
        a.setTenantId(tenantId);
        a.setModelCode("model-x");
        a.setEnabled(true);
        return a;
    }

    @Test
    void update_otherTenantsAutomation_throwsNotFound() {
        when(automationMapper.findByPid("auto-x")).thenReturn(automationOwnedBy(OTHER_TENANT));

        assertThatThrownBy(() -> service.update("auto-x", new AutomationUpdateRequest()))
                .isInstanceOf(ValidationException.class);

        verify(automationMapper, never()).updateAutomation(any());
    }

    @Test
    void delete_otherTenantsAutomation_throwsNotFound() {
        when(automationMapper.findByPid("auto-x")).thenReturn(automationOwnedBy(OTHER_TENANT));

        assertThatThrownBy(() -> service.delete("auto-x"))
                .isInstanceOf(ValidationException.class);

        verify(automationMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void triggerManually_otherTenantsAutomation_throwsAndDoesNotExecute() {
        when(automationMapper.findByPid("auto-x")).thenReturn(automationOwnedBy(OTHER_TENANT));

        assertThatThrownBy(() -> service.triggerManually("auto-x", "rec-1"))
                .isInstanceOf(ValidationException.class);

        verify(automationTriggerService, never()).executeAutomation(any(), anyString(), any());
    }

    @Test
    void delete_sameTenantAutomation_succeeds() {
        when(automationMapper.findByPid("auto-x")).thenReturn(automationOwnedBy(CURRENT_TENANT));

        assertThatCode(() -> service.delete("auto-x")).doesNotThrowAnyException();

        verify(automationMapper).deleteById(99L);
    }

    @Test
    void update_withoutTenantContext_failsClosed() {
        MetaContext.clear(); // no tenant context
        when(automationMapper.findByPid("auto-x")).thenReturn(automationOwnedBy(CURRENT_TENANT));

        assertThatThrownBy(() -> service.update("auto-x", new AutomationUpdateRequest()))
                .isInstanceOf(ValidationException.class);

        verify(automationMapper, never()).updateAutomation(any());
    }
}
