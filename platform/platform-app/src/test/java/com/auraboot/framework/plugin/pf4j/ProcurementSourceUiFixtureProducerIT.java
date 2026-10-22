package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dto.AuthenticationRequest;
import com.auraboot.framework.cloudconfig.dto.CloudConfigSaveRequest;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.service.CommandExecutor;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.notification.sourcebinding.ProductQuoteNativeInitialization;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Synthetic retained prerequisites. Public browser journeys are separately required. */
@SpringBootTest(classes = TestApplication.class, properties = {
        "spring.datasource.url=${TEST_DATABASE_URL}", "spring.data.redis.database=${TEST_REDIS_DATABASE}",
        "aura.plugins.dir=${PROCUREMENT_SOURCE_PLUGIN_DIR}",
        "security.field-encryption.key=${PROCUREMENT_SOURCE_UI_FIELD_ENCRYPTION_KEY}",
        "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.slf4j.Slf4jImpl",
        "logging.level.com.auraboot.framework=INFO", "logging.level.org.mybatis=INFO",
        "logging.level.org.springframework.security=WARN", "logging.level.org.springframework.cache=WARN"})
@ActiveProfiles("integration-test")
@Import(ProcurementPublicSourceNativeFixture.IsolatedScheduling.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "PROCUREMENT_SOURCE_UI_FIXTURE_IT", matches = "true")
class ProcurementSourceUiFixtureProducerIT extends ProcurementPublicSourceNativeFixture {
    static final String CANDIDATES = "qo_procurement_task_common:source_candidates";
    static final List<String> FIELDS = List.of("FID", "FBillNo", "FEntity_FEntryID", "FMaterialId",
            "FMaterialId.FNumber", "FMaterialId.FName", "FMaterialId.FSpecification", "FReqQty", "FApproveQty",
            "FOrderJoinQty", "FOrderQty", "FRemainQty", "FUnitID.FNumber", "FArrivalDate",
            "FPurchaseOrgId.FNumber", "FDocumentStatus", "FCancelStatus", "FCloseStatus");
    static final List<String> BUYER_PERMISSIONS = buyerPermissions();
    static final List<String> MAKER_PERMISSIONS = makerPermissions();
    @Autowired CommandExecutor actualCommands;
    @Autowired MetaModelService actualMetadata;
    @Autowired com.auraboot.framework.common.crypto.FieldEncryptionService actualEncryption;
    private JsonNode input;
    private String encryptionMode;
    private boolean remarkFacetEnabled;
    private final List<Map<String, Object>> sourceCommandProofs = new ArrayList<>();
    private String controlToken;
    private URI controlBase;
    private String makerToken;
    private String buyerToken;
    private String buyerPid;
    private Map<String, Object> buyerIdentity;
    private Map<String, Object> makerIdentity;
    private final List<Map<String, Object>> stages = new ArrayList<>();
    private final List<Map<String, Object>> publicActions = new ArrayList<>();
    private final List<Map<String, Object>> initializationActions = new ArrayList<>();
    private final List<Map<String, Object>> policies = new ArrayList<>();
    private String activeStage = "admission";

    static List<String> buyerPermissions() {
        var permissions = new ArrayList<>(sourceActorPermissions(true));
        permissions.addAll(List.of("qo.procurement.manage", "page.page.read", "model.pr_spend_category.read",
                "model.pr_supplier_common.read", "model.qo_procurement_history_common.read"));
        return List.copyOf(permissions);
    }

    static List<String> makerPermissions() {
        var permissions = new ArrayList<>(BUYER_PERMISSIONS);
        permissions.addAll(List.of("model.qo_procurement_submission_common.read",
                "model.qo_procurement_submission_line_common.read", "model.qo_procurement_execution_common.read",
                "model.qo_procurement_category_policy_common.read","model.qo_procurement_category_supplier_common.read"));
        return List.copyOf(permissions);
    }

