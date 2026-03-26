package com.auraboot.framework.plugin.marketplace.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SolutionCreateRequest {
    private String code;
    private String name;
    private String nameZh;
    private String nameEn;
    private String description;
    private String descriptionZh;
    private String descriptionEn;
    private String industry;
    private List<String> pluginCodes;
    private String iconUrl;
    private String coverImageUrl;
    private List<String> screenshots;
    private String readmeMarkdown;
    private String priceType;
    private BigDecimal price;
    private List<String> tags;
    private Integer sortOrder;
}
