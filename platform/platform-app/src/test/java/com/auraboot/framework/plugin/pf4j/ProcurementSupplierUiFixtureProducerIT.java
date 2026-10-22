package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dto.AuthenticationRequest;
import com.auraboot.framework.common.crypto.FieldEncryptionService;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.service.CommandExecutor;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Retained test-owned UI prerequisites, not browser or customer integration acceptance. */
@SpringBootTest(classes = TestApplication.class, properties = {
        "spring.datasource.url=${TEST_DATABASE_URL}", "spring.data.redis.database=${TEST_REDIS_DATABASE}",
        "aura.plugins.dir=${PROCUREMENT_SOURCE_PLUGIN_DIR}",
        "security.field-encryption.key=${PROCUREMENT_SUPPLIER_UI_FIELD_ENCRYPTION_KEY}",
        "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.slf4j.Slf4jImpl",
        "logging.level.com.auraboot.framework=INFO", "logging.level.org.mybatis=INFO",
        "logging.level.org.springframework.security=WARN", "logging.level.org.springframework.cache=WARN"})
@ActiveProfiles("integration-test")
@Import(ProcurementPublicSourceNativeFixture.IsolatedScheduling.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "PROCUREMENT_SUPPLIER_UI_FIXTURE_IT", matches = "true")
class ProcurementSupplierUiFixtureProducerIT extends ProcurementPublicSourceNativeFixture {
    static final List<String> STAGES = List.of("quoteSubmit", "quoteDecline", "shipmentJourney", "shipmentUnknown", "closedOrder");
    static final List<String> SUPPLIER_PERMISSIONS = List.of("meta.command.execute", "qo.srm.use", "page.page.read");
    static final List<String> INITIALIZER_PERMISSIONS = initializerPermissions();
    static final List<String> SOURCE_FILES = List.of(
            "platform/src/test/java/com/auraboot/framework/plugin/pf4j/ProcurementSupplierUiFixtureProducerIT.java",
            "platform/src/test/java/com/auraboot/framework/plugin/pf4j/ProcurementSupplierUiInitialization.java",
            "platform/src/test/java/com/auraboot/framework/plugin/pf4j/ProcurementPublicSourceNativeFixture.java",
            "platform/src/test/java/com/auraboot/framework/plugin/pf4j/ProcurementSourceUiFixtureProducerIT.java",
            "platform/src/test/java/com/auraboot/framework/plugin/pf4j/ProcurementOrderUiFixtureProducerIT.java",
            "platform/src/test/java/com/auraboot/framework/notification/sourcebinding/ProductQuoteNativeInitialization.java");
    static final String MAIN_BASE = "50924063538e4181cb601300f0c26c6bbfcce2f0";
    static final List<String> DOMAINS = List.of("task", "source", "quote", "quote_line", "history", "submission", "submission_line", "allocation", "execution", "order", "order_line", "externalReferences");
    static final List<String> AUXILIARY = List.of("supplier", "contact", "membership", "address", "shipment", "shipment_line", "product", "category", "category_policy", "category_supplier", "connection");
    @Autowired CommandExecutor actualCommands;
    @Autowired MetaModelService actualMetadata;
    @Autowired FieldEncryptionService actualEncryption;
    private JsonNode input;
    private JsonNode binding;
    private String makerJwt;
    private String supplierJwt;
    private String supplierPid;
    private String contactPid;
    private String materialPid;
    private String categoryPid;
    private Map<String, Object> supplierIdentity;
    private Map<String, Object> initializerIdentity;
    private final List<Map<String, Object>> initializationActions = new ArrayList<>();
    private final List<Map<String, Object>> publicActions = new ArrayList<>();
    private final List<Map<String, Object>> importProofs = new ArrayList<>();
    private final List<Map<String, Object>> initializationCommandProofs = new ArrayList<>();
    private String stage = "before-admission";
    private boolean completed;

    static List<String> initializerPermissions() {
        var permissions = new ArrayList<>(ProcurementSourceUiFixtureProducerIT.MAKER_PERMISSIONS);
        permissions.addAll(List.of("model.pr_purchase_order.read",
                "model.pr_purchase_order_line.read", "model.pr_supplier_pcba_contact.read",
                "model.qo_procurement_membership_common.read", "model.qo_procurement_address_common.read"));
        return List.copyOf(permissions);
    }

    static void requireSelectedInput(JsonNode value) {
        assertEquals("procurement-supplier-ui-input-v1", value.path("kind").asText());
        assertTrue(value.path("schemaVersion").isIntegralNumber());
        assertEquals(1, value.path("schemaVersion").asInt(-1));
        assertTrue(value.path("supplierFixtureEnabled").isBoolean() && value.path("supplierFixtureEnabled").booleanValue());
    }

    static String requestKey() { return "supplier-init-" + UUID.randomUUID(); }

