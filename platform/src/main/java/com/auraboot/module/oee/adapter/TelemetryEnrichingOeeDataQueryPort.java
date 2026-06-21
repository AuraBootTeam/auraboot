package com.auraboot.module.oee.adapter;

import com.auraboot.module.oee.dto.OeeEquipmentRef;
import com.auraboot.module.oee.dto.OeeInputs;
import com.auraboot.module.oee.dto.OeeRequest;
import com.auraboot.module.oee.port.OeeDataQueryPort;
import com.auraboot.module.oee.port.OeeTelemetrySource;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Primary {@link OeeDataQueryPort} that wraps {@link DynamicTableOeeAdapter} (Postgres downtime /
 * output inputs) and enriches the raw inputs with telemetry-derived A/P/Q signals from
 * {@link OeeTelemetrySource} when available (Option A / GreptimeDB convergence, DDR-2026-06-21 D5).
 *
 * <p>When the telemetry source is the no-op default (OSS core, IoT plugin absent), the inputs are
 * returned unchanged and the engine uses the Postgres downtime-derived A/P/Q path. When the IoT
 * plugin contributes a GreptimeDB-backed source, A/P/Q come from real device telemetry while the
 * Postgres downtimes still drive the six-big-losses reason breakdown.</p>
 */
@Component
@Primary
@RequiredArgsConstructor
public class TelemetryEnrichingOeeDataQueryPort implements OeeDataQueryPort {

    private final DynamicTableOeeAdapter delegate;
    private final OeeTelemetrySource telemetry;

    @Override
    public OeeInputs fetch(OeeRequest req) {
        OeeInputs inputs = delegate.fetch(req);
        telemetry.fetch(req.getTenantId(), req.getEquipmentId(), req.getWindowStart(), req.getWindowEnd())
            .ifPresent(t -> {
                inputs.setTelemetryOperatingHours(t.operatingHours());
                inputs.setTelemetryOutputQty(t.outputQty());
                inputs.setTelemetryGoodQty(t.goodQty());
            });
        return inputs;
    }

    @Override
    public List<OeeEquipmentRef> listEquipment(Long tenantId) {
        return delegate.listEquipment(tenantId);
    }
}
