package com.auraboot.framework.behavior.service;

import com.auraboot.framework.behavior.dto.*;
import com.auraboot.framework.behavior.mapper.AnalyticsFunnelMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalyticsFunnelServiceTest {
    private final AnalyticsFunnelMapper mapper = mock(AnalyticsFunnelMapper.class);
    private final AnalyticsFunnelService service = new AnalyticsFunnelService(mapper);
    private final Instant cutoff = Instant.parse("2026-09-12T12:00:00Z");
    private final BehaviorQueryWindow window = new BehaviorQueryWindow(cutoff.minusSeconds(3600), cutoff);

    @Test
    void queryCanBeParsedByTheTenantInterceptor() throws Exception {
        String sql = AnalyticsFunnelMapper.class.getMethod("count", Long.class, Instant.class, Instant.class, Instant.class)
                .getAnnotation(org.apache.ibatis.annotations.Select.class).value()[0].replaceAll("#\\{[^}]+}", "?");
        assertThatCode(() -> net.sf.jsqlparser.parser.CCJSqlParserUtil.parse(sql)).doesNotThrowAnyException();
    }

    @Test
    void reportsFixedDenominatorsAndBothRates() {
        var counts = new AnalyticsFunnelCounts();
        counts.setRequested(10); counts.setSucceeded(8); counts.setViewed(6); counts.setSaved(4); counts.setUsed(2);
        counts.setUnmatchedStageEvents(3);
        when(mapper.count(42L, window.from(), window.to(), cutoff)).thenReturn(counts);
        var result = service.query(42L, window, cutoff);
        assertThat(result.records()).extracting(AnalyticsFunnel.Stage::firstStageDenominator).containsOnly(10L);
        assertThat(result.records()).extracting(AnalyticsFunnel.Stage::previousStageDenominator).containsExactly(10L, 10L, 8L, 6L, 4L);
        assertThat(result.records()).extracting(stage -> stage.overallRate().doubleValue()).containsExactly(1.0, 0.8, 0.6, 0.4, 0.2);
        assertThat(result.records().get(4).previousStageRate()).isEqualByComparingTo("0.5");
        assertThat(result.quality().unmatchedStageEvents()).isEqualTo(3);
        assertThat(result.dataCutoff()).isEqualTo(cutoff);
    }

    @Test
    void zeroDenominatorIsNotZeroPercent() {
        when(mapper.count(42L, window.from(), window.to(), cutoff)).thenReturn(new AnalyticsFunnelCounts());
        var result = service.query(42L, window, cutoff);
        assertThat(result.records()).allSatisfy(stage -> {
            assertThat(stage.overallRate()).isNull();
            assertThat(stage.previousStageRate()).isNull();
            assertThat(stage.status()).isEqualTo("no_sample");
        });
    }
}
