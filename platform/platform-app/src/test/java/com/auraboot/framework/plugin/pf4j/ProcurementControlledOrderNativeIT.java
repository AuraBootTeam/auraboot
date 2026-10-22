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

/** Current synthetic backend prerequisites. Only subsequent real HTTP actions qualify P10–12 behavior. */
@SpringBootTest(classes = TestApplication.class, properties = {
        "spring.datasource.url=${TEST_DATABASE_URL}", "spring.data.redis.database=${TEST_REDIS_DATABASE}",
        "aura.plugins.dir=${PROCUREMENT_SOURCE_PLUGIN_DIR}",
        "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.slf4j.Slf4jImpl",
        "logging.level.com.auraboot.framework=INFO", "logging.level.org.mybatis=INFO",
        "logging.level.org.springframework.security=WARN", "logging.level.org.springframework.cache=WARN",
        "security.field-encryption.key=${S06_NATIVE_FIELD_ENCRYPTION_KEY}"})
@ActiveProfiles("integration-test")
@Import(ProcurementPublicSourceNativeFixture.IsolatedScheduling.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "PROCUREMENT_CONTROLLED_ORDER_NATIVE_IT", matches = "true")
class ProcurementControlledOrderNativeIT extends ProcurementPublicSourceNativeFixture {
    private record Offer(Submitted submitted, String supplier, String externalSupplier) { }
    private record Group(String stage, String taskPid, String sourcePid, String entry, List<Offer> offers,
                         List<String> executionPids, List<String> quantities) { }
    @Autowired MetaModelService metadata;
    @Autowired CommandExecutor initializerCommands;
    @Autowired FieldEncryptionService encryptionService;
    private String encryptionMode;
    private Map<String,Object> matchingEncryptionAdmission;
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
    private ProcurementControlledOrderMasterErp master;
    private final List<Map<String,Object>> paymentMappings=new ArrayList<>();
    private final List<Map<String,Object>> supplierIdentities=new ArrayList<>();
    private final List<String> addressPids=new ArrayList<>();
    private Map<String,Object> noMembershipIdentity;
    private String controlToken;
    private Map<String, Object> managedReceipt;

