package com.auraboot.framework.semantic.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.semantic.compiler.UserContext;
import com.auraboot.framework.semantic.dto.SemanticQueryResponse;
import com.auraboot.framework.semantic.entity.AbSemanticModel;
import com.auraboot.framework.semantic.entity.AbSemanticPreagg;
import com.auraboot.framework.semantic.mapper.AbSemanticModelMapper;
import com.auraboot.framework.semantic.mapper.AbSemanticPreaggMapper;
import com.auraboot.framework.userattribute.service.UserAttributeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SemanticPreaggContextTest {
    private final AbSemanticPreaggMapper preaggs = mock(AbSemanticPreaggMapper.class);
    private final AbSemanticModelMapper models = mock(AbSemanticModelMapper.class);
    private final SemanticQueryService queries = mock(SemanticQueryService.class);
    private final UserAttributeService attributes = mock(UserAttributeService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SemanticPreaggService service = new SemanticPreaggService(
            preaggs, models, queries, attributes, jdbc, new ObjectMapper());

    @BeforeEach
    void prepareCompiler() {
        MetaContext.clear();
        AbSemanticModel model = new AbSemanticModel();
        model.setPid("model-pid");
        model.setCode("context_metric");
        when(models.findByPid(anyLong(), anyString())).thenReturn(model);
        when(attributes.getAttributes(anyLong(), anyLong())).thenReturn(Map.of());
        when(queries.explainQuery(any(), any())).thenReturn(explained());
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(1L);
    }

    @AfterEach
    void clearContext() {
        MetaContext.clear();
    }

    @Test
    void createRetainsTheCallerForTheDefinitionInsert() {
        MetaContext.setContext(11L, 12L, "caller-pid", "caller", Set.of(77L));
        MetaContext.setEnvironmentId(8L);
        MetaContext.setOtelTraceId("preagg-context-test");
        MetaContext.Snapshot caller = MetaContext.snapshot();
        when(preaggs.insert(any(AbSemanticPreagg.class))).thenAnswer(invocation -> {
            assertThat(MetaContext.snapshot()).isEqualTo(caller);
            return 1;
        });

        AbSemanticPreagg created = service.create("Context contract", "model-pid", "count", List.of(), 1);

        assertThat(created.getTenantId()).isEqualTo(11L);
        assertThat(created.getCreatedBy()).isEqualTo(12L);
        assertThat(MetaContext.snapshot()).isEqualTo(caller);
    }

    @Test
    void failedRefreshRestoresCallerIdentityAndCommandPermit() {
        MetaContext.setContext(11L, 99L, "request-pid", "request", Set.of(77L));
        MetaContext.Snapshot caller = MetaContext.snapshot();
        AbSemanticPreagg definition = due("preagg-one", 11L, 12L);
        when(preaggs.findByPid(11L, definition.getPid())).thenReturn(definition);
        when(queries.explainQuery(any(), any())).thenThrow(new IllegalStateException("compiler unavailable"));

        MetaContext.runWithCommandPermitScope("DIRECT", () -> {
            assertThatThrownBy(() -> service.refreshNow(definition.getPid()))
                    .isInstanceOf(IllegalStateException.class).hasMessage("compiler unavailable");
            assertThat(MetaContext.snapshot()).isEqualTo(caller);
            assertThat(MetaContext.getCommandPermitScope()).isEqualTo("DIRECT");
        });
    }

    @Test
    void schedulerWithoutCallerBindsEachTenantAndLeavesNoContext() {
        AbSemanticPreagg first = due("preagg-one", 11L, 12L);
        AbSemanticPreagg second = due("preagg-two", 21L, 22L);
        when(preaggs.listAllAcrossTenants()).thenReturn(List.of(first, second));
        when(preaggs.findByPid(11L, first.getPid())).thenReturn(first);
        when(preaggs.findByPid(21L, second.getPid())).thenReturn(second);
        Set<Long> compiledTenants = new HashSet<>();
        when(queries.explainQuery(any(), any())).thenAnswer(invocation -> {
            UserContext creator = invocation.getArgument(1);
            assertThat(MetaContext.getCurrentTenantId()).isEqualTo(creator.tenantId());
            assertThat(MetaContext.getCurrentUserId()).isEqualTo(creator.userId());
            compiledTenants.add(creator.tenantId());
            return explained();
        });

        service.refreshAllDue();

        assertThat(compiledTenants).containsExactlyInAnyOrder(11L, 21L);
        verify(preaggs).updateById(first);
        verify(preaggs).updateById(second);
        assertThat(MetaContext.exists()).isFalse();
    }

    @Test
    void foregroundSweepRestoresItsCallerAndPermitAfterEachRefresh() {
        MetaContext.setContext(11L, 99L, "request-pid", "request", Set.of(77L));
        MetaContext.Snapshot caller = MetaContext.snapshot();
        AbSemanticPreagg definition = due("preagg-one", 11L, 12L);
        when(preaggs.listAllAcrossTenants()).thenReturn(List.of(definition));
        when(preaggs.findByPid(11L, definition.getPid())).thenReturn(definition);

        MetaContext.runWithCommandPermitScope("DIRECT", () -> {
            service.refreshAllDue();
            assertThat(MetaContext.snapshot()).isEqualTo(caller);
            assertThat(MetaContext.getCommandPermitScope()).isEqualTo("DIRECT");
        });
    }

    @Test
    void notDueDefinitionDoesNotCompileOrDisturbCaller() {
        MetaContext.setContext(11L, 99L, "request-pid", "request");
        MetaContext.Snapshot caller = MetaContext.snapshot();
        AbSemanticPreagg definition = due("preagg-one", 11L, 12L);
        definition.setLastRefreshedAt(OffsetDateTime.now());
        when(preaggs.listAllAcrossTenants()).thenReturn(List.of(definition));

        service.refreshAllDue();

        verify(queries, never()).explainQuery(any(), any());
        assertThat(MetaContext.snapshot()).isEqualTo(caller);
    }

    private static AbSemanticPreagg due(String pid, Long tenant, Long creator) {
        AbSemanticPreagg definition = new AbSemanticPreagg();
        definition.setPid(pid);
        definition.setTenantId(tenant);
        definition.setCreatedBy(creator);
        definition.setSemanticModelPid("model-pid");
        definition.setMetricCode("count");
        definition.setDimensionCodes("[]");
        definition.setMvName("mv_" + pid.replace('-', '_'));
        definition.setRefreshMinutes(1);
        definition.setLastRefreshedAt(OffsetDateTime.now().minusMinutes(2));
        return definition;
    }

    private static SemanticQueryResponse explained() {
        SemanticQueryResponse response = new SemanticQueryResponse();
        response.setSql("SELECT ? AS metric");
        response.setParams(List.of(42));
        return response;
    }
}
