package com.auraboot.framework.agent.provider;

import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CustomToolProviderTest {

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
