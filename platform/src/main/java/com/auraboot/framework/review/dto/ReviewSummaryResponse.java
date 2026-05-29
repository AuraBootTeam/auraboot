package com.auraboot.framework.review.dto;

import java.math.BigDecimal;
import java.util.Map;

public record ReviewSummaryResponse(
        String targetType,
        String targetId,
        long totalCount,
        BigDecimal averageRating,
        Map<Integer, Long> distribution
) {
}
