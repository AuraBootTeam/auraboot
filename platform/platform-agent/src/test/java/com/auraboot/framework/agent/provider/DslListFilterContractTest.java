package com.auraboot.framework.agent.provider;

import static org.assertj.core.api.Assertions.assertThat;
import com.auraboot.framework.agent.service.DeclaredAgentToolResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

class DslListFilterContractTest {
    @Test
    void discoveredAndDeclaredListsExplainTheActualQueryConditionFields() {
        ObjectMapper json = new ObjectMapper();
        for (Class<?> type : new Class<?>[]{DslToolProvider.class, DeclaredAgentToolResolver.class}) {
            Object provider = Mockito.mock(type, Mockito.CALLS_REAL_METHODS);
            Map<String, Object> schema = ReflectionTestUtils.invokeMethod(provider, "listParameterSchema");
            var item = json.valueToTree(schema).path("properties").path("filters").path("items").path("properties");
            assertThat(item.path("fieldName").path("type").asText()).isEqualTo("string");
            assertThat(item.path("operator").path("enum").toString()).contains("EQ", "IS_NULL", "BETWEEN");
            assertThat(item.has("subConditions")).isTrue();
            assertThat(item.has("values")).isTrue();
            assertThat(item.has("field")).isFalse();
        }
    }
}
