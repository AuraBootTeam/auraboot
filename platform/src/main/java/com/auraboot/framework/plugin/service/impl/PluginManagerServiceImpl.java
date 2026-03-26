package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.plugin.api.Plugin;
import com.auraboot.framework.plugin.dto.PluginInfo;
import com.auraboot.framework.plugin.dto.PluginManifest;
import com.auraboot.framework.plugin.dto.PluginOperationResult;
import com.auraboot.framework.plugin.dto.PluginStatus;
import com.auraboot.framework.plugin.entity.PluginRecord;
import com.auraboot.framework.plugin.exception.PluginLifecycleException;
import com.auraboot.framework.plugin.exception.PluginNotFoundException;
import com.auraboot.framework.plugin.mapper.PluginRecordMapper;
import com.auraboot.framework.plugin.pf4j.AuraPlugin;
import com.auraboot.framework.plugin.pf4j.AuraPluginManager;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import com.auraboot.framework.plugin.service.PluginManagerService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.pf4j.PluginWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Plugin lifecycle management service implementation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PluginManagerServiceImpl implements PluginManagerService {

    private final PluginRecordMapper pluginRecordMapper;
    private final AuraPluginManager auraPluginManager;
    private final ExtensionRegistry extensionRegistry;

    /**
     * In-memory cache of Plugin instances.
     * Key: pluginId
     */
    private final Map<String, Plugin> pluginInstances = new ConcurrentHashMap<>();

    // ========== PluginManagerService ==========

    @Override
    @Transactional
    public PluginOperationResult install(PluginManifest manifest) {
        Long tenantId = MetaContext.getCurrentTenantId();
        log.info("Installing plugin: {} (namespace: {}) for tenant {}",
                manifest.getPluginId(), manifest.getNamespace(), tenantId);

        // Validate manifest
        if (!manifest.isValid()) {
            return PluginOperationResult.failure(
                    PluginOperationResult.OperationType.INSTALL,
                    manifest.getPluginId(),
                    manifest.getNamespace(),
                    "Invalid manifest",
                    "Manifest must have pluginId, namespace, and version"
            );
        }

        // Check if already installed
        PluginRecord existing = pluginRecordMapper.findByTenantAndNamespace(manifest.getNamespace());
        if (existing != null) {
            return PluginOperationResult.failure(
                    PluginOperationResult.OperationType.INSTALL,
                    manifest.getPluginId(),
                    manifest.getNamespace(),
                    "Plugin already installed",
                    "Namespace '" + manifest.getNamespace() + "' is already in use"
            );
        }

        try {
            // Create plugin record
            PluginRecord record = PluginRecord.builder()
                    .pid(UniqueIdGenerator.generate())
                    .tenantId(tenantId)
                    .pluginId(manifest.getPluginId())
                    .namespace(manifest.getNamespace())
                    .version(manifest.getVersion())
                    .displayName(manifest.getDisplayName())
                    .description(manifest.getDescription())
                    .author(manifest.getAuthor())
                    .status(PluginStatus.INSTALLED.code())
                    .manifest(manifest)
                    .settings(manifest.getDefaultConfig())
                    .installedAt(Instant.now())
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .deletedFlag(false)
                    .build();

            pluginRecordMapper.insert(record);

            // Call plugin onInstall if instance registered
            Plugin plugin = pluginInstances.get(manifest.getPluginId());
            if (plugin != null) {
                DefaultPluginContext context = DefaultPluginContext.builder()
                        .tenantId(tenantId)
                        .pluginId(manifest.getPluginId())
                        .namespace(manifest.getNamespace())
                        .version(manifest.getVersion())
                        .settings(manifest.getDefaultConfig())
                        .manifest(manifest)
                        .freshInstall(true)
                        .build();

                plugin.onInstall(context);
            }

            log.info("Plugin installed successfully: {} (PID: {})", manifest.getPluginId(), record.getPid());
            return PluginOperationResult.installSuccess(record.getPid(), manifest.getPluginId(), manifest.getNamespace());

        } catch (Exception e) {
            log.error("Failed to install plugin: {}", manifest.getPluginId(), e);
            return PluginOperationResult.failure(
                    PluginOperationResult.OperationType.INSTALL,
                    manifest.getPluginId(),
                    manifest.getNamespace(),
                    "Installation failed",
                    e.getMessage()
            );
        }
    }

    @Override
    @Transactional
    public PluginOperationResult enable(String pluginId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        log.info("Enabling plugin: {} for tenant {}", pluginId, tenantId);

        PluginRecord record = pluginRecordMapper.findByTenantAndPluginId(pluginId);
        if (record == null) {
            throw new PluginNotFoundException(pluginId);
        }

        PluginStatus currentStatus = record.getStatusEnum();
        if (currentStatus == PluginStatus.ENABLED) {
            // Already enabled, return success
            return PluginOperationResult.enableSuccess(record.getPid(), pluginId, record.getNamespace());
        }

        if (!currentStatus.canTransitionTo(PluginStatus.ENABLED)) {
            throw PluginLifecycleException.invalidTransition(pluginId, record.getNamespace(), currentStatus, PluginStatus.ENABLED);
        }

        try {
            // Call plugin onEnable if instance registered
            Plugin plugin = pluginInstances.get(pluginId);
            if (plugin != null) {
                DefaultPluginContext context = DefaultPluginContext.builder()
                        .tenantId(tenantId)
                        .pluginId(pluginId)
                        .namespace(record.getNamespace())
                        .version(record.getVersion())
                        .settings(record.getSettings())
                        .manifest(record.getManifest())
                        .wasEnabled(currentStatus == PluginStatus.DISABLED)
                        .build();

                plugin.onEnable(context);
            }

            // Update status
            pluginRecordMapper.markAsEnabled(record.getPid());

            log.info("Plugin enabled successfully: {}", pluginId);
            PluginOperationResult result = PluginOperationResult.enableSuccess(record.getPid(), pluginId, record.getNamespace());
            result.setPreviousStatus(currentStatus);
            return result;

        } catch (Exception e) {
            log.error("Failed to enable plugin: {}", pluginId, e);
            // Mark as failed
            pluginRecordMapper.updateStatus(record.getPid(), PluginStatus.FAILED.code());
            throw PluginLifecycleException.enableFailed(pluginId, record.getNamespace(), currentStatus, e);
        }
    }

    @Override
    @Transactional
    public PluginOperationResult disable(String pluginId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        log.info("Disabling plugin: {} for tenant {}", pluginId, tenantId);

        PluginRecord record = pluginRecordMapper.findByTenantAndPluginId(pluginId);
        if (record == null) {
            throw new PluginNotFoundException(pluginId);
        }

        PluginStatus currentStatus = record.getStatusEnum();
        if (currentStatus == PluginStatus.DISABLED) {
            // Already disabled, return success
            return PluginOperationResult.disableSuccess(record.getPid(), pluginId, record.getNamespace());
        }

        if (!currentStatus.canTransitionTo(PluginStatus.DISABLED)) {
            throw PluginLifecycleException.invalidTransition(pluginId, record.getNamespace(), currentStatus, PluginStatus.DISABLED);
        }

        try {
            // Call plugin onDisable if instance registered
            Plugin plugin = pluginInstances.get(pluginId);
            if (plugin != null) {
                DefaultPluginContext context = DefaultPluginContext.builder()
                        .tenantId(tenantId)
                        .pluginId(pluginId)
                        .namespace(record.getNamespace())
                        .version(record.getVersion())
                        .settings(record.getSettings())
                        .manifest(record.getManifest())
                        .preUninstall(false)
                        .build();

                plugin.onDisable(context);
            }

            // Update status
            pluginRecordMapper.markAsDisabled(record.getPid());

            log.info("Plugin disabled successfully: {}", pluginId);
            PluginOperationResult result = PluginOperationResult.disableSuccess(record.getPid(), pluginId, record.getNamespace());
            result.setPreviousStatus(currentStatus);
            return result;

        } catch (Exception e) {
            log.error("Failed to disable plugin: {}", pluginId, e);
            // Mark as failed
            pluginRecordMapper.updateStatus(record.getPid(), PluginStatus.FAILED.code());
            throw PluginLifecycleException.disableFailed(pluginId, record.getNamespace(), currentStatus, e);
        }
    }

    @Override
    @Transactional
    public PluginOperationResult uninstall(String pluginId, boolean removeData) {
        Long tenantId = MetaContext.getCurrentTenantId();
        log.info("Uninstalling plugin: {} for tenant {} (removeData: {})", pluginId, tenantId, removeData);

        PluginRecord record = pluginRecordMapper.findByTenantAndPluginId(pluginId);
        if (record == null) {
            throw new PluginNotFoundException(pluginId);
        }

        PluginStatus currentStatus = record.getStatusEnum();
        if (!currentStatus.canUninstall()) {
            throw PluginLifecycleException.cannotUninstallEnabled(pluginId, record.getNamespace());
        }

        try {
            // Call plugin onUninstall if instance registered
            Plugin plugin = pluginInstances.get(pluginId);
            if (plugin != null) {
                DefaultPluginContext context = DefaultPluginContext.builder()
                        .tenantId(tenantId)
                        .pluginId(pluginId)
                        .namespace(record.getNamespace())
                        .version(record.getVersion())
                        .settings(record.getSettings())
                        .manifest(record.getManifest())
                        .removeData(removeData)
                        .build();

                plugin.onUninstall(context);
            }

            // Soft delete the record
            pluginRecordMapper.softDelete(record.getPid());

            // Unregister plugin instance
            pluginInstances.remove(pluginId);

            log.info("Plugin uninstalled successfully: {}", pluginId);
            return PluginOperationResult.uninstallSuccess(record.getPid(), pluginId, record.getNamespace());

        } catch (Exception e) {
            log.error("Failed to uninstall plugin: {}", pluginId, e);
            throw PluginLifecycleException.uninstallFailed(pluginId, record.getNamespace(), currentStatus, e);
        }
    }

    @Override
    @Transactional
    public PluginOperationResult updateSettings(String pluginId, Map<String, Object> settings) {
        Long tenantId = MetaContext.getCurrentTenantId();
        log.info("Updating settings for plugin: {} in tenant {}", pluginId, tenantId);

        PluginRecord record = pluginRecordMapper.findByTenantAndPluginId(pluginId);
        if (record == null) {
            throw new PluginNotFoundException(pluginId);
        }

        record.setSettings(settings);
        record.setUpdatedAt(Instant.now());
        pluginRecordMapper.updateById(record);

        log.info("Plugin settings updated successfully: {}", pluginId);
        return PluginOperationResult.builder()
                .success(true)
                .pluginPid(record.getPid())
                .pluginId(pluginId)
                .namespace(record.getNamespace())
                .operation(PluginOperationResult.OperationType.UPDATE_SETTINGS)
                .currentStatus(record.getStatusEnum())
                .build();
    }

    // ========== PluginRegistry ==========

    @Override
    public Optional<PluginInfo> getPluginByPid(String pid) {
        PluginRecord record = pluginRecordMapper.findByPid(pid);
        return Optional.ofNullable(record).map(this::toPluginInfo);
    }

    @Override
    public Optional<PluginInfo> getPluginByNamespace(String namespace) {
        PluginRecord record = pluginRecordMapper.findByTenantAndNamespace(namespace);
        return Optional.ofNullable(record).map(this::toPluginInfo);
    }

    @Override
    public Optional<PluginInfo> getPluginByPluginId(String pluginId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        PluginRecord record = findByPluginIdWithTypeHandler(tenantId, pluginId);
        return Optional.ofNullable(record).map(this::toPluginInfo);
    }

    /**
     * Find plugin by pluginId using MyBatis-Plus selectOne to ensure TypeHandler is applied.
     */
    private PluginRecord findByPluginIdWithTypeHandler(Long tenantId, String pluginId) {
        LambdaQueryWrapper<PluginRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PluginRecord::getTenantId, tenantId)
               .eq(PluginRecord::getPluginId, pluginId)
               .eq(PluginRecord::getDeletedFlag, false);
        return pluginRecordMapper.selectOne(wrapper);
    }

    @Override
    public List<PluginInfo> getAllPlugins() {
        return pluginRecordMapper.findByTenant().stream()
                .map(this::toPluginInfo)
                .collect(Collectors.toList());
    }

    @Override
    public List<PluginInfo> getPluginsByStatus(PluginStatus status) {
        return pluginRecordMapper.findByTenantAndStatus(status.code()).stream()
                .map(this::toPluginInfo)
                .collect(Collectors.toList());
    }

    @Override
    public List<PluginInfo> getEnabledPlugins() {
        return pluginRecordMapper.findEnabledByTenant().stream()
                .map(this::toPluginInfo)
                .collect(Collectors.toList());
    }

    @Override
    public boolean isPluginInstalled(String pluginId) {
        PluginRecord record = pluginRecordMapper.findByTenantAndPluginId(pluginId);
        return record != null;
    }

    @Override
    public boolean isPluginEnabled(String pluginId) {
        PluginRecord record = pluginRecordMapper.findByTenantAndPluginId(pluginId);
        return record != null && record.isEnabled();
    }

    @Override
    public boolean isNamespaceAvailable(String namespace) {
        return pluginRecordMapper.isNamespaceAvailable(namespace);
    }

    @Override
    public Optional<Plugin> getPluginInstance(String pluginId) {
        return Optional.ofNullable(pluginInstances.get(pluginId));
    }

    @Override
    public void registerPluginInstance(Plugin plugin) {
        pluginInstances.put(plugin.getPluginId(), plugin);
        log.info("Registered plugin instance: {}", plugin.getPluginId());
    }

    @Override
    public void unregisterPluginInstance(String pluginId) {
        pluginInstances.remove(pluginId);
        log.info("Unregistered plugin instance: {}", pluginId);
    }

    // ========== Helper Methods ==========

    private PluginInfo toPluginInfo(PluginRecord record) {
        return PluginInfo.builder()
                .pid(record.getPid())
                .pluginId(record.getPluginId())
                .namespace(record.getNamespace())
                .version(record.getVersion())
                .displayName(record.getDisplayName())
                .description(record.getDescription())
                .author(record.getAuthor())
                .status(record.getStatusEnum())
                .installedAt(record.getInstalledAt())
                .enabledAt(record.getEnabledAt())
                .disabledAt(record.getDisabledAt())
                .settings(record.getSettings())
                .manifest(record.getManifest())
                .build();
    }

    // ========== PF4J Integration ==========

    /**
     * Synchronize a PF4J plugin with the database.
     * This is called when a JAR plugin is hot-loaded.
     *
     * @param pluginId the PF4J plugin ID
     * @return the plugin info if successful
     */
    public Optional<PluginInfo> syncPf4jPlugin(String pluginId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        log.info("Syncing PF4J plugin: {} for tenant {}", pluginId, tenantId);

        PluginWrapper wrapper = auraPluginManager.getPluginWrapper(pluginId);
        if (wrapper == null) {
            log.warn("PF4J plugin not found: {}", pluginId);
            return Optional.empty();
        }

        // Check if already in database
        PluginRecord existing = pluginRecordMapper.findByTenantAndPluginId(pluginId);
        if (existing != null) {
            log.info("Plugin {} already exists in database, updating version", pluginId);
            existing.setVersion(wrapper.getDescriptor().getVersion());
            existing.setUpdatedAt(Instant.now());
            pluginRecordMapper.updateById(existing);
            return Optional.of(toPluginInfo(existing));
        }

        // Create new record
        PluginManifest manifest = PluginManifest.builder()
                .pluginId(pluginId)
                .namespace(extractNamespace(pluginId))
                .version(wrapper.getDescriptor().getVersion())
                .displayName(pluginId)
                .description(wrapper.getDescriptor().getPluginDescription())
                .author(wrapper.getDescriptor().getProvider())
                .build();

        PluginRecord record = PluginRecord.builder()
                .pid(UniqueIdGenerator.generate())
                .tenantId(tenantId)
                .pluginId(pluginId)
                .namespace(manifest.getNamespace())
                .version(manifest.getVersion())
                .displayName(manifest.getDisplayName())
                .description(manifest.getDescription())
                .author(manifest.getAuthor())
                .status(PluginStatus.INSTALLED.code())
                .manifest(manifest)
                .installedAt(Instant.now())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .deletedFlag(false)
                .build();

        pluginRecordMapper.insert(record);

        // Register plugin instance if it extends AuraPlugin
        org.pf4j.Plugin pf4jPlugin = wrapper.getPlugin();
        if (pf4jPlugin instanceof AuraPlugin auraPlugin) {
            pluginInstances.put(pluginId, auraPlugin);
            log.info("Registered AuraPlugin instance: {}", pluginId);
        }

        log.info("Synced PF4J plugin: {} (PID: {})", pluginId, record.getPid());
        return Optional.of(toPluginInfo(record));
    }

    /**
     * Remove a PF4J plugin from the database when hot-unloaded.
     *
     * @param pluginId the plugin ID
     */
    public void removePf4jPlugin(String pluginId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        log.info("Removing PF4J plugin: {} for tenant {}", pluginId, tenantId);

        PluginRecord record = pluginRecordMapper.findByTenantAndPluginId(pluginId);
        if (record != null) {
            pluginRecordMapper.softDelete(record.getPid());
            log.info("Removed PF4J plugin from database: {}", pluginId);
        }

        pluginInstances.remove(pluginId);
        extensionRegistry.removePluginFromCache(pluginId);
    }

    /**
     * Get the PF4J plugin manager.
     *
     * @return the AuraPluginManager instance
     */
    public AuraPluginManager getPf4jManager() {
        return auraPluginManager;
    }

    /**
     * Get the extension registry.
     *
     * @return the ExtensionRegistry instance
     */
    public ExtensionRegistry getExtensionRegistry() {
        return extensionRegistry;
    }

    private String extractNamespace(String pluginId) {
        // Extract namespace from plugin ID (e.g., "com.example.my-plugin" -> "my-plugin")
        int lastDot = pluginId.lastIndexOf('.');
        if (lastDot > 0) {
            return pluginId.substring(lastDot + 1);
        }
        return pluginId;
    }
}
