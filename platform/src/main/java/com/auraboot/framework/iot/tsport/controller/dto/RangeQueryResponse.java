package com.auraboot.framework.iot.tsport.controller.dto;

import com.auraboot.framework.plugin.extension.iot.TimeSeriesPoint;
import java.util.List;

/**
 * REST response wrapper for {@code GET /iot/api/v1/timeseries/range}.
 *
 * <p>{@code downsampleApplied} echoes the server-side downsample window the
 * impl actually pushed down (ISO-8601 duration, e.g. {@code "PT5M"} or
 * {@code null} when raw samples were returned).
 *
 * @since 2.6.1
 */
public record RangeQueryResponse(
        String deviceCode, String downsampleApplied, List<TimeSeriesPoint> points) {}
