package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.plugin.extension.*;
import lombok.extern.slf4j.Slf4j;
import org.pf4j.PluginWrapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Registry service for plugin extensions.
 * Provides discovery, caching, and lookup of extension points.
 *
 * Features:
 * - Caches extensions by type for fast lookup
 * - Supports plugin-specific extension queries
 * - Automatically refreshes cache on plugin changes
 * - Thread-safe operations
 */
@Slf4j
@Service
public class ExtensionRegistry {

    private final AuraPluginManager pluginManager;
    private final ObjectProvider<CommandHandlerExtension> coreCommandHandlerProvider;
    private final ObjectProvider<ServiceTaskActionExtension> coreServiceTaskActionProvider;

    public ExtensionRegistry(AuraPluginManager pluginManager,
                             ObjectProvider<CommandHandlerExtension> coreCommandHandlerProvider,
                             ObjectProvider<ServiceTaskActionExtension> coreServiceTaskActionProvider) {
        this.pluginManager = pluginManager;
        this.coreCommandHandlerProvider = coreCommandHandlerProvider;
        this.coreServiceTaskActionProvider = coreServiceTaskActionProvider;
    }

    // Extension caches by type
    private final Map<String, List<CommandHandlerExtension>> commandHandlers = new ConcurrentHashMap<>();
    private final Map<String, List<EventListenerExtension>> eventListeners = new ConcurrentHashMap<>();
    private final Map<String, List<DataProviderExtension>> dataProviders = new ConcurrentHashMap<>();
    private final Map<String, List<ValidatorExtension>> validators = new ConcurrentHashMap<>();
    private final Map<String, List<MenuProviderExtension>> menuProviders = new ConcurrentHashMap<>();
    private final Map<String, List<ServiceTaskActionExtension>> serviceTaskActions = new ConcurrentHashMap<>();

    // Global caches
    private volatile List<CommandHandlerExtension> allCommandHandlers;
    private volatile List<EventListenerExtension> allEventListeners;
    private volatile List<DataProviderExtension> allDataProviders;
    private volatile List<ValidatorExtension> allValidators;
    private volatile List<MenuProviderExtension> allMenuProviders;
    private volatile List<ServiceTaskActionExtension> allServiceTaskActions;

    @PostConstruct
    public void init() {
        clearAllCaches();
        log.info("ExtensionRegistry initialized with lazy extension caches");
    }

    // ========== Command Handlers ==========

    /**
     * Get command handler for a specific command type.
     *
     * @param commandType the command type
     * @return optional command handler
     */
    public Optional<CommandHandlerExtension> getCommandHandler(String commandType) {
        return getAllCommandHandlers().stream()
                .filter(h -> h.supports(commandType))
                // A chained secondary never participates in primary selection; the primary is the
                // highest-priority handler that does NOT chain after the primary. Existing handlers
                // all return false, so this is the historical winner-take-all selection unchanged.
                .filter(h -> !h.chainsAfterPrimary())
                .max(Comparator.comparingInt(CommandHandlerExtension::getPriority));
    }

    /**
     * Get the secondary (chained) handlers for a command type — handlers that opt in via
     * {@link CommandHandlerExtension#chainsAfterPrimary()} and run after the primary, in
     * descending priority order. Empty for every command that has no opt-in secondaries (i.e.
     * every command today).
     *
     * @param commandType the command type
     * @return chained secondary handlers, highest priority first (possibly empty)
     */
    public List<CommandHandlerExtension> getSecondaryCommandHandlers(String commandType) {
        return getAllCommandHandlers().stream()
                .filter(h -> h.supports(commandType))
                .filter(CommandHandlerExtension::chainsAfterPrimary)
                .sorted(Comparator.comparingInt(CommandHandlerExtension::getPriority).reversed())
                .toList();
    }

    /**
     * Get all command handlers.
     *
     * @return list of command handlers
     */
    public List<CommandHandlerExtension> getAllCommandHandlers() {
        if (allCommandHandlers == null) {
            // Merge handlers from two sources:
            // 1. PF4J plugin extensions (contributed by dynamically loaded plugins)
            // 2. Core Spring beans (handlers baked into the platform, e.g. bpm:run-rule)
            List<CommandHandlerExtension> pluginHandlers = pluginManager.getExtensionsOfType(CommandHandlerExtension.class);
            List<CommandHandlerExtension> coreHandlers = coreCommandHandlerProvider.stream().toList();
            allCommandHandlers = Stream.concat(pluginHandlers.stream(), coreHandlers.stream()).toList();
        }
        return allCommandHandlers;
    }

    /**
     * Get command handlers from a specific plugin.
     *
     * @param pluginId the plugin ID
     * @return list of command handlers
     */
    public List<CommandHandlerExtension> getCommandHandlers(String pluginId) {
        return commandHandlers.computeIfAbsent(pluginId,
                id -> pluginManager.getExtensionsOfType(CommandHandlerExtension.class, id));
    }

    // ========== ServiceTask Actions ==========

