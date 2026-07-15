package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.provider.ToolDefinition;
import com.auraboot.framework.agent.provider.ToolDiscoveryContext;
import com.auraboot.framework.agent.provider.ToolProviderRegistry;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link AgentChatToolDiscoveryAdapter} always-on injection (ARCH-004).
 *
 * <p>The named-agent tool-discovery path used to call only {@code discoverAll} — it
 * never merged the channel-gated always-on tools that {@code ToolDiscoveryPortImpl}
 * injects on the aurabot path. A CS site bound to a NAMED agent therefore silently
 * lost {@code escalate_to_human}. This pins that the adapter now discovers always-on
 * tools with the turn channel and merges them ahead of the discovered set.
 */
class AgentChatToolDiscoveryAdapterTest {

    private ToolDefinition tool(String code) {
        return ToolDefinition.builder().toolCode(code).toolName(code).description("d").build();
    }

    private AgentChatToolDiscoveryAdapter adapter(ToolProviderRegistry registry, GroundingService grounding) {
        return new AgentChatToolDiscoveryAdapter(
                mock(DynamicDataMapper.class), registry, grounding, new ObjectMapper());
    }

    @Test
    void discover_injectsChannelGatedAlwaysOnToolsAheadOfDiscovered() {
        ToolProviderRegistry registry = mock(ToolProviderRegistry.class);
        GroundingService grounding = mock(GroundingService.class);
        when(grounding.ground(anyLong(), anyString(), any())).thenReturn(null);

        when(registry.discoverAlwaysOn(any())).thenReturn(List.of(tool("escalate_to_human")));
        when(registry.discoverAll(any())).thenReturn(List.of(tool("cmd:crm:list_leads")));

        List<ToolDefinition> result = adapter(registry, grounding)
                .discover(1L, 2L, "cs-agent", "cs_widget", "I need a refund", Map.of());

        // always-on leads the merged list
        assertThat(result).extracting(ToolDefinition::getToolCode)
                .containsExactly("escalate_to_human", "cmd:crm:list_leads");

        // always-on discovery must carry the turn channel so the provider can gate on it
        ArgumentCaptor<ToolDiscoveryContext> captor = ArgumentCaptor.forClass(ToolDiscoveryContext.class);
        verify(registry).discoverAlwaysOn(captor.capture());
        assertThat(captor.getValue().getChannel()).isEqualTo("cs_widget");
    }

    @Test
    void discover_alsoPassesChannelToMainDiscovery() {
        ToolProviderRegistry registry = mock(ToolProviderRegistry.class);
        GroundingService grounding = mock(GroundingService.class);
        when(grounding.ground(anyLong(), anyString(), any())).thenReturn(null);
        when(registry.discoverAlwaysOn(any())).thenReturn(List.of());
        when(registry.discoverAll(any())).thenReturn(List.of(tool("cmd:crm:list_leads")));

        adapter(registry, grounding).discover(1L, 2L, "cs-agent", "cs_widget", "hi", Map.of());

        ArgumentCaptor<ToolDiscoveryContext> captor = ArgumentCaptor.forClass(ToolDiscoveryContext.class);
        verify(registry).discoverAll(captor.capture());
        assertThat(captor.getValue().getChannel()).isEqualTo("cs_widget");
    }

    @Test
    void discover_noAlwaysOnTools_returnsOnlyDiscovered() {
        ToolProviderRegistry registry = mock(ToolProviderRegistry.class);
        GroundingService grounding = mock(GroundingService.class);
        when(grounding.ground(anyLong(), anyString(), any())).thenReturn(null);
        when(registry.discoverAlwaysOn(any())).thenReturn(List.of());
        when(registry.discoverAll(any())).thenReturn(List.of(tool("cmd:crm:list_leads")));

        List<ToolDefinition> result = adapter(registry, grounding)
                .discover(1L, 2L, "cs-agent", "web", "hi", Map.of());

        assertThat(result).extracting(ToolDefinition::getToolCode)
                .containsExactly("cmd:crm:list_leads");
    }
}
