package com.auraboot.framework.aurabot.service;

import com.auraboot.framework.agent.port.GroundingPort;
import com.auraboot.framework.agent.port.ToolDiscoveryPort;
import com.auraboot.framework.application.tenant.MetaContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ChatToolResolver#isReadOnly(String)}.
 * Verifies read-only classification for provider tool naming conventions.
 */
class ChatToolResolverIsReadOnlyTest {

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
            public List<ToolDef> discoverTools(Long tenantId, List<String> candidateSkills,
                                               String modelHint, String intentHint, int maxTools) {
                return List.of(new ToolDef(
                        "cmd:crm:list_leads",
                        "List Leads",
                        "Query CRM leads",
                        Map.of("type", "object"),
                        true
                ));
            }

            @Override
            public Map<String, Object> executeTool(Long tenantId, String toolCode, Map<String, Object> params) {
                return Map.of();
            }
        };
        ChatToolResolver mappedResolver = new ChatToolResolver(groundingPort, toolDiscoveryPort, null);

        MetaContext.setSystemTenantContext(1L);
        ChatToolResolver.ResolvedTools resolved;
        try {
            resolved = mappedResolver.resolveTools("list leads", "crm_lead", null);
        } finally {
            MetaContext.clear();
        }

        assertThat(resolved.tools()).anySatisfy(tool ->
                assertThat(tool.getName()).isEqualTo("cmd_crm_list_leads"));
        assertThat(mappedResolver.isReadOnly("cmd_crm_list_leads")).isTrue();
        assertThat(mappedResolver.getProviderToolCode("cmd_crm_list_leads"))
                .isEqualTo("cmd:crm:list_leads");
    }

    @Test
    void resolveTools_hidesSqlWhenDomainReadToolExists() {
        GroundingPort groundingPort = (tenantId, userMessage, pageModel, recordId) ->
                new GroundingPort.GroundingResult("query", "crm_lead", 0.9, List.of(), true);
        ToolDiscoveryPort toolDiscoveryPort = new ToolDiscoveryPort() {
            @Override
            public List<ToolDef> discoverTools(Long tenantId, List<String> candidateSkills,
                                               String modelHint, String intentHint, int maxTools) {
                return List.of(
                        new ToolDef("list:crm_lead", "List Leads", "Query CRM leads", Map.of("type", "object"), true),
                        new ToolDef("platform.execute_sql", "Execute SQL", "SQL fallback", Map.of("type", "object"), true)
                );
            }

            @Override
            public Map<String, Object> executeTool(Long tenantId, String toolCode, Map<String, Object> params) {
                return Map.of();
            }
        };
        ChatToolResolver mappedResolver = new ChatToolResolver(groundingPort, toolDiscoveryPort, null);

        MetaContext.setSystemTenantContext(1L);
        ChatToolResolver.ResolvedTools resolved;
        try {
            resolved = mappedResolver.resolveTools("list leads", "crm_lead", null);
        } finally {
            MetaContext.clear();
        }

        assertThat(resolved.tools()).extracting(com.auraboot.framework.agent.dto.LlmChatRequest.Tool::getName)
                .contains("list_crm_lead")
                .doesNotContain("platform_execute_sql");
    }
}
