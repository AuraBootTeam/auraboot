package com.auraboot.framework.tenant.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Represents a "space" (tenant) that a user can enter.
 * Used by the Space Selection UI after login.
 *
 * @since 7.1.0
 */
@Data
@Builder
public class UserSpaceDTO {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;
    private String tenantName;
    private String tenantDisplayName;
    private String spaceType;       // "platform" (System Tenant) or "business" (Business Tenant)
    private List<String> roleCodes; // roles the user has in this tenant
    private boolean isDefault;      // hint: auto-select if only one business space
}
