package com.auraboot.framework.agent.service;

import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ObjectResolverOpeningTest {
    @Test
    void tenantModelNamesStayInTheirOwnCache() {
        DynamicDataMapper mapper = mock(DynamicDataMapper.class);
        when(mapper.selectByQueryWithoutTenant(anyString(), anyMap())).thenReturn(List.of());
        when(mapper.selectByQuery(anyString(), anyMap())).thenAnswer(call -> {
            String sql = call.getArgument(0);
            Map<String, Object> params = call.getArgument(1);
            if (sql.contains("ab_meta_model")) {
                assertThat(sql).contains("tenant_id = #{params.tenantId}");
                return Long.valueOf(1L).equals(params.get("tenantId"))
                        ? List.of(Map.of("code", "private_model", "display_name", "私有分析建议"))
                        : List.of();
            }
            return List.of();
        });
        ObjectResolver resolver = new ObjectResolver(mapper);
        resolver.rebuildIndex();
        assertThat(resolver.resolve(1L, "创建私有分析建议").getModelCode()).isEqualTo("private_model");
        assertThat(resolver.resolve(2L, "创建私有分析建议").getModelCode()).isNull();
        assertThat(resolver.resolve(2L, "private_model").getModelCode()).isNull();
        verify(mapper).selectByQueryWithoutTenant(
                contains("tenant_id IN (-1, 0)"), anyMap());
    }

    @Test
    void openingTargetWinsOverLongerCodesInAttachedQueryData() {
        DynamicDataMapper mapper = mock(DynamicDataMapper.class);
        when(mapper.selectByQueryWithoutTenant(anyString(), anyMap())).thenAnswer(call ->
                ((String) call.getArgument(0)).contains("ab_meta_model")
                        ? List.of(Map.of("code", "core_dashboard_suggestion", "display_name", "分析建议版本"),
                                  Map.of("code", "e2et_order", "display_name", "订单"))
                        : List.of());
        when(mapper.selectByQuery(anyString(), anyMap())).thenReturn(List.of());
        ObjectResolver resolver = new ObjectResolver(mapper);
        resolver.rebuildIndex();
        assertThat(resolver.resolve(1L, "创建分析建议版本。\nquery modelCode=e2et_order").getModelCode())
                .isEqualTo("core_dashboard_suggestion");
        assertThat(resolver.resolve(1L, "查看订单\ncore_dashboard_suggestion").getModelCode())
                .isEqualTo("e2et_order");
        assertThat(resolver.resolve(1L, "请求如下\ncore_dashboard_suggestion").getModelCode())
                .isEqualTo("core_dashboard_suggestion");
    }
}
