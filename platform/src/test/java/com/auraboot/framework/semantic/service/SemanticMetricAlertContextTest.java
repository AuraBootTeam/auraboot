package com.auraboot.framework.semantic.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.notification.service.NotificationService;
import com.auraboot.framework.semantic.dto.SemanticQueryResponse;
import com.auraboot.framework.semantic.entity.AbSemanticMetric;
import com.auraboot.framework.semantic.entity.AbSemanticMetricAlert;
import com.auraboot.framework.semantic.entity.AbSemanticModel;
import com.auraboot.framework.semantic.mapper.AbSemanticMetricAlertMapper;
import com.auraboot.framework.semantic.mapper.AbSemanticMetricMapper;
import com.auraboot.framework.semantic.mapper.AbSemanticModelMapper;
import com.auraboot.framework.userattribute.service.UserAttributeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Hermetic thread-context contract; does not replace notification/persistence IT. */
class SemanticMetricAlertContextTest {
    private SemanticMetricAlertService service;
    private SemanticQueryService queries;
    private AbSemanticMetricMapper metrics;
    private MetaContext.Snapshot caller;

    @BeforeEach
    void setup() {
        var alerts = mock(AbSemanticMetricAlertMapper.class);
        metrics = mock(AbSemanticMetricMapper.class);
        var models = mock(AbSemanticModelMapper.class);
        queries = mock(SemanticQueryService.class);
        var attributes = mock(UserAttributeService.class);
        service = new SemanticMetricAlertService(alerts, metrics, models, queries,
                mock(NotificationService.class), attributes);
        var alert = new AbSemanticMetricAlert();
        alert.setPid("alert");
        alert.setTenantId(1L);
        alert.setCreatedBy(2L);
        alert.setMetricPid("metric");
        alert.setComparator("gt");
        alert.setThreshold(BigDecimal.TEN);
        alert.setSilenceMinutes(60);
        when(alerts.findByPid(1L, "alert")).thenReturn(alert);
        when(alerts.listActiveAcrossTenants()).thenReturn(List.of(alert));
        var metric = new AbSemanticMetric();
        metric.setCode("count");
        metric.setSemanticModelPid("model");
        when(metrics.findByPid(1L, "metric")).thenReturn(metric);
        var model = new AbSemanticModel();
        model.setCode("orders");
        when(models.findByPid(1L, "model")).thenReturn(model);
        when(attributes.getAttributes(1L, 2L)).thenReturn(Map.of());
        var response = new SemanticQueryResponse();
        response.setRows(List.of(Map.of("count", 5)));
        when(queries.executeQuery(any(), any())).thenReturn(response);
        MetaContext.setContext(1L, 9L, "caller", "caller", Set.of(8L));
        MetaContext.setMemberId(3L);
        MetaContext.setEnvironmentId(4L);
        MetaContext.setOtelTraceId("caller-trace");
        caller = MetaContext.snapshot();
    }

    @AfterEach
    void clear() { MetaContext.clear(); }

    @Test
    void successRestoresCompleteCallerIdentity() {
        assertThat(service.evaluateNow("alert")).containsEntry("triggered", false);
        assertThat(MetaContext.snapshot()).isEqualTo(caller);
    }

    @Test
    void missingMetricRestoresCompleteCallerIdentity() {
        when(metrics.findByPid(1L, "metric")).thenReturn(null);
        assertThat(service.evaluateNow("alert")).containsEntry("skipped", "metric_missing");
        assertThat(MetaContext.snapshot()).isEqualTo(caller);
    }

    @Test
    void queryFailureRestoresCompleteCallerIdentity() {
        when(queries.executeQuery(any(), any())).thenThrow(new IllegalStateException("query denied"));
        assertThatThrownBy(() -> service.evaluateNow("alert"))
                .isInstanceOf(IllegalStateException.class).hasMessage("query denied");
        assertThat(MetaContext.snapshot()).isEqualTo(caller);
    }

    @Test
    void schedulerLeavesAnUnboundThreadUnbound() {
        MetaContext.clear();
        var empty = MetaContext.snapshot();
        service.evaluateAll();
        assertThat(MetaContext.snapshot()).isEqualTo(empty);
    }
}
