package com.auraboot.framework.notification.sourcebinding;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.crypto.FieldEncryptionService;
import com.auraboot.framework.plugin.dto.packages.PackageUninstallResult;
import com.auraboot.framework.plugin.service.PluginPackageService;
import com.auraboot.framework.scheduler.service.SchedulerEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static com.auraboot.framework.notification.sourcebinding.S06Current001PluginCleanupAdmission.require;

/** One explicitly authorized lifecycle cleanup; never a business test or a delivery-success assertion. */
@SpringBootTest(classes = TestApplication.class, properties = {
        "spring.datasource.url=${TEST_DATABASE_URL}", "spring.data.redis.database=${TEST_REDIS_DATABASE}",
        "s06.native.source-binding.fixture=true",
        "spring.datasource.hikari.connection-init-sql=SET TIME ZONE 'UTC'",
        "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.slf4j.Slf4jImpl",
        "logging.level.com.auraboot.framework=INFO", "logging.level.org.mybatis=INFO",
        "logging.level.org.springframework.security=WARN", "logging.level.org.springframework.cache=WARN"})
@ActiveProfiles("integration-test")
@Import(RegisteredSourceBindingPostgresIT.PollerIsolation.class)
@EnabledIfEnvironmentVariable(named = "S06_NATIVE_PLUGIN_REGISTRY_CLEANUP", matches = "true")
class S06Current001PluginRegistryCleanupIT {
    @DynamicPropertySource
    static void crypto(DynamicPropertyRegistry properties) {
        String key = S06NativeEncryptionAdmission.requiredKey(System.getenv());
        properties.add("security.field-encryption.key", () -> key);
    }

    @Autowired ApplicationContext context;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager manager;
    @Autowired PluginPackageService packages;
    @Autowired FieldEncryptionService encryption;
    @MockitoBean SchedulerEngine scheduler;

    private record Snapshot(Map<String, List<String>> tenantTables, List<String> allPluginRows) {}

