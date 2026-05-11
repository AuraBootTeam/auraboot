package com.auraboot.framework.agent.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AgentRuntimeObservabilityService")
class AgentRuntimeObservabilityServiceTest {

    @Test
    @DisplayName("records runtime metrics with stable low-cardinality tags")
    void recordsRuntimeMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentRuntimeObservabilityService service = new AgentRuntimeObservabilityService(registry);

        service.recordToolDiscovery("skill", true, 3);
        service.recordToolExecution("platform", true, "executed");
        service.recordAuthorizationDecision("incremental", "granted");
        service.recordResultContract("structured_result", "table", "success", true);
        service.recordUnsupportedToolType("vendor_magic");

        assertThat(registry.get("aurabot.agent.tool.discovery.calls")
                .tag("source", "skill")
                .tag("query_only", "true")
                .counter()
                .count()).isEqualTo(1.0);
        assertThat(registry.get("aurabot.agent.tool.discovery.tools")
                .summary()
                .totalAmount()).isEqualTo(3.0);
        assertThat(registry.get("aurabot.agent.tool.execution")
                .tag("tool_type", "platform")
                .tag("outcome", "success")
                .tag("stage", "executed")
                .counter()
                .count()).isEqualTo(1.0);
        assertThat(registry.get("aurabot.agent.authorization.decision")
                .tag("kind", "incremental")
                .tag("decision", "granted")
                .counter()
                .count()).isEqualTo(1.0);
        assertThat(registry.get("aurabot.agent.result_contract")
                .tag("output_type", "structured_result")
                .tag("render_hint", "table")
                .tag("status", "success")
                .tag("emitted", "true")
                .counter()
                .count()).isEqualTo(1.0);
        assertThat(registry.get("aurabot.agent.tool.unsupported_type")
                .tag("tool_type", "vendor_magic")
                .counter()
                .count()).isEqualTo(1.0);
    }
}
