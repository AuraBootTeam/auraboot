package com.auraboot.framework.permission.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;

/** Positive field allowances belong to one complete binding, never to a model-wide role union. */
public final class RoleGrantFields {
    private RoleGrantFields() {}
    @Schema(name="RoleGrantFieldsChange")
    public record Change(@NotBlank @Size(max=32) String bindingPid,
            @NotNull @Size(max=1000) List<String> readFields,
            @NotNull @Size(max=1000) List<String> writeFields) {}
    @Schema(name="RoleGrantFieldsField")
    public record Field(String code, String label) {}
    @Schema(name="RoleGrantFieldsBinding")
    public record Binding(String bindingPid, String permissionCode, String modelCode,
            String modelLabel, boolean editable, boolean legacy,
            List<String> readFields, List<String> writeFields, List<Field> fields) {}
    @Schema(name="RoleGrantFieldsReviewed")
    public record Reviewed(Binding before, List<String> readFields, List<String> writeFields) {}
}
