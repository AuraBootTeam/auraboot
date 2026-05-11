package com.auraboot.framework.plugin.marketplace.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.plugin.marketplace.dto.MarketplacePaidDtos.PaymentEventResponse;
import com.auraboot.framework.plugin.marketplace.service.MarketplaceStripeWebhookService;
import com.auraboot.framework.plugin.marketplace.service.MarketplaceStripeWebhookService.StripeWebhookException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MarketplaceStripeWebhookControllerTest {

    @Test
    void handleStripeWebhookReturnsPaidEventResponse() {
        MarketplaceStripeWebhookService service = mock(MarketplaceStripeWebhookService.class);
        PaymentEventResponse eventResponse = new PaymentEventResponse();
        eventResponse.setPurchasePid("PUR-PID");
        eventResponse.setStatus("active");
        when(service.handleWebhook("sig", "{}")).thenReturn(eventResponse);
        MarketplaceStripeWebhookController controller = new MarketplaceStripeWebhookController(service);

        ResponseEntity<ApiResponse<PaymentEventResponse>> response =
                controller.handleStripeWebhook("sig", "{}");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData().getPurchasePid()).isEqualTo("PUR-PID");
    }

    @Test
    void handleStripeWebhookReturnsSignatureFailureStatus() {
        MarketplaceStripeWebhookService service = mock(MarketplaceStripeWebhookService.class);
        when(service.handleWebhook("bad", "{}"))
                .thenThrow(new StripeWebhookException(401, "Stripe webhook signature verification failed"));
        MarketplaceStripeWebhookController controller = new MarketplaceStripeWebhookController(service);

        ResponseEntity<ApiResponse<PaymentEventResponse>> response =
                controller.handleStripeWebhook("bad", "{}");

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).contains("signature verification failed");
    }
}
