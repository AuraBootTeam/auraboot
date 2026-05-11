package com.auraboot.framework.plugin.marketplace.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.plugin.marketplace.dto.MarketplacePaidDtos.PaymentEventResponse;
import com.auraboot.framework.plugin.marketplace.service.MarketplaceStripeWebhookService;
import com.auraboot.framework.plugin.marketplace.service.MarketplaceStripeWebhookService.StripeWebhookException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/marketplace/paid/webhooks/stripe")
@RequiredArgsConstructor
@Tag(name = "Marketplace Stripe Webhook", description = "Signed Stripe webhook endpoint for paid Marketplace events")
public class MarketplaceStripeWebhookController {

    private final MarketplaceStripeWebhookService stripeWebhookService;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Handle a signed Stripe payment webhook")
    public ResponseEntity<ApiResponse<PaymentEventResponse>> handleStripeWebhook(
            @RequestHeader(value = "Stripe-Signature", required = false) String signature,
            @RequestBody String payload
    ) {
        try {
            return ResponseEntity.ok(ApiResponse.ok(stripeWebhookService.handleWebhook(signature, payload)));
        } catch (StripeWebhookException ex) {
            return ResponseEntity
                    .status(ex.getHttpStatus())
                    .body(ApiResponse.<PaymentEventResponse>error(ex.getHttpStatus(), ex.getMessage()));
        }
    }
}
