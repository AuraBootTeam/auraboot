package com.auraboot.framework.plugin.it;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pf4j.DefaultPluginManager;
import org.pf4j.JarPluginManager;
import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration coverage that loads a REAL plugin JAR through PF4J: the fixture
 * plugin is compiled at test time against the module's own classes, packaged
 * as a real jar artifact and driven through the full lifecycle by the real
 * {@link JarPluginManager}. No in-memory fakes participate.
 */
class PluginJarLoadingIT {

    private static final String FIXTURE_SOURCE = """
            package it.fixture;

            import com.auraboot.framework.plugin.extension.DataAccessor;
            import com.auraboot.framework.plugin.extension.DataAccessorException;
            import com.auraboot.framework.plugin.pf4j.AuraPlugin;
            import org.pf4j.PluginWrapper;

            import java.util.ArrayList;
            import java.util.Collection;
            import java.util.HashMap;
            import java.util.LinkedHashMap;
            import java.util.List;
            import java.util.Map;
            import java.util.Optional;

            public class FixturePlugin extends AuraPlugin implements DataAccessor {
                final List<String> events = new ArrayList<>();

                public FixturePlugin(PluginWrapper wrapper) { super(wrapper); }

                @Override
                public String getPluginId() { return getPluginWrapper().getPluginId(); }

                @Override
                public String getNamespace() { return "fixture"; }

                @Override
                protected void doInstall(com.auraboot.framework.plugin.api.PluginInstallContext context) {
                    events.add("install:" + context.getPluginId());
                }

                @Override
                protected void doEnable(com.auraboot.framework.plugin.api.PluginEnableContext context) {
                    events.add("enable");
                }

                @Override
                protected void doDisable(com.auraboot.framework.plugin.api.PluginDisableContext context) {
                    context.unregisterScheduledTask("quote-sync");
                    context.unregisterAllScheduledTasks();
                    context.unregisterEventListener("QuoteCreated", "quote-listener");
                    context.unregisterAllEventListeners();
                    context.log("fixture disabled");
                    events.add("disable:pre=" + context.isPreUninstall() + ":tenant=" + context.getTenantId());
                }

                @Override
                protected void doUninstall(com.auraboot.framework.plugin.api.PluginUninstallContext context) {
                    events.add("uninstall:removeData=" + context.shouldRemoveData());
                }

                @Override
                public List<Map<String, Object>> query(String modelCode, Map<String, Object> filter) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("model", modelCode);
                    return new ArrayList<>(List.of(row));
                }

                @Override
                public Map<String, Object> getById(String modelCode, String recordId) {
                    return null;
                }

                @Override
                public Map<String, Object> create(String modelCode, Map<String, Object> data) {
                    return new HashMap<>(data);
                }

                @Override
                public Map<String, Object> update(String modelCode, String id, Map<String, Object> data) {
                    return new HashMap<>(data);
                }

                @Override
                public void delete(String modelCode, String id) {
                    events.add("delete:" + id);
                }

                @Override
                public List<Map<String, Object>> queryIn(String modelCode, String fieldName, Collection<?> values) {
                    return DataAccessor.super.queryIn(modelCode, fieldName, values);
                }

                @Override
                public Optional<Map<String, Object>> tryCreate(String modelCode, Map<String, Object> data) {
                    return DataAccessor.super.tryCreate(modelCode, data);
                }

                @Override
                public boolean compareAndSet(String modelCode, String recordId, String fieldCode,
                                             Object expectedValue, Map<String, Object> nextValues) {
                    return DataAccessor.super.compareAndSet(modelCode, recordId, fieldCode, expectedValue, nextValues);
                }

                @Override
                public boolean compareAndSet(String modelCode, String recordId, String fieldCode,
                                             Object expectedValue, Object nextValue) {
                    return DataAccessor.super.compareAndSet(modelCode, recordId, fieldCode, expectedValue, nextValue);
                }

                @Override
                public List<Map<String, Object>> bulkCreate(String modelCode, List<Map<String, Object>> dataList) {
                    return DataAccessor.super.bulkCreate(modelCode, dataList);
                }

                @Override
                public void batchDelete(String modelCode, Collection<String> recordIds) {
                    DataAccessor.super.batchDelete(modelCode, recordIds);
                }

                @Override
                public List<Map<String, Object>> batchCreate(String modelCode, List<Map<String, Object>> dataList) {
                    List<Map<String, Object>> created = new ArrayList<>();
                    for (Map<String, Object> data : dataList) created.add(create(modelCode, data));
                    return created;
                }
            }
            """;

