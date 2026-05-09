package com.auraboot.framework.saas.bootstrap;

import com.auraboot.framework.saas.bootstrap.dto.BootstrapRequest;
import com.auraboot.framework.saas.config.service.SystemConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BootstrapStartupListener}.
 *
 * <p>The listener triggers automatic seeding when {@code auraboot.saas.bootstrap.mode}
 * equals {@code seed} and the system has not been initialized.
 */
@ExtendWith(MockitoExtension.class)
class BootstrapStartupListenerTest {

    @Mock
    private SystemConfigService systemConfigService;

    @Mock
    private BootstrapEngineService bootstrapEngineService;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private ApplicationArguments args;

    @InjectMocks
    private BootstrapStartupListener listener;

    @BeforeEach
    void setUp() {
        // default: seed mode
        ReflectionTestUtils.setField(listener, "bootstrapMode", "seed");
    }

    @Test
    void run_modeNotSeed_skipsAllWork() {
        ReflectionTestUtils.setField(listener, "bootstrapMode", "none");

        listener.run(args);

        verify(systemConfigService, never()).isInitialized();
        verify(bootstrapEngineService, never()).execute(any());
    }

    @Test
    void run_seedMode_alreadyInitialized_skipsExecute() {
        when(systemConfigService.isInitialized()).thenReturn(true);

        listener.run(args);

        verify(systemConfigService).isInitialized();
        verify(bootstrapEngineService, never()).execute(any());
    }

    @Test
    void run_seedMode_notInitialized_executesBootstrap_withDefaults() {
        // The classpath resource bootstrap/bootstrap-seed-config.json may not exist
        // in the test classpath; loadSeedConfig falls back to a default request and
        // execute() should still be called.
        when(systemConfigService.isInitialized()).thenReturn(false);
        when(bootstrapEngineService.execute(any(BootstrapRequest.class)))
                .thenReturn(new BootstrapEngineService.BootstrapResult(true, null, 1L, null));

        listener.run(args);

        verify(bootstrapEngineService).execute(any(BootstrapRequest.class));
    }

    @Test
    void run_seedMode_executeReturnsFailure_logsButDoesNotThrow() {
        when(systemConfigService.isInitialized()).thenReturn(false);
        when(bootstrapEngineService.execute(any(BootstrapRequest.class)))
                .thenReturn(new BootstrapEngineService.BootstrapResult(false, null, null, "boom"));

        listener.run(args);

        verify(bootstrapEngineService).execute(any(BootstrapRequest.class));
    }

    @Test
    void run_seedMode_executeThrows_swallowedAtTopLevel() {
        when(systemConfigService.isInitialized()).thenReturn(false);
        when(bootstrapEngineService.execute(any(BootstrapRequest.class)))
                .thenThrow(new RuntimeException("DB down"));

        // Must not propagate — top-level catch in listener.
        listener.run(args);

        verify(bootstrapEngineService).execute(any(BootstrapRequest.class));
    }
}
