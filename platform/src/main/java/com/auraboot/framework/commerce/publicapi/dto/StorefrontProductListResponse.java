package com.auraboot.framework.commerce.publicapi.dto;

import java.util.List;

public record StorefrontProductListResponse(
        List<StorefrontProductSummaryResponse> items,
        Long total,
        String nextCursor
) {}