    /** Override the shared setup: never inherit its old private production-identity seeds. */
    @Override @BeforeEach
    void prepare(TestInfo info) throws Exception {
        Path selectedInput = Path.of(required("PROCUREMENT_SUPPLIER_UI_INPUT")).toRealPath();
        Path evidenceRoot = Path.of(required("PROCUREMENT_PUBLIC_SOURCE_EVIDENCE_DIR")).toRealPath();
        assertTrue(selectedInput.startsWith(evidenceRoot));
        input = json.readTree(selectedInput.toFile());
        requireSelectedInput(input);
        assertEquals(required("PROCUREMENT_SUPPLIER_UI_INPUT_SHA256"), sha(Path.of(required("PROCUREMENT_SUPPLIER_UI_INPUT"))));
        super.prepareEvidence(info);
        binding = json.readTree(Path.of(required("PROCUREMENT_PUBLIC_SOURCE_READINESS_INPUT")).toFile());
        assertEquals(required("PROCUREMENT_PUBLIC_SOURCE_READINESS_SHA256"), sha(Path.of(required("PROCUREMENT_PUBLIC_SOURCE_READINESS_INPUT"))));
        assertEquals(binding.path("sourceSet"), input.path("sourceSet"));
        Path app = Path.of(input.path("applicationCoreRoot").asText()).toRealPath();
        assertEquals(input.path("applicationCoreCommit").asText(), gitRead(app, "rev-parse", "HEAD").strip());
        assertEquals("", gitRead(app, "status", "--porcelain").strip());
        assertEquals("", gitRead(app, "diff", MAIN_BASE, "HEAD", "--", "platform/src/main").strip());
        assertEquals(app.resolve("platform/build/classes/java/test").toRealPath(),
                Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());
        assertTrue(input.path("nativeSourceSha256").isObject());
        assertEquals(Set.copyOf(SOURCE_FILES), json.convertValue(input.path("nativeSourceSha256"), Map.class).keySet());
        for (var file : input.path("nativeSourceSha256").properties())
            assertEquals(file.getValue().asText(), sha(app.resolve(file.getKey())));
        admitEncryption(app);
        assertEquals("13", required("TEST_REDIS_DATABASE"));
        try (var sql = postgres.getConnection()) { assertEquals("enterprise_22", sql.getCatalog()); }
        assertTrue(extensions.getCommandHandler(SYNC).isPresent());
        provider = "sui_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        var bootstrap = users.signUp(provider + "-bootstrap@example.invalid", password(), "Supplier UI bootstrap", provider);
        userId = bootstrap.getId(); actor = bootstrap.getPid();
        var organization = new Tenant();
        organization.setPid(com.auraboot.framework.common.util.UniqueIdGenerator.generate());
        organization.setName(provider); organization.setDisplayName(provider); organization.setStatus("active");
        tenant = tenants.createTenant(organization).getId();
        assertNotEquals(Long.valueOf(required("TEST_TENANT_ID")), tenant);
        var bootstrapMember = members.addMember(userId, tenant, "active"); memberId = bootstrapMember.getId(); context();
        assertTrue(tenantBootstrap.bootstrapTenant(tenant, userId).isSuccess());
        retainedBootstrapPrincipal = Map.of("userPid", actor, "memberId", memberId.toString(),
                "purpose", "Formal bootstrap/import only", "usedForTestedPublicRequest", false);
        var directories = json.readTree(required("PROCUREMENT_SOURCE_IMPORT_DIRECTORIES"));
        assertTrue(directories.isArray() && directories.size() == 23);
        var paths = new ArrayList<Path>();
        for (JsonNode directory : directories) paths.add(Path.of(directory.asText()).toRealPath());
        stage = "formal-original-import";
        for (Path path : paths) formalImport(path);
        imports.verifyImportReferenceIntegrity();
        stage = "test-owned-initialization-appendix";
        var appendix = ProcurementSupplierUiInitialization.prepare(paths, caseEvidence.resolve("supplier-ui-appendix"), json);
        writeArtifact("supplier-ui-initialization-appendix.json", appendix);
        @SuppressWarnings("unchecked") var owners = (List<Map<String, Object>>) appendix.get("ownerDirectories");
        for (var owner : owners) formalImport(Path.of(owner.get("testDirectory").toString()));
        imports.verifyImportReferenceIntegrity();
        validateImportedInitialization(appendix);
        var maker = users.signUp(provider + "-maker@example.invalid", password(), "Supplier UI initializer", provider + "_maker");
        var makerMember = members.addMember(maker.getId(), tenant, "active");
        makerJwt = actorToken(maker.getId(), maker.getPid(), makerMember.getId(), maker.getEmail(), INITIALIZER_PERMISSIONS, password());
        initializerIdentity = identity(maker.getId(), retainedActors.getLast());
        mvc = MockMvcBuilders.webAppContextSetup(application).addFilters(springSecurityFilterChain).build();
        stage = "native-initial-facts";
        materialPid = initialize("prod_product", Map.of("prod_code", provider, "prod_name", "Synthetic supplier UI material", "prod_type", "raw_material", "prod_unit", "pcs", "prod_status", "active"));
        connection = initialize("customer_pcba_int_kingdee_connection", Map.of("customer_pcba_kd_profile_name", provider,
                "customer_pcba_kd_status", "active", "customer_pcba_kd_acct_id", provider,
                "customer_pcba_kd_base_url", "http://127.0.0.1:52169", "customer_pcba_kd_app_id", provider,
                "customer_pcba_kd_username", provider, "customer_pcba_kd_app_secret_ref", "cloud-config:" + provider,
                "customer_pcba_kd_auth_type", "app-secret"));
        supplierPid = initialize("pr_supplier_common", Map.of("pr_supplier_code", provider, "pr_supplier_name", "Synthetic UI Supplier", "pr_supplier_status", "enabled", "qo_pr_supplier_account_id", provider));
        categoryPid = initialize("pr_spend_category", Map.of("pr_spc_code", provider, "pr_spc_name", "Supplier UI category"));
        contactPid = initialize("pr_supplier_pcba_contact", Map.of("pr_spc_supplier_id", supplierPid, "pr_spc_contact_name", "Synthetic UI Contact", "qo_pr_contact_state", "active"));
        var supplier = users.signUp(provider + "-supplier@example.invalid", password(), "Synthetic UI Contact", provider + "_supplier");
        var supplierMember = members.addMember(supplier.getId(), tenant, "active");
        initialize("qo_procurement_membership_common", Map.of("qo_pr_membership_key", provider, "qo_pr_membership_user_pid", supplier.getPid(), "qo_pr_membership_supplier_pid", supplierPid, "qo_pr_membership_contact_pid", contactPid, "qo_pr_membership_state", "active"));
        supplierJwt = actorToken(supplier.getId(), supplier.getPid(), supplierMember.getId(), supplier.getEmail(), SUPPLIER_PERMISSIONS, password());
        supplierIdentity = identity(supplier.getId(), retainedActors.getLast());
        action(makerJwt, "qo_procurement_category_policy_common:create", null, Map.of("categoryPid", categoryPid,
                "connectionPid", connection, "supplierPids", List.of(supplierPid), "defaultSupplierPids", List.of(supplierPid)));
        priorCaseFacts = null; // Independent raw snapshots are emitted by this producer, not the shared legacy reader.
    }

