package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.provider.ToolDefinition;
import com.auraboot.framework.agent.provider.ToolProviderRegistry;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeclaredAgentToolResolverTest {

    private final ToolProviderRegistry registry = mock(ToolProviderRegistry.class);
    private final DynamicDataMapper mapper = mock(DynamicDataMapper.class);
    private final DeclaredAgentToolResolver resolver =
            new DeclaredAgentToolResolver(registry, mapper, new ObjectMapper());

    @Test
    void resolvesDeclaredCrossModelCommandViaItsOwnModelHint() {
        // crm:create_activity lives on model crm_activity_common
        when(mapper.selectByQuery(anyString(), any()))
                .thenReturn(List.of(Map.of("model_code", "crm_activity_common")));
        // the registry surfaces the command ONLY when discovering with that model hint
        ToolDefinition activity = ToolDefinition.builder()
                .toolCode("cmd:crm:create_activity").toolName("Create Activity").providerCode("dsl").build();
        when(registry.discoverAll(argThat(ctx -> ctx != null && "crm_activity_common".equals(ctx.getModelHint()))))
                .thenReturn(List.of(activity));
        when(registry.discoverAll(argThat(ctx -> ctx != null && ctx.getModelHint() == null)))
                .thenReturn(List.of());

        List<ToolDefinition> resolved = resolver.resolveDeclaredTools(
                7L, 1L, "cs_agent", List.of("cmd:crm:create_activity"));

        assertThat(resolved).extracting(ToolDefinition::getToolCode)
                .containsExactly("cmd:crm:create_activity");
    }

    @Test
    void onlyReturnsDeclaredCodesNotEveryDiscoveredTool() {
        when(mapper.selectByQuery(anyString(), any()))
                .thenReturn(List.of(Map.of("model_code", "crm_activity_common")));
        ToolDefinition wanted = ToolDefinition.builder().toolCode("cmd:crm:create_activity").providerCode("dsl").build();
        ToolDefinition other = ToolDefinition.builder().toolCode("cmd:crm:delete_activity").providerCode("dsl").build();
        when(registry.discoverAll(any())).thenReturn(List.of(wanted, other));

        List<ToolDefinition> resolved = resolver.resolveDeclaredTools(
                7L, 1L, "cs_agent", List.of("cmd:crm:create_activity"));

        assertThat(resolved).extracting(ToolDefinition::getToolCode).containsExactly("cmd:crm:create_activity");
    }

    @Test
    void parseDeclaredCodesHandlesCommaStringAndJsonArray() {
        assertThat(DeclaredAgentToolResolver.parseDeclaredCodes(
                Map.of("tools", "cmd:crm:create_activity, custom:send_customer_reply"), new ObjectMapper()))
                .containsExactly("cmd:crm:create_activity", "custom:send_customer_reply");
        assertThat(DeclaredAgentToolResolver.parseDeclaredCodes(
                Map.of("tools", "[\"a\",\"b\"]"), new ObjectMapper()))
                .containsExactly("a", "b");
        assertThat(DeclaredAgentToolResolver.parseDeclaredCodes(Map.of(), new ObjectMapper())).isEmpty();
    }
}
