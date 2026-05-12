package com.auraboot.framework.commerce.publicapi.dto;

import java.util.List;

public record StorefrontBootstrapResponse(
        String storeHandle,
        String storeName,
        String locale,
        String currencyCode,
        ThemeRef theme,
        List<ChannelRef> channels
) {
    public record ThemeRef(String themeId, String version, String previewToken) {}

    public record ChannelRef(String code, String name) {}
}
