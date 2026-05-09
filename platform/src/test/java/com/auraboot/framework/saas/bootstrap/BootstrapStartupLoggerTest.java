package com.auraboot.framework.saas.bootstrap;

import com.auraboot.framework.saas.config.service.SystemConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BootstrapStartupLogger}.
 *
 * <p>The logger runs at app boot to print a banner if the system has not
 * been initialized. It must never crash app startup if {@code isInitialized}
 * throws.
 */
@ExtendWith(MockitoExtension.class)
class BootstrapStartupLoggerTest {

    @Mock
    private SystemConfigService systemConfigService;

    @Mock
    private ApplicationArguments args;

    @InjectMocks
    private BootstrapStartupLogger logger;

    @BeforeEach
    void setUp() {
        // no-op
    }

    @Test
    void run_systemInitialized_silentReturn() {
        when(systemConfigService.isInitialized()).thenReturn(true);

        logger.run(args);

        verify(systemConfigService).isInitialized();
        verifyNoMoreInteractions(systemConfigService);
    }

    @Test
    void run_systemNotInitialized_logsBannerAndReturns() {
        when(systemConfigService.isInitialized()).thenReturn(false);

        // Should not throw — only emits warn-level log lines.
        logger.run(args);

        verify(systemConfigService).isInitialized();
    }

    @Test
    void run_isInitializedThrows_swallowedAndDoesNotCrashBoot() {
        when(systemConfigService.isInitialized())
                .thenThrow(new RuntimeException("DB down"));

        // Must not throw — auxiliary observability, never crash boot.
        logger.run(args);

        verify(systemConfigService).isInitialized();
    }
}
