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
}
