package com.auraboot.framework.iot.tsport.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.iot.tsport.controller.dto.AggregateQueryResponse;
import com.auraboot.framework.iot.tsport.controller.dto.BatchQueryRequest;
import com.auraboot.framework.iot.tsport.controller.dto.BatchQueryResponse;
import com.auraboot.framework.iot.tsport.controller.dto.LatestQueryResponse;
import com.auraboot.framework.iot.tsport.controller.dto.RangeQueryResponse;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.plugin.extension.iot.AggregatedPoint;
import com.auraboot.framework.plugin.extension.iot.QueryParams;
import com.auraboot.framework.plugin.extension.iot.TimeSeriesPoint;
import com.auraboot.framework.plugin.extension.iot.TimeSeriesPort;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service layer between {@link TimeSeriesQueryController} (REST edge) and
 * the {@link TimeSeriesPort} SPI.
 *
 * <p><b>Why a service (not a thin controller):</b>
 * <ul>
 *   <li>Centralises tenant resolution + cross-tenant guard so the
 *       controller cannot accidentally bypass it.</li>
 *   <li>Centralises {@link TimeSeriesPort} null-fallback (TSDB not
 *       configured → fail-fast with a stable i18n error code rather
 *       than NPE).</li>
 *   <li>Centralises input parsing (downsample / groupBy ISO-8601 →
 *       {@link Duration}) so REST {@link DateTimeParseException} maps to
 *       a 400 i18n message instead of leaking to the global handler.</li>
 *   <li>Lets the batch endpoint reuse the three single-query primitives
 *       without inheriting controller annotations.</li>
 * </ul>
 *
 * <p><b>Multi-tenant rigour (red line §1 / §15):</b> every method derives
 * tenantId from {@link MetaContext#getCurrentTenantId()} and passes it
 * explicitly to the SPI. {@link TimeSeriesPort} signatures take
 * {@code long tenantId} so the WHERE clause is non-bypassable at the
 * SQL level.
 *
 * <p><b>Cross-tenant API-key hook (deferred to M2):</b> the
 * {@link #resolveTenantId(Long)} method accepts an explicit override that
 * a future API-key auth layer can set on a read-only basis (e.g. a tenant
 * monitoring all subtenants). Until M2 wires real API-key auth this
 * override is rejected by the controller — the hook exists to lock the
 * shape now, not enable the feature.
 *
 * <p><b>Catch-Exception discipline (red line §8):</b> we do NOT swallow
 * runtime exceptions from the SPI. The only catch in the file is in
 * {@link #parseDuration(String, String)} where we re-throw a typed
 * {@link MetaServiceException} so the global handler can map it to a 400
 * with a stable i18n key instead of leaking a JDK message.
 *
 * @since 2.6.1
 */
@Service
public class TimeSeriesQueryService {

    private static final Logger log = LoggerFactory.getLogger(TimeSeriesQueryService.class);

    /**
     * Hard upper bound on a single {@code queryLatest} {@code limit}.
     * 1000 samples × ~80 bytes/JSON ≈ 80 KB — within a polite per-request
     * payload. Real bulk export goes through a separate M2 endpoint.
     */
    static final int MAX_LATEST_LIMIT = 1000;

    private final TimeSeriesPort port;

    /**
     * {@link TimeSeriesPort} is {@code @Autowired(required = false)} —
     * when {@code iot.tdengine.enabled=false} (default) no bean is
     * registered and we degrade fail-fast on every call with a stable
     * "TSDB unavailable" error (HTTP 503 from the global handler).
     */
    @Autowired(required = false)
    public TimeSeriesQueryService(TimeSeriesPort port) {
        this.port = port;
    }

    public boolean tsdbAvailable() {
        return port != null;
    }

    // ---------------------------------------------------------------------
    // Single-query primitives
    // ---------------------------------------------------------------------

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public LatestQueryResponse queryLatest(
            String deviceCode, List<String> codes, int limit, Long crossTenantOverride) {
        requirePort();
        validateDeviceCode(deviceCode);
        validateCodes(codes);
        if (limit < 1 || limit > MAX_LATEST_LIMIT) {
            throw new MetaServiceException(
                    "iot.tsport.limit.out_of_range:limit must be between 1 and "
                            + MAX_LATEST_LIMIT);
        }
        long tenantId = resolveTenantId(crossTenantOverride);
        List<TimeSeriesPoint> points = port.queryLatest(tenantId, deviceCode, codes, limit);
        return new LatestQueryResponse(deviceCode, points);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RangeQueryResponse queryRange(
            String deviceCode,
            List<String> codes,
            Instant from,
            Instant to,
            String downsample,
            Long crossTenantOverride) {
        requirePort();
        validateDeviceCode(deviceCode);
        validateCodes(codes);
        validateRange(from, to);
        Duration ds = downsample == null || downsample.isBlank()
                ? null
                : parseDuration(downsample, "downsample");
        long tenantId = resolveTenantId(crossTenantOverride);
        QueryParams.Range params = new QueryParams.Range(deviceCode, codes, from, to, ds);
        List<TimeSeriesPoint> points = port.queryRange(tenantId, params);
        return new RangeQueryResponse(deviceCode, ds == null ? null : ds.toString(), points);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AggregateQueryResponse queryAggregate(
            String deviceCode,
            List<String> codes,
            Instant from,
            Instant to,
            QueryParams.Aggregation aggregation,
            String groupBy,
            Long crossTenantOverride) {
        requirePort();
        validateDeviceCode(deviceCode);
        validateCodes(codes);
        validateRange(from, to);
        if (aggregation == null) {
            throw new MetaServiceException("iot.tsport.aggregation.required:aggregation is required");
        }
        if (groupBy == null || groupBy.isBlank()) {
            throw new MetaServiceException("iot.tsport.groupby.required:groupBy is required");
        }
        Duration gb = parseDuration(groupBy, "groupBy");
        long tenantId = resolveTenantId(crossTenantOverride);
        QueryParams.Aggregate params =
                new QueryParams.Aggregate(deviceCode, codes, from, to, aggregation, gb);
        List<AggregatedPoint> buckets = port.queryAggregate(tenantId, params);
        return new AggregateQueryResponse(deviceCode, aggregation, gb.toString(), buckets);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public BatchQueryResponse batchQuery(BatchQueryRequest request, Long crossTenantOverride) {
        requirePort();
        List<BatchQueryResponse.Result> results = new ArrayList<>(request.queries().size());
        for (BatchQueryRequest.Query q : request.queries()) {
            try {
                switch (q.type()) {
                    case LATEST -> {
                        int limit = q.limit() == null ? 1 : q.limit();
                        LatestQueryResponse r =
                                queryLatest(q.deviceCode(), q.codes(), limit, crossTenantOverride);
                        results.add(new BatchQueryResponse.Result(
                                q.type(), q.deviceCode(), r.points(), null, null, null));
                    }
                    case RANGE -> {
                        RangeQueryResponse r = queryRange(
                                q.deviceCode(),
                                q.codes(),
                                q.from(),
                                q.to(),
                                q.downsample(),
                                crossTenantOverride);
                        results.add(new BatchQueryResponse.Result(
                                q.type(), q.deviceCode(), r.points(), null, null, null));
                    }
                    case AGGREGATE -> {
                        AggregateQueryResponse r = queryAggregate(
                                q.deviceCode(),
                                q.codes(),
                                q.from(),
                                q.to(),
                                q.aggregation(),
                                q.groupBy(),
                                crossTenantOverride);
                        results.add(new BatchQueryResponse.Result(
                                q.type(), q.deviceCode(), null, r.buckets(), null, null));
                    }
                }
            } catch (MetaServiceException ex) {
                // Partial-success batch: surface per-query error rather than abort
                // the whole batch. Only typed MetaServiceException is recovered;
                // unexpected RuntimeExceptions still bubble (red line §8 — no
                // catch (Exception) swallowing).
                String msg = ex.getMessage();
                String code = "iot.tsport.batch.query.failed";
                if (msg != null && msg.contains(":")) {
                    int colon = msg.indexOf(':');
                    code = msg.substring(0, colon);
                    msg = msg.substring(colon + 1);
                }
                results.add(new BatchQueryResponse.Result(
                        q.type(), q.deviceCode(), null, null, code, msg));
            } catch (IllegalArgumentException ex) {
                // Same partial-success rationale, but for record-canonical
                // constructor errors raised by QueryParams.Range / Aggregate.
                results.add(new BatchQueryResponse.Result(
                        q.type(),
                        q.deviceCode(),
                        null,
                        null,
                        "iot.tsport.input.invalid",
                        ex.getMessage()));
            }
        }
        return new BatchQueryResponse(results);
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private void requirePort() {
        if (port == null) {
            throw new MetaServiceException(
                    "iot.tsport.unavailable:TimeSeriesPort is not configured (iot.tdengine.enabled=false)");
        }
    }

    private void validateDeviceCode(String deviceCode) {
        if (deviceCode == null || deviceCode.isBlank()) {
            throw new MetaServiceException(
                    "iot.tsport.device_code.required:deviceCode is required");
        }
    }

    private void validateCodes(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            throw new MetaServiceException("iot.tsport.codes.required:codes must not be empty");
        }
    }

    private void validateRange(Instant from, Instant to) {
        if (from == null || to == null) {
            throw new MetaServiceException(
                    "iot.tsport.range.required:from and to are required");
        }
        if (!to.isAfter(from)) {
            throw new MetaServiceException(
                    "iot.tsport.range.invalid:to must be strictly after from");
        }
    }

    private Duration parseDuration(String iso, String field) {
        try {
            Duration d = Duration.parse(iso);
            if (d.isZero() || d.isNegative()) {
                throw new MetaServiceException(
                        "iot.tsport." + field + ".invalid:" + field + " must be > 0");
            }
            return d;
        } catch (DateTimeParseException ex) {
            // Wrap as MetaServiceException so global handler maps to 400 with
            // a stable i18n code, rather than leaking the JDK message.
            throw new MetaServiceException(
                    "iot.tsport." + field + ".invalid:invalid ISO-8601 duration: " + iso);
        }
    }

    /**
     * Resolve the tenant for the SQL filter.
     *
     * <p>Today the only path is {@link MetaContext#getCurrentTenantId()} —
     * the {@code crossTenantOverride} parameter is reserved for the M2
     * API-key auth layer (read-only flag {@code readOnlyAcrossTenants}).
     * Until then a non-null override is rejected at the controller edge.
     */
    long resolveTenantId(Long crossTenantOverride) {
        if (crossTenantOverride != null) {
            // Hook in place; real auth check belongs in the API-key layer.
            // Controller currently passes null so this branch is unreachable
            // from REST until M2 — the IT covers the field-only behaviour.
            log.debug("cross-tenant query for tenant={}", crossTenantOverride);
            if (crossTenantOverride <= 0) {
                throw new MetaServiceException(
                        "iot.tsport.cross_tenant.invalid:cross-tenant override must be > 0");
            }
            return crossTenantOverride;
        }
        // MetaContext.getCurrentTenantId() throws IllegalStateException when
        // the holder is empty. We translate that (and zero / negative tenant
        // ids, which would otherwise leak across the WHERE clause) into a
        // single typed error so the controller can map to HTTP 401 with a
        // stable i18n code.
        if (!MetaContext.exists()) {
            throw new MetaServiceException(
                    "iot.tsport.tenant.missing:tenant context is not set");
        }
        Long tid = MetaContext.getCurrentTenantId();
        if (tid == null || tid <= 0) {
            throw new MetaServiceException(
                    "iot.tsport.tenant.missing:tenant context is not set");
        }
        return tid;
    }

    /**
     * Split the controller-side comma-separated {@code codes} param.
     * Trims each entry and rejects blank elements; defensive split lives
     * here so the controller stays declarative.
     */
    static List<String> splitCodes(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableList());
    }
}
