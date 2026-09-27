package com.auraboot.framework.plugin.pf4j;

import lombok.extern.slf4j.Slf4j;
import org.pf4j.*;
import org.pf4j.spring.SpringPluginManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.Locale;

/**
 * AuraBoot plugin manager extending SpringPluginManager.
 * Provides hot-loading capabilities for JAR plugins.
 *
 * Features:
 * - Dynamic plugin loading/unloading without restart
 * - Spring integration for dependency injection in plugins
 * - Extension point discovery and management
 * - Plugin isolation via separate classloaders
 */
@Slf4j
@Component
public class AuraPluginManager extends SpringPluginManager {

    private final Path pluginsRoot;
    private final Map<ClassLoader, LoadedPluginArtifact> loadedArtifacts = Collections.synchronizedMap(new WeakHashMap<>());

    public record LoadedPluginArtifact(String pluginId, Path path, String digest) {}

    /**
     * Create the plugin manager with configured plugins directory.
     *
     * @param pluginsDir the plugins directory path
     */
    public AuraPluginManager(@Value("${aura.plugins.dir:plugins}") String pluginsDir) {
        super(resolvePluginsPath(pluginsDir));
        this.pluginsRoot = resolvePluginsPath(pluginsDir);
        log.info("AuraPluginManager initialized with plugins directory: {}", this.pluginsRoot);
    }

    private static Path resolvePluginsPath(String pluginsDir) {
        Path path = Paths.get(pluginsDir);
        if (!path.isAbsolute()) {
            path = Paths.get(System.getProperty("user.dir"), pluginsDir);
        }
        return path;
    }

    /**
     * Override to fix classloader mismatch with Spring Boot DevTools.
     * Default JarPluginLoader uses its own classloader (base) as parent,
     * but extension point interfaces live in RestartClassLoader.
     * Using thread context classloader ensures plugins can see all app classes.
     */
    @Override
    protected PluginLoader createPluginLoader() {
        return new CompoundPluginLoader()
                .add(new PluginLoader() {
                    @Override
                    public boolean isApplicable(Path pluginPath) {
                        return Files.exists(pluginPath)
                                && pluginPath.toString().toLowerCase(Locale.ROOT).endsWith(".jar");
                    }

                    @Override
                    public ClassLoader loadPlugin(Path pluginPath, PluginDescriptor pluginDescriptor) {
                        Path artifact = pluginPath.toAbsolutePath().normalize();
                        String digest = pluginArtifactDigest(artifact);
                        ClassLoader parent = Thread.currentThread().getContextClassLoader();
                        PluginClassLoader pluginClassLoader = new PluginClassLoader(
                                AuraPluginManager.this, pluginDescriptor, parent);
                        pluginClassLoader.addFile(pluginPath.toFile());
                        loadedArtifacts.put(pluginClassLoader,
                                new LoadedPluginArtifact(pluginDescriptor.getPluginId(), artifact, digest));
                        return pluginClassLoader;
                    }
                })
                .add(new DefaultPluginLoader(this));
    }

    /**
     * Observe the original JAR identity for a currently started implementation.
     * The deployment must still make plugin artifacts read-only; this is a drift check,
     * not an atomic proof against a malicious writer changing bytes during class loading.
     */
    public Optional<LoadedPluginArtifact> observeLoadedArtifact(Class<?> implementation) {
        if (implementation == null) throw new IllegalArgumentException("Implementation class required");
        var artifact = loadedArtifacts.get(implementation.getClassLoader());
        if (artifact == null) return Optional.empty();
        var wrapper = getPlugin(artifact.pluginId());
        if (wrapper == null || wrapper.getPluginState() != PluginState.STARTED
                || wrapper.getPluginClassLoader() != implementation.getClassLoader()) return Optional.empty();
        if (!artifact.digest().equals(pluginArtifactDigest(artifact.path()))) {
            throw new IllegalStateException("Loaded plugin artifact changed: " + artifact.pluginId());
        }
        return Optional.of(artifact);
    }

