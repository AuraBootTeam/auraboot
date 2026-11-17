package com.auraboot.framework.permission.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;

/** Role-local restrictions; inherit never grants model or business write access. */
public final class RoleFieldAccess {
    private RoleFieldAccess() {}
    @Schema(name="RoleFieldAccessChange")
    public record Change(@NotBlank @Size(max=255) String modelCode,
            @NotBlank @Size(max=255) String fieldCode,
            @NotBlank @Pattern(regexp="inherit|read_only|hidden") String access) {}
    @Schema(name="RoleFieldAccessField")
    public record Field(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) String code,
            @Schema(nullable=true) String label,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String access,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<String> additionalRestrictions) {}
    @Schema(name="RoleFieldAccessModel")
    public record Model(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) String code,
            @Schema(nullable=true) String label,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<Field> fields) {}
    @Schema(name="RoleFieldAccessReviewed")
    public record Reviewed(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) String modelCode,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String fieldCode,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String modelLabel,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String fieldLabel,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String beforeAccess,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String access) {}
}
