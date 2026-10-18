package com.auraboot.framework.notification.sourcebinding;

import com.auraboot.framework.plugin.dto.packages.PackageUninstallOptions;
import com.auraboot.framework.plugin.dto.uninstall.UninstallDecision;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Historical v1 and explicitly sealed v2 Native teardown authority, never a general cleanup utility. */
final class S06Current001PluginCleanupAdmission {
    static final String RUNTIME = "procurement-web-stack-20261010";
    static final String CLASSIFICATION_SHA = "b9e7931faeb7caf28611acb439f2b4a2ecdfad99d4b5c569ebe106abf3d3c7ea";
    static final Path EVIDENCE = Path.of("/Users/ghj/work/auraboot/.workspace/evidence/" + RUNTIME);
    static final Path ORIGINAL = EVIDENCE.resolve("managed-fixture-integration-20261015/s06-native-main509-attempt-001");
    static final Map<String, String> TRACES = Map.ofEntries(
            Map.entry("sn5ee4edb1e3b8", "440974bb3b76002cccbce9e49bc6a778d46e0fdf7fc171b2fb86d1259006afb0"),
            Map.entry("sn6034b1b0e7ae", "7233cac44fe9b9782aa6c4af729d81f89e35e3b03d82ef4da21bae9036550f27"),
            Map.entry("sn6974f573678c", "75904a219ebaabe0bc9dd7c91b69de6f4ee1e0e8cef56a68ebd4bfb6ff8b3374"),
            Map.entry("sna3a52a325740", "d6ceb6cc2086b4099cea07926a62a75b76d41daa85d773ebb98a65bdf1092146"),
            Map.entry("sna96e1a1e265b", "899e4ace3185df5939b07675664b312ea83e0142bc52c79ff4757df5f29598b5"),
            Map.entry("snbeb258572b14", "ba0ab9f32c601e64c3014570a352ecd6b9382bbddeb4bc2f110e1d620f46deee"),
            Map.entry("sndbd811184b46", "21ac7ee459d25ec549653732ac75bd36d6005367e5cb2a1575eba9f1093119fe"));
    static final Set<String> METHODS = Set.of(
            "actualImportedMetadataExplicitRecipientsReplayAndConsumePersistExactlyOnce",
            "foreignWrongOwnerAmbiguousAndInvalidExplicitUserCannotPartiallyEnqueue",
            "changedTemplateSourceAndMembershipInvalidateQueuedAuthorityWithoutChangingOldEnvelope",
            "actualSecondEnqueueFailureRollsBackFirstQueueAndRestoresCallerContext",
            "actualInboxAndReceiptRollbackTogetherBeforeSuccessfulDuplicateConsume",
            "actualBindingMutationCannotBeAuthorizedByCachedMetadataOrOldApproval",
            "actualSerializableCrossOwnerPhantomCycleFailsWithoutAutomaticRetryOrQueue");
    record Target(long tenant, String pluginId, String namespace, String ownPid) {}
    record Expected(long tenant, String pid, String pluginId, String namespace, String version,
            String status, String rowSha256, String manifestSha256) {}
    record Admission(List<Expected> plugins, Path output, String inputSha256, String classificationSha256, int schemaVersion) {}

    private S06Current001PluginCleanupAdmission() {}

    static Admission load(Path input, String expectedHash, ObjectMapper json) throws Exception {
        return load(input, expectedHash, json, EVIDENCE.toRealPath(), java.time.Instant.now());
    }

