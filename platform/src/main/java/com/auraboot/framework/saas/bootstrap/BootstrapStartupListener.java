package com.auraboot.framework.saas.bootstrap;

import com.auraboot.framework.saas.bootstrap.dto.BootstrapRequest;
import com.auraboot.framework.saas.config.service.SystemConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/**
 * Runs on application startup. When bootstrap mode is "seed" and the system
 * is not yet initialized, automatically executes the bootstrap pipeline
 * using configuration from bootstrap/bootstrap-seed-config.json.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BootstrapStartupListener implements ApplicationRunner {

    @Value("${auraboot.saas.bootstrap.mode:none}")
    private String bootstrapMode;

    private final SystemConfigService systemConfigService;
    private final BootstrapEngineService bootstrapEngineService;
    private final ObjectMapper objectMapper;

    @Override
    public void run(ApplicationArguments args) {
        if (!"seed".equals(bootstrapMode)) {
            return;
        }
        if (systemConfigService.isInitialized()) {
            log.info("System already initialized, skipping seed bootstrap");
            return;
        }

        log.info("Bootstrap mode is 'seed', executing automatic bootstrap...");
        try {
            BootstrapRequest request = loadSeedConfig();
            var result = bootstrapEngineService.execute(request);
            if (result.success()) {
                log.info("Seed bootstrap completed successfully. Tenant ID: {}", result.tenantId());
            } else {
                log.error("Seed bootstrap failed: {}", result.error());
            }
        } catch (Exception e) {
            log.error("Seed bootstrap failed with exception", e);
        }
    }

    private BootstrapRequest loadSeedConfig() {
        try {
            ClassPathResource resource = new ClassPathResource("bootstrap/bootstrap-seed-config.json");
            try (InputStream is = resource.getInputStream()) {
                return objectMapper.readValue(is, BootstrapRequest.class);
            }
        } catch (Exception e) {
            log.warn("Could not load bootstrap-seed-config.json, using defaults: {}", e.getMessage());
            BootstrapRequest request = new BootstrapRequest();
            request.setAdminEmail("admin@example.com");
            request.setAdminPassword("Test2026x");
            request.setCompanyName("AuraBoot Dev");
            request.setSystemMode("single");
            request.setSeedDemoData(true);
            return request;
        }
    }
}
