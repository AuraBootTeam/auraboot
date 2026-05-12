package com.auraboot.framework.commerce.publicapi.dto;

import java.util.List;

public record StorefrontProductDetailResponse(
        String id,
        String handle,
        String title,
        String subtitle,
        String featuredImage,
        CommerceMoney price,
        Boolean availableForSale,
        String descriptionHtml,
        List<Media> media,
        List<Variant> variants
) {
    public record Media(String id, String url, String altText) {}

    public record Variant(
            String id,
            String sku,
            String title,
            CommerceMoney price,
            Boolean availableForSale,
            List<SelectedOption> selectedOptions
    ) {}

    public record SelectedOption(String name, String value) {}
}
