package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.provider.ToolDefinition;
import com.auraboot.framework.agent.provider.ToolProviderRegistry;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeclaredAgentToolResolverTest {

    private final ToolProviderRegistry registry = mock(ToolProviderRegistry.class);
    private final DynamicDataMapper mapper = mock(DynamicDataMapper.class);
    private final UserPermissionService permissionService = mock(UserPermissionService.class);
    private final DeclaredAgentToolResolver resolver =
            new DeclaredAgentToolResolver(registry, mapper, new ObjectMapper(), permissionService);

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
    void resolvesDeclaredCommandDirectlyWhenModelDiscoveryMissesIt() {
        when(mapper.selectByQuery(argThat(sql -> sql != null && sql.contains("SELECT model_code")),
                any()))
                .thenReturn(List.of(Map.of("model_code", "crm_activity_common")));
        when(registry.discoverAll(any())).thenReturn(List.of());
        when(mapper.selectByQuery(argThat(sql -> sql != null && sql.contains("SELECT code, display_name")),
                any()))
                .thenReturn(List.of(Map.of(
                        "code", "crm:create_activity",
                        "display_name", "Create Activity",
                        "agent_hint", "Create a CRM activity",
                        "model_code", "crm_activity_common",
                        "input_schema", "{\"type\":\"object\","
                                + "\"properties\":{\"crm_act_subject\":{\"type\":\"string\"}}}",
                        "execution_config", "{}",
                        "cmd_risk_level", "L1"
                )));

        List<ToolDefinition> resolved = resolver.resolveDeclaredTools(
                7L, 1L, "cs_agent", List.of("cmd:crm:create_activity"));

        assertThat(resolved).hasSize(1);
        ToolDefinition tool = resolved.get(0);
        assertThat(tool.getToolCode()).isEqualTo("cmd:crm:create_activity");
        assertThat(tool.getToolType()).isEqualTo("dsl_command");
        assertThat(tool.getSourceCode()).isEqualTo("crm:create_activity");
        assertThat(tool.getRiskLevel()).isEqualTo("L1");
    }

    @Test
    void onlyReturnsDeclaredCodesNotEveryDiscoveredTool() {
        when(mapper.selectByQuery(anyString(), any()))
                .thenReturn(List.of(Map.of("model_code", "crm_activity_common")));
        ToolDefinition wanted = ToolDefinition.builder()
                .toolCode("cmd:crm:create_activity")
                .providerCode("dsl")
                .build();
        ToolDefinition other = ToolDefinition.builder()
                .toolCode("cmd:crm:delete_activity")
                .providerCode("dsl")
                .build();
        when(registry.discoverAll(any())).thenReturn(List.of(wanted, other));

        List<ToolDefinition> resolved = resolver.resolveDeclaredTools(
                7L, 1L, "cs_agent", List.of("cmd:crm:create_activity"));

        assertThat(resolved).extracting(ToolDefinition::getToolCode).containsExactly("cmd:crm:create_activity");
    }

    @Test
    void resolvesDeclaredNamedQueryDirectlyWhenRegistryCannotDiscoverWithoutModelHint() {
        when(registry.discoverAll(any())).thenReturn(List.of());
        when(permissionService.hasPermission(1L, MetaPermission.QUERY_READ)).thenReturn(true);
        when(mapper.selectByQuery(anyString(), any()))
                .thenReturn(List.of(Map.of(
                        "code", "qc_quality_capa_context",
                        "title", "CAPA Context",
                        "purpose", "Find CAPA context",
                        "from_sql", "SELECT * FROM mt_qc_capa WHERE tenant_id = #{params.tenantId} "
                                + "AND pid = #{params.recordPid}",
                        "parameter_schema", "{}"
                )));

        List<ToolDefinition> resolved = resolver.resolveDeclaredTools(
                7L, 1L, "qc_agent", List.of("nq:qc_quality_capa_context"));

        assertThat(resolved).hasSize(1);
        ToolDefinition tool = resolved.get(0);
        assertThat(tool.getToolCode()).isEqualTo("nq:qc_quality_capa_context");
        assertThat(tool.getToolType()).isEqualTo("dsl_query");
        assertThat(tool.getSourceCode()).isEqualTo("qc_quality_capa_context");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) tool.getParameterSchema().get("properties");
        assertThat(properties).containsKey("recordPid");
        assertThat(properties).doesNotContainKeys("record" + "Id", "pid", "id");
    }

    @Test
    void resolvesDeclaredCustomToolDirectlyWhenAggregateDiscoveryMissesIt() {
        List<ToolDefinition> cappedOut = IntStream.range(0, 100)
                .mapToObj(i -> ToolDefinition.builder()
                        .toolCode("custom:unrelated_" + i)
                        .providerCode("custom")
                        .build())
                .toList();
        when(registry.discoverAll(any())).thenReturn(cappedOut);
        when(mapper.selectByQueryWithoutTenant(
                argThat(sql -> sql != null && sql.contains("FROM ab_agent_tool")),
                any()))
                .thenReturn(List.of(Map.of(
                        "tool_code", "send_customer_reply",
                        "tool_name", "Send Customer Reply",
                        "tool_description", "Send a professional customer email reply",
                        "tool_type", "built_in",
                        "input_schema", "{\"type\":\"object\","
                                + "\"properties\":{\"recipient_email\":{\"type\":\"string\"}}}",
                        "requires_approval", true,
                        "risk_level", "L2"
                )));

        List<ToolDefinition> resolved = resolver.resolveDeclaredTools(
                7L, 1L, "cs_agent", List.of("custom:send_customer_reply"));

        assertThat(resolved).hasSize(1);
        ToolDefinition tool = resolved.getFirst();
        assertThat(tool.getToolCode()).isEqualTo("custom:send_customer_reply");
        assertThat(tool.getProviderCode()).isEqualTo("custom");
        assertThat(tool.getToolType()).isEqualTo("built_in");
        assertThat(tool.isRequiresApproval()).isTrue();
        assertThat(tool.getRiskLevel()).isEqualTo("L2");
        assertThat(tool.getParameterSchema()).containsEntry("type", "object");
    }

    @Test
    void resolvesDeclaredProviderToolByProviderDiscoveryWhenAggregateDiscoveryMissesIt() {
        when(registry.discoverAll(any())).thenReturn(List.of());
        ToolDefinition delegated = ToolDefinition.builder()
                .toolCode("platform.delegate_task")
                .providerCode("platform")
                .toolType("platform")
                .build();
        when(registry.discoverByProvider(eq("platform"), any())).thenReturn(List.of(delegated));

        List<ToolDefinition> resolved = resolver.resolveDeclaredTools(
                7L, 1L, "planner", List.of("platform.delegate_task"));

        assertThat(resolved).extracting(ToolDefinition::getToolCode)
                .containsExactly("platform.delegate_task");
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
