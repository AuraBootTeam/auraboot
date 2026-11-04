package com.auraboot.framework.permission.capability;

import com.auraboot.framework.menu.entity.Menu;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityNavigationGroupsTest {
    @Test
    void followsNestedNavigationAndItsOrderWithoutChangingAuthority() {
        Capability warehouse = capability("legacy warehouse", "inv.warehouse.read");
        Capability sales = capability("legacy sales", "sl.sales.read");
        var result = CapabilityNavigationGroups.regroup(List.of(
                new CapabilityGroup("legacy warehouse", List.of(warehouse)),
                new CapabilityGroup("legacy sales", List.of(sales))), List.of(
                menu(1, null, "仓库", null, 100),
                menu(2, 1L, "收货作业", null, 5),
                menu(3, 2L, "收货单", "inv.warehouse.read", 1),
                menu(4, null, "订单", null, 50),
                menu(5, 4L, "销售订单", "sl.sales.read", 1)));

        assertThat(result).extracting(CapabilityGroup::getGroup).containsExactly("订单", "仓库");
        assertThat(warehouse.getIncludes()).containsExactly("inv.warehouse.read");
        assertThat(warehouse.isGranted()).isFalse();
        assertThat(warehouse.getAuthorizationState()).isEqualTo("none");
        assertThat(warehouse.getMissingCodes()).containsExactly("inv.warehouse.read");
    }

    @Test
    void sharedPagePlacementsDoNotDuplicateCapabilityOrChooseAnArbitraryOwner() {
        Capability shared = capability("shared business duties", "shared.read");
        var result = CapabilityNavigationGroups.regroup(List.of(
                new CapabilityGroup(shared.getGroup(), List.of(shared))), List.of(
                menu(1, null, "仓库", null, 100), menu(2, null, "生产", null, 80),
                menu(3, 1L, "追溯", "shared.read", 1), menu(4, 2L, "追溯", "shared.read", 1)));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getGroup()).isEqualTo("shared business duties");
        assertThat(result.get(0).getCapabilities()).containsExactly(shared);
    }

    @Test
    void hiddenNavigationDoesNotRemoveDutyAndTechnicalDiagnosticsStayAdvanced() {
        Capability hidden = capability("old", "inventory.read");
        Capability background = capability("integration", "integration.execute");
        Capability technical = capability("model diagnostics", "model.inventory.read");
        technical.setConventionDerived(true);
        Menu root = menu(1, null, "仓库", null, 100);
        root.setVisible(false);
        var result = CapabilityNavigationGroups.regroup(List.of(
                new CapabilityGroup("old", List.of(hidden, background, technical))), List.of(
                root, menu(2, 1L, "库存", "inventory.read", 1),
                menu(3, 1L, "技术", "model.inventory.read", 2)));
        assertThat(result).flatExtracting(CapabilityGroup::getCapabilities).hasSize(3);
        assertThat(hidden.getGroup()).isEqualTo("仓库");
        assertThat(background.getGroup()).isEqualTo("integration");
        assertThat(technical.getGroup()).isEqualTo("model diagnostics");
        assertThat(technical.isConventionDerived()).isTrue();
    }

    @Test
    void rejectsCyclicAndMissingParentsInsteadOfSilentlyRegrouping() {
        var groups = List.of(new CapabilityGroup("old", List.of(capability("old", "inv.read"))));
        assertThatThrownBy(() -> CapabilityNavigationGroups.regroup(groups, List.of(
                menu(1, 2L, "a", "inv.read", 1), menu(2, 1L, "b", null, 2))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Cyclic");
        assertThatThrownBy(() -> CapabilityNavigationGroups.regroup(groups, List.of(
                menu(1, 99L, "a", "inv.read", 1))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Missing");
    }

    private static Capability capability(String group, String permission) {
        return Capability.builder().code("cap." + permission).group(group)
                .displayGroupOrder(10000).displayOrder(1).includes(List.of(permission))
                .authorizationState("none").missingCodes(List.of(permission)).build();
    }

    private static Menu menu(long id, Long parent, String name, String permission, int order) {
        Menu menu = new Menu();
        menu.setId(id);
        menu.setParentId(parent);
        menu.setName(name);
        menu.setPermissionCode(permission);
        menu.setOrderNo(order);
        return menu;
    }
}
