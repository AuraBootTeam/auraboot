package com.auraboot.framework.auth.service.impl;

import com.auraboot.framework.auth.service.FederatedIdentityFeatureGate;
import org.springframework.stereotype.Component;

/** Default OSS policy: external identity providers are opt-in and therefore disabled. */
@Component
public class DenyAllFederatedIdentityFeatureGate implements FederatedIdentityFeatureGate {

    @Override
    public boolean isEnabled(String providerType) {
        return false;
    }
}