    /** Test-owned filesystem root only; the Native entry point always supplies EVIDENCE. */
    static Admission load(Path input, String expectedHash, ObjectMapper json, Path evidence, java.time.Instant now) throws Exception {
        evidence = evidence.toRealPath();
        Path file = input.toRealPath();
        require(file.startsWith(evidence) && hash(file).equals(expectedHash), "CLEANUP_INPUT_NOT_SEALED_OWNED");
        JsonNode value = json.readTree(file.toFile());
        if (value.path("kind").asText().equals("s06-owned-native-plugin-cleanup-admission-v2"))
            return current(strict(file, json), evidence, hash(file), json, now);
        exactKeys(value, Set.of("kind", "schemaVersion", "runtime", "database", "redisDatabase", "execute",
                "originalAttemptPath", "originalClassificationSha256", "expectedPlugins", "outputDirectory",
                "pausedVerificationPath", "pausedVerificationSha256"));
        require(value.path("kind").asText().equals("s06-current001-plugin-cleanup-admission-v1")
                && value.path("schemaVersion").isInt() && value.path("schemaVersion").intValue() == 1
                && value.path("runtime").asText().equals(RUNTIME) && value.path("database").asText().equals("enterprise_22")
                && value.path("redisDatabase").isInt() && value.path("redisDatabase").intValue() == 13
                && value.path("execute").isBoolean() && value.path("execute").booleanValue(), "CLEANUP_FIXED_SCOPE_INVALID");
        require(Files.isSameFile(Path.of(value.path("originalAttemptPath").asText()), ORIGINAL), "CLEANUP_WRONG_ORIGINAL_ATTEMPT");
        Path classification = ORIGINAL.resolve("classification.json").toRealPath();
        require(value.path("originalClassificationSha256").asText().equals(CLASSIFICATION_SHA)
                && hash(classification).equals(CLASSIFICATION_SHA), "CLEANUP_CLASSIFICATION_CHANGED");
        JsonNode verdict = json.readTree(classification.toFile());
        require(verdict.path("originalMethodsPassed").asInt(-1) == 7 && verdict.path("productFacetPassed").asBoolean(false),
                "CLEANUP_ORIGINAL_NATIVE_NOT_PASS");
        require(hash(ORIGINAL.resolve("native.xml")).equals(verdict.path("xmlSha256").asText()), "CLEANUP_ORIGINAL_XML_CHANGED");
        Map<Long, Target> targets = new LinkedHashMap<>();
        Set<String> methods = new HashSet<>();
        require(verdict.path("originalTracePairs").isArray() && verdict.path("originalTracePairs").size() == 7,
                "CLEANUP_ORIGINAL_TRACE_ROSTER_INVALID");
        for (JsonNode pair : verdict.path("originalTracePairs")) {
            Path trace = Path.of(pair.path("tracePath").asText()).toRealPath();
            require(trace.getParent().equals(ORIGINAL.resolve("test-traces").toRealPath()), "CLEANUP_TRACE_OUTSIDE_ORIGINAL");
            JsonNode facts = json.readTree(trace.toFile());
            String namespace = facts.path("namespace").asText();
            require(TRACES.containsKey(namespace) && trace.getFileName().toString().equals(namespace + ".json")
                    && hash(trace).equals(TRACES.get(namespace)) && hash(trace).equals(pair.path("traceSha256").asText()),
                    "CLEANUP_ORIGINAL_TRACE_CHANGED");
            String method = facts.path("test").asText();
            require(METHODS.contains(method) && methods.add(method) && facts.path("businessStarted").asBoolean(false)
                    && facts.path("stage").asText().equals("business-checkpoints-completed"), "CLEANUP_ORIGINAL_METHOD_INVALID");
            Path teardown = Path.of(pair.path("receiptPath").asText()).toRealPath();
            require(Files.isSameFile(teardown, trace.resolveSibling(namespace + "-teardown.json"))
                    && hash(teardown).equals(pair.path("receiptSha256").asText()), "CLEANUP_ORIGINAL_TEARDOWN_CHANGED");
            require(json.readTree(teardown.toFile()).path("sealedTraceSha256").asText().equals(hash(trace)),
                    "CLEANUP_TEARDOWN_TRACE_MISMATCH");
            String pluginId = "com.test.s06." + namespace;
            Set<String> ownerPids = new HashSet<>();
            for (JsonNode approval : facts.path("approvedBinding").path("approvals")) {
                require(approval.path("owners").isArray() && approval.path("owners").size() == 1, "CLEANUP_OWNER_AMBIGUOUS");
                JsonNode owner = approval.path("owners").get(0);
                require(owner.path("pluginId").asText().equals(pluginId) && owner.path("namespace").asText().equals(namespace)
                        && owner.path("version").asText().equals("1.0.0") && !owner.path("pid").asText().isBlank(),
                        "CLEANUP_ORIGINAL_OWNER_INVALID");
                ownerPids.add(owner.path("pid").asText());
            }
            require(ownerPids.size() == 1, "CLEANUP_ORIGINAL_OWNER_INVALID");
            long own = facts.path("identities").path("tenantId").longValue();
            long foreign = facts.path("identities").path("foreignTenantId").longValue();
            require(own > 0 && foreign > 0 && own != foreign, "CLEANUP_TENANT_INVALID");
            require(targets.put(own, new Target(own, pluginId, namespace, ownerPids.iterator().next())) == null
                    && targets.put(foreign, new Target(foreign, pluginId, namespace, null)) == null, "CLEANUP_TENANT_REUSED");
        }
        require(methods.equals(METHODS) && targets.size() == 14, "CLEANUP_ORIGINAL_METHOD_ROSTER_INVALID");
        List<Expected> plugins = expected(value.path("expectedPlugins"), targets);
        Path pausedPath = Path.of(value.path("pausedVerificationPath").asText()).toRealPath();
        require(pausedPath.startsWith(evidence) && hash(pausedPath).equals(value.path("pausedVerificationSha256").asText()),
                "CLEANUP_PAUSED_ADMISSION_CHANGED");
        JsonNode paused = json.readTree(pausedPath.toFile());
        require(paused.path("verdict").asText().equals("ok") && paused.path("findings").isArray() && paused.path("findings").isEmpty()
                && paused.path("runtime").asText().equals(RUNTIME) && paused.path("metadata").path("lifecycle").asText().equals("suspended")
                && paused.path("processes").isArray() && paused.path("processes").size() <= 1, "CLEANUP_PUBLIC_WORKERS_NOT_ISOLATED");
        for (JsonNode process : paused.path("processes")) {
            require(process.path("key").asText().equals("erp-fixture") && process.path("status").asText().equals("ok")
                    && process.path("actual").path("alive").asBoolean(false)
                    && process.path("expected").path("pid").longValue() > 0, "CLEANUP_FOREIGN_PROCESS");
            for (String identity : List.of("cwd", "commandHash", "tokenHash")) {
                require(!process.path("expected").path(identity).asText().isBlank()
                        && process.path("expected").path(identity).equals(process.path("actual").path(identity)),
                        "CLEANUP_ERP_IDENTITY_MISMATCH");
            }
        }
        require(paused.path("ports").isObject() && !paused.path("ports").isEmpty(), "CLEANUP_PORT_PROOF_MISSING");
        for (JsonNode port : paused.path("ports")) require(port.path("status").asText().equals("stopped")
                && (!port.has("pids") || port.path("pids").isEmpty()), "CLEANUP_PUBLIC_PORT_NOT_STOPPED");
        Path output = Path.of(value.path("outputDirectory").asText()).toAbsolutePath().normalize();
        require(!Files.exists(output) && output.getParent().toRealPath().startsWith(evidence), "CLEANUP_OUTPUT_NOT_FRESH_OWNED");
        return new Admission(List.copyOf(plugins), output, hash(file), CLASSIFICATION_SHA, 1);
    }

