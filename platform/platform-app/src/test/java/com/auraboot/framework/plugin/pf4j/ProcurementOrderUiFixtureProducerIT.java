package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.crypto.FieldEncryptionService;
import com.auraboot.framework.cloudconfig.dto.CloudConfigSaveRequest;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.CommandExecutor;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.auth.dto.AuthenticationRequest;
import com.auraboot.framework.notification.sourcebinding.ProductQuoteNativeInitialization;
import org.springframework.beans.factory.annotation.Autowired;
import com.auraboot.framework.plugin.extension.DataAccessor;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Retained synthetic browser prerequisites. Only subsequent real browser actions qualify UI behavior. */
@SpringBootTest(classes = TestApplication.class, properties = {
        "spring.datasource.url=${TEST_DATABASE_URL}", "spring.data.redis.database=${TEST_REDIS_DATABASE}",
        "aura.plugins.dir=${PROCUREMENT_SOURCE_PLUGIN_DIR}",
        "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.slf4j.Slf4jImpl",
        "logging.level.com.auraboot.framework=INFO", "logging.level.org.mybatis=INFO",
        "logging.level.org.springframework.security=WARN", "logging.level.org.springframework.cache=WARN",
        "security.field-encryption.key=${PROCUREMENT_ORDER_UI_FIELD_ENCRYPTION_KEY}"})
@ActiveProfiles("integration-test")
@Import(ProcurementPublicSourceNativeFixture.IsolatedScheduling.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "PROCUREMENT_ORDER_UI_FIXTURE_IT", matches = "true")
class ProcurementOrderUiFixtureProducerIT extends ProcurementPublicSourceNativeFixture {
    private record Offer(Submitted submitted, String supplier, String externalSupplier) { }
    private record Group(String stage, String taskPid, String sourcePid, String entry, List<Offer> offers,
                         List<String> executionPids, List<String> quantities) { }
    @Autowired MetaModelService metadata;
    @Autowired CommandExecutor initializerCommands;
    @Autowired FieldEncryptionService encryptionService;
    private String encryptionMode;
    private Path encryptionProofPath;
    private String encryptionProofSha256;
    private Path initializationDirectory;
    private final List<Map<String,Object>> directoryInitializationResults = new ArrayList<>();
    private final List<Map<String,Object>> connectionUpdateResults = new ArrayList<>();
    private final List<Map<String, Object>> initializationResults = new ArrayList<>();
    private Map<String, Object> initializationPrincipal;
    static final List<String> ERP_COLUMNS = READ_COLUMNS.stream()
            .map(column -> "Read_" + (column.equals("specification") ? "material_spec" : column)).toList();
    private String preparationStage = "before-managed-provider-admission";
    private boolean preparationCompleted;
    private String preparationToken;
    private Map<String,Object> preparationIdentity;
    private Path immutableProductionDirectory;
    private final List<Map<String,Object>> preparationPublicActions = new ArrayList<>();
    private URI control;
    private String controlToken;
    private Map<String, Object> managedReceipt;

    @Override
    protected void prepareEvidence(TestInfo test) throws Exception {
        var binding = json.readTree(Files.readAllBytes(Path.of(required("PROCUREMENT_PUBLIC_SOURCE_READINESS_INPUT"))));
        Path app = Path.of(binding.path("applicationCoreRoot").asText()).toRealPath();
        assertEquals(binding.path("applicationCoreCommit").asText(), gitRead(app, "rev-parse", "HEAD").strip());
        assertEquals("", gitRead(app, "status", "--porcelain").strip());
        assertEquals("", gitRead(app, "diff", "50924063538e4181cb601300f0c26c6bbfcce2f0", "HEAD", "--", "platform/src/main").strip());
        Path loaded = Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        assertEquals(app.resolve("platform/build/classes/java/test").toRealPath(), loaded);
        Path packageRoot = app.resolve("platform/src/test/java/com/auraboot/framework");
        assertEquals(binding.path("nativeHostTestSha256").asText(), sha(packageRoot.resolve("plugin/pf4j/ProcurementOrderUiFixtureProducerIT.java")));
        assertEquals(binding.path("nativeSharedFixtureSha256").asText(), sha(packageRoot.resolve("plugin/pf4j/ProcurementPublicSourceNativeFixture.java")));
        assertEquals(binding.path("nativeInitializationFixtureSha256").asText(), sha(packageRoot.resolve("notification/sourcebinding/ProductQuoteNativeInitialization.java")));
        encryptionProofPath = Path.of(binding.path("encryptionConfigProofPath").asText()).toRealPath();
        Path workspace = Path.of(binding.path("workspaceRoot").asText()).toRealPath();
        assertTrue(encryptionProofPath.startsWith(workspace.resolve(".workspace/evidence/procurement-web-stack-20261010").toRealPath()));
        encryptionProofSha256 = sha(encryptionProofPath);
        assertEquals(binding.path("encryptionConfigProofSha256").asText(), encryptionProofSha256);
        JsonNode proof = json.readTree(encryptionProofPath.toFile());
        assertEquals(binding.path("sourceSet").path("oss").asText(), proof.path("sourceCommit").asText());
        assertEquals(sha(app.resolve("platform/src/main/java/com/auraboot/framework/common/crypto/FieldEncryptionService.java")), proof.path("fieldEncryptionServiceSha256").asText());
        assertEquals(sha(app.resolve("platform/platform-app/src/main/resources/application.yml")), proof.path("applicationYmlSha256").asText());
        for (String prefix : List.of("runtimeAdmission", "runtimeEnvironmentEvidence", "runtimeProfileEvidence")) {
            Path artifact = Path.of(proof.path(prefix + "Path").asText()).toRealPath();
            assertTrue(artifact.startsWith(workspace.resolve(".workspace/evidence/procurement-web-stack-20261010").toRealPath()));
            assertEquals(proof.path(prefix + "Sha256").asText(), sha(artifact));
        }
        JsonNode runtimeProof = json.readTree(Path.of(proof.path("runtimeAdmissionPath").asText()).toFile());
        assertEquals("ok", runtimeProof.path("verdict").asText());
        assertEquals("procurement-web-stack-20261010", runtimeProof.path("runtime").asText());
        assertEquals(proof.path("runtimeMode").asText(), runtimeProof.path("metadata").path("mode").asText());
        List<JsonNode> runtimeCore = java.util.stream.StreamSupport.stream(runtimeProof.path("sources").spliterator(), false)
                .filter(row -> row.path("key").asText().equals("oss")).toList();
        assertEquals(1, runtimeCore.size());
        assertEquals(proof.path("sourceCommit").asText(), runtimeCore.getFirst().path("actual").path("commit").asText());
        JsonNode environmentProof = json.readTree(Path.of(proof.path("runtimeEnvironmentEvidencePath").asText()).toFile());
        assertEquals("procurement-web-stack-20261010", environmentProof.path("runtime").asText());
        assertEquals(proof.path("environmentKeyState").asText(), environmentProof.path("fieldEncryptionKeyState").asText());
        JsonNode profileProof = json.readTree(Path.of(proof.path("runtimeProfileEvidencePath").asText()).toFile());
        assertEquals("procurement-web-stack-20261010", profileProof.path("runtime").asText());
        assertEquals(proof.path("actualSpringProfiles").asText(), profileProof.path("actualSpringProfiles").asText());
        assertTrue(System.getenv().containsKey("PROCUREMENT_ORDER_UI_FIELD_ENCRYPTION_KEY"), "Root matching runtime value must be explicitly injected, even blank");
        encryptionMode = validateEncryptionMode(proof, System.getenv("PROCUREMENT_ORDER_UI_FIELD_ENCRYPTION_KEY"));
        assertEquals(binding.path("encryptionMode").asText(), encryptionMode);
        String probe = "SyntheticNativeEncryptionModeProbe";
        String encrypted = encryptionService.encrypt(probe);
        assertEquals(probe, encryptionService.decrypt(encrypted));
        if (encryptionMode.equals("dev-passthrough")) assertEquals(probe, encrypted);
        else assertTrue(encrypted.startsWith("ENC:"));
        super.prepareEvidence(test);
        writeArtifact("encryption-mode-admission.json", Map.of("mode", encryptionMode,
                "proofPath", encryptionProofPath.toString(), "proofSha256", encryptionProofSha256,
                "actualSpringProfiles", proof.path("actualSpringProfiles").asText(), "nonSecretBeanProbeMatched", true));

    }

