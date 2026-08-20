package com.auraboot.framework.plugin.dto.imports;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Locale;
import java.util.Set;

/**
 * Explicit data-scope override for one permission granted by a plugin role.
 *
 * <p>The permission supplies the canonical resource/action pair. This avoids
 * duplicating model codes and actions in role configuration while allowing a
 * role with a broad default scope to narrow selected business models.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoleDataScopeDefinitionDTO {

    private static final Set<String> VALID_SCOPES =
            Set.of("none", "self", "dept", "dept_and_sub", "all");
    private static final Set<String> VALID_MERGE_STRATEGIES = Set.of("MIN", "MAX");

    private String permissionCode;
    private String scopeType;

    @Builder.Default
    private String mergeStrategy = "MAX";

    public boolean isValid() {
        return permissionCode != null && !permissionCode.isBlank()
                && scopeType != null && VALID_SCOPES.contains(scopeType)
                && mergeStrategy != null
                && VALID_MERGE_STRATEGIES.contains(mergeStrategy.toUpperCase(Locale.ROOT));
    }

    public String effectiveMergeStrategy() {
        return mergeStrategy == null || mergeStrategy.isBlank()
                ? "MAX"
                : mergeStrategy.toUpperCase(Locale.ROOT);
    }
}
