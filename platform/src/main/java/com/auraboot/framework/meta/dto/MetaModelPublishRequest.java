package com.auraboot.framework.meta.dto;

import lombok.Data;

/**
 * Request body for model publishing governance.
 */
@Data
public class MetaModelPublishRequest {

    private String versionNote;

    private Boolean impactAcknowledged;

    private String acknowledgementNote;
}