    @Test void producePendingFailedUnknownAndMixedSupplierExecutionPrerequisites() throws Exception {
        try {
            prepareExecutionFixture();
            preparationCompleted = true;
            preparationStage = "native-fixture-completed";
        } finally {
            writeArtifact("order-ui-producer-stage.json", Map.of("stage", preparationStage,
                    "completed", preparationCompleted, "kind", "procurement-order-execution-ui-native-v1",
                    "receiptExists", Files.exists(caseEvidence.resolve("order-ui-fixture-receipt.json")),
                    "actualProviderIsSynthetic", true, "browserExecuted", 0));
        }
    }

    /** Update only the exact tenant credential created by the shared loopback prerequisites. */
    static CloudConfigSaveRequest managedSecretUpdateRequest(
            com.auraboot.framework.cloudconfig.entity.CloudConfig existing, Long tenantId,
            String providerCode, String configuration) {
        assertNotNull(existing, "Existing test-owned integration credential required");
        assertNotNull(tenantId);
        assertEquals(tenantId, existing.getTenantId(), "Cannot borrow a foreign/platform credential");
        assertEquals("tenant", existing.getConfigLevel());
        assertEquals("integration", existing.getServiceType());
        assertEquals(providerCode, existing.getProviderCode());
        assertNotNull(existing.getPid());
        assertFalse(existing.getPid().isBlank());
        var request = new CloudConfigSaveRequest();
        request.setPid(existing.getPid());
        request.setConfigLevel("tenant");
        request.setServiceType("integration");
        request.setProviderCode(providerCode);
        request.setEnabled(true);
        request.setPriority(1);
        request.setConfig(configuration);
        return request;
    }