    static Admission current(JsonNode value, Path evidence, String inputHash, ObjectMapper json, java.time.Instant now) throws Exception {
        exactKeys(value, Set.of("kind", "schemaVersion", "runtime", "database", "redisDatabase", "execute",
                "nativeScopePath", "nativeScopeSha256", "pluginRosterCapturedAtUtc", "expectedPlugins", "outputDirectory",
                "pausedVerificationPath", "pausedVerificationSha256"));
        require(value.path("kind").asText().equals("s06-owned-native-plugin-cleanup-admission-v2")
                && value.path("schemaVersion").isInt() && value.path("schemaVersion").intValue() == 2
                && value.path("runtime").asText().equals(RUNTIME) && value.path("database").asText().equals("enterprise_22")
                && value.path("redisDatabase").isInt() && value.path("redisDatabase").intValue() == 13
                && value.path("execute").isBoolean() && value.path("execute").booleanValue(), "CLEANUP_FIXED_SCOPE_INVALID");
        freshUtc(value.path("pluginRosterCapturedAtUtc").asText(), now);
        Path scopePath = owned(value.path("nativeScopePath").asText(), value.path("nativeScopeSha256").asText(), evidence);
        JsonNode scope = strict(scopePath, json);
        exactKeys(scope, Set.of("kind", "schemaVersion", "runtime", "originalAttemptPath", "classificationSha256", "xmlSha256"));
        require(scope.path("kind").asText().equals("s06-owned-native-plugin-cleanup-scope-v1")
                && scope.path("schemaVersion").isInt() && scope.path("schemaVersion").intValue() == 1
                && scope.path("runtime").asText().equals(RUNTIME), "CLEANUP_NATIVE_SCOPE_INVALID");
        Path attempt = Path.of(scope.path("originalAttemptPath").asText()).toRealPath();
        require(attempt.startsWith(evidence), "CLEANUP_NATIVE_SCOPE_OUTSIDE_EVIDENCE");
        Path classification = owned(attempt.resolve("classification.json").toString(), scope.path("classificationSha256").asText(), evidence);
        JsonNode verdict = strict(classification, json);
        require(verdict.path("originalMethodsPassed").isInt() && verdict.path("originalMethodsPassed").intValue() == METHODS.size()
                && verdict.path("productFacetPassed").isBoolean() && verdict.path("productFacetPassed").booleanValue(), "CLEANUP_ORIGINAL_NATIVE_NOT_PASS");
        Path xml = owned(attempt.resolve("native.xml").toString(), scope.path("xmlSha256").asText(), evidence);
        require(hash(xml).equals(verdict.path("xmlSha256").asText()), "CLEANUP_ORIGINAL_XML_CHANGED");
        xmlMethods(xml);
        Map<Long, Target> targets = currentTargets(attempt, verdict, json);
        List<Expected> plugins = expectedCurrent(value.path("expectedPlugins"), targets);
        Path pausedPath = owned(value.path("pausedVerificationPath").asText(), value.path("pausedVerificationSha256").asText(), evidence);
        JsonNode paused = strict(pausedPath, json);
        require(paused.path("verdict").asText().equals("ok") && paused.path("findings").isArray() && paused.path("findings").isEmpty()
                && paused.path("runtime").asText().equals(RUNTIME) && paused.path("metadata").path("lifecycle").asText().equals("suspended")
                && paused.path("processes").isArray() && paused.path("processes").size() <= 1, "CLEANUP_PUBLIC_WORKERS_NOT_ISOLATED");
        for (JsonNode process : paused.path("processes")) {
            require(process.path("key").asText().equals("erp-fixture") && process.path("status").asText().equals("ok")
                    && process.path("actual").path("alive").isBoolean() && process.path("actual").path("alive").booleanValue()
                    && process.path("expected").path("pid").longValue() > 0, "CLEANUP_FOREIGN_PROCESS");
            for (String identity : List.of("cwd", "commandHash", "tokenHash")) require(!process.path("expected").path(identity).asText().isBlank()
                    && process.path("expected").path(identity).equals(process.path("actual").path(identity)), "CLEANUP_ERP_IDENTITY_MISMATCH");
        }
        require(paused.path("ports").isObject() && !paused.path("ports").isEmpty(), "CLEANUP_PORT_PROOF_MISSING");
        for (JsonNode port : paused.path("ports")) require(port.path("status").asText().equals("stopped")
                && (!port.has("pids") || port.path("pids").isEmpty()), "CLEANUP_PUBLIC_PORT_NOT_STOPPED");
        Path output = Path.of(value.path("outputDirectory").asText()).toAbsolutePath().normalize();
        require(!Files.exists(output) && output.getParent().toRealPath().startsWith(evidence), "CLEANUP_OUTPUT_NOT_FRESH_OWNED");
        return new Admission(plugins, output, inputHash, hash(classification), 2);
    }

