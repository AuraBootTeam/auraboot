package com.auraboot.framework.auth.service;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;

/**
 * Runtime gate for optional federated identity providers.
 *
 * <p>The OSS default denies every external provider. Enterprise may supply a primary
 * implementation backed by deployment configuration and entitlement checks.</p>
 */
public interface FederatedIdentityFeatureGate {

    /** Whether the concrete provider implementation type is enabled. */
    boolean isEnabled(String providerType);

    /** Fail closed before exposing, configuring, or invoking a disabled provider. */
    default void requireEnabled(String providerType) {
        if (!isEnabled(providerType)) {
            throw new BusinessException(
                    ResponseCode.FORBIDDEN,
                    "Federated identity provider is not enabled");
        }
    }
}
