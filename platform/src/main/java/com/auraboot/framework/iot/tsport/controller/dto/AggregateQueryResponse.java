package com.auraboot.framework.iot.tsport.controller.dto;

import com.auraboot.framework.plugin.extension.iot.AggregatedPoint;
import com.auraboot.framework.plugin.extension.iot.QueryParams;
import java.util.List;

/**
 * REST response wrapper for {@code GET /iot/api/v1/timeseries/aggregate}.
 *
 * @since 2.6.1
 */
public record AggregateQueryResponse(
        String deviceCode,
        QueryParams.Aggregation aggregation,
        String groupBy,
        List<AggregatedPoint> buckets) {}
