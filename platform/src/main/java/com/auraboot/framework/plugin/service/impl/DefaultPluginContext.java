package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.plugin.api.PluginContext;
import com.auraboot.framework.plugin.api.PluginDisableContext;
import com.auraboot.framework.plugin.api.PluginEnableContext;
import com.auraboot.framework.plugin.api.PluginInstallContext;
import com.auraboot.framework.plugin.api.PluginUninstallContext;
import com.auraboot.framework.plugin.dto.PluginManifest;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Default implementation of plugin context interfaces.
 */
@Slf4j
public class DefaultPluginContext implements PluginInstallContext, PluginEnableContext,
        PluginDisableContext, PluginUninstallContext {

    private final Long tenantId;
    private final String pluginId;
    private final String namespace;
    private final String version;
    private final Map<String, Object> settings;
    private final PluginManifest manifest;

    // Install context
    private final boolean freshInstall;
    private final String previousVersion;
    private final List<String> registeredModels = new ArrayList<>();
    private final List<String> registeredCommands = new ArrayList<>();

    // Enable context
    private final boolean wasEnabled;

    // Disable context
    private final boolean preUninstall;

    // Uninstall context
    private final boolean removeData;
    private final List<String> modelsToRemove = new ArrayList<>();
    private final List<String> commandsToRemove = new ArrayList<>();

    private DefaultPluginContext(Builder builder) {
        this.tenantId = builder.tenantId;
        this.pluginId = builder.pluginId;
        this.namespace = builder.namespace;
        this.version = builder.version;
        this.settings = builder.settings != null ? new HashMap<>(builder.settings) : new HashMap<>();
        this.manifest = builder.manifest;
        this.freshInstall = builder.freshInstall;
        this.previousVersion = builder.previousVersion;
        this.wasEnabled = builder.wasEnabled;
        this.preUninstall = builder.preUninstall;
        this.removeData = builder.removeData;
    }

    // ========== PluginContext ==========

    @Override
    public Long getTenantId() {
        return tenantId;
    }

    @Override
    public String getPluginId() {
        return pluginId;
    }

    @Override
    public String getNamespace() {
        return namespace;
    }

    @Override
    public String getVersion() {
        return version;
    }

    @Override
    public Map<String, Object> getSettings() {
        return new HashMap<>(settings);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getSetting(String key) {
        return (T) settings.get(key);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getSetting(String key, T defaultValue) {
        Object value = settings.get(key);
        return value != null ? (T) value : defaultValue;
    }

    // ========== PluginInstallContext ==========

    @Override
    public PluginManifest getManifest() {
        return manifest;
    }

    @Override
    public boolean isFreshInstall() {
        return freshInstall;
    }

    @Override
    public String getPreviousVersion() {
        return previousVersion;
    }

    @Override
    public void registerModel(String modelCode) {
        registeredModels.add(modelCode);
        log.debug("Plugin {} registered model: {}", pluginId, modelCode);
    }

    @Override
    public void registerCommand(String commandCode) {
        registeredCommands.add(commandCode);
        log.debug("Plugin {} registered command: {}", pluginId, commandCode);
    }

    @Override
    public void reportProgress(int percentage, String message) {
        log.info("Plugin {} install progress: {}% - {}", pluginId, percentage, message);
    }

    public List<String> getRegisteredModels() {
        return new ArrayList<>(registeredModels);
    }

    public List<String> getRegisteredCommands() {
        return new ArrayList<>(registeredCommands);
    }

    // ========== PluginEnableContext ==========

    @Override
    public boolean wasEnabled() {
        return wasEnabled;
    }

    @Override
    public void registerScheduledTask(String taskId, String cronExpression, String taskClass) {
        log.debug("Plugin {} registered scheduled task: {} ({})", pluginId, taskId, cronExpression);
        // TODO: Integrate with ScheduledTaskService
    }

    @Override
    public void registerEventListener(String eventType, String listenerClass) {
        log.debug("Plugin {} registered event listener: {} -> {}", pluginId, eventType, listenerClass);
        // TODO: Integrate with event system
    }

    // ========== PluginDisableContext ==========

    @Override
    public boolean isPreUninstall() {
        return preUninstall;
    }

    @Override
    public void unregisterScheduledTask(String taskId) {
        log.debug("Plugin {} unregistered scheduled task: {}", pluginId, taskId);
        // TODO: Integrate with ScheduledTaskService
    }

    @Override
    public void unregisterAllScheduledTasks() {
        log.debug("Plugin {} unregistered all scheduled tasks", pluginId);
        // TODO: Integrate with ScheduledTaskService
    }

    @Override
    public void unregisterEventListener(String eventType, String listenerClass) {
        log.debug("Plugin {} unregistered event listener: {} -> {}", pluginId, eventType, listenerClass);
        // TODO: Integrate with event system
    }

    @Override
    public void unregisterAllEventListeners() {
        log.debug("Plugin {} unregistered all event listeners", pluginId);
        // TODO: Integrate with event system
    }

    // ========== PluginUninstallContext ==========

    @Override
    public boolean shouldRemoveData() {
        return removeData;
    }

    @Override
    public void markModelForRemoval(String modelCode) {
        modelsToRemove.add(modelCode);
        log.debug("Plugin {} marked model for removal: {}", pluginId, modelCode);
    }

    @Override
    public void markCommandForRemoval(String commandCode) {
        commandsToRemove.add(commandCode);
        log.debug("Plugin {} marked command for removal: {}", pluginId, commandCode);
    }

    public List<String> getModelsToRemove() {
        return new ArrayList<>(modelsToRemove);
    }

    public List<String> getCommandsToRemove() {
        return new ArrayList<>(commandsToRemove);
    }

    // ========== Common ==========

    @Override
    public void log(String message) {
        log.info("Plugin {}: {}", pluginId, message);
    }

    // ========== Builder ==========

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Long tenantId;
        private String pluginId;
        private String namespace;
        private String version;
        private Map<String, Object> settings;
        private PluginManifest manifest;
        private boolean freshInstall = true;
        private String previousVersion;
        private boolean wasEnabled = false;
        private boolean preUninstall = false;
        private boolean removeData = false;

        public Builder tenantId(Long tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder pluginId(String pluginId) {
            this.pluginId = pluginId;
            return this;
        }

        public Builder namespace(String namespace) {
            this.namespace = namespace;
            return this;
        }

        public Builder version(String version) {
            this.version = version;
            return this;
        }

        public Builder settings(Map<String, Object> settings) {
            this.settings = settings;
            return this;
        }

        public Builder manifest(PluginManifest manifest) {
            this.manifest = manifest;
            return this;
        }

        public Builder freshInstall(boolean freshInstall) {
            this.freshInstall = freshInstall;
            return this;
        }

        public Builder previousVersion(String previousVersion) {
            this.previousVersion = previousVersion;
            return this;
        }

        public Builder wasEnabled(boolean wasEnabled) {
            this.wasEnabled = wasEnabled;
            return this;
        }

        public Builder preUninstall(boolean preUninstall) {
            this.preUninstall = preUninstall;
            return this;
        }

        public Builder removeData(boolean removeData) {
            this.removeData = removeData;
            return this;
        }

        public DefaultPluginContext build() {
            return new DefaultPluginContext(this);
        }
    }
}