    /**
     * Get the service-task action extension for a specific action type — the highest-priority
     * extension whose {@link ServiceTaskActionExtension#supports(String)} matches.
     *
     * @param actionType the {@code smart:action} value
     * @return optional service-task action extension
     */
    public Optional<ServiceTaskActionExtension> getServiceTaskAction(String actionType) {
        return getAllServiceTaskActions().stream()
                .filter(a -> a.supports(actionType))
                .max(Comparator.comparingInt(ServiceTaskActionExtension::getPriority));
    }

    /**
     * Get all service-task action extensions. Merges two sources (same pattern as command
     * handlers): PF4J plugin extensions and core Spring beans baked into the platform/host.
     *
     * @return list of service-task action extensions
     */
    public List<ServiceTaskActionExtension> getAllServiceTaskActions() {
        if (allServiceTaskActions == null) {
            List<ServiceTaskActionExtension> pluginActions =
                    pluginManager.getExtensionsOfType(ServiceTaskActionExtension.class);
            List<ServiceTaskActionExtension> coreActions = coreServiceTaskActionProvider.stream().toList();
            allServiceTaskActions = Stream.concat(pluginActions.stream(), coreActions.stream()).toList();
        }
        return allServiceTaskActions;
    }

    /**
     * Get service-task action extensions from a specific plugin.
     *
     * @param pluginId the plugin ID
     * @return list of service-task action extensions
     */
    public List<ServiceTaskActionExtension> getServiceTaskActions(String pluginId) {
        return serviceTaskActions.computeIfAbsent(pluginId,
                id -> pluginManager.getExtensionsOfType(ServiceTaskActionExtension.class, id));
    }

    // ========== Event Listeners ==========

    /**
     * Get event listeners interested in a specific event type.
     *
     * @param eventType the event type
     * @return list of interested listeners, sorted by order
     */
    public List<EventListenerExtension> getEventListeners(String eventType) {
        return getAllEventListeners().stream()
                .filter(l -> l.isInterestedIn(eventType))
                .sorted(Comparator.comparingInt(EventListenerExtension::getOrder))
                .collect(Collectors.toList());
    }

    /**
     * Get all event listeners.
     *
     * @return list of event listeners
     */
    public List<EventListenerExtension> getAllEventListeners() {
        if (allEventListeners == null) {
            allEventListeners = pluginManager.getExtensionsOfType(EventListenerExtension.class);
        }
        return allEventListeners;
    }

    /**
     * Get event listeners from a specific plugin.
     *
     * @param pluginId the plugin ID
     * @return list of event listeners
     */
    public List<EventListenerExtension> getEventListeners(String pluginId, boolean unused) {
        return eventListeners.computeIfAbsent(pluginId,
                id -> pluginManager.getExtensionsOfType(EventListenerExtension.class, id));
    }

    // ========== Data Providers ==========

    /**
     * Get data provider by key.
     *
     * @param providerKey the provider key
     * @return optional data provider
     */
    public Optional<DataProviderExtension> getDataProvider(String providerKey) {
        return getAllDataProviders().stream()
                .filter(p -> p.supports(providerKey))
                .findFirst();
    }

    /**
     * Get all data providers.
     *
     * @return list of data providers
     */
    public List<DataProviderExtension> getAllDataProviders() {
        if (allDataProviders == null) {
            allDataProviders = pluginManager.getExtensionsOfType(DataProviderExtension.class);
        }
        return allDataProviders;
    }

    /**
     * Get data providers from a specific plugin.
     *
     * @param pluginId the plugin ID
     * @return list of data providers
     */
    public List<DataProviderExtension> getDataProviders(String pluginId) {
        return dataProviders.computeIfAbsent(pluginId,
                id -> pluginManager.getExtensionsOfType(DataProviderExtension.class, id));
    }

    // ========== Validators ==========

    /**
     * Get validators for a specific key.
     *
     * @param validatorKey the validator key
     * @return list of validators, sorted by order
     */
    public List<ValidatorExtension> getValidators(String validatorKey) {
        return getAllValidators().stream()
                .filter(v -> v.supports(validatorKey))
                .sorted(Comparator.comparingInt(ValidatorExtension::getOrder))
                .collect(Collectors.toList());
    }

    /**
     * Get all validators.
     *
     * @return list of validators
     */
    public List<ValidatorExtension> getAllValidators() {
        if (allValidators == null) {
            allValidators = pluginManager.getExtensionsOfType(ValidatorExtension.class);
        }
        return allValidators;
    }

    /**
     * Get validators from a specific plugin.
     *
     * @param pluginId the plugin ID
     * @return list of validators
     */
    public List<ValidatorExtension> getValidators(String pluginId, boolean unused) {
        return validators.computeIfAbsent(pluginId,
                id -> pluginManager.getExtensionsOfType(ValidatorExtension.class, id));
    }

    // ========== Menu Providers ==========

    /**
     * Get menu items for a specific menu group.
     *
     * @param menuGroup the menu group
     * @param context the menu context
     * @return list of menu items, sorted by order
     */
    public List<MenuProviderExtension.MenuItem> getMenuItems(String menuGroup, MenuProviderExtension.MenuContext context) {
        return getAllMenuProviders().stream()
                .filter(p -> p.getMenuGroup().equals(menuGroup))
                .filter(p -> p.isActive(context))
                .sorted(Comparator.comparingInt(MenuProviderExtension::getOrder))
                .flatMap(p -> p.getMenuItems(context).stream())
                .collect(Collectors.toList());
    }

