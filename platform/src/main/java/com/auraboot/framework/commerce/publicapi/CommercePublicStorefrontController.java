package com.auraboot.framework.commerce.publicapi;

import com.auraboot.framework.commerce.publicapi.dto.CheckoutSessionResponse;
import com.auraboot.framework.commerce.publicapi.dto.CompleteCheckoutRequest;
import com.auraboot.framework.commerce.publicapi.dto.CompleteCheckoutResponse;
import com.auraboot.framework.commerce.publicapi.dto.CreateCheckoutRequest;
import com.auraboot.framework.commerce.publicapi.dto.CreateStorefrontCartRequest;
import com.auraboot.framework.commerce.publicapi.dto.StorefrontBootstrapResponse;
import com.auraboot.framework.commerce.publicapi.dto.StorefrontCartResponse;
import com.auraboot.framework.commerce.publicapi.dto.StorefrontProductDetailResponse;
import com.auraboot.framework.commerce.publicapi.dto.StorefrontProductListResponse;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class CommercePublicStorefrontController {

    private final CommercePublicApiService commercePublicApiService;

    @GetMapping("/api/public/stores/{storeHandle}/bootstrap")
    public ApiResponse<StorefrontBootstrapResponse> bootstrap(@PathVariable String storeHandle) {
        return commercePublicApiService.bootstrap(storeHandle)
                .map(ApiResponse::success)
                .orElseGet(() -> notFound("Store not found", Map.of("storeHandle", storeHandle)));
    }

    @GetMapping("/api/public/stores/{storeHandle}/products")
    public ApiResponse<StorefrontProductListResponse> products(
            @PathVariable String storeHandle,
            @RequestParam(required = false) String collectionHandle,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "12") Integer pageSize
    ) {
        return commercePublicApiService.products(storeHandle, collectionHandle, query, cursor, pageSize)
                .map(ApiResponse::success)
                .orElseGet(() -> notFound("Store not found", Map.of("storeHandle", storeHandle)));
    }

    @GetMapping("/api/public/stores/{storeHandle}/products/{handle}")
    public ApiResponse<StorefrontProductDetailResponse> product(
            @PathVariable String storeHandle,
            @PathVariable String handle
    ) {
        return commercePublicApiService.product(storeHandle, handle)
                .map(ApiResponse::success)
                .orElseGet(() -> notFound("Product not found", Map.of("storeHandle", storeHandle, "handle", handle)));
    }

    @PostMapping("/api/public/stores/{storeHandle}/cart")
    public ApiResponse<StorefrontCartResponse> createCart(
            @PathVariable String storeHandle,
            @RequestBody CreateStorefrontCartRequest request
    ) {
        return unsupported("Commerce cart runtime is not implemented yet");
    }

    @PostMapping("/api/public/checkouts")
    public ApiResponse<CheckoutSessionResponse> createCheckout(@RequestBody CreateCheckoutRequest request) {
        return unsupported("Commerce checkout runtime is not implemented yet");
    }

    @PostMapping("/api/public/checkouts/{checkoutId}/complete")
    public ApiResponse<CompleteCheckoutResponse> completeCheckout(
            @PathVariable String checkoutId,
            @RequestBody CompleteCheckoutRequest request
    ) {
        return unsupported("Commerce checkout completion is not implemented yet");
    }

    @SuppressWarnings("unchecked")
    private <T> ApiResponse<T> notFound(String message, Map<String, Object> context) {
        return (ApiResponse<T>) ApiResponse.error(ResponseCode.NOT_FOUND, message, context);
    }

    private <T> ApiResponse<T> unsupported(String message) {
        return ApiResponse.error(Integer.parseInt(ResponseCode.UnsupportedFeature.getCode()), message);
    }
}
