package com.auraboot.framework.commerce.merchant;

import com.auraboot.framework.commerce.merchant.dto.MerchantCommerceContextResponse;

import java.util.Optional;

public interface CommerceMerchantContextService {

    Optional<MerchantCommerceContextResponse> currentContext();
}
