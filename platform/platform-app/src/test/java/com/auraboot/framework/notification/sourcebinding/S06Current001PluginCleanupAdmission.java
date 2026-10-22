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
    static Path evidence() { return NativeProcurementWorkspace.root().resolve(".workspace/evidence").resolve(RUNTIME); }
    static Path original() { return evidence().resolve("managed-fixture-integration-20261015/s06-native-main509-attempt-001"); }
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
        return load(input, expectedHash, json, evidence().toRealPath(), java.time.Instant.now());
    }

    /** Test-owned filesystem root only; the Native entry point always supplies EVIDENCE. */
    static Admission load(Path input, String expectedHash, ObjectMapper json, Path evidence, java.time.Instant now) throws Exception {
        evidence = evidence.toRealPath();
        Path file = input.toRealPath();
        require(file.startsWith(evidence) && hash(file).equals(expectedHash), "CLEANUP_INPUT_NOT_SEALED_OWNED");
        JsonNode value = json.readTree(file.toFile());
        if (value.path("kind").asText().equals("s06-automatic-owned-plugin-cleanup-admission-v3"))
            return automatic(automaticStrict(file, json), evidence, hash(file), json, now);
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
        require(Files.isSameFile(Path.of(value.path("originalAttemptPath").asText()), original()), "CLEANUP_WRONG_ORIGINAL_ATTEMPT");
        Path classification = original().resolve("classification.json").toRealPath();
        require(value.path("originalClassificationSha256").asText().equals(CLASSIFICATION_SHA)
                && hash(classification).equals(CLASSIFICATION_SHA), "CLEANUP_CLASSIFICATION_CHANGED");
        JsonNode verdict = json.readTree(classification.toFile());
        require(verdict.path("originalMethodsPassed").asInt(-1) == 7 && verdict.path("productFacetPassed").asBoolean(false),
                "CLEANUP_ORIGINAL_NATIVE_NOT_PASS");
        require(hash(original().resolve("native.xml")).equals(verdict.path("xmlSha256").asText()), "CLEANUP_ORIGINAL_XML_CHANGED");
        Map<Long, Target> targets = new LinkedHashMap<>();
        Set<String> methods = new HashSet<>();
        require(verdict.path("originalTracePairs").isArray() && verdict.path("originalTracePairs").size() == 7,
                "CLEANUP_ORIGINAL_TRACE_ROSTER_INVALID");
        for (JsonNode pair : verdict.path("originalTracePairs")) {
            Path trace = Path.of(pair.path("tracePath").asText()).toRealPath();
            require(trace.getParent().equals(original().resolve("test-traces").toRealPath()), "CLEANUP_TRACE_OUTSIDE_ORIGINAL");
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
    // BEGIN automatic sourceFamily cleanup admission.
    static final String AUTOMATIC_FAMILY = "s06-pf4j-automatic-quote-shipment-v1";
    static final String AUTOMATIC_CLASS = "com.auraboot.framework.notification.sourcebinding.ProcurementReminderTickNativeIT";
    static final String AUTOMATIC_METHOD = "actualPf4jRegisteredTimerQueuesQuoteAndShipmentAndDeliversOnlySyntheticInApp";
    static final String AUTOMATIC_SELECTOR = AUTOMATIC_CLASS + "." + AUTOMATIC_METHOD;

    /** File-only preflight is deliberately not a deletion Admission. */
    record AutomaticPreflight(List<Expected> plugins, Path output, String classificationSha256) {}

    static AutomaticPreflight automaticPreflight(Path input, String expectedHash, ObjectMapper json) throws Exception {
        Path evidence = evidence().toRealPath();
        Path original = automaticIntentPath(input, expectedHash);
        JsonNode intent = automaticStrict(original, json);
        automaticOriginalTimestamp(intent.path("pluginRosterCapturedAtUtc").asText(), java.time.Instant.now());
        return automaticProof(intent, evidence, json);
    }

    static Path automaticIntentPath(Path input, String expectedHash) throws Exception {
        return owned(input.toString(), expectedHash, evidence().toRealPath());
    }

    static void automaticOriginalTimestamp(String value, java.time.Instant now) {
        java.time.Instant captured;
        try { require(value.endsWith("Z"), "CLEANUP_POST_BOOT_ORIGINAL_TIME_INVALID"); captured = java.time.Instant.parse(value); }
        catch (java.time.format.DateTimeParseException invalid) { throw new IllegalStateException("CLEANUP_POST_BOOT_ORIGINAL_TIME_INVALID"); }
        require(!captured.isAfter(now), "CLEANUP_POST_BOOT_ORIGINAL_TIME_INVALID");
    }

    static Admission automatic(JsonNode input, Path evidence, String inputHash, ObjectMapper json, java.time.Instant now) throws Exception {
        freshUtc(input.path("pluginRosterCapturedAtUtc").asText(), now);
        AutomaticPreflight proof = automaticProof(input, evidence, json);
        return new Admission(proof.plugins(), proof.output(), inputHash, proof.classificationSha256(), 3);
    }

    private static AutomaticPreflight automaticProof(JsonNode input, Path evidence, ObjectMapper json) throws Exception {
        exactKeys(input, Set.of("kind", "schemaVersion", "sourceFamily", "runtime", "database", "redisDatabase", "execute",
                "nativeEvidence", "expectedPlugins", "pluginRosterCapturedAtUtc", "pausedVerificationPath", "pausedVerificationSha256", "outputDirectory"));
        require(input.path("kind").asText().equals("s06-automatic-owned-plugin-cleanup-admission-v3")
                && input.path("schemaVersion").isInt() && input.path("schemaVersion").intValue() == 3
                && input.path("sourceFamily").asText().equals(AUTOMATIC_FAMILY)
                && input.path("runtime").asText().equals(RUNTIME) && input.path("database").asText().equals("enterprise_22")
                && input.path("redisDatabase").isInt() && input.path("redisDatabase").intValue() == 13
                && input.path("execute").isBoolean() && input.path("execute").booleanValue(), "CLEANUP_AUTOMATIC_SCOPE_INVALID");
        JsonNode proof = input.path("nativeEvidence");
        exactKeys(proof, Set.of("sourceFamily", "selector", "appRoot", "appCommit", "sourceSet", "compiledSeal", "xml",
                "receipt", "trace", "fixtureSetup", "sourceBinding", "classification", "runner"));
        require(proof.path("sourceFamily").asText().equals(AUTOMATIC_FAMILY)
                && proof.path("selector").asText().equals(AUTOMATIC_SELECTOR), "CLEANUP_AUTOMATIC_SOURCE_FAMILY_INVALID");
        Path xml = automaticRef(proof.path("xml"), evidence);
        automaticXml(xml);
        JsonNode receipt = automaticRead(proof.path("receipt"), evidence, json);
        JsonNode trace = automaticRead(proof.path("trace"), evidence, json);
        JsonNode setup = automaticRead(proof.path("fixtureSetup"), evidence, json);
        JsonNode binding = automaticRead(proof.path("sourceBinding"), evidence, json);
        JsonNode classification = automaticRead(proof.path("classification"), evidence, json);
        require(receipt.path("kind").asText().equals("procurement-pf4j-automatic-quote-shipment-native-v1")
                && receipt.path("completed").isBoolean() && receipt.path("completed").booleanValue()
                && receipt.path("stage").asText().equals("automatic-quote-shipment-and-intent-teardown-completed")
                && receipt.path("nativeTickExecuted").isBoolean() && receipt.path("nativeTickExecuted").booleanValue()
                && receipt.path("automaticTimerVerified").isBoolean() && receipt.path("automaticTimerVerified").booleanValue()
                && receipt.path("actualPf4jRegistryUsed").isBoolean() && receipt.path("actualPf4jRegistryUsed").booleanValue()
                && receipt.path("formalRecipientPolicyApproved").isBoolean() && !receipt.path("formalRecipientPolicyApproved").booleanValue()
                && receipt.path("automaticSourceWorkerVerified").isBoolean() && !receipt.path("automaticSourceWorkerVerified").booleanValue()
                && receipt.path("providerCalls").isInt() && receipt.path("providerCalls").intValue() == 0
                && receipt.path("wechatCalls").isInt() && receipt.path("wechatCalls").intValue() == 0,
                "CLEANUP_AUTOMATIC_NATIVE_NOT_COMPLETE");
        require(receipt.path("sealedTrace").equals(proof.path("trace"))
                && receipt.path("fixtureSetupPath").asText().equals(proof.path("fixtureSetup").path("path").asText())
                && receipt.path("fixtureSetupSha256").asText().equals(proof.path("fixtureSetup").path("sha256").asText())
                && trace.path("completed").isBoolean() && !trace.path("completed").booleanValue()
                && trace.path("businessCompleted").isBoolean() && trace.path("businessCompleted").booleanValue()
                && trace.path("phases").equals(receipt.path("phases"))
                && receipt.path("protectedBefore").equals(receipt.path("protectedAfter")), "CLEANUP_AUTOMATIC_TRACE_CHANGED");
        require(setup.path("stage").asText().equals("automatic-registry-fixture-prepared")
                && setup.path("namespace").asText().matches("sn[a-f0-9]{12}")
                && setup.path("tenantId").isIntegralNumber() && setup.path("tenantId").canConvertToLong()
                && setup.path("tenantId").longValue() > 0
                && receipt.path("tenantId").isTextual() && receipt.path("tenantId").asText().equals(setup.path("tenantId").asText()),
                "CLEANUP_AUTOMATIC_TENANT_INVALID");
        require(classification.path("kind").asText().equals("s06-pf4j-automatic-native-qualified-v1")
                && classification.path("completed").isBoolean() && classification.path("completed").booleanValue()
                && classification.path("postSourceClassGuard").isBoolean() && classification.path("postSourceClassGuard").booleanValue()
                && classification.path("selector").equals(proof.path("selector"))
                && classification.path("appCommit").equals(proof.path("appCommit"))
                && classification.path("sourceSet").equals(proof.path("sourceSet")), "CLEANUP_AUTOMATIC_CLASSIFICATION_INVALID");
        for (String ref : List.of("compiledSeal", "xml", "receipt", "trace", "fixtureSetup", "sourceBinding", "runner"))
            require(classification.path(ref).equals(proof.path(ref)), "CLEANUP_AUTOMATIC_CLASSIFICATION_REF_CHANGED");
        automaticPhases(receipt, setup, evidence, json);
        Path pausedFile = owned(input.path("pausedVerificationPath").asText(), input.path("pausedVerificationSha256").asText(), evidence);
        JsonNode paused = strict(pausedFile, json);
        automaticPaused(paused, proof.path("sourceSet"));
        automaticCompiled(proof, binding, paused, evidence, json);
        List<Expected> plugins = automaticExpected(input.path("expectedPlugins"), setup, binding, json);
        Path output = Path.of(input.path("outputDirectory").asText()).toAbsolutePath().normalize();
        require(!Files.exists(output) && output.getParent().toRealPath().startsWith(evidence), "CLEANUP_OUTPUT_NOT_FRESH_OWNED");
        return new AutomaticPreflight(List.copyOf(plugins), output, hash(automaticRef(proof.path("classification"), evidence)));
    }

    static void automaticExactCurrentRoster(List<Expected> expected, List<String> rawRows, ObjectMapper json) throws Exception {
        require(expected.size() == 23 && rawRows.size() == expected.size(), "CLEANUP_POST_BOOT_EXACT_ROSTER_CHANGED");
        Set<String> seen = new HashSet<>();
        for (String raw : rawRows) {
            JsonNode row = json.reader().with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw);
            String pid = row.path("pid").asText();
            Expected target = expected.stream().filter(value -> value.pid().equals(pid)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("CLEANUP_POST_BOOT_EXACT_ROSTER_CHANGED"));
            require(seen.add(pid) && row.path("tenant_id").isIntegralNumber() && row.path("tenant_id").canConvertToLong()
                    && row.path("tenant_id").longValue() == target.tenant()
                    && row.path("plugin_id").asText().equals(target.pluginId()) && row.path("namespace").asText().equals(target.namespace())
                    && row.path("version").asText().equals(target.version()) && row.path("status").asText().equals(target.status())
                    && row.path("deleted_flag").isBoolean() && !row.path("deleted_flag").booleanValue()
                    && HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .equals(target.rowSha256())
                    && SourceBindingCanonical.hash(row.path("manifest")).equals(target.manifestSha256()),
                    "CLEANUP_POST_BOOT_EXACT_ROSTER_CHANGED");
        }
        require(seen.equals(expected.stream().map(Expected::pid).collect(java.util.stream.Collectors.toSet())),
                "CLEANUP_POST_BOOT_EXACT_ROSTER_CHANGED");
    }

    static com.fasterxml.jackson.databind.node.ObjectNode automaticRefreshedInput(JsonNode original, java.time.Instant observedAt) {
        require(original.isObject(), "CLEANUP_POST_BOOT_INPUT_INVALID");
        var derived = ((com.fasterxml.jackson.databind.node.ObjectNode) original).deepCopy();
        derived.put("pluginRosterCapturedAtUtc", observedAt.toString());
        automaticOnlyCapturedAtChanged(original, derived);
        return derived;
    }

    static void automaticOnlyCapturedAtChanged(JsonNode original, JsonNode derived) {
        require(original.isObject() && derived.isObject() && derived.path("pluginRosterCapturedAtUtc").isTextual(),
                "CLEANUP_POST_BOOT_INPUT_INVALID");
        var before = ((com.fasterxml.jackson.databind.node.ObjectNode) original).deepCopy();
        var after = ((com.fasterxml.jackson.databind.node.ObjectNode) derived).deepCopy();
        before.remove("pluginRosterCapturedAtUtc"); after.remove("pluginRosterCapturedAtUtc");
        require(before.equals(after), "CLEANUP_POST_BOOT_INPUT_CHANGED");
    }

    static Path automaticRef(JsonNode ref, Path evidence) throws Exception {
        exactKeys(ref, Set.of("path", "sha256"));
        require(ref.path("path").isTextual() && ref.path("sha256").isTextual(), "CLEANUP_AUTOMATIC_REF_MISSING");
        return owned(ref.path("path").textValue(), ref.path("sha256").textValue(), evidence);
    }
    static JsonNode automaticRead(JsonNode ref, Path evidence, ObjectMapper json) throws Exception {
        return automaticStrict(automaticRef(ref, evidence), json);
    }
    static JsonNode automaticStrict(Path file, ObjectMapper json) throws Exception {
        return json.reader().with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readTree(Files.readString(file));
    }
    static void automaticXml(Path xml) throws Exception {
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var document = factory.newDocumentBuilder().parse(xml.toFile());
        var suite = document.getDocumentElement();
        require(suite.getTagName().equals("testsuite") && suite.getAttribute("tests").equals("1")
                && suite.getAttribute("failures").equals("0") && suite.getAttribute("errors").equals("0") && suite.getAttribute("skipped").equals("0")
                && document.getElementsByTagName("failure").getLength() == 0 && document.getElementsByTagName("error").getLength() == 0
                && document.getElementsByTagName("skipped").getLength() == 0 && document.getElementsByTagName("testcase").getLength() == 1,
                "CLEANUP_AUTOMATIC_XML_NOT_PASS");
        var test = (org.w3c.dom.Element) document.getElementsByTagName("testcase").item(0);
        String method = test.getAttribute("name"); if (method.endsWith("()")) method = method.substring(0, method.length() - 2);
        require(test.getAttribute("classname").equals(AUTOMATIC_CLASS) && method.equals(AUTOMATIC_METHOD), "CLEANUP_AUTOMATIC_XML_WRONG_SELECTOR");
    }
    static void automaticPhases(JsonNode receipt, JsonNode setup, Path evidence, ObjectMapper json) throws Exception {
        require(receipt.path("phases").isArray() && receipt.path("phases").size() == 2, "CLEANUP_AUTOMATIC_PHASES_INVALID");
        Set<String> kinds = new HashSet<>(), sourcePids = new HashSet<>();
        var teardowns = json.createArrayNode();
        for (JsonNode phase : receipt.path("phases")) {
            String kind = phase.path("kind").asText();
            require(Set.of("quote", "shipment").contains(kind) && kinds.add(kind)
                    && phase.path("deliveryAndReplayVerified").isBoolean() && phase.path("deliveryAndReplayVerified").booleanValue()
                    && phase.path("automaticTransactions").isInt() && phase.path("automaticTransactions").intValue() >= 2
                    && phase.path("ownedOutboxRows").isArray() && phase.path("ownedOutboxRows").size() == 2
                    && phase.path("outboxAfterIntentTeardown").isArray() && phase.path("outboxAfterIntentTeardown").isEmpty(),
                    "CLEANUP_AUTOMATIC_PHASES_INVALID");
            JsonNode expectedBinding = setup.path(kind.equals("quote") ? "quoteBinding" : "shipmentBinding");
            require(phase.path("binding").equals(expectedBinding.path("code"))
                    && phase.path("targetPid").equals(setup.path(kind.equals("quote") ? "quotePid" : "orderPid"))
                    && !phase.path("sourcePid").asText().isBlank() && sourcePids.add(phase.path("sourcePid").asText())
                    && phase.path("slot").asText().equals(kind.equals("quote") ? "2026-10-13T01:30:00Z" : "2026-10-17T06:00:00Z"),
                    "CLEANUP_AUTOMATIC_PHASE_BINDING_CHANGED");
            teardowns.add(phase.path("intentTeardown"));
            JsonNode phaseTrace = automaticRead(phase.path("phaseTrace"), evidence, json);
            JsonNode teardown = automaticRead(phase.path("intentTeardown"), evidence, json);
            require(phaseTrace.path("kind").asText().equals(kind) && phaseTrace.path("sourcePid").equals(phase.path("sourcePid"))
                    && phaseTrace.path("ownedOutboxRows").equals(phase.path("ownedOutboxRows"))
                    && teardown.path("sealedTraceSha256").equals(phase.path("phaseTrace").path("sha256"))
                    && teardown.path("tenantId").equals(setup.path("tenantId"))
                    && teardown.path("binding").equals(phase.path("binding")) && teardown.path("sourcePid").equals(phase.path("sourcePid"))
                    && teardown.path("inboxReceiptReplayPreserved").isBoolean() && teardown.path("inboxReceiptReplayPreserved").booleanValue()
                    && teardown.path("deliverySuccessClaimed").isBoolean() && !teardown.path("deliverySuccessClaimed").booleanValue(),
                    "CLEANUP_AUTOMATIC_INTENT_TEARDOWN_INVALID");
        }
        require(kinds.equals(Set.of("quote", "shipment")) && receipt.path("intentTeardowns").equals(teardowns), "CLEANUP_AUTOMATIC_PHASES_INVALID");
    }
    static void automaticPaused(JsonNode paused, JsonNode sources) {
        exactKeys(sources, Set.of("oss", "quote", "enterprise", "plugins", "crm"));
        require(paused.path("runtime").asText().equals(RUNTIME) && paused.path("verdict").asText().equals("ok")
                && paused.path("findings").isArray() && paused.path("findings").isEmpty()
                && paused.path("metadata").path("lifecycle").asText().equals("suspended")
                && paused.path("processes").isArray() && paused.path("processes").size() == 1, "CLEANUP_PUBLIC_WORKERS_NOT_ISOLATED");
        require(paused.path("environment").path("POSTGRES_DB").asText().equals("enterprise_22")
                && paused.path("environment").path("REDIS_DATABASE").asText().equals("13"), "CLEANUP_AUTOMATIC_DATA_SCOPE_CHANGED");
        for (JsonNode process : paused.path("processes")) {
            require(process.path("key").asText().equals("erp-fixture") && process.path("status").asText().equals("ok")
                    && process.path("actual").path("alive").isBoolean() && process.path("actual").path("alive").booleanValue()
                    && process.path("expected").path("pid").isIntegralNumber() && process.path("expected").path("pid").longValue() > 0,
                    "CLEANUP_FOREIGN_PROCESS");
            for (String field : List.of("cwd", "commandHash", "tokenHash")) require(!process.path("expected").path(field).asText().isBlank()
                    && process.path("expected").path(field).equals(process.path("actual").path(field)), "CLEANUP_ERP_IDENTITY_MISMATCH");
        }
        require(paused.path("ports").isObject() && !paused.path("ports").isEmpty(), "CLEANUP_PORT_PROOF_MISSING");
        for (JsonNode port : paused.path("ports")) require(port.path("status").asText().equals("stopped")
                && (!port.has("pids") || port.path("pids").isEmpty()), "CLEANUP_PUBLIC_PORT_NOT_STOPPED");
        require(paused.path("sources").isArray() && paused.path("sources").size() == 5, "CLEANUP_AUTOMATIC_SOURCES_INVALID");
        Set<String> seen = new HashSet<>();
        for (JsonNode source : paused.path("sources")) {
            String key = source.path("key").asText();
            require(sources.has(key) && seen.add(key) && sources.path(key).isTextual() && sources.path(key).asText().matches("[a-f0-9]{40}")
                    && source.path("status").asText().equals("ok") && source.path("actual").path("commit").equals(sources.path(key))
                    && source.path("actual").equals(source.path("expected"))
                    && source.path("actual").path("dirtyFingerprint").asText().equals("clean"), "CLEANUP_AUTOMATIC_SOURCES_INVALID");
        }
    }
    static List<Expected> automaticExpected(JsonNode rows, JsonNode setup, JsonNode binding, ObjectMapper json) throws Exception {
        long tenant = setup.path("tenantId").longValue();
        require(tenant > 0 && binding.path("orderedImportDirectories").isArray() && binding.path("orderedImportDirectories").size() == 23
                && setup.path("orderedImportDirectories").equals(binding.path("orderedImportDirectories"))
                && setup.path("formalImportedDirectories").isInt() && setup.path("formalImportedDirectories").intValue() == 23
                && setup.path("formalImportDiagnostics").isArray() && setup.path("formalImportDiagnostics").size() == 23,
                "CLEANUP_AUTOMATIC_FORMAL23_MISSING");
        Map<String, JsonNode> manifests = new LinkedHashMap<>();
        int index = 0;
        for (JsonNode directory : binding.path("orderedImportDirectories")) {
            Path path = Path.of(directory.path("path").asText()).toRealPath();
            Path manifestFile = path.resolve("plugin.json");
            require(hash(manifestFile).equals(directory.path("pluginJsonSha256").asText()), "CLEANUP_AUTOMATIC_SOURCE_MANIFEST_CHANGED");
            JsonNode manifest = automaticStrict(manifestFile, json);
            require(manifests.put(manifest.path("pluginId").asText(), manifest) == null, "CLEANUP_AUTOMATIC_OWNER_DUPLICATED");
            JsonNode imported = setup.path("formalImportDiagnostics").get(index++);
            require(imported.path("directory").asText().equals(directory.path("path").asText())
                    && imported.path("previewValid").isBoolean() && imported.path("previewValid").booleanValue()
                    && imported.path("executeSuccess").isBoolean() && imported.path("executeSuccess").booleanValue(),
                    "CLEANUP_AUTOMATIC_FORMAL_IMPORT_NOT_PASS");
        }
        require(rows.isArray() && rows.size() == manifests.size(), "CLEANUP_AUTOMATIC_EXACT_PLUGIN_ROSTER_REQUIRED");
        Set<String> pids = new HashSet<>(), ids = new HashSet<>(); List<Expected> result = new ArrayList<>();
        for (JsonNode row : rows) {
            exactKeys(row, Set.of("tenantId", "pluginPid", "pluginId", "namespace", "version", "status", "rowSha256", "manifestSha256"));
            for (String field : List.of("tenantId", "pluginPid", "pluginId", "namespace", "version", "status", "rowSha256", "manifestSha256"))
                require(row.path(field).isTextual(), "CLEANUP_AUTOMATIC_PLUGIN_OUTSIDE_OWNED_TENANT");
            String id = row.path("pluginId").asText(), pid = row.path("pluginPid").asText(); JsonNode manifest = manifests.get(id);
            require(manifest != null && ids.add(id) && pid.matches("[A-Za-z0-9_-]{1,64}") && pids.add(pid)
                    && row.path("tenantId").isTextual() && row.path("tenantId").asText().equals(Long.toString(tenant))
                    && row.path("namespace").equals(manifest.path("namespace")) && row.path("version").equals(manifest.path("version"))
                    && Set.of("installed", "enabled").contains(row.path("status").asText())
                    && row.path("rowSha256").asText().matches("[a-f0-9]{64}") && row.path("manifestSha256").asText().matches("[a-f0-9]{64}"),
                    "CLEANUP_AUTOMATIC_PLUGIN_OUTSIDE_OWNED_TENANT");
            result.add(new Expected(tenant, pid, id, row.path("namespace").asText(), row.path("version").asText(),
                    row.path("status").asText(), row.path("rowSha256").asText(), row.path("manifestSha256").asText()));
        }
        return List.copyOf(result);
    }
    static void automaticCompiled(JsonNode proof, JsonNode binding, JsonNode paused, Path evidence, ObjectMapper json) throws Exception {
        JsonNode seal = automaticRead(proof.path("compiledSeal"), evidence, json);
        // Reuse the existing controlled-Tick --seal-compiled layout, not a fabricated historical seal.
        exactKeys(seal, Set.of("appCommit", "runnerSha256", "inventory", "actualReady"));
        require(proof.path("appCommit").isTextual() && proof.path("appCommit").asText().matches("[a-f0-9]{40}")
                && seal.path("appCommit").equals(proof.path("appCommit"))
                && seal.path("runnerSha256").asText().matches("[a-f0-9]{64}")
                && hash(automaticRef(proof.path("runner"), evidence)).equals(seal.path("runnerSha256").asText())
                && seal.path("actualReady").isBoolean() && !seal.path("actualReady").booleanValue()
                && binding.path("sourceSet").equals(proof.path("sourceSet")), "CLEANUP_AUTOMATIC_COMPILED_SEAL_INVALID");
        Path app = Path.of(proof.path("appRoot").asText()).toRealPath();
        require(app.startsWith(NativeProcurementWorkspace.root().resolve(".workspace/worktrees").toRealPath())
                && automaticGit(app, "rev-parse", "HEAD").equals(proof.path("appCommit").asText())
                && automaticGit(app, "status", "--porcelain").isEmpty(), "CLEANUP_AUTOMATIC_APP_NOT_CLEAN");
        Path testClasses = app.resolve("platform/build/classes/java/test").toRealPath();
        require(Files.isSameFile(testClasses, Path.of(S06Current001PluginCleanupAdmission.class.getProtectionDomain().getCodeSource().getLocation().toURI())),
                "CLEANUP_AUTOMATIC_NOT_ACTUAL_APP_CLASSES");
        JsonNode inv = seal.path("inventory");
        exactKeys(inv, Set.of("mainSources", "nativeSources", "buildInputs", "nativeClasses", "originalClasses", "offlineClasses",
                "runtimeBootJarPath", "runtimeBootJarSha256", "productionClassCount", "allNativeSources", "allNativeClasses", "changedTestFiles"));
        require(json.valueToTree(automaticMainSources(app)).equals(inv.path("mainSources"))
                && json.valueToTree(automaticFiles(app.resolve("platform/src/test/java"), ".java", "")).equals(inv.path("allNativeSources"))
                && json.valueToTree(automaticFiles(testClasses, ".class", "")).equals(inv.path("allNativeClasses"))
                && json.valueToTree(automaticFiles(app.resolve("platform/build/classes/java/main"), ".class", "")).equals(inv.path("originalClasses"))
                && json.valueToTree(automaticFiles(app.resolve("platform/build/classes-instrumented-main"), ".class", "")).equals(inv.path("offlineClasses")),
                "CLEANUP_AUTOMATIC_FULL_SOURCE_CLASS_DRIFT");
        require(inv.path("originalClasses").size() > 0 && inv.path("productionClassCount").isInt()
                && inv.path("productionClassCount").intValue() == inv.path("originalClasses").size()
                && automaticKeys(inv.path("originalClasses")).equals(automaticKeys(inv.path("offlineClasses"))),
                "CLEANUP_AUTOMATIC_FULL_MAIN_ROSTER_MISSING");
        require(inv.path("allNativeClasses").has(AUTOMATIC_CLASS.replace('.', '/') + ".class")
                && inv.path("allNativeClasses").has("com/auraboot/framework/notification/sourcebinding/S06Current001PluginCleanupAdmission.class"),
                "CLEANUP_AUTOMATIC_REQUIRED_CLASS_MISSING");
        exactKeys(inv.path("buildInputs"), Set.of("platform/build.gradle", "platform/settings.gradle"));
        for (String file : List.of("platform/build.gradle", "platform/settings.gradle"))
            require(hash(app.resolve(file)).equals(inv.path("buildInputs").path(file).asText()), "CLEANUP_AUTOMATIC_BUILD_INPUT_DRIFT");
        for (String file : automaticKeys(inv.path("nativeSources"))) {
            require(file.matches("[A-Za-z0-9_]+\\.java"), "CLEANUP_AUTOMATIC_NATIVE_SOURCE_PATH_INVALID");
            require(hash(app.resolve(
                "platform/src/test/java/com/auraboot/framework/notification/sourcebinding/" + file)).equals(inv.path("nativeSources").path(file).asText()),
                "CLEANUP_AUTOMATIC_NATIVE_SOURCE_DRIFT");
        }
        for (String file : automaticKeys(inv.path("nativeClasses"))) require(inv.path("allNativeClasses").path(file).equals(inv.path("nativeClasses").path(file)),
                "CLEANUP_AUTOMATIC_NATIVE_CLASS_DRIFT");
        String main = proof.path("sourceSet").path("oss").asText();
        var changed = new LinkedHashMap<String, String>();
        for (String file : automaticGit(app, "diff", "--name-only", main, "HEAD").lines().toList()) {
            require(file.startsWith("platform/src/test/java/") && file.endsWith(".java"), "CLEANUP_AUTOMATIC_APP_CHANGED_PRODUCTION");
            changed.put(file, hash(app.resolve(file)));
        }
        require(!changed.isEmpty() && json.valueToTree(changed).equals(inv.path("changedTestFiles")), "CLEANUP_AUTOMATIC_CHANGED_SOURCE_ROSTER_DRIFT");
        Path runtimeCore = null;
        for (JsonNode source : paused.path("sources")) if (source.path("key").asText().equals("oss"))
            runtimeCore = Path.of(source.path("actual").path("root").asText()).toRealPath();
        require(runtimeCore != null && json.valueToTree(automaticMainSources(runtimeCore))
                .equals(inv.path("mainSources")), "CLEANUP_AUTOMATIC_RUNTIME_MAIN_SOURCE_MISMATCH");
        Path boot = Path.of(inv.path("runtimeBootJarPath").asText()).toRealPath();
        require(hash(boot).equals(inv.path("runtimeBootJarSha256").asText()), "CLEANUP_AUTOMATIC_BOOT_JAR_DRIFT");
        int artifacts = 0;
        for (JsonNode artifact : paused.path("artifacts")) if (artifact.path("key").asText().equals("enterprise-backend")) {
            artifacts++;
            require(artifact.path("status").asText().equals("ok") && Files.isSameFile(boot, Path.of(artifact.path("path").asText()))
                    && artifact.path("expected").path("hash").equals(inv.path("runtimeBootJarSha256"))
                    && artifact.path("actual").path("hash").equals(inv.path("runtimeBootJarSha256")), "CLEANUP_AUTOMATIC_BOOT_ARTIFACT_MISMATCH");
        }
        require(artifacts == 1 && json.valueToTree(automaticCoreJarClasses(boot)).equals(inv.path("originalClasses")),
                "CLEANUP_AUTOMATIC_BOOT_MAIN_CLASS_MISMATCH");
    }
    static Set<String> automaticKeys(JsonNode map) {
        require(map.isObject() && !map.isEmpty(), "CLEANUP_AUTOMATIC_COMPLETE_MAP_REQUIRED");
        Set<String> keys = new HashSet<>(); map.fieldNames().forEachRemaining(keys::add); return keys;
    }
    static Map<String, String> automaticMainSources(Path root) throws Exception {
        String tracked = automaticGit(root, "ls-files", "-z", "--", "platform/src/main");
        var result = new java.util.TreeMap<String, String>();
        for (String relative : tracked.split(java.util.regex.Pattern.quote(Character.toString(0)), -1)) {
            if (relative.isEmpty()) continue;
            Path file = root.resolve(relative).normalize();
            require(relative.startsWith("platform/src/main/") && file.startsWith(root)
                    && root.relativize(file).toString().equals(relative), "CLEANUP_AUTOMATIC_MAIN_PATH_ESCAPE");
            Path current = file;
            while (!current.equals(root)) {
                require(!Files.isSymbolicLink(current), "CLEANUP_AUTOMATIC_SOURCE_SYMLINK");
                current = current.getParent();
            }
            require(Files.isRegularFile(file) && result.put(relative, hash(file)) == null,
                    "CLEANUP_AUTOMATIC_TRACKED_MAIN_MISSING_OR_DUPLICATED");
        }
        require(!result.isEmpty(), "CLEANUP_AUTOMATIC_COMPLETE_MAP_REQUIRED");
        return result;
    }
    static Map<String, String> automaticFiles(Path root, String suffix, String prefix) throws Exception {
        require(Files.isDirectory(root) && !Files.isSymbolicLink(root), "CLEANUP_AUTOMATIC_CLASS_ROOT_MISSING");
        var result = new java.util.TreeMap<String, String>();
        try (var files = Files.walk(root)) {
            for (Path file : files.sorted().toList()) {
                require(!Files.isSymbolicLink(file), "CLEANUP_AUTOMATIC_SOURCE_SYMLINK");
                if (Files.isRegularFile(file) && file.toString().endsWith(suffix)) result.put(prefix + root.relativize(file).toString(), hash(file));
            }
        }
        require(!result.isEmpty(), "CLEANUP_AUTOMATIC_COMPLETE_MAP_REQUIRED"); return result;
    }
    static Map<String, String> automaticCoreJarClasses(Path boot) throws Exception {
        try (var outer = new java.util.zip.ZipFile(boot.toFile())) {
            var matches = java.util.Collections.list(outer.entries()).stream().filter(entry ->
                    entry.getName().startsWith("BOOT-INF/lib/auraboot-core-") && entry.getName().endsWith(".jar")).toList();
            require(matches.size() == 1, "CLEANUP_AUTOMATIC_UNIQUE_CORE_JAR_REQUIRED");
            var result = new java.util.TreeMap<String, String>();
            try (var inner = new java.util.zip.ZipInputStream(outer.getInputStream(matches.getFirst()))) {
                java.util.zip.ZipEntry entry;
                while ((entry = inner.getNextEntry()) != null) if (!entry.isDirectory() && entry.getName().endsWith(".class")) {
                    require(result.put(entry.getName(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(inner.readAllBytes()))) == null,
                            "CLEANUP_AUTOMATIC_DUPLICATE_CORE_CLASS");
                }
            }
            return result;
        }
    }
    static String automaticGit(Path root, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", root.toString())); command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String result = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        require(process.waitFor() == 0, "CLEANUP_AUTOMATIC_GIT_PROOF_FAILED"); return result.strip();
    }
    // END automatic sourceFamily cleanup admission.
}
