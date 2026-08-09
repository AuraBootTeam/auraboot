package com.auraboot.framework.saas.bootstrap;

import com.auraboot.framework.saas.bootstrap.dto.BootstrapRequest;
import com.auraboot.framework.saas.config.entity.BootstrapEntity;
import com.auraboot.framework.saas.config.mapper.BootstrapMapper;
import com.auraboot.framework.saas.config.service.SystemConfigService;
import com.auraboot.framework.saas.constant.BootstrapStatus;
import com.auraboot.framework.saas.constant.SystemConfigKeys;
import com.auraboot.framework.tenant.service.TenantBootstrapService;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.user.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BootstrapEngineServicePostProcessorTest {

    @Mock private SystemConfigService systemConfigService;
    @Mock private BootstrapMapper bootstrapMapper;
    @Mock private UserService userService;
    @Mock private TenantService tenantService;
    @Mock private TenantBootstrapService tenantBootstrapService;
    @Mock private BootstrapRepairService bootstrapRepairService;
    @Mock private ObjectMapper objectMapper;
    @Mock private BootstrapPostProcessor firstPostProcessor;
    @Mock private BootstrapPostProcessor secondPostProcessor;

    @Test
    void runsPostProcessorsInOrderBeforeMarkingSystemInitialized() throws Exception {
        BootstrapEngineService service = serviceWith(firstPostProcessor, secondPostProcessor);
        BootstrapRequest request = request();
        BootstrapEngineService.CoreBootstrapResult coreResult = coreResult();
        doReturn(coreResult).when(service).executeCoreBootstrap(any(), eq(request));

        BootstrapEngineService.BootstrapResult result = service.execute(request);

        assertThat(result.success()).isTrue();
        BootstrapContext expectedContext = new BootstrapContext(1L, 20L, 30L, "admin-pid");
        InOrder order = inOrder(firstPostProcessor, secondPostProcessor, systemConfigService);
        order.verify(firstPostProcessor).process(expectedContext);
        order.verify(secondPostProcessor).process(expectedContext);
        order.verify(systemConfigService).initialize(
                eq(SystemConfigKeys.SYSTEM_INITIALIZED), eq("true"),
                eq("system"), eq("boolean"), any(), eq(true));
    }

    @Test
    void postProcessorFailureKeepsSystemUninitializedAndMarksBootstrapFailed() throws Exception {
        BootstrapEngineService service = serviceWith(firstPostProcessor);
        BootstrapRequest request = request();
        doReturn(coreResult()).when(service).executeCoreBootstrap(any(), eq(request));
        doThrow(new IllegalStateException("AuraQR seed reconcile failed"))
                .when(firstPostProcessor).process(any());

        BootstrapEngineService.BootstrapResult result = service.execute(request);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("AuraQR seed reconcile failed");
        verify(systemConfigService, never()).initialize(
                eq(SystemConfigKeys.SYSTEM_INITIALIZED), any(), any(), any(), any(), eq(true));
        verify(bootstrapMapper).updateById(org.mockito.ArgumentMatchers.<BootstrapEntity>argThat(
                bootstrap -> BootstrapStatus.FAILED.getCode().equals(bootstrap.getStatus())));
    }

    private BootstrapEngineService serviceWith(BootstrapPostProcessor... postProcessors) throws Exception {
        when(systemConfigService.isInitialized()).thenReturn(false);
        when(bootstrapMapper.findActiveBootstrap()).thenReturn(null);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        BootstrapEngineService target = new BootstrapEngineService(
                systemConfigService,
                bootstrapMapper,
                userService,
                tenantService,
                tenantBootstrapService,
                bootstrapRepairService,
                objectMapper,
                List.of(postProcessors));
        return spy(target);
    }

    private static BootstrapRequest request() {
        BootstrapRequest request = new BootstrapRequest();
        request.setCompanyName("AuraQR Fresh Bootstrap");
        request.setAdminEmail("bootstrap@auraqr.test");
        request.setAdminPassword("Test2026x!");
        request.setAdminDisplayName("AuraQR Admin");
        request.setSystemMode("single");
        request.setInstanceUrl("http://localhost:6443");
        return request;
    }

    private static BootstrapEngineService.CoreBootstrapResult coreResult() {
        BootstrapEngineService.CoreBootstrapResult result =
                new BootstrapEngineService.CoreBootstrapResult();
        result.systemTenantId = 1L;
        result.defaultTenantId = 20L;
        result.adminUserId = 30L;
        result.adminUserPid = "admin-pid";
        return result;
    }
}
