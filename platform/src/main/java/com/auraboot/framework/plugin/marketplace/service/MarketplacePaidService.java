package com.auraboot.framework.plugin.marketplace.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.plugin.marketplace.dto.MarketplacePaidDtos.CheckoutRequest;
import com.auraboot.framework.plugin.marketplace.dto.MarketplacePaidDtos.CheckoutResponse;
import com.auraboot.framework.plugin.marketplace.dto.MarketplacePaidDtos.IssueInstallTokenRequest;
import com.auraboot.framework.plugin.marketplace.dto.MarketplacePaidDtos.IssueInstallTokenResponse;
import com.auraboot.framework.plugin.marketplace.dto.MarketplacePaidDtos.PaymentEventRequest;
import com.auraboot.framework.plugin.marketplace.dto.MarketplacePaidDtos.PaymentEventResponse;
import com.auraboot.framework.plugin.marketplace.dto.MarketplacePaidDtos.RedeemInstallTokenRequest;
import com.auraboot.framework.plugin.marketplace.dto.MarketplacePaidDtos.RedeemInstallTokenResponse;
import com.auraboot.framework.plugin.marketplace.dto.MarketplacePaidDtos.RevokePurchaseRequest;
import com.auraboot.framework.plugin.marketplace.dto.MarketplacePaidDtos.RevokePurchaseResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MarketplacePaidService {

    private static final String PURCHASE_TABLE = "mt_mkt_purchase";
    private static final String TOKEN_TABLE = "mt_mkt_install_token";
    private static final String EVENT_TABLE = "mt_mkt_provider_event";
    private static final String PROVIDER_LOCAL_TEST = LocalTestMarketplacePaymentProvider.PROVIDER;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final DynamicDataMapper dynamicDataMapper;
    private final List<MarketplacePaymentProvider> paymentProviders;
    private final ObjectMapper objectMapper;

    @Transactional
    public CheckoutResponse checkout(CheckoutRequest request) {
        requireText(request.getPluginPid(), "pluginPid is required");
        requireText(request.getPricingPlanPid(), "pricingPlanPid is required");
        requireText(request.getBuyerTenantPid(), "buyerTenantPid is required");

        Long tenantId = currentTenantId();
        String providerCode = providerCode(request.getProvider());
        Map<String, Object> existing = findPurchaseByIdempotencyKey(tenantId, providerCode, request.getIdempotencyKey());
        if (existing != null) {
            return checkoutResponse(existing);
        }

        Instant now = Instant.now();
        String purchasePid = UlidGenerator.nextULID();
        MarketplacePaymentProvider.CheckoutSession checkoutSession =
                resolveProvider(providerCode).createCheckout(request, purchasePid);
        String providerPaymentId = checkoutSession.providerPaymentId();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("pid", purchasePid);
        row.put("tenant_id", tenantId);
        row.put("created_at", now);
        row.put("updated_at", now);
        row.put("created_by", MetaContext.getCurrentUserId());
        row.put("updated_by", MetaContext.getCurrentUserId());
        row.put("mkt_pur_plugin_pid", request.getPluginPid());
        row.put("mkt_pur_plugin_id", request.getPluginPid());
        row.put("mkt_pur_plan_pid", request.getPricingPlanPid());
        row.put("mkt_pur_plan_id", request.getPricingPlanPid());
        row.put("mkt_pur_buyer_tenant_pid", request.getBuyerTenantPid());
        row.put("mkt_pur_buyer_tenant_id", request.getBuyerTenantPid());
        row.put("mkt_pur_payment_provider", providerCode);
        row.put("mkt_pur_provider_payment_id", providerPaymentId);
        row.put("mkt_pur_provider_session_id", checkoutSession.providerSessionId());
        row.put("mkt_pur_provider_checkout_url", checkoutSession.checkoutUrl());
        row.put("mkt_pur_idempotency_key", request.getIdempotencyKey());
        row.put("mkt_pur_stripe_payment_id", providerPaymentId);
        row.put("mkt_pur_amount", request.getAmount() != null ? request.getAmount() : BigDecimal.ZERO);
        row.put("mkt_pur_currency", StringUtils.hasText(request.getCurrency()) ? request.getCurrency() : "usd");
        row.put("mkt_pur_status", "checkout_started");
        dynamicDataMapper.insert(PURCHASE_TABLE, row);
        return checkoutResponse(row);
    }

    @Transactional
    public PaymentEventResponse applyPaymentEvent(PaymentEventRequest request) {
        requireText(request.getEventType(), "eventType is required");
        String providerCode = providerCode(request.getProvider());
        String dedupeKey = eventDedupeKey(request, providerCode);
        requireText(dedupeKey, "eventId or idempotencyKey is required");

        Map<String, Object> existingEvent = findProviderEvent(dedupeKey);
        if (existingEvent != null && "processed".equals(stringValue(existingEvent.get("mkt_evt_status")))) {
            String purchasePid = stringValue(existingEvent.get("mkt_evt_purchase_pid"));
            Map<String, Object> purchase = requirePurchase(purchasePid);
            return paymentEventResponse(
                    purchasePid,
                    stringValue(purchase.get("mkt_pur_status")),
                    providerCode,
                    providerPaymentId(purchase, request.getProviderPaymentId()),
                    stringValue(existingEvent.get("pid")),
                    true
            );
        }

        String purchasePid = resolvePurchasePid(request);
        Map<String, Object> purchase = requirePurchase(purchasePid);
        Map<String, Object> event = existingEvent != null ? existingEvent : insertProviderEvent(request, providerCode, dedupeKey);
        String nextStatus = switch (request.getEventType()) {
            case "payment_confirmed" -> "active";
            case "payment_failed" -> "failed";
            case "refund_confirmed" -> "refunded";
            default -> throw new IllegalArgumentException("Unsupported payment event: " + request.getEventType());
        };

        Map<String, Object> update = baseUpdate();
        update.put("mkt_pur_status", nextStatus);
        if ("active".equals(nextStatus)) {
            update.put("mkt_pur_purchased_at", Instant.now());
        }
        if (StringUtils.hasText(request.getProviderPaymentId())) {
            update.put("mkt_pur_provider_payment_id", request.getProviderPaymentId());
            update.put("mkt_pur_stripe_payment_id", request.getProviderPaymentId());
        }
        if (StringUtils.hasText(request.getProviderSessionId())) {
            update.put("mkt_pur_provider_session_id", request.getProviderSessionId());
        }
        String providerRefundReference = providerRefundReference(request);
        if ("refunded".equals(nextStatus) && StringUtils.hasText(providerRefundReference)) {
            update.put("mkt_pur_provider_refund_reference", providerRefundReference);
        }
        update.put("mkt_pur_payment_provider", providerCode);
        dynamicDataMapper.update(PURCHASE_TABLE, update, pidConditions(purchasePid));
        if ("refunded".equals(nextStatus) || "failed".equals(nextStatus)) {
            revokeIssuedTokensForPurchase(purchasePid);
        }

        String eventPid = stringValue(event.get("pid"));
        markProviderEventProcessed(eventPid, purchasePid, nextStatus, null);

        return paymentEventResponse(
                purchasePid,
                nextStatus,
                providerCode,
                providerPaymentId(purchase, request.getProviderPaymentId()),
                eventPid,
                existingEvent != null
        );
    }

    @Transactional
    public IssueInstallTokenResponse issueInstallToken(IssueInstallTokenRequest request) {
        requireText(request.getPurchasePid(), "purchasePid is required");
        requireText(request.getVersionPid(), "versionPid is required");

        Map<String, Object> purchase = requirePurchase(request.getPurchasePid());
        String purchaseStatus = stringValue(purchase.get("mkt_pur_status"));
        if (!"active".equals(purchaseStatus)) {
            throw new IllegalStateException("Purchase must be active before issuing install token: " + request.getPurchasePid());
        }

        Instant now = Instant.now();
        long ttlHours = request.getTtlHours() != null && request.getTtlHours() > 0 ? request.getTtlHours() : 24L;
        Instant expiresAt = now.plus(ttlHours, ChronoUnit.HOURS);
        String tokenPid = UlidGenerator.nextULID();
        String token = tokenPid + "." + randomSecret();
        String tokenHash = tokenHash(token);
        String pluginPid = stringValue(purchase.get("mkt_pur_plugin_pid"));
        String buyerTenantPid = stringValue(purchase.get("mkt_pur_buyer_tenant_pid"));

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("tokenPid", tokenPid);
        claims.put("purchasePid", request.getPurchasePid());
        claims.put("pluginPid", pluginPid);
        claims.put("versionPid", request.getVersionPid());
        claims.put("buyerTenantPid", buyerTenantPid);
        claims.put("targetInstanceUrl", request.getTargetInstanceUrl());
        claims.put("issuedAt", now.toString());
        claims.put("expiresAt", expiresAt.toString());

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("pid", tokenPid);
        row.put("tenant_id", currentTenantId());
        row.put("created_at", now);
        row.put("updated_at", now);
        row.put("created_by", MetaContext.getCurrentUserId());
        row.put("updated_by", MetaContext.getCurrentUserId());
        row.put("mkt_tok_token", tokenHash);
        row.put("mkt_tok_plugin_pid", pluginPid);
        row.put("mkt_tok_plugin_id", pluginPid);
        row.put("mkt_tok_version_pid", request.getVersionPid());
        row.put("mkt_tok_version_id", request.getVersionPid());
        row.put("mkt_tok_purchase_pid", request.getPurchasePid());
        row.put("mkt_tok_purchase_id", request.getPurchasePid());
        row.put("mkt_tok_target_instance_url", request.getTargetInstanceUrl());
        row.put("mkt_tok_status", "issued");
        row.put("mkt_tok_expires_at", expiresAt);
        dynamicDataMapper.insert(TOKEN_TABLE, row);

        IssueInstallTokenResponse response = new IssueInstallTokenResponse();
        response.setTokenPid(tokenPid);
        response.setToken(token);
        response.setMaskedToken(maskToken(token));
        response.setStatus("issued");
        response.setExpiresAt(expiresAt);
        response.setClaims(claims);
        return response;
    }

    @Transactional
    public RedeemInstallTokenResponse redeemInstallToken(RedeemInstallTokenRequest request) {
        requireText(request.getToken(), "token is required");

        Map<String, Object> token = requireTokenByValue(request.getToken());
        if (!isIssuedToken(token)) {
            throw new IllegalStateException("Install token is not redeemable");
        }
        validateIssuedToken(token, request.getTargetInstanceUrl());

        String tokenPid = stringValue(token.get("pid"));
        updateTokenStatus(tokenPid, "redeemed", Instant.now());
        return tokenResponse(token, "redeemed");
    }

    @Transactional
    public RedeemInstallTokenResponse authorizeInstallTokenForInstall(
            String tokenValue,
            String pluginPid,
            String versionPid,
            String targetInstanceUrl
    ) {
        requireText(tokenValue, "installToken is required");
        requireText(pluginPid, "pluginPid is required");
        requireText(versionPid, "versionPid is required");

        Map<String, Object> token = requireTokenByValue(tokenValue);
        validateTokenScope(token, pluginPid, versionPid, targetInstanceUrl);

        if (isIssuedToken(token)) {
            validateIssuedToken(token, targetInstanceUrl);
            updateTokenStatus(stringValue(token.get("pid")), "redeemed", Instant.now());
            return tokenResponse(token, "redeemed");
        }
        if ("redeemed".equals(stringValue(token.get("mkt_tok_status")))) {
            return tokenResponse(token, "redeemed");
        }
        throw new IllegalStateException("Install token is not valid for installation");
    }

    @Transactional
    public RevokePurchaseResponse revokePurchase(RevokePurchaseRequest request) {
        requireText(request.getPurchasePid(), "purchasePid is required");
        requireText(request.getReason(), "reason is required");
        requirePurchase(request.getPurchasePid());

        Map<String, Object> purchaseUpdate = baseUpdate();
        purchaseUpdate.put("mkt_pur_status", "revoked");
        purchaseUpdate.put("mkt_pur_revoke_reason", request.getReason());
        putOperatorAudit(purchaseUpdate, "revoked", request.getReason());
        dynamicDataMapper.update(PURCHASE_TABLE, purchaseUpdate, pidConditions(request.getPurchasePid()));

        int revokedTokens = revokeIssuedTokensForPurchase(request.getPurchasePid());
        String eventPid = insertOperatorEvent(request.getPurchasePid(), "purchase_revoked", request.getReason(), "processed");
        RevokePurchaseResponse response = new RevokePurchaseResponse();
        response.setPurchasePid(request.getPurchasePid());
        response.setStatus("revoked");
        response.setRevokedTokenCount(revokedTokens);
        response.setEventPid(eventPid);
        return response;
    }

    @Transactional
    public RevokePurchaseResponse refundPurchase(RevokePurchaseRequest request) {
        requireText(request.getPurchasePid(), "purchasePid is required");
        requireText(request.getReason(), "reason is required");
        Map<String, Object> purchase = requirePurchase(request.getPurchasePid());
        String providerRefundReference = request.getProviderRefundReference();
        if (!StringUtils.hasText(providerRefundReference)) {
            providerRefundReference = createProviderRefund(purchase, request);
        }

        Map<String, Object> purchaseUpdate = baseUpdate();
        purchaseUpdate.put("mkt_pur_status", "refunded");
        purchaseUpdate.put("mkt_pur_refund_reason", request.getReason());
        if (StringUtils.hasText(providerRefundReference)) {
            purchaseUpdate.put("mkt_pur_provider_refund_reference", providerRefundReference);
        }
        putOperatorAudit(purchaseUpdate, "refunded", request.getReason());
        dynamicDataMapper.update(PURCHASE_TABLE, purchaseUpdate, pidConditions(request.getPurchasePid()));

        int revokedTokens = revokeIssuedTokensForPurchase(request.getPurchasePid());
        String eventPid = insertOperatorEvent(
                request.getPurchasePid(),
                "purchase_refunded",
                request.getReason(),
                "processed",
                providerRefundReference
        );
        RevokePurchaseResponse response = new RevokePurchaseResponse();
        response.setPurchasePid(request.getPurchasePid());
        response.setStatus("refunded");
        response.setRevokedTokenCount(revokedTokens);
        response.setEventPid(eventPid);
        response.setProviderRefundReference(providerRefundReference);
        return response;
    }

    private Map<String, Object> requirePurchase(String purchasePid) {
        requireText(purchasePid, "purchasePid is required");
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(
                "SELECT * FROM " + PURCHASE_TABLE + " WHERE tenant_id = #{params.tenantId} AND pid = #{params.purchasePid} LIMIT 1",
                Map.of("tenantId", currentTenantId(), "purchasePid", purchasePid));
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Purchase not found: " + purchasePid);
        }
        return rows.get(0);
    }

    private Map<String, Object> findPurchaseByIdempotencyKey(Long tenantId, String provider, String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return null;
        }
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(
                "SELECT * FROM " + PURCHASE_TABLE
                        + " WHERE tenant_id = #{params.tenantId}"
                        + " AND mkt_pur_payment_provider = #{params.provider}"
                        + " AND mkt_pur_idempotency_key = #{params.idempotencyKey}"
                        + " LIMIT 1",
                Map.of("tenantId", tenantId, "provider", provider, "idempotencyKey", idempotencyKey));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> findProviderEvent(String dedupeKey) {
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(
                "SELECT * FROM " + EVENT_TABLE
                        + " WHERE tenant_id = #{params.tenantId}"
                        + " AND mkt_evt_dedupe_key = #{params.dedupeKey}"
                        + " LIMIT 1",
                Map.of("tenantId", currentTenantId(), "dedupeKey", dedupeKey));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> insertProviderEvent(PaymentEventRequest request, String provider, String dedupeKey) {
        Instant now = Instant.now();
        String eventPid = UlidGenerator.nextULID();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("pid", eventPid);
        row.put("tenant_id", currentTenantId());
        row.put("created_at", now);
        row.put("updated_at", now);
        row.put("created_by", MetaContext.getCurrentUserId());
        row.put("updated_by", MetaContext.getCurrentUserId());
        row.put("mkt_evt_provider", provider);
        row.put("mkt_evt_event_id", request.getEventId());
        row.put("mkt_evt_dedupe_key", dedupeKey);
        row.put("mkt_evt_event_type", request.getEventType());
        row.put("mkt_evt_purchase_pid", request.getPurchasePid());
        row.put("mkt_evt_provider_payment_id", request.getProviderPaymentId());
        row.put("mkt_evt_provider_reference", providerReference(request));
        row.put("mkt_evt_status", "received");
        row.put("mkt_evt_payload_hash", payloadHash(eventPayload(request)));
        row.put("mkt_evt_raw_payload", safeJson(eventPayload(request)));
        dynamicDataMapper.insert(EVENT_TABLE, row);
        return row;
    }

    private void markProviderEventProcessed(String eventPid, String purchasePid, String resultStatus, String errorMessage) {
        if (!StringUtils.hasText(eventPid)) {
            return;
        }
        Map<String, Object> update = baseUpdate();
        update.put("mkt_evt_status", errorMessage == null ? "processed" : "failed");
        update.put("mkt_evt_purchase_pid", purchasePid);
        update.put("mkt_evt_result_status", resultStatus);
        update.put("mkt_evt_error_message", errorMessage);
        update.put("mkt_evt_processed_at", Instant.now());
        dynamicDataMapper.update(EVENT_TABLE, update, pidConditions(eventPid));
    }

    private String insertOperatorEvent(String purchasePid, String eventType, String reason, String status) {
        return insertOperatorEvent(purchasePid, eventType, reason, status, null);
    }

    private String insertOperatorEvent(
            String purchasePid,
            String eventType,
            String reason,
            String status,
            String providerReference
    ) {
        PaymentEventRequest request = new PaymentEventRequest();
        request.setPurchasePid(purchasePid);
        request.setProvider("operator");
        request.setEventId("operator:" + eventType + ":" + purchasePid + ":" + UlidGenerator.nextULID());
        request.setEventType(eventType);
        request.setIdempotencyKey(request.getEventId());
        request.setProviderReference(providerReference);
        String dedupeKey = eventDedupeKey(request, "operator");
        Map<String, Object> row = insertProviderEvent(request, "operator", dedupeKey);
        String eventPid = stringValue(row.get("pid"));
        Map<String, Object> update = baseUpdate();
        update.put("mkt_evt_status", status);
        update.put("mkt_evt_result_status", eventType);
        update.put("mkt_evt_raw_payload", safeJson(Map.of(
                "purchasePid", purchasePid,
                "eventType", eventType,
                "reason", reason,
                "providerReference", StringUtils.hasText(providerReference) ? providerReference : "",
                "operatorPid", StringUtils.hasText(MetaContext.getCurrentUserPid()) ? MetaContext.getCurrentUserPid() : ""
        )));
        update.put("mkt_evt_processed_at", Instant.now());
        dynamicDataMapper.update(EVENT_TABLE, update, pidConditions(eventPid));
        return eventPid;
    }

    private Map<String, Object> findTokenByHash(String hash) {
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(
                "SELECT * FROM " + TOKEN_TABLE + " WHERE tenant_id = #{params.tenantId} AND mkt_tok_token = #{params.tokenHash} LIMIT 1",
                Map.of("tenantId", currentTenantId(), "tokenHash", hash));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> requireTokenByValue(String tokenValue) {
        String hash = tokenHash(tokenValue);
        Map<String, Object> token = findTokenByHash(hash);
        if (token == null) {
            throw new IllegalArgumentException("Install token not found");
        }
        return token;
    }

    private boolean isIssuedToken(Map<String, Object> token) {
        return "issued".equals(stringValue(token.get("mkt_tok_status")));
    }

    private void validateIssuedToken(Map<String, Object> token, String targetInstanceUrl) {
        Instant expiresAt = instantValue(token.get("mkt_tok_expires_at"));
        if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
            updateTokenStatus(stringValue(token.get("pid")), "expired", null);
            throw new IllegalStateException("Install token expired");
        }
        validateTargetInstance(token, targetInstanceUrl);
    }

    private void validateTokenScope(Map<String, Object> token, String pluginPid, String versionPid, String targetInstanceUrl) {
        if (!pluginPid.equals(stringValue(token.get("mkt_tok_plugin_pid")))) {
            throw new IllegalArgumentException("Install token plugin mismatch");
        }
        if (!versionPid.equals(stringValue(token.get("mkt_tok_version_pid")))) {
            throw new IllegalArgumentException("Install token version mismatch");
        }
        validateTargetInstance(token, targetInstanceUrl);
    }

    private void validateTargetInstance(Map<String, Object> token, String targetInstanceUrl) {
        String expectedTargetInstanceUrl = stringValue(token.get("mkt_tok_target_instance_url"));
        if (StringUtils.hasText(expectedTargetInstanceUrl)
                && StringUtils.hasText(targetInstanceUrl)
                && !expectedTargetInstanceUrl.equals(targetInstanceUrl)) {
            throw new IllegalArgumentException("Install token target instance mismatch");
        }
    }

    private RedeemInstallTokenResponse tokenResponse(Map<String, Object> token, String status) {
        RedeemInstallTokenResponse response = new RedeemInstallTokenResponse();
        response.setTokenPid(stringValue(token.get("pid")));
        response.setPurchasePid(stringValue(token.get("mkt_tok_purchase_pid")));
        response.setPluginPid(stringValue(token.get("mkt_tok_plugin_pid")));
        response.setVersionPid(stringValue(token.get("mkt_tok_version_pid")));
        response.setStatus(status);
        return response;
    }

    private int revokeIssuedTokensForPurchase(String purchasePid) {
        Map<String, Object> update = baseUpdate();
        update.put("mkt_tok_status", "revoked");
        return dynamicDataMapper.update(TOKEN_TABLE, update,
                Map.of("tenant_id", currentTenantId(), "mkt_tok_purchase_pid", purchasePid, "mkt_tok_status", "issued"));
    }

    private void updateTokenStatus(String tokenPid, String status, Instant redeemedAt) {
        Map<String, Object> update = baseUpdate();
        update.put("mkt_tok_status", status);
        if (redeemedAt != null) {
            update.put("mkt_tok_redeemed_at", redeemedAt);
        }
        dynamicDataMapper.update(TOKEN_TABLE, update, pidConditions(tokenPid));
    }

    private Map<String, Object> baseUpdate() {
        Map<String, Object> update = new LinkedHashMap<>();
        update.put("updated_at", Instant.now());
        update.put("updated_by", MetaContext.getCurrentUserId());
        return update;
    }

    private void putOperatorAudit(Map<String, Object> update, String action, String reason) {
        update.put("mkt_pur_last_operator_action", action);
        update.put("mkt_pur_last_operator_reason", reason);
        update.put("mkt_pur_last_operator_pid", MetaContext.getCurrentUserPid());
        update.put("mkt_pur_last_operator_at", Instant.now());
    }

    private Map<String, Object> pidConditions(String pid) {
        return Map.of("tenant_id", currentTenantId(), "pid", pid);
    }

    private CheckoutResponse checkoutResponse(Map<String, Object> row) {
        CheckoutResponse response = new CheckoutResponse();
        response.setPurchasePid(stringValue(row.get("pid")));
        response.setPluginPid(stringValue(row.get("mkt_pur_plugin_pid")));
        response.setPricingPlanPid(stringValue(row.get("mkt_pur_plan_pid")));
        response.setBuyerTenantPid(stringValue(row.get("mkt_pur_buyer_tenant_pid")));
        response.setStatus(stringValue(row.get("mkt_pur_status")));
        response.setProvider(StringUtils.hasText(stringValue(row.get("mkt_pur_payment_provider")))
                ? stringValue(row.get("mkt_pur_payment_provider"))
                : PROVIDER_LOCAL_TEST);
        response.setProviderPaymentId(providerPaymentId(row, null));
        response.setProviderSessionRef(stringValue(row.get("mkt_pur_provider_session_id")));
        response.setCheckoutUrl(stringValue(row.get("mkt_pur_provider_checkout_url")));
        response.setAmount(bigDecimalValue(row.get("mkt_pur_amount")));
        response.setCurrency(stringValue(row.get("mkt_pur_currency")));
        return response;
    }

    private PaymentEventResponse paymentEventResponse(
            String purchasePid,
            String status,
            String provider,
            String providerPaymentId,
            String eventPid,
            boolean replayed
    ) {
        PaymentEventResponse response = new PaymentEventResponse();
        response.setPurchasePid(purchasePid);
        response.setStatus(status);
        response.setProvider(provider);
        response.setProviderPaymentId(providerPaymentId);
        response.setProviderReference(null);
        response.setEventPid(eventPid);
        response.setReplayed(replayed);
        return response;
    }

    private String providerReference(PaymentEventRequest request) {
        if (StringUtils.hasText(request.getProviderReference())) {
            return request.getProviderReference();
        }
        return request.getProviderRefundReference();
    }

    private String providerRefundReference(PaymentEventRequest request) {
        if (StringUtils.hasText(request.getProviderRefundReference())) {
            return request.getProviderRefundReference();
        }
        return request.getProviderReference();
    }

    private Object eventPayload(PaymentEventRequest request) {
        return request.getRawPayload() != null ? request.getRawPayload() : request;
    }

    private String providerPaymentId(Map<String, Object> purchase, String requestProviderPaymentId) {
        if (StringUtils.hasText(requestProviderPaymentId)) {
            return requestProviderPaymentId;
        }
        String providerPaymentId = stringValue(purchase.get("mkt_pur_provider_payment_id"));
        return StringUtils.hasText(providerPaymentId)
                ? providerPaymentId
                : stringValue(purchase.get("mkt_pur_stripe_payment_id"));
    }

    private String createProviderRefund(Map<String, Object> purchase, RevokePurchaseRequest request) {
        String provider = providerCode(stringValue(purchase.get("mkt_pur_payment_provider")));
        MarketplacePaymentProvider.RefundRequest refundRequest = new MarketplacePaymentProvider.RefundRequest(
                request.getPurchasePid(),
                providerPaymentId(purchase, null),
                stringValue(purchase.get("mkt_pur_provider_session_id")),
                bigDecimalValue(purchase.get("mkt_pur_amount")),
                stringValue(purchase.get("mkt_pur_currency")),
                request.getReason(),
                "marketplace:refund:" + request.getPurchasePid()
        );
        MarketplacePaymentProvider.RefundResult refundResult = resolveProvider(provider).createRefund(refundRequest);
        if (refundResult == null || !StringUtils.hasText(refundResult.providerRefundReference())) {
            throw new IllegalStateException("providerRefundReference is required");
        }
        return refundResult.providerRefundReference();
    }

    private MarketplacePaymentProvider resolveProvider(String provider) {
        Map<String, MarketplacePaymentProvider> providers = paymentProviders.stream()
                .collect(Collectors.toMap(MarketplacePaymentProvider::provider, Function.identity(), (left, right) -> right));
        MarketplacePaymentProvider paymentProvider = providers.get(providerCode(provider));
        if (paymentProvider == null) {
            throw new IllegalArgumentException("Unsupported marketplace payment provider: " + provider);
        }
        return paymentProvider;
    }

    private String providerCode(String provider) {
        return StringUtils.hasText(provider) ? provider.trim() : PROVIDER_LOCAL_TEST;
    }

    private String resolvePurchasePid(PaymentEventRequest request) {
        if (StringUtils.hasText(request.getPurchasePid())) {
            return request.getPurchasePid();
        }
        requireText(request.getProviderPaymentId(), "purchasePid or providerPaymentId is required");
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(
                "SELECT * FROM " + PURCHASE_TABLE
                        + " WHERE tenant_id = #{params.tenantId}"
                        + " AND (mkt_pur_provider_payment_id = #{params.providerPaymentId}"
                        + " OR mkt_pur_stripe_payment_id = #{params.providerPaymentId})"
                        + " LIMIT 1",
                Map.of("tenantId", currentTenantId(), "providerPaymentId", request.getProviderPaymentId()));
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Purchase not found for provider payment reference");
        }
        return stringValue(rows.get(0).get("pid"));
    }

    private String eventDedupeKey(PaymentEventRequest request, String provider) {
        if (StringUtils.hasText(request.getEventId())) {
            return provider + ":" + request.getEventId();
        }
        if (StringUtils.hasText(request.getIdempotencyKey())) {
            return provider + ":" + request.getIdempotencyKey();
        }
        if (StringUtils.hasText(request.getProviderPaymentId())) {
            return provider + ":" + request.getProviderPaymentId() + ":" + request.getEventType();
        }
        return null;
    }

    private String payloadHash(Object payload) {
        return tokenHash(safeJson(payload));
    }

    private String safeJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return String.valueOf(payload);
        }
    }

    private Long currentTenantId() {
        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("Tenant context required");
        }
        return tenantId;
    }

    private void requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
    }

    private String randomSecret() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String tokenHash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }

    private String maskToken(String token) {
        if (token.length() <= 12) {
            return "****";
        }
        return token.substring(0, 8) + "..." + token.substring(token.length() - 4);
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private BigDecimal bigDecimalValue(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return StringUtils.hasText(stringValue(value)) ? new BigDecimal(stringValue(value)) : null;
    }

    private Instant instantValue(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof java.util.Date date) {
            return date.toInstant();
        }
        return StringUtils.hasText(stringValue(value)) ? Instant.parse(stringValue(value)) : null;
    }
}