    @org.springframework.test.context.DynamicPropertySource
    static void matchingKey(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("security.field-encryption.key",()->com.auraboot.framework.notification.sourcebinding.S06NativeEncryptionAdmission.requiredKey(System.getenv()));
    }
    @Override protected void admitEncryption() throws Exception {
        var proof=com.auraboot.framework.notification.sourcebinding.S06NativeEncryptionAdmission.admit(System.getenv(),json,encryptionService,getClass(),application.getEnvironment().getActiveProfiles());
        matchingEncryptionAdmission=Map.copyOf(proof);encryptionMode=proof.get("mode").toString();encryptionProofPath=Path.of(required("S06_NATIVE_ENCRYPTION_PROOF_PATH")).toRealPath();
        encryptionProofSha256=sha(encryptionProofPath);writeArtifact("encryption-mode-admission.json",proof);
    }
    @Override protected void prepareEvidence(TestInfo test) throws Exception {
        var binding=json.readTree(Path.of(required("PROCUREMENT_PUBLIC_SOURCE_READINESS_INPUT")).toFile());
        Path app=Path.of(binding.path("applicationCoreRoot").asText()).toRealPath();
        assertEquals(binding.path("applicationCoreCommit").asText(),gitRead(app,"rev-parse","HEAD").strip());assertEquals("",gitRead(app,"status","--porcelain").strip());
        assertEquals("",gitRead(app,"diff","39c92a8e2b15499f236f55f6aa0e804bfba67390","HEAD","--","platform/src/main").strip());
        assertEquals(app.resolve("platform/build/classes/java/test").toRealPath(),Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());
        assertEquals(binding.path("nativeHostTestSha256").asText(),sha(app.resolve("platform/src/test/java/com/auraboot/framework/plugin/pf4j/ProcurementControlledOrderNativeIT.java")));
        super.prepareEvidence(test);
    }
    @Test void produceCurrentStructuredOrderPrerequisites() throws Exception {
        try {
            prepareExecutionFixture();
            preparationCompleted = true;
            preparationStage = "native-fixture-completed";
        } finally {
            writeArtifact("order-ui-producer-stage.json", Map.of("stage", preparationStage,
                    "completed", preparationCompleted, "kind", "procurement-current-structured-order-native-v1",
                    "receiptExists", Files.exists(caseEvidence.resolve("controlled-order-fixture-receipt.json")),
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
        master=new ProcurementControlledOrderMasterErp(provider);master.start();
        configureDirectoryPaymentPolicy();
        List<Group> groups = new ArrayList<>();
        groups.add(prepareGroup("pending", "21", 1, true));
        groups.add(prepareGroup("failed", "22", 1, false));
        groups.add(prepareGroup("unknown", "23", 1, false));
        groups.add(prepareGroup("mixed", "24", 2, false));
        groups.add(prepareGroup("createSuccess", "25", 1, false));
        groups.add(prepareGroup("unknownCreation", "26", 1, false));
        groups.add(prepareGroup("concurrentCreation", "27", 1, false));
        groups.add(prepareGroup("partialCreation", "28", 2, false));
        validateStageNames(groups.stream().map(Group::stage).toList());
        prepareShipmentControls(groups.getFirst().offers().getFirst().supplier());
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
        receipt.put("kind", "procurement-current-structured-order-native-v1");
        var bindingPath = Path.of(required("PROCUREMENT_PUBLIC_SOURCE_READINESS_INPUT")).toRealPath();
        var binding = json.readTree(Files.readAllBytes(bindingPath));
        Path app = Path.of(binding.path("applicationCoreRoot").asText()).toRealPath();
        String actualHead = gitRead(app, "rev-parse", "HEAD").strip();
        assertEquals(binding.path("applicationCoreCommit").asText(), actualHead);
        assertEquals("", gitRead(app, "status", "--porcelain").strip());
        String mainBase = "39c92a8e2b15499f236f55f6aa0e804bfba67390";
        assertEquals("", gitRead(app, "diff", mainBase, "HEAD", "--", "platform/src/main").strip());
        Path test = app.resolve("platform/src/test/java/com/auraboot/framework/plugin/pf4j/ProcurementControlledOrderNativeIT.java").toRealPath();
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
        receipt.put("sourceSet", binding.path("sourceSet"));receipt.put("orderedImportDirectories",binding.path("orderedImportDirectories"));
        receipt.put("sourceCommits", sources);
        receipt.put("encryptionMode", encryptionMode);receipt.put("matchingEncryptionAdmission",matchingEncryptionAdmission);
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
        receipt.put("scenarios", retained.subList(4, 8));receipt.put("supplierActors",supplierIdentities);receipt.put("modelBindings",currentBindings());receipt.put("fullBusinessSnapshot",snapshotAllCurrentModels());receipt.put("paymentMappings",paymentMappings);receipt.put("backendActionsExecuted",false);receipt.put("addressPids",addressPids);receipt.put("noMembershipActor",noMembershipIdentity);
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
        receipt.put("producerMethod", "produceCurrentStructuredOrderPrerequisites");
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
        writeArtifact("controlled-order-fixture-receipt.json", receipt);
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
        var contract = new LinkedHashMap<String, Object>(super.orderReadContract());contract.put("supplierReferenceFormId","BD_Supplier");
        var fields = new LinkedHashMap<String, String>();
        for (int index = 0; index < READ_COLUMNS.size(); index++) fields.put(READ_COLUMNS.get(index), ERP_COLUMNS.get(index));
        contract.put("fields", fields);
        return contract;
    }

    private Map<String,Object> structuredOrderContract() {
        var row=new LinkedHashMap<>(orderContract());row.put("supplierReferenceFormId","BD_Supplier");row.put("paymentConditionValues",List.copyOf(paymentMappings));return row;
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
            policies.add(json.valueToTree(GENERATE.equals(code) ? structuredOrderContract() : orderReadContract()));
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
        policy.put("writeContract", structuredOrderContract());
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

    private List<Offer> prepareOffers(String stage,int count,String source) throws Exception {
        initializedConnectionUpdate(Map.of("customer_pcba_kd_base_url",master.baseUrl(),"customer_pcba_kd_username","local-reader","customer_pcba_kd_app_id","local-app"));
        var directory=publicAction(preparationToken,"qo_procurement_task_common:sync_directory",task,Map.of("selectedSupplierIds",count==1?List.of("701"):List.of("701","702")));
        assertTrue(directory.path("paymentTermsSynchronized").asBoolean());assertEquals(count,directory.path("items").size());
        var supplierPids=new ArrayList<String>();for(JsonNode item:directory.path("items"))supplierPids.add(item.path("supplierPid").asText());
        String category=initializedDirectoryCreate("pr_spend_category",Map.of("pr_spc_code",provider+"-"+stage,"pr_spc_name","Current structured payment category"));
        publicAction(preparationToken,"qo_procurement_category_policy_common:create",null,Map.of("categoryPid",category,"connectionPid",connection,"supplierPids",supplierPids,"defaultSupplierPids",supplierPids));
        assertEquals("LOCAL-ORG",fixture(SYNC,()->db.getById("pr_purchase_request",source).get("qo_pr_source_organization")));
        long revision=fixture(SYNC,()->new BigDecimal(db.getById("pr_purchase_request",source).get("qo_pr_source_revision").toString()).longValueExact());
        publicAction(preparationToken,"qo_procurement_task_common:classify_source",task,Map.of("sourcePid",source,"sourceRevision",revision,"categoryPid",category));
        long currentRevision=fixture(SYNC,()->new BigDecimal(db.getById("pr_purchase_request",source).get("qo_pr_source_revision").toString()).longValueExact());assertEquals(revision+1,currentRevision);
        publicAction(preparationToken,"qo_procurement_task_common:dispatch",task,Map.of("supplierPids",supplierPids,"cutoffAt","2066-11-15T08:00:00Z"));
        var offers=new ArrayList<Offer>();
        for(int index=0;index<count;index++) {
            String supplier=supplierPids.get(index);var item=directory.path("items").get(index);assertEquals(1,item.path("contacts").size());String contact=item.path("contacts").get(0).path("contactPid").asText();
            var bids=fixture(SYNC,()->db.query("s2p_bid",Map.of("s2p_bid_sourcing_event_id",task,"s2p_bid_supplier_id",supplier)));assertEquals(1,bids.size());String quote=pid(bids.getFirst());
            String key=supplierActorKey(provider,stage,index);var user=users.signUp(key+"@example.invalid",required("PROCUREMENT_ORDER_UI_FIXTURE_PASSWORD"),"Current controlled supplier",key);
            var member=members.addMember(user.getId(),tenant,"active");
            initializedCreate("qo_procurement_membership_common",Map.of("qo_pr_membership_key",key,"qo_pr_membership_user_pid",user.getPid(),"qo_pr_membership_supplier_pid",supplier,"qo_pr_membership_contact_pid",contact,"qo_pr_membership_state","active"));
            String jwt=exactSupplierActor(user.getId(),user.getPid(),member.getId(),member.getPid(),user.getEmail());supplierIdentities.getLast().put("supplierPid",supplier);supplierIdentities.getLast().put("contactPid",contact);
            var view=publicAction(jwt,"qo_procurement_quote_common:view_mine",quote,Map.of());JsonNode option=view.path("paymentConditionOptions").get(0);assertNotNull(option);assertFalse(option.path("sourceDigest").asText().isBlank());
            var selection=Map.of("financeEntryId",option.path("financeEntryId").asText(),"conditionId",option.path("conditionId").asText(),"sourceDigest",option.path("sourceDigest").asText());
            var line=Map.<String,Object>of("sourcePid",source,"sourceRevision",currentRevision,"unitPrice","1.25","currency","CNY","unit","PCS","taxRate","0.13","taxIncluded",true,"availableQty","600","deliveryDate","2066-12-01");
            publicAction(jwt,"qo_procurement_quote_common:save_draft",quote,Map.of("revision",view.path("quote").path("revision").asText(),"lines",List.of(line),"paymentCondition",selection));
            view=publicAction(jwt,"qo_procurement_quote_common:view_mine",quote,Map.of());var formal=publicAction(jwt,"qo_procurement_quote_common:submit",quote,Map.of("revision",view.path("quote").path("revision").asText()));
            String submission=formal.path("submissionPid").asText();assertFalse(submission.isBlank());
            var stored=fixture(SYNC,()->db.getById("qo_procurement_submission_common",submission));JsonNode header=storedJson(stored.get("qo_pr_submission_content"));
            JsonNode terms=storedJson(header.get("qo_pr_quote_payment_terms_snapshot"));assertEquals("KINGDEE_PAYMENT_CONDITION",terms.path("kind").asText());assertEquals(2,terms.path("condition").path("PayRecCondtionEntry").size());
            addPaymentMapping(terms,item.path("supplierId").asText());
            offers.add(new Offer(new Submitted(source,quote,submission,currentRevision),supplier,item.path("supplierId").asText()));
        }
        initializedConnectionUpdate(Map.of("customer_pcba_kd_base_url","http://127.0.0.1:"+server.getAddress().getPort(),"customer_pcba_kd_username","local-reader","customer_pcba_kd_app_id","local-app"));
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
        codes.add("model.pr_supplier_pcba_contact.read");assertEquals(21,codes.size());assertEquals(21,new java.util.HashSet<>(codes).size());return List.copyOf(codes);
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
        String jwt=actorToken(user.getId(),user.getPid(),member.getId(),email,codes,password);
        var identity=retainedActors.getLast();identity.put("tenantId",tenant.toString());identity.put("userId",user.getId().toString());identity.put("memberPid",member.getPid());
        var assigned=userRoles.findByMemberIdAndTenantId(member.getId(),tenant);assertEquals(1,assigned.size());identity.put("rolePid",roles.getRoleById(assigned.getFirst().getRoleId()).getPid());
        var effective=new java.util.HashSet<>(SetBaseline.CODES);effective.addAll(codes);identity.put("effectivePermissionCodes",effective.stream().sorted().toList());return jwt;
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
    static String supplierActorKey(String provider,String stage,int index) {
        assertNotNull(provider);assertTrue(provider.matches("ps_[0-9a-f]{24}"));
        int stageIndex=List.of("pending","failed","unknown","mixed","createSuccess","unknownCreation","concurrentCreation","partialCreation").indexOf(stage);
        assertTrue(stageIndex>=0,"A known order fixture stage is required");assertTrue(index>=0 && index<2,"Supplier fixture index must be zero or one");
        return provider+"-s"+stageIndex+"-"+index;
    }
    static boolean validSyntheticCredential(String value,int minimum) {return value!=null && value.length()>=minimum && value.length()<=128 && value.chars().allMatch(c->c>=0x21 && c<=0x7e);}
    static void validateStageNames(List<String> stages) {
        assertEquals(List.of("pending","failed","unknown","mixed","createSuccess","unknownCreation","concurrentCreation","partialCreation"),stages);
    }
    private static final List<String> CURRENT_MODELS=List.of("s2p_sourcing_event","pr_purchase_request","s2p_bid","qo_procurement_quote_line_common","qo_procurement_history_common","qo_procurement_submission_common","qo_procurement_submission_line_common","qo_procurement_allocation_common","qo_procurement_execution_common","customer_pcba_int_external_ref","pr_supplier_common","pr_supplier_pcba_contact","qo_procurement_membership_common","prod_product","pr_spend_category","qo_procurement_category_policy_common","qo_procurement_category_supplier_common","customer_pcba_int_kingdee_connection","pr_purchase_order","pr_purchase_order_line","qo_procurement_address_common","qo_procurement_shipment_common","qo_procurement_shipment_line_common");
    private Map<String,Object> currentBindings() throws Exception {
        var result=new LinkedHashMap<String,Object>();var jdbc=new org.springframework.jdbc.core.JdbcTemplate(postgres);
        for(String model:CURRENT_MODELS) {
            String table=metadata.getTableName(model);assertTrue(table.matches("[A-Za-z_][A-Za-z0-9_]{0,62}"));
            var definition=metadata.getModelDefinitionFromDb(model).orElseThrow();assertEquals(table,definition.getTableName());
            var rows=jdbc.queryForList("SELECT row_to_json(m)::text FROM ab_meta_model m WHERE tenant_id=? AND code=? AND is_current=TRUE AND deleted_flag=FALSE",String.class,tenant,model);
            assertEquals(1,rows.size());var raw=ProcurementQuoteLifecycleErp.JSON.readTree(rows.getFirst());assertEquals("published",raw.path("status").asText());assertEquals(definition.getId().toString(),raw.path("id").asText());assertFalse(raw.path("pid").asText().isBlank());assertFalse(raw.path("plugin_pid").asText().isBlank());
            result.put(model,Map.of("physicalTable",table,"rawModel",raw));
        }return result;
    }
    private Map<String,Object> snapshotAllCurrentModels() throws Exception {
        var result=new LinkedHashMap<String,Object>();
        try(var connection=postgres.getConnection()) {
            try(var statement=connection.createStatement()){statement.execute("SET TIME ZONE 'UTC'");}
            var jdbc=new org.springframework.jdbc.core.JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection,true));
            for(String model:CURRENT_MODELS){String table=metadata.getTableName(model);assertTrue(table.matches("[A-Za-z_][A-Za-z0-9_]{0,62}"));result.put(model,jdbc.query("SELECT row_to_json(t)::text FROM \""+table+"\" t WHERE tenant_id=? ORDER BY pid",(rs,n)->{try{return ProcurementQuoteLifecycleErp.JSON.readTree(rs.getString(1));}catch(java.io.IOException invalid){throw new IllegalStateException(invalid);}},tenant));}
        }return result;
    }
    static String addressCommand(String model) {if(!"qo_procurement_address_common".equals(model))throw new IllegalArgumentException("Only exact address initialization");return model+":init_controlled_order_fixture";}
    static Path addressOwner(Map<String,Object> proof) {
        var owners=((List<?>)proof.get("ownerDirectories")).stream().map(v->(Map<?,?>)v).filter(v->"com.auraboot.quote-procurement".equals(v.get("pluginId"))).toList();assertEquals(1,owners.size());return Path.of(owners.getFirst().get("testDirectory").toString());
    }
    private void prepareShipmentControls(String supplier) throws Exception {
        var declared=new ArrayList<Path>();for(JsonNode path:json.readTree(required("PROCUREMENT_SOURCE_IMPORT_DIRECTORIES"))) {Path dir=Path.of(path.asText()).toRealPath();var manifest=json.readTree(dir.resolve("plugin.json").toFile());declared.add("com.auraboot.quote-procurement".equals(manifest.path("pluginId").asText())?Path.of(procurementDirectory):dir);}
        var proof=ProcurementSupplierUiInitialization.prepareOwned(declared,caseEvidence.resolve("current-address-appendix"),json,Map.of("qo_procurement_address_common",ProcurementSupplierUiInitialization.FIELDS.get("qo_procurement_address_common")),ProcurementControlledOrderNativeIT::addressCommand);
        writeArtifact("current-address-initialization-appendix.json",proof);Path addressOwner=addressOwner(proof);importDirectory(addressOwner);procurementDirectory=addressOwner.toString();
        for(int index=0;index<2;index++)addressPids.add(initializedAction("qo_procurement_address_common",addressCommand("qo_procurement_address_common"),Map.of("qo_pr_address_supplier_pid",supplier,"qo_pr_address_state","active","qo_pr_address_address","Controlled warehouse "+index,"qo_pr_address_manager_name","Controlled manager "+index,"qo_pr_address_manager_phone",index==0?"+8613900000071":"+8613900000072"),false));
        String key=provider+"-no-membership";var user=users.signUp(key+"@example.invalid",required("PROCUREMENT_ORDER_UI_FIXTURE_PASSWORD"),"Controlled no membership",key);var member=members.addMember(user.getId(),tenant,"active");
        exactSupplierActor(user.getId(),user.getPid(),member.getId(),member.getPid(),user.getEmail());noMembershipIdentity=new LinkedHashMap<>(supplierIdentities.removeLast());retainedActors.removeLast();
        assertTrue(fixture(SYNC,()->db.query("qo_procurement_membership_common",Map.of("qo_pr_membership_user_pid",user.getPid()))).isEmpty());
    }
    private void configureDirectoryPaymentPolicy() throws Exception {
        Path original=Path.of(procurementDirectory);Path copy=Files.createDirectory(caseEvidence.resolve("current-master-policy-dsl"));
        try(var paths=Files.walk(original)){for(Path path:paths.toList()){Path target=copy.resolve(original.relativize(path));if(Files.isDirectory(path))Files.createDirectories(target);else Files.copy(path,target);}}
        Path file=copy.resolve("config/commands/supplier-directory.json");var array=json.readTree(file.toFile());int changed=0;
        for(JsonNode command:array)if("qo_procurement_task_common:sync_directory".equals(command.path("code").asText())) {
            var params=(com.fasterxml.jackson.databind.node.ObjectNode)command.path("handlerParams");assertTrue(params.path("procurementSupplierDirectoryPolicies").isEmpty());assertTrue(params.path("procurementSupplierPaymentPolicies").isEmpty());
            params.set("procurementSupplierDirectoryPolicies",json.valueToTree(List.of(Map.of("connectionPid",connection,"accountId",provider,"defaultCallingCode","+86","supplierStates",List.of(Map.of("documentStatus","C","forbidStatus","A","enabled",true)),"contactStates",List.of(Map.of("forbidStatus","A","active",true))))));
            params.set("procurementSupplierPaymentPolicies",json.valueToTree(List.of(Map.of("connectionPid",connection,"accountId",provider,"conditionStates",List.of(Map.of("documentStatus","C","forbidStatus","A","enabled",true))))));changed++;
        }
        assertEquals(1,changed);json.writeValue(file.toFile(),array);importDirectory(copy);procurementDirectory=copy.toString();
        writeArtifact("current-master-policy.json",array);
    }
    static JsonNode storedJson(Object raw) throws Exception {
        assertNotNull(raw);
        if(raw instanceof String text)return ProcurementQuoteLifecycleErp.JSON.readTree(text);
        if(raw instanceof JsonNode node && node.isTextual())return ProcurementQuoteLifecycleErp.JSON.readTree(node.textValue());
        return ProcurementQuoteLifecycleErp.JSON.valueToTree(raw);
    }
    private static Object canonicalPayment(JsonNode node) {
        if(node.isObject()){var sorted=new java.util.TreeMap<String,Object>();node.properties().forEach(e->sorted.put(e.getKey(),canonicalPayment(e.getValue())));return sorted;}
        if(node.isArray()){var rows=new ArrayList<Object>();node.forEach(v->rows.add(canonicalPayment(v)));return rows;}
        if(node.isNumber())return node.decimalValue().stripTrailingZeros();if(node.isNull())return null;if(node.isBoolean())return node.booleanValue();
        assertTrue(node.isTextual());return node.textValue();
    }
    private void addPaymentMapping(JsonNode terms,String external) throws Exception {
        var row=new LinkedHashMap<String,Object>();row.put("supplierId",terms.path("supplierId").asText());row.put("supplierExternalId",external);
        row.put("financeEntryId",terms.path("selection").path("financeEntryId").asText());row.put("conditionId",terms.path("selection").path("conditionId").asText());row.put("conditionNumber",terms.path("condition").path("Number").asText());
        row.put("supplierCreateOrgId",terms.path("supplierOrganizations").path("CreateOrgId_Id").asText());row.put("supplierUseOrgId",terms.path("supplierOrganizations").path("UseOrgId_Id").asText());
        row.put("conditionCreateOrgId",terms.path("condition").path("CreateOrgId_Id").asText());row.put("conditionUseOrgId",terms.path("condition").path("UseOrgId_Id").asText());
        row.put("sourceDigest",terms.path("selection").path("sourceDigest").asText());row.put("snapshotDigest",digest(ProcurementQuoteLifecycleErp.JSON.writeValueAsBytes(canonicalPayment(terms))));
        row.put("orderOrganization","LOCAL-ORG");row.put("orderOrganizationExternalId","org-local");row.put("targetField","Payment.Id");row.put("targetValue","payment-local");
        if(!paymentMappings.contains(row))paymentMappings.add(Map.copyOf(row));
    }
    private String exactSupplierActor(Long id,String pid,Long member,String memberPid,String email) throws Exception {
        context();var role=new com.auraboot.framework.rbac.entity.Role();role.setPid(com.auraboot.framework.common.util.UniqueIdGenerator.generate());role.setCode(provider+"_os_"+id);
        role.setName("Controlled order supplier exact2");role.setType("custom");role.setScopeType("tenant");role.setStatus("active");role.setTenantId(tenant);role.setIsDefault(false);role.setIsSystem(false);role.setDeletedFlag(false);role.setPriority(100);role=roles.createRole(role);
        var codes=List.of("meta.command.execute","qo.srm.use");var ids=new ArrayList<Long>();for(String code:codes){var permission=permissions.findByCode(code);assertNotNull(permission);ids.add(permission.getId());}
        assertTrue(roles.assignPermissions(role.getId(),ids));roles.setDefaultDataScopeType(role.getId(),"self");assertTrue(userRoles.assignRolesToMember(member,List.of(role.getId()),tenant,userId));
        MetaContext.setContext(tenant,id,pid,provider);MetaContext.setMemberId(member);userPermissions.evictUserPermissions(tenant,id);assertFalse(userPermissions.hasPermission(tenant,id,member,"*"));
        assertEquals(List.of(role.getId()),userRoles.findByMemberIdAndTenantId(member,tenant).stream().map(com.auraboot.framework.rbac.entity.UserRole::getRoleId).toList());
        var auth=new AuthenticationRequest();auth.setEmail(email);auth.setPassword(required("PROCUREMENT_ORDER_UI_FIXTURE_PASSWORD"));var authenticated=authentication.authenticate(auth);
        assertEquals(tenant,authenticated.getTenantId());assertEquals(id,authenticated.getUserId());MetaContext.clear();
        var result=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/auth/me").header("Authorization","Bearer "+authenticated.getJwt())).andReturn();assertEquals(200,result.getResponse().getStatus());
        var me=json.readTree(result.getResponse().getContentAsByteArray()).path("data");assertEquals(pid,me.path("user").path("pid").asText());assertEquals(tenant.toString(),me.path("user").path("tenantId").asText());
        var expected=new java.util.HashSet<>(SetBaseline.CODES);expected.addAll(codes);var actual=new java.util.HashSet<String>();for(JsonNode code:me.path("permissions").path("permissionCodes"))assertTrue(actual.add(code.asText()));assertEquals(expected,actual);
        assertEquals(1,me.path("permissions").path("roles").size());assertEquals(role.getCode(),me.path("permissions").path("roles").get(0).path("code").asText());
        var identity=new LinkedHashMap<String,Object>();identity.put("tenantId",tenant.toString());identity.put("userId",id.toString());identity.put("userPid",pid);identity.put("memberPid",memberPid);identity.put("rolePid",role.getPid());identity.put("roleCode",role.getCode());identity.put("email",email);identity.put("permissionCodes",expected.stream().sorted().toList());identity.put("dataScopeType","self");identity.put("passwordEnvironment","PROCUREMENT_ORDER_UI_FIXTURE_PASSWORD");identity.put("authenticationVerified",true);supplierIdentities.add(identity);retainedActors.add(identity);context();return authenticated.getJwt();
    }
    private static final class SetBaseline {static final java.util.Set<String>CODES=java.util.Set.of("meta.model.read","page.page.read","party.party.read","dashboard.saved_view.read","notification.view");}
    @Override protected Map<String,Object> snapshot() {
        var result=new LinkedHashMap<String,Object>();
        try(var connection=postgres.getConnection()) {
            try(var statement=connection.createStatement()){statement.execute("SET TIME ZONE 'UTC'");}
            var jdbc=new org.springframework.jdbc.core.JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection,true));
            for(String entity:List.of("task","source","quote","quote_line","history","submission","submission_line","allocation","execution","order","order_line","externalReferences")) {
                String model=entity.equals("externalReferences")?"customer_pcba_int_external_ref":domainModel(entity);String table=metadata.getTableName(model);assertTrue(table.matches("[A-Za-z_][A-Za-z0-9_]{0,62}"));
                var definition=metadata.getModelDefinitionFromDb(model).orElseThrow();assertEquals(table,definition.getTableName());assertEquals(tenant,postgresMetadata(model).get("tenant_id"));
                result.put(entity,jdbc.query("SELECT row_to_json(t)::text FROM \""+table+"\" t WHERE tenant_id=? ORDER BY pid",(rs,n)->{try{return ProcurementQuoteLifecycleErp.JSON.readTree(rs.getString(1));}catch(java.io.IOException invalid){throw new IllegalStateException(invalid);}},tenant));
            }
        } catch(java.sql.SQLException invalid){throw new IllegalStateException(invalid);}return result;
    }
    @Override @org.junit.jupiter.api.AfterEach void close() throws Exception {try{if(master!=null){writeArtifact("master-provider-counts.json",master.counts());master.close();}}finally{super.close();}}

}
