package com.auraboot.framework.commerce.publicapi.dto;

public record CompleteCheckoutRequest(String idempotencyKey, String paymentToken) {}
