package com.auraboot.framework.plugin.marketplace.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketplacePluginDTO {
    private String pid;
    private String pluginId;
    private String namespace;
    private String displayName;
    private String summary;
    private String author;
    private String iconUrl;
    private String pluginType;
    private String categoryCode;
    private String categoryName;
    private List<String> tags;
    private String status;
    private Boolean featured;
    private Integer installCount;
    private String latestVersion;
    private String licenseMode;
    private Instant publishedAt;
    private Boolean installed;
    private String installedVersion;
    private java.math.BigDecimal averageRating;
    private Integer reviewCount;
}
