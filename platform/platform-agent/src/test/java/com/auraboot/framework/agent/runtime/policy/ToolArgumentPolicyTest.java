package com.auraboot.framework.agent.runtime.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ToolArgumentPolicy")
class ToolArgumentPolicyTest {

    private final ToolArgumentPolicy policy = new ToolArgumentPolicy();

    @Test
    @DisplayName("normalizes null args to immutable empty map")
    void normalizesNullArgs() {
        Map<String, Object> normalized = policy.normalize(null);

        assertThat(normalized).isEmpty();
    }

    @Test
    @DisplayName("hashes args deterministically independent of insertion order")
    void hashesArgsDeterministically() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("name", "Acme");
        first.put("industry", "software");

        Map<String, Object> second = new LinkedHashMap<>();
        second.put("industry", "software");
        second.put("name", "Acme");

        assertThat(policy.hash(policy.normalize(first)))
                .isEqualTo(policy.hash(policy.normalize(second)));
    }
}
