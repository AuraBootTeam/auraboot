package com.auraboot.framework.commerce.publicapi.dto;

import java.util.List;

public record StorefrontCartResponse(
        String id,
        String checkoutUrl,
        List<Line> lines,
        CommerceMoney subtotal
) {
    public record Line(
            String id,
            String productTitle,
            String variantTitle,
            Integer quantity,
            CommerceMoney unitPrice,
            CommerceMoney lineTotal
    ) {}
}
