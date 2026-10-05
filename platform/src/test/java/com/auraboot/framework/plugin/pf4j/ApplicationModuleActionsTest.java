package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.plugin.extension.ApplicationModuleExtension;
import com.auraboot.framework.plugin.extension.ServiceTaskActionExtension;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.List;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ApplicationModuleActionsTest {
    @Configuration
    static class ProductModule {
        @Bean ServiceTaskActionExtension productAction() {
            return new ExtensionRegistryTest.TestAction("SEND_IM", -100);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void childSpringActionIsVisibleOnlyWhileModuleIsLoaded() {
        try (var host = new AnnotationConfigApplicationContext()) {
            host.refresh();
            var manager = mock(AuraPluginManager.class);
            ObjectProvider<ServiceTaskActionExtension> actions = mock(ObjectProvider.class);
            when(actions.stream()).thenAnswer(ignored -> Stream.empty());
            when(manager.getExtensionsOfType(ServiceTaskActionExtension.class)).thenReturn(List.of());
            var registry = new ExtensionRegistry(manager, mock(ObjectProvider.class), actions);
            var module = new ApplicationModuleExtension() {
                public String moduleId() { return "bpm"; }
                public List<Class<?>> configurationTypes() { return List.of(ProductModule.class); }
            };
            when(manager.getExtensionsOfType(ApplicationModuleExtension.class, "bpm"))
                    .thenReturn(List.of(module));
            var modules = new ApplicationModuleRegistry(host, manager,
                    mock(PluginRequestMappingHandlerMapping.class), mock(WorkflowCapabilityRegistry.class),
                    mock(DecisionUsageSourceRegistry.class), registry);
            assertThat(registry.getServiceTaskAction("SEND_IM")).isEmpty();
            modules.register("bpm");
            assertThat(host.getBeansOfType(ServiceTaskActionExtension.class)).isEmpty();
            assertThat(registry.getServiceTaskAction("SEND_IM")).isPresent();
            assertThat(registry.getServiceTaskActions("bpm")).hasSize(1);
            modules.unregister("bpm");
            assertThat(registry.getServiceTaskAction("SEND_IM")).isEmpty();
            assertThat(registry.getServiceTaskActions("bpm")).isEmpty();
            assertThat(modules.childContexts()).isEmpty();
        }
    }
}
