package com.auraboot.framework.behavior.service;

import com.auraboot.framework.behavior.dto.*;
import com.auraboot.framework.behavior.mapper.AnalyticsExecutionMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalyticsExecutionServiceTest {
    private final AnalyticsExecutionMapper mapper = mock(AnalyticsExecutionMapper.class);
    private final AnalyticsExecutionService service = new AnalyticsExecutionService(mapper);
    private final Instant cutoff = Instant.parse("2026-09-12T12:00:00Z");
    private final BehaviorQueryWindow window = new BehaviorQueryWindow(cutoff.minusSeconds(3600), cutoff);

    @Test void distinguishesStartedDenominatorFromCompletedDenominator() {
        var counts = new AnalyticsExecutionCounts();
        counts.setStarted(10); counts.setSucceeded(3); counts.setFailed(2); counts.setCancelled(1); counts.setUnresolved(4);
        when(mapper.count(7L, window.from(), window.to(), cutoff)).thenReturn(counts);
        var result = service.query(7L, window, cutoff);
        assertThat(result.successRate()).isEqualByComparingTo("0.3");
        assertThat(result.completedSuccessRate()).isEqualByComparingTo("0.5");
        assertThat(result.counts().getUnresolved()).isEqualTo(4);
        assertThat(result.dataCutoff()).isEqualTo(cutoff);
        assertThat(result.countingUnit()).isEqualTo("agent_run");
    }

    @Test void emptyCohortHasNoInventedZeroPercent() {
        when(mapper.count(7L, window.from(), window.to(), cutoff)).thenReturn(new AnalyticsExecutionCounts());
        var result = service.query(7L, window, cutoff);
        assertThat(result.successRate()).isNull();
        assertThat(result.completedSuccessRate()).isNull();
        assertThat(result.sampleStatus()).isEqualTo("no_sample");
    }

    @Test void sqlIsCompatibleWithTenantParser() throws Exception {
        String sql = AnalyticsExecutionMapper.class.getMethod("count", Long.class, Instant.class, Instant.class, Instant.class)
                .getAnnotation(org.apache.ibatis.annotations.Select.class).value()[0].replaceAll("#\\{[^}]+}", "?");
        assertThatCode(() -> net.sf.jsqlparser.parser.CCJSqlParserUtil.parse(sql)).doesNotThrowAnyException();
    }
}