    private void admitEncryption(Path app) throws Exception {
        Path proofPath = Path.of(input.path("encryptionConfigProofPath").asText()).toRealPath();
        assertEquals(input.path("encryptionConfigProofSha256").asText(), sha(proofPath));
        Path ownedEvidence = Path.of(input.path("workspaceRoot").asText()).toRealPath().resolve(".workspace/evidence/procurement-web-stack-20261010").toRealPath();
        assertTrue(proofPath.startsWith(ownedEvidence));
        JsonNode proof = json.readTree(proofPath.toFile());
        for (String prefix : List.of("runtimeAdmission", "runtimeEnvironmentEvidence", "runtimeProfileEvidence")) {
            Path artifact = Path.of(proof.path(prefix + "Path").asText()).toRealPath();
            assertTrue(artifact.startsWith(ownedEvidence)); assertEquals(proof.path(prefix + "Sha256").asText(), sha(artifact));
        }
        JsonNode runtimeAdmission = json.readTree(Path.of(proof.path("runtimeAdmissionPath").asText()).toFile());
        assertEquals("ok", runtimeAdmission.path("verdict").asText());
        assertEquals("procurement-web-stack-20261010", runtimeAdmission.path("runtime").asText());
        assertEquals(proof.path("runtimeMode").asText(), runtimeAdmission.path("metadata").path("mode").asText());
        JsonNode environmentEvidence = json.readTree(Path.of(proof.path("runtimeEnvironmentEvidencePath").asText()).toFile());
        JsonNode profileEvidence = json.readTree(Path.of(proof.path("runtimeProfileEvidencePath").asText()).toFile());
        assertEquals(proof.path("environmentKeyState").asText(), environmentEvidence.path("fieldEncryptionKeyState").asText());
        assertEquals(proof.path("actualSpringProfiles").asText(), profileEvidence.path("actualSpringProfiles").asText());
        assertEquals(input.path("sourceSet").path("oss").asText(), proof.path("sourceCommit").asText());
        assertEquals(sha(app.resolve("platform/src/main/java/com/auraboot/framework/common/crypto/FieldEncryptionService.java")), proof.path("fieldEncryptionServiceSha256").asText());
        assertEquals(sha(app.resolve("platform/platform-app/src/main/resources/application.yml")), proof.path("applicationYmlSha256").asText());
        assertTrue(System.getenv().containsKey("PROCUREMENT_SUPPLIER_UI_FIELD_ENCRYPTION_KEY"));
        assertEquals(input.path("encryptionMode").asText(), ProcurementOrderUiFixtureProducerIT.validateEncryptionMode(proof, System.getenv("PROCUREMENT_SUPPLIER_UI_FIELD_ENCRYPTION_KEY")));
        String probe = "SupplierUiEncryptionProbe";
        assertEquals(probe, actualEncryption.decrypt(actualEncryption.encrypt(probe)));
        writeArtifact("encryption-mode-admission.json", Map.of("mode", input.path("encryptionMode").asText(),
                "proofPath", proofPath.toString(), "proofSha256", sha(proofPath), "actualSpringProfiles", proof.path("actualSpringProfiles").asText(), "nonSecretBeanProbeMatched", true));
    }

