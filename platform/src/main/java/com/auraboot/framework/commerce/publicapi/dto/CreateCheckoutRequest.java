package com.auraboot.framework.commerce.publicapi.dto;

import java.util.List;

public record CreateCheckoutRequest(
        String storeHandle,
        String cartId,
        List<StorefrontCartLineInput> lines,
        String email
) {}