    @Override protected void prepareEvidence(org.junit.jupiter.api.TestInfo test) throws Exception {
        super.prepareEvidence(test);
        Path sourceUiInput = Path.of(required("PROCUREMENT_SOURCE_UI_INPUT")).toRealPath();
        assertEquals(required("PROCUREMENT_SOURCE_UI_INPUT_SHA256"), sha(sourceUiInput));
        input = json.readTree(sourceUiInput.toFile());
        if (input.has("remarkFacetEnabled")) assertTrue(input.path("remarkFacetEnabled").isBoolean());
        remarkFacetEnabled = input.path("remarkFacetEnabled").asBoolean(false);
        assertEquals("procurement-source-ui-input-v1", input.path("kind").asText());
        assertEquals(1, input.path("schemaVersion").asInt(-1));
        assertEquals(ProcurementOrderUiFixtureProducerIT.canonicalSources(input.path("sourceSet")),
                ProcurementOrderUiFixtureProducerIT.canonicalSources(json.readTree(caseEvidence.resolve("readiness.json").toFile()).path("sourceCommits")));
        Path app = Path.of(input.path("applicationCoreRoot").asText()).toRealPath();
        assertEquals(input.path("applicationCoreCommit").asText(), gitRead(app, "rev-parse", "HEAD").strip());
        assertEquals("", gitRead(app, "status", "--porcelain").strip());
        assertEquals("", gitRead(app, "diff", "50924063538e4181cb601300f0c26c6bbfcce2f0", "HEAD", "--", "platform/src/main").strip());
        Path loaded = Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        assertEquals(app.resolve("platform/build/classes/java/test").toRealPath(), loaded);
        Path testRoot = app.resolve("platform/src/test/java/com/auraboot/framework");
        assertEquals(input.path("nativeHostTestSha256").asText(), sha(testRoot.resolve("plugin/pf4j/ProcurementSourceUiFixtureProducerIT.java")));
        assertEquals(input.path("nativeSharedFixtureSha256").asText(), sha(testRoot.resolve("plugin/pf4j/ProcurementPublicSourceNativeFixture.java")));
        assertEquals(input.path("nativeInitializationFixtureSha256").asText(), sha(testRoot.resolve("notification/sourcebinding/ProductQuoteNativeInitialization.java")));
        Path workspace = Path.of(input.path("workspaceRoot").asText()).toRealPath();
        Path evidenceRoot = workspace.resolve(".workspace/evidence/procurement-web-stack-20261010").toRealPath();
        assertTrue(sourceUiInput.startsWith(evidenceRoot));
        Path proofPath = Path.of(input.path("encryptionConfigProofPath").asText()).toRealPath();
        assertTrue(proofPath.startsWith(evidenceRoot));
        assertEquals(input.path("encryptionConfigProofSha256").asText(), sha(proofPath));
        JsonNode proof = json.readTree(proofPath.toFile());
        assertEquals(input.path("sourceSet").path("oss").asText(), proof.path("sourceCommit").asText());
        assertEquals(sha(app.resolve("platform/src/main/java/com/auraboot/framework/common/crypto/FieldEncryptionService.java")), proof.path("fieldEncryptionServiceSha256").asText());
        assertEquals(sha(app.resolve("platform/platform-app/src/main/resources/application.yml")), proof.path("applicationYmlSha256").asText());
        assertTrue(System.getenv().containsKey("PROCUREMENT_SOURCE_UI_FIELD_ENCRYPTION_KEY"));
        encryptionMode = ProcurementOrderUiFixtureProducerIT.validateEncryptionMode(proof,
                System.getenv("PROCUREMENT_SOURCE_UI_FIELD_ENCRYPTION_KEY"));
        assertEquals(input.path("encryptionMode").asText(), encryptionMode);
        for (String stem : List.of("runtimeAdmission", "runtimeEnvironmentEvidence", "runtimeProfileEvidence")) {
            Path artifact = Path.of(proof.path(stem + "Path").asText()).toRealPath();
            assertTrue(artifact.startsWith(evidenceRoot));
            assertEquals(proof.path(stem + "Sha256").asText(), sha(artifact));
        }
        JsonNode runtimeProof = json.readTree(Path.of(proof.path("runtimeAdmissionPath").asText()).toFile());
        assertEquals("ok", runtimeProof.path("verdict").asText());
        assertEquals("procurement-web-stack-20261010", runtimeProof.path("runtime").asText());
        assertEquals(proof.path("runtimeMode").asText(), runtimeProof.path("metadata").path("mode").asText());
        var actualCore = java.util.stream.StreamSupport.stream(runtimeProof.path("sources").spliterator(), false)
                .filter(row -> row.path("key").asText().equals("oss")).toList();
        assertEquals(1, actualCore.size());
        assertEquals(proof.path("sourceCommit").asText(), actualCore.getFirst().path("actual").path("commit").asText());
        JsonNode environment = json.readTree(Path.of(proof.path("runtimeEnvironmentEvidencePath").asText()).toFile());
        assertEquals("procurement-web-stack-20261010", environment.path("runtime").asText());
        assertEquals(proof.path("environmentKeyState").asText(), environment.path("fieldEncryptionKeyState").asText());
        JsonNode profile = json.readTree(Path.of(proof.path("runtimeProfileEvidencePath").asText()).toFile());
        assertEquals("procurement-web-stack-20261010", profile.path("runtime").asText());
        assertEquals(proof.path("actualSpringProfiles").asText(), profile.path("actualSpringProfiles").asText());
        String probe = "SyntheticSourceUiEncryptionProbe";
        String encrypted = actualEncryption.encrypt(probe);
        assertEquals(probe, actualEncryption.decrypt(encrypted));
        if (encryptionMode.equals("dev-passthrough")) assertEquals(probe, encrypted);
        else assertTrue(encrypted.startsWith("ENC:"));
        writeArtifact("encryption-mode-admission.json", Map.of("mode", encryptionMode,
                "proofPath", proofPath.toString(), "proofSha256", sha(proofPath),
                "actualSpringProfiles", proof.path("actualSpringProfiles").asText(), "nonSecretBeanProbeMatched", true));
    }

