package com.auraboot.framework.iot.spi.impl;

import com.auraboot.framework.iot.broker.EmqxRuleTestService;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.plugin.extension.iot.BackgroundProductAccessor;
import com.auraboot.framework.plugin.extension.iot.BackgroundProductAccessor.ProductSchema;
import com.auraboot.framework.plugin.extension.iot.BackgroundProductAccessor.PropertyDef;
import com.auraboot.framework.plugin.extension.iot.BackgroundRuleAccessor;
import com.auraboot.framework.plugin.extension.iot.BackgroundRuleAccessor.RuleKind;
import com.auraboot.framework.plugin.extension.iot.BackgroundRuleAccessor.RuleScope;
import com.auraboot.framework.plugin.extension.iot.BackgroundRuleAccessor.RuleView;
import com.auraboot.framework.plugin.extension.iot.BackgroundRuleSimulator.SimResult;
import com.auraboot.framework.plugin.extension.iot.BackgroundRuleSimulator.SimWindow;
import com.auraboot.framework.plugin.extension.iot.TimeSeriesPoint;
import com.auraboot.framework.plugin.extension.iot.QueryParams;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RuleSimulatorImplTest {

    private DynamicDataService dds;
    private BackgroundRuleAccessor ruleAccessor;
    private BackgroundProductAccessor productAccessor;
    private FakeTimeSeriesPort tsp;
    private EmqxRuleTestService emqxRuleTest;
    private RuleSimulatorImpl sim;

    private static final Instant T0 = Instant.parse("2026-06-05T00:00:00Z");

    @BeforeEach
    void setUp() {
        dds = mock(DynamicDataService.class);
        ruleAccessor = mock(BackgroundRuleAccessor.class);
        productAccessor = mock(BackgroundProductAccessor.class);
        tsp = new FakeTimeSeriesPort();
        emqxRuleTest = mock(EmqxRuleTestService.class);
        sim = new RuleSimulatorImpl(dds, ruleAccessor, productAccessor, tsp, emqxRuleTest, new ObjectMapper());
    }

    private RuleView sqlRule() {
        return new RuleView("rule-temp", RuleScope.DEVICE, "dev-1", RuleKind.SQL,
                "SELECT payload.temp as temp FROM \"t/+/p/+/d/+/telemetry\" WHERE payload.temp > 80",
                "{}", "CRITICAL", 0, true, 42L);
    }

    private Map<String, Object> deviceRow(String code, String iotId, String pk) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("pid", "pid-" + iotId);
        row.put("iot_d_iot_id", iotId);
        row.put("iot_d_device_code", code);
        row.put("iot_d_product_key", pk);
        row.put("tenant_id", 42L);
        row.put("iot_d_status", "ONLINE");
        row.put("iot_d_acl_pattern", null);
        row.put("iot_d_tags", null);
        row.put("iot_d_last_seen_at", null);
        return row;
    }

    private void stubDeviceList(Map<String, Object>... rows) {
        when(dds.list(eq("iot_device"), any(DynamicQueryRequest.class)))
                .thenReturn(PaginationResult.of(List.of(rows), (long) rows.length, 1, 1000));
    }

    private SimWindow window(int maxSamples) {
        return new SimWindow(T0.minusSeconds(3600), T0.plusSeconds(3600), maxSamples);
    }

    @Test
    void simulate_sqlDeviceScope_countsMatchesViaEmqxDryRun_noSideEffects() {
        when(ruleAccessor.findByCode(42L, "rule-temp")).thenReturn(Optional.of(sqlRule()));
        stubDeviceList(deviceRow("dev-1", "iot-1", "pk-air"));
        when(productAccessor.getSchema("pk-air")).thenReturn(Optional.of(new ProductSchema(
                List.of(new PropertyDef("temp", "float", true, "°C", Map.of())), List.of(), List.of())));
        // Three telemetry frames at distinct timestamps.
        tsp.points = List.of(
                new TimeSeriesPoint("dev-1", "temp", T0, 90, "GOOD"),
                new TimeSeriesPoint("dev-1", "temp", T0.plusSeconds(60), 50, "GOOD"),
                new TimeSeriesPoint("dev-1", "temp", T0.plusSeconds(120), 85, "GOOD"));
        // EMQX says: frame1 (90) fires, frame2 (50) no, frame3 (85) fires.
        when(emqxRuleTest.matches(anyString(), anyString(), anyString()))
                .thenReturn(true).thenReturn(false).thenReturn(true);

        SimResult r = sim.simulate(42L, "rule-temp", window(100));

        assertThat(r.ruleCode()).isEqualTo("rule-temp");
        assertThat(r.kind()).isEqualTo("SQL");
        assertThat(r.samplesChecked()).isEqualTo(3);
        assertThat(r.wouldFire()).hasSize(2);
        assertThat(r.wouldFire().get(0).deviceCode()).isEqualTo("dev-1");
        assertThat(r.wouldFire().get(0).severity()).isEqualTo("CRITICAL");
        assertThat(r.wouldFire().get(0).at()).isEqualTo(T0);
        assertThat(r.wouldFire().get(1).at()).isEqualTo(T0.plusSeconds(120));
        // EMQX dry-run was invoked once per frame (faithful evaluation).
        verify(emqxRuleTest, times(3)).matches(anyString(), anyString(), anyString());
        // §2.2末 zero side effects: no DB write, ever.
        verify(dds, never()).update(anyString(), anyString(), any());
        verify(dds, never()).create(anyString(), any());
    }

    @Test
    void simulate_capsAtMaxSamples() {
        when(ruleAccessor.findByCode(42L, "rule-temp")).thenReturn(Optional.of(sqlRule()));
        stubDeviceList(deviceRow("dev-1", "iot-1", "pk-air"));
        when(productAccessor.getSchema("pk-air")).thenReturn(Optional.of(new ProductSchema(
                List.of(new PropertyDef("temp", "float", true, "°C", Map.of())), List.of(), List.of())));
        tsp.points = List.of(
                new TimeSeriesPoint("dev-1", "temp", T0, 90, "GOOD"),
                new TimeSeriesPoint("dev-1", "temp", T0.plusSeconds(60), 95, "GOOD"),
                new TimeSeriesPoint("dev-1", "temp", T0.plusSeconds(120), 99, "GOOD"));
        when(emqxRuleTest.matches(anyString(), anyString(), anyString())).thenReturn(true);

        SimResult r = sim.simulate(42L, "rule-temp", window(1));

        assertThat(r.samplesChecked()).isEqualTo(1);
        assertThat(r.wouldFire()).hasSize(1);
        verify(emqxRuleTest, times(1)).matches(anyString(), anyString(), anyString());
    }

    @Test
    void simulate_noTelemetry_returnsZero_noEmqxCall() {
        when(ruleAccessor.findByCode(42L, "rule-temp")).thenReturn(Optional.of(sqlRule()));
        stubDeviceList(deviceRow("dev-1", "iot-1", "pk-air"));
        when(productAccessor.getSchema("pk-air")).thenReturn(Optional.of(new ProductSchema(
                List.of(new PropertyDef("temp", "float", true, "°C", Map.of())), List.of(), List.of())));
        tsp.points = List.of();

        SimResult r = sim.simulate(42L, "rule-temp", window(100));

        assertThat(r.samplesChecked()).isZero();
        assertThat(r.wouldFire()).isEmpty();
        verifyNoInteractions(emqxRuleTest);
    }

    @Test
    void simulate_smartEngineKind_throwsNotProductionEvaluated() {
        RuleView se = new RuleView("rule-se", RuleScope.DEVICE, "dev-1", RuleKind.SMART_ENGINE,
                "process-key", "{}", "MAJOR", 0, true, 42L);
        when(ruleAccessor.findByCode(42L, "rule-se")).thenReturn(Optional.of(se));

        assertThatThrownBy(() -> sim.simulate(42L, "rule-se", window(100)))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("rule_kind_not_production_evaluated:SMART_ENGINE");
        verifyNoInteractions(emqxRuleTest);
        verifyNoInteractions(dds);
    }

    @Test
    void simulate_chainKind_throwsNotProductionEvaluated() {
        RuleView chain = new RuleView("rule-chain", RuleScope.DEVICE, "dev-1", RuleKind.CHAIN,
                "pid-x", "{}", "MINOR", 0, true, 42L);
        when(ruleAccessor.findByCode(42L, "rule-chain")).thenReturn(Optional.of(chain));

        assertThatThrownBy(() -> sim.simulate(42L, "rule-chain", window(100)))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("rule_kind_not_production_evaluated:CHAIN");
    }

    @Test
    void simulate_noTelemetryStore_throwsTelemetryStoreUnavailable() {
        // Backend deployed without TDengine → TimeSeriesPort bean absent (null).
        RuleSimulatorImpl noStore = new RuleSimulatorImpl(dds, ruleAccessor, productAccessor,
                (com.auraboot.framework.plugin.extension.iot.TimeSeriesPort) null, emqxRuleTest, new ObjectMapper());
        when(ruleAccessor.findByCode(42L, "rule-temp")).thenReturn(Optional.of(sqlRule()));

        assertThatThrownBy(() -> noStore.simulate(42L, "rule-temp", window(100)))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("telemetry_store_unavailable");
        verifyNoInteractions(emqxRuleTest);
    }

    @Test
    void simulate_ruleNotFound_throws() {
        when(ruleAccessor.findByCode(42L, "ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> sim.simulate(42L, "ghost", window(100)))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("rule_not_found");
    }

    @Test
    void simulate_rejectsNonPositiveTenant() {
        assertThatThrownBy(() -> sim.simulate(0L, "rule-temp", window(100)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Minimal in-test TimeSeriesPort: only queryRange is exercised. */
    static final class FakeTimeSeriesPort implements com.auraboot.framework.plugin.extension.iot.TimeSeriesPort {
        List<TimeSeriesPoint> points = List.of();

        @Override
        public void writeBatch(long tenantId, List<TimeSeriesPoint> points) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<TimeSeriesPoint> queryLatest(long tenantId, String deviceCode, List<String> codes, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<TimeSeriesPoint> queryRange(long tenantId, QueryParams.Range params) {
            return points;
        }

        @Override
        public List<com.auraboot.framework.plugin.extension.iot.AggregatedPoint> queryAggregate(
                long tenantId, QueryParams.Aggregate params) {
            throw new UnsupportedOperationException();
        }
    }
}