    static void freshUtc(String value, java.time.Instant now) {
        java.time.Instant captured;
        try { require(value.endsWith("Z"), "CLEANUP_ROSTER_NOT_FRESH_UTC"); captured = java.time.Instant.parse(value); }
        catch (java.time.format.DateTimeParseException invalid) { throw new IllegalStateException("CLEANUP_ROSTER_NOT_FRESH_UTC"); }
        require(!captured.isAfter(now) && java.time.Duration.between(captured, now).compareTo(java.time.Duration.ofSeconds(300)) <= 0,
                "CLEANUP_ROSTER_NOT_FRESH_UTC");
    }

    static void xmlMethods(Path xml) throws Exception {
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var document = factory.newDocumentBuilder().parse(xml.toFile());
        require(document.getElementsByTagName("failure").getLength() == 0 && document.getElementsByTagName("error").getLength() == 0
                && document.getElementsByTagName("skipped").getLength() == 0, "CLEANUP_ORIGINAL_NATIVE_NOT_PASS");
        var suite = document.getDocumentElement();
        require(suite.getTagName().equals("testsuite") && suite.getAttribute("tests").equals(Integer.toString(METHODS.size()))
                && suite.getAttribute("failures").equals("0") && suite.getAttribute("errors").equals("0")
                && suite.getAttribute("skipped").equals("0"), "CLEANUP_ORIGINAL_NATIVE_NOT_PASS");
        var cases = document.getElementsByTagName("testcase"); Set<String> methods = new HashSet<>();
        require(cases.getLength() == METHODS.size(), "CLEANUP_ORIGINAL_METHOD_ROSTER_INVALID");
        for (int index = 0; index < cases.getLength(); index++) {
            var row = (org.w3c.dom.Element) cases.item(index); String name = row.getAttribute("name");
            if (name.endsWith("()")) name = name.substring(0, name.length() - 2);
            require(row.getAttribute("classname").equals("com.auraboot.framework.notification.sourcebinding.RegisteredSourceBindingPostgresIT")
                    && METHODS.contains(name) && methods.add(name), "CLEANUP_ORIGINAL_METHOD_ROSTER_INVALID");
        }
        require(methods.equals(METHODS), "CLEANUP_ORIGINAL_METHOD_ROSTER_INVALID");
    }

