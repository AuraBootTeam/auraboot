package com.auraboot.framework.saas.bootstrap;

import com.auraboot.framework.saas.config.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Order(Integer.MAX_VALUE)
@RequiredArgsConstructor
public class BootstrapStartupLogger implements ApplicationRunner {

    private final SystemConfigService systemConfigService;

    @Override
    public void run(ApplicationArguments args) {
        // Auxiliary observability — must never crash app boot if downstream fails.
        boolean initialized;
        try {
            initialized = systemConfigService.isInitialized();
        } catch (Exception e) {
            log.warn("Bootstrap status check failed at startup: {}", e.getMessage());
            return;
        }
        if (initialized) {
            return;
        }
        log.warn("================================================");
        log.warn("  AuraBoot Bootstrap NOT INITIALIZED");
        log.warn("  Run: scripts/oss-reset-and-init.sh");
        log.warn("  Or:  visit http://localhost:5173/setup");
        log.warn("================================================");
    }
}
