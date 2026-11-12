package com.auraboot.framework.menu.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

/** Public navigation authoring contract; numeric storage identities never leave this boundary. */
public final class NavigationConfiguration {
    private NavigationConfiguration() {}

    @Schema(name="NavigationDefinition", requiredProperties = {"name", "kind", "target", "opening", "authenticated", "visible", "enabled", "orderNo"})
    public record Definition(@NotBlank @Size(max=120) String name, @Schema(nullable=true) @Size(max=26) String parentPid,
            @Schema(nullable=true) @Size(max=80) String icon, @NotBlank String kind, @Size(max=2048) String target,
            String opening, @Schema(nullable=true) @Size(max=150) String permissionCode, boolean authenticated,
            boolean visible, boolean enabled, @Min(0) @Max(100000) int orderNo) {}

    @Schema(name="NavigationWrite", requiredProperties = {"expectedRevision", "definition"})
    public record Write(@NotBlank String expectedRevision, @NotNull @Valid Definition definition) {}
    @Schema(name="NavigationRevision", requiredProperties={"expectedRevision"})
    public record Revision(@NotBlank String expectedRevision) {}

    @Schema(name="NavigationEntry", requiredProperties = {"pid", "definition", "source", "customized", "available"})
    public record Entry(String pid, Definition definition, String source, boolean customized, boolean available) {}
    @Schema(name="NavigationTarget", requiredProperties={"pid","name","kind","target","opening"})
    public record Target(String pid,String name,String kind,String target,String opening) {}

    @Schema(name="NavigationOption", requiredProperties={"value","label"})
    public record Option(String value, String label) {}
    @Schema(name="NavigationAudit", requiredProperties={"pid","menuPid","name","actor","action","createdAt"})
    public record Audit(String pid, String menuPid, String name, String actor, String action, String createdAt) {}

    @Schema(name="NavigationSnapshot", requiredProperties = {"revision", "entries", "pages", "permissions", "audit"})
    public record Snapshot(String revision, List<Entry> entries, List<Option> pages,
            List<Option> permissions, List<Audit> audit) {}
}