    private void formalImport(Path directory) throws Exception {
        assertTrue(directory.toString().length() <= 256, "Formal source_name must remain bounded");
        var preview = imports.parseDirectory(directory.toString(), true);
        String name = "import-" + importProofs.size(); writeArtifact(name + "-preview.json", preview);
        assertTrue(preview.isValid(), "Supplier UI import: " + preview.getErrors());
        var request = com.auraboot.framework.plugin.dto.imports.ImportRequest.builder().importId(preview.getImportId())
                .conflictStrategy(com.auraboot.framework.plugin.dto.imports.ImportRequest.ConflictStrategy.OVERWRITE)
                .autoPublishFields(true).autoPublishModels(true).autoPublishCommands(true).autoPublishPages(true).createResourcePermissions(true).build();
        var result = imports.execute(preview.getImportId(), request); writeArtifact(name + "-result.json", result);
        assertTrue(result.isSuccess(), result.getErrorMessage());
        importProofs.add(Map.of("directory", directory.toString(), "pluginId", result.getPluginId(), "pluginPid", result.getPluginPid(),
                "previewPath", caseEvidence.resolve(name + "-preview.json").toString(), "previewSha256", sha(caseEvidence.resolve(name + "-preview.json")),
                "resultPath", caseEvidence.resolve(name + "-result.json").toString(), "resultSha256", sha(caseEvidence.resolve(name + "-result.json"))));
    }

