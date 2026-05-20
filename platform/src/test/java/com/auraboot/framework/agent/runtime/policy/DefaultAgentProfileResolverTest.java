package com.auraboot.framework.agent.runtime.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DefaultAgentProfileResolver")
class DefaultAgentProfileResolverTest {

    private final DefaultAgentProfileResolver resolver = DefaultAgentProfileResolver.INSTANCE;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("resolves profile permissions, evidence-first flag and context policy from guardrails")
    void resolvesProfilePermissionsAndContextPolicy() {
        AgentProfile profile = resolver.resolve(objectMapper, Map.of(
                "agent_code", "sales_agent",
                "guardrails", """
                        {
                          "profilePermissions": ["crm.customer.read", "crm.customer.update"],
                          "evidenceFirst": true,
                          "contextPolicy": {
                            "scopes": ["page", "record", "rag"],
                            "allowSensitiveContext": false,
                            "capabilityCeiling": "READ_ONLY",
                            "toolExposure": "READ_ONLY_CATALOG"
                          }
                        }
                        """));

        assertThat(profile.agentCode()).isEqualTo("sales_agent");
        assertThat(profile.profilePermissions())
                .containsExactlyInAnyOrder("crm.customer.read", "crm.customer.update");
        assertThat(profile.evidenceFirst()).isTrue();
        assertThat(profile.contextPolicy().scopes()).containsExactlyInAnyOrder("page", "record", "rag");
        assertThat(profile.contextPolicy().allowSensitiveContext()).isFalse();
        assertThat(profile.contextPolicy().capabilityCeiling()).isEqualTo(ToolCapabilityCeiling.READ_ONLY);
        assertThat(profile.contextPolicy().toolExposure()).isEqualTo(ToolExposure.READ_ONLY_CATALOG);
    }

    @Test
    @DisplayName("invalid guardrails fail closed to an empty policy")
    void invalidGuardrailsFailClosedToEmptyPolicy() {
        AgentProfile profile = resolver.resolve(objectMapper, Map.of(
                "agent_code", "support_agent",
                "guardrails", "{not-json"));

        assertThat(profile.agentCode()).isEqualTo("support_agent");
        assertThat(profile.profilePermissions()).isNull();
        assertThat(profile.evidenceFirst()).isFalse();
        assertThat(profile.contextPolicy()).isEqualTo(AgentContextPolicy.defaults());
    }

    @Test
    @DisplayName("context policy accepts comma separated scope shorthand")
    void contextPolicyAcceptsCommaSeparatedScopeShorthand() {
        AgentProfile profile = resolver.resolve(objectMapper, Map.of(
                "agent_code", "sales_agent",
                "guardrails", Map.of(
                        "permissions", "crm.customer.read",
                        "contextScopes", "page,record")));

        assertThat(profile.profilePermissions()).isEqualTo(Set.of("crm.customer.read"));
        assertThat(profile.contextPolicy().scopes()).containsExactlyInAnyOrder("page", "record");
    }
}
