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
public class MarketplacePluginDetailDTO {
    private String pid;
    private String pluginId;
    private String namespace;
    private String displayName;
    private String displayNameZh;
    private String displayNameEn;
    private String summary;
    private String description;
    private String author;
    private String homepage;
    private String iconUrl;
    private String pluginType;
    private String categoryCode;
    private String categoryName;
    private List<String> tags;
    private String status;
    private String visibility;
    private Boolean featured;
    private Integer installCount;
    private String latestVersion;
    private Integer totalVersions;
    private String minPlatformVersion;
    private String licenseMode;
    private Instant createdAt;
    private Instant publishedAt;
    private Boolean installed;
    private String installedVersion;
    private List<MarketplaceVersionDTO> versions;
    private String readmeMarkdown;
    private List<String> screenshots;
    private java.math.BigDecimal averageRating;
    private Integer reviewCount;
}
