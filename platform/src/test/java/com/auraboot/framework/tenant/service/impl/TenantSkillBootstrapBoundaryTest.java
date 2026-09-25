package com.auraboot.framework.tenant.service.impl;

import com.auraboot.framework.agent.service.SkillAutoGenerator;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.tenant.dto.bootstrap.TenantBootstrapTemplate;
import com.auraboot.framework.tenant.exception.BootstrapException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TenantSkillBootstrapBoundaryTest {
    @Mock private SkillAutoGenerator skills;
    @Spy @InjectMocks private TenantBootstrapServiceImpl service;

    @Test
    void explicitTenantBootstrapPropagatesSkillFailureAndRestoresContext() {
        var template = new TenantBootstrapTemplate();
        doReturn(template).when(service).loadTemplate("default-bootstrap");
        doNothing().when(service).validateTemplate(template);
        MetaContext.setSystemTenantContext(77L);
        try {
            when(skills.syncSkills(991L)).thenAnswer(invocation -> {
                assertEquals(991L, MetaContext.getCurrentTenantId());
                throw new IllegalStateException("skill publication failed");
            });
            BootstrapException failure = assertThrows(BootstrapException.class,
                    () -> service.bootstrapTenant(991L, 42L));
            assertTrue(failure.getMessage().contains("skill publication failed"));
            verify(skills).syncSkills(991L);
            assertEquals(77L, MetaContext.getCurrentTenantId());
        } finally {
            MetaContext.clear();
        }
    }

    @Test
    void agentServicesDoNotRegisterStartupBootstrapRunners() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(ApplicationRunner.class));
        assertTrue(scanner.findCandidateComponents("com.auraboot.framework.agent.service").isEmpty());
    }
}
