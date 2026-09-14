package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.plugin.extension.ApplicationModuleExtension;
import com.auraboot.framework.plugin.extension.WorkflowCapability;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Activates product application modules without scanning product packages in core. */
@Slf4j
@Service
public class ApplicationModuleRegistry {

    private final ConfigurableApplicationContext hostContext;
    private final AuraPluginManager pluginManager;
    private final PluginRequestMappingHandlerMapping requestMappings;
    private final WorkflowCapabilityRegistry workflowCapabilities;
    private final Map<String, List<LoadedModule>> modulesByPlugin = new LinkedHashMap<>();

    public ApplicationModuleRegistry(ConfigurableApplicationContext hostContext,
                                     AuraPluginManager pluginManager,
                                     PluginRequestMappingHandlerMapping requestMappings,
                                     WorkflowCapabilityRegistry workflowCapabilities) {
        this.hostContext = hostContext;
        this.pluginManager = pluginManager;
        this.requestMappings = requestMappings;
        this.workflowCapabilities = workflowCapabilities;
    }

    public synchronized void register(String pluginId) {
        if (modulesByPlugin.containsKey(pluginId)) {
            return;
        }
        List<ApplicationModuleExtension> extensions =
                pluginManager.getExtensionsOfType(ApplicationModuleExtension.class, pluginId);
        List<LoadedModule> loaded = new ArrayList<>();
        try {
            for (ApplicationModuleExtension extension : extensions) {
                if (extension.moduleId() == null || extension.moduleId().isBlank()) {
                    throw new IllegalArgumentException("application module id must not be blank");
                }
                AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext();
                child.setParent(hostContext);
                child.setClassLoader(extension.getClass().getClassLoader());
                extension.configurationTypes().forEach(child::register);
                child.refresh();

                List<Object> controllers = child.getBeansWithAnnotation(RestController.class)
                        .values().stream().toList();
                controllers.forEach(requestMappings::registerController);
                child.getBeansOfType(WorkflowCapability.class).values()
                        .forEach(provider -> workflowCapabilities.register(pluginId, provider));
                child.publishEvent(new ApplicationModuleReadyEvent(extension.moduleId()));
                loaded.add(new LoadedModule(extension.moduleId(), child, controllers));
                log.info("Registered plugin application module {} ({})", extension.moduleId(), pluginId);
            }
            modulesByPlugin.put(pluginId, List.copyOf(loaded));
        } catch (RuntimeException error) {
            workflowCapabilities.unregister(pluginId);
            closeReverse(loaded);
            throw error;
        }
    }

    public synchronized void unregister(String pluginId) {
        workflowCapabilities.unregister(pluginId);
        closeReverse(modulesByPlugin.remove(pluginId));
    }

    public synchronized List<String> registeredModuleIds(String pluginId) {
        return modulesByPlugin.getOrDefault(pluginId, List.of()).stream()
                .map(LoadedModule::moduleId).toList();
    }

    private void closeReverse(List<LoadedModule> loaded) {
        if (loaded == null) return;
        for (int index = loaded.size() - 1; index >= 0; index--) {
            LoadedModule module = loaded.get(index);
            module.controllers().forEach(requestMappings::unregisterController);
            module.context().close();
        }
    }

    public record ApplicationModuleReadyEvent(String moduleId) {}
    private record LoadedModule(String moduleId, AnnotationConfigApplicationContext context,
                                List<Object> controllers) {}
}
