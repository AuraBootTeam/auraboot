package com.auraboot.framework.iot.tsport.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.iot.tsport.controller.dto.AggregateQueryResponse;
import com.auraboot.framework.iot.tsport.controller.dto.BatchQueryRequest;
import com.auraboot.framework.iot.tsport.controller.dto.BatchQueryResponse;
import com.auraboot.framework.iot.tsport.controller.dto.LatestQueryResponse;
import com.auraboot.framework.iot.tsport.controller.dto.RangeQueryResponse;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.plugin.extension.iot.QueryParams;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform-side REST endpoint exposing the M1 IoT time-series query
 * contract (SDK v1 caps #2 + #7).
 *
 * <p><b>Why platform-side and not a plugin {@code @RestController}:</b>
 * the plan §11 / M1.E starter pack §4 originally projected an
 * {@code iot-tsdb-bridge} PF4J plugin exposing the REST surface, but the
 * AuraBoot plugin runtime hardens {@code RequestMappingHandlerMapping} at
 * application boot — plugin-time {@code BeanPostProcessor} cannot register
 * new {@code @RequestMapping}s after the mapping registry is locked. The
 * {@link com.auraboot.framework.plugin.extension.iot.TimeSeriesPort} SPI
 * and its TDengine impl already live in {@code auraboot/platform}, so the
 * controller co-locates with its dependency rather than introducing a
 * plugin-side REST shim that cannot actually serve traffic. The plan +
 * starter pack are updated in {@code iot/docs/m1e2a-acceptance.md} §
 * "follow-up tasks".
 *
 * <p><b>Tenant isolation (red line §1 / §15):</b> tenant resolution is
 * delegated to {@link TimeSeriesQueryService#resolveTenantId(Long)} which
 * reads {@code MetaContext} on every call. Cross-tenant API-key auth is
 * a documented M2 follow-up — the controller does NOT honour any client
 * tenant override today.
 *
 * <p><b>Permission (red line §13):</b> the OSS default bootstrap registers
 * {@link MetaPermission#IOT_DATA_POINT_READ} so the controller can use
 * the constant directly; the enterprise {@code ent-iot-control} plugin
 * registers the same code in its {@code permissions.json}, so the two
 * sources of truth converge.
 *
 * <p><b>No silent fallback (red line §8):</b> when
 * {@link com.auraboot.framework.plugin.extension.iot.TimeSeriesPort} is
 * not configured the service throws {@link MetaServiceException} with
 * code {@code iot.tsport.unavailable} which this controller maps to
 * HTTP 503. Operators see a clear "TSDB not configured" rather than a
 * silent empty payload.
 *
 * @since 2.6.1
 */
@RestController
@RequestMapping("/iot/api/v1/timeseries")
@RequirePermission(MetaPermission.IOT_DATA_POINT_READ)
@Tag(name = "iot-timeseries", description = "M1 IoT time-series query (SDK v1 caps #2 + #7)")
public class TimeSeriesQueryController {

    private static final Logger log = LoggerFactory.getLogger(TimeSeriesQueryController.class);

    private final TimeSeriesQueryService service;

    public TimeSeriesQueryController(TimeSeriesQueryService service) {
        this.service = service;
    }

    @GetMapping("/latest")
    @Operation(
            summary = "Latest N samples for one device across many datapoint codes.",
            description = "Returns ts-DESC ordered samples. Missing codes are silently omitted.")
    public ApiResponse<LatestQueryResponse> latest(
            @RequestParam String deviceCode,
            @RequestParam String codes,
            @RequestParam(defaultValue = "1") int limit) {
        List<String> codeList = TimeSeriesQueryService.splitCodes(codes);
        LatestQueryResponse data = service.queryLatest(deviceCode, codeList, limit, null);
        return ApiResponse.success(data);
    }

    @GetMapping("/range")
    @Operation(
            summary = "Raw or downsampled samples in [from, to).",
            description = "downsample is ISO-8601 (e.g. PT5M, PT1H). When omitted, raw samples are returned.")
    public ApiResponse<RangeQueryResponse> range(
            @RequestParam String deviceCode,
            @RequestParam String codes,
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(required = false) String downsample) {
        List<String> codeList = TimeSeriesQueryService.splitCodes(codes);
        RangeQueryResponse data =
                service.queryRange(deviceCode, codeList, from, to, downsample, null);
        return ApiResponse.success(data);
    }

    @GetMapping("/aggregate")
    @Operation(
            summary = "Server-side aggregation over fixed groupBy buckets in [from, to).",
            description = "aggregation ∈ {AVG, MAX, MIN, SUM, COUNT, FIRST, LAST}; groupBy is ISO-8601.")
    public ApiResponse<AggregateQueryResponse> aggregate(
            @RequestParam String deviceCode,
            @RequestParam String codes,
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam QueryParams.Aggregation aggregation,
            @RequestParam String groupBy) {
        List<String> codeList = TimeSeriesQueryService.splitCodes(codes);
        AggregateQueryResponse data = service.queryAggregate(
                deviceCode, codeList, from, to, aggregation, groupBy, null);
        return ApiResponse.success(data);
    }

    @PostMapping(value = "/batchQuery", consumes = "application/json")
    @Operation(
            summary = "Batch latest / range / aggregate queries (≤ 50 entries).",
            description = "Partial-success: one failed query carries errorCode/errorMessage; siblings still return successfully.")
    public ApiResponse<BatchQueryResponse> batchQuery(@Valid @RequestBody BatchQueryRequest request) {
        BatchQueryResponse data = service.batchQuery(request, null);
        return ApiResponse.success(data);
    }

    /**
     * Map our typed {@link MetaServiceException} to a proper HTTP status.
     *
     * <p>Service-layer error messages follow {@code "code:human"} so we
     * can route by code prefix:
     * <ul>
     *   <li>{@code iot.tsport.unavailable} → 503</li>
     *   <li>{@code iot.tsport.tenant.missing} → 401</li>
     *   <li>everything else (validation) → 400</li>
     * </ul>
     */
    @ExceptionHandler(MetaServiceException.class)
    public ResponseEntity<ApiResponse<Void>> handleMetaServiceException(MetaServiceException ex) {
        String raw = ex.getMessage() == null ? "" : ex.getMessage();
        String code = raw;
        String message = raw;
        int colon = raw.indexOf(':');
        if (colon > 0) {
            code = raw.substring(0, colon);
            message = raw.substring(colon + 1);
        }
        HttpStatus status;
        if ("iot.tsport.unavailable".equals(code)) {
            status = HttpStatus.SERVICE_UNAVAILABLE;
        } else if ("iot.tsport.tenant.missing".equals(code)) {
            status = HttpStatus.UNAUTHORIZED;
        } else {
            status = HttpStatus.BAD_REQUEST;
        }
        log.debug("timeseries query rejected: code={} status={} msg={}", code, status, message);
        ApiResponse<Void> body = ApiResponse.error(status.value(), message);
        body.setCode(code);
        return ResponseEntity.status(status).body(body);
    }
}
