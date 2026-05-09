package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.DataProviderExtension;
import com.auraboot.framework.plugin.extension.EventListenerExtension;
import com.auraboot.framework.plugin.extension.MenuProviderExtension;
import com.auraboot.framework.plugin.extension.ValidatorExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.pf4j.PluginWrapper;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExtensionRegistryTest {

    @Mock private AuraPluginManager pluginManager;
    @Mock private ObjectProvider<CommandHandlerExtension> coreCommandHandlerProvider;
    @Mock private PluginWrapper pluginWrapper;

    private ExtensionRegistry registry;

    // ----- test doubles -----
    static class TestCmd implements CommandHandlerExtension {
        private final String type;
        private final int priority;
        TestCmd(String type, int priority) { this.type = type; this.priority = priority; }
        @Override public String getCommandType() { return type; }
        @Override public int getPriority() { return priority; }
        @Override public Object execute(CommandHandlerExtension.CommandContext context) { return null; }
    }

    static class TestEvent implements EventListenerExtension {
        private final Set<String> subs;
        private final int order;
        TestEvent(Set<String> subs, int order) { this.subs = subs; this.order = order; }
        @Override public Set<String> getSubscribedEvents() { return subs; }
        @Override public int getOrder() { return order; }
        @Override public void onEvent(EventListenerExtension.EventContext context) {}
    }

    static class TestData implements DataProviderExtension {
        private final String key;
        TestData(String key) { this.key = key; }
        @Override public String getProviderKey() { return key; }
        @Override public List<DataProviderExtension.DataItem> fetchData(DataProviderExtension.DataRequest request) { return Collections.emptyList(); }
    }

    static class TestValidator implements ValidatorExtension {
        private final String key;
        private final int order;
        TestValidator(String key, int order) { this.key = key; this.order = order; }
        @Override public String getValidatorKey() { return key; }
        @Override public int getOrder() { return order; }
        @Override public ValidatorExtension.ValidationResult validate(ValidatorExtension.ValidationContext context) { return null; }
    }

    static class TestMenu implements MenuProviderExtension {
        private final String group;
        TestMenu(String group) { this.group = group; }
        @Override public String getMenuGroup() { return group; }
        @Override public List<MenuItem> getMenuItems(MenuContext context) { return Collections.emptyList(); }
    }

    @BeforeEach
    void setUp() {
        // default empty providers
        lenient().when(coreCommandHandlerProvider.stream()).thenAnswer(inv -> Stream.empty());
        lenient().when(pluginManager.getExtensionsOfType(eq(CommandHandlerExtension.class))).thenReturn(List.of());
        lenient().when(pluginManager.getExtensionsOfType(eq(EventListenerExtension.class))).thenReturn(List.of());
        lenient().when(pluginManager.getExtensionsOfType(eq(DataProviderExtension.class))).thenReturn(List.of());
        lenient().when(pluginManager.getExtensionsOfType(eq(ValidatorExtension.class))).thenReturn(List.of());
        lenient().when(pluginManager.getExtensionsOfType(eq(MenuProviderExtension.class))).thenReturn(List.of());
        lenient().when(pluginManager.getAllPlugins()).thenReturn(List.of());

        registry = new ExtensionRegistry(pluginManager, coreCommandHandlerProvider);
        registry.init();
    }

    @Test
    void getCommandHandler_picks_highest_priority() {
        TestCmd low = new TestCmd("ship", 1);
        TestCmd high = new TestCmd("ship", 10);
        when(pluginManager.getExtensionsOfType(eq(CommandHandlerExtension.class))).thenReturn(List.of(low, high));
        registry.refreshAllCaches();

        Optional<CommandHandlerExtension> r = registry.getCommandHandler("ship");
        assertThat(r).isPresent().get().isSameAs(high);
        assertThat(registry.getCommandHandler("nope")).isEmpty();
    }

    @Test
    void getAllCommandHandlers_merges_plugin_and_core() {
        TestCmd plug = new TestCmd("p", 5);
        TestCmd core = new TestCmd("c", 5);
        when(pluginManager.getExtensionsOfType(eq(CommandHandlerExtension.class))).thenReturn(List.of(plug));
        when(coreCommandHandlerProvider.stream()).thenAnswer(inv -> Stream.of(core));
        registry.refreshAllCaches();

        List<CommandHandlerExtension> all = registry.getAllCommandHandlers();
        assertThat(all).containsExactlyInAnyOrder(plug, core);
        // cached on second call
        assertThat(registry.getAllCommandHandlers()).isSameAs(all);
    }

    @Test
    void getCommandHandlers_by_pluginId_caches() {
        TestCmd c = new TestCmd("x", 0);
        when(pluginManager.getExtensionsOfType(eq(CommandHandlerExtension.class), eq("plug-1"))).thenReturn(List.of(c));
        List<CommandHandlerExtension> first = registry.getCommandHandlers("plug-1");
        assertThat(first).containsExactly(c);
        // Even if underlying call would change, cache returns same list
        when(pluginManager.getExtensionsOfType(eq(CommandHandlerExtension.class), eq("plug-1"))).thenReturn(List.of());
        assertThat(registry.getCommandHandlers("plug-1")).isSameAs(first);
    }

    @Test
    void getEventListeners_filters_by_event_and_sorts_by_order() {
        TestEvent a = new TestEvent(Set.of("user.created"), 10);
        TestEvent b = new TestEvent(Set.of("user.created"), 1);
        TestEvent c = new TestEvent(Set.of("order.created"), 0);
        when(pluginManager.getExtensionsOfType(eq(EventListenerExtension.class))).thenReturn(List.of(a, b, c));
        registry.refreshAllCaches();

        List<EventListenerExtension> r = registry.getEventListeners("user.created");
        assertThat(r).containsExactly(b, a);
    }

    @Test
    void getDataProvider_finds_by_key() {
        TestData d1 = new TestData("inv");
        when(pluginManager.getExtensionsOfType(eq(DataProviderExtension.class))).thenReturn(List.of(d1));
        registry.refreshAllCaches();

        assertThat(registry.getDataProvider("inv")).isPresent().get().isSameAs(d1);
        assertThat(registry.getDataProvider("nope")).isEmpty();
    }

    @Test
    void getValidators_filters_by_key_and_sorts() {
        TestValidator v1 = new TestValidator("k", 5);
        TestValidator v2 = new TestValidator("k", 1);
        TestValidator vOther = new TestValidator("other", 0);
        when(pluginManager.getExtensionsOfType(eq(ValidatorExtension.class))).thenReturn(List.of(v1, v2, vOther));
        registry.refreshAllCaches();

        List<ValidatorExtension> r = registry.getValidators("k");
        assertThat(r).containsExactly(v2, v1);
    }

    @Test
    void getStatistics_includes_per_plugin_data() {
        when(pluginWrapper.getPluginId()).thenReturn("plug-1");
        when(pluginManager.getAllPlugins()).thenReturn(List.of(pluginWrapper));

        Map<String, Object> stats = registry.getStatistics();
        assertThat(stats).containsKey("commandHandlers")
            .containsKey("eventListeners")
            .containsKey("dataProviders")
            .containsKey("validators")
            .containsKey("menuProviders")
            .containsKey("totalExtensions")
            .containsKey("perPlugin");
        @SuppressWarnings("unchecked")
        Map<String, ?> perPlugin = (Map<String, ?>) stats.get("perPlugin");
        assertThat(perPlugin).containsKey("plug-1");
    }

    @Test
    void getRegisteredKeys_aggregates_keys_across_extension_types() {
        TestCmd cmd = new TestCmd("c1", 0);
        TestEvent ev = new TestEvent(Set.of("e1", "e2"), 0);
        TestData data = new TestData("d1");
        TestValidator val = new TestValidator("v1", 0);
        TestMenu menu = new TestMenu("g1");
        when(pluginManager.getExtensionsOfType(eq(CommandHandlerExtension.class))).thenReturn(List.of(cmd));
        when(pluginManager.getExtensionsOfType(eq(EventListenerExtension.class))).thenReturn(List.of(ev));
        when(pluginManager.getExtensionsOfType(eq(DataProviderExtension.class))).thenReturn(List.of(data));
        when(pluginManager.getExtensionsOfType(eq(ValidatorExtension.class))).thenReturn(List.of(val));
        when(pluginManager.getExtensionsOfType(eq(MenuProviderExtension.class))).thenReturn(List.of(menu));
        registry.refreshAllCaches();

        Map<String, List<String>> keys = registry.getRegisteredKeys();
        assertThat(keys.get("commandTypes")).containsExactly("c1");
        assertThat(keys.get("eventPatterns")).containsExactlyInAnyOrder("e1", "e2");
        assertThat(keys.get("dataProviderKeys")).containsExactly("d1");
        assertThat(keys.get("validatorKeys")).containsExactly("v1");
        assertThat(keys.get("menuGroups")).containsExactly("g1");
    }

    @Test
    void refreshPluginCache_clears_per_plugin_and_global_caches() {
        // Pre-fill plugin-specific cache
        when(pluginManager.getExtensionsOfType(eq(CommandHandlerExtension.class), eq("p"))).thenReturn(List.of(new TestCmd("a", 0)));
        registry.getCommandHandlers("p");

        registry.refreshPluginCache("p");
        // After refresh, calling again invokes manager again — change return value
        when(pluginManager.getExtensionsOfType(eq(CommandHandlerExtension.class), eq("p"))).thenReturn(List.of());
        assertThat(registry.getCommandHandlers("p")).isEmpty();
    }

    @Test
    void removePluginFromCache_is_alias_of_refreshPluginCache() {
        registry.removePluginFromCache("anything");
        // No-op if absent — just exercises the path.
        assertThat(registry.getAllCommandHandlers()).isEmpty();
    }
}
