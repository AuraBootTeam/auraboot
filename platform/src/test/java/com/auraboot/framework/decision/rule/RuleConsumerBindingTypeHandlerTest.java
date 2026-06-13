package com.auraboot.framework.decision.rule;

import com.auraboot.framework.decision.ast.Scope;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuleConsumerBindingTypeHandlerTest {

    @Test
    void parsesCaseInsensitiveEnumValuesFromJsonb() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("rule_binding")).thenReturn("""
                {
                  "consumerType": "SLA",
                  "consumerCode": "sla-1",
                  "consumerNodeId": "deadline",
                  "bindingKind": "decision_ref",
                  "enabled": true,
                  "decisionBinding": {
                    "decisionCode": "complaint_sla_deadline",
                    "versionPolicy": "latest_published",
                    "inputMappings": [
                      { "input": "targetKey", "source": { "kind": "field", "scope": "record", "path": "data.targetKey" } }
                    ],
                    "fallbackPolicy": { "mode": "fail_closed" },
                    "traceMode": "sampled",
                    "enabled": true
                  }
                }
                """);

        RuleConsumerBinding binding = new RuleConsumerBindingTypeHandler()
                .getNullableResult(rs, "rule_binding");

        assertThat(binding.bindingKind()).isEqualTo(RuleBindingKind.DECISION_REF);
        assertThat(binding.decisionBinding().versionPolicy()).isEqualTo(DecisionVersionPolicy.LATEST_PUBLISHED);
        assertThat(binding.decisionBinding().fallbackPolicy().mode())
                .isEqualTo(DecisionBinding.FallbackMode.FAIL_CLOSED);
        assertThat(binding.decisionBinding().traceMode()).isEqualTo(DecisionBinding.TraceMode.SAMPLED);
        assertThat(binding.decisionBinding().inputMappings()).hasSize(1);
        RuleValueSource source = binding.decisionBinding().inputMappings().get(0).source();
        assertThat(source.kind()).isEqualTo(RuleValueSource.Kind.FIELD);
        assertThat(source.scope()).isEqualTo(Scope.RECORD);
        assertThat(source.path()).isEqualTo("data.targetKey");
    }
}