    @Test void produceSourceSelectionAndRefreshPrerequisites() throws Exception {
        try {
            admitProvider();
            importInitializer();
            makerToken = actor("maker", MAKER_PERMISSIONS);
            makerIdentity = new LinkedHashMap<>(retainedActors.getLast());
            buyerToken = actor("buyer", BUYER_PERMISSIONS);
            buyerIdentity = new LinkedHashMap<>(retainedActors.getLast());
            buyerPid = buyerIdentity.get("userPid").toString();
            // The UI credential is solely this actual independent account, never the bootstrap/maker.
            assertNotEquals(buyerPid, makerIdentity.get("userPid"));
            var selection = createStage("selection", List.of(sourceRow("101", "11", "UI-SELECT"), sourceRow("101", "12", "UI-SELECT")));
            assertEquals(2, candidates(selection).path("records").size());
            assertTrue(sources().isEmpty());
            var foreign = createStage("foreignAccount", List.of(sourceRow("101", "11", "UI-SELECT")));
            sync(foreign, List.of(pair("101", "11")));
            foreign.put("businessBefore", snapshot());
            var critical = createStage("critical", List.of(sourceRow("102", "21", "UI-CRITICAL")));
            sync(critical, List.of(pair("102", "21")));
            critical.put("formal", submitQuote(critical, false));
            var missing = createStage("missingUnselected", List.of(sourceRow("103", "31", "UI-MISSING"), sourceRow("103", "32", "UI-MISSING")));
            sync(missing, List.of(pair("103", "31"), pair("103", "32")));
            // Owned provider omissions are not cancellation facts. Both persisted sources remain.
            control("/__control/source-scenario", Map.of("namespace", missing.get("namespace"),
                    "sourceRows", List.of(sourceRow("103", "31", "UI-MISSING"))));
            missing.put("businessBefore", snapshot());
            var paginationRows = new ArrayList<List<Object>>();
            var selected = new ArrayList<Map<String, String>>();
            for (int index = 1; index <= 21; index++) {
                paginationRows.add(sourceRow("104", Integer.toString(40 + index), "UI-PAGINATION"));
                selected.add(pair("104", Integer.toString(40 + index)));
            }
            var pagination = createStage("factsPagination", paginationRows);
            sync(pagination, selected);
            pagination.put("formal", submitQuote(pagination, true));
            assertEquals(21, sources().size());
            for (var source : sources()) {
                assertDecimal("1500", source.get("pr_preq_qty"));
                assertDecimal("1000", source.get("qo_pr_source_remaining_qty"));
                assertDecimal("125", source.get("qo_pr_source_reserved_qty"));
            }
            pagination.put("reservationAuthority", "actual public quote submit and task allocate; bootstrap is not UI");
            if (remarkFacetEnabled) {
                var remark = createStage("remark", List.of(remarkRow("105", "71", "UI-REMARK", "Initial controlled remark")), "FControlledRemark");
                sync(remark, List.of(pair("105", "71")));
                assertEquals("Initial controlled remark", sources().getFirst().get("pr_preq_remark"));
                remark.put("formal", submitQuote(remark, false));
                var sourceBefore = sources().getFirst();
                assertNotNull(sourceBefore.get("row_version"));
                assertNotNull(sourceBefore.get("qo_pr_source_revision"));
                remark.put("sourceBusinessRevisionBefore", sourceBefore.get("qo_pr_source_revision"));
                remark.put("sourceAuditVersionBefore", sourceBefore.get("row_version"));
                remark.put("sourceRecordBefore", new LinkedHashMap<>(sourceBefore));
                remark.put("businessBefore", snapshot());
                remark.put("expectedRemarkTransitions", java.util.Arrays.asList("Updated controlled remark", null));
                // Only the controlled provider changes here. Both public refreshes belong to the browser.
                control("/__control/source-scenario", Map.of("namespace", remark.get("namespace"),
                        "sourceRows", List.of(remarkRow("105", "71", "UI-REMARK", "Updated controlled remark"))));
                assertEquals("Initial controlled remark", sources().getFirst().get("pr_preq_remark"));
            }
            for (var stage : stages) {
                task = stage.get("taskPid").toString();
                connection = stage.get("connectionPid").toString();
                stage.put("sourceRows", sources());
                stage.put("providerState", sourceState(stage.get("namespace").toString()));
                stage.put("taskRecord", fixture(SYNC, () -> db.getById("s2p_sourcing_event", task)));
                stage.put("externalReferences", references());
                stage.put("expectedQuantities", sources().stream().map(source -> Map.of("sourcePid", pid(source),
                        "requestedQty", source.get("pr_preq_qty").toString(), "remainingQty", source.get("qo_pr_source_remaining_qty").toString(),
                        "reservedQty", source.get("qo_pr_source_reserved_qty").toString(),
                        "unreservedQty", new BigDecimal(source.get("qo_pr_source_remaining_qty").toString()).subtract(new BigDecimal(source.get("qo_pr_source_reserved_qty").toString())).toPlainString())).toList());
            }
            var receipt = new LinkedHashMap<String, Object>();
            receipt.put("kind", "procurement-source-ui-native-v1");
            receipt.put("schemaVersion", 1);
            receipt.put("runtime", "procurement-web-stack-20261010");
            receipt.put("database", "enterprise_22");
            receipt.put("redisDatabase", 13);
            receipt.put("sourceBindingPath", System.getenv("PROCUREMENT_PUBLIC_SOURCE_READINESS_INPUT"));
            receipt.put("sourceBindingSha256", sha(Path.of(System.getenv("PROCUREMENT_PUBLIC_SOURCE_READINESS_INPUT"))));
            receipt.put("encryptionMode", encryptionMode);
            receipt.put("encryptionConfigProofPath", input.path("encryptionConfigProofPath").asText());
            receipt.put("encryptionConfigProofSha256", input.path("encryptionConfigProofSha256").asText());
            receipt.put("managedProviderReceiptPath", input.path("managedProviderReceiptPath").asText());
            receipt.put("managedProviderReceiptSha256", input.path("managedProviderReceiptSha256").asText());
            receipt.put("tenantId", tenant.toString());
            receipt.put("applicationCoreRoot", input.path("applicationCoreRoot").asText());
            receipt.put("applicationCoreCommit", input.path("applicationCoreCommit").asText());
            receipt.put("nativeHostTestSha256", input.path("nativeHostTestSha256").asText());
            receipt.put("nativeSharedFixtureSha256", input.path("nativeSharedFixtureSha256").asText());
            receipt.put("nativeInitializationFixtureSha256", input.path("nativeInitializationFixtureSha256").asText());
            receipt.put("sourceSet", ProcurementOrderUiFixtureProducerIT.canonicalSources(input.path("sourceSet")));
            receipt.put("sourceCommits", ProcurementOrderUiFixtureProducerIT.canonicalSources(
                    json.readTree(caseEvidence.resolve("readiness.json").toFile()).path("sourceCommits")));
            receipt.put("productJarSha256", json.readTree(caseEvidence.resolve("readiness.json").toFile()).path("pluginJarSha256").asText());
            receipt.put("nativeReadinessPath", caseEvidence.resolve("readiness.json").toString());
            receipt.put("nativeReadinessSha256", sha(caseEvidence.resolve("readiness.json")));
            receipt.put("buyer", buyerIdentity);
            receipt.put("nativeLifecycleMaker", makerIdentity);
            receipt.put("stages", stages);
            receipt.put("publicActions", publicActions);
            receipt.put("initializationActions", initializationActions);
            receipt.put("serverPolicies", policies);
            receipt.put("sourceCommandProofs", sourceCommandProofs);
            receipt.put("encryptionModeAdmissionPath", caseEvidence.resolve("encryption-mode-admission.json").toString());
            receipt.put("encryptionModeAdmissionSha256", sha(caseEvidence.resolve("encryption-mode-admission.json")));
            var validatedTables = tableMap();
            var rawSnapshot = rawBusinessSnapshot(validatedTables);
            receipt.put("businessSnapshotTables", validatedTables);
            receipt.put("businessSnapshot", rawSnapshot);
            receipt.put("immutableBusinessBefore", rawSnapshot);
            receipt.put("testInitializationAppendixPath", caseEvidence.resolve("source-ui-initialization-appendix.json").toString());
            receipt.put("testInitializationAppendixSha256", sha(caseEvidence.resolve("source-ui-initialization-appendix.json")));
            receipt.put("membershipInitialFactsSynthetic", true);
            receipt.put("bootstrapFactsAreBrowserAcceptance", false);
            receipt.put("retainedAfterNative", true);
            receipt.put("remarkFacetStatus", remarkFacetEnabled ? "prepared-controlled-server-remark" : "blocked-contract; no server column configured");
            receipt.put("actualCustomerERPVerified", false);
            receipt.put("browserExecuted", 0);
            writeArtifact("source-ui-fixture-receipt.json", receipt);
            activeStage = "native-fixture-completed";
        } finally {
            writeArtifact("source-ui-producer-stage.json", Map.of("stage", activeStage,
                    "completed", activeStage.equals("native-fixture-completed"), "browserExecuted", 0,
                    "publicActionCount", publicActions.size(), "receiptExists", Files.exists(caseEvidence.resolve("source-ui-fixture-receipt.json"))));
        }
    }

