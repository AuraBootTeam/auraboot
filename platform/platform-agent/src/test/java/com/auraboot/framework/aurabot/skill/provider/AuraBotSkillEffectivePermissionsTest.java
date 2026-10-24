package com.auraboot.framework.aurabot.skill.provider;

import com.auraboot.framework.agent.provider.ToolDiscoveryContext;
import com.auraboot.framework.aurabot.skill.AuraBotSkill;
import com.auraboot.framework.aurabot.skill.AuraBotSkillRegistry;
import com.auraboot.framework.aurabot.skill.RiskLevel;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.context.support.StaticMessageSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuraBotSkillEffectivePermissionsTest {
    private static final String MODEL_PERMISSION = "meta.model.update";
    private final UserPermissionService permissions = mock(UserPermissionService.class);
    private final ObjectMapper json = new ObjectMapper();

    @Test void publishedPermissionCodeExposesWriteSkillWithoutDatabasePermissionIds() throws Exception {
        when(permissions.getUserPermissionCodes(9L)).thenReturn(Set.of(MODEL_PERMISSION));
        var tools = provider().discover(ToolDiscoveryContext.builder().userId(9L).build());
        assertEquals(1, tools.size());
        assertEquals("aurabot:model:create", tools.get(0).getToolCode());
        assertTrue(tools.get(0).isRequiresConfirmation());
        assertTrue(tools.get(0).isRequiresApproval());
    }

    @Test void missingEffectivePermissionKeepsWriteSkillHidden() throws Exception {
        when(permissions.getUserPermissionCodes(9L)).thenReturn(Set.of("meta.model.read"));
        assertTrue(provider().discover(ToolDiscoveryContext.builder().userId(9L).build()).isEmpty());
    }

    @Test void anonymousDiscoveryDoesNotResolveAnotherUsersPermissions() throws Exception {
        assertTrue(provider().discover(ToolDiscoveryContext.builder().build()).isEmpty());
        verifyNoInteractions(permissions);
    }

    private AuraBotSkillToolProvider provider() throws Exception {
        var skill = mock(AuraBotSkill.class);
        when(skill.name()).thenReturn("model:create");
        when(skill.displayName()).thenReturn("Create model");
        when(skill.riskLevel()).thenReturn(RiskLevel.HIGH);
        when(skill.requiredPermissions()).thenReturn(Set.of(MODEL_PERMISSION));
        when(skill.paramsSchema()).thenReturn(json.readTree("{\"type\":\"object\",\"properties\":{}}"));
        var context = new StaticApplicationContext();
        context.getBeanFactory().registerSingleton("modelCreate", skill);
        var registry = new AuraBotSkillRegistry();
        registry.setApplicationContext(context);
        registry.onContextRefreshed(new ContextRefreshedEvent(context));
        return new AuraBotSkillToolProvider(registry, permissions, json,
                new StaticMessageSource());
    }
}
