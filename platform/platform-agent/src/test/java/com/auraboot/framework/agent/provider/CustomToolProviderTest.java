package com.auraboot.framework.agent.provider;

import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CustomToolProviderTest {

    @ParameterizedTest
    @ValueSource(strings = {"api_call", "custom_api"})
    void configuredApiTypesReachTheSameSsrfValidationWithoutNetwork(String toolType) {
        var mapper = mock(DynamicDataMapper.class);
        var provider = new CustomToolProvider(mapper, new ObjectMapper(), null);
        when(mapper.selectByQueryWithoutTenant(any(), any())).thenReturn(List.of(Map.of(
                "tool_type", toolType, "source_code", "POST file:///not-a-network-target")));

        var result = provider.execute(123L, "custom:owned-receipt", Map.of("requestId", "owned"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("URL scheme not allowed")
                .doesNotContain("Unsupported custom tool type");
    }

    @Test
    void unrelatedCustomTypesRemainUnsupported() {
        var mapper = mock(DynamicDataMapper.class);
        var provider = new CustomToolProvider(mapper, new ObjectMapper(), null);
        when(mapper.selectByQueryWithoutTenant(any(), any())).thenReturn(List.of(Map.of(
                "tool_type", "mcp_server", "source_code", "POST file:///not-a-network-target")));
        assertThat(provider.execute(123L, "custom:owned-receipt", Map.of()).getErrorMessage())
                .contains("Unsupported custom tool type");
    }

    @Test
    void discoverIncludesSchemaApprovalAndRiskMetadataFromAgentTool() {
        DynamicDataMapper dynamicDataMapper = mock(DynamicDataMapper.class);
        CustomToolProvider provider = new CustomToolProvider(dynamicDataMapper, new ObjectMapper(), null);
        String inputSchema = """
                {"type":"object","properties":{"recipient_email":{"type":"string"}},"required":["recipient_email"]}
                """;
        when(dynamicDataMapper.selectByQueryWithoutTenant(any(), any())).thenReturn(List.of(Map.of(
                "tool_code", "send_customer_reply",
                "tool_name", "Send Customer Reply Email",
                "tool_description", "Send a professional reply email to the customer.",
                "tool_type", "built_in",
                "input_schema", inputSchema,
                "requires_approval", true,
                "risk_level", "L2"
        )));

        List<ToolDefinition> tools = provider.discover(ToolDiscoveryContext.builder()
                .tenantId(123L)
                .maxResults(20)
                .build());

        assertThat(tools).hasSize(1);
        ToolDefinition tool = tools.getFirst();
        assertThat(tool.getToolCode()).isEqualTo("custom:send_customer_reply");
        assertThat(tool.isRequiresApproval()).isTrue();
        assertThat(tool.getRiskLevel()).isEqualTo("L2");
        assertThat(tool.getParameterSchema()).containsEntry("type", "object");
        assertThat(tool.getParameterSchema()).containsKey("properties");
        assertThat(tool.getParameterSchema()).containsKey("required");
    }
}
