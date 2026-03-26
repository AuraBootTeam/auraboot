package com.auraboot.framework.plugin.marketplace.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SolutionDetailDTO {
    private String pid;
    private String code;
    private String name;
    private String nameZh;
    private String nameEn;
    private String description;
    private String descriptionZh;
    private String descriptionEn;
    private String industry;
    private List<String> pluginCodes;
    private List<SolutionPluginInfo> plugins;
    private String iconUrl;
    private String coverImageUrl;
    private List<String> screenshots;
    private String readmeMarkdown;
    private String priceType;
    private BigDecimal price;
    private String status;
    private Integer installCount;
    private BigDecimal averageRating;
    private Integer reviewCount;
    private Boolean featured;
    private List<String> tags;
    private Instant createdAt;
    private Instant publishedAt;
    private Boolean installed;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SolutionPluginInfo {
        private String pluginId;
        private String displayName;
        private String summary;
        private String iconUrl;
        private Boolean installed;
        private Boolean availableInMarketplace;
    }
}
