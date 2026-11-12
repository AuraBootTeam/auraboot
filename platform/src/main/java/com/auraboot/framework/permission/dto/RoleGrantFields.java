package com.auraboot.framework.permission.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;

/** Positive field allowances belong to one complete binding, never to a model-wide role union. */
public final class RoleGrantFields {
    private RoleGrantFields() {}
    @Schema(name="RoleGrantFieldsChange", description="Set exactly one of bindingPid (existing binding) or permissionCode (newly selected permission). New permissions require the candidate metadata revision.")
    public record Change(@Schema(nullable=true) @Size(max=32) String bindingPid,
            @Schema(nullable=true) @Size(max=255) String permissionCode,
            @Schema(nullable=true) @Pattern(regexp="[a-f0-9]{64}") String expectedMetadataRevision,
            @NotNull @Size(max=1000) List<String> readFields,
            @NotNull @Size(max=1000) List<String> writeFields,
            @Schema(nullable=true, description="Business object; omitted requests retain the permission's canonical resource model.")
            @Pattern(regexp="[A-Za-z_][A-Za-z0-9_]{0,127}") String modelCode) {
        public Change(String bindingPid, String permissionCode, String revision, List<String> reads, List<String> writes) {
            this(bindingPid, permissionCode, revision, reads, writes, null);
        }
        public Change(String bindingPid, List<String> readFields, List<String> writeFields) {
            this(bindingPid, null, null, readFields, writeFields, null);
        }
    }
    @Schema(name="RoleGrantFieldsField")
    public record Field(String code, @Schema(nullable=true) String label) {}
    @Schema(name="RoleGrantFieldsBinding")
    public record Binding(@Schema(nullable=true) String bindingPid, String permissionCode, String modelCode,
            @Schema(nullable=true) String modelLabel, boolean editable, boolean legacy,
            List<String> readFields, List<String> writeFields, List<Field> fields, String metadataRevision,
            boolean configured) {
        public Binding(String pid, String code, String model, String label, boolean editable, boolean legacy,
                List<String> reads, List<String> writes, List<Field> fields, String revision) {
            this(pid, code, model, label, editable, legacy, reads, writes, fields, revision, true);
        }
        public Binding(String bindingPid, String permissionCode, String modelCode, String label,
                boolean editable, boolean legacy, List<String> readFields, List<String> writeFields, List<Field> fields) {
            this(bindingPid, permissionCode, modelCode, label, editable, legacy, readFields, writeFields, fields, null, true);
        }
    }
    @Schema(name="RoleGrantFieldsReviewed")
    public record Reviewed(Binding before, List<String> readFields, List<String> writeFields) {}
}
