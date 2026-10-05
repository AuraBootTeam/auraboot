package com.auraboot.framework.decision;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.decision.entity.*;
import com.auraboot.framework.decision.mapper.*;
import com.auraboot.framework.decision.service.impl.DecisionEvaluationServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class DecisionLogBusinessNameTest {
    @Mock DrtLogMapper logs;
    @Mock DrtDefinitionMapper definitions;
    @InjectMocks DecisionEvaluationServiceImpl service;
    @BeforeAll static void metadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                DrtDefinitionEntity.class);
    }
    @BeforeEach void context() { MetaContext.setContext(123L,456L,"user","owner"); }
    @AfterEach void clear() { MetaContext.clear(); }
    private DrtLogEntity log(String pid) {
        var row=new DrtLogEntity();row.setPid(pid);row.setTenantId(123L);row.setDecisionCode("sla_deadline");return row;
    }
    private DrtDefinitionEntity definition(long tenant,String name) {
        var row=new DrtDefinitionEntity();row.setTenantId(tenant);row.setDecisionCode("sla_deadline");row.setDecisionName(name);return row;
    }
    @Test void traceNamesComeFromOneTenantScopedCatalogueLookup() {
        when(logs.findByTraceId(123L,"trace")).thenReturn(List.of(log("one"),log("two")));
        when(definitions.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(definition(123L,"Business SLA")));
        var result=service.findLogsByTraceId("trace");
        assertThat(result).hasSize(2).allSatisfy(row -> {
            assertThat(row.getDecisionName()).isEqualTo("Business SLA");
            assertThat(row.getDecisionCode()).isEqualTo("sla_deadline");
        });
        ArgumentCaptor<LambdaQueryWrapper<DrtDefinitionEntity>> wrapper=ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(definitions,times(1)).selectList(wrapper.capture());
        assertThat(wrapper.getValue().getSqlSegment()).contains("tenant_id", "decision_code");
        assertThat(wrapper.getValue().getParamNameValuePairs().values()).contains(123L,"sla_deadline");
    }
    @Test void foreignTenantOrMissingCatalogueNeverSuppliesDisplayNames() {
        when(logs.findByTraceId(123L,"trace")).thenReturn(List.of(log("one")));
        when(definitions.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(definition(999L,"Foreign secret")),List.of());
        // A foreign row inside a tenant-scoped catalogue response is tampering: the
        // lookup fails closed instead of leaking the foreign display name.
        assertThatThrownBy(() -> service.findLogsByTraceId("trace"))
                .isInstanceOf(IllegalStateException.class);
        // Missing catalogue entries leave the business name empty rather than a code.
        assertThat(service.findLogsByTraceId("trace").getFirst().getDecisionName()).isNull();
    }
}
