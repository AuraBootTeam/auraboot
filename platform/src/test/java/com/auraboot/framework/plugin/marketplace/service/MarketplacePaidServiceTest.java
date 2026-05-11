package com.auraboot.framework.plugin.marketplace.service;

import com.auraboot.framework.application.tenant.MetaContext;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class MarketplacePaidServiceTest {

    @Mock
    private DynamicDataMapper dynamicDataMapper;

    private MarketplacePaidService service;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(100L, 200L, "USER-PID", "admin");
        service = new MarketplacePaidService(
                dynamicDataMapper,
                List.of(new LocalTestMarketplacePaymentProvider()),
                new ObjectMapper()
        );
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    @DisplayName("checkout stores pid companions and no internal record id")
    void checkoutStoresPidCompanions() {
        when(dynamicDataMapper.selectByQuery(anyStringSql(), anyMap())).thenReturn(List.of());
        when(dynamicDataMapper.insert(eq("mt_mkt_purchase"), anyMap())).thenReturn(1);

        CheckoutRequest request = new CheckoutRequest();
        request.setPluginPid("PLG-PID");
        request.setPricingPlanPid("PLAN-PID");
        request.setBuyerTenantPid("TENANT-PID");
        request.setAmount(new BigDecimal("19.99"));
        request.setCurrency("usd");
        request.setIdempotencyKey("IDEMP-1");

        CheckoutResponse response = service.checkout(request);

        ArgumentCaptor<Map<String, Object>> captor = mapCaptor();
        verify(dynamicDataMapper).insert(eq("mt_mkt_purchase"), captor.capture());
        Map<String, Object> row = captor.getValue();

        assertThat(row).doesNotContainKey("id");
        assertThat(row).containsEntry("tenant_id", 100L);
        assertThat(row).containsEntry("mkt_pur_plugin_pid", "PLG-PID");
        assertThat(row).containsEntry("mkt_pur_plugin_id", "PLG-PID");
        assertThat(row).containsEntry("mkt_pur_plan_pid", "PLAN-PID");
        assertThat(row).containsEntry("mkt_pur_plan_id", "PLAN-PID");
        assertThat(row).containsEntry("mkt_pur_buyer_tenant_pid", "TENANT-PID");
        assertThat(row).containsEntry("mkt_pur_buyer_tenant_id", "TENANT-PID");
        assertThat(row).containsEntry("mkt_pur_payment_provider", "local_test");
        assertThat(row).containsEntry("mkt_pur_provider_payment_id", "local_test:checkout:IDEMP-1");
        assertThat(row).containsEntry("mkt_pur_idempotency_key", "IDEMP-1");
        assertThat(row).containsEntry("mkt_pur_status", "checkout_started");

        assertThat(response.getPurchasePid()).isNotBlank();
        assertThat(response.getProvider()).isEqualTo("local_test");
        assertThat(response.getProviderPaymentId()).isEqualTo("local_test:checkout:IDEMP-1");
    }

    @Test
    @DisplayName("install token issue stores hash and returns pid-only claims")
    void issueInstallTokenStoresHashAndReturnsPidOnlyClaims() {
        when(dynamicDataMapper.selectByQuery(anyStringSql(), anyMap())).thenReturn(List.of(activePurchase()));
        when(dynamicDataMapper.insert(eq("mt_mkt_install_token"), anyMap())).thenReturn(1);

        IssueInstallTokenRequest request = new IssueInstallTokenRequest();
        request.setPurchasePid("PUR-PID");
        request.setVersionPid("VER-PID");
        request.setTargetInstanceUrl("https://tenant.example");

        IssueInstallTokenResponse response = service.issueInstallToken(request);

        ArgumentCaptor<Map<String, Object>> captor = mapCaptor();
        verify(dynamicDataMapper).insert(eq("mt_mkt_install_token"), captor.capture());
        Map<String, Object> row = captor.getValue();

        assertThat(response.getToken()).isNotBlank();
        assertThat(row.get("mkt_tok_token")).asString().startsWith("sha256:");
        assertThat(row.get("mkt_tok_token")).isNotEqualTo(response.getToken());
        assertThat(row).containsEntry("mkt_tok_plugin_pid", "PLG-PID");
        assertThat(row).containsEntry("mkt_tok_plugin_id", "PLG-PID");
        assertThat(row).containsEntry("mkt_tok_version_pid", "VER-PID");
        assertThat(row).containsEntry("mkt_tok_version_id", "VER-PID");
        assertThat(row).containsEntry("mkt_tok_purchase_pid", "PUR-PID");
        assertThat(row).containsEntry("mkt_tok_purchase_id", "PUR-PID");

        assertThat(response.getClaims())
                .containsEntry("purchasePid", "PUR-PID")
                .containsEntry("pluginPid", "PLG-PID")
                .containsEntry("versionPid", "VER-PID")
                .containsEntry("buyerTenantPid", "TENANT-PID");
        assertThat(response.getClaims()).doesNotContainKeys("id", "pluginId", "versionId", "purchaseId");
    }

    @Test
    @DisplayName("install token issue rejects non-active purchase")
    void issueInstallTokenRejectsNonActivePurchase() {
        Map<String, Object> purchase = activePurchase();
        purchase.put("mkt_pur_status", "checkout_started");
        when(dynamicDataMapper.selectByQuery(anyStringSql(), anyMap())).thenReturn(List.of(purchase));

        IssueInstallTokenRequest request = new IssueInstallTokenRequest();
        request.setPurchasePid("PUR-PID");
        request.setVersionPid("VER-PID");

        assertThatThrownBy(() -> service.issueInstallToken(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Purchase must be active");
    }

    @Test
    @DisplayName("redeem token marks issued token as redeemed once")
    void redeemTokenMarksIssuedTokenRedeemed() {
        IssueInstallTokenResponse issued = issueTokenForRedeem();
        when(dynamicDataMapper.selectByQuery(anyStringSql(), anyMap())).thenReturn(List.of(issuedToken(issued.getToken())));
        when(dynamicDataMapper.update(eq("mt_mkt_install_token"), anyMap(), anyMap())).thenReturn(1);

        RedeemInstallTokenRequest request = new RedeemInstallTokenRequest();
        request.setToken(issued.getToken());
        request.setTargetInstanceUrl("https://tenant.example");

        RedeemInstallTokenResponse response = service.redeemInstallToken(request);

        ArgumentCaptor<Map<String, Object>> updateCaptor = mapCaptor();
        verify(dynamicDataMapper).update(eq("mt_mkt_install_token"), updateCaptor.capture(), anyMap());
        assertThat(updateCaptor.getValue()).containsEntry("mkt_tok_status", "redeemed");
        assertThat(updateCaptor.getValue()).containsKey("mkt_tok_redeemed_at");
        assertThat(response.getStatus()).isEqualTo("redeemed");
        assertThat(response.getPurchasePid()).isEqualTo("PUR-PID");
    }

    @Test
    @DisplayName("install authorization redeems issued token with matching plugin and version pid")
    void authorizeInstallTokenRedeemsMatchingIssuedToken() {
        IssueInstallTokenResponse issued = issueTokenForRedeem();
        when(dynamicDataMapper.selectByQuery(anyStringSql(), anyMap())).thenReturn(List.of(issuedToken(issued.getToken())));
        when(dynamicDataMapper.update(eq("mt_mkt_install_token"), anyMap(), anyMap())).thenReturn(1);

        RedeemInstallTokenResponse response = service.authorizeInstallTokenForInstall(
                issued.getToken(),
                "PLG-PID",
                "VER-PID",
                "https://tenant.example"
        );

        assertThat(response.getStatus()).isEqualTo("redeemed");
        assertThat(response.getPluginPid()).isEqualTo("PLG-PID");
        assertThat(response.getVersionPid()).isEqualTo("VER-PID");
        ArgumentCaptor<Map<String, Object>> updateCaptor = mapCaptor();
        verify(dynamicDataMapper).update(eq("mt_mkt_install_token"), updateCaptor.capture(), anyMap());
        assertThat(updateCaptor.getValue()).containsEntry("mkt_tok_status", "redeemed");
    }

    @Test
    @DisplayName("install authorization rejects token scoped to another plugin")
    void authorizeInstallTokenRejectsPluginMismatch() {
        IssueInstallTokenResponse issued = issueTokenForRedeem();
        when(dynamicDataMapper.selectByQuery(anyStringSql(), anyMap())).thenReturn(List.of(issuedToken(issued.getToken())));

        assertThatThrownBy(() -> service.authorizeInstallTokenForInstall(
                issued.getToken(),
                "OTHER-PLUGIN-PID",
                "VER-PID",
                "https://tenant.example"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("plugin mismatch");
    }

    @Test
    @DisplayName("provider event replay is deduped by event key")
    void providerEventReplayIsDeduped() {
        Map<String, Object> activePurchase = activePurchase();
        activePurchase.put("mkt_pur_provider_payment_id", "local_test:pay:1");
        Map<String, Object> processedEvent = new java.util.LinkedHashMap<>(Map.of(
                "pid", "EVT-PID",
                "mkt_evt_status", "processed",
                "mkt_evt_purchase_pid", "PUR-PID"
        ));

        when(dynamicDataMapper.selectByQuery(anyStringSql(), anyMap()))
                .thenReturn(List.of())
                .thenReturn(List.of(activePurchase()))
                .thenReturn(List.of(processedEvent))
                .thenReturn(List.of(activePurchase));
        when(dynamicDataMapper.insert(eq("mt_mkt_provider_event"), anyMap())).thenReturn(1);
        when(dynamicDataMapper.update(eq("mt_mkt_purchase"), anyMap(), anyMap())).thenReturn(1);
        when(dynamicDataMapper.update(eq("mt_mkt_provider_event"), anyMap(), anyMap())).thenReturn(1);

        PaymentEventRequest request = new PaymentEventRequest();
        request.setPurchasePid("PUR-PID");
        request.setProvider("local_test");
        request.setProviderPaymentId("local_test:pay:1");
        request.setEventId("evt-1");
        request.setEventType("payment_confirmed");

        PaymentEventResponse first = service.applyPaymentEvent(request);
        PaymentEventResponse replay = service.applyPaymentEvent(request);

        assertThat(first.isReplayed()).isFalse();
        assertThat(first.getStatus()).isEqualTo("active");
        assertThat(replay.isReplayed()).isTrue();
        assertThat(replay.getEventPid()).isEqualTo("EVT-PID");
        verify(dynamicDataMapper, times(1)).update(eq("mt_mkt_purchase"), anyMap(), anyMap());
    }

    @Test
    @DisplayName("revoke purchase revokes issued tokens by purchase pid")
    void revokePurchaseRevokesIssuedTokens() {
        when(dynamicDataMapper.selectByQuery(anyStringSql(), anyMap())).thenReturn(List.of(activePurchase()));
        when(dynamicDataMapper.update(eq("mt_mkt_purchase"), anyMap(), anyMap())).thenReturn(1);
        when(dynamicDataMapper.update(eq("mt_mkt_install_token"), anyMap(), anyMap())).thenReturn(2);
        when(dynamicDataMapper.insert(eq("mt_mkt_provider_event"), anyMap())).thenReturn(1);
        when(dynamicDataMapper.update(eq("mt_mkt_provider_event"), anyMap(), anyMap())).thenReturn(1);

        RevokePurchaseRequest request = new RevokePurchaseRequest();
        request.setPurchasePid("PUR-PID");
        request.setReason("Contract violation");

        RevokePurchaseResponse response = service.revokePurchase(request);

        assertThat(response.getStatus()).isEqualTo("revoked");
        assertThat(response.getRevokedTokenCount()).isEqualTo(2);
        assertThat(response.getEventPid()).isNotBlank();

        ArgumentCaptor<Map<String, Object>> updateCaptor = mapCaptor();
        verify(dynamicDataMapper).update(eq("mt_mkt_purchase"), updateCaptor.capture(), anyMap());
        assertThat(updateCaptor.getValue()).containsEntry("mkt_pur_revoke_reason", "Contract violation");
        assertThat(updateCaptor.getValue()).containsEntry("mkt_pur_last_operator_action", "revoked");
        assertThat(updateCaptor.getValue()).containsEntry("mkt_pur_last_operator_pid", "USER-PID");
    }

    @Test
    @DisplayName("refund purchase records provider refund reference")
    void refundPurchaseRecordsProviderRefundReference() {
        when(dynamicDataMapper.selectByQuery(anyStringSql(), anyMap())).thenReturn(List.of(activePurchase()));
        when(dynamicDataMapper.update(eq("mt_mkt_purchase"), anyMap(), anyMap())).thenReturn(1);
        when(dynamicDataMapper.update(eq("mt_mkt_install_token"), anyMap(), anyMap())).thenReturn(1);
        when(dynamicDataMapper.insert(eq("mt_mkt_provider_event"), anyMap())).thenReturn(1);
        when(dynamicDataMapper.update(eq("mt_mkt_provider_event"), anyMap(), anyMap())).thenReturn(1);

        RevokePurchaseRequest request = new RevokePurchaseRequest();
        request.setPurchasePid("PUR-PID");
        request.setReason("Customer refund");
        request.setProviderRefundReference("re_123");

        RevokePurchaseResponse response = service.refundPurchase(request);

        assertThat(response.getStatus()).isEqualTo("refunded");
        assertThat(response.getProviderRefundReference()).isEqualTo("re_123");

        ArgumentCaptor<Map<String, Object>> purchaseUpdateCaptor = mapCaptor();
        verify(dynamicDataMapper).update(eq("mt_mkt_purchase"), purchaseUpdateCaptor.capture(), anyMap());
        assertThat(purchaseUpdateCaptor.getValue())
                .containsEntry("mkt_pur_refund_reason", "Customer refund")
                .containsEntry("mkt_pur_provider_refund_reference", "re_123")
                .containsEntry("mkt_pur_last_operator_action", "refunded");

        ArgumentCaptor<Map<String, Object>> eventInsertCaptor = mapCaptor();
        verify(dynamicDataMapper).insert(eq("mt_mkt_provider_event"), eventInsertCaptor.capture());
        assertThat(eventInsertCaptor.getValue()).containsEntry("mkt_evt_provider_reference", "re_123");
    }

    @Test
    @DisplayName("refund purchase asks payment provider when refund reference is omitted")
    void refundPurchaseAsksPaymentProviderWhenReferenceOmitted() {
        RecordingPaymentProvider stripeProvider = new RecordingPaymentProvider();
        service = new MarketplacePaidService(
                dynamicDataMapper,
                List.of(new LocalTestMarketplacePaymentProvider(), stripeProvider),
                new ObjectMapper()
        );
        when(dynamicDataMapper.selectByQuery(anyStringSql(), anyMap())).thenReturn(List.of(stripePurchase()));
        when(dynamicDataMapper.update(eq("mt_mkt_purchase"), anyMap(), anyMap())).thenReturn(1);
        when(dynamicDataMapper.update(eq("mt_mkt_install_token"), anyMap(), anyMap())).thenReturn(1);
        when(dynamicDataMapper.insert(eq("mt_mkt_provider_event"), anyMap())).thenReturn(1);
        when(dynamicDataMapper.update(eq("mt_mkt_provider_event"), anyMap(), anyMap())).thenReturn(1);

        RevokePurchaseRequest request = new RevokePurchaseRequest();
        request.setPurchasePid("PUR-PID");
        request.setReason("Provider-backed refund");

        RevokePurchaseResponse response = service.refundPurchase(request);

        assertThat(stripeProvider.lastRefund).isNotNull();
        assertThat(stripeProvider.lastRefund.purchasePid()).isEqualTo("PUR-PID");
        assertThat(stripeProvider.lastRefund.providerPaymentId()).isEqualTo("pi_123");
        assertThat(stripeProvider.lastRefund.reason()).isEqualTo("Provider-backed refund");
        assertThat(response.getProviderRefundReference()).isEqualTo("re_provider_123");

        ArgumentCaptor<Map<String, Object>> purchaseUpdateCaptor = mapCaptor();
        verify(dynamicDataMapper).update(eq("mt_mkt_purchase"), purchaseUpdateCaptor.capture(), anyMap());
        assertThat(purchaseUpdateCaptor.getValue())
                .containsEntry("mkt_pur_provider_refund_reference", "re_provider_123")
                .containsEntry("mkt_pur_last_operator_action", "refunded");

        ArgumentCaptor<Map<String, Object>> eventInsertCaptor = mapCaptor();
        verify(dynamicDataMapper).insert(eq("mt_mkt_provider_event"), eventInsertCaptor.capture());
        assertThat(eventInsertCaptor.getValue()).containsEntry("mkt_evt_provider_reference", "re_provider_123");
    }

    @Test
    @DisplayName("refund confirmed event stores provider reference and revokes issued tokens")
    void refundConfirmedEventStoresProviderReference() {
        when(dynamicDataMapper.selectByQuery(anyStringSql(), anyMap()))
                .thenReturn(List.of())
                .thenReturn(List.of(activePurchase()));
        when(dynamicDataMapper.insert(eq("mt_mkt_provider_event"), anyMap())).thenReturn(1);
        when(dynamicDataMapper.update(eq("mt_mkt_purchase"), anyMap(), anyMap())).thenReturn(1);
        when(dynamicDataMapper.update(eq("mt_mkt_install_token"), anyMap(), anyMap())).thenReturn(1);
        when(dynamicDataMapper.update(eq("mt_mkt_provider_event"), anyMap(), anyMap())).thenReturn(1);

        PaymentEventRequest request = new PaymentEventRequest();
        request.setPurchasePid("PUR-PID");
        request.setProvider("stripe");
        request.setProviderPaymentId("pi_123");
        request.setProviderSessionId("cs_123");
        request.setProviderRefundReference("re_123");
        request.setEventId("evt-refund-1");
        request.setEventType("refund_confirmed");
        request.setRawPayload(Map.of("id", "evt-refund-1", "type", "refund.succeeded"));

        PaymentEventResponse response = service.applyPaymentEvent(request);

        assertThat(response.getStatus()).isEqualTo("refunded");
        ArgumentCaptor<Map<String, Object>> purchaseUpdateCaptor = mapCaptor();
        verify(dynamicDataMapper).update(eq("mt_mkt_purchase"), purchaseUpdateCaptor.capture(), anyMap());
        assertThat(purchaseUpdateCaptor.getValue())
                .containsEntry("mkt_pur_status", "refunded")
                .containsEntry("mkt_pur_provider_session_id", "cs_123")
                .containsEntry("mkt_pur_provider_refund_reference", "re_123");

        ArgumentCaptor<Map<String, Object>> eventInsertCaptor = mapCaptor();
        verify(dynamicDataMapper).insert(eq("mt_mkt_provider_event"), eventInsertCaptor.capture());
        assertThat(eventInsertCaptor.getValue())
                .containsEntry("mkt_evt_provider_reference", "re_123");
        assertThat(eventInsertCaptor.getValue().get("mkt_evt_raw_payload"))
                .asString()
                .contains("refund.succeeded");
    }

    private IssueInstallTokenResponse issueTokenForRedeem() {
        when(dynamicDataMapper.selectByQuery(anyStringSql(), anyMap())).thenReturn(List.of(activePurchase()));
        when(dynamicDataMapper.insert(eq("mt_mkt_install_token"), anyMap())).thenReturn(1);
        IssueInstallTokenRequest request = new IssueInstallTokenRequest();
        request.setPurchasePid("PUR-PID");
        request.setVersionPid("VER-PID");
        request.setTargetInstanceUrl("https://tenant.example");
        return service.issueInstallToken(request);
    }

    private Map<String, Object> activePurchase() {
        return new java.util.LinkedHashMap<>(Map.of(
                "pid", "PUR-PID",
                "mkt_pur_status", "active",
                "mkt_pur_payment_provider", "local_test",
                "mkt_pur_plugin_pid", "PLG-PID",
                "mkt_pur_buyer_tenant_pid", "TENANT-PID"
        ));
    }

    private Map<String, Object> stripePurchase() {
        Map<String, Object> purchase = activePurchase();
        purchase.put("mkt_pur_payment_provider", "stripe");
        purchase.put("mkt_pur_provider_payment_id", "pi_123");
        purchase.put("mkt_pur_provider_session_id", "cs_123");
        purchase.put("mkt_pur_amount", new BigDecimal("49.99"));
        purchase.put("mkt_pur_currency", "usd");
        return purchase;
    }

    private Map<String, Object> issuedToken(String token) {
        String tokenHash = "sha256:" + java.util.HexFormat.of().formatHex(hash(token));
        return new java.util.LinkedHashMap<>(Map.of(
                "pid", "TOK-PID",
                "mkt_tok_token", tokenHash,
                "mkt_tok_status", "issued",
                "mkt_tok_purchase_pid", "PUR-PID",
                "mkt_tok_plugin_pid", "PLG-PID",
                "mkt_tok_version_pid", "VER-PID",
                "mkt_tok_target_instance_url", "https://tenant.example",
                "mkt_tok_expires_at", Instant.now().plusSeconds(3600)
        ));
    }

    private byte[] hash(String token) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private String anyStringSql() {
        return org.mockito.ArgumentMatchers.anyString();
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Map<String, Object>> mapCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }

    private static final class RecordingPaymentProvider implements MarketplacePaymentProvider {
        private RefundRequest lastRefund;

        @Override
        public String provider() {
            return "stripe";
        }

        @Override
        public CheckoutSession createCheckout(CheckoutRequest request, String purchasePid) {
            throw new UnsupportedOperationException("checkout not used in this test");
        }

        @Override
        public RefundResult createRefund(RefundRequest request) {
            this.lastRefund = request;
            return new RefundResult("re_provider_123", Map.of("id", "re_provider_123"));
        }
    }
}
