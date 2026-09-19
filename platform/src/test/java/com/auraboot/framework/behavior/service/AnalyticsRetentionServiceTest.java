package com.auraboot.framework.behavior.service;

import com.auraboot.framework.behavior.dto.*;
import com.auraboot.framework.behavior.mapper.AnalyticsRetentionMapper;
import com.auraboot.framework.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalyticsRetentionServiceTest {
    private final AnalyticsRetentionMapper mapper = mock(AnalyticsRetentionMapper.class);
    private final AnalyticsRetentionService service = new AnalyticsRetentionService(mapper);
    private final Instant now = Instant.parse("2026-09-12T12:00:00Z");
    private final BehaviorQueryWindow window = new BehaviorQueryWindow(now.minusSeconds(86400), now);

    @Test
    void immatureCohortNeverBecomesZeroRetention() {
        var point = point(false, 10, 3);
        when(mapper.query(42L, "user", window.from(), window.to(), now)).thenReturn(List.of(point));
        var result = service.query(42L, "user", window, now).records().getFirst();
        assertThat(result.status()).isEqualTo("immature");
        assertThat(result.retained()).isNull();
        assertThat(result.retentionRate()).isNull();
        assertThat(result.cohortSize()).isEqualTo(10);
    }

    @Test
    void matureCohortUsesItsOwnUnitAndDenominator() {
        when(mapper.query(42L, "artifact", window.from(), window.to(), now)).thenReturn(List.of(point(true, 4, 3)));
        var response = service.query(42L, "artifact", window, now);
        assertThat(response.unit()).isEqualTo("artifact");
        assertThat(response.records().getFirst().retentionRate()).isEqualByComparingTo("0.75");
        assertThat(response.records().getFirst().retained()).isEqualTo(3L);
    }

    @Test
    void unknownUnitCannotSelectAnArbitraryGrouping() {
        assertThatThrownBy(() -> service.query(42L, "tenant_id", window, now)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void sqlParsesWithTheProductionParser() throws Exception {
        String sql = AnalyticsRetentionMapper.class.getMethod("query", Long.class, String.class, Instant.class, Instant.class, Instant.class)
                .getAnnotation(org.apache.ibatis.annotations.Select.class).value()[0].replaceAll("#\\{[^}]+}", "?");
        assertThatCode(() -> net.sf.jsqlparser.parser.CCJSqlParserUtil.parse(sql)).doesNotThrowAnyException();
    }

    private static AnalyticsRetentionPoint point(boolean mature, long size, long returning) {
        var point = new AnalyticsRetentionPoint();
        point.setCohortDay("2026-09-11"); point.setDayOffset(1); point.setMature(mature);
        point.setCohortSize(size); point.setReturningCount(returning);
        return point;
    }
}