    private void prepareExecutionFixture() throws Exception {
        preparationStage = "managed-provider-admission";
        admitManagedProvider();
        preparationStage = "source-allocation-prerequisites";
        assertNotNull(preparationToken);
        List<Group> groups = new ArrayList<>();
        groups.add(prepareGroup("pending", "21", 1, true));
        groups.add(prepareGroup("failed", "22", 1, false));
        groups.add(prepareGroup("unknown", "23", 1, false));
        groups.add(prepareGroup("mixed", "24", 2, false));
        groups.add(prepareGroup("createSuccess", "25", 1, false));
        groups.add(prepareGroup("unknownCreation", "26", 1, false));
        groups.add(prepareGroup("concurrentCreation", "27", 1, false));
        groups.add(prepareGroup("partialCreation", "28", 2, false));
        String erpSecret = required("PROCUREMENT_ORDER_UI_ERP_SECRET");
        assertTrue(validSyntheticCredential(erpSecret, 16));
        String erpUsername = provider + "-reader";
        String erpAppId = provider + "-app";
        initializedConnectionUpdate(Map.of("customer_pcba_kd_base_url",control.toString(),
                "customer_pcba_kd_username",erpUsername,"customer_pcba_kd_app_id",erpAppId));
        context();
        var existingSecret = configs.getEffectiveConfig(tenant, "integration", provider);
        var secret = managedSecretUpdateRequest(existingSecret, tenant, provider,
                json.writeValueAsString(Map.of("appSecret", erpSecret)));
        configs.saveConfig(secret);
        var updatedSecret = configs.getEffectiveConfig(tenant, "integration", provider);
        assertEquals(secret.getPid(), managedSecretUpdateRequest(updatedSecret, tenant, provider, "{}").getPid());
        writeArtifact("managed-provider-secret-update.json", Map.of("configPid", secret.getPid(),
                "tenantId", tenant.toString(), "configLevel", "tenant", "serviceType", "integration",
                "providerCode", provider, "existingConfigUpdated", true, "secretValuePersistedInEvidence", false));
        preparationStage = "combined-server-policy-import";
        configureCombinedPolicy();
        List<Map<String, Object>> markerSpecs = new ArrayList<>();
        List<Map<String, Object>> readRows = new ArrayList<>();
        for (Group group : groups) for (int index = 0; index < group.executionPids().size(); index++) {
            String executionPid = group.executionPids().get(index);
            var execution = fixture(SYNC, () -> db.getById(domainModel("execution"), executionPid));
            String marker = execution.get("qo_pr_execution_execution_marker").toString();
            String orderId = provider + "-" + group.stage() + "-" + index;
            String bill = "LOCAL-UI-" + group.stage() + "-" + index;
            String mode = responseMode(group.stage(), index);
            markerSpecs.add(Map.of("marker", marker, "mode", mode, "orderId", orderId, "billNo", bill));
            readRows.add(readRow(group, index, execution, orderId, bill));
        }
        preparationStage = "managed-provider-registration";
        controlPost("register", Map.of("namespace", provider,
                "account", Map.of("acctID", provider, "username", erpUsername, "appId", erpAppId,
                        "secretSha256", digest(erpSecret.getBytes(StandardCharsets.UTF_8))),
                "markers", markerSpecs, "readColumns", ERP_COLUMNS,
                "readRows", readRows));
        preparationStage = "actual-actors-and-denied-no-write";
        String password = required("PROCUREMENT_ORDER_UI_FIXTURE_PASSWORD");
        assertTrue(validSyntheticCredential(password, 12), "Only an explicit synthetic fixture password is admitted");
        String allowed = uiActor(true, password);
        Map<String, Object> allowedIdentity = new LinkedHashMap<>(retainedActors.getLast());
        String denied = uiActor(false, password);
        Map<String, Object> deniedIdentity = new LinkedHashMap<>(retainedActors.getLast());
        for (Map<String, Object> identity : List.of(allowedIdentity, deniedIdentity)) {
            identity.put("dataScopeAuthority", "synthetic tenant only");
            identity.put("credentialEnvironment", "PROCUREMENT_ORDER_UI_FIXTURE_PASSWORD");
        }
        var beforeDenied = snapshot();
        JsonNode providerBeforeDenied = controlState();
        assertEquals(403, orderCall(denied, groups.getFirst().executionPids().getFirst(), Map.of()).status());
        assertEquals(beforeDenied, snapshot());
        assertEquals(providerBeforeDenied, controlState(), "Denied actor cannot reach the provider");
        JsonNode beforeProvider = controlState();
        Map<String, Object> beforeBusiness = snapshot();
        preparationStage = "native-states-public-command-generation";
        for (Group group : groups) if (List.of("failed", "unknown", "mixed").contains(group.stage())) {
            for (String executionPid : group.executionPids()) {
                Result outcome = orderCall(allowed, executionPid, Map.of());
                assertEquals(group.stage().equals("unknown") ? 400 : 200, outcome.status());
            }
        }
        Map<String, Object> afterBusiness = snapshot();
        for (String entity : List.of("task", "source", "quote", "quote_line", "history", "submission",
                "submission_line", "allocation", "externalReferences", "order", "order_line")) {
            assertEquals(beforeBusiness.get(entity), afterBusiness.get(entity), "Preparation may change only execution checkpoints: " + entity);
        }
        List<Map<String, Object>> retained = new ArrayList<>();
        JsonNode providerState = controlState();
        for (Group group : groups) {
            List<Map<String, Object>> executionRows = new ArrayList<>();
            for (int index = 0; index < group.executionPids().size(); index++) {
                String executionPid = group.executionPids().get(index);
                Map<String, Object> row = fixture(SYNC, () -> db.getById(domainModel("execution"), executionPid));
                String expected = initialState(group.stage(), index);
                assertEquals(expected, row.get("qo_pr_execution_state"));
                String marker = row.get("qo_pr_execution_execution_marker").toString();
                JsonNode ledger = providerState.path("markers").path(marker);
                assertEquals(expected.equals("pending") ? 0 : 1, ledger.path("saveCount").asInt(-1));
                assertEquals(expected.equals("succeeded") || expected.equals("unknown"), ledger.path("created").asBoolean());
                assertEquals(0, ledger.path("queryCount").asInt(-1));
                executionRows.add(row);
            }
            var stage = new LinkedHashMap<String, Object>();
            stage.put("stage", group.stage());
            stage.put("taskPid", group.taskPid());
            stage.put("sourcePid", group.sourcePid());
            stage.put("entryId", group.entry());
            stage.put("quantities", group.quantities());
            stage.put("executionRows", executionRows);
            stage.put("offers", group.offers().stream().map(offer -> Map.of("supplierPid", offer.supplier(),
                    "supplierExternalId", offer.externalSupplier(), "quotePid", offer.submitted().quote(),
                    "submissionPid", offer.submitted().submission(), "sourceRevision", offer.submitted().sourceRevision())).toList());
            stage.put("allocationRows", fixture(SYNC, () -> db.query(domainModel("allocation"), Map.of("qo_pr_allocation_task_pid", group.taskPid()))));
            retained.add(stage);
        }
        preparationStage = "receipt-admission-and-sealing";
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("schemaVersion", 1);
        receipt.put("kind", "procurement-order-execution-ui-native-v1");
        var bindingPath = Path.of(required("PROCUREMENT_PUBLIC_SOURCE_READINESS_INPUT")).toRealPath();
        var binding = json.readTree(Files.readAllBytes(bindingPath));
        Path app = Path.of(binding.path("applicationCoreRoot").asText()).toRealPath();
        String actualHead = gitRead(app, "rev-parse", "HEAD").strip();
        assertEquals(binding.path("applicationCoreCommit").asText(), actualHead);
        assertEquals("", gitRead(app, "status", "--porcelain").strip());
        String mainBase = "50924063538e4181cb601300f0c26c6bbfcce2f0";
        assertEquals("", gitRead(app, "diff", mainBase, "HEAD", "--", "platform/src/main").strip());
        Path test = app.resolve("platform/src/test/java/com/auraboot/framework/plugin/pf4j/ProcurementOrderUiFixtureProducerIT.java").toRealPath();
        Path shared = test.resolveSibling("ProcurementPublicSourceNativeFixture.java");
        assertEquals(binding.path("nativeHostTestSha256").asText(), sha(test));
        assertEquals(binding.path("nativeSharedFixtureSha256").asText(), sha(shared));
        receipt.put("applicationCoreRoot", app.toString());
        receipt.put("applicationCoreCommit", actualHead);
        receipt.put("nativeHostBaseCommit", mainBase);
        receipt.put("nativeHostTestPath", test.toString());
        receipt.put("nativeHostTestSha256", sha(test));
        receipt.put("nativeSharedFixturePath", shared.toString());
        receipt.put("nativeSharedFixtureSha256", sha(shared));
        Path initializer = app.resolve("platform/src/test/java/com/auraboot/framework/notification/sourcebinding/ProductQuoteNativeInitialization.java").toRealPath();
        assertEquals(binding.path("nativeInitializationFixtureSha256").asText(), sha(initializer));
        receipt.put("nativeInitializationFixturePath", initializer.toString());
        receipt.put("nativeInitializationFixtureSha256", sha(initializer));
        Map<String, String> sources = canonicalSources(binding.path("sourceSet"));
        assertEquals(sources, canonicalSources(json.readTree(caseEvidence.resolve("readiness.json").toFile()).path("sourceCommits")));
        receipt.put("sourceSet", sources);
        receipt.put("sourceCommits", sources);
        receipt.put("encryptionMode", encryptionMode);
        receipt.put("encryptionConfigProofPath", encryptionProofPath.toString());
        receipt.put("encryptionConfigProofSha256", encryptionProofSha256);
        receipt.put("sourceBindingPath", bindingPath.toString());
        receipt.put("sourceBindingSha256", sha(bindingPath));
        receipt.put("productJarSha256", binding.path("productJarSha256").asText());
        receipt.put("nativeReadinessPath", caseEvidence.resolve("readiness.json").toString());
        receipt.put("nativeReadinessSha256", sha(caseEvidence.resolve("readiness.json")));
        receipt.put("serverPolicyPath", caseEvidence.resolve("ui-order-server-policy.json").toString());
        receipt.put("serverPolicySha256", sha(caseEvidence.resolve("ui-order-server-policy.json")));
        receipt.put("runtime", "procurement-web-stack-20261010");
        receipt.put("database", "enterprise_22");
        receipt.put("redisDatabase", 13);
        receipt.put("connectionPid", connection);
        receipt.put("accountId", provider);
        receipt.put("account", Map.of("acctID", provider, "username", erpUsername, "appId", erpAppId));
        receipt.put("tenantId", tenant.toString());
        receipt.put("namespace", provider);
        receipt.put("buyer", allowedIdentity);
        receipt.put("buyerWithoutIntegration", deniedIdentity);
        receipt.put("credentialEnvironment", "PROCUREMENT_ORDER_UI_FIXTURE_PASSWORD");
        receipt.put("nativeStates", retained.subList(0, 4));
        receipt.put("scenarios", retained.subList(4, 8));
        Path managedReceiptPath = Path.of(required("PROCUREMENT_SOURCE_PLUGIN_DIR")).toRealPath()
                .getParent().getParent().resolve("processes/erp-fixture-receipt.json").toRealPath();
        receipt.put("managedProviderReceiptPath", managedReceiptPath.toString());
        receipt.put("managedProviderReceiptSha256", sha(managedReceiptPath));
        receipt.put("managedProviderRegistrationSha256", providerState.path("registrationSha256").asText());
        assertTrue(providerState.path("registrationSha256").asText().matches("[a-f0-9]{64}"));
        receipt.put("managedProviderReceipt", managedReceipt);
        receipt.put("managedProviderStateBefore", beforeProvider);
        receipt.put("managedProviderStateAfter", providerState);
        receipt.put("businessSnapshot", afterBusiness);
        receipt.put("businessSnapshotTables", snapshotTables());
        receipt.put("immutableBusinessBefore", beforeBusiness);
        receipt.put("retainedAfterNative", true);
        receipt.put("externalERPOutcomeSynthetic", true);
        receipt.put("actualCustomerERPVerified", false);
        receipt.put("browserExecuted", 0);
        receipt.put("membershipInitialFactsSynthetic", true);
        receipt.put("producerClass", getClass().getName());
        receipt.put("producerMethod", "producePendingFailedUnknownAndMixedSupplierExecutionPrerequisites");
        receipt.put("initializationPrincipal", initializationPrincipal);
        receipt.put("initializationCommandResults", initializationResults);
        receipt.put("directoryInitializationCommandResults",directoryInitializationResults);
        receipt.put("connectionUpdateCommandResults",connectionUpdateResults);
        writeArtifact("execution-preparation-public-actions.json",preparationPublicActions);
        receipt.put("preparationPublicActions",preparationPublicActions);
        receipt.put("preparationMakerPermissions",preparationPermissionCodes());
        receipt.put("preparationMaker",preparationIdentity);
        Path ownerArtifact=caseEvidence.resolve("order-ui-integration-owner-appendix.json");
        receipt.put("integrationOwnerAppendixPath",ownerArtifact.toString());
        receipt.put("integrationOwnerAppendixSha256",sha(ownerArtifact));
        receipt.put("initializationAppendixPath", caseEvidence.resolve("order-ui-initialization-appendix.json").toString());
        receipt.put("initializationAppendixSha256", sha(caseEvidence.resolve("order-ui-initialization-appendix.json")));
        writeArtifact("order-ui-fixture-receipt.json", receipt);
    }

