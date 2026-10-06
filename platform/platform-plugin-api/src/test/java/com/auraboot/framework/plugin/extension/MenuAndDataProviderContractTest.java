package com.auraboot.framework.plugin.extension;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class MenuAndDataProviderContractTest {
    @Test
    void menuContextCarriesTenantIdentityAndRequiresExactRolesAndPermissions() {
        var context = MenuProviderExtension.MenuContext.builder().tenantId(7L).pluginId("plugin").namespace("ns")
                .userId(9L).userRoles(List.of("manager")).userPermissions(List.of("order.read"))
                .settings(Map.of("enabled", true)).build();
        assertThat(context).isEqualTo(new MenuProviderExtension.MenuContext(7L, "plugin", "ns", 9L,
                List.of("manager"), List.of("order.read"), Map.of("enabled", true)));
        assertThat(context.hasRole("manager")).isTrue();
        assertThat(context.hasRole("manage")).isFalse();
        assertThat(context.hasPermission("order.read")).isTrue();
        assertThat(context.hasPermission("order")).isFalse();
        var missing = MenuProviderExtension.MenuContext.builder().userRoles(null).userPermissions(null).build();
        assertThat(missing.hasRole("manager")).isFalse();
        assertThat(missing.hasPermission("order.read")).isFalse();
        var defaults = MenuProviderExtension.MenuContext.builder().build();
        assertThat(defaults.userRoles()).isEmpty();
        assertThat(defaults.userPermissions()).isEmpty();
        assertThat(defaults.settings()).isEmpty();
    }

    @Test
    void menuItemPreservesNavigationHierarchyAndAccessRequirements() {
        var child = MenuProviderExtension.MenuItem.builder().key("child").label("Child").path("/child").build();
        var item = MenuProviderExtension.MenuItem.builder().key("orders").label("Orders").icon("box")
                .path("/orders").target("_blank").order(12).children(List.of(child))
                .requiredRoles(List.of("manager")).requiredPermissions(List.of("order.read"))
                .metadata(Map.of("badge", 3)).build();
        assertThat(item).isEqualTo(new MenuProviderExtension.MenuItem("orders", "Orders", "box", "/orders", "_blank", 12,
                List.of(child), List.of("manager"), List.of("order.read"), Map.of("badge", 3)));
        assertThat(child.target()).isEqualTo("_self");
        assertThat(child.order()).isEqualTo(100);
        assertThat(child.children()).isEmpty();
        assertThat(child.requiredRoles()).isEmpty();
        assertThat(child.requiredPermissions()).isEmpty();
        assertThat(child.metadata()).isEmpty();
    }

    @Test
    void menuProviderDefaultsDoNotDispatchMenuConstruction() {
        MenuProviderExtension provider = new MenuProviderExtension() {
            public String getMenuGroup() { return "main-sidebar"; }
            public List<MenuItem> getMenuItems(MenuContext context) { throw new AssertionError("Unexpected dispatch"); }
        };
        assertThat(provider.isActive(MenuProviderExtension.MenuContext.builder().build())).isTrue();
        assertThat(provider.getOrder()).isEqualTo(100);
    }

    @Test
    void dataRequestCarriesFiltersPaginationAndPluginIdentityWithoutCrossWiring() {
        var request = DataProviderExtension.DataRequest.builder().tenantId(7L).pluginId("plugin").namespace("ns")
                .providerKey("ns:users").searchTerm("Alice").filters(Map.of("active", true))
                .offset(20).limit(10).settings(Map.of("sort", "name")).build();
        assertThat(request).isEqualTo(new DataProviderExtension.DataRequest(7L, "plugin", "ns", "ns:users", "Alice",
                Map.of("active", true), 20, 10, Map.of("sort", "name")));
        var defaults = DataProviderExtension.DataRequest.builder().build();
        assertThat(defaults.offset()).isZero();
        assertThat(defaults.limit()).isEqualTo(100);
        assertThat(defaults.filters()).isEmpty();
        assertThat(defaults.settings()).isEmpty();
    }

    @Test
    void defaultCountUsesTheSameRequestOnceAndSupportUsesExactKey() {
        var request = DataProviderExtension.DataRequest.builder().providerKey("ns:users").build();
        AtomicInteger calls = new AtomicInteger();
        DataProviderExtension provider = new DataProviderExtension() {
            public String getProviderKey() { return "ns:users"; }
            public List<DataItem> fetchData(DataRequest received) {
                assertThat(received).isSameAs(request);
                calls.incrementAndGet();
                return List.of(new DataItem("a", "Alice"), new DataItem("b", "Bob", Map.of("active", true)));
            }
        };
        assertThat(provider.getCount(request)).isEqualTo(2L);
        assertThat(calls).hasValue(1);
        assertThat(provider.supports("ns:users")).isTrue();
        assertThat(provider.supports("other:users")).isFalse();
        assertThat(provider.supports(null)).isFalse();
        assertThat(provider.isCacheable()).isTrue();
        assertThat(provider.getCacheTtlSeconds()).isEqualTo(300);
        assertThat(new DataProviderExtension.DataItem("a", "Alice").metadata()).isEmpty();
    }
}
