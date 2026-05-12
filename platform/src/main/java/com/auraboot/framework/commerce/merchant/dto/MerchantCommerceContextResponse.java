package com.auraboot.framework.commerce.merchant.dto;

import java.util.List;

public record MerchantCommerceContextResponse(
        Long tenantId,
        StoreSummary selectedStore,
        List<StoreSummary> stores,
        List<OperationLink> operations
) {
    public record StoreSummary(
            String id,
            String handle,
            String name,
            String status,
            String storefrontPath
    ) {
    }

    public record OperationLink(
            String code,
            String route,
            boolean enabled
    ) {
    }
}
