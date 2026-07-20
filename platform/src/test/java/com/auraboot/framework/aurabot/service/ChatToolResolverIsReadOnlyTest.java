package com.auraboot.framework.aurabot.service;

import com.auraboot.framework.agent.port.GroundingPort;
import com.auraboot.framework.agent.port.ToolDiscoveryPort;
import com.auraboot.framework.application.tenant.MetaContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ChatToolResolver#isReadOnly(String)}.
 * Verifies read-only classification for provider tool naming conventions.
 */
class ChatToolResolverIsReadOnlyTest {

    /** The discovery half of {@link ToolDiscoveryPort}, so these cases can still be written as lambdas. */
    @FunctionalInterface
    interface DiscoverFn {
        List<ToolDiscoveryPort.ToolDef> discover(Long tenantId, List<String> candidateSkills, String modelHint,
                                                 String intentHint, int maxTools, String channel);
    }

    /**
     * Adapts a discovery lambda to the full port. Always-on returns empty: none of these cases run on a
     * RAG-only channel, so the resolver never asks for always-on tools here.
     */
    private static ToolDiscoveryPort discoveryPort(DiscoverFn fn) {
        return new ToolDiscoveryPort() {
            @Override
            public List<ToolDef> discoverAlwaysOnTools(Long tenantId, String channel) {
                return List.of();
            }

            @Override
            public List<ToolDef> discoverTools(Long tenantId, List<String> candidateSkills, String modelHint,
                                               String intentHint, int maxTools, String channel) {
                return fn.discover(tenantId, candidateSkills, modelHint, intentHint, maxTools, channel);
            }
        };
    }

    private ChatToolResolver resolver;

    @BeforeEach
    void setUp() {
        // Construct with null SPI ports — only isReadOnly() is tested
        resolver = new ChatToolResolver(null, null, null);
    }

    @Test
    void nullToolName_isReadOnly() {
        assertThat(resolver.isReadOnly(null)).isTrue();
    }

    @Test
    void nqPrefix_isReadOnly() {
        assertThat(resolver.isReadOnly("nq_some_query")).isTrue();
    }

    @Test
    void listPrefix_isReadOnly() {
        assertThat(resolver.isReadOnly("list_models")).isTrue();
    }

    @Test
    void getPrefix_isReadOnly() {
        assertThat(resolver.isReadOnly("get_record")).isTrue();
    }

    @Test
    void platformExecuteSql_isReadOnly() {
        assertThat(resolver.isReadOnly("platform_execute_sql")).isTrue();
    }

    @Test
    void platformListModels_isReadOnly() {
        assertThat(resolver.isReadOnly("platform_list_models")).isTrue();
    }

    @Test
    void platformModelSuggest_isReadOnly() {
        assertThat(resolver.isReadOnly("platform_model_suggest")).isTrue();
    }

    @Test
    void platformCreateModel_isNotReadOnly() {
        assertThat(resolver.isReadOnly("platform_create_model")).isFalse();
    }

    @Test
    void cmdPrefix_isNotReadOnly() {
        assertThat(resolver.isReadOnly("cmd__crm_lead__update")).isFalse();
    }

    @Test
    void discoveredReadOnlyCommand_isReadOnlyAndMapsToProviderCode() {
        GroundingPort groundingPort = (tenantId, userMessage, pageModel, recordId) ->
                new GroundingPort.GroundingResult("create", "crm_lead", 0.9, List.of(), false);
        ToolDiscoveryPort toolDiscoveryPort = new ToolDiscoveryPort() {
            @Override
            public List<ToolDef> discoverAlwaysOnTools(Long tenantId, String channel) {
                // These cases exercise discovered tools on a normal channel; no always-on provider.
                return List.of();
            }

            @Override
            public List<ToolDef> discoverTools(Long tenantId, List<String> candidateSkills,
                                               String modelHint, String intentHint, int maxTools, String channel) {
                return List.of(new ToolDef(
                        "cmd:crm:list_leads",
                        "List Leads",
                        "Query CRM leads",
                        Map.of("type", "object"),
                        true
                ));
            }
        };
        ChatToolResolver mappedResolver = new ChatToolResolver(groundingPort, toolDiscoveryPort, null);

        MetaContext.setSystemTenantContext(1L);
        ChatToolResolver.ResolvedTools resolved;
        try {
            resolved = mappedResolver.resolveTools("list leads", "crm_lead", null, null);
            // Tool metadata is tenant-scoped (IMPL-06). The executor reads it back within
            // the same tenant context it used to resolve, so assert here (before clear)
            // rather than after — a cross-tenant read is exactly the bug being prevented.
            assertThat(mappedResolver.isReadOnly("cmd_crm_list_leads")).isTrue();
            assertThat(mappedResolver.getProviderToolCode("cmd_crm_list_leads"))
                    .isEqualTo("cmd:crm:list_leads");
        } finally {
            MetaContext.clear();
        }

        assertThat(resolved.tools()).anySatisfy(tool ->
                assertThat(tool.getName()).isEqualTo("cmd_crm_list_leads"));
    }