    private void validateImportedInitialization(Map<String, Object> appendix) throws Exception {
        for (var model : ProcurementSupplierUiInitialization.FIELDS.entrySet()) {
            var command = commands.findByCode(ProcurementSupplierUiInitialization.command(model.getKey()));
            assertEquals(tenant, command.getTenantId()); assertEquals(model.getKey(), command.getModelCode());
            assertEquals("published", command.getStatus()); assertEquals(true, command.getIsCurrent());
            var commandRow = new JdbcTemplate(postgres).queryForMap(
                    "SELECT id,pid,code,model_code,tenant_id,plugin_pid,status,is_current,version,execution_config::text AS execution_config FROM ab_command_definition WHERE tenant_id=? AND pid=? AND code=? AND is_current=TRUE AND deleted_flag=FALSE",
                    tenant, command.getPid(), command.getCode());
            assertEquals(command.getId(), commandRow.get("id"));
            assertEquals(command.getModelCode(), commandRow.get("model_code"));
            assertEquals(tenant, commandRow.get("tenant_id"));
            assertEquals(json.readTree(command.getExecutionConfig()), json.readTree(commandRow.get("execution_config").toString()));
            String artifact = "initialization-command-" + model.getKey() + ".json";
            writeArtifact(artifact, commandRow);
            var commandProof = new LinkedHashMap<String, Object>();
            commandProof.put("code", command.getCode());
            commandProof.put("modelCode", command.getModelCode());
            commandProof.put("metadataPath", caseEvidence.resolve(artifact).toString());
            commandProof.put("metadataSha256", sha(caseEvidence.resolve(artifact)));
            commandProof.put("actualExecutionConfigSha256", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(commandRow.get("execution_config").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))));
            var ownerImport = importProofs.stream().filter(proof -> commandRow.get("plugin_pid").equals(proof.get("pluginPid"))).reduce((first, latest) -> latest).orElseThrow();
            commandProof.put("importProof", ownerImport);
            initializationCommandProofs.add(commandProof);
            var definition = actualMetadata.getModelDefinitionFromDb(model.getKey()).orElseThrow();
            for (String code : model.getValue()) assertTrue(definition.getFields().stream().anyMatch(f -> code.equals(f.getCode())), "Actual physical field missing: " + code);
        }
        JsonNode expected = json.valueToTree(appendix);
        var projected = new LinkedHashMap<String, Object>();
        for (var model : ProcurementSupplierUiInitialization.FIELDS.entrySet()) {
            var definition = actualMetadata.getModelDefinitionFromDb(model.getKey()).orElseThrow();
            for (JsonNode proof : expected.path("exactModelCreateWriterAppendix")) {
                if (model.getKey().equals(proof.path("model").asText()))
                    assertEquals(proof.path("testPolicy"), json.valueToTree(definition.getExtension().get("recordCommandWriters")));
            }
            var actualFields = new LinkedHashMap<String, Object>();
            for (String code : model.getValue()) {
                var field = definition.getFields().stream().filter(f -> code.equals(f.getCode())).findFirst().orElseThrow();
                for (JsonNode proof : expected.path("exactFieldWriterAppendix")) {
                    if (code.equals(proof.path("field").asText()) && json.convertValue(proof.path("models"), List.class).contains(model.getKey()))
                        assertEquals(proof.path("testWriters"), json.valueToTree(field.getAllowedWriterCommands()));
                }
                actualFields.put(code, field.getAllowedWriterCommands() == null ? List.of() : field.getAllowedWriterCommands());
            }
            projected.put(model.getKey(), Map.of("actualWriters", actualFields, "modelId", definition.getId()));
        }
        writeUnchecked("projected-initialization-graph.json", projected);
    }

    private void writeUnchecked(String name, Object value) {
        try { writeArtifact(name, value); } catch (Exception error) { throw new IllegalStateException(error); }
    }

    private String initialize(String model, Map<String, Object> facts) throws Exception {
        ProcurementSupplierUiInitialization.validatePayload(model, facts);
        var request = new CommandExecuteRequest(); request.setPayload(new LinkedHashMap<>(facts)); request.setClientRequestId(requestKey());
        // Authenticate the exact test-owned maker; no runWithAuthorizedCommandCode/permit impersonation.
        var identity = initializerIdentity; var user = users.findByPid(identity.get("userPid").toString());
        var auth = new AuthenticationRequest(); auth.setEmail(user.getEmail()); auth.setPassword(password());
        var loggedIn = authentication.authenticate(auth); assertEquals(tenant, loggedIn.getTenantId()); assertEquals(user.getId(), loggedIn.getUserId());
        var saved = MetaContext.snapshot();
        try {
            MetaContext.setContext(tenant, user.getId(), user.getPid(), provider); MetaContext.setMemberId(Long.valueOf(identity.get("memberId").toString()));
            var result = actualCommands.execute(ProcurementSupplierUiInitialization.command(model), request);
            assertEquals("completed", result.getPhaseReached()); String pid = result.getData().get("recordPid").toString();
            initializationActions.add(Map.of("command", ProcurementSupplierUiInitialization.command(model), "model", model,
                    "recordPid", pid, "authenticationVerified", true, "syntheticInitialFactsOnly", true));
            return pid;
        } finally { MetaContext.clear(); MetaContext.restore(saved); }
    }

    private Map<String, Object> identity(Long id, Map<String, Object> original) {
        var result = new LinkedHashMap<>(original); result.put("userId", id.toString()); result.put("tenantId", tenant.toString());
        result.put("credentialEnvironment", "PROCUREMENT_SUPPLIER_UI_FIXTURE_PASSWORD"); result.put("dataScopeAuthority", "synthetic tenant only");
        return result;
    }

    static void requireCompletedCommandResponse(JsonNode body) {
        assertTrue(body.path("code").isTextual(), "Command ApiResponse code must be textual");
        assertEquals("0", body.path("code").textValue());
        assertEquals("completed", body.path("data").path("phaseReached").asText());
    }

    private JsonNode action(String jwt, String code, String target, Map<String, Object> payload) throws Exception {
        var request = new LinkedHashMap<String, Object>(); request.put("payload", payload); request.put("clientRequestId", requestKey());
        if (target != null) {
            request.put("targetRecordPid", target); request.put("expectedVersion", raw(commands.findByCode(code).getModelCode(), target).get("row_version"));
        }
        MetaContext.clear();
        try {
            var response = mvc.perform(post("/api/meta/commands/execute/" + code).header("Authorization", "Bearer " + jwt)
                    .contentType("application/json").content(json.writeValueAsBytes(request))).andReturn();
            assertEquals(200, response.getResponse().getStatus()); JsonNode body = json.readTree(response.getResponse().getContentAsByteArray());
            requireCompletedCommandResponse(body);
            publicActions.add(Map.of("command", code, "target", target == null ? "create" : target, "payload", payload, "httpStatus", 200));
            return body.path("data").path("data");
        } finally { context(); }
    }

    @Test void produceSupplierQuoteAndShipmentPrerequisites() throws Exception {
        var stages = new ArrayList<Map<String, Object>>();
        try {
            for (int index = 0; index < STAGES.size(); index++) {
                stage = STAGES.get(index);
                String taskPid = action(makerJwt, "qo_procurement_task_common:create", null,
                        Map.of("title", provider + " " + stage, "connectionPid", connection, "deliveryDate", "2066-12-01")).path("recordPid").asText();
                assertFalse(taskPid.isBlank()); retainedTaskPids.add(taskPid);
                var sources = new ArrayList<String>();
                for (int entry = 1; entry <= 2; entry++) {
                    String source = initialize("pr_purchase_request", sourceFacts(taskPid, index, entry)); sources.add(source);
                    if (stage.startsWith("quote")) action(makerJwt, "qo_procurement_task_common:classify_source", taskPid,
                            Map.of("sourcePid", source, "sourceRevision", raw("pr_purchase_request", source).get("qo_pr_source_revision"), "categoryPid", categoryPid));
                }
                var record = new LinkedHashMap<String, Object>(); record.put("stage", stage); record.put("taskPid", taskPid);
                record.put("sourceRows", sources.stream().map(id -> raw("pr_purchase_request", id)).toList()); record.put("accountId", provider);
                record.put("sourceSnapshot", sources.stream().map(id -> raw("pr_purchase_request", id)).toList());
                if (stage.startsWith("quote")) {
                    action(makerJwt, "qo_procurement_task_common:dispatch", taskPid,
                            Map.of("supplierPids", List.of(supplierPid), "cutoffAt", "2066-11-15T08:00:00Z"));
                    var quotes = rows("s2p_bid").stream().filter(q -> taskPid.equals(q.get("s2p_bid_sourcing_event_id"))).toList();
                    assertEquals(1, quotes.size()); var quote = quotes.getFirst(); assertEquals("draft", quote.get("s2p_bid_status"));
                    record.put("quotePid", quote.get("pid")); record.put("quoteRecord", quote);
                    record.put("lines", rows(domainModel("quote_line")).stream().filter(row -> quote.get("pid").equals(row.get("qo_pr_quote_line_quote_pid"))).toList());
                    assertEquals(2, ((List<?>) record.get("lines")).size());
                    record.put("expectedState", "draft");
                } else createOrderStage(record, sources, index);
                record.put("sourceRows", sources.stream().map(id -> raw("pr_purchase_request", id)).toList());
                record.put("sourceSnapshot", sources.stream().map(id -> raw("pr_purchase_request", id)).toList());
                stages.add(record);
            }
            stage = "actual-supplier-read-control";
            for (Map<String, Object> group : stages) {
                String entity = group.containsKey("quotePid") ? "quote" : "order";
                var read = action(supplierJwt, "qo_procurement_" + entity + "_common:view_mine", group.get(entity + "Pid").toString(), Map.of());
                assertEquals(group.get(entity + "Pid"), read.path(entity).path("pid").asText());
                assertEquals(2, read.path("lines").size());
                group.put("supplierProjection", json.convertValue(read, Map.class));
                group.put("rawLines", group.get("lines"));
                group.put(entity, json.convertValue(read.path(entity), Map.class));
                group.put("lines", json.convertValue(read.path("lines"), List.class));
                if (entity.equals("order")) {
                    var addressRead = action(supplierJwt, "qo_procurement_address_common:list_mine", null, Map.of("page", 1, "size", 100));
                    assertEquals(6, addressRead.path("total").asInt(-1));
                    assertEquals(6, addressRead.path("items").size());
                    var rawAddresses = (List<Map<String, Object>>) group.get("addresses");
                    var wanted = rawAddresses.stream().map(row -> row.get("pid").toString()).toList();
                    var selected = new ArrayList<Map<String, Object>>();
                    for (JsonNode address : addressRead.path("items")) {
                        if (wanted.contains(address.path("pid").asText())) {
                            assertTrue(address.path("selectable").asBoolean());
                            selected.add(json.convertValue(address, Map.class));
                        }
                    }
                    assertEquals(2, selected.size());
                    group.put("rawAddresses", rawAddresses);
                    group.put("addresses", selected);
                }
            }
            var before = snapshot(); var aux = auxiliarySnapshot();
            assertTrue(((List<?>) before.get("submission")).isEmpty()); assertTrue(((List<?>) before.get("allocation")).isEmpty());
            assertTrue(((List<?>) before.get("execution")).isEmpty()); assertTrue(((List<?>) aux.get("shipment")).isEmpty());
            var receipt = new LinkedHashMap<String, Object>(); receipt.put("kind", "procurement-supplier-ui-native-v1"); receipt.put("schemaVersion", 1);
            receipt.put("runtime", "procurement-web-stack-20261010"); receipt.put("database", "enterprise_22"); receipt.put("redisDatabase", 13);
            receipt.put("tenantId", tenant.toString()); receipt.put("namespace", provider); receipt.put("supplier", supplierIdentity);
            receipt.put("initializer", initializerIdentity); receipt.put("supplierPid", supplierPid); receipt.put("contactPid", contactPid);
            receipt.put("stages", stages); receipt.put("sourceSet", ProcurementOrderUiFixtureProducerIT.canonicalSources(binding.path("sourceSet")));
            receipt.put("sourceCommits", ProcurementOrderUiFixtureProducerIT.canonicalSources(binding.path("sourceSet")));
            for (String key : List.of("applicationCoreRoot", "applicationCoreCommit", "nativeSourceSha256", "encryptionMode", "encryptionConfigProofPath", "encryptionConfigProofSha256")) receipt.put(key, json.convertValue(input.path(key), Object.class));
            receipt.put("sourceBindingPath", required("PROCUREMENT_PUBLIC_SOURCE_READINESS_INPUT")); receipt.put("sourceBindingSha256", required("PROCUREMENT_PUBLIC_SOURCE_READINESS_SHA256"));
            receipt.put("nativeReadinessPath", caseEvidence.resolve("readiness.json").toString());
            receipt.put("nativeReadinessSha256", sha(caseEvidence.resolve("readiness.json")));
            receipt.put("encryptionModeAdmissionPath", caseEvidence.resolve("encryption-mode-admission.json").toString());
            receipt.put("encryptionModeAdmissionSha256", sha(caseEvidence.resolve("encryption-mode-admission.json")));
            receipt.put("projectedInitializationGraphPath", caseEvidence.resolve("projected-initialization-graph.json").toString());
            receipt.put("projectedInitializationGraphSha256", sha(caseEvidence.resolve("projected-initialization-graph.json")));
            receipt.put("productJarSha256", binding.path("productJarSha256").asText()); receipt.put("businessSnapshotTables", tableMap(DOMAINS));
            receipt.put("businessSnapshot", before); receipt.put("immutableBusinessBefore", before);
            receipt.put("auxiliarySnapshotTables", tableMap(AUXILIARY)); receipt.put("auxiliarySnapshot", aux); receipt.put("immutableAuxiliaryBefore", aux);
            receipt.put("initializationAppendixPath", caseEvidence.resolve("supplier-ui-initialization-appendix.json").toString());
            receipt.put("initializationAppendixSha256", sha(caseEvidence.resolve("supplier-ui-initialization-appendix.json")));
            receipt.put("initializationCommandProofs", initializationCommandProofs);
            receipt.put("initializationActions", initializationActions); receipt.put("publicActions", publicActions); receipt.put("formalImports", importProofs);
            receipt.put("membershipInitialFactsSynthetic", true); receipt.put("phoneOrWechatConsentVerified", false);
            receipt.put("receiptWriterVerified", false); receipt.put("realProviderInvoked", false); receipt.put("actualCustomerERPVerified", false);
            receipt.put("retainedAfterNative", true); receipt.put("browserExecuted", 0); receipt.put("bootstrapFactsAreBrowserAcceptance", false);
            Files.writeString(caseEvidence.resolve("supplier-ui-fixture-receipt.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(receipt) + "\n", StandardOpenOption.CREATE_NEW);
            completed = true; stage = "native-fixture-completed";
        } finally {
            writeArtifact("supplier-ui-producer-stage.json", Map.of("stage", stage, "completed", completed,
                    "receiptExists", Files.exists(caseEvidence.resolve("supplier-ui-fixture-receipt.json")), "browserExecuted", 0,
                    "realProviderInvoked", false, "publicActionCount", publicActions.size()));
        }
    }

    @Override @AfterEach
    void close() throws Exception {
        try {
            if (caseEvidence != null && !Files.exists(caseEvidence.resolve("supplier-ui-producer-stage.json")))
                writeArtifact("supplier-ui-producer-stage.json", Map.of("stage", stage, "completed", completed,
                        "receiptExists", Files.exists(caseEvidence.resolve("supplier-ui-fixture-receipt.json")),
                        "browserExecuted", 0, "realProviderInvoked", false, "publicActionCount", publicActions.size()));
        } finally { super.close(); }
    }

    private Map<String, Object> sourceFacts(String task, int index, int entry) {
        var facts = new LinkedHashMap<String, Object>();
        facts.put("pr_preq_code", provider + "-" + index + "-" + entry); facts.put("pr_preq_product_id", materialPid);
        facts.put("pr_preq_qty", "1500"); facts.put("pr_preq_source", "external_requisition"); facts.put("pr_preq_source_no", provider + "-" + index);
        facts.put("pr_preq_status", "pending"); facts.put("pr_preq_collaboration_managed", true);
        facts.put("qo_pr_source_key", provider + ":" + index + ":" + entry); facts.put("qo_pr_source_task_pid", task);
        facts.put("qo_pr_source_account_id", provider); facts.put("qo_pr_source_external_system", "KINGDEE"); facts.put("qo_pr_source_instance_key", connection);
        facts.put("qo_pr_source_requisition_id", Integer.toString(501 + index)); facts.put("qo_pr_source_entry_id", Integer.toString(entry));
        facts.put("qo_pr_source_material_id", "900"); facts.put("qo_pr_source_material_code", provider);
        facts.put("qo_pr_source_material_name", "Synthetic supplier UI material"); facts.put("qo_pr_source_specification", "Synthetic exact-decimal material");
        facts.put("qo_pr_source_unit", "PCS"); facts.put("qo_pr_source_remaining_qty", "1000"); facts.put("qo_pr_source_reserved_qty", "0");
        facts.put("qo_pr_source_delivery_date", "2066-12-01"); facts.put("qo_pr_source_revision", 0); facts.put("qo_pr_source_state", "active");
        return facts;
    }

    private void createOrderStage(Map<String, Object> stage, List<String> sources, int index) throws Exception {
        String state = stage.get("stage").equals("closedOrder") ? "closed" : "open";
        String erpId = provider + "-order-" + index;
        String order = initialize("pr_purchase_order", Map.ofEntries(Map.entry("pr_po_code", erpId), Map.entry("pr_po_supplier", supplierPid),
                Map.entry("pr_po_date", "2026-10-14"), Map.entry("pr_po_status", "approved"), Map.entry("pr_po_collaboration_managed", true),
                Map.entry("qo_pr_order_account_id", provider), Map.entry("qo_pr_order_key", erpId), Map.entry("qo_pr_order_erp_id", erpId),
                Map.entry("qo_pr_order_state", state), Map.entry("qo_pr_order_revision", 0), Map.entry("qo_pr_order_origin", "external_published"), Map.entry("qo_pr_order_change_pending", false)));
        var lines = new ArrayList<Map<String, Object>>(); var quantities = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 2; i++) {
            String quantity = i == 0 ? "300.000001" : "100"; String initial = state.equals("closed") && i == 0 ? "250" : "0";
            String line = initialize("pr_purchase_order_line", Map.ofEntries(Map.entry("pr_pol_order_id", order), Map.entry("pr_pol_product_id", materialPid),
                    Map.entry("pr_pol_qty", quantity), Map.entry("pr_pol_price", "1.25"), Map.entry("pr_pol_amount", new BigDecimal(quantity).multiply(new BigDecimal("1.25")).toPlainString()),
                    Map.entry("pr_pol_received_qty", initial), Map.entry("pr_pol_collaboration_managed", true), Map.entry("qo_pr_order_line_source_pid", sources.get(i)),
                    Map.entry("qo_pr_order_line_erp_entry_id", Integer.toString(i + 1)), Map.entry("qo_pr_order_line_submitted_qty", initial), Map.entry("qo_pr_order_line_material_code", provider),
                    Map.entry("qo_pr_order_line_material_name", "Synthetic supplier UI material"), Map.entry("qo_pr_order_line_specification", "Synthetic exact-decimal material"),
                    Map.entry("qo_pr_order_line_unit", "PCS"), Map.entry("qo_pr_order_line_delivery_date", "2066-12-01")));
            lines.add(raw("pr_purchase_order_line", line)); quantities.add(Map.of("orderLinePid", line, "orderedQty", quantity,
                    "submittedQty", initial, "receivedQty", initial, "remainingQty", new BigDecimal(quantity).subtract(new BigDecimal(initial)).toPlainString()));
        }
        var addresses = new ArrayList<Map<String, Object>>();
        for (String label : List.of("A", "B")) {
            String address = initialize("qo_procurement_address_common", Map.of("qo_pr_address_supplier_pid", supplierPid, "qo_pr_address_state", "active",
                    "qo_pr_address_address", "Synthetic warehouse " + label + " " + index, "qo_pr_address_manager_name", "Fixture manager " + label, "qo_pr_address_manager_phone", "+8613800000000"));
            addresses.add(raw("qo_procurement_address_common", address));
        }
        stage.put("orderPid", order); stage.put("orderRecord", raw("pr_purchase_order", order)); stage.put("lines", lines);
        stage.put("addresses", addresses); stage.put("expectedQuantities", quantities); stage.put("expectedState", state);
        stage.put("initialOrderFactsSynthetic", true); stage.put("initialReceivedSynthetic", state.equals("closed"));
        stage.put("receiptWriterVerified", false);
    }

    private Map<String, Object> tableMap(List<String> entities) {
        var result = new LinkedHashMap<String, Object>(); var jdbc = new JdbcTemplate(postgres);
        for (String entity : entities) {
            String model = physicalModel(entity); String table = actualMetadata.getTableName(model);
            assertTrue(table.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")); var definition = actualMetadata.getModelDefinitionFromDb(model).orElseThrow();
            var row = jdbc.queryForMap("SELECT id,pid,code,tenant_id,plugin_pid,status FROM ab_meta_model WHERE tenant_id=? AND code=? AND is_current=TRUE AND deleted_flag=FALSE", tenant, model);
            assertEquals(tenant, row.get("tenant_id")); assertEquals(definition.getId(), row.get("id")); assertEquals(model, row.get("code"));
            assertEquals("published", row.get("status")); assertTrue(row.get("plugin_pid") instanceof String && !row.get("plugin_pid").toString().isBlank());
            assertEquals(table, definition.getTableName()); assertEquals(table, jdbc.queryForObject("SELECT to_regclass(?)::text", String.class, table));
            result.put(entity, Map.of("modelCode", model, "physicalTable", table, "actualTenantModel", row));
        }
        return result;
    }

    private String physicalModel(String entity) {
        return switch (entity) {
            case "externalReferences" -> "customer_pcba_int_external_ref";
            case "category" -> "pr_spend_category";
            case "connection" -> "customer_pcba_int_kingdee_connection";
            default -> domainModel(entity);
        };
    }

    private List<Map<String, Object>> rows(String model) {
        String table = actualMetadata.getTableName(model); assertTrue(table.matches("[A-Za-z_][A-Za-z0-9_]{0,62}"));
        return new JdbcTemplate(postgres).query(
                "SELECT row_to_json(t)::text FROM \"" + table + "\" t WHERE t.tenant_id=? ORDER BY t.pid",
                (result, index) -> canonicalSnapshotRow(json, result.getString(1)), tenant);
    }

    static Map<String, Object> canonicalSnapshotRow(com.fasterxml.jackson.databind.ObjectMapper mapper, String raw) {
        if (raw == null) throw new IllegalArgumentException("Snapshot row must be canonical PostgreSQL JSON");
        try {
            var tree = mapper.readTree(raw);
            if (tree == null || !tree.isObject())
                throw new IllegalArgumentException("Snapshot row must be a JSON object");
            return mapper.readerFor(new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() { })
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.USE_LONG_FOR_INTS)
                    .readValue(raw);
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("Invalid canonical PostgreSQL snapshot row", error);
        }
    }

    private Map<String, Object> raw(String model, String pid) {
        var selected = rows(model).stream().filter(row -> pid.equals(row.get("pid"))).toList(); assertEquals(1, selected.size()); return selected.getFirst();
    }

    @Override protected Map<String, Object> snapshot() {
        var result = new LinkedHashMap<String, Object>(); for (String entity : DOMAINS) result.put(entity, rows(physicalModel(entity))); return result;
    }

    private Map<String, Object> auxiliarySnapshot() {
        var result = new LinkedHashMap<String, Object>(); for (String entity : AUXILIARY) result.put(entity, rows(physicalModel(entity))); return result;
    }

    private static String password() {
        String password = required("PROCUREMENT_SUPPLIER_UI_FIXTURE_PASSWORD");
        assertTrue(password.length() >= 12 && password.length() <= 128 && password.chars().allMatch(c -> c >= 33 && c <= 126)); return password;
    }
}