    static Map<Long, Target> currentTargets(Path attempt, JsonNode verdict, ObjectMapper json) throws Exception {
        require(verdict.path("originalTracePairs").isArray() && verdict.path("originalTracePairs").size() == METHODS.size(), "CLEANUP_ORIGINAL_TRACE_ROSTER_INVALID");
        Map<Long, Target> targets = new LinkedHashMap<>(); Set<String> methods = new HashSet<>(); Set<String> namespaces = new HashSet<>();
        for (JsonNode pair : verdict.path("originalTracePairs")) {
            Path trace = owned(pair.path("tracePath").asText(), pair.path("traceSha256").asText(), attempt.resolve("test-traces").toRealPath());
            require(trace.getParent().equals(attempt.resolve("test-traces").toRealPath()), "CLEANUP_TRACE_OUTSIDE_ORIGINAL");
            JsonNode facts = strict(trace, json); String namespace = facts.path("namespace").asText(); String method = facts.path("test").asText();
            require(namespace.matches("sn[a-f0-9]{12}") && namespaces.add(namespace) && trace.getFileName().toString().equals(namespace + ".json")
                    && METHODS.contains(method) && methods.add(method) && facts.path("businessStarted").isBoolean() && facts.path("businessStarted").booleanValue()
                    && facts.path("stage").asText().equals("business-checkpoints-completed"), "CLEANUP_ORIGINAL_METHOD_INVALID");
            Path teardown = owned(pair.path("receiptPath").asText(), pair.path("receiptSha256").asText(), attempt.resolve("test-traces").toRealPath());
            require(Files.isSameFile(teardown, trace.resolveSibling(namespace + "-teardown.json")), "CLEANUP_ORIGINAL_TEARDOWN_CHANGED");
            JsonNode receipt = strict(teardown, json);
            require(receipt.path("sealedTraceSha256").asText().equals(hash(trace)) && receipt.path("inboxReceiptReplayPreserved").isBoolean()
                    && receipt.path("inboxReceiptReplayPreserved").booleanValue() && receipt.path("deliverySuccessClaimed").isBoolean()
                    && !receipt.path("deliverySuccessClaimed").booleanValue(), "CLEANUP_TEARDOWN_TRACE_MISMATCH");
            String pluginId = "com.test.s06." + namespace; Set<String> ownerPids = new HashSet<>();
            require(facts.path("approvedBinding").path("approvals").isArray() && !facts.path("approvedBinding").path("approvals").isEmpty(), "CLEANUP_ORIGINAL_OWNER_INVALID");
            for (JsonNode approval : facts.path("approvedBinding").path("approvals")) {
                require(approval.path("owners").isArray() && approval.path("owners").size() == 1, "CLEANUP_OWNER_AMBIGUOUS");
                JsonNode owner = approval.path("owners").get(0);
                require(owner.path("pluginId").asText().equals(pluginId) && owner.path("namespace").asText().equals(namespace)
                        && owner.path("version").asText().equals("1.0.0") && !owner.path("pid").asText().isBlank(), "CLEANUP_ORIGINAL_OWNER_INVALID");
                ownerPids.add(owner.path("pid").asText());
            }
            require(ownerPids.size() == 1, "CLEANUP_ORIGINAL_OWNER_INVALID");
            JsonNode ownNode = facts.path("identities").path("tenantId"), foreignNode = facts.path("identities").path("foreignTenantId");
            require(ownNode.isIntegralNumber() && ownNode.canConvertToLong() && foreignNode.isIntegralNumber() && foreignNode.canConvertToLong(), "CLEANUP_TENANT_INVALID");
            long own = ownNode.longValue(), foreign = foreignNode.longValue();
            require(own > 0 && foreign > 0 && own != foreign && receipt.path("tenantId").longValue() == own, "CLEANUP_TENANT_INVALID");
            require(targets.put(own, new Target(own, pluginId, namespace, ownerPids.iterator().next())) == null
                    && targets.put(foreign, new Target(foreign, pluginId, namespace, null)) == null, "CLEANUP_TENANT_REUSED");
        }
        require(methods.equals(METHODS), "CLEANUP_ORIGINAL_METHOD_ROSTER_INVALID");
        return Map.copyOf(targets);
    }