    private Map<String, Object> snapshotTables() {
        return fixture(SYNC, () -> {
            var tables = new LinkedHashMap<String, Object>();
            for (String entity : List.of("task", "source", "quote", "quote_line", "history", "submission",
                    "submission_line", "allocation", "execution", "order", "order_line", "externalReferences")) {
                String model = entity.equals("externalReferences") ? "customer_pcba_int_external_ref" : domainModel(entity);
                String table = metadata.getTableName(model);
                assertTrue(table.matches("[a-zA-Z_][a-zA-Z0-9_]{0,62}"));
                var definition = metadata.getModelDefinitionFromDb(model).orElseThrow();
                assertEquals(table, definition.getTableName());
                var row = postgresMetadata(model);
                assertEquals(tenant, row.get("tenant_id"));
                assertEquals(definition.getId(), row.get("id"));
                assertEquals("published", row.get("status"));
                var jdbc = new org.springframework.jdbc.core.JdbcTemplate(postgres);
                assertEquals(table, jdbc.queryForObject("SELECT to_regclass(?)::text", String.class, table));
                tables.put(entity, Map.of("modelCode", model, "physicalTable", table, "actualTenantModel", row));
            }
            return tables;
        });
    }

    private Map<String, Object> postgresMetadata(String model) {
        return new org.springframework.jdbc.core.JdbcTemplate(postgres).queryForMap(
                "SELECT id,pid,code,tenant_id,plugin_pid,status FROM ab_meta_model WHERE tenant_id=? AND code=? AND is_current=TRUE AND deleted_flag=FALSE",
                tenant, model);
    }

    @Override
    protected Map<String, Object> orderReadContract() {
        var contract = new LinkedHashMap<String, Object>(super.orderReadContract());
        var fields = new LinkedHashMap<String, String>();
        for (int index = 0; index < READ_COLUMNS.size(); index++) fields.put(READ_COLUMNS.get(index), ERP_COLUMNS.get(index));
        contract.put("fields", fields);
        return contract;
    }

