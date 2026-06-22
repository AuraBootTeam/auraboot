package com.auraboot.module.oee.adapter;

import com.auraboot.framework.plugin.extension.OeeTelemetrySourceExtension;
import com.auraboot.framework.plugin.pf4j.AuraPluginManager;
import com.auraboot.module.oee.dto.OeeEquipmentRef;
import com.auraboot.module.oee.dto.OeeInputs;
import com.auraboot.module.oee.dto.OeeRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelemetryEnrichingOeeDataQueryPortTest {

    private final DynamicTableOeeAdapter delegate = mock(DynamicTableOeeAdapter.class);
    private final AuraPluginManager pluginManager = mock(AuraPluginManager.class);

    private OeeInputs postgresBase() {
        return OeeInputs.builder()
            .calendarHours(new BigDecimal("10"))
            .downtimes(List.of(OeeInputs.Downtime.builder().type("breakdown").hours(new BigDecimal("1")).build()))
            .actualQty(new BigDecimal("700")).defectQty(new BigDecimal("35"))
            .capacityPerHour(new BigDecimal("100"))
            .build();
    }

    private OeeRequest req() {
        return OeeRequest.builder().tenantId(1L).equipmentId("EQ-1")
            .windowStart(LocalDateTime.parse("2026-06-01T00:00:00"))
            .windowEnd(LocalDateTime.parse("2026-06-02T00:00:00")).build();
    }

    @Test
    void telemetryExtensionPresent_populatesTelemetryBlock_keepsPostgresLossInputs() {
        when(delegate.fetch(any())).thenReturn(postgresBase());
        // functional ExtensionPoint -> lambda
        OeeTelemetrySourceExtension ext = (t, eq, s, e) -> Optional.of(
            new OeeTelemetrySourceExtension.OeeTelemetry(new BigDecimal("6"), new BigDecimal("540"), new BigDecimal("513")));
        when(pluginManager.getExtensionsOfType(OeeTelemetrySourceExtension.class)).thenReturn(List.of(ext));

        OeeInputs out = new TelemetryEnrichingOeeDataQueryPort(delegate, pluginManager).fetch(req());

        // telemetry block populated -> engine sources A/P/Q from these:
        assertEquals(0, new BigDecimal("6").compareTo(out.getTelemetryOperatingHours()));
        assertEquals(0, new BigDecimal("540").compareTo(out.getTelemetryOutputQty()));
        assertEquals(0, new BigDecimal("513").compareTo(out.getTelemetryGoodQty()));
        // Postgres loss inputs preserved (still drive six-big-losses):
        assertEquals(1, out.getDowntimes().size());
        assertEquals(0, new BigDecimal("10").compareTo(out.getCalendarHours()));
    }

    @Test
    void noTelemetryExtension_leavesInputsUnchanged() {
        when(delegate.fetch(any())).thenReturn(postgresBase());
        when(pluginManager.getExtensionsOfType(OeeTelemetrySourceExtension.class)).thenReturn(List.of());

        OeeInputs out = new TelemetryEnrichingOeeDataQueryPort(delegate, pluginManager).fetch(req());

        assertNull(out.getTelemetryOperatingHours());
        assertNull(out.getTelemetryOutputQty());
        assertNull(out.getTelemetryGoodQty());
    }

    @Test
    void telemetryExtensionReturnsEmpty_fallsBackToPostgres() {
        when(delegate.fetch(any())).thenReturn(postgresBase());
        OeeTelemetrySourceExtension empty = (t, eq, s, e) -> Optional.empty();
        when(pluginManager.getExtensionsOfType(OeeTelemetrySourceExtension.class)).thenReturn(List.of(empty));

        OeeInputs out = new TelemetryEnrichingOeeDataQueryPort(delegate, pluginManager).fetch(req());

        assertNull(out.getTelemetryOperatingHours());
    }

    @Test
    void multipleTelemetryExtensions_usesFirstSourceWithData() {
        when(delegate.fetch(any())).thenReturn(postgresBase());
        OeeTelemetrySourceExtension empty = (t, eq, s, e) -> Optional.empty();
        OeeTelemetrySourceExtension present = (t, eq, s, e) -> Optional.of(
            new OeeTelemetrySourceExtension.OeeTelemetry(new BigDecimal("5"), new BigDecimal("450"), new BigDecimal("405")));
        OeeTelemetrySourceExtension later = (t, eq, s, e) -> Optional.of(
            new OeeTelemetrySourceExtension.OeeTelemetry(new BigDecimal("9"), new BigDecimal("999"), new BigDecimal("999")));
        when(pluginManager.getExtensionsOfType(OeeTelemetrySourceExtension.class))
            .thenReturn(List.of(empty, present, later));

        OeeInputs out = new TelemetryEnrichingOeeDataQueryPort(delegate, pluginManager).fetch(req());

        assertEquals(0, new BigDecimal("5").compareTo(out.getTelemetryOperatingHours()));
        assertEquals(0, new BigDecimal("450").compareTo(out.getTelemetryOutputQty()));
        assertEquals(0, new BigDecimal("405").compareTo(out.getTelemetryGoodQty()));
    }

    @Test
    void listEquipment_delegatesToPostgresAdapter() {
        when(delegate.listEquipment(1L))
            .thenReturn(List.of(OeeEquipmentRef.builder().equipmentId("EQ-1").code("C1").name("N1").build()));

        List<OeeEquipmentRef> refs =
            new TelemetryEnrichingOeeDataQueryPort(delegate, pluginManager).listEquipment(1L);

        assertEquals(1, refs.size());
        verify(delegate).listEquipment(1L);
    }
}