    static List<Object> sourceRow(String head, String entry, String bill) {
        assertTrue(head.matches("[1-9][0-9]{0,29}"));
        assertTrue(entry.matches("[1-9][0-9]{0,29}"));
        return List.of(head, bill, entry, "900", "SRC-900", "Synthetic source material", "SYNTHETIC", "1500",
                "1500", "0", "0", "1000", "PCS", "2066-12-01", "SYNTHETIC-ORG", "C", "A", "A");
    }

    static List<Object> remarkRow(String head, String entry, String bill, String remark) {
        assertTrue(remark == null || remark.length() <= 500);
        var row = new ArrayList<>(sourceRow(head, entry, bill));
        row.add(remark);
        return row;
    }

    static Map<String, String> pair(String head, String entry) {
        sourceRow(head, entry, "validation-only");
        return Map.of("requisitionId", head, "entryId", entry);
    }

    private void admitProvider() throws Exception {
        activeStage = "managed-source-provider-admission";
        Path path = Path.of(required("PROCUREMENT_SOURCE_UI_INPUT")).toRealPath();
        assertEquals(required("PROCUREMENT_SOURCE_UI_INPUT_SHA256"), sha(path));
        input = json.readTree(path.toFile());
        Path receiptPath = Path.of(input.path("managedProviderReceiptPath").asText()).toRealPath();
        assertEquals(input.path("managedProviderReceiptSha256").asText(), sha(receiptPath));
        Path state = Path.of(required("PROCUREMENT_SOURCE_PLUGIN_DIR")).toRealPath().getParent().getParent();
        Path expected = state.resolve("processes/erp-fixture-receipt.json").toRealPath();
        assertTrue(Files.isSameFile(receiptPath, expected));
        var receipt = json.readTree(receiptPath.toFile());
        assertEquals("registered", receipt.path("status").asText());
        assertEquals("procurement-web-stack-20261010", receipt.path("runtime").asText());
        assertEquals(52169, receipt.path("port").asInt(-1));
        assertEquals(receipt.path("scriptSha256").asText(), sha(Path.of(receipt.path("scriptPath").asText())));
        Path tokenPath = Path.of(required("PROCUREMENT_ERP_CONTROL_TOKEN_FILE")).toRealPath();
        assertTrue(Files.isSameFile(tokenPath, Path.of(receipt.path("controlTokenFile").asText())));
        assertEquals(receipt.path("controlTokenFileSha256").asText(), sha(tokenPath));
        controlToken = Files.readString(tokenPath).strip();
        assertFalse(controlToken.isEmpty());
        controlBase = URI.create("http://127.0.0.1:52169");
    }

    private String actor(String label, List<String> permissions) {
        context();
        String email = provider + "-source-" + label + "@example.invalid";
        String password = required("PROCUREMENT_SOURCE_UI_FIXTURE_PASSWORD");
        var user = users.signUp(email, password, "Synthetic source " + label, provider + "_" + label);
        var member = members.addMember(user.getId(), tenant, "active");
        String jwt = actorToken(user.getId(), user.getPid(), member.getId(), email, permissions, password);
        var identity = retainedActors.getLast();
        identity.put("userId", user.getId().toString());
        identity.put("credentialEnvironment", "PROCUREMENT_SOURCE_UI_FIXTURE_PASSWORD");
        identity.put("dataScopeAuthority", "synthetic tenant only");
        return jwt;
    }

    private Path initializerIntegrationOriginal;
    private boolean initializerIntegrationImported;
    @org.junit.jupiter.api.AfterEach void restoreInitializerIntegrationOwner() throws Exception {
        if(initializerIntegrationImported) {
            context();formalImport(initializerIntegrationOriginal,"source-ui-integration-owner-restored",
                    ProductQuoteNativeInitialization.SOURCE_INTEGRATION_OWNER);
            initializerIntegrationImported=false;
        }
    }

    private void importInitializer() throws Exception {
        activeStage = "test-only-initializer-import";
        var directories=new ArrayList<Path>();
        for(JsonNode directory:json.readTree(required("PROCUREMENT_SOURCE_IMPORT_DIRECTORIES")))directories.add(Path.of(directory.asText()));
        Path integration=ProductQuoteNativeInitialization.sourceUiIntegrationOwner(directories,json);
        var ownerAppendix=ProductQuoteNativeInitialization.prepareSourceUiIntegration(integration,
                caseEvidence.resolve("source-ui-integration-owner-appendix"),json);
        writeArtifact("source-ui-integration-owner-appendix.json",ownerAppendix);
        initializerIntegrationOriginal=integration;initializerIntegrationImported=true;
        formalImport(Path.of(ownerAppendix.get("testDirectory").toString()),"source-ui-integration-owner",
                ProductQuoteNativeInitialization.SOURCE_INTEGRATION_OWNER);
        var appendix = ProductQuoteNativeInitialization.prepareSourceUi(Path.of(procurementDirectory),
                caseEvidence.resolve("source-ui-initialization-appendix"), json);
        writeArtifact("source-ui-initialization-appendix.json", appendix);
        formalImport(Path.of(appendix.get("testDirectory").toString()), "source-ui-initializer");
    }

