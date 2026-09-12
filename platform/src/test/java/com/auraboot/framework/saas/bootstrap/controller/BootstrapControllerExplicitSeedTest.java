package com.auraboot.framework.saas.bootstrap.controller;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.auraboot.framework.application.bootstrap.PlatformSeedService;
import com.auraboot.framework.saas.bootstrap.BootstrapEngineService;
import com.auraboot.framework.saas.bootstrap.dto.BootstrapRequest;
import com.auraboot.framework.saas.config.service.SystemConfigService;
import com.auraboot.framework.scheduler.service.SchedulerEngine;
import com.auraboot.framework.scheduler.service.impl.SystemTaskInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BootstrapControllerExplicitSeedTest {

    @Mock private BootstrapEngineService bootstrapEngineService;
    @Mock private SystemConfigService systemConfigService;
    @Mock private PlatformSeedService platformSeedService;
    @Mock private SystemTaskInitializer systemTaskInitializer;
    @Mock private SchedulerEngine schedulerEngine;

    @InjectMocks private BootstrapController controller;

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
