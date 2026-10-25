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
import java.util.ArrayList;
import org.mockito.ArgumentCaptor;

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

    @Test
    void auditSnapshotExcludesCredentialAndPreservesBindingOnSuccess() throws Exception {
        ObjectMapper realMapper = new ObjectMapper();
        BootstrapRequest request = request();
        request.setSeedDemoData(false);
        String password = request.getAdminPassword();
        BootstrapEngineService service = serviceWithSerializer(realMapper);
        doReturn(coreResult()).when(service).executeCoreBootstrap(any(), eq(request));

        assertThat(service.execute(request).success()).isTrue();

        assertSafeAudit(realMapper, request);
        assertThat(request.getAdminPassword()).isEqualTo(password);
        // Removing audit persistence must not disable the native setup API credential input.
        BootstrapRequest rebound = realMapper.readValue(
                "{\"adminPassword\":\"binding-fixture-password\"}", BootstrapRequest.class);
        assertThat(rebound.getAdminPassword()).isEqualTo("binding-fixture-password");
    }

    @Test
    void failedBootstrapAuditExcludesCredentialWithOptionalInputsAbsent() throws Exception {
        ObjectMapper realMapper = new ObjectMapper();
        BootstrapRequest request = request();
        request.setAdminDisplayName(null);
        request.setSystemMode(null);
        request.setInstanceUrl(null);
        request.setSeedDemoData(null);
        String password = request.getAdminPassword();
        BootstrapEngineService service = serviceWithSerializer(realMapper, firstPostProcessor);
        doReturn(coreResult()).when(service).executeCoreBootstrap(any(), eq(request));
        doThrow(new IllegalStateException("fixture post-processor failure"))
                .when(firstPostProcessor).process(any());

        assertThat(service.execute(request).success()).isFalse();

        assertSafeAudit(realMapper, request);
        assertThat(request.getAdminPassword()).isEqualTo(password);
        verify(systemConfigService, never()).initialize(
                eq(SystemConfigKeys.SYSTEM_INITIALIZED), any(), any(), any(), any(), eq(true));
        verify(bootstrapMapper).updateById(org.mockito.ArgumentMatchers.<BootstrapEntity>argThat(
                bootstrap -> BootstrapStatus.FAILED.getCode().equals(bootstrap.getStatus())));
    }

    private void assertSafeAudit(ObjectMapper realMapper, BootstrapRequest request) throws Exception {
        ArgumentCaptor<BootstrapEntity> inserted = ArgumentCaptor.forClass(BootstrapEntity.class);
        verify(bootstrapMapper).insert(inserted.capture());
        String input = inserted.getValue().getInputParams();
        assertThat(input).isNotBlank();
        var audit = realMapper.readTree(input);
        assertThat(audit.has("adminPassword")).as("credential fields never enter bootstrap audit").isFalse();
        assertThat(input.contains(request.getAdminPassword())).as("credential values never enter bootstrap audit").isFalse();
        var fields = new ArrayList<String>();
        audit.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("companyName", "adminEmail", "adminDisplayName",
                "systemMode", "seedDemoData", "instanceUrl");
        assertThat(audit.path("companyName").asText()).isEqualTo(request.getCompanyName());
        assertThat(audit.path("adminEmail").asText()).isEqualTo(request.getAdminEmail());
        for (String key : List.of("adminDisplayName", "systemMode", "instanceUrl")) {
            String expected = switch (key) {
                case "adminDisplayName" -> request.getAdminDisplayName();
                case "systemMode" -> request.getSystemMode();
                case "instanceUrl" -> request.getInstanceUrl();
                default -> throw new AssertionError(key);
            };
            if (expected == null) assertThat(audit.path(key).isNull()).isTrue();
            else assertThat(audit.path(key).asText()).isEqualTo(expected);
        }
        if (request.getSeedDemoData() == null) assertThat(audit.path("seedDemoData").isNull()).isTrue();
        else assertThat(audit.path("seedDemoData").asBoolean()).isEqualTo(request.getSeedDemoData());
    }

    private BootstrapEngineService serviceWith(BootstrapPostProcessor... postProcessors) throws Exception {
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        return serviceWithSerializer(objectMapper, postProcessors);
    }

    private BootstrapEngineService serviceWithSerializer(ObjectMapper serializer, BootstrapPostProcessor... postProcessors) {
        when(systemConfigService.isInitialized()).thenReturn(false);
        when(bootstrapMapper.findActiveBootstrap()).thenReturn(null);
        BootstrapEngineService target = new BootstrapEngineService(
                systemConfigService,
                bootstrapMapper,
                userService,
                tenantService,
                tenantBootstrapService,
                bootstrapRepairService,
                serializer,
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
