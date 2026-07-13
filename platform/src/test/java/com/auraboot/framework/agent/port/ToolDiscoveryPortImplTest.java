package com.auraboot.framework.agent.port;

import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.provider.ToolDefinition;
import com.auraboot.framework.agent.provider.ToolDiscoveryContext;
import com.auraboot.framework.agent.provider.ToolProviderRegistry;
import com.auraboot.framework.agent.service.AgentSkillService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolDiscoveryPortImplTest {

    @Test
    void discoverTools_keepsModelHintProviderToolsWhenSkillToolsExist() {
        AgentSkillService agentSkillService = mock(AgentSkillService.class);
        ToolProviderRegistry toolProviderRegistry = mock(ToolProviderRegistry.class);
        ToolDiscoveryPortImpl port = new ToolDiscoveryPortImpl(agentSkillService, toolProviderRegistry);

        when(agentSkillService.resolveSkillTools(1L, "crm_contact_query"))
                .thenReturn(List.of(AgentToolDefinition.builder()
                        .name("list_crm_contact")
                        .description("List CRM contacts")
                        .inputSchema(Map.of("type", "object"))
                        .toolType("dsl_query")
                        .build()));
        when(toolProviderRegistry.discoverAll(any(ToolDiscoveryContext.class)))
                .thenReturn(List.of(
                        ToolDefinition.builder()
                                .toolCode("list:crm_lead")
                                .toolName("List CRM leads")
                                .description("Paginated list of crm_lead records")
                                .toolType("dsl_query")
                                .build(),
                        ToolDefinition.builder()
                                .toolCode("get:crm_lead")
                                .toolName("Get CRM lead")
                                .description("Get one crm_lead record")
                                .toolType("dsl_query")
                                .build()));

        List<ToolDiscoveryPort.ToolDef> tools = port.discoverTools(
                1L,
                List.of("crm_contact_query"),
                "crm_lead",
                "query",
                5,
                null);

        assertThat(tools).extracting(ToolDiscoveryPort.ToolDef::code)
                .contains("list:crm_lead", "get:crm_lead", "list_crm_contact");
        org.mockito.Mockito.verify(toolProviderRegistry).discoverAll(any(ToolDiscoveryContext.class));
        org.mockito.Mockito.verify(agentSkillService).resolveSkillTools(eq(1L), eq("crm_contact_query"));
    }

    // ------------------------------------------------------------------
    // Always-on tools
    //
    // The whole point of the mechanism is that it survives the two filters that would otherwise
    // remove it. A test that only checks "the tool comes back" would pass against a plain
    // discoverAll() and prove nothing.
    // ------------------------------------------------------------------

    private static ToolDefinition escalateTool() {
        return ToolDefinition.builder()
                .toolCode("escalate_to_human")
                .toolName("Escalate to a human")
                .description("Hand this conversation to a human agent")
                .toolType("cs_action")          // deliberately NOT a read-only type
                .build();
    }

    private ToolDiscoveryPortImpl portWithAlwaysOn(ToolProviderRegistry registry, AgentSkillService skills) {
        when(registry.discoverAlwaysOn(any(ToolDiscoveryContext.class))).thenReturn(List.of(escalateTool()));
        return new ToolDiscoveryPortImpl(skills, registry);
    }

    @Test
    void alwaysOnTool_survivesTheReadIntentFilter() {
        // "how long is the warranty?" grounds as a read intent, which drops every mutating tool.
        // That is exactly the moment the visitor needs a human — so the escalation tool must live.
        AgentSkillService skills = mock(AgentSkillService.class);
        ToolProviderRegistry registry = mock(ToolProviderRegistry.class);
        ToolDiscoveryPortImpl port = portWithAlwaysOn(registry, skills);

        when(registry.discoverAll(any(ToolDiscoveryContext.class)))
                .thenReturn(List.of(ToolDefinition.builder()
                        .toolCode("create:crm_lead")     // mutating: must be dropped by queryOnly
                        .toolName("Create lead")
                        .toolType("dsl_command")
                        .build()));

        List<ToolDiscoveryPort.ToolDef> tools =
                port.discoverTools(1L, List.of(), null, "query", 5, "cs_widget");

        assertThat(tools).extracting(ToolDiscoveryPort.ToolDef::code)
                .contains("escalate_to_human")
                .doesNotContain("create:crm_lead");
    }

    @Test
    void alwaysOnTool_isNotTruncatedAwayByMaxTools() {
        // A tool offered only when the budget happens to be underspent is not "always on".
        AgentSkillService skills = mock(AgentSkillService.class);
        ToolProviderRegistry registry = mock(ToolProviderRegistry.class);
        ToolDiscoveryPortImpl port = portWithAlwaysOn(registry, skills);

        List<ToolDefinition> crowd = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            crowd.add(ToolDefinition.builder()
                    .toolCode("list:model_" + i)
                    .toolName("List model " + i)
                    .toolType("dsl_query")
                    .build());
        }
        when(registry.discoverAll(any(ToolDiscoveryContext.class))).thenReturn(crowd);

        List<ToolDiscoveryPort.ToolDef> tools =
                port.discoverTools(1L, List.of(), null, "query", 3, "cs_widget");

        assertThat(tools).extracting(ToolDiscoveryPort.ToolDef::code).contains("escalate_to_human");
        assertThat(tools.get(0).code()).isEqualTo("escalate_to_human");   // and it leads
    }

    @Test
    void alwaysOnTool_survivesEvenWhenSkillResolutionWins() {
        // The skill-resolution path returns early. Its early return must not skip the always-on merge.
        AgentSkillService skills = mock(AgentSkillService.class);
        ToolProviderRegistry registry = mock(ToolProviderRegistry.class);
        ToolDiscoveryPortImpl port = portWithAlwaysOn(registry, skills);

        when(skills.resolveSkillTools(1L, "faq"))
                .thenReturn(List.of(AgentToolDefinition.builder()
                        .name("search_faq")
                        .toolType("dsl_query")
                        .build()));

        List<ToolDiscoveryPort.ToolDef> tools =
                port.discoverTools(1L, List.of("faq"), null, "query", 5, "cs_widget");

        assertThat(tools).extracting(ToolDiscoveryPort.ToolDef::code)
                .contains("escalate_to_human", "search_faq");
    }

    @Test
    void channelIsPassedToTheProvider_soItCanKeepItsToolsOffOtherChannels() {
        AgentSkillService skills = mock(AgentSkillService.class);
        ToolProviderRegistry registry = mock(ToolProviderRegistry.class);
        ToolDiscoveryPortImpl port = new ToolDiscoveryPortImpl(skills, registry);

        port.discoverTools(1L, List.of(), null, "query", 5, "cs_widget");

        org.mockito.ArgumentCaptor<ToolDiscoveryContext> captor =
                org.mockito.ArgumentCaptor.forClass(ToolDiscoveryContext.class);
        org.mockito.Mockito.verify(registry).discoverAlwaysOn(captor.capture());
        assertThat(captor.getValue().getChannel()).isEqualTo("cs_widget");
    }
}