    @Test
    void resolveTools_hidesSqlWhenDomainReadToolExists() {
        GroundingPort groundingPort = (tenantId, userMessage, pageModel, recordId) ->
                new GroundingPort.GroundingResult("query", "crm_lead", 0.9, List.of(), true);
        ToolDiscoveryPort toolDiscoveryPort = new ToolDiscoveryPort() {
            @Override
            public List<ToolDef> discoverAlwaysOnTools(Long tenantId, String channel) {
                // These cases exercise discovered tools on a normal channel; no always-on provider.
                return List.of();
            }

            @Override
            public List<ToolDef> discoverTools(Long tenantId, List<String> candidateSkills,
                                               String modelHint, String intentHint, int maxTools, String channel) {
                return List.of(
                        new ToolDef("list:crm_lead", "List Leads", "Query CRM leads", Map.of("type", "object"), true),
                        new ToolDef("platform.execute_sql", "Execute SQL", "SQL fallback", Map.of("type", "object"), true)
                );
            }
        };
        ChatToolResolver mappedResolver = new ChatToolResolver(groundingPort, toolDiscoveryPort, null);

        MetaContext.setSystemTenantContext(1L);
        ChatToolResolver.ResolvedTools resolved;
        try {
            resolved = mappedResolver.resolveTools("list leads", "crm_lead", null, null);
        } finally {
            MetaContext.clear();
        }

        assertThat(resolved.tools()).extracting(com.auraboot.framework.agent.dto.LlmChatRequest.Tool::getName)
                .contains("list_crm_lead")
                .doesNotContain("platform_execute_sql");
    }

    @Test
    void resolveTools_propagatesGroundingFailureInsteadOfReturningEmptyTools() {
        GroundingPort groundingPort = (tenantId, userMessage, pageModel, recordId) -> {
            throw new IllegalStateException("grounding unavailable");
        };
        ToolDiscoveryPort toolDiscoveryPort =
                discoveryPort((tenantId, candidateSkills, modelHint, intentHint, maxTools, channel) -> List.of());
        ChatToolResolver mappedResolver = new ChatToolResolver(groundingPort, toolDiscoveryPort, null);

        MetaContext.setSystemTenantContext(1L);
        try {
            assertThatThrownBy(() -> mappedResolver.resolveTools("list leads", "crm_lead", null, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AuraBot tool resolution failed")
                    .hasRootCauseMessage("grounding unavailable");
        } finally {
            MetaContext.clear();
        }
    }

    @Test
    void resolveTools_propagatesToolDiscoveryFailureInsteadOfReturningEmptyTools() {
        GroundingPort groundingPort = (tenantId, userMessage, pageModel, recordId) ->
                new GroundingPort.GroundingResult("query", "crm_lead", 0.9, List.of("list:crm_lead"), true);
        ToolDiscoveryPort toolDiscoveryPort =
                discoveryPort((tenantId, candidateSkills, modelHint, intentHint, maxTools, channel) -> {
                    throw new IllegalStateException("tool registry unavailable");
                });
        ChatToolResolver mappedResolver = new ChatToolResolver(groundingPort, toolDiscoveryPort, null);

        MetaContext.setSystemTenantContext(1L);
        try {
            assertThatThrownBy(() -> mappedResolver.resolveTools("list leads", "crm_lead", null, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AuraBot tool resolution failed")
                    .hasRootCauseMessage("tool registry unavailable");
        } finally {
            MetaContext.clear();
        }
    }

    @Test
    void toolMetadataCache_isTenantScoped_noCrossTenantOverwrite() {
        // IMPL-06: the resolver is a process-wide singleton. The same wire tool name in
        // two tenants carries OPPOSITE read-only metadata; without tenant-scoped cache
        // keys, tenant 2's resolve would clobber tenant 1's entry and isReadOnly would
        // return the wrong tenant's value.
        GroundingPort grounding = (t, msg, pm, rid) ->
                new GroundingPort.GroundingResult("query", "crm_lead", 0.9, List.of(), true);
        ToolDiscoveryPort discovery = discoveryPort((tenantId, skills, modelHint, intentHint, maxTools, channel) ->
                List.of(new ToolDiscoveryPort.ToolDef(
                        "cmd:crm:widget",
                        "Widget",
                        "tenant-specific tool",
                        Map.of("type", "object"),
                        tenantId != null && tenantId == 1L))); // readOnly only for tenant 1
        ChatToolResolver r = new ChatToolResolver(grounding, discovery, null);

        MetaContext.setSystemTenantContext(1L);
        try {
            r.resolveTools("x", "crm_lead", null, null);
        } finally {
            MetaContext.clear();
        }
        MetaContext.setSystemTenantContext(2L);
        try {
            r.resolveTools("x", "crm_lead", null, null);
        } finally {
            MetaContext.clear();
        }

        // Under tenant 1 the tool must still read as tenant 1's value (read-only),
        // not tenant 2's (writable).
        MetaContext.setSystemTenantContext(1L);
        try {
            assertThat(r.isReadOnly("cmd_crm_widget"))
                    .as("tenant 2 must not overwrite tenant 1's tool metadata")
                    .isTrue();
        } finally {
            MetaContext.clear();
        }
        // Under tenant 2, its own value (writable).
        MetaContext.setSystemTenantContext(2L);
        try {
            assertThat(r.isReadOnly("cmd_crm_widget")).isFalse();
        } finally {
            MetaContext.clear();
        }
    }
}
