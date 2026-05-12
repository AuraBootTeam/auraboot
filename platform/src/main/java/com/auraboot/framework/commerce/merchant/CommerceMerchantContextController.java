package com.auraboot.framework.commerce.merchant;

import com.auraboot.framework.commerce.merchant.dto.MerchantCommerceContextResponse;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class CommerceMerchantContextController {

    private final CommerceMerchantContextService merchantContextService;

    @RequirePermission(MetaPermission.TENANT_READ)
    @GetMapping("/api/commerce/merchant/context")
    public ApiResponse<MerchantCommerceContextResponse> currentContext() {
        return merchantContextService.currentContext()
                .map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.error(
                        ResponseCode.FORBIDDEN,
                        "Merchant tenant context is required",
                        null
                ));
    }
}
