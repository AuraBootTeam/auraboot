package com.auraboot.framework.commerce.publicapi.dto;

public record StorefrontProductSummaryResponse(
        String id,
        String handle,
        String title,
        String subtitle,
        String featuredImage,
        CommerceMoney price,
        Boolean availableForSale
) {}