    private static String pluginArtifactDigest(Path path) {
        if (!Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Plugin artifact must be a regular non-symlink JAR");
        }
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            try (var input = new java.security.DigestInputStream(
                    Files.newInputStream(path, java.nio.file.LinkOption.NOFOLLOW_LINKS), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (IOException | java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Cannot fingerprint plugin artifact", failure);
        }
    }

    /** PF4J's default batch loader logs malformed plugins and continues; deployments must fail. */
    @Override
    public void loadPlugins() {
        for (Path path : pluginRepository.getPluginPaths()) {
            loadPluginFromPath(path);
        }
        resolvePlugins();
        if (!getUnresolvedPlugins().isEmpty()) {
            throw new IllegalStateException("Unresolved plugins prevent application startup");
        }
    }

    @PostConstruct
    public void init() {
        try {
            // Ensure plugins directory exists
            if (!Files.exists(pluginsRoot)) {
                Files.createDirectories(pluginsRoot);
                log.info("Created plugins directory: {}", pluginsRoot);
            }

            if (!Files.isDirectory(pluginsRoot) || !Files.isReadable(pluginsRoot)) {
                throw new IllegalStateException("Plugin root must be a readable directory: " + pluginsRoot);
            }

            // Load all plugins
            loadPlugins();
            log.info("Loaded {} plugins", getPlugins().size());

            // Start all plugins
            startPlugins();
            for (PluginWrapper wrapper : getPlugins()) {
                PluginState state = wrapper.getPluginState();
                if (state != PluginState.STARTED && state != PluginState.DISABLED) {
                    throw new IllegalStateException("Plugin did not start: " + wrapper.getPluginId()
                            + " (" + state + ")", wrapper.getFailedException());
                }
            }
            log.info("Started {} plugins", getStartedPlugins().size());

        } catch (Exception e) {
            cleanup();
            throw new IllegalStateException("Failed to initialize plugin manager", e);
        }
    }

    @PreDestroy
    public void cleanup() {
        log.info("Shutting down plugin manager...");
        for (PluginWrapper wrapper : new ArrayList<>(getStartedPlugins())) {
            try {
                stopPlugin(wrapper.getPluginId());
            } catch (Exception e) {
                log.warn("Failed to stop plugin {} during shutdown: {}",
                        wrapper.getPluginId(), e.getMessage(), e);
            }
        }
        log.info("Plugin manager shutdown complete");
    }

    // ========== Hot-Loading Operations ==========

    /**
     * Hot-load a plugin from a JAR file.
     * If a plugin with the same ID is already loaded, it will be unloaded first.
     *
     * @param jarPath path to the plugin JAR file
     * @return the loaded plugin ID, or null if loading failed
     */
    public String hotLoadPlugin(Path jarPath) {
        log.info("Hot-loading plugin from: {}", jarPath);

        try {
            // Read plugin ID from JAR manifest before loading
            String existingPluginId = readPluginIdFromJar(jarPath);
            if (existingPluginId != null && isPluginLoaded(existingPluginId)) {
                log.info("Plugin {} is already loaded, unloading first", existingPluginId);
                hotUnloadPlugin(existingPluginId);
            }

            // Load the plugin
            String pluginId = loadPlugin(jarPath);
            if (pluginId == null) {
                log.error("Failed to load plugin from: {}", jarPath);
                return null;
            }

            // Start the plugin
            PluginState state = startPlugin(pluginId);
            if (state != PluginState.STARTED) {
                log.error("Failed to start plugin: {} (state: {})", pluginId, state);
                unloadPlugin(pluginId);
                return null;
            }

            log.info("Successfully hot-loaded plugin: {}", pluginId);
            return pluginId;

        } catch (Exception e) {
            log.error("Failed to hot-load plugin from: {}", jarPath, e);
            return null;
        }
    }

    /**
     * Read plugin ID from JAR manifest.
     *
     * @param jarPath path to the plugin JAR file
     * @return the plugin ID, or null if not found
     */
    private String readPluginIdFromJar(Path jarPath) {
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            Manifest manifest = jarFile.getManifest();
            if (manifest != null) {
                String pluginId = manifest.getMainAttributes().getValue("Plugin-Id");
                if (pluginId != null && !pluginId.isEmpty()) {
                    log.debug("Read Plugin-Id from JAR: {}", pluginId);
                    return pluginId;
                }
            }
        } catch (IOException e) {
            log.warn("Failed to read manifest from JAR: {}", jarPath, e);
        }
        return null;
    }

    /**
     * Hot-reload a plugin (unload and reload).
     *
     * @param pluginId the plugin ID to reload
     * @return true if reload was successful
     */
    public boolean hotReloadPlugin(String pluginId) {
        log.info("Hot-reloading plugin: {}", pluginId);

        PluginWrapper wrapper = getPlugin(pluginId);
        if (wrapper == null) {
            log.error("Plugin not found: {}", pluginId);
            return false;
        }

        Path pluginPath = wrapper.getPluginPath();

        try {
            // Stop and unload
            stopPlugin(pluginId);
            unloadPlugin(pluginId);

            // Reload
            String reloadedId = loadPlugin(pluginPath);
            if (reloadedId == null) {
                log.error("Failed to reload plugin: {}", pluginId);
                return false;
            }

            // Start
            PluginState state = startPlugin(reloadedId);
            if (state != PluginState.STARTED) {
                log.error("Failed to start reloaded plugin: {} (state: {})", reloadedId, state);
                return false;
            }

            log.info("Successfully hot-reloaded plugin: {}", pluginId);
            return true;

        } catch (Exception e) {
            log.error("Failed to hot-reload plugin: {}", pluginId, e);
            return false;
        }
    }

    /**
     * Hot-unload a plugin.
     *
     * @param pluginId the plugin ID to unload
     * @return true if unload was successful
     */
    public boolean hotUnloadPlugin(String pluginId) {
        log.info("Hot-unloading plugin: {}", pluginId);

        PluginWrapper wrapper = getPlugin(pluginId);
        if (wrapper == null) {
            log.warn("Plugin not found: {}", pluginId);
            return true; // Already unloaded
        }

        try {
            // Stop if running
            if (wrapper.getPluginState() == PluginState.STARTED) {
                stopPlugin(pluginId);
            }

            // Unload
            boolean unloaded = unloadPlugin(pluginId);
            if (!unloaded) {
                log.error("Failed to unload plugin: {}", pluginId);
                return false;
            }

            log.info("Successfully hot-unloaded plugin: {}", pluginId);
            return true;

        } catch (Exception e) {
            log.error("Failed to hot-unload plugin: {}", pluginId, e);
            return false;
        }
    }

    // ========== Extension Discovery ==========

    /**
     * Get all extensions of a specific type.
     *
     * @param extensionType the extension interface class
     * @param <T> the extension type
     * @return list of extensions
     */
    public <T> List<T> getExtensionsOfType(Class<T> extensionType) {
        return getExtensions(extensionType);
    }

    /**
     * Get extensions of a specific type from a specific plugin.
     *
     * @param extensionType the extension interface class
     * @param pluginId the plugin ID
     * @param <T> the extension type
     * @return list of extensions from the plugin
     */
    public <T> List<T> getExtensionsOfType(Class<T> extensionType, String pluginId) {
        return getExtensions(extensionType, pluginId);
    }

    /**
     * Get all extension classes for a plugin.
     *
     * @param pluginId the plugin ID
     * @return set of extension classes
     */
    @Override
    public Set<String> getExtensionClassNames(String pluginId) {
        return super.getExtensionClassNames(pluginId);
    }

    // ========== Plugin Information ==========

    /**
     * Get plugin wrapper by ID.
     *
     * @param pluginId the plugin ID
     * @return the plugin wrapper, or null if not found
     */
    public PluginWrapper getPluginWrapper(String pluginId) {
        return getPlugin(pluginId);
    }

    /**
     * Get all started plugins.
     *
     * @return list of started plugin wrappers
     */
    public List<PluginWrapper> getAllStartedPlugins() {
        return getStartedPlugins();
    }

    /**
     * Get all loaded plugins.
     *
     * @return list of all plugin wrappers
     */
    public List<PluginWrapper> getAllPlugins() {
        return getPlugins();
    }

    /**
     * Check if a plugin is loaded.
     *
     * @param pluginId the plugin ID
     * @return true if the plugin is loaded
     */
    public boolean isPluginLoaded(String pluginId) {
        return getPlugin(pluginId) != null;
    }

    /**
     * Check if a plugin is started.
     *
     * @param pluginId the plugin ID
     * @return true if the plugin is started
     */
    public boolean isPluginStarted(String pluginId) {
        PluginWrapper wrapper = getPlugin(pluginId);
        return wrapper != null && wrapper.getPluginState() == PluginState.STARTED;
    }

    /**
     * Get plugin state.
     *
     * @param pluginId the plugin ID
     * @return the plugin state, or null if not found
     */
    public PluginState getPluginState(String pluginId) {
        PluginWrapper wrapper = getPlugin(pluginId);
        return wrapper != null ? wrapper.getPluginState() : null;
    }

    /**
     * Get plugin descriptor.
     *
     * @param pluginId the plugin ID
     * @return the plugin descriptor, or null if not found
     */
    public PluginDescriptor getPluginDescriptor(String pluginId) {
        PluginWrapper wrapper = getPlugin(pluginId);
        return wrapper != null ? wrapper.getDescriptor() : null;
    }

    /**
     * Get the plugins root directory.
     *
     * @return plugins root path
     */
    public Path getPluginsRoot() {
        return pluginsRoot;
    }

    // ========== Plugin Statistics ==========

    /**
     * Get plugin statistics.
     *
     * @return map of statistics
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalPlugins", getPlugins().size());
        stats.put("startedPlugins", getStartedPlugins().size());
        stats.put("stoppedPlugins", getPlugins().size() - getStartedPlugins().size());
        stats.put("pluginsRoot", pluginsRoot.toString());

        // Extension statistics
        Map<String, Integer> extensionCounts = new HashMap<>();
        for (PluginWrapper wrapper : getPlugins()) {
            String pluginId = wrapper.getPluginId();
            Set<String> extensions = getExtensionClassNames(pluginId);
            extensionCounts.put(pluginId, extensions.size());
        }
        stats.put("extensionCounts", extensionCounts);

        return stats;
    }
}
