package com.auraboot.framework.plugin.marketplace.service;

import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.plugin.marketplace.dto.MarketplacePaidDtos.CheckoutRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class LocalTestMarketplacePaymentProvider implements MarketplacePaymentProvider {

    public static final String PROVIDER = "local_test";

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public CheckoutSession createCheckout(CheckoutRequest request, String purchasePid) {
        String key = StringUtils.hasText(request.getIdempotencyKey())
                ? request.getIdempotencyKey()
                : purchasePid + ":" + UlidGenerator.nextULID();
        String providerPaymentId = PROVIDER + ":checkout:" + key;
        return new CheckoutSession(providerPaymentId, providerPaymentId, null);
    }

    @Override
    public RefundResult createRefund(RefundRequest request) {
        String key = StringUtils.hasText(request.idempotencyKey())
                ? request.idempotencyKey()
                : request.purchasePid() + ":" + UlidGenerator.nextULID();
        String providerRefundReference = PROVIDER + ":refund:" + key;
        return new RefundResult(providerRefundReference, java.util.Map.of(
                "id", providerRefundReference,
                "purchasePid", request.purchasePid(),
                "providerPaymentId", StringUtils.hasText(request.providerPaymentId()) ? request.providerPaymentId() : ""
        ));
    }
}
