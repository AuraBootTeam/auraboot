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
public class SolutionDTO {
    private String pid;
    private String code;
    private String name;
    private String nameZh;
    private String nameEn;
    private String description;
    private String industry;
    private List<String> pluginCodes;
    private String iconUrl;
    private String coverImageUrl;
    private String priceType;
    private BigDecimal price;
    private String status;
    private Integer installCount;
    private BigDecimal averageRating;
    private Integer reviewCount;
    private Boolean featured;
    private List<String> tags;
    private Instant publishedAt;
    private Boolean installed;
    private Integer pluginCount;
}
