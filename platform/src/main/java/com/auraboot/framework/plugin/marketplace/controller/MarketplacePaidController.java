package com.auraboot.framework.plugin.marketplace.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
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
import com.auraboot.framework.plugin.marketplace.service.MarketplacePaidService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/marketplace/paid")
@RequiredArgsConstructor
@RequirePermission(MetaPermission.PLUGIN_MANAGE)
@Tag(name = "Marketplace Paid", description = "Provider-backed paid Marketplace checkout and install-token APIs")
public class MarketplacePaidController {

    private final MarketplacePaidService paidService;

    @PostMapping("/checkout")
    @Operation(summary = "Create a provider checkout purchase")
    public ApiResponse<CheckoutResponse> checkout(@RequestBody CheckoutRequest request) {
        return ApiResponse.ok(paidService.checkout(request));
    }

    @PostMapping("/payment-events")
    @Operation(summary = "Apply a provider payment event")
    public ApiResponse<PaymentEventResponse> applyPaymentEvent(@RequestBody PaymentEventRequest request) {
        return ApiResponse.ok(paidService.applyPaymentEvent(request));
    }

    @PostMapping("/payment-events/local-test")
    @Operation(summary = "Apply a local-test payment event")
    public ApiResponse<PaymentEventResponse> applyLocalPaymentEvent(@RequestBody PaymentEventRequest request) {
        request.setProvider("local_test");
        return ApiResponse.ok(paidService.applyPaymentEvent(request));
    }

    @PostMapping("/install-tokens")
    @Operation(summary = "Issue an install token for an active purchase")
    public ApiResponse<IssueInstallTokenResponse> issueInstallToken(@RequestBody IssueInstallTokenRequest request) {
        return ApiResponse.ok(paidService.issueInstallToken(request));
    }

    @PostMapping("/install-tokens/redeem")
    @Operation(summary = "Redeem an install token once")
    public ApiResponse<RedeemInstallTokenResponse> redeemInstallToken(@RequestBody RedeemInstallTokenRequest request) {
        return ApiResponse.ok(paidService.redeemInstallToken(request));
    }

    @PostMapping("/purchases/revoke")
    @Operation(summary = "Revoke a purchase and any issued install tokens")
    public ApiResponse<RevokePurchaseResponse> revokePurchase(@RequestBody RevokePurchaseRequest request) {
        return ApiResponse.ok(paidService.revokePurchase(request));
    }

    @PostMapping("/purchases/refund")
    @Operation(summary = "Refund a purchase and revoke any issued install tokens")
    public ApiResponse<RevokePurchaseResponse> refundPurchase(@RequestBody RevokePurchaseRequest request) {
        return ApiResponse.ok(paidService.refundPurchase(request));
    }
}
