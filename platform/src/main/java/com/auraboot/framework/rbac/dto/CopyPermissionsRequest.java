package com.auraboot.framework.rbac.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Public role PID is preferred; the internal-ID shape remains available to existing clients. */
@Schema(description="Provide exactly one target role identity; public PID is preferred.")
public record CopyPermissionsRequest(
        @Positive @Schema(requiredMode=Schema.RequiredMode.NOT_REQUIRED,nullable=true,deprecated=true) Long targetRoleId,
        @Size(min=1,max=64) @Schema(requiredMode=Schema.RequiredMode.NOT_REQUIRED,nullable=true) String targetRolePid
) {
    public CopyPermissionsRequest(Long targetRoleId) {this(targetRoleId,null);}

    @AssertTrue(message="Exactly one target role identity is required")
    @JsonIgnore
    @Schema(hidden=true)
    public boolean isValidTarget() {
        return (targetRoleId==null)!=(targetRolePid==null) && (targetRolePid==null || !targetRolePid.isBlank());
    }
}
