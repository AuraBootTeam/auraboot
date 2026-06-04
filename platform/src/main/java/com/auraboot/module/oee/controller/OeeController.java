package com.auraboot.module.oee.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.module.oee.dto.OeeRequest;
import com.auraboot.module.oee.dto.OeeResult;
import com.auraboot.module.oee.engine.OeeCalculationEngine;
import com.auraboot.module.oee.port.OeeDataQueryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

/**
 * OEE (Overall Equipment Effectiveness) REST controller for PCBA manufacturing.
 *
 * <p>Computes availability / performance / quality / OEE / TEEP + six-big-losses for a single
 * equipment over a time window. The raw inputs come from the {@link OeeDataQueryPort} (backed by
 * {@code DynamicTableOeeAdapter} reading {@code mt_pe_*}), and the pure {@link OeeCalculationEngine}
 * derives the rates.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/manufacturing/oee")
@RequiredArgsConstructor
public class OeeController {

    private final OeeCalculationEngine engine;
    private final OeeDataQueryPort port;

    /**
     * Compute OEE for one equipment over a time window.
     *
     * @param equipmentId equipment primary key (pid / ULID string)
     * @param start       window start, ISO-8601 local date-time (e.g. {@code 2026-06-01T00:00:00})
     * @param end         window end, ISO-8601 local date-time (exclusive)
     * @return OEE result (availability / performance / quality / oee / teep + six losses)
     */
    @GetMapping("/equipment/{equipmentId}")
    @RequirePermission(MetaPermission.MANUFACTURING_OEE)
    public ApiResponse<OeeResult> equipmentOee(
            @PathVariable String equipmentId,
            @RequestParam String start,
            @RequestParam String end) {
        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null) {
            return ApiResponse.error("Tenant context not found");
        }

        LocalDateTime windowStart;
        LocalDateTime windowEnd;
        try {
            windowStart = LocalDateTime.parse(start);
            windowEnd = LocalDateTime.parse(end);
        } catch (DateTimeParseException e) {
            return ApiResponse.error("Invalid date-time; expected ISO-8601 local date-time (e.g. 2026-06-01T00:00:00)");
        }
        if (!windowStart.isBefore(windowEnd)) {
            return ApiResponse.error("Window start must be before window end");
        }

        OeeRequest req = OeeRequest.builder()
                .tenantId(tenantId)
                .equipmentId(equipmentId)
                .windowStart(windowStart)
                .windowEnd(windowEnd)
                .build();
        log.info("Computing OEE for tenant {}, equipment {}, window [{}, {})", tenantId, equipmentId, start, end);
        return ApiResponse.success(engine.calculate(port.fetch(req)));
    }
}
