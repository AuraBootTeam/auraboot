package com.auraboot.framework.branding;

/** Deployment-level identity used by server-generated artifacts. */
public record BrandingIdentity(
        String productName,
        String platformName,
        String generatedByText) {

    public static BrandingIdentity community() {
        return new BrandingIdentity(
                CommunityBranding.PRODUCT_NAME,
                CommunityBranding.PLATFORM_NAME,
                CommunityBranding.GENERATED_BY_TEXT);
    }
}
