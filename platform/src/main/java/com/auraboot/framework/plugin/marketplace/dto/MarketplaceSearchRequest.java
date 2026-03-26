package com.auraboot.framework.plugin.marketplace.dto;

import lombok.Data;

@Data
public class MarketplaceSearchRequest {
    private String keyword;
    private String category;
    private String sort = "popular";
    private Integer page = 1;
    private Integer pageSize = 20;
}
