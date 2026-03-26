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
public class MarketplaceVersionDTO {
    private String pid;
    private String version;
    private String changelog;
    private String changelogZh;
    private List<String> dependencies;
    private String minPlatformVersion;
    private Integer dslVersion;
    private String status;
    private Integer installCount;
    private Instant createdAt;
    private Instant publishedAt;
}
