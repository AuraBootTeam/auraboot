package com.auraboot.framework.menu.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Set;

/** Navigation assignments never contain business grants or record scopes. */
public final class RoleNavigation {
    private RoleNavigation() {}
    @Schema(name="RoleNavigationChoice",requiredProperties={"pid","name","directory","enabled","source","businessAllowed"})
    public record Choice(String pid,String name,@Schema(nullable=true) String parentPid,boolean directory,
            boolean enabled,String source,boolean businessAllowed,@Schema(nullable=true) String permissionCode) {}
    @Schema(name="RoleNavigationSelection",requiredProperties={"rolePid","revision","mode","selectedMenuPids","menus"})
    public record Selection(String rolePid,String revision,String mode,Set<String> selectedMenuPids,List<Choice> menus) {}
    @Schema(name="RoleNavigationWrite",requiredProperties={"expectedRevision","selectedMenuPids"})
    public record Write(@NotBlank @Pattern(regexp="[a-f0-9]{64}") String expectedRevision,
            @NotNull @Size(max=5000) Set<@NotBlank @Size(max=32) String> selectedMenuPids) {}
    @Schema(name="NavigationRoleOption",requiredProperties={"pid","name","enabled","selected","mode"})
    public record RoleOption(String pid,String name,boolean enabled,boolean selected,String mode) {}
    @Schema(name="NavigationEntryRoles",requiredProperties={"menuPid","revision","roles"})
    public record EntryRoles(String menuPid,String revision,List<RoleOption> roles) {}
    @Schema(name="NavigationEntryRolesWrite",requiredProperties={"expectedRevision","rolePids"})
    public record EntryRolesWrite(@NotBlank @Pattern(regexp="[a-f0-9]{64}") String expectedRevision,
            @NotNull @Size(max=5000) Set<@NotBlank @Size(max=32) String> rolePids) {}
    @Schema(name="NavigationPreviewMember",requiredProperties={"pid","name"})
    public record MemberOption(String pid,String name) {}
    @Schema(name="NavigationPreviewOptions",requiredProperties={"members","roles"})
    public record PreviewOptions(List<MemberOption> members,List<RoleOption> roles) {}
    @Schema(name="NavigationPreviewRequest")
    public record PreviewRequest(@Size(max=32) String memberPid,@Size(max=5000) Set<@NotBlank @Size(max=32) String> rolePids,
            @Size(max=32) String draftRolePid,@jakarta.validation.Valid com.auraboot.framework.permission.dto.RoleAuthorizationDraftRequest draft) {}
    @Schema(name="NavigationUserPreview",requiredProperties={"rolePids","menus","source"})
    public record UserPreview(Set<String> rolePids,List<Choice> menus,String source) {}
    @Schema(name="RoleAuthorizationChange",requiredProperties={"kind","target"})
    public record AuthorizationChange(String kind,String target,String before,String after) {}
    @Schema(name="RoleNavigationAudit",requiredProperties={"pid","actor","createdAt","addedMenus","removedMenus","previousMode","changes"})
    public record Audit(String pid,String actor,String createdAt,List<String> addedMenus,List<String> removedMenus,String previousMode,List<AuthorizationChange> changes) {}
}
