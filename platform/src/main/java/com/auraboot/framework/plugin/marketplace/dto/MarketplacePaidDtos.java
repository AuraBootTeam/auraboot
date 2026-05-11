package com.auraboot.framework.plugin.marketplace.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public final class MarketplacePaidDtos {

    private MarketplacePaidDtos() {
    }

    @Data
    public static class CheckoutRequest {
        private String provider = "local_test";
        private String pluginPid;
        private String pricingPlanPid;
        private String buyerTenantPid;
        private BigDecimal amount;
        private String currency = "usd";
        private String idempotencyKey;
        private String successUrl;
        private String cancelUrl;
    }

    @Data
    public static class CheckoutResponse {
        private String purchasePid;
        private String pluginPid;
        private String pricingPlanPid;
        private String buyerTenantPid;
        private String status;
        private String provider;
        private String providerPaymentId;
        private String providerSessionRef;
        private String checkoutUrl;
        private BigDecimal amount;
        private String currency;
    }

    @Data
    public static class PaymentEventRequest {
        private String purchasePid;
        private String provider = "local_test";
        private String providerPaymentId;
        private String providerSessionId;
        private String providerReference;
        private String providerRefundReference;
        private String eventId;
        private String eventType;
        private String idempotencyKey;
        private Map<String, Object> rawPayload;
    }

    @Data
    public static class PaymentEventResponse {
        private String purchasePid;
        private String status;
        private String provider;
        private String providerPaymentId;
        private String providerReference;
        private String eventPid;
        private boolean replayed;
    }

    @Data
    public static class IssueInstallTokenRequest {
        private String purchasePid;
        private String versionPid;
        private String targetInstanceUrl;
        private Long ttlHours = 24L;
    }

    @Data
    public static class IssueInstallTokenResponse {
        private String tokenPid;
        private String token;
        private String maskedToken;
        private String status;
        private Instant expiresAt;
        private Map<String, Object> claims;
    }

    @Data
    public static class RedeemInstallTokenRequest {
        private String token;
        private String targetInstanceUrl;
    }

    @Data
    public static class RedeemInstallTokenResponse {
        private String tokenPid;
        private String purchasePid;
        private String pluginPid;
        private String versionPid;
        private String status;
    }

    @Data
    public static class RevokePurchaseRequest {
        private String purchasePid;
        private String reason;
        private String providerRefundReference;
    }

    @Data
    public static class RevokePurchaseResponse {
        private String purchasePid;
        private String status;
        private int revokedTokenCount;
        private String eventPid;
        private String providerRefundReference;
    }
}
