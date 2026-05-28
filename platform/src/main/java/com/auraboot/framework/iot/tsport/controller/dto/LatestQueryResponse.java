package com.auraboot.framework.iot.tsport.controller.dto;

import com.auraboot.framework.plugin.extension.iot.TimeSeriesPoint;
import java.util.List;

/**
 * REST response wrapper for {@code GET /iot/api/v1/timeseries/latest}.
 *
 * <p>Thin wrapper around {@link TimeSeriesPoint} so the JSON shape stays
 * stable even if we later add response-level metadata (e.g. partial /
 * truncated flags). Keeping it a record (rather than a Map) so the SDK
 * generator can emit a typed client.
 *
 * @since 2.6.1
 */
public record LatestQueryResponse(String deviceCode, List<TimeSeriesPoint> points) {}