    private void configureCombinedPolicy() throws Exception {
        context();
        Path original = Path.of(procurementDirectory);
        Path formalOrigin=immutableProductionDirectory.resolve("config/commands/procurement.json");
        requireUnchangedFormalCommands(formalOrigin,original.resolve("config/commands/procurement.json"));
        Path copy = Files.createDirectory(caseEvidence.resolve("ui-order-dsl"));
        try (var files = Files.walk(original)) {
            for (Path path : files.toList()) {
                Path target = copy.resolve(original.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(target); else Files.copy(path, target);
            }
        }
        Path file = copy.resolve("config/commands/procurement.json");
        var definitions = json.readTree(Files.readAllBytes(file));
        int changed = 0;
        for (var definition : definitions) {
            String code = definition.path("code").asText();
            String key = GENERATE.equals(code) ? "procurementOrderWriteContracts"
                    : SYNC_ORDERS.equals(code) || SYNC_CLOSURES.equals(code) ? "procurementOrderReadContracts" : null;
            if (key == null) continue;
            var policies = (com.fasterxml.jackson.databind.node.ArrayNode) definition.path("handlerParams").path(key);
            assertTrue(policies.isEmpty(), "Production default must remain unconfigured");
            policies.add(json.valueToTree(GENERATE.equals(code) ? orderContract() : orderReadContract()));
            changed++;
        }
        assertEquals(3, changed);
        Files.write(file, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(definitions));
        var preview = imports.parseDirectory(copy.toString(), true);
        writeArtifact("ui-order-policy-import-preview.json", preview);
        assertTrue(preview.isValid(), "Actual combined policy preview: " + preview.getErrors());
        var request = com.auraboot.framework.plugin.dto.imports.ImportRequest.builder()
                .importId(preview.getImportId()).conflictStrategy(com.auraboot.framework.plugin.dto.imports.ImportRequest.ConflictStrategy.OVERWRITE)
                .autoPublishFields(true).autoPublishModels(true).autoPublishCommands(true).autoPublishPages(true)
                .createResourcePermissions(true).build();
        var imported = imports.execute(preview.getImportId(), request);
        writeArtifact("ui-order-policy-import-result.json", imported);
        assertTrue(imported.isSuccess(), "Actual combined policy import: " + imported.getErrorMessage());
        assertEquals("com.auraboot.quote-procurement", imported.getPluginId());
        List<Map<String, Object>> commandProofs = new ArrayList<>();
        for (String code : List.of(GENERATE, SYNC_ORDERS, SYNC_CLOSURES)) {
            var command = commands.findByCode(code);
            assertEquals(tenant, command.getTenantId());
            assertEquals(domainModel("execution"), command.getModelCode());
            var execution = json.readTree(command.getExecutionConfig());
            assertEquals(code, execution.path("handler").asText());
            assertEquals("qo.procurement.integrate", execution.path("permissions").get(0).asText());
            String key = GENERATE.equals(code) ? "procurementOrderWriteContracts" : "procurementOrderReadContracts";
            assertEquals(1, execution.path("handlerParams").path(key).size());
            assertEquals("published", command.getStatus());
            assertEquals(Boolean.TRUE, command.getIsCurrent());
            var raw = new org.springframework.jdbc.core.JdbcTemplate(postgres).queryForMap(
                    "SELECT id,pid,code,tenant_id,plugin_pid,status,is_current,version FROM ab_command_definition WHERE tenant_id=? AND pid=? AND code=? AND is_current=TRUE AND deleted_flag=FALSE",
                    tenant, command.getPid(), code);
            assertEquals(command.getId(), raw.get("id"));
            assertEquals(tenant, raw.get("tenant_id"));
            assertEquals(imported.getPluginPid(), raw.get("plugin_pid"));
            JsonNode originalDefinition = json.readTree(formalOrigin.toFile());
            JsonNode rawOriginal = java.util.stream.StreamSupport.stream(originalDefinition.spliterator(), false)
                    .filter(row -> code.equals(row.path("code").asText())).findFirst().orElseThrow();
            JsonNode configuredDefinition = java.util.stream.StreamSupport.stream(definitions.spliterator(), false)
                    .filter(row -> code.equals(row.path("code").asText())).findFirst().orElseThrow();
            assertEquals(configuredDefinition.path("handlerParams").path(key), execution.path("handlerParams").path(key));
            assertTrue(rawOriginal.path("handlerParams").path(key).isArray());
            assertTrue(rawOriginal.path("handlerParams").path(key).isEmpty());
            var proof = new LinkedHashMap<String, Object>();
            proof.put("id", command.getId());
            proof.put("pid", command.getPid());
            proof.put("code", code);
            proof.put("modelCode", command.getModelCode());
            proof.put("tenantId", command.getTenantId().toString());
            proof.put("pluginPid", raw.get("plugin_pid"));
            proof.put("status", command.getStatus());
            proof.put("isCurrent", command.getIsCurrent());
            proof.put("version", command.getVersion());
            proof.put("originalDefinition", rawOriginal);
            proof.put("configuredDefinition", configuredDefinition);
            proof.put("originalDefinitionSha256", digest(json.writeValueAsBytes(rawOriginal)));
            proof.put("configuredDefinitionSha256", digest(json.writeValueAsBytes(configuredDefinition)));
            proof.put("actualExecutionConfig", execution);
            proof.put("actualExecutionConfigSha256", executionConfigObjectSha256(json, execution));
            proof.put("rawExecutionConfigSha256", digest(command.getExecutionConfig().getBytes(StandardCharsets.UTF_8)));
            commandProofs.add(proof);
        }
        var policy = new LinkedHashMap<String, Object>();
        policy.put("schemaVersion", 1);
        policy.put("kind", "procurement-order-ui-test-owned-policy-v1");
        policy.put("originCommandPath", formalOrigin.toRealPath().toString());
        policy.put("originCommandSha256", sha(formalOrigin));
        policy.put("configuredCommandPath", file.toRealPath().toString());
        policy.put("configuredCommandSha256", sha(file));
        policy.put("tenantId", tenant.toString());
        policy.put("pluginPid", imported.getPluginPid());
        policy.put("pluginId", imported.getPluginId());
        policy.put("previewPath", caseEvidence.resolve("ui-order-policy-import-preview.json").toString());
        policy.put("previewSha256", sha(caseEvidence.resolve("ui-order-policy-import-preview.json")));
        policy.put("importResultPath", caseEvidence.resolve("ui-order-policy-import-result.json").toString());
        policy.put("importResultSha256", sha(caseEvidence.resolve("ui-order-policy-import-result.json")));
        policy.put("commandProofs", commandProofs);
        policy.put("productionDefaultChanged", false);
        policy.put("writeContract", orderContract());
        policy.put("readContract", orderReadContract());
        writeArtifact("ui-order-server-policy.json", policy);
    }

    /** Replaces only this producer's first prerequisite writes; original Source/Order defaults stay unchanged. */
    @Override protected void prepareInitialSourceRecords() throws Exception {
        prepareInitializationAppendix();
        preparationToken=preparationActor();
        preparationIdentity=new LinkedHashMap<>(retainedActors.getLast());
        String product=initializedDirectoryCreate("prod_product",Map.of("prod_code",provider,"prod_name","Public source material",
                "prod_type","raw_material","prod_unit","pcs","prod_status","active"));
        connection=initializedDirectoryCreate("customer_pcba_int_kingdee_connection",Map.of("customer_pcba_kd_profile_name",provider,
                "customer_pcba_kd_status","active","customer_pcba_kd_acct_id",provider,"customer_pcba_kd_base_url","http://127.0.0.1:"+server.getAddress().getPort(),
                "customer_pcba_kd_app_id","local-app","customer_pcba_kd_username","local-reader","customer_pcba_kd_app_secret_ref","cloud-config:"+provider,"customer_pcba_kd_auth_type","app-secret"));
        initializedDirectoryCreate("customer_pcba_int_external_ref",Map.of("customer_pcba_ref_external_system","KINGDEE","customer_pcba_ref_instance_key",connection,
                "customer_pcba_ref_account_id",provider,"customer_pcba_ref_external_form_id","BD_MATERIAL","customer_pcba_ref_external_id","900",
                "customer_pcba_ref_local_model_code","prod_product","customer_pcba_ref_local_pid",product));
        task=publicAction(preparationToken,"qo_procurement_task_common:create",null,Map.of("title",provider,"connectionPid",connection)).path("recordPid").asText();
        assertFalse(task.isBlank());
    }

    private Group prepareGroup(String stage, String entry, int suppliers, boolean initialTask) throws Exception {
        if (!initialTask) {
            task = publicAction(preparationToken,"qo_procurement_task_common:create",null,
                    Map.of("title",provider+"-"+stage,"connectionPid",connection)).path("recordPid").asText();
            assertFalse(task.isBlank());
            retainedTaskPids.add(task);
        }
        response = row(entry, 1000, "C");
        if (initialTask) {
            configurePolicy();
        }
        assertEquals(200, call(token, selected(entry)).status());
        String currentTask = task;
        String source = pid(sources().getFirst());
        List<Offer> offers = prepareOffers(stage, suppliers, source);
        List<String> quantities = suppliers == 1 ? List.of("300.000001") : List.of("150", "150.000001");
        var allocations = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < offers.size(); index++) {
            Submitted quote = offers.get(index).submitted();
            allocations.add(Map.of("quotePid", quote.quote(), "sourcePid", source, "submissionPid", quote.submission(),
                    "quantity", quantities.get(index)));
        }
        publicAction(preparationToken,"qo_procurement_task_common:allocate",currentTask,
                Map.of("sourceDemands",List.of(Map.of("sourcePid",source,"sourceRevision",offers.getFirst().submitted().sourceRevision(),
                        "quantity","300.000001")),"allocations",allocations));
        List<String> executionPids = new ArrayList<>();
        for (Offer offer : offers) {
            var found = fixture(SYNC, () -> db.query(domainModel("execution"),
                    Map.of("qo_pr_execution_task_pid", currentTask, "qo_pr_execution_supplier_pid", offer.supplier())));
            assertEquals(1, found.size());
            assertEquals("pending", found.getFirst().get("qo_pr_execution_state"));
            executionPids.add(pid(found.getFirst()));
        }
        return new Group(stage, currentTask, source, entry, offers, executionPids, quantities);
    }