    /**
     * Get all menu providers.
     *
     * @return list of menu providers
     */
    public List<MenuProviderExtension> getAllMenuProviders() {
        if (allMenuProviders == null) {
            allMenuProviders = pluginManager.getExtensionsOfType(MenuProviderExtension.class);
        }
        return allMenuProviders;
    }

    /**
     * Get menu providers from a specific plugin.
     *
     * @param pluginId the plugin ID
     * @return list of menu providers
     */
    public List<MenuProviderExtension> getMenuProviders(String pluginId) {
        return menuProviders.computeIfAbsent(pluginId,
                id -> pluginManager.getExtensionsOfType(MenuProviderExtension.class, id));
    }

    // ========== Cache Management ==========

    /**
     * Refresh all extension caches.
     */
    public void refreshAllCaches() {
        log.info("Refreshing all extension caches");
        clearAllCaches();
        log.info("Extension caches cleared; extension lists will reload on demand");
    }

    private void clearAllCaches() {
        commandHandlers.clear();
        eventListeners.clear();
        dataProviders.clear();
        validators.clear();
        menuProviders.clear();
        serviceTaskActions.clear();

        allCommandHandlers = null;
        allEventListeners = null;
        allDataProviders = null;
        allValidators = null;
        allMenuProviders = null;
        allServiceTaskActions = null;
    }

    /**
     * Refresh caches for a specific plugin.
     *
     * @param pluginId the plugin ID
     */
    public void refreshPluginCache(String pluginId) {
        log.info("Refreshing extension cache for plugin: {}", pluginId);

        // Clear plugin-specific caches
        commandHandlers.remove(pluginId);
        eventListeners.remove(pluginId);
        dataProviders.remove(pluginId);
        validators.remove(pluginId);
        menuProviders.remove(pluginId);
        serviceTaskActions.remove(pluginId);

        // Clear global caches to force refresh
        allCommandHandlers = null;
        allEventListeners = null;
        allDataProviders = null;
        allValidators = null;
        allMenuProviders = null;
        allServiceTaskActions = null;
    }

    /**
     * Remove plugin from caches.
     *
     * @param pluginId the plugin ID
     */
    public void removePluginFromCache(String pluginId) {
        log.info("Removing plugin from extension cache: {}", pluginId);
        refreshPluginCache(pluginId);
    }

    // ========== Statistics ==========

    /**
     * Get extension statistics.
     *
     * @return map of extension type to count
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("commandHandlers", getAllCommandHandlers().size());
        stats.put("eventListeners", getAllEventListeners().size());
        stats.put("dataProviders", getAllDataProviders().size());
        stats.put("validators", getAllValidators().size());
        stats.put("menuProviders", getAllMenuProviders().size());
        stats.put("totalExtensions",
                getAllCommandHandlers().size() +
                getAllEventListeners().size() +
                getAllDataProviders().size() +
                getAllValidators().size() +
                getAllMenuProviders().size());

        // Per-plugin statistics
        Map<String, Map<String, Integer>> perPluginStats = new HashMap<>();
        for (PluginWrapper wrapper : pluginManager.getAllPlugins()) {
            String pluginId = wrapper.getPluginId();
            Map<String, Integer> pluginStats = new HashMap<>();
            pluginStats.put("commandHandlers", getCommandHandlers(pluginId).size());
            pluginStats.put("eventListeners", getEventListeners(pluginId, true).size());
            pluginStats.put("dataProviders", getDataProviders(pluginId).size());
            pluginStats.put("validators", getValidators(pluginId, true).size());
            pluginStats.put("menuProviders", getMenuProviders(pluginId).size());
            perPluginStats.put(pluginId, pluginStats);
        }
        stats.put("perPlugin", perPluginStats);

        return stats;
    }

    /**
     * Get all registered extension keys.
     *
     * @return map of extension type to list of keys
     */
    public Map<String, List<String>> getRegisteredKeys() {
        Map<String, List<String>> keys = new HashMap<>();

        keys.put("commandTypes", getAllCommandHandlers().stream()
                .map(CommandHandlerExtension::getCommandType)
                .collect(Collectors.toList()));

        keys.put("eventPatterns", getAllEventListeners().stream()
                .flatMap(l -> l.getSubscribedEvents().stream())
                .distinct()
                .collect(Collectors.toList()));

        keys.put("dataProviderKeys", getAllDataProviders().stream()
                .map(DataProviderExtension::getProviderKey)
                .collect(Collectors.toList()));

        keys.put("validatorKeys", getAllValidators().stream()
                .map(ValidatorExtension::getValidatorKey)
                .collect(Collectors.toList()));

        keys.put("menuGroups", getAllMenuProviders().stream()
                .map(MenuProviderExtension::getMenuGroup)
                .distinct()
                .collect(Collectors.toList()));

        return keys;
    }
}
