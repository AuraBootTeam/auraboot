package com.auraboot.module.oee.adapter;

import com.auraboot.framework.plugin.extension.OeeTelemetrySourceExtension;
import com.auraboot.framework.plugin.pf4j.AuraPluginManager;
import com.auraboot.module.oee.dto.OeeEquipmentRef;
import com.auraboot.module.oee.dto.OeeInputs;
import com.auraboot.module.oee.dto.OeeRequest;
import com.auraboot.module.oee.port.OeeDataQueryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Primary {@link OeeDataQueryPort} that wraps {@link DynamicTableOeeAdapter} (Postgres downtime /
 * output inputs) and enriches the raw inputs with telemetry-derived A/P/Q signals from any
 * {@link OeeTelemetrySourceExtension} contributed by a plugin (Option A / GreptimeDB convergence,
 * DDR-2026-06-21 D5).
 *
 * <p>Telemetry arrives via the PF4J {@link com.auraboot.framework.plugin.extension.OeeTelemetrySourceExtension}
 * extension point (discovered through {@link AuraPluginManager}), NOT Spring injection — a plugin's
 * Spring bean cannot reach the host context, so the IoT plugin contributes an {@code @Extension}.
 * When no extension is registered (no telemetry plugin), the inputs are returned unchanged and the
 * engine uses the Postgres downtime-derived A/P/Q path.</p>
 */
@Component
@Primary
@RequiredArgsConstructor
public class TelemetryEnrichingOeeDataQueryPort implements OeeDataQueryPort {

    private final DynamicTableOeeAdapter delegate;
    private final AuraPluginManager pluginManager;

    @Override
    public OeeInputs fetch(OeeRequest req) {
        OeeInputs inputs = delegate.fetch(req);
        for (OeeTelemetrySourceExtension src :
                pluginManager.getExtensionsOfType(OeeTelemetrySourceExtension.class)) {
            Optional<OeeTelemetrySourceExtension.OeeTelemetry> t = fetchTelemetry(src, req);
            if (t.isPresent()) {
                inputs.setTelemetryOperatingHours(t.get().operatingHours());
                inputs.setTelemetryOutputQty(t.get().outputQty());
                inputs.setTelemetryGoodQty(t.get().goodQty());
                break;  // first source that has data for this equipment wins
            }
        }
        return inputs;
    }

    @Override
    public List<OeeEquipmentRef> listEquipment(Long tenantId) {
        return delegate.listEquipment(tenantId);
    }

    private Optional<OeeTelemetrySourceExtension.OeeTelemetry> fetchTelemetry(
            OeeTelemetrySourceExtension src,
            OeeRequest req) {
        Optional<OeeTelemetrySourceExtension.OeeTelemetry> byId =
                src.fetch(req.getTenantId(), req.getEquipmentId(), req.getWindowStart(), req.getWindowEnd());
        if (byId.isPresent() || isBlank(req.getEquipmentCode())
                || req.getEquipmentCode().equals(req.getEquipmentId())) {
            return byId;
        }
        return src.fetch(req.getTenantId(), req.getEquipmentCode(), req.getWindowStart(), req.getWindowEnd());
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