    private List<Offer> prepareOffers(String stage, int count, String source) throws Exception {
        String category = initializedDirectoryCreate("pr_spend_category",Map.of("pr_spc_code",provider+"-"+stage,
                "pr_spc_name","Explicit order UI fixture category"));
        List<String> supplierPids=new ArrayList<>();
        for(int index=0;index<count;index++) supplierPids.add(initializedCreate("pr_supplier_common",
                Map.of("pr_supplier_code",provider+"-"+stage+"-"+index,"pr_supplier_name","Order UI "+stage+" "+index,
                        "pr_supplier_status","enabled","qo_pr_supplier_account_id",provider)));
        publicAction(preparationToken,"qo_procurement_category_policy_common:create",null,
                Map.of("categoryPid",category,"connectionPid",connection,"supplierPids",supplierPids,"defaultSupplierPids",supplierPids));
        long revision=fixture(SYNC,()->new BigDecimal(db.getById("pr_purchase_request",source).get("qo_pr_source_revision").toString()).longValueExact());
        publicAction(preparationToken,"qo_procurement_task_common:classify_source",task,
                Map.of("sourcePid",source,"sourceRevision",revision,"categoryPid",category));
        long currentRevision=fixture(SYNC,()->new BigDecimal(db.getById("pr_purchase_request",source).get("qo_pr_source_revision").toString()).longValueExact());
        assertEquals(revision+1,currentRevision);
        publicAction(preparationToken,"qo_procurement_task_common:dispatch",task,
                Map.of("supplierPids",supplierPids,"cutoffAt","2066-11-15T08:00:00Z"));
        List<Offer> offers=new ArrayList<>();
        for(int index=0;index<count;index++) {
            String supplier=supplierPids.get(index);
            var bid=fixture(SYNC,()->db.query("s2p_bid",Map.of("s2p_bid_sourcing_event_id",task,"s2p_bid_supplier_id",supplier)));
            assertEquals(1,bid.size());String quote=pid(bid.getFirst());
            context();String key=provider+"-"+stage+"-"+index;
            var user=users.signUp(key+"@example.invalid",PASSWORD,"Explicit supplier fixture",key);
            var member=members.addMember(user.getId(),tenant,"active");
            String supplierToken=actorToken(user.getId(),user.getPid(),member.getId(),user.getEmail(),List.of("meta.command.execute","qo.srm.use"));
            String contact=initializedCreate("pr_supplier_pcba_contact",Map.of("pr_spc_supplier_id",supplier,"pr_spc_contact_name","Order UI supplier","qo_pr_contact_state","active"));
            initializedCreate("qo_procurement_membership_common",Map.of("qo_pr_membership_key",key,"qo_pr_membership_user_pid",user.getPid(),
                    "qo_pr_membership_supplier_pid",supplier,"qo_pr_membership_contact_pid",contact,"qo_pr_membership_state","active"));
            var line=Map.<String,Object>of("sourcePid",source,"sourceRevision",currentRevision,"unitPrice","1.25","currency","CNY","unit","PCS",
                    "taxRate","0.13","taxIncluded",true,"availableQty","600","deliveryDate","2066-12-01");
            publicAction(supplierToken,"qo_procurement_quote_common:save_draft",quote,Map.of("revision",0,"lines",List.of(line),"paymentTerms","Net 30"));
            var formal=publicAction(supplierToken,"qo_procurement_quote_common:submit",quote,Map.of("revision",1));
            String external="supplier-"+stage+"-"+index;
            initializedDirectoryCreate("customer_pcba_int_external_ref",Map.of("customer_pcba_ref_external_system","KINGDEE","customer_pcba_ref_instance_key",connection,
                    "customer_pcba_ref_account_id",provider,"customer_pcba_ref_external_form_id","FixtureSupplier","customer_pcba_ref_external_id",external,
                    "customer_pcba_ref_local_model_code","pr_supplier_common","customer_pcba_ref_local_pid",supplier));
            offers.add(new Offer(new Submitted(source,quote,formal.path("submissionPid").asText(),currentRevision),supplier,external));
            assertFalse(offers.getLast().submitted().submission().isBlank());
        }
        return List.copyOf(offers);
    }

    private String preparationActor() {
        context();String email=provider+"-preparation@example.invalid";
        var user=users.signUp(email,PASSWORD,"Explicit order preparation maker",provider+"-preparation");
        var member=members.addMember(user.getId(),tenant,"active");
        return actorToken(user.getId(),user.getPid(),member.getId(),email,preparationPermissionCodes());
    }

    static List<String> preparationPermissionCodes() {
        var codes=new ArrayList<>(ProcurementSourceUiFixtureProducerIT.MAKER_PERMISSIONS);
        assertEquals(20,codes.size());assertEquals(20,new java.util.HashSet<>(codes).size());return List.copyOf(codes);
    }

    static void requireCompletedPublicResponse(JsonNode body) {
        assertTrue(body.path("code").isTextual());assertEquals("0",body.path("code").textValue());
        assertEquals("completed",body.path("data").path("phaseReached").asText());
        assertTrue(body.path("data").path("data").isObject() || body.path("data").path("data").isArray());
    }

