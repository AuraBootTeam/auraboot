package com.auraboot.framework.notification.sourcebinding;

import com.auraboot.framework.plugin.dto.uninstall.UninstallDecision;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Hermetic fixed-scope admission only. No database or plugin service is invoked. */
class S06Current001PluginCleanupAdmissionTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void exactFourteenOwnedAndForeignPairsKeepTheirDistinctActualPids() throws Exception {
        Fixture fixture = fixture();
        var admitted = S06Current001PluginCleanupAdmission.expected(fixture.rows(), fixture.targets());
        S06Current001PluginRegistryCleanupIT.validateDatabaseUrl("jdbc:postgresql://127.0.0.1:5432/enterprise_22?prepareThreshold=0");
        for (String invalid : java.util.List.of("jdbc:postgresql://remote:5432/enterprise_22", "jdbc:postgresql://127.0.0.1:5433/enterprise_22",
                "jdbc:postgresql://127.0.0.1:5432/another", "jdbc:postgresql://user@127.0.0.1:5432/enterprise_22")) {
            assertThatThrownBy(() -> S06Current001PluginRegistryCleanupIT.validateDatabaseUrl(invalid))
                    .hasMessage("CLEANUP_JDBC_URL_INVALID");
        }
        assertThat(admitted).hasSize(14);
        assertThat(admitted.stream().map(S06Current001PluginCleanupAdmission.Expected::pid).distinct()).hasSize(14);
    }

    @Test
    void missingExtraDuplicateOrGlobalRowsCannotExpandTheCleanupScope() {
        Fixture fixture = fixture();
        ArrayNode missing = fixture.rows().deepCopy();
        missing.remove(0);
        assertThatThrownBy(() -> S06Current001PluginCleanupAdmission.expected(missing, fixture.targets()))
                .hasMessage("CLEANUP_EXACT_FOURTEEN_REQUIRED");
        ArrayNode extra = fixture.rows().deepCopy();
        extra.add(fixture.rows().get(0));
        assertThatThrownBy(() -> S06Current001PluginCleanupAdmission.expected(extra, fixture.targets()))
                .hasMessage("CLEANUP_EXACT_FOURTEEN_REQUIRED");
        ArrayNode duplicate = fixture.rows().deepCopy();
        duplicate.set(1, duplicate.get(0));
        assertThatThrownBy(() -> S06Current001PluginCleanupAdmission.expected(duplicate, fixture.targets()))
                .hasMessage("CLEANUP_EXPECTED_PLUGIN_OUTSIDE_SCOPE");
        ArrayNode global = fixture.rows().deepCopy();
        ((ObjectNode) global.get(0)).put("tenantId", "0");
        assertThatThrownBy(() -> S06Current001PluginCleanupAdmission.expected(global, fixture.targets()))
                .hasMessage("CLEANUP_TENANT_INVALID");
    }

    @Test
    void wrongPidNamespaceVersionStatusOrHashCannotBeApprovedByMatchingPrefix() {
        Fixture fixture = fixture();
        Map<String, String> invalid = Map.of("pluginPid", "foreign-pid", "namespace", "com.test.s06.other",
                "version", "2.0.0", "status", "disabled", "rowSha256", "bad", "manifestSha256", "bad");
        for (var change : invalid.entrySet()) {
            ArrayNode rows = fixture.rows().deepCopy();
            ((ObjectNode) rows.get(0)).put(change.getKey(), change.getValue());
            assertThatThrownBy(() -> S06Current001PluginCleanupAdmission.expected(rows, fixture.targets()))
                    .as(change.getKey()).hasMessage("CLEANUP_EXPECTED_PLUGIN_OUTSIDE_SCOPE");
        }
    }

    @Test
    void packageOptionsSkipEveryComponentAndDisableEveryDestructiveOption() {
        var options = S06Current001PluginCleanupAdmission.options();
        assertThat(options.isSkipConfig()).isTrue();
        assertThat(options.isSkipBackend()).isTrue();
        assertThat(options.isSkipFrontend()).isTrue();
        assertThat(options.isForce()).isFalse();
        assertThat(options.isRemoveAllData()).isFalse();
        assertThat(options.isRemoveBackendJar()).isFalse();
        assertThat(options.isRemoveFrontendAssets()).isFalse();
        assertThat(options.getDefaultDecision()).isEqualTo(UninstallDecision.SKIP);
    }

    @Test
    void normalizationAllowsOnlyTwoDeclaredRegistrationFieldsAndNeverForeignRowsOrManifestChanges() {
        ObjectNode original = json.createObjectNode().put("pid", "owned").put("tenant_id", 123L)
                .put("deleted_flag", false).put("updated_at", "before").put("manifest", "original");
        ObjectNode removed = original.deepCopy().put("deleted_flag", true).put("updated_at", "after");
        assertThat(S06Current001PluginRegistryCleanupIT.normalizePlugin(original, Set.of("owned")))
                .isEqualTo(S06Current001PluginRegistryCleanupIT.normalizePlugin(removed, Set.of("owned")));
        assertThat(S06Current001PluginRegistryCleanupIT.normalizePlugin(original, Set.of("another")))
                .isNotEqualTo(S06Current001PluginRegistryCleanupIT.normalizePlugin(removed, Set.of("another")));
        ObjectNode metadata = json.createObjectNode().put("lifecycle", "suspended").put("lastUsedAt", "before");
        ObjectNode touched = metadata.deepCopy().put("lastUsedAt", "after");
        assertThat(S06Current001PluginRegistryCleanupIT.stableMetadata(metadata))
                .isEqualTo(S06Current001PluginRegistryCleanupIT.stableMetadata(touched));
        touched.put("lifecycle", "open");
        assertThat(S06Current001PluginRegistryCleanupIT.stableMetadata(metadata))
                .isNotEqualTo(S06Current001PluginRegistryCleanupIT.stableMetadata(touched));
        removed.put("manifest", "changed");
        assertThat(S06Current001PluginRegistryCleanupIT.normalizePlugin(original, Set.of("owned")))
                .isNotEqualTo(S06Current001PluginRegistryCleanupIT.normalizePlugin(removed, Set.of("owned")));
    }

    private Fixture fixture() {
        Map<Long, S06Current001PluginCleanupAdmission.Target> targets = new LinkedHashMap<>();
        ArrayNode rows = json.createArrayNode();
        int index = 0;
        for (String namespace : S06Current001PluginCleanupAdmission.TRACES.keySet().stream().sorted().toList()) {
            for (int side = 0; side < 2; side++) {
                long tenant = ++index;
                String pid = "actual-test-pid-" + tenant;
                String plugin = "com.test.s06." + namespace;
                targets.put(tenant, new S06Current001PluginCleanupAdmission.Target(tenant, plugin, namespace, side == 0 ? pid : null));
                rows.addObject().put("tenantId", Long.toString(tenant)).put("pluginPid", pid).put("pluginId", plugin)
                        .put("namespace", namespace).put("version", "1.0.0").put("status", "installed")
                        .put("rowSha256", "a".repeat(64)).put("manifestSha256", "b".repeat(64));
            }
        }
        return new Fixture(rows, targets);
    }

    private record Fixture(ArrayNode rows, Map<Long, S06Current001PluginCleanupAdmission.Target> targets) {}
}
