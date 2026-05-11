package com.auraboot.framework.plugin.marketplace.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.marketplace.dto.MarketplacePaidDtos.PaymentEventRequest;
import com.auraboot.framework.plugin.marketplace.dto.MarketplacePaidDtos.PaymentEventResponse;
import com.auraboot.framework.plugin.marketplace.service.MarketplaceStripeWebhookService.StripeWebhookException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketplaceStripeWebhookServiceTest {

    private static final String SECRET = "whsec_test_secret";

    @Mock
    private MarketplacePaidService paidService;

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    @DisplayName("signed checkout session webhook maps to payment confirmed event with tenant context")
    void signedCheckoutSessionWebhookMapsToPaymentEvent() {
        MarketplaceStripeWebhookService service = new MarketplaceStripeWebhookService(
                paidService,
                new ObjectMapper(),
                SECRET,
                300
        );
        String payload = """
                {
                  "id": "evt_checkout_1",
                  "type": "checkout.session.completed",
                  "data": {
                    "object": {
                      "id": "cs_test_1",
                      "payment_intent": "pi_test_1",
                      "client_reference_id": "PUR-PID",
                      "metadata": {
                        "tenant_id": "100",
                        "purchase_pid": "PUR-PID",
                        "idempotency_key": "idem-1"
                      }
                    }
                  }
                }
                """;
        when(paidService.applyPaymentEvent(any())).thenAnswer(invocation -> {
            assertThat(MetaContext.getCurrentTenantId()).isEqualTo(100L);
            assertThat(MetaContext.getCurrentUserPid()).isEqualTo("stripe-webhook");
            PaymentEventResponse response = new PaymentEventResponse();
            response.setPurchasePid("PUR-PID");
            response.setStatus("active");
            response.setProvider("stripe");
            response.setProviderPaymentId("pi_test_1");
            response.setEventPid("EVT-PID");
            return response;
        });

        PaymentEventResponse response = service.handleWebhook(signature(payload), payload);

        assertThat(response.getStatus()).isEqualTo("active");
        assertThat(MetaContext.exists()).isFalse();
        ArgumentCaptor<PaymentEventRequest> captor = ArgumentCaptor.forClass(PaymentEventRequest.class);
        verify(paidService).applyPaymentEvent(captor.capture());
        PaymentEventRequest request = captor.getValue();
        assertThat(request.getProvider()).isEqualTo("stripe");
        assertThat(request.getPurchasePid()).isEqualTo("PUR-PID");
        assertThat(request.getProviderPaymentId()).isEqualTo("pi_test_1");
        assertThat(request.getProviderSessionId()).isEqualTo("cs_test_1");
        assertThat(request.getProviderReference()).isEqualTo("cs_test_1");
        assertThat(request.getEventId()).isEqualTo("evt_checkout_1");
        assertThat(request.getEventType()).isEqualTo("payment_confirmed");
        assertThat(request.getIdempotencyKey()).isEqualTo("idem-1");
    }

    @Test
    @DisplayName("signed refund webhook maps provider refund reference")
    void signedRefundWebhookMapsProviderRefundReference() {
        MarketplaceStripeWebhookService service = new MarketplaceStripeWebhookService(
                paidService,
                new ObjectMapper(),
                SECRET,
                300
        );
        String payload = """
                {
                  "id": "evt_refund_1",
                  "type": "refund.succeeded",
                  "data": {
                    "object": {
                      "id": "re_test_1",
                      "payment_intent": "pi_test_1",
                      "metadata": {
                        "tenant_id": "100",
                        "purchase_pid": "PUR-PID"
                      }
                    }
                  }
                }
                """;
        when(paidService.applyPaymentEvent(any())).thenAnswer(invocation -> {
            PaymentEventResponse response = new PaymentEventResponse();
            response.setPurchasePid("PUR-PID");
            response.setStatus("refunded");
            response.setProvider("stripe");
            response.setProviderPaymentId("pi_test_1");
            response.setEventPid("EVT-PID");
            return response;
        });

        service.handleWebhook(signature(payload), payload);

        ArgumentCaptor<PaymentEventRequest> captor = ArgumentCaptor.forClass(PaymentEventRequest.class);
        verify(paidService).applyPaymentEvent(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("refund_confirmed");
        assertThat(captor.getValue().getProviderPaymentId()).isEqualTo("pi_test_1");
        assertThat(captor.getValue().getProviderReference()).isEqualTo("re_test_1");
        assertThat(captor.getValue().getProviderRefundReference()).isEqualTo("re_test_1");
    }

    @Test
    @DisplayName("invalid Stripe signature is rejected before processing")
    void invalidStripeSignatureIsRejected() {
        MarketplaceStripeWebhookService service = new MarketplaceStripeWebhookService(
                paidService,
                new ObjectMapper(),
                SECRET,
                300
        );
        String payload = "{\"id\":\"evt_bad\",\"type\":\"checkout.session.completed\",\"data\":{\"object\":{}}}";

        assertThatThrownBy(() -> service.handleWebhook("t=" + Instant.now().getEpochSecond() + ",v1=bad", payload))
                .isInstanceOfSatisfying(StripeWebhookException.class, ex -> {
                    assertThat(ex.getHttpStatus()).isEqualTo(401);
                    assertThat(ex.getMessage()).contains("signature verification failed");
                });
    }

    private String signature(String payload) {
        long timestamp = Instant.now().getEpochSecond();
        return "t=" + timestamp + ",v1=" + hmac(timestamp + "." + payload, SECRET);
    }

    private String hmac(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }
}
