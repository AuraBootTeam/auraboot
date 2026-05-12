package com.auraboot.framework.commerce.publicapi.dto;

public record CheckoutSessionResponse(
        String id,
        String token,
        String storeHandle,
        String status,
        String cartId,
        CommerceMoney total
) {}
