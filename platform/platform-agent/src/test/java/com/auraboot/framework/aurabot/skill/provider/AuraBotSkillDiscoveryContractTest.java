package com.auraboot.framework.aurabot.skill.provider;

import com.auraboot.framework.agent.provider.ToolDiscoveryContext;
import com.auraboot.framework.aurabot.skill.AuraBotSkillRegistry;
import com.auraboot.framework.aurabot.skill.SkillMeta;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuraBotSkillDiscoveryContractTest {
    @Test
    void confirmationSkillsExplainTheServerPreviewContractInsteadOfRequestingAHandcraftedCard() {
        var registry = mock(AuraBotSkillRegistry.class);
        var permissions = mock(UserPermissionService.class);
        when(permissions.getUserPermissionCodes(1L)).thenReturn(Set.of("meta.model.update"));
        when(registry.list(anySet())).thenReturn(List.of(
                SkillMeta.builder().name("model:create").displayName("Create model").riskLevel("HIGH")
                        .paramsSchema(new ObjectMapper().createObjectNode()).build(),
                SkillMeta.builder().name("model:query").displayName("Query model").riskLevel("LOW")
                        .paramsSchema(new ObjectMapper().createObjectNode()).build()));
        var provider = new AuraBotSkillToolProvider(registry, permissions, new ObjectMapper(), new StaticMessageSource());
        var tools = provider.discover(ToolDiscoveryContext.builder().tenantId(7L).userId(1L).build());
        assertThat(tools.get(0).getDescription()).contains("server-side preview", "human approval", "Do not simulate");
        assertThat(tools.get(0).isRequiresApproval()).isTrue();
        assertThat(tools.get(1).getDescription()).doesNotContain("server-side preview");
        assertThat(tools.get(1).isRequiresApproval()).isFalse();
    }
}
