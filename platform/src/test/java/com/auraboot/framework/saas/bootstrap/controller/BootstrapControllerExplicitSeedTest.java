package com.auraboot.framework.saas.bootstrap.controller;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.auraboot.framework.application.bootstrap.PlatformSeedService;
import com.auraboot.framework.saas.bootstrap.BootstrapEngineService;
import com.auraboot.framework.saas.bootstrap.dto.BootstrapRequest;
import com.auraboot.framework.saas.bootstrap.dto.BootstrapProgressResponse;
import com.auraboot.framework.saas.constant.SystemConfigKeys;
import com.auraboot.framework.saas.config.service.SystemConfigService;
import com.auraboot.framework.scheduler.service.SchedulerEngine;
import com.auraboot.framework.scheduler.service.impl.SystemTaskInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class BootstrapControllerExplicitSeedTest {

    @Mock private BootstrapEngineService bootstrapEngineService;
    @Mock private SystemConfigService systemConfigService;
    @Mock private PlatformSeedService platformSeedService;
    @Mock private SystemTaskInitializer systemTaskInitializer;
    @Mock private SchedulerEngine schedulerEngine;

    @InjectMocks private BootstrapController controller;

    @ParameterizedTest
    @ValueSource(strings = {"single", "multi", "hybrid"})
    void statusReportsThePersistedDeploymentMode(String mode) {
        when(systemConfigService.isInitialized()).thenReturn(true);
        when(systemConfigService.get(SystemConfigKeys.SYSTEM_MODE)).thenReturn(Optional.of(mode));
        when(bootstrapEngineService.getProgress())
                .thenReturn(BootstrapProgressResponse.builder().status("completed").build());

        assertEquals(mode, controller.getStatus().getData().getMode());
        verify(platformSeedService, never()).seed();
    }

    @Test
    void setupSeedsPlatformBeforeRunningBootstrapEngine() {
        BootstrapRequest request = new BootstrapRequest();
        when(systemConfigService.isInitialized()).thenReturn(false);
        when(bootstrapEngineService.execute(request))
                .thenReturn(BootstrapEngineService.BootstrapResult.success(null, 2L));

        controller.setup(request);

        InOrder order = inOrder(platformSeedService, bootstrapEngineService,
                systemTaskInitializer, schedulerEngine);
        order.verify(platformSeedService).seed();
        order.verify(bootstrapEngineService).execute(request);
        order.verify(systemTaskInitializer).initializeSystemTasks();
        order.verify(schedulerEngine).reload();
    }

    @Test
    void setupDoesNotSeedAnInitializedInstallation() {
        BootstrapRequest request = new BootstrapRequest();
        when(systemConfigService.isInitialized()).thenReturn(true);

        controller.setup(request);

        verify(platformSeedService, never()).seed();
        verify(bootstrapEngineService, never()).execute(request);
        verify(systemTaskInitializer, never()).initializeSystemTasks();
        verify(schedulerEngine, never()).reload();
    }

    @Test
    void setupDoesNotInitializeTasksWhenBootstrapFails() {
        BootstrapRequest request = new BootstrapRequest();
        when(systemConfigService.isInitialized()).thenReturn(false);
        when(bootstrapEngineService.execute(request))
                .thenReturn(BootstrapEngineService.BootstrapResult.failure("failed"));

        controller.setup(request);

        verify(platformSeedService).seed();
        verify(systemTaskInitializer, never()).initializeSystemTasks();
        verify(schedulerEngine, never()).reload();
    }
}