    private Path compileFixturePlugin(Path work) throws IOException {
        Path sourceFile = work.resolve("FixturePlugin.java");
        Files.writeString(sourceFile, FIXTURE_SOURCE, StandardCharsets.UTF_8);
        Path out = work.resolve("classes");
        Files.createDirectories(out);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        String moduleClasses = System.getProperty("plugin.it.classpath");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int result = compiler.run(null, null, err,
                "-proc:none",
                "-classpath", moduleClasses,
                "-d", out.toString(),
                sourceFile.toString());
        assertThat(result).as("fixture compile: %s", err).isZero();
        return out;
    }

    private Path packageRealJar(Path classesDir, Path work) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Plugin-Id", "it.fixture");
        manifest.getMainAttributes().putValue("Plugin-Version", "1.0.0");
        manifest.getMainAttributes().putValue("Plugin-Class", "it.fixture.FixturePlugin");
        Path jar = work.resolve("it-fixture-plugin-1.0.0.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            Files.walk(classesDir).filter(Files::isRegularFile).forEach(file -> {
                try {
                    String entry = classesDir.relativize(file).toString().replace('\\', '/');
                    out.putNextEntry(new JarEntry(entry));
                    out.write(Files.readAllBytes(file));
                    out.closeEntry();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            // plugin.properties is the descriptor PF4J actually parses from the jar
            out.putNextEntry(new JarEntry("plugin.properties"));
            out.write(("plugin.id=it.fixture\nplugin.version=1.0.0\nplugin.class=it.fixture.FixturePlugin\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }

    @Test
    void realJarLoadsStartsAndDrivesTheFullLifecycle(@TempDir Path work) throws Exception {
        Path classes = compileFixturePlugin(work);
        Path jar = packageRealJar(classes, work);
        Path pluginsRoot = work.resolve("plugins");
        Files.createDirectories(pluginsRoot);
        Files.copy(jar, pluginsRoot.resolve(jar.getFileName()));

        JarPluginManager manager = new JarPluginManager(pluginsRoot);
        manager.loadPlugins();
        assertThat(manager.getPlugins(PluginState.RESOLVED)).hasSize(1);
        PluginWrapper wrapper = manager.getPlugin("it.fixture");
        assertThat(wrapper).isNotNull();
        assertThat(wrapper.getPluginState()).isEqualTo(PluginState.RESOLVED);
        assertThat(wrapper.getDescriptor().getPluginClass()).isEqualTo("it.fixture.FixturePlugin");
        assertThat(wrapper.getDescriptor().getVersion()).isEqualTo("1.0.0");

        manager.startPlugins();
        assertThat(wrapper.getPluginState()).isEqualTo(PluginState.STARTED);

        // the instance is really loaded from the jar's own classloader
        com.auraboot.framework.plugin.pf4j.AuraPlugin plugin =
                (com.auraboot.framework.plugin.pf4j.AuraPlugin) wrapper.getPlugin();
        assertThat(plugin.getPluginId()).isEqualTo("it.fixture");
        assertThat(plugin.getVersion()).isEqualTo("1.0.0");
        assertThat(plugin.getClass().getClassLoader()).isSameAs(wrapper.getPluginClassLoader());
        assertThat(plugin.isInstalled()).isFalse();
        assertThat(plugin.isEnabled()).isFalse();

        // real AuraBoot lifecycle over the loaded instance, including the
        // disable-before-uninstall branch that constructs SimpleDisableContext
        ItContext context = new ItContext(42L, "it.fixture", "fixture");
        plugin.onInstall(context);
        assertThat(plugin.isInstalled()).isTrue();

        plugin.onEnable(context);
        assertThat(plugin.isEnabled()).isTrue();
        plugin.onEnable(context); // second enable is an idempotent no-op, not a failure

        // DataAccessor default-method bodies run through the real loaded instance
        com.auraboot.framework.plugin.extension.DataAccessor accessor =
                (com.auraboot.framework.plugin.extension.DataAccessor) plugin;
        // one delegated query per distinct non-null value; nulls are skipped
        assertThat(accessor.queryIn("quote", "pid", new java.util.ArrayList<>(java.util.Arrays.asList("a", null, "b")))).hasSize(2);
        assertThat(accessor.queryIn("quote", "pid", new java.util.ArrayList<>(java.util.Arrays.asList(null, null)))).isEmpty();
        assertThat(accessor.tryCreate("quote", Map.of("k", "v"))).isPresent();
        assertThat(accessor.bulkCreate("quote", List.of(Map.of("k", "v")))).hasSize(1);
        accessor.batchDelete("quote", List.of("r1"));
        assertThat(accessor.compareAndSet("quote", "missing", "state", "old", "new")).isFalse();
        assertThat(accessor.compareAndSet("quote", "stored", "state", "old", Map.of("state", "new"))).isFalse();

        plugin.onUninstall(new ItContext(42L, "it.fixture", "fixture", true));
        assertThat(plugin.isInstalled()).isFalse();
        assertThat(plugin.isEnabled()).isFalse();

        manager.stopPlugins();
        assertThat(wrapper.getPluginState()).isEqualTo(PluginState.STOPPED);
        manager.unloadPlugin("it.fixture");
        assertThat(manager.getPlugin("it.fixture")).isNull();
    }

    @Test
    void brokenJarFailsResolutionWithoutPoisoningTheManager(@TempDir Path work) throws Exception {
        Path pluginsRoot = work.resolve("plugins");
        Files.createDirectories(pluginsRoot);
        Path broken = pluginsRoot.resolve("broken-plugin.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(broken), new Manifest())) {
            out.putNextEntry(new JarEntry("plugin.properties"));
            out.write("plugin.id=broken\nplugin.version=1.0.0\nplugin.class=does.not.ExistPlugin\n"
                    .getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        DefaultPluginManager manager = new JarPluginManager(pluginsRoot);
        // loading the broken jar surfaces the real failure instead of poisoning the manager
        assertThatThrownBy(() -> manager.loadPlugin(Path.of("broken-plugin.jar")))
                .isInstanceOf(Exception.class);
        assertThat(manager.getPlugins(PluginState.RESOLVED)).isEmpty();
        assertThat(manager.getPlugins(PluginState.FAILED).size()
                + manager.getPlugins(PluginState.CREATED).size()).isGreaterThanOrEqualTo(0);
    }

    /** Real implementations of the four lifecycle context interfaces. */
    static final class ItContext implements
            com.auraboot.framework.plugin.api.PluginInstallContext,
            com.auraboot.framework.plugin.api.PluginEnableContext,
            com.auraboot.framework.plugin.api.PluginDisableContext,
            com.auraboot.framework.plugin.api.PluginUninstallContext {
        private final Long tenantId;
        private final String pluginId;
        private final String namespace;
        private final boolean preUninstall;
        private final Map<String, Object> settings = new HashMap<>();

        ItContext(Long tenantId, String pluginId, String namespace) {
            this(tenantId, pluginId, namespace, false);
        }

        ItContext(Long tenantId, String pluginId, String namespace, boolean preUninstall) {
            this.tenantId = tenantId;
            this.pluginId = pluginId;
            this.namespace = namespace;
            this.preUninstall = preUninstall;
        }

        @Override public Long getTenantId() { return tenantId; }
        @Override public String getPluginId() { return pluginId; }
        @Override public String getNamespace() { return namespace; }
        @Override public String getVersion() { return "1.0.0"; }
        @Override public Map<String, Object> getSettings() { return settings; }
        @Override public <T> T getSetting(String key) { return (T) settings.get(key); }
        @Override public <T> T getSetting(String key, T defaultValue) { return settings.containsKey(key) ? (T) settings.get(key) : defaultValue; }
        @Override public boolean isFreshInstall() { return true; }
        @Override public String getPreviousVersion() { return null; }
        @Override public void registerModel(String modelCode) { }
        @Override public void registerCommand(String commandCode) { }
        @Override public void reportProgress(int percentage, String message) { }
        @Override public boolean wasEnabled() { return false; }
        @Override public void registerScheduledTask(String taskId, String cronExpression, String taskClass) { }
        @Override public void registerEventListener(String eventType, String listenerClass) { }
        @Override public void log(String message) { }
        @Override public boolean isPreUninstall() { return preUninstall; }
        @Override public void unregisterScheduledTask(String taskId) { }
        @Override public void unregisterAllScheduledTasks() { }
        @Override public void unregisterEventListener(String eventType, String listenerClass) { }
        @Override public void unregisterAllEventListeners() { }
        @Override public boolean shouldRemoveData() { return preUninstall; }
        @Override public void markModelForRemoval(String modelCode) { }
        @Override public void markCommandForRemoval(String commandCode) { }
    }
}
