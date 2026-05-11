package com.auraboot.framework.plugin.marketplace.dto;

import lombok.Data;

@Data
public class MarketplaceInstallRequest {
    private String version;
    private String installToken;
    private String targetInstanceUrl;
    private String conflictStrategy = "overwrite";
    private boolean autoPublishModels = true;
    private boolean autoPublishFields = true;
    private boolean autoPublishCommands = true;
    private boolean autoPublishPages = true;
}
