package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.plugin.extension.BackgroundComponentExtension;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;

import java.beans.Introspector;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registers PF4J {@link BackgroundComponentExtension} instances contributed by
 * a plugin as singletons in the host's Spring {@code ApplicationContext}.
 *
 * <p>Lifecycle:
 * <pre>
 *   plugin enabled  -> register(pluginId)
 *                       -> for each extension instance:
 *                            autowireBean   (resolves @Autowired)
 *                            initializeBean (runs BeanPostProcessors:
 *                                              @PostConstruct,
 *                                              @KafkaListener,
 *                                              @Scheduled,
 *                                              @EventListener)
 *                            registerSingleton
 *   plugin disabled -> unregister(pluginId)
 *                       -> destroySingleton (runs @PreDestroy)
 * </pre>
 *
 * <p>Bean names default to the simple class name with the first letter
 * lower-cased; plugins can override via {@link BackgroundComponentExtension#beanName()}.
 * Names are namespaced by {@code <pluginId>__<beanName>} in the registry's
 * internal map but registered under {@code beanName} in the bean factory; if
 * two plugins contribute the same bean name the second registration throws.
 *
 * @since 2.5.0
 */
@Slf4j
@Service
public class BackgroundComponentRegistry {

    private final ConfigurableApplicationContext applicationContext;
    private final AuraPluginManager pluginManager;

    /** pluginId -> list of bean names registered for that plugin. */
    private final Map<String, List<String>> registeredBeans = new ConcurrentHashMap<>();

    public BackgroundComponentRegistry(ConfigurableApplicationContext applicationContext,
                                       AuraPluginManager pluginManager) {
        this.applicationContext = applicationContext;
        this.pluginManager = pluginManager;
    }

    /**
     * Register all {@link BackgroundComponentExtension} instances contributed by
     * the named plugin. Idempotent: a second call for the same {@code pluginId}
     * with no new extensions is a no-op; if new extensions were added, only those
     * are registered.
     *
     * @param pluginId the PF4J plugin id (matches {@code Plugin-Id} manifest attr)
     */
    public void register(String pluginId) {
        List<BackgroundComponentExtension> components =
                pluginManager.getExtensionsOfType(BackgroundComponentExtension.class, pluginId);
        if (components.isEmpty()) {
            return;
        }

        ConfigurableListableBeanFactory beanFactory = applicationContext.getBeanFactory();
        List<String> newlyRegistered = new ArrayList<>();
        List<String> existing = registeredBeans.getOrDefault(pluginId, Collections.emptyList());

        for (BackgroundComponentExtension component : components) {
            String name = resolveBeanName(component);
            if (existing.contains(name) || beanFactory.containsSingleton(name)) {
                log.debug("Background component already registered, skipping: {} ({})", name, pluginId);
                continue;
            }
            try {
                beanFactory.autowireBean(component);
                Object initialized = beanFactory.initializeBean(component, name);
                beanFactory.registerSingleton(name, initialized);
                newlyRegistered.add(name);
                log.info("Registered plugin background component: {} ({})", name, pluginId);
            } catch (RuntimeException e) {
                log.error("Failed to register plugin background component: {} ({})", name, pluginId, e);
                // Roll back partial registration so caller can fail the enable cleanly.
                for (String already : newlyRegistered) {
                    destroyBean(beanFactory, already);
                }
                throw e;
            }
        }

        if (!newlyRegistered.isEmpty()) {
            List<String> merged = new ArrayList<>(existing);
            merged.addAll(newlyRegistered);
            registeredBeans.put(pluginId, merged);
        }
    }

    /**
     * Unregister all background components for the named plugin. Triggers
     * {@code @PreDestroy} on each before removing from the singleton cache.
     *
     * <p>No-op if the plugin had no background components.
     *
     * @param pluginId the PF4J plugin id
     */
    public void unregister(String pluginId) {
        List<String> names = registeredBeans.remove(pluginId);
        if (names == null || names.isEmpty()) {
            return;
        }
        ConfigurableListableBeanFactory beanFactory = applicationContext.getBeanFactory();
        for (String name : names) {
            destroyBean(beanFactory, name);
        }
        log.info("Unregistered {} background component(s) for plugin: {}", names.size(), pluginId);
    }

    /**
     * Visible for inspection / testing.
     *
     * @param pluginId the plugin id
     * @return immutable list of bean names registered for this plugin (empty if none)
     */
    public List<String> registeredBeanNames(String pluginId) {
        return Collections.unmodifiableList(
                registeredBeans.getOrDefault(pluginId, Collections.emptyList()));
    }

    private String resolveBeanName(BackgroundComponentExtension component) {
        String explicit = component.beanName();
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        return Introspector.decapitalize(component.getClass().getSimpleName());
    }

    private void destroyBean(ConfigurableListableBeanFactory beanFactory, String name) {
        if (!(beanFactory instanceof DefaultListableBeanFactory dlbf) || !dlbf.containsSingleton(name)) {
            return;
        }
        Object bean = dlbf.getSingleton(name);
        try {
            // We bypass Spring's normal singleton creation by calling
            // registerSingleton on an already-initialized instance from the
            // plugin classloader; that path does not enroll the bean in
            // DefaultSingletonBeanRegistry.disposableBeans, so destroySingleton
            // alone would not fire @PreDestroy. Invoke the destruction-aware
            // BeanPostProcessors manually before removing the singleton.
            for (BeanPostProcessor bpp : dlbf.getBeanPostProcessors()) {
                if (bpp instanceof DestructionAwareBeanPostProcessor dabpp
                        && dabpp.requiresDestruction(bean)) {
                    try {
                        dabpp.postProcessBeforeDestruction(bean, name);
                    } catch (RuntimeException e) {
                        log.warn("DestructionAwareBeanPostProcessor {} failed for {}", bpp, name, e);
                    }
                }
            }
            dlbf.destroySingleton(name);
            log.info("Destroyed plugin background component: {}", name);
        } catch (RuntimeException e) {
            // Continue removing other beans even if one fails to destroy.
            log.warn("Failed to destroy plugin background component: {}", name, e);
        }
    }
}