    static List<Expected> expectedCurrent(JsonNode rows, Map<Long, Target> targets) {
        require(!targets.isEmpty() && rows.isArray() && rows.size() == targets.size(), "CLEANUP_EXACT_DERIVED_ROSTER_REQUIRED");
        List<Expected> result = new ArrayList<>(); Set<Long> tenants = new HashSet<>(); Set<String> pids = new HashSet<>();
        for (JsonNode row : rows) {
            exactKeys(row, Set.of("tenantId", "pluginPid", "pluginId", "namespace", "version", "status", "rowSha256", "manifestSha256"));
            require(row.path("tenantId").isTextual() && row.path("tenantId").asText().matches("[1-9][0-9]*"), "CLEANUP_TENANT_INVALID");
            long tenant = Long.parseLong(row.path("tenantId").asText()); Target target = targets.get(tenant); String pid = row.path("pluginPid").asText();
            require(target != null && tenants.add(tenant) && !pid.isBlank() && pids.add(pid)
                    && row.path("pluginId").asText().equals(target.pluginId()) && row.path("namespace").asText().equals(target.namespace())
                    && (target.ownPid() == null || target.ownPid().equals(pid)) && row.path("version").asText().equals("1.0.0")
                    && row.path("status").asText().equals("installed") && row.path("rowSha256").asText().matches("[a-f0-9]{64}")
                    && row.path("manifestSha256").asText().matches("[a-f0-9]{64}"), "CLEANUP_EXPECTED_PLUGIN_OUTSIDE_SCOPE");
            result.add(new Expected(tenant, pid, target.pluginId(), target.namespace(), "1.0.0", "installed", row.path("rowSha256").asText(), row.path("manifestSha256").asText()));
        }
        require(tenants.equals(targets.keySet()), "CLEANUP_EXACT_DERIVED_ROSTER_REQUIRED");
        return List.copyOf(result);
    }

