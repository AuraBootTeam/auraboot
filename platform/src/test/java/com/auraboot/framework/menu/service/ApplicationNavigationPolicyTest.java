package com.auraboot.framework.menu.service;

import com.auraboot.framework.application.release.ApplicationRuntimeDefinitionCatalog;
import com.auraboot.framework.menu.entity.Menu;
import com.auraboot.framework.menu.service.impl.MenuServiceImpl;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApplicationNavigationPolicyTest {
    private Menu menu(String code, Integer type, String path, String permission) {
        Menu menu = new Menu();
        menu.setCode(code);
        menu.setType(type);
        menu.setPath(path);
        menu.setPermissionCode(permission);
        menu.setVisible(true);
        return menu;
    }

    private Menu group(List<String> keep) {
        Menu group = menu("advanced", 0, null, null);
        ExtensionBean extension = new ExtensionBean();
        extension.setDynamicProperty("applicationNavigation", Map.of("keepRootCodes", keep, "deduplicatePaths", true));
        group.setExtension(extension);
        return group;
    }

    @Test
    void groupsOtherRootsWithoutChangingRoutesOrPermissions() {
        Menu product = menu("product", 1, "/class", "class.read");
        Menu platform = menu("platform", 1, "/platform", "platform.read");
        Menu advanced = group(List.of("product"));
        var result = ApplicationNavigationPolicy.groupRoots(List.of(product, platform, advanced));
        assertThat(result).containsExactly(product, advanced);
        assertThat(advanced.getChildren()).containsExactly(platform);
        assertThat(platform.getPath()).isEqualTo("/platform");
        assertThat(platform.getPermissionCode()).isEqualTo("platform.read");
    }

    @Test
    void leavesUnconfiguredApplicationsUntouched() {
        var roots = List.of(menu("platform", 1, "/platform", "platform.read"));
        assertThat(ApplicationNavigationPolicy.groupRoots(roots)).isSameAs(roots);
        assertThat(ApplicationNavigationPolicy.declared(roots)).isFalse();
    }

    @Test
    void rejectsAmbiguousMissingAndSelfReferencingPolicies() {
        assertThatThrownBy(() -> ApplicationNavigationPolicy.groupRoots(List.of(group(List.of()), group(List.of()))))
                .isInstanceOf(DataRetrievalFailureException.class);
        for (List<String> keep : List.of(List.of("missing"), List.of("advanced"), List.of("product", "product"))) {
            assertThatThrownBy(() -> ApplicationNavigationPolicy.groupRoots(List.of(menu("product", 1, "/class", null), group(keep))))
                    .isInstanceOf(DataRetrievalFailureException.class);
        }
    }

    @Test
    void serviceFiltersPermissionBeforeDeduplicationAndPrunesEmptyGroups() {
        Menu denied = menu("denied", 1, "/roles", "denied.read");
        Menu allowed = menu("allowed", 1, "/roles", "roles.read");
        Menu duplicate = menu("duplicate", 1, "/roles", "roles.read");
        Menu advanced = group(List.of("denied"));
        var grouped = ApplicationNavigationPolicy.groupRoots(List.of(denied, allowed, duplicate, advanced));
        var catalog = mock(ApplicationRuntimeDefinitionCatalog.class);
        when(catalog.menuTree(42L, "example")).thenReturn(grouped);
        MenuServiceImpl service = new MenuServiceImpl();
        ReflectionTestUtils.setField(service, "applicationRuntimePrimaryEnabled", true);
        ReflectionTestUtils.setField(service, "defaultApplicationCode", "example");
        ReflectionTestUtils.setField(service, "applicationRuntimeDefinitionCatalog", catalog);
        List<Menu> result = ReflectionTestUtils.invokeMethod(service, "releaseMenuTree", 42L, Set.of("roles.read"));
        assertThat(result).containsExactly(advanced);
        assertThat(advanced.getChildren()).containsExactly(allowed);
        assertThat(ApplicationNavigationPolicy.deduplicateVisible(List.of(group(List.of())))).isEmpty();
    }

    @Test
    void directoryPathDoesNotHideADeclaredChildPageOnTheSamePath() {
        // Rule Center (a type-1 entry that also parents the console's children)
        // must not consume the /decision-ops slot: the plugin declares Strategy
        // Studio as the default navigable entry on that path.
        Menu ruleCenter = menu("decisionops_console", 1, "/decision-ops", "decision.definition.read");
        Menu strategyStudio = menu("decisionops_strategy_studio", 1, "/decision-ops", "decision.definition.read");
        Menu definitions = menu("decisionops_definitions", 1, "/p/decisionops_definitions", "decision.definition.read");
        ruleCenter.setChildren(new java.util.ArrayList<>(List.of(strategyStudio, definitions)));

        List<Menu> result = ApplicationNavigationPolicy.deduplicateVisible(List.of(ruleCenter));

        assertThat(result).containsExactly(ruleCenter);
        assertThat(ruleCenter.getChildren()).containsExactly(strategyStudio, definitions);
    }
}
