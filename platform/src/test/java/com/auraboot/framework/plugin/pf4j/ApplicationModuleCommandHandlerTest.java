package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.plugin.extension.ApplicationModuleExtension;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.ServiceTaskActionExtension;
import com.auraboot.framework.meta.registry.CommandHandlerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.GenericApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Exercises real child-context discovery and unload without promoting product beans to Core. */
class ApplicationModuleCommandHandlerTest {
    @Configuration(proxyBeanMethods = false)
    static class ProductConfiguration {
        @Bean TestHandler productHandler() { return new TestHandler(); }
    }

    static class TestHandler implements CommandHandlerExtension, DisposableBean {
        boolean closed;
        @Override public String getCommandType() { return "product:delete-owned-record"; }
        @Override public Object execute(CommandContext context) { return "owned-service-result"; }
        @Override public void destroy() { closed = true; }
    }

    @Test
    void lateModuleRegistrationSynchronizesImportMetadataAndUnloadRemovesOnlyLiveOwnedEntries() {
        withBridgedHost(host -> {
            var bridge = host.getBean(PluginExtensionRegistryBridge.class);
            var imports = host.getBean(CommandHandlerRegistry.class);
            var modules = host.getBean(ApplicationModuleRegistry.class);
            bridge.bridge();
            assertThat(imports.isRegistered("product:delete-owned-record")).isFalse();
            modules.register("product");
            assertThat(imports.isRegistered("product:delete-owned-record")).isTrue();
            modules.register("product");
            assertThat(imports.getAll()).hasSize(1);
            modules.unregister("product");
            assertThat(imports.isRegistered("product:delete-owned-record")).isFalse();
            modules.register("product");
            assertThat(imports.isRegistered("product:delete-owned-record")).isTrue();
            modules.unregister("product");
        });
    }

    @Test
    void moduleSynchronizationPreservesBuiltinsAndLaterMetadataOwners() {
        withBridgedHost(host -> {
            var imports = host.getBean(CommandHandlerRegistry.class);
            var modules = host.getBean(ApplicationModuleRegistry.class);
            var builtin = new CommandHandlerRegistry.HandlerMeta(
                    "product:delete-owned-record", "builtin", "keep", null, null);
            imports.register(builtin);
            modules.register("product");
            assertThat(imports.getAll()).containsExactly(builtin);
            modules.unregister("product");
            assertThat(imports.getAll()).containsExactly(builtin);
        });
        withBridgedHost(host -> {
            var imports = host.getBean(CommandHandlerRegistry.class);
            var modules = host.getBean(ApplicationModuleRegistry.class);
            modules.register("product");
            var replacement = new CommandHandlerRegistry.HandlerMeta(
                    "product:delete-owned-record", "new-owner", "keep", null, null);
            imports.register(replacement);
            modules.unregister("product");
            assertThat(imports.getAll()).containsExactly(replacement);
        });
    }

    private void withBridgedHost(java.util.function.Consumer<org.springframework.context.annotation.AnnotationConfigApplicationContext> test) {
        AuraPluginManager manager = mock(AuraPluginManager.class);
        when(manager.getExtensionsOfType(CommandHandlerExtension.class)).thenReturn(List.of());
        ApplicationModuleExtension product = new ApplicationModuleExtension() {
            @Override public String moduleId() { return "product.runtime"; }
            @Override public List<Class<?>> configurationTypes() { return List.of(ProductConfiguration.class); }
        };
        when(manager.getExtensionsOfType(ApplicationModuleExtension.class, "product")).thenReturn(List.of(product));
        try (var host = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            host.registerBean(CommandHandlerRegistry.class);
            host.registerBean(ExtensionRegistry.class, () -> new ExtensionRegistry(manager,
                    host.getBeanProvider(CommandHandlerExtension.class),
                    host.getBeanProvider(ServiceTaskActionExtension.class),
                    host.getBeanProvider(ApplicationModuleRegistry.class)));
            host.registerBean(ApplicationModuleRegistry.class, () -> new ApplicationModuleRegistry(host, manager,
                    mock(PluginRequestMappingHandlerMapping.class), mock(WorkflowCapabilityRegistry.class),
                    mock(DecisionUsageSourceRegistry.class), host.getBean(ExtensionRegistry.class)));
            host.registerBean(PluginExtensionRegistryBridge.class);
            host.refresh();
            test.accept(host);
            host.getBean(ExtensionRegistry.class).detachLifecycleListener();
        }
    }

    @Test
    void discoversLoadedModuleAfterCacheWarmupAndDropsClosedBeansOnUnload() {
        AuraPluginManager manager = mock(AuraPluginManager.class);
        when(manager.getExtensionsOfType(CommandHandlerExtension.class)).thenReturn(List.of());
        when(manager.getExtensionsOfType(CommandHandlerExtension.class, "product")).thenReturn(List.of());
        ApplicationModuleExtension product = new ApplicationModuleExtension() {
            @Override public String moduleId() { return "product.runtime"; }
            @Override public List<Class<?>> configurationTypes() { return List.of(ProductConfiguration.class); }
        };
        when(manager.getExtensionsOfType(ApplicationModuleExtension.class, "product"))
                .thenReturn(List.of(product));
        try (GenericApplicationContext host = new GenericApplicationContext()) {
            host.refresh();
            ExtensionRegistry commands = new ExtensionRegistry(manager,
                    host.getBeanProvider(CommandHandlerExtension.class),
                    host.getBeanProvider(ServiceTaskActionExtension.class),
                    host.getBeanProvider(ApplicationModuleRegistry.class));
            ApplicationModuleRegistry modules = new ApplicationModuleRegistry(host, manager,
                    mock(PluginRequestMappingHandlerMapping.class), mock(WorkflowCapabilityRegistry.class),
                    mock(DecisionUsageSourceRegistry.class), commands);
            host.getBeanFactory().registerSingleton("applicationModules", modules);
            assertThat(commands.getCommandHandler("product:delete-owned-record")).isEmpty();
            modules.register("product");
            TestHandler first = (TestHandler) commands.getCommandHandler("product:delete-owned-record").orElseThrow();
            assertThat(first.execute(null)).isEqualTo("owned-service-result");
            assertThat(commands.getCommandHandlers("product")).containsExactly(first);
            assertThat(modules.commandHandlers("another-product")).isEmpty();
            assertThat(host.getBeansOfType(CommandHandlerExtension.class)).isEmpty();

            modules.unregister("product");
            assertThat(first.closed).isTrue();
            assertThat(commands.getCommandHandler("product:delete-owned-record")).isEmpty();
            assertThat(commands.getCommandHandlers("product")).isEmpty();

            modules.register("product");
            TestHandler replacement = (TestHandler) commands.getCommandHandler("product:delete-owned-record").orElseThrow();
            assertThat(replacement).isNotSameAs(first);
            assertThat(replacement.closed).isFalse();
            modules.unregister("product");
            commands.detachLifecycleListener();
        }
    }
}
