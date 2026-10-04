package com.auraboot.framework.semantic.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.semantic.entity.AbSemanticPreagg;
import com.auraboot.framework.semantic.entity.AbSemanticModel;
import com.auraboot.framework.semantic.mapper.AbSemanticModelMapper;
import com.auraboot.framework.semantic.mapper.AbSemanticPreaggMapper;
import com.auraboot.framework.userattribute.service.UserAttributeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class SemanticPreaggSchedulerScopeTest {
    private final AbSemanticPreaggMapper mapper = mock(AbSemanticPreaggMapper.class);
    private final AbSemanticModelMapper models = mock(AbSemanticModelMapper.class);
    private final SemanticQueryService queries = mock(SemanticQueryService.class);
    private final SemanticPreaggService service = new SemanticPreaggService(mapper,
            models, queries,
            mock(UserAttributeService.class), mock(JdbcTemplate.class), new ObjectMapper());

    @AfterEach void clearContext() { MetaContext.clear(); }

    @Test void contextlessSchedulerScopesCrossTenantScanAndRestoresBypass() {
        MetaContext.clear();
        when(mapper.listAllAcrossTenants()).thenAnswer(invocation -> {
            assertThat(MetaContext.exists()).isFalse();
            assertThat(MetaContext.isTenantFilterBypassed()).isTrue();
            return List.of();
        });
        service.refreshAllDue();
        assertThat(MetaContext.exists()).isFalse();
        assertThat(MetaContext.isTenantFilterBypassed()).isFalse();
    }

    @Test void failedScheduledRefreshRestoresFullCallerSnapshot() {
        MetaContext.setContext(11L, 22L, "caller", "caller");
        MetaContext.setMemberId(33L);
        MetaContext.Snapshot caller = MetaContext.snapshot();
        AbSemanticPreagg due = new AbSemanticPreagg();
        due.setPid("due-preagg"); due.setTenantId(99L); due.setCreatedBy(88L);
        due.setSemanticModelPid("missing-model");
        due.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC).minusHours(2));
        due.setRefreshMinutes(1);
        when(mapper.listAllAcrossTenants()).thenReturn(List.of(due));
        service.refreshAllDue();
        assertThat(MetaContext.snapshot()).isEqualTo(caller);
        assertThat(MetaContext.isTenantFilterBypassed()).isFalse();
    }

    @Test void failingScanDoesNotLeakCrossTenantBypass() {
        MetaContext.clear();
        when(mapper.listAllAcrossTenants()).thenThrow(new IllegalStateException("scan rejected"));
        assertThatThrownBy(service::refreshAllDue).hasMessage("scan rejected");
        assertThat(MetaContext.exists()).isFalse();
        assertThat(MetaContext.isTenantFilterBypassed()).isFalse();
    }

    @Test void failedManualRefreshRestoresCallerAfterCreatorContextSwitch() {
        MetaContext.setContext(11L, 22L, "caller", "caller");
        MetaContext.setMemberId(33L);
        MetaContext.Snapshot caller = MetaContext.snapshot();
        AbSemanticPreagg preagg = new AbSemanticPreagg();
        preagg.setPid("manual-preagg"); preagg.setTenantId(11L); preagg.setCreatedBy(88L);
        preagg.setSemanticModelPid("model"); preagg.setMetricCode("metric");
        preagg.setDimensionCodes("[]");
        AbSemanticModel model = new AbSemanticModel(); model.setCode("governed");
        when(mapper.findByPid(11L, "manual-preagg")).thenReturn(preagg);
        when(models.findByPid(11L, "model")).thenReturn(model);
        when(queries.explainQuery(any(), any())).thenThrow(new IllegalStateException("policy unavailable"));
        assertThatThrownBy(() -> service.refreshNow("manual-preagg")).hasMessage("policy unavailable");
        assertThat(MetaContext.snapshot()).isEqualTo(caller);
        assertThat(MetaContext.isTenantFilterBypassed()).isFalse();
    }
}
