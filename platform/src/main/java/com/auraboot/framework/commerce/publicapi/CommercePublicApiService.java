package com.auraboot.framework.commerce.publicapi;

import com.auraboot.framework.commerce.publicapi.dto.StorefrontBootstrapResponse;
import com.auraboot.framework.commerce.publicapi.dto.StorefrontProductDetailResponse;
import com.auraboot.framework.commerce.publicapi.dto.StorefrontProductListResponse;

import java.util.Optional;

public interface CommercePublicApiService {

    Optional<StorefrontBootstrapResponse> bootstrap(String storeHandle);

    Optional<StorefrontProductListResponse> products(
            String storeHandle,
            String collectionHandle,
            String query,
            String cursor,
            Integer pageSize
    );

    Optional<StorefrontProductDetailResponse> product(String storeHandle, String handle);
}