    private String formalImport(Path directory,String label) throws Exception {
        return formalImport(directory,label,"com.auraboot.quote-procurement");
    }
    private String formalImport(Path directory, String label,String expectedPlugin) throws Exception {
        var preview = imports.parseDirectory(directory.toString(), true);
        writeArtifact(label + "-preview.json", preview);
        assertTrue(preview.isValid(), "Actual Source UI preview: " + preview.getErrors());
        var request = com.auraboot.framework.plugin.dto.imports.ImportRequest.builder().importId(preview.getImportId())
                .conflictStrategy(com.auraboot.framework.plugin.dto.imports.ImportRequest.ConflictStrategy.OVERWRITE)
                .autoPublishFields(true).autoPublishModels(true).autoPublishCommands(true).autoPublishPages(true)
                .createResourcePermissions(true).build();
        var result = imports.execute(preview.getImportId(), request);
        writeArtifact(label + "-result.json", result);
        assertTrue(result.isSuccess(), "Actual Source UI import: " + result.getErrorMessage());
        assertEquals(expectedPlugin, result.getPluginId());
        if(ProductQuoteNativeInitialization.SOURCE_INTEGRATION_OWNER.equals(expectedPlugin))return result.getPluginPid();
        var expected = (com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(caseEvidence.resolve("source-ui-initialization-appendix.json").toFile()).path("exactWriterAppendix");
        expected.setAll((com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(caseEvidence.resolve("source-ui-integration-owner-appendix.json").toFile()).path("exactWriterAppendix"));
        for (String model : ProductQuoteNativeInitialization.SOURCE_UI_FIELDS.keySet()) {
            var command = commands.findByCode(ProductQuoteNativeInitialization.sourceUiCommand(model));
            assertEquals(tenant, command.getTenantId());
            assertEquals(model, command.getModelCode());
            assertEquals("published", command.getStatus());
            assertEquals(true, command.getIsCurrent());
            var modelDefinition = actualMetadata.getModelDefinitionFromDb(model).orElseThrow();
            for (String fieldCode : ProductQuoteNativeInitialization.SOURCE_UI_FIELDS.get(model)) {
                var field = modelDefinition.getFields().stream().filter(row -> fieldCode.equals(row.getCode())).findFirst().orElseThrow();
                if (expected.has(fieldCode)) assertEquals(expected.path(fieldCode).path("testWriters"), json.valueToTree(field.getAllowedWriterCommands()));
            }
        }
        return result.getPluginPid();
    }

    static String fixtureRequestKey(String action) {
        if (!List.of("init", "public").contains(action)) throw new IllegalArgumentException("Invalid fixture action");
        return "source-" + action + "-" + UUID.randomUUID();
    }

    private String initialize(String model, Map<String, Object> values) throws Exception {
        ProductQuoteNativeInitialization.validateSourceUiPayload(model, values);
        var saved = MetaContext.snapshot();
        try {
            var initializer = users.findByPid(retainedBootstrapPrincipal.get("userPid").toString());
            var member = members.findByTenantIdAndUserId(tenant, initializer.getId());
            MetaContext.setContext(tenant, initializer.getId(), initializer.getPid(), provider);
            MetaContext.setMemberId(member.getId());
            var authenticationRequest = new AuthenticationRequest();
            authenticationRequest.setEmail(initializer.getEmail());
            authenticationRequest.setPassword(PASSWORD);
            var authenticated = authentication.authenticate(authenticationRequest);
            assertEquals(tenant, authenticated.getTenantId());
            assertEquals(initializer.getId(), authenticated.getUserId());
            var request = new CommandExecuteRequest();
            request.setPayload(new LinkedHashMap<>(values));
            request.setClientRequestId(fixtureRequestKey("init"));
            var result = actualCommands.execute(ProductQuoteNativeInitialization.sourceUiCommand(model), request);
            assertEquals("completed", result.getPhaseReached());
            String pid = result.getData().get("recordPid").toString();
            initializationActions.add(Map.of("command", ProductQuoteNativeInitialization.sourceUiCommand(model),
                    "model", model, "recordPid", pid, "authenticationVerified", true,
                    "syntheticInitialFactsOnly", true));
            return pid;
        } finally {
            MetaContext.clear();
            MetaContext.restore(saved);
        }
    }

    private Map<String, Object> createStage(String name, List<List<Object>> rows) throws Exception {
        return createStage(name, rows, null);
    }

    private Map<String, Object> createStage(String name, List<List<Object>> rows, String remarkField) throws Exception {
        activeStage = name;
        String namespace = "su_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String secret = UUID.randomUUID().toString();
        var cloud = new CloudConfigSaveRequest();
        cloud.setConfigLevel("tenant");
        cloud.setServiceType("integration");
        cloud.setProviderCode(namespace);
        cloud.setEnabled(true);
        cloud.setPriority(1);
        cloud.setConfig("{\"appSecret\":\"" + secret + "\"}");
        context();
        configs.saveConfig(cloud);
        var registrationPayload = new LinkedHashMap<String, Object>();
        registrationPayload.put("namespace", namespace);
        registrationPayload.put("account", Map.of("acctID", namespace, "username", namespace, "appId", namespace,
                "secretSha256", digest(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        registrationPayload.put("sourceRows", rows);
        if (remarkField != null) registrationPayload.put("remarkField", remarkField);
        var registration = control("/__control/register-source", registrationPayload);
        connection = initialize("customer_pcba_int_kingdee_connection", Map.of(
                "customer_pcba_kd_profile_name", namespace, "customer_pcba_kd_status", "active",
                "customer_pcba_kd_acct_id", namespace, "customer_pcba_kd_base_url", controlBase.toString(),
                "customer_pcba_kd_app_id", namespace, "customer_pcba_kd_username", namespace,
                "customer_pcba_kd_app_secret_ref", "cloud-config:" + namespace, "customer_pcba_kd_auth_type", "app-secret"));
        String material = fixture(SYNC, () -> pid(db.query("prod_product", Map.of("prod_code", provider)).getFirst()));
        initialize("customer_pcba_int_external_ref", Map.of("customer_pcba_ref_external_system", "KINGDEE",
                "customer_pcba_ref_instance_key", connection, "customer_pcba_ref_account_id", namespace,
                "customer_pcba_ref_external_form_id", "BD_MATERIAL", "customer_pcba_ref_external_id", "900",
                "customer_pcba_ref_local_model_code", "prod_product", "customer_pcba_ref_local_pid", material));
        configureSourcePolicy(connection, namespace, remarkField);
        var result = action(makerToken, "qo_procurement_task_common:create", null,
                Map.of("title", "Synthetic " + name, "connectionPid", connection));
        task = result.path("recordPid").asText();
        assertFalse(task.isBlank());
        retainedTaskPids.add(task);
        var stage = new LinkedHashMap<String, Object>();
        stage.put("stage", name);
        stage.put("namespace", namespace);
        stage.put("accountId", namespace);
        stage.put("connectionPid", connection);
        stage.put("taskPid", task);
        stage.put("billNo", rows.getFirst().get(1));
        stage.put("initialProviderRows", rows);
        stage.put("registration", registration);
        if (remarkField != null) stage.put("remarkField", remarkField);
        stages.add(stage);
        return stage;
    }

    private void configureSourcePolicy(String connectionPid, String account, String remarkField) throws Exception {
        Path original = Path.of(procurementDirectory);
        Path appendix = caseEvidence.resolve("source-ui-initialization-appendix");
        Path copy = caseEvidence.resolve("source-policy-" + account);
        try (var paths = Files.walk(appendix)) {
            for (Path source : paths.toList()) {
                assertFalse(Files.isSymbolicLink(source));
                Path destination = copy.resolve(appendix.relativize(source));
                if (Files.isDirectory(source)) Files.createDirectories(destination); else Files.copy(source, destination);
            }
        }
        assertTrue(copy.toString().length() <= 256, "Actual import source_name bound");
        var definitions = json.readTree(copy.resolve("config/commands/procurement.json").toFile());
        int configuredCommands = 0;
        for (var definition : definitions) {
            if (!List.of(SYNC, CANDIDATES).contains(definition.path("code").asText())) continue;
            configuredCommands++;
            var catalog = (ArrayNode) definition.path("handlerParams").path("procurementSourceEligibilityPolicies");
            assertTrue(catalog.isEmpty());
            // Keep all prior exact account registrations admitted for the retained browser stages.
            for (var prior : policies) catalog.add(json.valueToTree(prior));
            catalog.add(json.valueToTree(policy(connectionPid, account, remarkField)));
        }
        assertEquals(2, configuredCommands);
        json.writeValue(copy.resolve("config/commands/procurement.json").toFile(), definitions);
        String importLabel = "source-ui-policy-" + account;
        String ownerPlugin = formalImport(copy, importLabel);
        sourceCommandProofs.clear();
        policies.add(policy(connectionPid, account, remarkField));
        for (String code : List.of(SYNC, CANDIDATES)) {
            var command = commands.findByCode(code);
            assertEquals(tenant, command.getTenantId());
            assertEquals("s2p_sourcing_event", command.getModelCode());
            assertEquals(json.valueToTree(policies), json.readTree(command.getExecutionConfig()).path("handlerParams").path("procurementSourceEligibilityPolicies"));
            assertEquals("published", command.getStatus());
            assertEquals(Boolean.TRUE, command.getIsCurrent());
            var raw = new org.springframework.jdbc.core.JdbcTemplate(postgres).queryForMap(
                    "SELECT id,pid,code,model_code,tenant_id,plugin_pid,status,is_current,version,execution_config::text AS execution_config FROM ab_command_definition WHERE tenant_id=? AND pid=? AND code=? AND is_current=TRUE AND deleted_flag=FALSE",
                    tenant, command.getPid(), code);
            assertEquals(command.getId(), raw.get("id"));
            assertEquals(ownerPlugin, raw.get("plugin_pid"));
            assertEquals(json.readTree(command.getExecutionConfig()), json.readTree(raw.get("execution_config").toString()));
            String artifactLabel = "source-ui-command-" + account + "-" + (code.equals(SYNC) ? "sync" : "candidates");
            writeArtifact(artifactLabel + ".json", raw);
            var proof = new LinkedHashMap<String, Object>();
            proof.put("id", command.getId());
            proof.put("pid", command.getPid());
            proof.put("code", code);
            proof.put("modelCode", command.getModelCode());
            proof.put("tenantId", tenant.toString());
            proof.put("pluginPid", raw.get("plugin_pid"));
            proof.put("status", command.getStatus());
            proof.put("isCurrent", command.getIsCurrent());
            proof.put("version", command.getVersion());
            proof.put("metadataPath", caseEvidence.resolve(artifactLabel + ".json").toString());
            proof.put("metadataSha256", sha(caseEvidence.resolve(artifactLabel + ".json")));
            proof.put("actualExecutionConfigSha256", digest(raw.get("execution_config").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            proof.put("importPreviewPath", caseEvidence.resolve(importLabel + "-preview.json").toString());
            proof.put("importPreviewSha256", sha(caseEvidence.resolve(importLabel + "-preview.json")));
            proof.put("importResultPath", caseEvidence.resolve(importLabel + "-result.json").toString());
            proof.put("importResultSha256", sha(caseEvidence.resolve(importLabel + "-result.json")));
            sourceCommandProofs.add(proof);
        }
        writeArtifact("source-policy-" + account + ".json", Map.of("sourceCommandSha256", sha(original.resolve("config/commands/procurement.json")),
                "configuredCommandSha256", sha(copy.resolve("config/commands/procurement.json")), "testOnlyPolicies", List.copyOf(policies)));
    }

    static Map<String, Object> policy(String connectionPid, String account) {
        return Map.of("connectionPid", connectionPid, "accountId", account,
                "rules", List.of(Map.of("documentStatus", "C", "cancelStatus", "A", "closeStatus", "A", "eligible", true)));
    }

    static Map<String, Object> policy(String connectionPid, String account, String remarkField) {
        if (remarkField == null) return policy(connectionPid, account);
        assertEquals("FControlledRemark", remarkField, "Only this synthetic provider's declared nineteenth column is admitted");
        var result = new LinkedHashMap<>(policy(connectionPid, account));
        result.put("remarkField", remarkField);
        return result;
    }

    private JsonNode candidates(Map<String, Object> stage) throws Exception {
        return action(buyerToken, CANDIDATES, stage.get("taskPid").toString(), Map.of("billNo", stage.get("billNo"), "pageSize", 2));
    }

    private void sync(Map<String, Object> stage, List<Map<String, String>> selected) throws Exception {
        task = stage.get("taskPid").toString();
        connection = stage.get("connectionPid").toString();
        assertEquals(selected.size(), candidates(stage).path("records").size());
        action(buyerToken, SYNC, task, Map.of("selected", selected, "pageSize", 2));
        assertEquals(selected.size(), sources().size());
    }

    private JsonNode action(String jwt, String code, String target, Map<String, Object> payload) throws Exception {
        var request = new LinkedHashMap<String, Object>();
        Object targetVersion=null;
        if (target != null) {
            request.put("targetRecordPid", target);
            targetVersion=fixture(SYNC, () -> db.getById(commands.findByCode(code).getModelCode(), target).get("row_version"));
            request.put("expectedVersion",targetVersion);
        }
        request.put("clientRequestId", fixtureRequestKey("public"));
        request.put("payload", payload);
        MetaContext.clear();
        org.springframework.test.web.servlet.MvcResult result;
        try {
            result = mvc.perform(post("/api/meta/commands/execute/" + code).header("Authorization", "Bearer " + jwt)
                    .contentType("application/json").content(json.writeValueAsBytes(request))).andReturn();
        } finally { context(); }
        JsonNode body=null;
        try {body=json.readTree(result.getResponse().getContentAsByteArray());}
        catch(java.io.IOException malformed) {
            writeArtifact("public-action-"+(++publicRequestSequence)+".json",safePublicResponse(result.getResponse().getStatus(),code,targetVersion,null));
            throw malformed;
        }
        writeArtifact("public-action-"+(++publicRequestSequence)+".json",safePublicResponse(result.getResponse().getStatus(),code,targetVersion,body));
        assertEquals(200, result.getResponse().getStatus(), "Actual public command " + code);
        var completedData=requireCompletedCommandResponse(body);
        publicActions.add(Map.of("command", code, "target", target == null ? "create" : target,
                "httpStatus", 200, "payload", payload));
        return completedData;
    }

    static Map<String,Object> safePublicResponse(int status,String command,Object version,JsonNode body) {
        var diagnostic=new LinkedHashMap<String,Object>();diagnostic.put("httpStatus",status);
        diagnostic.put("command",command);diagnostic.put("responseObject",body!=null && body.isObject());
        diagnostic.put("rawResponsePersisted",false);
        String versionType=version==null?"absent":version.getClass().getSimpleName();
        diagnostic.put("targetVersionType",List.of("absent","Integer","Long","BigInteger","BigDecimal","String").contains(versionType)?versionType:"other");
        if(body==null || !body.isObject())return diagnostic;
        for(String key:List.of("code","contextCode")) {
            JsonNode value=key.equals("code")?body.path("code"):body.path("context").path("code");
            if((value.isTextual() || value.isIntegralNumber()) && value.asText().matches("[0-9]{1,8}"))diagnostic.put(key,value.asText());
        }
        JsonNode messageKey=body.path("context").path("messageKey");
        if(messageKey.isTextual() && messageKey.asText().matches("\\$i18n:qo_pr\\.error\\.[a-z0-9_]{1,80}"))diagnostic.put("messageKey",messageKey.asText());
        String phase=body.path("data").path("phaseReached").asText();
        if(Set.of("completed","handler","validation","authorization","target_scope","assert","auto_set","field_map","computed_fields").contains(phase))diagnostic.put("phaseReached",phase);
        for(String key:List.of("fieldCode","fieldName","field")) {
            JsonNode field=body.path("context").path(key);
            if(field.isTextual() && Set.of("row_version","s2p_se_collaboration_managed","s2p_se_kingdee_connection_pid",
                    "customer_pcba_kd_acct_id","customer_pcba_kd_base_url","customer_pcba_kd_username",
                    "customer_pcba_kd_app_id","customer_pcba_kd_app_secret_ref").contains(field.asText()))
                diagnostic.put("knownContextField",field.asText());
        }
        return diagnostic;
    }
    static JsonNode requireCompletedCommandResponse(JsonNode body) {
        assertTrue(body!=null && body.isObject(),"Public command response must be an object");
        assertTrue(body.path("code").isTextual() && "0".equals(body.path("code").textValue()),
                "Public command requires its exact textual success code");
        JsonNode result=body.path("data");
        assertTrue(result.isObject() && result.path("phaseReached").isTextual()
                && "completed".equals(result.path("phaseReached").textValue()),"Public command must complete");
        assertTrue(result.path("data").isObject(),"Completed public command business data must be an object");
        return result.path("data");
    }

    private Map<String, Object> submitQuote(Map<String, Object> stage, boolean allocate) throws Exception {
        task = stage.get("taskPid").toString();
        connection = stage.get("connectionPid").toString();
        String category = initialize("pr_spend_category", Map.of("pr_spc_code", stage.get("namespace"), "pr_spc_name", "Synthetic source category"));
        String supplier = initialize("pr_supplier_common", Map.of("pr_supplier_code", stage.get("namespace"),
                "pr_supplier_name", "Synthetic source supplier", "pr_supplier_status", "enabled", "qo_pr_supplier_account_id", stage.get("accountId")));
        action(makerToken, "qo_procurement_category_policy_common:create", null,
                Map.of("categoryPid", category, "connectionPid", connection, "supplierPids", List.of(supplier), "defaultSupplierPids", List.of(supplier)));
        for (var row : sources()) action(makerToken, "qo_procurement_task_common:classify_source", task,
                Map.of("sourcePid", pid(row), "sourceRevision", row.get("qo_pr_source_revision"), "categoryPid", category));
        action(makerToken, "qo_procurement_task_common:dispatch", task,
                Map.of("supplierPids", List.of(supplier), "cutoffAt", "2066-11-15T08:00:00Z"));
        String quote = fixture(SYNC, () -> pid(db.query("s2p_bid", Map.of("s2p_bid_sourcing_event_id", task)).getFirst()));
        String email = stage.get("namespace") + "-supplier@example.invalid";
        context();
        var supplierUser = users.signUp(email, required("PROCUREMENT_SOURCE_UI_FIXTURE_PASSWORD"), "Synthetic supplier", stage.get("namespace").toString());
        var supplierMember = members.addMember(supplierUser.getId(), tenant, "active");
        String supplierJwt = actorToken(supplierUser.getId(), supplierUser.getPid(), supplierMember.getId(), email,
                List.of("meta.command.execute", "qo.srm.use"), required("PROCUREMENT_SOURCE_UI_FIXTURE_PASSWORD"));
        String contact = initialize("pr_supplier_pcba_contact", Map.of("pr_spc_supplier_id", supplier, "pr_spc_contact_name", "Synthetic contact", "qo_pr_contact_state", "active"));
        initialize("qo_procurement_membership_common", Map.of("qo_pr_membership_key", stage.get("namespace"),
                "qo_pr_membership_user_pid", supplierUser.getPid(), "qo_pr_membership_supplier_pid", supplier,
                "qo_pr_membership_contact_pid", contact, "qo_pr_membership_state", "active"));
        var lines = new ArrayList<Map<String, Object>>();
        for (var source : sources()) {
            var line = new LinkedHashMap<String, Object>();
            line.put("sourcePid", pid(source)); line.put("sourceRevision", source.get("qo_pr_source_revision"));
            line.put("unitPrice", "1.25"); line.put("currency", "CNY"); line.put("unit", "PCS");
            line.put("taxRate", "0.13"); line.put("taxIncluded", true); line.put("availableQty", "600");
            line.put("deliveryDate", "2066-12-01"); lines.add(line);
        }
        action(supplierJwt, "qo_procurement_quote_common:save_draft", quote, Map.of("revision", 0, "lines", lines, "paymentTerms", "Net 30"));
        var submitted = action(supplierJwt, "qo_procurement_quote_common:submit", quote, Map.of("revision", 1));
        String submission = submitted.path("submissionPid").asText(); assertFalse(submission.isBlank());
        if (allocate) {
            var demands = new ArrayList<Map<String, Object>>(); var allocations = new ArrayList<Map<String, Object>>();
            for (var source : sources()) {
                demands.add(Map.of("sourcePid", pid(source), "sourceRevision", source.get("qo_pr_source_revision"), "quantity", "125"));
                allocations.add(Map.of("quotePid", quote, "sourcePid", pid(source), "submissionPid", submission, "quantity", "125"));
            }
            action(makerToken, "qo_procurement_task_common:allocate", task, Map.of("sourceDemands", demands, "allocations", allocations));
        }
        return Map.of("quotePid", quote, "submissionPid", submission, "immutableSnapshot", formalSnapshot(submission));
    }

    private JsonNode control(String path, Map<String, Object> body) throws Exception {
        var request = HttpRequest.newBuilder(controlBase.resolve(path)).timeout(java.time.Duration.ofSeconds(30)).header("X-Aura-ERP-Fixture-Token", controlToken)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build();
        var response = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return json.readTree(response.body());
    }

    private JsonNode sourceState(String namespace) throws Exception {
        var request = HttpRequest.newBuilder(controlBase.resolve("/__control/source-state?namespace=" + namespace)).timeout(java.time.Duration.ofSeconds(30))
                .header("X-Aura-ERP-Fixture-Token", controlToken).GET().build();
        var response = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return json.readTree(response.body());
    }

    /** Capture database JSON without JDBC Timestamp or JSONB map serialization loss. */
    private com.fasterxml.jackson.databind.JsonNode rawBusinessSnapshot(Map<String, Object> tables) throws Exception {
        var result = json.createObjectNode();
        try (var connection = postgres.getConnection()) {
            String previousZone;
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SHOW TIME ZONE")) {
                assertTrue(rows.next()); previousZone = rows.getString(1);
            }
            try {
                try (var statement = connection.prepareStatement("SELECT set_config('TimeZone', ?, false)")) {
                    statement.setString(1, "UTC"); statement.execute();
                }
                for (var entity : tables.entrySet()) {
                    var binding = (Map<?, ?>) entity.getValue();
                    String table = binding.get("physicalTable").toString();
                    assertTrue(table.matches("[A-Za-z_][A-Za-z0-9_]{0,62}"));
                    String sql = "SELECT COALESCE(json_agg(t ORDER BY t.pid), '[]'::json)::text FROM "
                            + table + " t WHERE tenant_id=?";
                    try (var statement = connection.prepareStatement(sql)) {
                        statement.setLong(1, tenant);
                        try (var rows = statement.executeQuery()) {
                            assertTrue(rows.next()); result.set(entity.getKey(), readLosslessDatabaseJson(rows.getString(1)));
                            assertFalse(rows.next());
                        }
                    }
                }
            } finally {
                try (var statement = connection.prepareStatement("SELECT set_config('TimeZone', ?, false)")) {
                    statement.setString(1, previousZone); statement.execute();
                }
            }
        }
        return result;
    }

    static com.fasterxml.jackson.databind.JsonNode readLosslessDatabaseJson(String value) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
                .setNodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true))
                .readTree(value);
    }

    private Map<String, Object> tableMap() {
        var result = new LinkedHashMap<String, Object>();
        for (String entity : List.of("task", "source", "quote", "quote_line", "history", "submission", "submission_line", "allocation", "execution", "order", "order_line", "externalReferences")) {
            String model = entity.equals("externalReferences") ? "customer_pcba_int_external_ref" : domainModel(entity);
            String table = actualMetadata.getTableName(model);
            assertTrue(table.matches("[A-Za-z_][A-Za-z0-9_]{0,62}"));
            var modelDefinition = actualMetadata.getModelDefinitionFromDb(model).orElseThrow();
            assertEquals(table, modelDefinition.getTableName());
            var row = new org.springframework.jdbc.core.JdbcTemplate(postgres).queryForMap("SELECT id,pid,code,tenant_id,plugin_pid,status FROM ab_meta_model WHERE tenant_id=? AND code=? AND is_current=TRUE AND deleted_flag=FALSE", tenant, model);
            assertEquals(tenant, row.get("tenant_id"));
            assertEquals(modelDefinition.getId(), row.get("id"));
            assertEquals(model, row.get("code"));
            assertEquals("published", row.get("status"));
            assertTrue(row.get("plugin_pid") instanceof String && !row.get("plugin_pid").toString().isBlank());
            assertEquals(table, new org.springframework.jdbc.core.JdbcTemplate(postgres).queryForObject("SELECT to_regclass(?)::text", String.class, table));
            result.put(entity, Map.of("modelCode", model, "physicalTable", table, "actualTenantModel", row));
        }
        return result;
    }

    static void assertDecimal(String expected, Object value) {
        assertNotNull(value);
        assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(value.toString())));
    }

    private static String digest(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