    private static Path owned(String input, String digest, Path root) throws Exception {
        Path declared = Path.of(input); require(!Files.isSymbolicLink(declared), "CLEANUP_INPUT_NOT_SEALED_OWNED");
        Path actual = declared.toRealPath(); require(actual.startsWith(root) && digest.matches("[a-f0-9]{64}") && hash(actual).equals(digest), "CLEANUP_INPUT_NOT_SEALED_OWNED");
        return actual;
    }
    private static JsonNode strict(Path path, ObjectMapper json) throws Exception {
        return json.reader().with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(Files.readString(path));
    }

    static List<Expected> expected(JsonNode rows, Map<Long, Target> targets) {
        require(rows.isArray() && rows.size() == 14 && targets.size() == 14, "CLEANUP_EXACT_FOURTEEN_REQUIRED");
        List<Expected> result = new ArrayList<>();
        Set<Long> tenants = new HashSet<>();
        Set<String> pids = new HashSet<>();
        for (JsonNode row : rows) {
            exactKeys(row, Set.of("tenantId", "pluginPid", "pluginId", "namespace", "version", "status", "rowSha256", "manifestSha256"));
            require(row.path("tenantId").isTextual() && row.path("tenantId").asText().matches("[1-9][0-9]*"), "CLEANUP_TENANT_INVALID");
            long tenant = Long.parseLong(row.path("tenantId").asText());
            Target target = targets.get(tenant);
            String pid = row.path("pluginPid").asText();
            require(target != null && tenants.add(tenant) && !pid.isBlank() && pids.add(pid)
                    && row.path("pluginId").asText().equals(target.pluginId()) && row.path("namespace").asText().equals(target.namespace())
                    && (target.ownPid() == null || target.ownPid().equals(pid)) && row.path("version").asText().equals("1.0.0")
                    && row.path("status").asText().equals("installed") && row.path("rowSha256").asText().matches("[a-f0-9]{64}")
                    && row.path("manifestSha256").asText().matches("[a-f0-9]{64}"), "CLEANUP_EXPECTED_PLUGIN_OUTSIDE_SCOPE");
            result.add(new Expected(tenant, pid, target.pluginId(), target.namespace(), "1.0.0", "installed",
                    row.path("rowSha256").asText(), row.path("manifestSha256").asText()));
        }
        return result;
    }

    static PackageUninstallOptions options() {
        return PackageUninstallOptions.builder().skipFrontend(true).skipBackend(true).skipConfig(true)
                .removeAllData(false).force(false).removeFrontendAssets(false).removeBackendJar(false)
                .defaultDecision(UninstallDecision.SKIP).build();
    }

    static void exactKeys(JsonNode node, Set<String> expected) {
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        require(node.isObject() && actual.equals(expected), "CLEANUP_SCHEMA_INVALID");
    }

    static String hash(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    static void require(boolean condition, String code) {
        if (!condition) throw new IllegalStateException(code);
    }
}
