package com.auraboot.framework.plugin.marketplace.service;

import com.auraboot.framework.plugin.marketplace.dto.MarketplacePaidDtos.CheckoutRequest;

import java.math.BigDecimal;
import java.util.Map;

public interface MarketplacePaymentProvider {

    String provider();

    CheckoutSession createCheckout(CheckoutRequest request, String purchasePid);

    RefundResult createRefund(RefundRequest request);

    record CheckoutSession(
            String providerPaymentId,
            String providerSessionId,
            String checkoutUrl
    ) {
    }

    record RefundRequest(
            String purchasePid,
            String providerPaymentId,
            String providerSessionId,
            BigDecimal amount,
            String currency,
            String reason,
            String idempotencyKey
    ) {
    }

    record RefundResult(
            String providerRefundReference,
            Map<String, Object> rawPayload
    ) {
    }
}
