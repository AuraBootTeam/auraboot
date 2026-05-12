package com.auraboot.framework.commerce.publicapi.dto;

public record CompleteCheckoutResponse(String checkoutId, String orderId, String status) {}