    private JsonNode publicAction(String jwt,String code,String target,Map<String,Object> payload) throws Exception {
        var request=new LinkedHashMap<String,Object>();
        if(target!=null) {
            request.put("targetRecordPid",target);
            request.put("expectedVersion",fixture(SYNC,()->db.getById(commands.findByCode(code).getModelCode(),target).get("row_version")));
        }
        request.put("clientRequestId",java.util.UUID.randomUUID().toString());request.put("payload",payload);
        MetaContext.clear();org.springframework.test.web.servlet.MvcResult result;
        try {
            result=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/meta/commands/execute/"+code)
                    .header("Authorization","Bearer "+jwt).contentType("application/json").content(json.writeValueAsBytes(request))).andReturn();
        } finally {context();}
        assertEquals(200,result.getResponse().getStatus(),"Actual public command "+code);
        var body=json.readTree(result.getResponse().getContentAsByteArray());requireCompletedPublicResponse(body);
        preparationPublicActions.add(Map.of("command",code,"target",target==null?"create":target,"payload",payload,"httpStatus",200,"phaseReached","completed"));
        return body.path("data").path("data");
    }

    static void requireUnchangedFormalCommands(Path formalOrigin,Path composedSource) throws Exception {
        org.junit.jupiter.api.Assertions.assertArrayEquals(Files.readAllBytes(formalOrigin),Files.readAllBytes(composedSource),
                "Composed initializer copy must preserve the exact original formal command bytes");
    }

    private void prepareInitializationAppendix() throws Exception {
        immutableProductionDirectory=Path.of(procurementDirectory).toRealPath();
        var proof = ProductQuoteNativeInitialization.prepareOrderUiComposed(Path.of(procurementDirectory),
                caseEvidence.resolve("order-ui-initialization-dsl"), json);
        initializationDirectory = Path.of(proof.get("testDirectory").toString());
        assertTrue(initializationDirectory.toString().length() <= 256);
        var declared=new ArrayList<Path>();for(JsonNode value:json.readTree(required("PROCUREMENT_SOURCE_IMPORT_DIRECTORIES")))declared.add(Path.of(value.asText()));
        Path integrationOwner=ProductQuoteNativeInitialization.sourceUiIntegrationOwner(declared,json);
        var ownerProof=ProductQuoteNativeInitialization.prepareOrderUiIntegration(integrationOwner,caseEvidence.resolve("order-ui-integration-owner-dsl"),json);
        // All copies must be imported before the first directory initializer or connection update.
        importDirectory(Path.of(ownerProof.get("testDirectory").toString()));
        importDirectory(initializationDirectory);
        // Every subsequent same-owner policy copy must retain both initializer families.
        procurementDirectory=initializationDirectory.toString();
        var writers=new LinkedHashMap<String,Object>();
        for(var part:List.of(proof,ownerProof)) {
            @SuppressWarnings("unchecked") var values=(Map<String,Object>)part.get("exactWriterAppendix");writers.putAll(values);
        }
        JsonNode expected=json.valueToTree(writers);
        var allFields=new LinkedHashMap<String,List<String>>(ProductQuoteNativeInitialization.ORDER_UI_FIELDS);
        allFields.putAll(ProductQuoteNativeInitialization.ORDER_DIRECTORY_FIELDS);

        for(var entry:allFields.entrySet()) {
            var definition=metadata.getModelDefinitionFromDb(entry.getKey()).orElseThrow();
            for(String code:entry.getValue()) {
                var field=definition.getFields().stream().filter(f->code.equals(f.getCode())).findFirst().orElseThrow();
                if(expected.has(code))assertEquals(expected.path(code).path("testWriters"),json.valueToTree(field.getAllowedWriterCommands()));
            }
        }
        for(String model:ProductQuoteNativeInitialization.ORDER_UI_FIELDS.keySet()) {
            var command=commands.findByCode(ProductQuoteNativeInitialization.orderUiCommand(model));
            assertEquals(model,command.getModelCode());assertEquals("published",command.getStatus());assertTrue(command.getCode().length()<=64);
        }
        for(String model:ProductQuoteNativeInitialization.ORDER_DIRECTORY_FIELDS.keySet()) {
            var command=commands.findByCode(ProductQuoteNativeInitialization.orderUiDirectoryCommand(model));
            assertEquals(model,command.getModelCode());assertEquals("published",command.getStatus());assertTrue(command.getCode().length()<=64);
        }
        var updater=commands.findByCode(ProductQuoteNativeInitialization.ORDER_CONNECTION_UPDATE_COMMAND);
        assertEquals("customer_pcba_int_kingdee_connection",updater.getModelCode());assertEquals("published",updater.getStatus());assertTrue(updater.getCode().length()<=64);
        writeArtifact("order-ui-integration-owner-appendix.json",ownerProof);
        writeArtifact("order-ui-initialization-appendix.json", proof);
    }

    private String initializedCreate(String model, Map<String, Object> facts) {
        ProductQuoteNativeInitialization.validateOrderUiPayload(model, facts);
        return initializedAction(model,ProductQuoteNativeInitialization.orderUiCommand(model),facts,false);
    }

    private String initializedDirectoryCreate(String model,Map<String,Object> facts) {
        if(!ProductQuoteNativeInitialization.ORDER_DIRECTORY_FIELDS.containsKey(model))throw new IllegalArgumentException("Unsupported order directory initializer");
        if(!facts.keySet().equals(java.util.Set.copyOf(ProductQuoteNativeInitialization.ORDER_DIRECTORY_FIELDS.get(model))))throw new IllegalArgumentException("Exact order directory fields required");
        return initializedAction(model,ProductQuoteNativeInitialization.orderUiDirectoryCommand(model),facts,false);
    }

    private void initializedConnectionUpdate(Map<String,Object> facts) {
        ProductQuoteNativeInitialization.validateOrderConnectionUpdate(facts);
        initializedAction("customer_pcba_int_kingdee_connection",ProductQuoteNativeInitialization.ORDER_CONNECTION_UPDATE_COMMAND,facts,true);
        var actual=fixture(SYNC,()->db.getById("customer_pcba_int_kingdee_connection",connection));
        for(var value:facts.entrySet())assertEquals(value.getValue(),actual.get(value.getKey()));
    }

    private String initializedAction(String model,String command,Map<String,Object> facts,boolean update) {
        Integer updateVersion=update?fixture(SYNC,()->new BigDecimal(db.getById(model,connection).get("row_version").toString()).intValueExact()):null;
        MetaContext.Snapshot previous = MetaContext.snapshot();
        try {
            var initializer = users.findByPid(retainedBootstrapPrincipal.get("userPid").toString());
            assertNotNull(initializer);
            var member = members.findByTenantIdAndUserId(tenant, initializer.getId());
            assertNotNull(member);
            MetaContext.setContext(tenant, initializer.getId(), initializer.getPid(), provider);
            MetaContext.setMemberId(member.getId());
            var login = new AuthenticationRequest();
            login.setEmail(initializer.getEmail());
            login.setPassword(PASSWORD);
            var authenticated = authentication.authenticate(login);
            assertEquals(tenant, authenticated.getTenantId());
            assertEquals(initializer.getId(), authenticated.getUserId());
            assertNotNull(authenticated.getJwt());
            var assigned = userRoles.findByMemberIdAndTenantId(member.getId(), tenant);
            assertFalse(assigned.isEmpty());
            List<String> roleCodes = assigned.stream().map(role -> {
                var actual = roles.getRoleById(role.getRoleId());
                assertNotNull(actual);
                return actual.getCode();
            }).toList();
            assertTrue(userPermissions.hasPermission(tenant, initializer.getId(), member.getId(), "qo.procurement.integrate"));
            initializationPrincipal = Map.of("userPid", initializer.getPid(), "memberId", member.getId().toString(),
                    "authenticationVerified", true, "dataScopeAuthority", "synthetic tenant only", "usedForTestedBusinessActions", false,
                    "actualRoleCodes", roleCodes, "integrationPermissionVerified", true);
            var request = new CommandExecuteRequest();
            request.setPayload(new LinkedHashMap<>(facts));
            String requestKey=java.util.UUID.randomUUID().toString();assertTrue(requestKey.length()<=64 && command.length()<=64);
            request.setClientRequestId(requestKey);
            if(update) {
                request.setTargetRecordPid(connection);
                request.setExpectedVersion(updateVersion);
            }
            assertEquals(initializer.getId(),MetaContext.getCurrentUserId());assertEquals(member.getId(),MetaContext.getCurrentMemberId());
            var result = initializerCommands.execute(command, request);
            assertEquals("completed", result.getPhaseReached());
            Object record = update?connection:result.getData().get("recordPid");
            assertTrue(record instanceof String && !((String) record).isBlank());
            var evidence=new LinkedHashMap<String,Object>(Map.of("modelCode",model,"commandCode",command,"recordPid",record,
                    "phaseReached",result.getPhaseReached(),"clientRequestId",requestKey));
            if(update) {evidence.put("expectedVersion",request.getExpectedVersion());evidence.put("payload",facts);connectionUpdateResults.add(evidence);}
            else if(ProductQuoteNativeInitialization.ORDER_UI_FIELDS.containsKey(model))initializationResults.add(evidence);
            else directoryInitializationResults.add(evidence);
            return record.toString();
        } finally {
            MetaContext.clear();
            MetaContext.restore(previous);
        }
    }

    private String uiActor(boolean integrate, String password) {
        context();String email = provider + "-ui-" + integrate + "@example.invalid";
        var user = users.signUp(email, password, "Order UI fixture actor", provider + "-ui-" + integrate);
        var member = members.addMember(user.getId(), tenant, "active");
        List<String> codes = uiPermissionCodes(integrate);
        return actorToken(user.getId(), user.getPid(), member.getId(), email, codes, password);
    }

    static String validateEncryptionMode(JsonNode proof, String key) {
        assertTrue(proof.isObject());
        assertTrue(proof.path("schemaVersion").isIntegralNumber());
        assertEquals(1, proof.path("schemaVersion").asInt(-1));
        assertEquals("procurement-native-runtime-encryption-proof-v1", proof.path("kind").asText());
        assertEquals("procurement-web-stack-20261010", proof.path("runtime").asText());
        assertEquals("${FIELD_ENCRYPTION_KEY:}", proof.path("configProperty").asText());
        assertTrue(proof.has("actualSpringProfiles") && proof.path("actualSpringProfiles").isTextual());
        String profiles = proof.path("actualSpringProfiles").asText().toLowerCase(java.util.Locale.ROOT);
        String mode = proof.path("mode").asText();
        String state = proof.path("environmentKeyState").asText();
        assertNotNull(key, "Matching managed value must be explicit");
        if (mode.equals("dev-passthrough")) {
            assertTrue(List.of("development", "verification").contains(proof.path("runtimeMode").asText()));
            assertTrue(state.equals("UNSET") || state.equals("EMPTY"));
            assertTrue(key.isBlank());
            assertFalse(profiles.contains("prod"), "Production profile cannot be admitted with a blank key");
            assertTrue(profiles.isBlank() || profiles.contains("dev") || profiles.contains("local") || profiles.contains("test"));
            return mode;
        }
        assertEquals("encrypted", mode);
        assertEquals("SET", state);
        assertFalse(key.isBlank());
        byte[] decoded;
        try {
            decoded = java.util.Base64.getDecoder().decode(key);
        } catch (IllegalArgumentException error) {
            throw new AssertionError("Managed encryption key is not valid Base64");
        }
        assertEquals(32, decoded.length, "Managed AES-256 key must be exactly 32 bytes");
        return mode;
    }

    static boolean validSyntheticCredential(String value, int minimum) {
        return value != null && value.length() >= minimum && value.length() <= 128
                && value.chars().allMatch(c -> c >= 0x21 && c <= 0x7e);
    }

    static Map<String, String> canonicalSources(JsonNode sourceSet) {
        assertTrue(sourceSet.isObject());
        assertEquals(5, sourceSet.size());
        Map<String, String> names = Map.of("oss", "core", "quote", "product", "enterprise", "enterprise", "crm", "crm", "plugins", "plugins");
        var result = new LinkedHashMap<String, String>();
        for (var entry : names.entrySet()) {
            JsonNode value = sourceSet.get(entry.getKey());
            assertNotNull(value, "Missing managed source key: " + entry.getKey());
            assertTrue(value.isTextual() && value.asText().matches("[a-f0-9]{40}"));
            result.put(entry.getValue(), value.asText());
        }
        return Map.copyOf(result);
    }

    static String initialState(String stage, int index) {
        if (List.of("pending", "createSuccess", "unknownCreation", "concurrentCreation", "partialCreation").contains(stage)) return "pending";
        if (stage.equals("mixed") && (index == 0 || index == 1)) return index == 0 ? "succeeded" : "failed";
        if (stage.equals("failed") || stage.equals("unknown")) return stage;
        throw new IllegalArgumentException("UNKNOWN_FIXTURE_STAGE");
    }

    static String responseMode(String stage, int index) {
        initialState(stage, index);
        if (stage.equals("unknown") || stage.equals("unknownCreation")) return "unknown";
        if (stage.equals("failed") || (stage.equals("mixed") || stage.equals("partialCreation")) && index == 1) return "failed";
        return "success";
    }

    static List<String> uiPermissionCodes(boolean integrate) {
        List<String> codes = new ArrayList<>(ORDER_PERMISSIONS);
        codes.addAll(List.of("qo.procurement.manage", "page.page.read", "model.pr_purchase_order.read", "model.pr_purchase_order_line.read", "model.qo_procurement_shipment_common.read",
                "model.qo_procurement_shipment_line_common.read", "model.pr_spend_category.read"));
        if (!integrate) codes.remove("qo.procurement.integrate");
        assertEquals(integrate ? 20 : 19, codes.size());
        assertEquals(codes.size(), new java.util.HashSet<>(codes).size());
        return List.copyOf(codes);
    }

    private Map<String, Object> readRow(Group group, int index, Map<String, Object> execution, String orderId, String bill) {
        List<Object> values = List.of(orderId, bill, orderId + "-line", group.offers().get(index).externalSupplier(), "900",
                execution.get("created_at").toString().substring(0, 10), "LOCAL_APPROVED", "LOCAL_OPEN", group.quantities().get(index),
                "0", "0", "LOCAL-MATERIAL", "Local material", "local", "PCS", "2066-12-01", "100", group.entry());
        assertEquals(READ_COLUMNS.size(), values.size());
        var row = new LinkedHashMap<String, Object>();
        for (int slot = 0; slot < values.size(); slot++) row.put(ERP_COLUMNS.get(slot), values.get(slot));
        return row;
    }

    private void admitManagedProvider() throws Exception {
        Path plugins = Path.of(required("PROCUREMENT_SOURCE_PLUGIN_DIR")).toRealPath();
        Path state = plugins.getParent().getParent();
        Path receiptPath = state.resolve("processes/erp-fixture-receipt.json").toRealPath();
        managedReceipt = json.readValue(Files.readAllBytes(receiptPath), new com.fasterxml.jackson.core.type.TypeReference<>() { });
        JsonNode admission = json.readTree(Files.readAllBytes(Path.of(required("PROCUREMENT_PUBLIC_SOURCE_READINESS_INPUT"))));
        assertEquals(sha(receiptPath), admission.path("managedErpReceiptSha256").asText(), "Root actual managed provider receipt must be sealed");
        assertEquals("registered", managedReceipt.get("status"));
        assertEquals("procurement-web-stack-20261010", managedReceipt.get("runtime"));
        Path tokenPath = state.resolve("processes/erp-fixture-control.token").toRealPath();
        assertEquals(tokenPath, Path.of(required("PROCUREMENT_ERP_CONTROL_TOKEN_FILE")).toRealPath());
        assertEquals(tokenPath.toString(), managedReceipt.get("controlTokenFile"));
        assertEquals(sha(tokenPath), managedReceipt.get("controlTokenFileSha256"));
        assertEquals(sha(Path.of(managedReceipt.get("scriptPath").toString())), managedReceipt.get("scriptSha256"));
        Path descriptor = state.resolve("processes/erp-fixture.tsv").toRealPath();
        assertEquals(descriptor, Path.of(managedReceipt.get("descriptorPath").toString()).toRealPath());
        assertEquals(sha(descriptor), managedReceipt.get("descriptorSha256"));
        assertEquals(sha(state.resolve("processes/ownership.token")), managedReceipt.get("tokenFileSha256"));
        Path quote = Path.of(admission.path("verifiedSourceIdentities").path("quote").path("root").asText()).toRealPath();
        assertEquals(quote.resolve("scripts/procurement_erp_fixture.py").toRealPath(),
                Path.of(managedReceipt.get("scriptPath").toString()).toRealPath());
        assertEquals(quote.toString(), managedReceipt.get("cwd"));
        controlToken = Files.readString(tokenPath).strip();
        assertFalse(controlToken.isBlank());
        int port = ((Number) managedReceipt.get("port")).intValue();
        assertEquals(52169, port);
        control = URI.create("http://127.0.0.1:" + port);
    }

    private JsonNode controlPost(String action, Map<String, Object> payload) throws Exception {
        var request = HttpRequest.newBuilder(control.resolve("/__control/" + action)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").header("X-Aura-ERP-Fixture-Token", controlToken)
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(payload))).build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "Managed provider control rejected sealed fixture request");
        return json.readTree(response.body());
    }

    private JsonNode controlState() throws Exception {
        var request = HttpRequest.newBuilder(control.resolve("/__control/state?namespace=" + provider)).timeout(Duration.ofSeconds(10))
                .header("X-Aura-ERP-Fixture-Token", controlToken).GET().build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        JsonNode state = json.readTree(response.body());
        assertEquals(provider, state.path("namespace").asText());
        assertEquals(managedReceipt.get("scriptSha256"), state.path("scriptSha256").asText());
        return state;
    }

    static String executionConfigObjectSha256(com.fasterxml.jackson.databind.ObjectMapper mapper, JsonNode execution) throws Exception {
        return digest(mapper.writeValueAsBytes(execution));
    }

    private static String digest(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