    @Test
    void uninstallOnlyCurrent001FourteenSealedPluginRegistrations() throws Exception {
        if (uninstallAutomaticAfterExactPostBootRefresh()) return;
        require(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).isEmpty(), "CLEANUP_SCHEDULER_ACTIVE");
        require("13".equals(System.getenv("TEST_REDIS_DATABASE")), "CLEANUP_REDIS_SCOPE_INVALID");
        try (var connection = dataSource.getConnection()) {
            require(connection.getMetaData().getDatabaseProductName().equals("PostgreSQL")
                    && connection.getCatalog().equals("enterprise_22"), "CLEANUP_DATABASE_SCOPE_INVALID");
            validateDatabaseUrl(connection.getMetaData().getURL());
        }
        String sessionTimezone = jdbc.queryForObject("SELECT current_setting('TimeZone')", String.class);
        require("UTC".equals(sessionTimezone), "CLEANUP_SESSION_TIMEZONE_NOT_UTC");
        var admission = S06Current001PluginCleanupAdmission.load(
                Path.of(required("S06_NATIVE_PLUGIN_CLEANUP_INPUT")), required("S06_NATIVE_PLUGIN_CLEANUP_INPUT_SHA256"), json);
        Map<String, Object> crypto = S06NativeEncryptionAdmission.admit(System.getenv(), json, encryption,
                getClass(), context.getEnvironment().getActiveProfiles());
        if (admission.schemaVersion() == 3) {
            uninstallAutomaticOwnedRegistrations(admission, crypto, sessionTimezone);
            return;
        }
        Files.createDirectory(admission.output());
        verifyCurrentPaused(admission.output());
        MetaContext.Snapshot caller = MetaContext.snapshot();
        List<Long> tenants = admission.plugins().stream().map(S06Current001PluginCleanupAdmission.Expected::tenant).sorted().toList();
        Set<String> pids = new HashSet<>();
        admission.plugins().forEach(row -> pids.add(row.pid()));
        TransactionTemplate transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        Map<String, Object> completed;
        try {
            completed = transaction.execute(status -> {
                try {
                    List<String> ids = admission.plugins().stream().map(S06Current001PluginCleanupAdmission.Expected::pluginId).distinct().sorted().toList();
                    String sql = "SELECT row_to_json(p)::text FROM ab_plugin p WHERE p.plugin_id IN ("
                            + placeholders(ids.size()) + ") AND p.deleted_flag=FALSE ORDER BY p.tenant_id,p.pid FOR UPDATE";
                    List<String> locked = jdbc.queryForList(sql, String.class, ids.toArray());
                    require(locked.size() == admission.plugins().size(), "CLEANUP_ACTUAL_PLUGIN_ROSTER_CHANGED");
                    Map<String, JsonNode> actual = new LinkedHashMap<>();
                    for (String raw : locked) {
                        JsonNode row = json.readTree(raw);
                        require(actual.put(row.path("pid").asText(), row) == null, "CLEANUP_DUPLICATE_ACTUAL_PID");
                        var expected = admission.plugins().stream().filter(r -> r.pid().equals(row.path("pid").asText())).findFirst()
                                .orElseThrow(() -> new IllegalStateException("CLEANUP_UNEXPECTED_PLUGIN"));
                        require(row.path("tenant_id").longValue() == expected.tenant()
                                && row.path("plugin_id").asText().equals(expected.pluginId())
                                && row.path("namespace").asText().equals(expected.namespace())
                                && row.path("version").asText().equals(expected.version())
                                && row.path("status").asText().equals(expected.status())
                                && row.path("deleted_flag").isBoolean() && !row.path("deleted_flag").booleanValue()
                                && digest(raw).equals(expected.rowSha256())
                                && SourceBindingCanonical.hash(row.path("manifest")).equals(expected.manifestSha256()),
                                "CLEANUP_ACTUAL_PLUGIN_TUPLE_CHANGED");
                    }
                    require(actual.keySet().equals(pids), "CLEANUP_MISSING_PLUGIN_PID");
                    Snapshot before = snapshot(tenants, pids);
                    write(admission.output().resolve("preview.json"), Map.of("inputSha256", admission.inputSha256(),
                            "expectedPlugins", admission.plugins().stream().map(target -> Map.of(
                                    "tenantId", Long.toString(target.tenant()), "pluginPid", target.pid(),
                                    "pluginId", target.pluginId(), "namespace", target.namespace(), "version", target.version(),
                                    "status", target.status(), "rowSha256", target.rowSha256(), "manifestSha256", target.manifestSha256())).toList(), "preservedBefore", summary(before), "crypto", crypto,
                            "sessionTimezone", sessionTimezone, "executed", false, "sourceClassificationSha256", admission.classificationSha256()));
                    List<Map<String, Object>> results = new ArrayList<>();
                    for (var target : admission.plugins()) {
                        MetaContext.setContext(target.tenant(), 0L, "SYSTEM", "s06-current001-owned-lifecycle-cleanup");
                        PackageUninstallResult result = packages.uninstall(target.pid(), S06Current001PluginCleanupAdmission.options());
                        require(result != null && result.isSuccess() && target.pid().equals(result.getPluginPid())
                                && target.pluginId().equals(result.getPluginId()) && result.getConfigResult() == null
                                && result.getBackendResult() == null && result.getFrontendResult() == null,
                                "CLEANUP_OFFICIAL_PACKAGE_UNINSTALL_FAILED");
                        results.add(Map.of("tenantId", Long.toString(target.tenant()), "pluginPid", target.pid(),
                                "pluginId", target.pluginId(), "success", true));
                    }
                    for (var target : admission.plugins()) {
                        JsonNode after = json.readTree(jdbc.queryForObject("SELECT row_to_json(p)::text FROM ab_plugin p WHERE pid=?",
                                String.class, target.pid()));
                        require(after.path("deleted_flag").isBoolean() && after.path("deleted_flag").booleanValue(),
                                "CLEANUP_SOFT_DELETE_NOT_PERSISTED");
                        require(normalizePlugin(after, pids).equals(normalizePlugin(actual.get(target.pid()), pids)),
                                "CLEANUP_PLUGIN_OTHER_FIELDS_CHANGED");
                    }
                    Snapshot after = snapshot(tenants, pids);
                    require(before.equals(after), "CLEANUP_CHANGED_PRESERVED_FACTS");
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("officialResults", results);
                    result.put("preservedBefore", summary(before));
                    result.put("preservedAfter", summary(after));
                    return result;
                } catch (Exception | AssertionError failure) {
                    status.setRollbackOnly();
                    throw new IllegalStateException(admission.schemaVersion() == 1 ? "CLEANUP_ALL_FOURTEEN_ROLLED_BACK" : "CLEANUP_EXACT_ROSTER_ROLLED_BACK", failure);
                }
            });
        } finally {
            MetaContext.clear();
            MetaContext.restore(caller);
        }
        require(completed != null, "CLEANUP_NO_TRANSACTION_RESULT");
        for (var target : admission.plugins()) {
            Boolean deleted = jdbc.queryForObject("SELECT deleted_flag FROM ab_plugin WHERE pid=? AND tenant_id=?",
                    Boolean.class, target.pid(), target.tenant());
            require(Boolean.TRUE.equals(deleted), "CLEANUP_COMMIT_SOFT_DELETE_NOT_PERSISTED");
        }
        Snapshot persisted = snapshot(tenants, pids);
        require(summary(persisted).equals(completed.get("preservedAfter")), "CLEANUP_COMMIT_READBACK_CHANGED");
        completed.put("persistedAfter", summary(persisted));
        completed.put("inputSha256", admission.inputSha256());
        completed.put("sourceClassificationSha256", admission.classificationSha256());
        completed.put("softDeletedPluginRegistrations", admission.plugins().size());
        completed.put("sessionTimezone", sessionTimezone);
        completed.put("businessDeliverySuccessClaimed", false);
        completed.put("stage", admission.schemaVersion() == 1 ? "current001-plugin-registration-cleanup-completed" : "sealed-native-plugin-registration-cleanup-completed");
        write(admission.output().resolve("cleanup-receipt.json"), completed);
    }

    static void validateDatabaseUrl(String url) throws Exception {
        require(url != null && url.startsWith("jdbc:postgresql://"), "CLEANUP_JDBC_URL_INVALID");
        java.net.URI uri = java.net.URI.create(url.substring(5));
        require(uri.getScheme().equals("postgresql") && Set.of("127.0.0.1", "localhost", "::1", "[::1]").contains(uri.getHost())
                && uri.getPort() == 5432 && uri.getPath().equals("/enterprise_22") && uri.getUserInfo() == null
                && uri.getFragment() == null, "CLEANUP_JDBC_URL_INVALID");
    }

    private void verifyCurrentPaused(Path output) throws Exception {
        Path original = Path.of(json.readTree(Path.of(required("S06_NATIVE_PLUGIN_CLEANUP_INPUT")).toFile())
                .path("pausedVerificationPath").asText()).toRealPath();
        JsonNode expected = json.readTree(original.toFile());
        Path fresh = output.resolve("actual-runtime-paused-verification.json");
        Path errors = output.resolve("actual-runtime-paused-verification.stderr");
        Files.createFile(fresh);
        Files.createFile(errors);
        Process process = new ProcessBuilder(NativeProcurementWorkspace.root().resolve("aura").toString(), "runtime", "verify",
                S06Current001PluginCleanupAdmission.RUNTIME, "--json")
                .directory(NativeProcurementWorkspace.root().toFile())
                .redirectOutput(fresh.toFile()).redirectError(errors.toFile()).start();
        if (!process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("CLEANUP_RUNTIME_VERIFY_TIMEOUT");
        }
        require(process.exitValue() == 0, "CLEANUP_CURRENT_RUNTIME_NOT_VERIFIED");
        JsonNode actual = json.readTree(fresh.toFile());
        require(actual.path("verdict").asText().equals("ok") && actual.path("findings").isArray()
                && actual.path("findings").isEmpty(), "CLEANUP_CURRENT_RUNTIME_NOT_VERIFIED");
        require(stableMetadata(actual.path("metadata")).equals(stableMetadata(expected.path("metadata"))),
                "CLEANUP_CURRENT_RUNTIME_METADATA_DRIFT");
        for (String identity : List.of("runtime", "sources", "runtimeManifest", "allocation", "environment", "processes", "ports")) {
            require(actual.path(identity).equals(expected.path(identity)), "CLEANUP_CURRENT_RUNTIME_GENERATION_OR_IDENTITY_DRIFT");
        }
    }

    private Snapshot snapshot(List<Long> tenants, Set<String> pids) throws Exception {
        List<String> tables = jdbc.queryForList("SELECT c.table_name FROM information_schema.columns c JOIN information_schema.tables t "
                + "ON t.table_schema=c.table_schema AND t.table_name=c.table_name WHERE c.table_schema='public' "
                + "AND t.table_type='BASE TABLE' AND c.column_name='tenant_id' AND c.table_name<>'ab_plugin' ORDER BY c.table_name", String.class);
        Map<String, List<String>> rows = new LinkedHashMap<>();
        for (String table : tables) {
            require(table.matches("[A-Za-z_][A-Za-z_0-9]*"), "CLEANUP_UNSAFE_DISCOVERED_TABLE");
            List<String> values = new ArrayList<>(jdbc.queryForList("SELECT row_to_json(t)::text FROM \"" + table
                    + "\" t WHERE tenant_id IN (" + placeholders(tenants.size()) + ")", String.class, tenants.toArray()));
            values.sort(Comparator.naturalOrder());
            rows.put(table, List.copyOf(values));
        }
        rows.put("@schema-columns", jdbc.queryForList("SELECT row_to_json(c)::text FROM information_schema.columns c "
                + "WHERE table_schema='public' ORDER BY table_name,ordinal_position", String.class));
        rows.put("@schema-constraints", jdbc.queryForList("SELECT json_build_array(n.nspname,t.relname,c.conname,"
                + "pg_get_constraintdef(c.oid))::text FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid "
                + "JOIN pg_namespace n ON n.oid=t.relnamespace WHERE n.nspname='public' ORDER BY t.relname,c.conname", String.class));
        List<String> plugins = new ArrayList<>();
        for (String raw : jdbc.queryForList("SELECT row_to_json(p)::text FROM ab_plugin p ORDER BY pid", String.class)) {
            plugins.add(normalizePlugin(json.readTree(raw), pids).toString());
        }
        return new Snapshot(rows, List.copyOf(plugins));
    }

    static JsonNode stableMetadata(JsonNode metadata) {
        require(metadata.isObject(), "CLEANUP_RUNTIME_METADATA_MISSING");
        ObjectNode result = metadata.deepCopy();
        result.remove("lastUsedAt");
        return result;
    }

    static JsonNode normalizePlugin(JsonNode row, Set<String> exactPids) {
        ObjectNode value = row.deepCopy();
        if (exactPids.contains(row.path("pid").asText())) {
            value.remove(List.of("deleted_flag", "updated_at"));
        }
        return value;
    }

    private Map<String, Object> summary(Snapshot snapshot) {
        Map<String, Object> result = new LinkedHashMap<>();
        snapshot.tenantTables().forEach((table, rows) -> result.put(table,
                Map.of("rows", rows.size(), "sha256", digest(jsonRows(rows)))));
        result.put("allPluginRowsExceptExactDeletedFlagUpdatedAt", Map.of("rows", snapshot.allPluginRows().size(),
                "sha256", digest(jsonRows(snapshot.allPluginRows()))));
        return result;
    }

    private String jsonRows(List<String> rows) {
        try { return json.writeValueAsString(rows); }
        catch (Exception failure) { throw new IllegalStateException("CLEANUP_SNAPSHOT_SERIALIZATION_FAILED", failure); }
    }

    private void write(Path file, Object value) throws Exception {
        Files.writeString(file, json.writerWithDefaultPrettyPrinter().writeValueAsString(value), StandardOpenOption.CREATE_NEW);
    }

    private static String placeholders(int count) {
        require(count > 0 && count <= S06Current001PluginCleanupAdmission.METHODS.size() * 2, "CLEANUP_QUERY_SCOPE_INVALID");
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private static String required(String key) {
        String value = System.getenv(key);
        require(value != null && !value.isBlank(), "CLEANUP_REQUIRED_INPUT_MISSING:" + key);
        return value;
    }
    // BEGIN automatic sourceFamily official cleanup.
    private Map<String, Object> automaticPostBootRosterRefresh;

    private boolean uninstallAutomaticAfterExactPostBootRefresh() throws Exception {
        Path declared = Path.of(required("S06_NATIVE_PLUGIN_CLEANUP_INPUT"));
        if (!json.readTree(declared.toFile()).path("kind").asText().equals("s06-automatic-owned-plugin-cleanup-admission-v3")) return false;
        Path original = S06Current001PluginCleanupAdmission.automaticIntentPath(declared,
                required("S06_NATIVE_PLUGIN_CLEANUP_INPUT_SHA256"));
        JsonNode intent = S06Current001PluginCleanupAdmission.automaticStrict(original, json);
        // Complete XML/sourceFamily/owners/PIDs/source/class/Boot/phase proofs BEFORE this method's first database access.
        var preflight = S06Current001PluginCleanupAdmission.automaticPreflight(original,
                required("S06_NATIVE_PLUGIN_CLEANUP_INPUT_SHA256"), json);
        require(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).isEmpty(), "CLEANUP_SCHEDULER_ACTIVE");
        require("13".equals(System.getenv("TEST_REDIS_DATABASE")), "CLEANUP_REDIS_SCOPE_INVALID");
        Map<String, Object> crypto = S06NativeEncryptionAdmission.admit(System.getenv(), json, encryption,
                getClass(), context.getEnvironment().getActiveProfiles());
        try (var connection = dataSource.getConnection()) {
            require(connection.getMetaData().getDatabaseProductName().equals("PostgreSQL")
                    && connection.getCatalog().equals("enterprise_22"), "CLEANUP_DATABASE_SCOPE_INVALID");
            validateDatabaseUrl(connection.getMetaData().getURL());
        }
        var readonly = new TransactionTemplate(manager);
        readonly.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        readonly.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        readonly.setReadOnly(true);
        java.time.Instant observedAt = readonly.execute(status -> {
            jdbc.execute("SET LOCAL TIME ZONE 'UTC'");
            require("UTC".equals(jdbc.queryForObject("SELECT current_setting('TimeZone')", String.class)), "CLEANUP_SESSION_TIMEZONE_NOT_UTC");
            require("on".equals(jdbc.queryForObject("SHOW transaction_read_only", String.class))
                    && "repeatable read".equals(jdbc.queryForObject("SHOW transaction_isolation", String.class)),
                    "CLEANUP_POST_BOOT_NOT_READONLY_RR");
            long tenant = preflight.plugins().getFirst().tenant();
            List<String> rows = jdbc.queryForList("SELECT row_to_json(p)::text FROM ab_plugin p WHERE p.tenant_id=? "
                    + "AND p.deleted_flag=FALSE ORDER BY p.pid", String.class, tenant);
            try { S06Current001PluginCleanupAdmission.automaticExactCurrentRoster(preflight.plugins(), rows, json); }
            catch (Exception invalid) { throw new IllegalStateException("CLEANUP_POST_BOOT_EXACT_ROSTER_CHANGED", invalid); }
            return java.time.Instant.now();
        });
        require(observedAt != null, "CLEANUP_POST_BOOT_OBSERVATION_MISSING");
        require(S06Current001PluginCleanupAdmission.hash(original).equals(required("S06_NATIVE_PLUGIN_CLEANUP_INPUT_SHA256")),
                "CLEANUP_POST_BOOT_ORIGINAL_INPUT_CHANGED");
        Path refresh = original.getParent().resolve("automatic-postboot-refresh-" + java.util.UUID.randomUUID());
        Files.createDirectory(refresh, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
        Path observation = refresh.resolve("fresh-roster-observation.json");
        write(observation, Map.of("kind", "s06-automatic-post-boot-roster-observation-v1", "capturedAtUtc", observedAt.toString(),
                "sessionTimezone", "UTC", "readOnly", true, "isolation", "REPEATABLE_READ", "originalIntent", automaticArtifactRef(original),
                "expectedPlugins", preflight.plugins(), "exactRawRosterUnchanged", true));
        Path derived = refresh.resolve("derived-input.json");
        var derivedInput = S06Current001PluginCleanupAdmission.automaticRefreshedInput(intent, observedAt);
        S06Current001PluginCleanupAdmission.automaticOnlyCapturedAtChanged(intent, derivedInput);
        write(derived, derivedInput);
        Files.setPosixFilePermissions(observation, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        Files.setPosixFilePermissions(derived, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        automaticPostBootRosterRefresh = Map.of("originalIntent", automaticArtifactRef(original),
                "freshRead", automaticArtifactRef(observation), "derivedInput", automaticArtifactRef(derived));
        // Only this fresh, fully revalidated result is deletion authority. The readonly preflight never is.
        var admission = S06Current001PluginCleanupAdmission.load(derived, S06Current001PluginCleanupAdmission.hash(derived), json);
        require(admission.plugins().equals(preflight.plugins()) && admission.output().equals(preflight.output())
                && admission.classificationSha256().equals(preflight.classificationSha256()), "CLEANUP_POST_BOOT_ADMISSION_CHANGED");
        uninstallAutomaticOwnedRegistrations(admission, crypto, "UTC");
        return true;
    }

    private Map<String, String> automaticArtifactRef(Path file) throws Exception {
        return Map.of("path", file.toRealPath().toString(), "sha256", S06Current001PluginCleanupAdmission.hash(file));
    }

    private void uninstallAutomaticOwnedRegistrations(S06Current001PluginCleanupAdmission.Admission admission,
                                                     Map<String, Object> crypto, String sessionTimezone) throws Exception {
        require(admission.schemaVersion() == 3 && !admission.plugins().isEmpty(), "CLEANUP_AUTOMATIC_ADMISSION_REQUIRED");
        require(automaticPostBootRosterRefresh != null && automaticPostBootRosterRefresh.size() == 3, "CLEANUP_POST_BOOT_AUTHORITY_MISSING");
        Set<Long> tenantSet = admission.plugins().stream().map(S06Current001PluginCleanupAdmission.Expected::tenant).collect(java.util.stream.Collectors.toSet());
        require(tenantSet.size() == 1, "CLEANUP_AUTOMATIC_ONE_FRESH_TENANT_ONLY");
        long tenant = tenantSet.iterator().next();
        Set<String> pids = admission.plugins().stream().map(S06Current001PluginCleanupAdmission.Expected::pid).collect(java.util.stream.Collectors.toSet());
        require(pids.size() == admission.plugins().size(), "CLEANUP_DUPLICATE_ACTUAL_PID");
        Files.createDirectory(admission.output());
        verifyCurrentPaused(admission.output());
        var caller = MetaContext.snapshot();
        var transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        Map<String, Object> completed;
        try {
            completed = transaction.execute(status -> {
                try {
                    // Tenant is derived from the new single-method setup, never pluginId-global or a fixed14 list.
                    List<String> locked = jdbc.queryForList("SELECT row_to_json(p)::text FROM ab_plugin p WHERE p.tenant_id=? "
                            + "AND p.deleted_flag=FALSE ORDER BY p.pid FOR UPDATE", String.class, tenant);
                    require(locked.size() == admission.plugins().size(), "CLEANUP_AUTOMATIC_ACTUAL_ROSTER_CHANGED");
                    Map<String, JsonNode> actual = new LinkedHashMap<>();
                    for (String raw : locked) {
                        JsonNode row = json.readTree(raw);
                        String pid = row.path("pid").asText();
                        var target = admission.plugins().stream().filter(expected -> expected.pid().equals(pid)).findFirst()
                                .orElseThrow(() -> new IllegalStateException("CLEANUP_AUTOMATIC_UNEXPECTED_PLUGIN"));
                        require(actual.put(pid, row) == null && row.path("tenant_id").longValue() == target.tenant()
                                && row.path("plugin_id").asText().equals(target.pluginId()) && row.path("namespace").asText().equals(target.namespace())
                                && row.path("version").asText().equals(target.version()) && row.path("status").asText().equals(target.status())
                                && row.path("deleted_flag").isBoolean() && !row.path("deleted_flag").booleanValue()
                                && digest(raw).equals(target.rowSha256()) && SourceBindingCanonical.hash(row.path("manifest")).equals(target.manifestSha256()),
                                "CLEANUP_AUTOMATIC_ACTUAL_TUPLE_CHANGED");
                    }
                    require(actual.keySet().equals(pids), "CLEANUP_AUTOMATIC_MISSING_PID");
                    Snapshot before = snapshot(List.of(tenant), pids);
                    write(admission.output().resolve("preview.json"), Map.of("sourceFamily", S06Current001PluginCleanupAdmission.AUTOMATIC_FAMILY,
                            "inputSha256", admission.inputSha256(), "expectedPlugins", admission.plugins(), "preservedBefore", summary(before),
                            "crypto", crypto, "sessionTimezone", sessionTimezone, "executed", false));
                    List<Map<String, Object>> results = new ArrayList<>();
                    for (var target : admission.plugins()) {
                        MetaContext.setContext(tenant, 0L, "SYSTEM", "s06-automatic-owned-lifecycle-cleanup");
                        var result = packages.uninstall(target.pid(), S06Current001PluginCleanupAdmission.options());
                        require(result != null && result.isSuccess() && target.pid().equals(result.getPluginPid())
                                && target.pluginId().equals(result.getPluginId()) && result.getConfigResult() == null
                                && result.getBackendResult() == null && result.getFrontendResult() == null,
                                "CLEANUP_OFFICIAL_PACKAGE_UNINSTALL_FAILED");
                        results.add(Map.of("tenantId", Long.toString(tenant), "pluginPid", target.pid(), "pluginId", target.pluginId(), "success", true));
                    }
                    for (var target : admission.plugins()) {
                        JsonNode after = json.readTree(jdbc.queryForObject("SELECT row_to_json(p)::text FROM ab_plugin p WHERE pid=? AND tenant_id=?",
                                String.class, target.pid(), tenant));
                        require(after.path("deleted_flag").isBoolean() && after.path("deleted_flag").booleanValue()
                                && normalizePlugin(after, pids).equals(normalizePlugin(actual.get(target.pid()), pids)),
                                "CLEANUP_AUTOMATIC_PLUGIN_OTHER_FIELDS_CHANGED");
                    }
                    Snapshot after = snapshot(List.of(tenant), pids);
                    require(before.equals(after), "CLEANUP_CHANGED_PRESERVED_FACTS");
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("officialResults", results); result.put("preservedBefore", summary(before)); result.put("preservedAfter", summary(after));
                    return result;
                } catch (Exception | AssertionError failure) {
                    status.setRollbackOnly(); throw new IllegalStateException("CLEANUP_AUTOMATIC_EXACT_ROSTER_ROLLED_BACK", failure);
                }
            });
        } finally { MetaContext.clear(); MetaContext.restore(caller); }
        require(completed != null, "CLEANUP_NO_TRANSACTION_RESULT");
        var readback = new TransactionTemplate(manager);
        readback.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        readback.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        readback.setReadOnly(true);
        var persisted = readback.execute(status -> {
            try {
                for (var target : admission.plugins()) require(Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT deleted_flag FROM ab_plugin WHERE pid=? AND tenant_id=?", Boolean.class, target.pid(), tenant)),
                        "CLEANUP_COMMIT_SOFT_DELETE_NOT_PERSISTED");
                return summary(snapshot(List.of(tenant), pids));
            } catch (Exception failure) { throw new IllegalStateException("CLEANUP_AUTOMATIC_READBACK_FAILED", failure); }
        });
        require(persisted != null && persisted.equals(completed.get("preservedAfter")), "CLEANUP_COMMIT_READBACK_CHANGED");
        completed.put("persistedAfter", persisted);
        completed.put("kind", "s06-automatic-owned-plugin-cleanup-receipt-v1");
        completed.put("postBootRosterRefresh", automaticPostBootRosterRefresh);
        completed.put("sourceFamily", S06Current001PluginCleanupAdmission.AUTOMATIC_FAMILY);
        completed.put("inputSha256", admission.inputSha256()); completed.put("sourceClassificationSha256", admission.classificationSha256());
        completed.put("softDeletedPluginRegistrations", admission.plugins().size()); completed.put("sessionTimezone", sessionTimezone);
        completed.put("businessDeliverySuccessClaimed", false); completed.put("stage", "automatic-owned-plugin-registration-cleanup-completed");
        write(admission.output().resolve("cleanup-receipt.json"), completed);
    }
    // END automatic sourceFamily official cleanup.
}
