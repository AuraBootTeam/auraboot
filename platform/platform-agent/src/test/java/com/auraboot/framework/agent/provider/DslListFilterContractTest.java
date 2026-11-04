package com.auraboot.framework.agent.provider;

import static org.assertj.core.api.Assertions.assertThat;
import com.auraboot.framework.agent.service.DeclaredAgentToolResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.List;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyMap;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

class DslListFilterContractTest {
    @Test
    void discoveredAndDeclaredListsExplainTheActualQueryConditionFields() {
        ObjectMapper json = new ObjectMapper();
        for (Class<?> type : new Class<?>[]{DslToolProvider.class, DeclaredAgentToolResolver.class}) {
            Object provider = Mockito.mock(type, Mockito.CALLS_REAL_METHODS);
            DynamicDataMapper mapper = Mockito.mock(DynamicDataMapper.class);
            Mockito.when(mapper.selectByQuery(anyString(), anyMap())).thenReturn(List.of(
                    Map.of("code", "tpm_ts_project_id", "data_type", "reference"),
                    Map.of("code", "tpm_ts_title", "data_type", "text")));
            ReflectionTestUtils.setField(provider, "dynamicDataMapper", mapper);
            Map<String, Object> schema = ReflectionTestUtils.invokeMethod(provider, "listParameterSchema", 42L, "tpm_task");
            var item = json.valueToTree(schema).path("properties").path("filters").path("items").path("properties");
            assertThat(item.path("fieldName").path("type").asText()).isEqualTo("string");
            assertThat(item.path("operator").path("enum").toString()).contains("EQ", "IS_NULL", "BETWEEN");
            assertThat(item.has("subConditions")).isTrue();
            assertThat(item.has("values")).isTrue();
            assertThat(item.has("field")).isFalse();
            assertThat(item.path("fieldName").path("enum").toString()).contains("tpm_ts_project_id", "tpm_ts_title").doesNotContain("tpm_tk_project_id");
            Mockito.verify(mapper).selectByQuery(Mockito.contains("m.tenant_id = #{params.tenantId}"),
                    Mockito.eq(Map.of("tenantId", 42L, "modelCode", "tpm_task")));
        }
    }
}
