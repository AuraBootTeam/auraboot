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

    @Test
    void currentRosterUsesExactDerivedTargetsRatherThanALiteralFourteen() {
        Fixture fixture = fixture();
        Map<Long,S06Current001PluginCleanupAdmission.Target> selected = new LinkedHashMap<>();
        ArrayNode rows = json.createArrayNode();
        for (int index = 0; index < 2; index++) {
            rows.add(fixture.rows().get(index));
            long tenant = Long.parseLong(rows.get(index).path("tenantId").asText());
            selected.put(tenant, fixture.targets().get(tenant));
        }
        assertThat(S06Current001PluginCleanupAdmission.expectedCurrent(rows,selected)).hasSize(selected.size());
        rows.remove(0);
        assertThatThrownBy(() -> S06Current001PluginCleanupAdmission.expectedCurrent(rows,selected))
                .hasMessage("CLEANUP_EXACT_DERIVED_ROSTER_REQUIRED");
        ArrayNode global = fixture.rows().deepCopy();((ObjectNode)global.get(0)).put("tenantId","0");
        assertThatThrownBy(() -> S06Current001PluginCleanupAdmission.expectedCurrent(global,fixture.targets()))
                .hasMessage("CLEANUP_TENANT_INVALID");
        ArrayNode wrong = fixture.rows().deepCopy();((ObjectNode)wrong.get(0)).put("pluginPid","wrong-pid");
        assertThatThrownBy(() -> S06Current001PluginCleanupAdmission.expectedCurrent(wrong,fixture.targets()))
                .hasMessage("CLEANUP_EXPECTED_PLUGIN_OUTSIDE_SCOPE");
    }

    @Test
    void rosterCaptureMustBeRecentExplicitUtcAndNeverFuture() {
        java.time.Instant now = java.time.Instant.parse("2026-10-18T08:00:00Z");
        S06Current001PluginCleanupAdmission.freshUtc("2026-10-18T07:59:59Z",now);
        for(String invalid : java.util.List.of("2026-10-18T07:54:59Z","2026-10-18T08:00:01Z","2026-10-18T15:59:59+08:00","","false"))
            assertThatThrownBy(() -> S06Current001PluginCleanupAdmission.freshUtc(invalid,now))
                    .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void actualXmlMustContainExactlyTheSevenSuccessfulOriginalMethods(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        StringBuilder xml = new StringBuilder("<testsuite tests=\"" + S06Current001PluginCleanupAdmission.METHODS.size() + "\" failures=\"0\" errors=\"0\" skipped=\"0\">");
        for (String method : S06Current001PluginCleanupAdmission.METHODS.stream().sorted().toList())
            xml.append("<testcase classname=\"com.auraboot.framework.notification.sourcebinding.RegisteredSourceBindingPostgresIT\" name=\"")
                    .append(method).append("()\"/>");
        xml.append("</testsuite>");
        java.nio.file.Path file = temp.resolve("native.xml");java.nio.file.Files.writeString(file,xml);
        S06Current001PluginCleanupAdmission.xmlMethods(file);
        for(String invalid : java.util.List.of(xml.toString().replaceFirst("/>","><failure/></testcase>"),
                xml.toString().replaceFirst("/>","><skipped/></testcase>"),
                xml.toString().replaceFirst("RegisteredSourceBindingPostgresIT","OtherTest"),
                xml.toString().replaceFirst("<testcase[^>]*/>",""))) {
            java.nio.file.Files.writeString(file,invalid);
            assertThatThrownBy(() -> S06Current001PluginCleanupAdmission.xmlMethods(file)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void currentTraceAndTeardownHashesDeriveOnlyTheirActualOwnedPairs(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        java.nio.file.Path attempt = temp.toRealPath();java.nio.file.Path traces = java.nio.file.Files.createDirectory(attempt.resolve("test-traces"));
        ObjectNode verdict = json.createObjectNode();ArrayNode pairs = verdict.putArray("originalTracePairs");
        int index = 0;
        for(String method : S06Current001PluginCleanupAdmission.METHODS.stream().sorted().toList()) {
            String namespace = "sn" + String.format("%012x",++index);
            ObjectNode facts = json.createObjectNode().put("namespace",namespace).put("test",method)
                    .put("businessStarted",true).put("stage","business-checkpoints-completed");
            facts.putObject("identities").put("tenantId",index*2).put("foreignTenantId",index*2+1);
            facts.putObject("approvedBinding").putArray("approvals").addObject().putArray("owners").addObject()
                    .put("pid","owned-"+index).put("pluginId","com.test.s06."+namespace).put("namespace",namespace).put("version","1.0.0");
            java.nio.file.Path trace = traces.resolve(namespace+".json");java.nio.file.Files.writeString(trace,json.writeValueAsString(facts));
            ObjectNode receipt = json.createObjectNode().put("tenantId",index*2).put("sealedTraceSha256",S06Current001PluginCleanupAdmission.hash(trace))
                    .put("inboxReceiptReplayPreserved",true).put("deliverySuccessClaimed",false);
            java.nio.file.Path teardown = traces.resolve(namespace+"-teardown.json");java.nio.file.Files.writeString(teardown,json.writeValueAsString(receipt));
            pairs.addObject().put("tracePath",trace.toString()).put("traceSha256",S06Current001PluginCleanupAdmission.hash(trace))
                    .put("receiptPath",teardown.toString()).put("receiptSha256",S06Current001PluginCleanupAdmission.hash(teardown));
        }
        var targets = S06Current001PluginCleanupAdmission.currentTargets(attempt,verdict,json);
        assertThat(targets).hasSize(pairs.size()*2);
        assertThat(targets.get(2L).ownPid()).isEqualTo("owned-1");
        ObjectNode changed = verdict.deepCopy();((ObjectNode)changed.path("originalTracePairs").get(0)).put("traceSha256","0".repeat(64));
        assertThatThrownBy(() -> S06Current001PluginCleanupAdmission.currentTargets(attempt,changed,json))
                .hasMessage("CLEANUP_INPUT_NOT_SEALED_OWNED");
        ObjectNode missing = verdict.deepCopy();((ArrayNode)missing.path("originalTracePairs")).remove(0);
        assertThatThrownBy(() -> S06Current001PluginCleanupAdmission.currentTargets(attempt,missing,json))
                .hasMessage("CLEANUP_ORIGINAL_TRACE_ROSTER_INVALID");
        ObjectNode duplicate = verdict.deepCopy();((ArrayNode)duplicate.path("originalTracePairs")).set(1,duplicate.path("originalTracePairs").get(0));
        assertThatThrownBy(() -> S06Current001PluginCleanupAdmission.currentTargets(attempt,duplicate,json))
                .hasMessage("CLEANUP_ORIGINAL_METHOD_INVALID");
        java.nio.file.Path teardown = java.nio.file.Path.of(pairs.get(0).path("receiptPath").asText());
        ObjectNode badReceipt = (ObjectNode)json.readTree(teardown.toFile());badReceipt.put("sealedTraceSha256","0".repeat(64));
        java.nio.file.Files.writeString(teardown,json.writeValueAsString(badReceipt));
        ((ObjectNode)pairs.get(0)).put("receiptSha256",S06Current001PluginCleanupAdmission.hash(teardown));
        assertThatThrownBy(() -> S06Current001PluginCleanupAdmission.currentTargets(attempt,verdict,json))
                .hasMessage("CLEANUP_TEARDOWN_TRACE_MISMATCH");
    }

    @Test
    void completeCurrentLoadChecksTheWholeSealedFilesystemChain(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        CurrentFixture fixture = currentFixture(temp);
        var admission = S06Current001PluginCleanupAdmission.load(fixture.input(),S06Current001PluginCleanupAdmission.hash(fixture.input()),json,fixture.root(),fixture.now());
        assertThat(admission.schemaVersion()).isEqualTo(2);
        assertThat(admission.plugins()).hasSize(S06Current001PluginCleanupAdmission.METHODS.size()*2);
        assertThat(admission.classificationSha256()).isEqualTo(S06Current001PluginCleanupAdmission.hash(fixture.classification()));
        assertThat(java.nio.file.Files.exists(admission.output())).isFalse();
        assertThatThrownBy(() -> S06Current001PluginCleanupAdmission.load(fixture.input(),"0".repeat(64),json,fixture.root(),fixture.now()))
                .hasMessage("CLEANUP_INPUT_NOT_SEALED_OWNED");
    }

    @Test
    void completeLoadRejectsRehashedSemanticForgeryAndLiveWorkers(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        for(String variant : java.util.List.of("failed-classification","wrong-xml-class","missing-trace","bad-teardown",
                "missing-row","extra-row","wrong-owner","global-tenant","stale","open","extra-process","existing-output","unknown-input")) {
            CurrentFixture fixture = currentFixture(java.nio.file.Files.createDirectory(temp.resolve(variant)));
            ObjectNode input = (ObjectNode)json.readTree(fixture.input().toFile());
            ObjectNode verdict = (ObjectNode)json.readTree(fixture.classification().toFile());
            ObjectNode scope = (ObjectNode)json.readTree(fixture.scope().toFile());
            ObjectNode paused = (ObjectNode)json.readTree(fixture.paused().toFile());
            switch(variant) {
                case "failed-classification" -> verdict.put("productFacetPassed",false);
                case "wrong-xml-class" -> java.nio.file.Files.writeString(fixture.xml(),java.nio.file.Files.readString(fixture.xml()).replace("RegisteredSourceBindingPostgresIT","ForgedTest"));
                case "missing-trace" -> ((ArrayNode)verdict.get("originalTracePairs")).remove(0);
                case "bad-teardown" -> {
                    ObjectNode pair = (ObjectNode)verdict.path("originalTracePairs").get(0);
                    java.nio.file.Path receipt = java.nio.file.Path.of(pair.path("receiptPath").asText());
                    ObjectNode data = (ObjectNode)json.readTree(receipt.toFile());data.put("sealedTraceSha256","0".repeat(64));
                    java.nio.file.Files.writeString(receipt,json.writeValueAsString(data));pair.put("receiptSha256",S06Current001PluginCleanupAdmission.hash(receipt));
                }
                case "missing-row" -> ((ArrayNode)input.get("expectedPlugins")).remove(0);
                case "extra-row" -> ((ArrayNode)input.get("expectedPlugins")).add(input.get("expectedPlugins").get(0).deepCopy());
                case "wrong-owner" -> ((ObjectNode)input.path("expectedPlugins").get(0)).put("pluginPid","unrelated");
                case "global-tenant" -> ((ObjectNode)input.path("expectedPlugins").get(0)).put("tenantId","0");
                case "stale" -> input.put("pluginRosterCapturedAtUtc",fixture.now().minusSeconds(301).toString());
                case "open" -> ((ObjectNode)paused.get("metadata")).put("lifecycle","open");
                case "extra-process" -> ((ArrayNode)paused.get("processes")).addObject().put("key","backend");
                case "existing-output" -> java.nio.file.Files.createDirectory(java.nio.file.Path.of(input.path("outputDirectory").asText()));
                case "unknown-input" -> input.put("allowAnyPlugin",true);
                default -> throw new AssertionError();
            }
            verdict.put("xmlSha256",S06Current001PluginCleanupAdmission.hash(fixture.xml()));
            java.nio.file.Files.writeString(fixture.classification(),json.writeValueAsString(verdict));
            scope.put("classificationSha256",S06Current001PluginCleanupAdmission.hash(fixture.classification()));
            scope.put("xmlSha256",S06Current001PluginCleanupAdmission.hash(fixture.xml()));
            java.nio.file.Files.writeString(fixture.scope(),json.writeValueAsString(scope));
            java.nio.file.Files.writeString(fixture.paused(),json.writeValueAsString(paused));
            input.put("nativeScopeSha256",S06Current001PluginCleanupAdmission.hash(fixture.scope()));
            input.put("pausedVerificationSha256",S06Current001PluginCleanupAdmission.hash(fixture.paused()));
            java.nio.file.Files.writeString(fixture.input(),json.writeValueAsString(input));
            assertThatThrownBy(() -> S06Current001PluginCleanupAdmission.load(fixture.input(),S06Current001PluginCleanupAdmission.hash(fixture.input()),json,fixture.root(),fixture.now()))
                    .as(variant).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void completeLoadRejectsChangedReferentsEvenWhenTheTopInputIsSealed(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        for(String variant : java.util.List.of("scope","classification","xml","trace","teardown","paused")) {
            CurrentFixture fixture = currentFixture(java.nio.file.Files.createDirectory(temp.resolve(variant)));
            ObjectNode verdict = (ObjectNode)json.readTree(fixture.classification().toFile());
            java.nio.file.Path target = switch(variant) {
                case "scope" -> fixture.scope();case "classification" -> fixture.classification();case "xml" -> fixture.xml();case "paused" -> fixture.paused();
                case "trace" -> java.nio.file.Path.of(verdict.path("originalTracePairs").get(0).path("tracePath").asText());
                case "teardown" -> java.nio.file.Path.of(verdict.path("originalTracePairs").get(0).path("receiptPath").asText());
                default -> throw new AssertionError();
            };
            java.nio.file.Files.writeString(target,java.nio.file.Files.readString(target)+" ");
            assertThatThrownBy(() -> S06Current001PluginCleanupAdmission.load(fixture.input(),S06Current001PluginCleanupAdmission.hash(fixture.input()),json,fixture.root(),fixture.now()))
                    .as(variant).isInstanceOf(IllegalStateException.class);
        }
    }

    private CurrentFixture currentFixture(java.nio.file.Path directory) throws Exception {
        java.nio.file.Path root = directory.toRealPath();java.nio.file.Path attempt = java.nio.file.Files.createDirectory(root.resolve("attempt"));
        java.nio.file.Path traces = java.nio.file.Files.createDirectory(attempt.resolve("test-traces"));
        ObjectNode verdict = json.createObjectNode().put("originalMethodsPassed",S06Current001PluginCleanupAdmission.METHODS.size()).put("productFacetPassed",true);
        ArrayNode pairs = verdict.putArray("originalTracePairs");ArrayNode expected = json.createArrayNode();
        StringBuilder xmlBody = new StringBuilder("<testsuite tests=\"7\" failures=\"0\" errors=\"0\" skipped=\"0\">");int index=0;
        for(String method : S06Current001PluginCleanupAdmission.METHODS.stream().sorted().toList()) {
            String namespace="sn"+String.format("%012x",++index);String plugin="com.test.s06."+namespace;
            ObjectNode facts=json.createObjectNode().put("namespace",namespace).put("test",method).put("businessStarted",true).put("stage","business-checkpoints-completed");
            facts.putObject("identities").put("tenantId",index*2).put("foreignTenantId",index*2+1);
            facts.putObject("approvedBinding").putArray("approvals").addObject().putArray("owners").addObject()
                    .put("pid","own-"+index).put("pluginId",plugin).put("namespace",namespace).put("version","1.0.0");
            java.nio.file.Path trace=traces.resolve(namespace+".json");java.nio.file.Files.writeString(trace,json.writeValueAsString(facts));
            ObjectNode receipt=json.createObjectNode().put("tenantId",index*2).put("sealedTraceSha256",S06Current001PluginCleanupAdmission.hash(trace))
                    .put("inboxReceiptReplayPreserved",true).put("deliverySuccessClaimed",false);
            java.nio.file.Path teardown=traces.resolve(namespace+"-teardown.json");java.nio.file.Files.writeString(teardown,json.writeValueAsString(receipt));
            pairs.addObject().put("tracePath",trace.toString()).put("traceSha256",S06Current001PluginCleanupAdmission.hash(trace))
                    .put("receiptPath",teardown.toString()).put("receiptSha256",S06Current001PluginCleanupAdmission.hash(teardown));
            for(int side=0;side<2;side++) expected.addObject().put("tenantId",Integer.toString(index*2+side)).put("pluginPid",(side==0?"own-":"foreign-")+index)
                    .put("pluginId",plugin).put("namespace",namespace).put("version","1.0.0").put("status","installed")
                    .put("rowSha256","a".repeat(64)).put("manifestSha256","b".repeat(64));
            xmlBody.append("<testcase classname=\"com.auraboot.framework.notification.sourcebinding.RegisteredSourceBindingPostgresIT\" name=\"").append(method).append("()\"/>");
        }
        java.nio.file.Path xml=attempt.resolve("native.xml");java.nio.file.Files.writeString(xml,xmlBody.append("</testsuite>"));
        verdict.put("xmlSha256",S06Current001PluginCleanupAdmission.hash(xml));
        java.nio.file.Path classification=attempt.resolve("classification.json");java.nio.file.Files.writeString(classification,json.writeValueAsString(verdict));
        ObjectNode scope=json.createObjectNode().put("kind","s06-owned-native-plugin-cleanup-scope-v1").put("schemaVersion",1)
                .put("runtime",S06Current001PluginCleanupAdmission.RUNTIME).put("originalAttemptPath",attempt.toString())
                .put("classificationSha256",S06Current001PluginCleanupAdmission.hash(classification)).put("xmlSha256",S06Current001PluginCleanupAdmission.hash(xml));
        java.nio.file.Path scopePath=root.resolve("scope.json");java.nio.file.Files.writeString(scopePath,json.writeValueAsString(scope));
        ObjectNode paused=json.createObjectNode().put("runtime",S06Current001PluginCleanupAdmission.RUNTIME).put("verdict","ok");paused.putArray("findings");
        paused.putObject("metadata").put("lifecycle","suspended");paused.putArray("processes");paused.putObject("ports").putObject("backend").put("status","stopped").putArray("pids");
        java.nio.file.Path pausedPath=root.resolve("paused.json");java.nio.file.Files.writeString(pausedPath,json.writeValueAsString(paused));
        java.time.Instant now=java.time.Instant.parse("2026-10-18T08:00:00Z");
        ObjectNode input=json.createObjectNode().put("kind","s06-owned-native-plugin-cleanup-admission-v2").put("schemaVersion",2)
                .put("runtime",S06Current001PluginCleanupAdmission.RUNTIME).put("database","enterprise_22").put("redisDatabase",13).put("execute",true)
                .put("nativeScopePath",scopePath.toString()).put("nativeScopeSha256",S06Current001PluginCleanupAdmission.hash(scopePath))
                .put("pluginRosterCapturedAtUtc",now.toString()).put("outputDirectory",root.resolve("new-output").toString())
                .put("pausedVerificationPath",pausedPath.toString()).put("pausedVerificationSha256",S06Current001PluginCleanupAdmission.hash(pausedPath));
        input.set("expectedPlugins",expected);java.nio.file.Path inputPath=root.resolve("input.json");java.nio.file.Files.writeString(inputPath,json.writeValueAsString(input));
        return new CurrentFixture(root,inputPath,scopePath,classification,xml,pausedPath,now);
    }

    private record CurrentFixture(java.nio.file.Path root,java.nio.file.Path input,java.nio.file.Path scope,
            java.nio.file.Path classification,java.nio.file.Path xml,java.nio.file.Path paused,java.time.Instant now) {}

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
    // BEGIN automatic cleanup contracts.
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path automaticRoot;
    private record AutomaticFixture(ObjectNode setup, ObjectNode binding, ArrayNode rows) {}
    private AutomaticFixture automaticFixture() throws Exception {
        var setup = json.createObjectNode().put("tenantId", 987L).put("formalImportedDirectories", 23);
        ArrayNode directories = json.createArrayNode(), imports = setup.putArray("formalImportDiagnostics"), rows = json.createArrayNode();
        for (int i = 0; i < 23; i++) {
            var directory = java.nio.file.Files.createDirectory(automaticRoot.resolve("owner-" + i));
            var manifest = json.createObjectNode().put("pluginId", "com.test.automatic.owner" + i).put("namespace", "auto_" + i).put("version", "1.0.0");
            var file = directory.resolve("plugin.json"); java.nio.file.Files.writeString(file, manifest.toString());
            directories.addObject().put("path", directory.toString()).put("pluginJsonSha256", S06Current001PluginCleanupAdmission.hash(file));
            imports.addObject().put("directory", directory.toString()).put("previewValid", true).put("executeSuccess", true);
            rows.addObject().put("tenantId", "987").put("pluginPid", "owned-" + i).put("pluginId", "com.test.automatic.owner" + i)
                    .put("namespace", "auto_" + i).put("version", "1.0.0").put("status", "installed").put("rowSha256", "a".repeat(64)).put("manifestSha256", "b".repeat(64));
        }
        setup.set("orderedImportDirectories", directories);
        var binding = json.createObjectNode(); binding.set("orderedImportDirectories", directories.deepCopy());
        return new AutomaticFixture(setup, binding, rows);
    }
    @Test
    void automaticRosterDerivesTwentyThreeActualOwnersInOneFreshTenantWithoutFixedFourteen() throws Exception {
        var f = automaticFixture();
        var result = S06Current001PluginCleanupAdmission.automaticExpected(f.rows(), f.setup(), f.binding(), json);
        assertThat(result).hasSize(23);
        assertThat(result.stream().map(S06Current001PluginCleanupAdmission.Expected::tenant).distinct()).containsExactly(987L);
        var enabled = f.rows().deepCopy(); ((ObjectNode) enabled.get(0)).put("status", "enabled");
        assertThat(S06Current001PluginCleanupAdmission.automaticExpected(enabled, f.setup(), f.binding(), json).getFirst().status()).isEqualTo("enabled");
    }
    @Test
    void automaticRosterRejectsForeignFrozenFixtureMissingExtraDuplicateAndDisabledTargets() throws Exception {
        var f = automaticFixture();
        for (var mutation : Map.of("tenantId", "123", "pluginId", "com.auraboot.unrelated", "pluginPid", "owned-1",
                "namespace", "unrelated", "version", "2.0", "status", "disabled", "rowSha256", "invalid").entrySet()) {
            var rows = f.rows().deepCopy(); ((ObjectNode) rows.get(0)).put(mutation.getKey(), mutation.getValue());
            assertThatThrownBy(() -> S06Current001PluginCleanupAdmission.automaticExpected(rows, f.setup(), f.binding(), json))
                    .as(mutation.getKey()).hasMessage("CLEANUP_AUTOMATIC_PLUGIN_OUTSIDE_OWNED_TENANT");
        }
        var missing = f.rows().deepCopy(); missing.remove(0);
        assertThatThrownBy(() -> S06Current001PluginCleanupAdmission.automaticExpected(missing, f.setup(), f.binding(), json))
                .hasMessage("CLEANUP_AUTOMATIC_EXACT_PLUGIN_ROSTER_REQUIRED");
        var extra = f.rows().deepCopy(); extra.add(f.rows().get(0));
        assertThatThrownBy(() -> S06Current001PluginCleanupAdmission.automaticExpected(extra, f.setup(), f.binding(), json))
                .hasMessage("CLEANUP_AUTOMATIC_EXACT_PLUGIN_ROSTER_REQUIRED");
        ((ObjectNode) f.setup().path("formalImportDiagnostics").get(22)).put("executeSuccess", false);
        assertThatThrownBy(() -> S06Current001PluginCleanupAdmission.automaticExpected(f.rows(), f.setup(), f.binding(), json))
                .hasMessage("CLEANUP_AUTOMATIC_FORMAL_IMPORT_NOT_PASS");
    }
    @Test
    void automaticXmlAcceptsOnlyTheNewSingleSuccessfulMethodAndNeverTheOldSevenIdentity() throws Exception {
        String header = "<testsuite tests='1' failures='0' errors='0' skipped='0'>";
        String body = "<testcase classname='" + S06Current001PluginCleanupAdmission.AUTOMATIC_CLASS + "' name='"
                + S06Current001PluginCleanupAdmission.AUTOMATIC_METHOD + "()'/></testsuite>";
        var file = automaticRoot.resolve("native.xml"); java.nio.file.Files.writeString(file, header + body);
        S06Current001PluginCleanupAdmission.automaticXml(file);
        java.nio.file.Files.writeString(file, header + body.replace(S06Current001PluginCleanupAdmission.AUTOMATIC_METHOD,
                "actualImportedMetadataExplicitRecipientsReplayAndConsumePersistExactlyOnce"));
        assertThatThrownBy(() -> S06Current001PluginCleanupAdmission.automaticXml(file)).hasMessage("CLEANUP_AUTOMATIC_XML_WRONG_SELECTOR");
        java.nio.file.Files.writeString(file, (header + body).replace("skipped='0'", "skipped='1'"));
        assertThatThrownBy(() -> S06Current001PluginCleanupAdmission.automaticXml(file)).hasMessage("CLEANUP_AUTOMATIC_XML_NOT_PASS");
    }
    @Test
    void futureNullRefsChangedPhysicalHashesAndEmptyCompiledMapsCannotQualifyCleanup() throws Exception {
        assertThatThrownBy(() -> S06Current001PluginCleanupAdmission.automaticRef(json.getNodeFactory().nullNode(), automaticRoot)).hasMessage("CLEANUP_SCHEMA_INVALID");
        var file = automaticRoot.resolve("proof.json"); java.nio.file.Files.writeString(file, "{}");
        var ref = json.createObjectNode().put("path", file.toString()).put("sha256", S06Current001PluginCleanupAdmission.hash(file));
        assertThat(S06Current001PluginCleanupAdmission.automaticRef(ref, automaticRoot)).isEqualTo(file.toRealPath());
        java.nio.file.Files.writeString(file, "{\"changed\":true}");
        assertThatThrownBy(() -> S06Current001PluginCleanupAdmission.automaticRef(ref, automaticRoot)).hasMessage("CLEANUP_INPUT_NOT_SEALED_OWNED");
        assertThatThrownBy(() -> S06Current001PluginCleanupAdmission.automaticKeys(json.createObjectNode())).hasMessage("CLEANUP_AUTOMATIC_COMPLETE_MAP_REQUIRED");
        assertThatThrownBy(() -> S06Current001PluginCleanupAdmission.automaticFiles(automaticRoot.resolve("missing"), ".class", ""))
                .hasMessage("CLEANUP_AUTOMATIC_CLASS_ROOT_MISSING");
        java.nio.file.Files.writeString(file, "{} {}");
        assertThatThrownBy(() -> S06Current001PluginCleanupAdmission.automaticStrict(file, json)).isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
        java.nio.file.Files.writeString(file, "{\"duplicate\":1,\"duplicate\":2}");
        assertThatThrownBy(() -> S06Current001PluginCleanupAdmission.automaticStrict(file, json)).isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }
    @Test
    void duplicateNestedCoreJarsCannotMakeACompleteClassSealAmbiguous() throws Exception {
        var boot = automaticRoot.resolve("boot.jar");
        try (var out = new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(boot))) {
            for (String name : java.util.List.of("BOOT-INF/lib/auraboot-core-1.jar", "BOOT-INF/lib/auraboot-core-2.jar")) {
                out.putNextEntry(new java.util.zip.ZipEntry(name)); out.write(new byte[]{0}); out.closeEntry();
            }
        }
        assertThatThrownBy(() -> S06Current001PluginCleanupAdmission.automaticCoreJarClasses(boot)).hasMessage("CLEANUP_AUTOMATIC_UNIQUE_CORE_JAR_REQUIRED");
    }
    @Test
    void automaticCleanupCannotReinterpretAQuoteOnlyOrUnsealedIntentPhaseAsShipmentDelivery() {
        var receipt = json.createObjectNode(); receipt.putArray("phases").addObject().put("kind", "quote");
        assertThatThrownBy(() -> S06Current001PluginCleanupAdmission.automaticPhases(receipt, json.createObjectNode(), automaticRoot, json))
                .hasMessage("CLEANUP_AUTOMATIC_PHASES_INVALID");
        ((ArrayNode) receipt.path("phases")).addObject().put("kind", "shipment").put("automaticTransactions", 2).put("deliveryAndReplayVerified", true);
        assertThatThrownBy(() -> S06Current001PluginCleanupAdmission.automaticPhases(receipt, json.createObjectNode(), automaticRoot, json))
                .hasMessage("CLEANUP_AUTOMATIC_PHASES_INVALID");
    }
    @Test
    void cleanupV1V2AndOfficialTransactionBodiesRemainPinnedAfterWorkspacePortability() throws Exception {
        java.nio.file.Path root = java.nio.file.Path.of("src/test/java/com/auraboot/framework/notification/sourcebinding");
        if (!java.nio.file.Files.isDirectory(root)) root = java.nio.file.Path.of("platform").resolve(root);
        for (var entry : Map.of("S06Current001PluginCleanupAdmission.java", "502c0816da66376c528a4fe040f3bd42859da13e601ac2604a298cc6fdbe6b14", "S06Current001PluginRegistryCleanupIT.java", "e10ecca8ce72f442a88e2a00904c0c0dd4f22afee7c81224633fc556f76823b5").entrySet()) {
            String source = java.nio.file.Files.readString(root.resolve(entry.getKey()));
            String start = entry.getKey().equals("S06Current001PluginCleanupAdmission.java") ? "    // BEGIN automatic sourceFamily cleanup admission.\n" : "    // BEGIN automatic sourceFamily official cleanup.\n";
            String end = entry.getKey().equals("S06Current001PluginCleanupAdmission.java") ? "    // END automatic sourceFamily cleanup admission.\n" : "    // END automatic sourceFamily official cleanup.\n";
            int first = source.indexOf(start), last = source.indexOf(end, first);
            assertThat(first).isNotNegative(); assertThat(last).isGreaterThan(first);
            String original = source.substring(0, first) + source.substring(last + end.length());
            if (entry.getKey().equals("S06Current001PluginCleanupAdmission.java")) original = original.replace("        if (value.path(\"kind\").asText().equals(\"s06-automatic-owned-plugin-cleanup-admission-v3\"))\n            return automatic(automaticStrict(file, json), evidence, hash(file), json, now);\n", "");
            else original = original.replace("        if (uninstallAutomaticAfterExactPostBootRefresh()) return;\n", "").replace("        if (admission.schemaVersion() == 3) {\n            uninstallAutomaticOwnedRegistrations(admission, crypto, sessionTimezone);\n            return;\n        }\n", "");
            assertThat(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(original.getBytes(java.nio.charset.StandardCharsets.UTF_8)))).isEqualTo(entry.getValue());
        }
    }
    @Test
    void automaticTrackedMainIncludesResourcesAndRejectsMissingOrSymlinkedTrackedResource() throws Exception {
        java.nio.file.Path root = java.nio.file.Files.createDirectory(automaticRoot.resolve("tracked"));
        assertThat(new ProcessBuilder("git", "init", "--quiet", root.toString()).start().waitFor()).isZero();
        java.nio.file.Path javaFile = root.resolve("platform/src/main/java/Example.java"), resource = root.resolve("platform/platform-app/src/main/resources/application.yml");
        java.nio.file.Files.createDirectories(javaFile.getParent()); java.nio.file.Files.createDirectories(resource.getParent());
        java.nio.file.Files.writeString(javaFile, "class Example {}\n"); java.nio.file.Files.writeString(resource, "guard: exact\n");
        assertThat(new ProcessBuilder("git", "-C", root.toString(), "add", "platform/src/main").start().waitFor()).isZero();
        var map = S06Current001PluginCleanupAdmission.automaticMainSources(root);
        assertThat(map).containsOnlyKeys("platform/src/main/java/Example.java", "platform/platform-app/src/main/resources/application.yml");
        assertThat(map.get("platform/platform-app/src/main/resources/application.yml")).isEqualTo(S06Current001PluginCleanupAdmission.hash(resource));
        java.nio.file.Files.delete(resource);
        assertThatThrownBy(() -> S06Current001PluginCleanupAdmission.automaticMainSources(root))
                .hasMessage("CLEANUP_AUTOMATIC_TRACKED_MAIN_MISSING_OR_DUPLICATED");
        java.nio.file.Files.createSymbolicLink(resource, javaFile);
        assertThatThrownBy(() -> S06Current001PluginCleanupAdmission.automaticMainSources(root)).hasMessage("CLEANUP_AUTOMATIC_SOURCE_SYMLINK");
    }
    @Test
    void automaticPausedRequiresSuspendedExactSoleErpAndNoPublicWorker() {
        var pins = json.createObjectNode(); var paused = json.createObjectNode().put("runtime", S06Current001PluginCleanupAdmission.RUNTIME).put("verdict", "ok");
        paused.putArray("findings"); paused.putObject("metadata").put("lifecycle", "suspended");
        paused.putObject("environment").put("POSTGRES_DB", "enterprise_22").put("REDIS_DATABASE", "13");
        paused.putObject("ports").putObject("backend").put("status", "stopped").putArray("pids");
        var process = paused.putArray("processes").addObject().put("key", "erp-fixture").put("status", "ok");
        var expected = process.putObject("expected").put("pid", 123).put("cwd", "/owned/quote").put("commandHash", "a").put("tokenHash", "b");
        process.set("actual", expected.deepCopy().put("alive", true));
        var sources = paused.putArray("sources");
        for (String key : java.util.List.of("oss", "quote", "enterprise", "plugins", "crm")) {
            pins.put(key, "a".repeat(40)); var source = sources.addObject().put("key", key).put("status", "ok");
            var identity = json.createObjectNode().put("commit", "a".repeat(40)).put("dirtyFingerprint", "clean");
            source.set("expected", identity); source.set("actual", identity.deepCopy());
        }
        S06Current001PluginCleanupAdmission.automaticPaused(paused, pins);
        var zero = paused.deepCopy(); ((ArrayNode) zero.path("processes")).removeAll();
        assertThatThrownBy(() -> S06Current001PluginCleanupAdmission.automaticPaused(zero, pins)).hasMessage("CLEANUP_PUBLIC_WORKERS_NOT_ISOLATED");
        var backend = paused.deepCopy(); ((ObjectNode) backend.path("processes").get(0)).put("key", "backend");
        assertThatThrownBy(() -> S06Current001PluginCleanupAdmission.automaticPaused(backend, pins)).hasMessage("CLEANUP_FOREIGN_PROCESS");
        var open = paused.deepCopy(); ((ObjectNode) open.path("metadata")).put("lifecycle", "open");
        assertThatThrownBy(() -> S06Current001PluginCleanupAdmission.automaticPaused(open, pins)).hasMessage("CLEANUP_PUBLIC_WORKERS_NOT_ISOLATED");
        var token = paused.deepCopy(); ((ObjectNode) token.path("processes").get(0).path("actual")).put("tokenHash", "changed");
        assertThatThrownBy(() -> S06Current001PluginCleanupAdmission.automaticPaused(token, pins)).hasMessage("CLEANUP_ERP_IDENTITY_MISMATCH");
    }
    private record PostBootRows(java.util.List<S06Current001PluginCleanupAdmission.Expected> expected, java.util.List<String> raw) {}
    private PostBootRows postBootRows() throws Exception {
        var expected = new java.util.ArrayList<S06Current001PluginCleanupAdmission.Expected>();
        var raw = new java.util.ArrayList<String>();
        for (int i = 0; i < 23; i++) {
            var manifest = json.createObjectNode().put("pluginId", "com.test.owner" + i);
            var row = json.createObjectNode().put("pid", "p" + i).put("tenant_id", 987).put("plugin_id", "com.test.owner" + i)
                    .put("namespace", "n" + i).put("version", "1.0.0").put("status", "installed").put("deleted_flag", false);
            row.set("manifest", manifest); String text = row.toString(); raw.add(text);
            expected.add(new S06Current001PluginCleanupAdmission.Expected(987, "p" + i, "com.test.owner" + i, "n" + i, "1.0.0", "installed",
                    java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                    SourceBindingCanonical.hash(manifest)));
        }
        return new PostBootRows(expected, raw);
    }
    @Test
    void postBootReadonlyRosterAcceptsOnlyAllTwentyThreeOriginalRawTuples() throws Exception {
        var f = postBootRows();
        S06Current001PluginCleanupAdmission.automaticExactCurrentRoster(f.expected(), f.raw(), json);
        for (String field : java.util.List.of("pid", "tenant_id", "plugin_id", "namespace", "version", "status", "deleted_flag", "manifest", "updated_at")) {
            var rows = new java.util.ArrayList<>(f.raw());
            var changed = (ObjectNode) json.readTree(rows.get(0)); changed.put(field, "changed"); rows.set(0, changed.toString());
            assertThatThrownBy(() -> S06Current001PluginCleanupAdmission.automaticExactCurrentRoster(f.expected(), rows, json))
                    .as(field).hasMessage("CLEANUP_POST_BOOT_EXACT_ROSTER_CHANGED");
        }
        var missing = new java.util.ArrayList<>(f.raw()); missing.remove(0);
        assertThatThrownBy(() -> S06Current001PluginCleanupAdmission.automaticExactCurrentRoster(f.expected(), missing, json)).hasMessage("CLEANUP_POST_BOOT_EXACT_ROSTER_CHANGED");
        var duplicate = new java.util.ArrayList<>(f.raw()); duplicate.set(0, duplicate.get(1));
        assertThatThrownBy(() -> S06Current001PluginCleanupAdmission.automaticExactCurrentRoster(f.expected(), duplicate, json)).hasMessage("CLEANUP_POST_BOOT_EXACT_ROSTER_CHANGED");
    }
    @Test
    void postBootDerivedInputChangesOnlyActualObservationTimeAndNeverTheWhitelist() {
        var original = json.createObjectNode().put("kind", "s06-automatic-owned-plugin-cleanup-admission-v3")
                .put("pluginRosterCapturedAtUtc", "2026-10-19T00:00:00Z"); original.putArray("expectedPlugins").addObject().put("pluginPid", "sealed-original");
        var derived = S06Current001PluginCleanupAdmission.automaticRefreshedInput(original, java.time.Instant.parse("2026-10-19T01:00:00Z"));
        assertThat(original.path("pluginRosterCapturedAtUtc").asText()).isEqualTo("2026-10-19T00:00:00Z");
        assertThat(derived.path("pluginRosterCapturedAtUtc").asText()).isEqualTo("2026-10-19T01:00:00Z");
        ((ObjectNode) derived.path("expectedPlugins").get(0)).put("pluginPid", "rebaseline");
        assertThatThrownBy(() -> S06Current001PluginCleanupAdmission.automaticOnlyCapturedAtChanged(original, derived)).hasMessage("CLEANUP_POST_BOOT_INPUT_CHANGED");
    }
    @Test
    void postBootPreflightIsNotDeletionAuthorityAndOriginalFutureTimeIsAlwaysRejected() {
        assertThat(S06Current001PluginCleanupAdmission.AutomaticPreflight.class)
                .isNotEqualTo(S06Current001PluginCleanupAdmission.Admission.class);
        var now = java.time.Instant.parse("2026-10-19T01:00:00Z");
        S06Current001PluginCleanupAdmission.automaticOriginalTimestamp("2026-10-19T00:00:00Z", now);
        assertThatThrownBy(() -> S06Current001PluginCleanupAdmission.freshUtc("2026-10-19T00:00:00Z", now)).hasMessage("CLEANUP_ROSTER_NOT_FRESH_UTC");
        for (String bad : java.util.List.of("2026-10-19T01:00:01Z", "invalid", "2026-10-19T01:00:00+00:00"))
            assertThatThrownBy(() -> S06Current001PluginCleanupAdmission.automaticOriginalTimestamp(bad, now)).hasMessage("CLEANUP_POST_BOOT_ORIGINAL_TIME_INVALID");
        assertThatThrownBy(() -> S06Current001PluginCleanupAdmission.freshUtc("2026-10-19T01:00:01Z", now)).hasMessage("CLEANUP_ROSTER_NOT_FRESH_UTC");
        S06Current001PluginCleanupAdmission.freshUtc(now.toString(), now);
    }
    // END automatic cleanup contracts.
}
