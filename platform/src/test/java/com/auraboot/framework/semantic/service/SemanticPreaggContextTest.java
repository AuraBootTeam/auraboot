package com.auraboot.framework.semantic.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.semantic.dto.SemanticQueryResponse;
import com.auraboot.framework.semantic.entity.AbSemanticModel;
import com.auraboot.framework.semantic.entity.AbSemanticPreagg;
import com.auraboot.framework.semantic.exception.SemanticValidationException;
import com.auraboot.framework.semantic.mapper.AbSemanticModelMapper;
import com.auraboot.framework.semantic.mapper.AbSemanticPreaggMapper;
import com.auraboot.framework.userattribute.service.UserAttributeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Hermetic characterization of thread identity; real persistence is covered by SemanticPreaggIT. */
class SemanticPreaggContextTest {
    private AbSemanticPreaggMapper mapper;
    private SemanticQueryService queries;
    private SemanticPreaggService service;
    private AbSemanticPreagg preagg;
    private MetaContext.Snapshot caller;

    @BeforeEach
    void setup() {
        mapper = mock(AbSemanticPreaggMapper.class);
        AbSemanticModelMapper models = mock(AbSemanticModelMapper.class);
        queries = mock(SemanticQueryService.class);
        UserAttributeService attributes = mock(UserAttributeService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        service = new SemanticPreaggService(mapper, models, queries, attributes, jdbc, new ObjectMapper());
        AbSemanticModel model = new AbSemanticModel();
        model.setCode("orders");
        when(models.findByPid(1L, "model")).thenReturn(model);
        when(attributes.getAttributes(1L, 2L)).thenReturn(Map.of());
        SemanticQueryResponse explained = new SemanticQueryResponse();
        explained.setSql("SELECT 1 AS count");
        explained.setParams(List.of());
        when(queries.explainQuery(any(), any())).thenReturn(explained);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(1L);
        preagg = new AbSemanticPreagg();
        preagg.setPid("preagg");
        preagg.setTenantId(1L);
        preagg.setCreatedBy(2L);
        preagg.setSemanticModelPid("model");
        preagg.setMetricCode("count");
        preagg.setDimensionCodes("[]");
        preagg.setMvName("mv_preagg");
        preagg.setRefreshMinutes(1);
        preagg.setUpdatedAt(OffsetDateTime.now().minusMinutes(2));
        when(mapper.findByPid(1L, "preagg")).thenReturn(preagg);
        when(mapper.listAllAcrossTenants()).thenReturn(List.of(preagg));
        MetaContext.setContext(1L, 2L, "caller-pid", "caller", Set.of(8L));
        MetaContext.setMemberId(3L);
        MetaContext.setEnvironmentId(4L);
        MetaContext.setOtelTraceId("caller-trace");
        caller = MetaContext.snapshot();
    }

    @AfterEach
    void clearContext() { MetaContext.clear(); }

    @Test
    void createRestoresCompleteCallerIdentity() {
        service.create("Orders", "model", "count", List.of(), 60);
        assertThat(MetaContext.snapshot()).isEqualTo(caller);
        verify(mapper).insert(any(AbSemanticPreagg.class));
    }

    @Test
    void refreshRestoresCompleteCallerIdentity() {
        assertThat(service.refreshNow("preagg")).isEqualTo(1L);
        assertThat(MetaContext.snapshot()).isEqualTo(caller);
    }

    @Test
    void failedRefreshRestoresCompleteCallerIdentity() {
        when(queries.explainQuery(any(), any())).thenThrow(new IllegalStateException("compile failure"));
        assertThatThrownBy(() -> service.refreshNow("preagg"))
                .isInstanceOf(IllegalStateException.class).hasMessage("compile failure");
        assertThat(MetaContext.snapshot()).isEqualTo(caller);
    }

    @Test
    void schedulerRestoresCallerAndLeavesAnUnboundThreadUnbound() {
        service.refreshAllDue();
        assertThat(MetaContext.snapshot()).isEqualTo(caller);
        preagg.setLastRefreshedAt(OffsetDateTime.now().minusMinutes(2));
        MetaContext.clear();
        service.refreshAllDue();
        assertThat(MetaContext.snapshot()).isNull();
    }

    @Test
    void corruptDimensionsFailBeforeSqlInsteadOfChangingTheQuery() {
        preagg.setDimensionCodes("{corrupt");
        assertThatThrownBy(() -> service.refreshNow("preagg"))
                .isInstanceOf(SemanticValidationException.class)
                .extracting("errorCode").isEqualTo("SEMANTIC_PREAGG_INVALID");
        verifyNoInteractions(queries);
        assertThat(MetaContext.snapshot()).isEqualTo(caller);
    }
}
