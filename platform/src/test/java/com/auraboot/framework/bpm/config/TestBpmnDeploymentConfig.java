package com.auraboot.framework.bpm.config;

import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.service.command.RepositoryCommandService;
import com.auraboot.smart.framework.engine.service.query.RepositoryQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Deploys test BPMN definitions when running with the test profile.
 */
@Configuration
@Profile("test")
@RequiredArgsConstructor
public class TestBpmnDeploymentConfig {

    private static final String SIMPLE_APPROVAL_KEY = "simple-approval";
    private static final String MULTI_TENANT_KEY = "multi-tenant-test";

    private static final String[] SIMPLE_APPROVAL_TENANTS = {"tenant-001", "tenant-002"};
    private static final String[] MULTI_TENANT_TENANTS = {"tenant-a", "tenant-b", "tenant-c"};

    private final SmartEngine smartEngine;

    @Bean
    public ApplicationRunner deployTestBpmnDefinitions() {
        return args -> {
            RepositoryQueryService queryService = smartEngine.getRepositoryQueryService();
            RepositoryCommandService commandService = smartEngine.getRepositoryCommandService();

            deployForTenants(queryService, commandService, SIMPLE_APPROVAL_KEY,
                    "bpmn/simple-approval.bpmn", SIMPLE_APPROVAL_TENANTS);
            deployForTenants(queryService, commandService, MULTI_TENANT_KEY,
                    "bpmn/multi-tenant-test.bpmn", MULTI_TENANT_TENANTS);
        };
    }

    private void deployForTenants(RepositoryQueryService queryService,
                                  RepositoryCommandService commandService,
                                  String processKey,
                                  String classpathResource,
                                  String[] tenants) {
        for (String tenant : tenants) {
            String tenantId = normalizeTenantId(tenant);
            if (!hasDefinition(queryService, processKey, tenantId)) {
                commandService.deploy(classpathResource, tenantId);
            }
        }
    }

    private boolean hasDefinition(RepositoryQueryService queryService, String processKey, String tenantId) {
        return queryService.getAllCachedProcessDefinition().stream()
                .anyMatch(definition -> processKey.equals(definition.getId())
                        && tenantId.equals(definition.getTenantId()));
    }

    private String normalizeTenantId(String tenantLabel) {
        long tenantId;
        try {
            tenantId = Long.parseLong(tenantLabel);
        } catch (NumberFormatException e) {
            tenantId = tenantLabel.hashCode();
        }
        if (tenantId < 0) {
            tenantId = -tenantId;
        }
        return String.valueOf(tenantId);
    }
}
