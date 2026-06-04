package com.auraboot.module.oee.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.module.oee.dto.OeeFleetRow;
import com.auraboot.module.oee.dto.OeeFleetSummary;
import com.auraboot.module.oee.dto.OeeRequest;
import com.auraboot.module.oee.dto.OeeResult;
import com.auraboot.module.oee.engine.OeeCalculationEngine;
import com.auraboot.module.oee.port.OeeDataQueryPort;
import com.auraboot.module.oee.service.OeeFleetService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * OEE (Overall Equipment Effectiveness) REST controller for PCBA manufacturing.
 *
 * <p>Computes availability / performance / quality / OEE / TEEP + six-big-losses for a single
 * equipment over a time window. The raw inputs come from the {@link OeeDataQueryPort} (backed by
 * {@code DynamicTableOeeAdapter} reading {@code mt_pe_*}), and the pure {@link OeeCalculationEngine}
 * derives the rates.</p>
 *
 * <p>The {@code /fleet} and {@code /fleet/summary} endpoints roll the same per-equipment computation
 * up to all equipment of the tenant and wrap the result as {@code {"records": [...]}} so config-only
 * dashboard widgets (which read {@code data.records}) can bind to them via an {@code api} dataSource.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/manufacturing/oee")
@RequiredArgsConstructor
public class OeeController {

    private final OeeCalculationEngine engine;
    private final OeeDataQueryPort port;
    private final OeeFleetService fleetService;

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
        LocalDateTime[] window;
        try {
            window = parseWindow(start, end);
        } catch (WindowException e) {
            return ApiResponse.error(e.getMessage());
        }

        OeeRequest req = OeeRequest.builder()
                .tenantId(tenantId)
                .equipmentId(equipmentId)
                .windowStart(window[0])
                .windowEnd(window[1])
                .build();
        log.info("Computing OEE for tenant {}, equipment {}, window [{}, {})", tenantId, equipmentId, start, end);
        return ApiResponse.success(engine.calculate(port.fetch(req)));
    }

    /**
     * Per-equipment OEE for every equipment of the tenant over the window. Returns
     * {@code {"records": [OeeFleetRow...]}} for dashboard {@code api} dataSource binding.
     */
    @GetMapping("/fleet")
    @RequirePermission(MetaPermission.MANUFACTURING_OEE)
    public ApiResponse<Map<String, Object>> fleetOee(
            @RequestParam String start,
            @RequestParam String end) {
        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null) {
            return ApiResponse.error("Tenant context not found");
        }
        LocalDateTime[] window;
        try {
            window = parseWindow(start, end);
        } catch (WindowException e) {
            return ApiResponse.error(e.getMessage());
        }
        List<OeeFleetRow> rows = fleetService.fleet(tenantId, window[0], window[1]);
        log.info("Computing fleet OEE for tenant {}, {} equipment, window [{}, {})", tenantId, rows.size(), start, end);
        return ApiResponse.success(Map.of("records", rows));
    }

    /**
     * Fleet-level OEE roll-up (avg rates over equipment-with-data, total losses, counts). Returns a
     * single-element {@code {"records": [OeeFleetSummary]}} so KPI cards can bind a field directly.
     */
    @GetMapping("/fleet/summary")
    @RequirePermission(MetaPermission.MANUFACTURING_OEE)
    public ApiResponse<Map<String, Object>> fleetSummary(
            @RequestParam String start,
            @RequestParam String end) {
        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null) {
            return ApiResponse.error("Tenant context not found");
        }
        LocalDateTime[] window;
        try {
            window = parseWindow(start, end);
        } catch (WindowException e) {
            return ApiResponse.error(e.getMessage());
        }
        OeeFleetSummary summary = fleetService.summary(tenantId, window[0], window[1]);
        return ApiResponse.success(Map.of("records", List.of(summary)));
    }

    /** Parse + validate the [start, end) window. Throws {@link WindowException} on bad input. */
    private LocalDateTime[] parseWindow(String start, String end) {
        LocalDateTime windowStart;
        LocalDateTime windowEnd;
        try {
            windowStart = LocalDateTime.parse(start);
            windowEnd = LocalDateTime.parse(end);
        } catch (DateTimeParseException e) {
            throw new WindowException("Invalid date-time; expected ISO-8601 local date-time (e.g. 2026-06-01T00:00:00)");
        }
        if (!windowStart.isBefore(windowEnd)) {
            throw new WindowException("Window start must be before window end");
        }
        return new LocalDateTime[]{windowStart, windowEnd};
    }

    /** Internal signal for an invalid time window; mapped to an {@link ApiResponse} error by callers. */
    private static class WindowException extends RuntimeException {
        WindowException(String message) {
            super(message);
        }
    }
}
