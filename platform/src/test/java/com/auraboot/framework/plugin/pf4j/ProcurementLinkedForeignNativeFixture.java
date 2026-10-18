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

/** Composed opt-in fixture. No inherited business @Test methods are executable here. */
final class ProcurementLinkedForeignNativeFixture extends ProcurementPublicSourceNativeFixture {
    static final List<String> SUPPLIER_PERMISSIONS = List.of("meta.command.execute", "qo.srm.use");
    static final List<String> INITIALIZER_PERMISSIONS = initializerPermissions();
    static final List<String> SOURCE_FILES = ProcurementLinkedForeignNativeAdmission.SOURCES;
    static final String MAIN_BASE = ProcurementLinkedForeignNativeAdmission.MAIN_BASE;
    static final List<String> DOMAINS = List.of("task", "source", "quote", "quote_line", "history", "submission", "submission_line", "allocation", "execution", "order", "order_line", "externalReferences");
    static final List<String> AUXILIARY = List.of("supplier", "contact", "membership", "address", "shipment", "shipment_line", "product", "category", "category_policy", "category_supplier", "connection");
    @Autowired AuraPluginManager plugins;
    @Autowired CommandExecutor actualCommands;
    @Autowired MetaModelService actualMetadata;
    @Autowired FieldEncryptionService actualEncryption;
    @Autowired org.springframework.core.env.Environment nativeEnvironment;
    private JsonNode input;
    private JsonNode binding;
    private String makerJwt;
    private String supplierJwt;
    private String supplierPid;
    private String contactPid;
    private String materialPid;
    private String categoryPid;
    private ProcurementLinkedForeignNativeFixture reuseTenant;
    private TestInfo testInfo;
    private boolean closedFixture;
    @Autowired com.auraboot.framework.auth.wechat.WechatMiniProperties miniProperties;
    private String originalMiniAppId,originalMiniSecret,originalMiniBase;
    private String normalizedPhone;
    private String foreignOnlyPhone;
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
        permissions.addAll(ORDER_PERMISSIONS);
        return List.copyOf(new java.util.LinkedHashSet<>(permissions));
    }

    static void requireSelectedInput(JsonNode value) {
        ProcurementLinkedForeignNativeAdmission.mainBase(value);
        assertTrue(value.path("fixtureEnabled").isBoolean() && value.path("fixtureEnabled").booleanValue());
    }

    static String requestKey() { return "linked-init-" + UUID.randomUUID(); }

    /** Override the shared setup: never inherit its old private production-identity seeds. */
    @Override
    void prepare(TestInfo info) throws Exception {
        testInfo=info;
        Path selectedInput = Path.of(required("PROCUREMENT_LINKED_NATIVE_INPUT")).toRealPath();
        Path evidenceRoot = Path.of(required("PROCUREMENT_PUBLIC_SOURCE_EVIDENCE_DIR")).toRealPath();
        assertTrue(selectedInput.startsWith(evidenceRoot));
        input = json.readTree(selectedInput.toFile());
        requireSelectedInput(input);
        if(input.path("schemaVersion").intValue()==2) {
            assertEquals("nativePhoneBindingIgnoresActualForeignTenantDirectory",info.getTestMethod().orElseThrow().getName());
            assertEquals("true",required("PROCUREMENT_FOREIGN_DIRECTORY_FIXTURE"));
            assertFalse("true".equals(System.getenv("PROCUREMENT_BUYER_ORDER_FIXTURE")));
        }
        assertEquals(required("PROCUREMENT_LINKED_NATIVE_INPUT_SHA256"), sha(Path.of(required("PROCUREMENT_LINKED_NATIVE_INPUT"))));
        super.prepareEvidence(info);
        binding = json.readTree(Path.of(required("PROCUREMENT_PUBLIC_SOURCE_READINESS_INPUT")).toFile());
        assertEquals(required("PROCUREMENT_PUBLIC_SOURCE_READINESS_SHA256"), sha(Path.of(required("PROCUREMENT_PUBLIC_SOURCE_READINESS_INPUT"))));
        assertEquals(binding.path("sourceSet"), input.path("sourceSet"));
        Path app = Path.of(input.path("applicationCoreRoot").asText()).toRealPath();
        assertEquals(input.path("applicationCoreCommit").asText(), gitRead(app, "rev-parse", "HEAD").strip());
        assertEquals("", gitRead(app, "status", "--porcelain").strip());
        assertEquals("", gitRead(app, "diff", ProcurementLinkedForeignNativeAdmission.mainBase(input), "HEAD", "--", "platform/src/main").strip());
        assertEquals(app.resolve("platform/build/classes/java/test").toRealPath(),
                Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());
        assertTrue(input.path("nativeSourceSha256").isObject());
        assertEquals(Set.copyOf(ProcurementLinkedForeignNativeAdmission.sources(input)), json.convertValue(input.path("nativeSourceSha256"), Map.class).keySet());
        for (var file : input.path("nativeSourceSha256").properties())
            assertEquals(file.getValue().asText(), sha(app.resolve(file.getKey())));
        assertTrue(System.getenv().containsKey("PROCUREMENT_HOST_FIELD_ENCRYPTION_KEY"));
        ProcurementLinkedForeignNativeAdmission.validate(input, evidenceRoot, app, actualEncryption,
                System.getenv("PROCUREMENT_HOST_FIELD_ENCRYPTION_KEY"), plugins, json,nativeEnvironment.getActiveProfiles());
        assertEquals("13", required("TEST_REDIS_DATABASE"));
        try (var sql = postgres.getConnection()) { assertEquals("enterprise_22", sql.getCatalog()); }
        assertTrue(extensions.getCommandHandler(SYNC).isPresent());
        normalizedPhone = required("PROCUREMENT_LINKED_LOCAL_PHONE");
        foreignOnlyPhone = required("PROCUREMENT_LINKED_FOREIGN_PHONE");
        assertTrue(normalizedPhone.matches("\\+86[0-9]{11}"));
        assertTrue(foreignOnlyPhone.matches("\\+86[0-9]{11}") && !normalizedPhone.equals(foreignOnlyPhone));
        provider = "lfi_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        var bootstrap = reuseTenant==null?users.signUp(provider + "-bootstrap@example.invalid", password(), "Linked bootstrap", provider):users.findByPid(reuseTenant.actor);
        userId = bootstrap.getId(); actor = bootstrap.getPid();
        var organization = new Tenant();
        organization.setPid(com.auraboot.framework.common.util.UniqueIdGenerator.generate());
        organization.setName(provider); organization.setDisplayName(provider); organization.setStatus("active");
        tenant = reuseTenant==null?tenants.createTenant(organization).getId():reuseTenant.tenant;
        assertNotEquals(Long.valueOf(required("TEST_TENANT_ID")), tenant);
        var bootstrapMember = reuseTenant==null?members.addMember(userId, tenant, "active"):members.findByTenantIdAndUserId(tenant,userId); memberId = bootstrapMember.getId(); context();
        if(reuseTenant==null)assertTrue(tenantBootstrap.bootstrapTenant(tenant, userId).isSuccess());
        retainedBootstrapPrincipal = Map.of("userPid", actor, "memberId", memberId.toString(),
                "purpose", "Formal bootstrap/import only", "usedForTestedPublicRequest", false);
        var directories = json.readTree(required("PROCUREMENT_SOURCE_IMPORT_DIRECTORIES"));
        assertTrue(directories.isArray() && directories.size() == 23);
        var paths = new ArrayList<Path>();
        for (JsonNode directory : directories) paths.add(Path.of(directory.asText()).toRealPath());
        stage = "formal-original-import";
        for (Path path : paths) {
            formalImport(path);
            if (Files.isRegularFile(path.resolve("config/commands/procurement.json"))) procurementDirectory=path.toString();
        }
        imports.verifyImportReferenceIntegrity();
        stage = "test-owned-initialization-appendix";
        var appendix = ProcurementLinkedForeignInitialization.prepare(paths, caseEvidence.resolve("supplier-ui-appendix"), json);
        writeArtifact("supplier-ui-initialization-appendix.json", appendix);
        @SuppressWarnings("unchecked") var owners = (List<Map<String, Object>>) appendix.get("ownerDirectories");
        for (var owner : owners) {
            formalImport(Path.of(owner.get("testDirectory").toString()));
            if (owner.get("sourceDirectory").toString().equals(procurementDirectory))
                procurementDirectory=owner.get("testDirectory").toString();
        }
        imports.verifyImportReferenceIntegrity();
        validateImportedInitialization(appendix);
        var maker = users.signUp(provider + "-maker@example.invalid", password(), "Supplier UI initializer", provider + "_maker");
        var makerMember = members.addMember(maker.getId(), tenant, "active");
        makerJwt = actorToken(maker.getId(), maker.getPid(), makerMember.getId(), maker.getEmail(), INITIALIZER_PERMISSIONS, password());
        initializerIdentity = identity(maker.getId(), makerMember.getPid(), retainedActors.getLast());
        mvc = MockMvcBuilders.webAppContextSetup(application).addFilters(springSecurityFilterChain).build();
        stage = "native-initial-facts";
        startControlledErp();
        materialPid = initialize("prod_product", Map.of("prod_code", provider, "prod_name", "Synthetic supplier UI material", "prod_type", "raw_material", "prod_unit", "pcs", "prod_status", "active"));
        connection = initialize("customer_pcba_int_kingdee_connection", Map.of("customer_pcba_kd_profile_name", provider,
                "customer_pcba_kd_status", "active", "customer_pcba_kd_acct_id", provider,
                "customer_pcba_kd_base_url", "http://127.0.0.1:" + server.getAddress().getPort(), "customer_pcba_kd_app_id", provider,
                "customer_pcba_kd_username", provider, "customer_pcba_kd_app_secret_ref", "cloud-config:" + provider,
                "customer_pcba_kd_auth_type", "app-secret"));
        initialize("customer_pcba_int_external_ref", materialReference(connection, provider, materialPid));
        supplierPid = initialize("pr_supplier_common", Map.of("pr_supplier_code", provider, "pr_supplier_name", "Synthetic UI Supplier", "pr_supplier_status", "enabled", "qo_pr_supplier_account_id", provider));
        categoryPid = initialize("pr_spend_category", Map.of("pr_spc_code", provider, "pr_spc_name", "Supplier UI category"));
        contactPid = initialize("pr_supplier_pcba_contact", Map.of("pr_spc_supplier_id", supplierPid, "pr_spc_contact_name", "Synthetic UI Contact", "qo_pr_contact_state", "active", "qo_pr_contact_normalized_phone", normalizedPhone));
        var supplier = users.signUp(provider + "-supplier@example.invalid", password(), "Synthetic UI Contact", provider + "_supplier");
        var supplierMember = members.addMember(supplier.getId(), tenant, "active");
        initialize("qo_procurement_membership_common", Map.of("qo_pr_membership_key", provider, "qo_pr_membership_user_pid", supplier.getPid(), "qo_pr_membership_supplier_pid", supplierPid, "qo_pr_membership_contact_pid", contactPid, "qo_pr_membership_state", "active"));
        supplierJwt = actorToken(supplier.getId(), supplier.getPid(), supplierMember.getId(), supplier.getEmail(), SUPPLIER_PERMISSIONS, password());
        supplierIdentity = identity(supplier.getId(), supplierMember.getPid(), retainedActors.getLast());
        action(makerJwt, "qo_procurement_category_policy_common:create", null, Map.of("categoryPid", categoryPid,
                "connectionPid", connection, "supplierPids", List.of(supplierPid), "defaultSupplierPids", List.of(supplierPid)));
        priorCaseFacts = null; // Independent raw snapshots are emitted by this producer, not the shared legacy reader.
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
        for (var model : ProcurementLinkedForeignInitialization.FIELDS.entrySet()) {
            var command = commands.findByCode(ProcurementLinkedForeignInitialization.command(model.getKey()));
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
        for (var model : ProcurementLinkedForeignInitialization.FIELDS.entrySet()) {
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
        ProcurementLinkedForeignInitialization.validatePayload(model, facts);
        var request = new CommandExecuteRequest(); request.setPayload(new LinkedHashMap<>(facts)); request.setClientRequestId(requestKey());
        // Authenticate the exact test-owned maker; no runWithAuthorizedCommandCode/permit impersonation.
        var identity = initializerIdentity; var user = users.findByPid(identity.get("userPid").toString());
        var auth = new AuthenticationRequest(); auth.setEmail(user.getEmail()); auth.setPassword(password());
        var loggedIn = authentication.authenticate(auth); assertEquals(tenant, loggedIn.getTenantId()); assertEquals(user.getId(), loggedIn.getUserId());
        var saved = MetaContext.snapshot();
        try {
            MetaContext.setContext(tenant, user.getId(), user.getPid(), provider); MetaContext.setMemberId(Long.valueOf(identity.get("memberId").toString()));
            var result = actualCommands.execute(ProcurementLinkedForeignInitialization.command(model), request);
            assertEquals("completed", result.getPhaseReached()); String pid = result.getData().get("recordPid").toString();
            initializationActions.add(Map.of("command", ProcurementLinkedForeignInitialization.command(model), "model", model,
                    "recordPid", pid, "authenticationVerified", true, "syntheticInitialFactsOnly", true));
            return pid;
        } finally { MetaContext.clear(); MetaContext.restore(saved); }
    }

    private Map<String, Object> identity(Long id, String memberPid, Map<String, Object> original) {
        assertNotNull(memberPid);assertFalse(memberPid.isBlank());
        var result = new LinkedHashMap<>(original); result.put("userId", id.toString()); result.put("tenantId", tenant.toString());result.put("memberPid",memberPid);
        result.put("credentialEnvironment", "PROCUREMENT_SUPPLIER_FIXTURE_PASSWORD"); result.put("dataScopeAuthority", "synthetic tenant only");
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
            int status = response.getResponse().getStatus();
            JsonNode body;
            try { body = json.readTree(response.getResponse().getContentAsByteArray()); }
            catch (java.io.IOException malformed) {
                writeArtifact("linked-public-action-" + (++publicRequestSequence) + ".json",
                        safePublicResponse(status, code, target, null));
                throw malformed;
            }
            writeArtifact("linked-public-action-" + (++publicRequestSequence) + ".json",
                    safePublicResponse(status, code, target, body));
            assertEquals(200, status);
            requireCompletedCommandResponse(body);
            publicActions.add(Map.of("command", code, "target", target == null ? "create" : target, "payload", payload, "httpStatus", 200));
            return body.path("data").path("data");
        } finally { context(); }
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
        String password = required("PROCUREMENT_SUPPLIER_FIXTURE_PASSWORD");
        assertTrue(password.length() >= 12 && password.length() <= 128 && password.chars().allMatch(c -> c >= 33 && c <= 126)); return password;
    }

    private void startControlledErp() throws Exception {
        server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);response=row("11",1000,"C");
        server.createContext("/K3Cloud/Kingdee.BOS.WebApi.ServicesStub.AuthService.LoginByAppSecret.common.kdsvc",http->{
            var body=json.readTree(http.getRequestBody().readAllBytes());
            assertEquals("local-test-secret",body.path("appSecret").asText());loginCount.incrementAndGet();
            http.getResponseHeaders().add("Set-Cookie","public-source-local=authenticated; Path=/K3Cloud");
            byte[] bytes="{\"LoginResultType\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8);http.sendResponseHeaders(200,bytes.length);http.getResponseBody().write(bytes);http.close();
        });
        server.createContext("/K3Cloud/Kingdee.BOS.WebApi.ServicesStub.DynamicFormService.ExecuteBillQuery.common.kdsvc",http->{
            var request=json.readTree(http.getRequestBody().readAllBytes());String content=response;
            if("FixturePurchase".equals(request.path("data").path("FormId").asText())) {
                assertNotNull(savedOrder);assertEquals(Set.of("data"),json.convertValue(request,Map.class).keySet());var query=request.path("data");
                assertEquals(2,query.path("Limit").asInt(-1));
                if("OrderId,BillNo,Marker".equals(query.path("FieldKeys").asText())) {
                    assertEquals(0,query.path("StartRow").asInt(-1));orderLookupCount.incrementAndGet();assertEquals("Marker = '"+savedOrder.path("Marker").asText()+"'",query.path("FilterString").asText());
                    content=json.writeValueAsString(List.of(List.of(provider+"-erp-order",provider+"-erp-bill",savedOrder.path("Marker").asText())));
                } else {
                    orderReadCount.incrementAndGet();assertEquals(String.join(",",READ_COLUMNS.stream().map(column->"Read_"+column).toList()),query.path("FieldKeys").asText());
                    String filter=query.path("FilterString").asText();assertTrue(filter.equals("Read_erp_id = '"+provider+"-erp-order'") || filter.equals(""));
                    assertEquals("Read_erp_id ASC,Read_erp_entry_id ASC",query.path("OrderString").asText());assertEquals(0,query.path("TopRowCount").asInt(-1));
                    var rows=orderReadRows();int start=query.path("StartRow").asInt(-1);
                    assertTrue(start>=0 && start<=rows.size() && start%2==0,"Controlled read page must be canonical");
                    content=json.writeValueAsString(rows.subList(start,Math.min(start+2,rows.size())));
                }
            } else queryCount.incrementAndGet();
            byte[] bytes=content.getBytes(java.nio.charset.StandardCharsets.UTF_8);http.sendResponseHeaders(200,bytes.length);http.getResponseBody().write(bytes);http.close();
        });
        server.createContext("/K3Cloud/Kingdee.BOS.WebApi.ServicesStub.DynamicFormService.Save.common.kdsvc",http->{
            var request=json.readTree(http.getRequestBody().readAllBytes());assertEquals("FixturePurchase",request.path("formid").asText());
            orderSaveCount.incrementAndGet();savedOrder=request.path("data").path("Model");assertTrue(savedOrder.isObject());
            String content=json.writeValueAsString(Map.of("Result",Map.of("ResponseStatus",Map.of("IsSuccess",true,"SuccessEntitys",List.of(Map.of("Id",provider+"-erp-order","Number",provider+"-erp-bill"))))));
            byte[] bytes=content.getBytes(java.nio.charset.StandardCharsets.UTF_8);http.sendResponseHeaders(200,bytes.length);http.getResponseBody().write(bytes);http.close();
        });server.start();
        var secret=new com.auraboot.framework.cloudconfig.dto.CloudConfigSaveRequest();secret.setConfigLevel("tenant");secret.setServiceType("integration");secret.setProviderCode(provider);secret.setEnabled(true);secret.setPriority(1);
        secret.setConfig("{\"appSecret\":\"local-test-secret\"}");configs.saveConfig(secret);
    }

    @Override protected List<List<Object>> orderReadRows() {
        var line=savedOrder.path("Lines").get(0);
        return List.of(List.of(provider+"-erp-order",provider+"-erp-bill","local-entry-1",savedOrder.path("Supplier").path("Id").asText(),
                line.path("Material").path("Id").asText(),savedOrder.path("Date").asText(),"LOCAL_APPROVED",readClosedStatus,
                readQuantity,"0",readReceivedQuantity,"LOCAL-MATERIAL","Local material","local","PCS",line.path("DeliveryDate").asText(),
                line.path("Source").path("Head").asText(),line.path("Source").path("Entry").asText()));
    }
    static Map<String, Object> materialReference(String connectionPid, String accountId, String productPid) {
        assertTrue(connectionPid != null && !connectionPid.isBlank());
        assertTrue(accountId != null && !accountId.isBlank());
        assertTrue(productPid != null && !productPid.isBlank());
        return Map.of("customer_pcba_ref_external_system", "KINGDEE",
                "customer_pcba_ref_instance_key", connectionPid, "customer_pcba_ref_account_id", accountId,
                "customer_pcba_ref_external_form_id", "BD_MATERIAL", "customer_pcba_ref_external_id", "900",
                "customer_pcba_ref_local_model_code", "prod_product", "customer_pcba_ref_local_pid", productPid);
    }

    /** Fixed diagnostic whitelist only; raw public errors, headers and credentials are never evidence. */
    static Map<String, Object> safePublicResponse(int status, String command, String targetPid, JsonNode body) {
        var diagnostic = new LinkedHashMap<String, Object>();
        diagnostic.put("httpStatus", status); diagnostic.put("command", command);
        diagnostic.put("responseObject", body != null && body.isObject());
        diagnostic.put("rawResponsePersisted", false);
        if (targetPid != null && targetPid.matches("[A-Za-z0-9_-]{1,64}")) diagnostic.put("targetRecordPid", targetPid);
        if (body == null || !body.isObject()) return diagnostic;
        for (String key : List.of("code", "contextCode")) {
            JsonNode value = key.equals("code") ? body.path("code") : body.path("context").path("code");
            if ((value.isTextual() || value.isIntegralNumber()) && value.asText().matches("[0-9]{1,8}"))
                diagnostic.put(key, value.asText());
        }
        JsonNode messageKey = body.path("context").path("messageKey");
        if (messageKey.isTextual() && messageKey.asText().matches("\\$i18n:qo_pr\\.error\\.[a-z0-9_]{1,80}"))
            diagnostic.put("messageKey", messageKey.asText());
        String phase = body.path("data").path("phaseReached").asText();
        if (Set.of("completed", "handler", "validation", "authorization", "target_scope", "assert",
                "auto_set", "field_map", "computed_fields").contains(phase)) diagnostic.put("phaseReached", phase);
        return diagnostic;
    }

    @Override protected Result call(String bearer, Map<String, Object> payload) throws Exception {
        Result result = super.call(bearer, payload);
        var safe = new LinkedHashMap<>(safePublicResponse(
                result.status(), SYNC, task, result.body()));
        safe.put("loginCount", loginCount.get()); safe.put("queryCount", queryCount.get());
        safe.put("credentialsOrRawBodyPersisted", false);
        writeArtifact("linked-source-response-" + publicRequestSequence + ".json", safe);
        return result;
    }

    private Map<String,Object> authenticatedActor(String suffix,List<String> codes,String credential) {
        context();String email=provider+suffix+"@example.invalid";
        var user=users.signUp(email,required(credential),"Linked retained fixture",provider+suffix);
        var member=members.addMember(user.getId(),tenant,"active");
        String bearer=actorToken(user.getId(),user.getPid(),member.getId(),email,codes,required(credential));
        var identity=new LinkedHashMap<String,Object>(retainedActors.getLast());identity.put("memberPid",member.getPid());
        identity.put("tenantId",tenant.toString());identity.put("credentialEnvironment",credential);
        identity.put("bearer",bearer);return identity;
    }
    static Map<String,Object> receiptActor(Map<String,Object> actor) {
        for(String field:List.of("email","userPid","memberPid","roleCode"))
            assertTrue(actor.get(field) instanceof String value && !value.isBlank(),"Exact authenticated receipt actor identity required");
        var safe=new LinkedHashMap<>(actor);safe.remove("bearer");safe.remove("memberId");return safe;
    }
    static List<String> buyerPermissions(boolean missing) {
        var codes=new ArrayList<>(List.of("qo.procurement.manage","meta.command.execute","page.page.read",
                "model.s2p_sourcing_event.read","model.qo_procurement_execution_common.read",
                "model.pr_supplier_common.read","model.pr_purchase_order.read","model.pr_purchase_order_line.read",
                "model.qo_procurement_shipment_common.read","model.qo_procurement_shipment_line_common.read",
                "model.pr_purchase_request.read","model.pr_spend_category.read"));
        if(missing)assertTrue(codes.remove("model.pr_supplier_common.read"));
        assertEquals(missing?11:12,codes.size());return List.copyOf(codes);
    }
    static void assertDecimal(String expected, Object actual) {
        assertNotNull(actual);
        assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(actual.toString())));
    }

    private long currentQuoteSourceRevision(String sourcePid, String quotePid) {
        var lines = rows(domainModel("quote_line")).stream().filter(row -> quotePid.equals(row.get("qo_pr_quote_line_quote_pid"))
                && sourcePid.equals(row.get("qo_pr_quote_line_source_pid"))).toList();
        assertEquals(1, lines.size());
        return matchingSourceRevision(raw("pr_purchase_request", sourcePid), lines.getFirst(), sourcePid, quotePid);
    }

    static long matchingSourceRevision(Map<String, Object> currentSource, Map<String, Object> frozenLine,
                                       String sourcePid, String quotePid) {
        assertEquals(sourcePid, currentSource.get("pid"));
        assertEquals("active", currentSource.get("qo_pr_source_state"));
        assertEquals(sourcePid, frozenLine.get("qo_pr_quote_line_source_pid"));
        assertEquals(quotePid, frozenLine.get("qo_pr_quote_line_quote_pid"));
        long current = new BigDecimal(currentSource.get("qo_pr_source_revision").toString()).longValueExact();
        long frozen = new BigDecimal(frozenLine.get("qo_pr_quote_line_source_revision").toString()).longValueExact();
        assertTrue(current >= 0); assertEquals(current, frozen, "Stale frozen quote demand must not be rebased by a fixture");
        return current;
    }

    private Map<String,Object> linkedStage(boolean closed) throws Exception {
        task=action(makerJwt,"qo_procurement_task_common:create",null,Map.of("title",provider,"connectionPid",connection)).path("recordPid").asText();
        configurePolicy();procurementDirectory=caseEvidence.resolve("configured-dsl").toString();
        assertEquals(200,call(makerJwt,selected("11")).status());
        var sourceRows=rows("pr_purchase_request").stream().filter(r->task.equals(r.get("qo_pr_source_task_pid"))).toList();assertEquals(1,sourceRows.size());
        String source=sourceRows.getFirst().get("pid").toString();long revision=new BigDecimal(sourceRows.getFirst().get("qo_pr_source_revision").toString()).longValueExact();
        action(makerJwt,"qo_procurement_task_common:classify_source",task,Map.of("sourcePid",source,"sourceRevision",revision,"categoryPid",categoryPid));
        action(makerJwt,"qo_procurement_task_common:dispatch",task,Map.of("supplierPids",List.of(supplierPid),"cutoffAt","2066-11-15T08:00:00Z"));
        var quotes=rows("s2p_bid").stream().filter(q->task.equals(q.get("s2p_bid_sourcing_event_id"))).toList();assertEquals(1,quotes.size());
        String quote=quotes.getFirst().get("pid").toString();
        long offeredRevision = currentQuoteSourceRevision(source, quote);
        var line=Map.<String,Object>of("sourcePid",source,"sourceRevision",offeredRevision,"unitPrice","1.250001","currency","CNY","unit","PCS",
                "taxRate","0.13","taxIncluded",true,"availableQty","600","deliveryDate","2066-12-01");
        action(supplierJwt,"qo_procurement_quote_common:save_draft",quote,Map.of("revision",0,"lines",List.of(line),"paymentTerms","Net 30"));
        var formal=action(supplierJwt,"qo_procurement_quote_common:submit",quote,Map.of("revision",1));
        String submission=formal.path("submissionPid").asText();assertFalse(submission.isBlank());
        long allocationRevision = currentQuoteSourceRevision(source, quote);
        assertEquals(offeredRevision, allocationRevision, "Formal offer must still match the current classified demand");
        action(makerJwt,"qo_procurement_task_common:allocate",task,Map.of("sourceDemands",List.of(Map.of("sourcePid",source,"sourceRevision",allocationRevision,"quantity","300.000001")),
                "allocations",List.of(Map.of("quotePid",quote,"sourcePid",source,"submissionPid",submission,"quantity","300.000001"))));
        var executions=rows(domainModel("execution")).stream().filter(e->task.equals(e.get("qo_pr_execution_task_pid"))).toList();assertEquals(1,executions.size());
        String execution=executions.getFirst().get("pid").toString();
        initialize("customer_pcba_int_external_ref",Map.of("customer_pcba_ref_external_system","KINGDEE","customer_pcba_ref_instance_key",connection,
                "customer_pcba_ref_account_id",provider,"customer_pcba_ref_external_form_id","FixtureSupplier","customer_pcba_ref_external_id","supplier-local",
                "customer_pcba_ref_local_model_code","pr_supplier_common","customer_pcba_ref_local_pid",supplierPid));
        configureOrderPolicy();
        // Retain the exact initializer appendix and write mapping when importing the read contract.
        procurementDirectory=caseEvidence.resolve("order-dsl").toString();configureOrderReadPolicy();
        assertEquals(200,orderCall(makerJwt,execution,Map.of()).status());
        assertEquals("succeeded",raw(domainModel("execution"),execution).get("qo_pr_execution_state"));
        readReceivedQuantity=closed?"250":"0";
        var published=orderSyncCall(makerJwt,SYNC_ORDERS,execution);assertEquals(200,published.status());
        assertEquals("published",published.body().path("data").path("data").path("status").asText());
        var orders=rows("pr_purchase_order").stream().filter(o->provider.equals(o.get("qo_pr_order_account_id")) && (provider+"-erp-order").equals(o.get("qo_pr_order_erp_id"))).toList();
        assertEquals(1,orders.size());String order=orders.getFirst().get("pid").toString();
        var lines=rows("pr_purchase_order_line").stream().filter(l->order.equals(l.get("pr_pol_order_id"))).toList();assertEquals(1,lines.size());
        var quantities=lines.getFirst();
        assertDecimal("300.000001", quantities.get("pr_pol_qty"));
        var addresses=new ArrayList<String>();for(String label:List.of("A","B"))addresses.add(initialize("qo_procurement_address_common",Map.of(
                "qo_pr_address_supplier_pid",supplierPid,"qo_pr_address_state","active","qo_pr_address_address","Synthetic warehouse "+label,
                "qo_pr_address_manager_name","Fixture manager "+label,"qo_pr_address_manager_phone","+8613800000000")));
        if(closed) {
            action(supplierJwt,"qo_procurement_order_common:submit_shipment",order,Map.of("requestKey",UUID.randomUUID().toString(),"addressPid",addresses.getFirst(),
                    "lines",List.of(Map.of("orderLinePid",quantities.get("pid"),"quantity","250"))));
            readClosedStatus="LOCAL_CLOSED";assertEquals(200,orderSyncCall(makerJwt,SYNC_CLOSURES,execution).status());
            quantities=raw("pr_purchase_order_line",quantities.get("pid").toString());
            assertDecimal("250", quantities.get("qo_pr_order_line_submitted_qty"));
        }
        var result=new LinkedHashMap<String,Object>();result.put("namespace",provider);result.put("taskPid",task);result.put("sourcePid",source);result.put("quotePid",quote);
        result.put("submissionPid",submission);result.put("executionPid",execution);result.put("accountId",provider);result.put("supplierPid",supplierPid);
        result.put("orderPid",order);result.put("orderLinePid",quantities.get("pid"));result.put("erpOrderId",provider+"-erp-order");result.put("erpBillNo",provider+"-erp-bill");
        result.put("orderedQty","300.000001");result.put("submittedQty",closed?"250":"0");result.put("receivedQty",closed?"250":"0");result.put("initialReceivedSynthetic",closed);result.put("receiptWriterVerified",false);
        result.put("orderState",raw("pr_purchase_order",order).get("qo_pr_order_state"));result.put("addressPids",addresses);result.put("supplierActor",receiptActor(supplierIdentity));
        result.put("orderLineMaterialFacts",Map.of("material_code",quantities.get("qo_pr_order_line_material_code"),"material_name",quantities.get("qo_pr_order_line_material_name"),
                "specification",quantities.get("qo_pr_order_line_specification"),"unit",quantities.get("qo_pr_order_line_unit")));
        result.put("orderLineMaterialFactsOrigin","Synthetic loopback source read persisted by native domain lifecycle; copied into formal order-line snapshot, not customer ERP006/007 evidence");return result;
    }

    void produceLinked() throws Exception {
        var buyer=authenticatedActor("-buyer",buyerPermissions(false),"PROCUREMENT_BUYER_FIXTURE_PASSWORD");
        var denied=authenticatedActor("-buyer-no-supplier",buyerPermissions(true),"PROCUREMENT_BUYER_FIXTURE_PASSWORD");
        var open=linkedStage(false);
        var second=application.getAutowireCapableBeanFactory().createBean(ProcurementLinkedForeignNativeFixture.class);
        second.reuseTenant=this;
        try {
            second.prepare(testInfo);var closed=second.linkedStage(true);
            for(String key:List.of("namespace","taskPid","sourcePid","executionPid","accountId","supplierPid","orderPid","orderLinePid","erpOrderId"))
                assertNotEquals(open.get(key),closed.get(key));
            // The exact independent closed-account observation must not touch the open account.
            assertEquals("open",raw("pr_purchase_order",open.get("orderPid").toString()).get("qo_pr_order_state"));
            var receipt=bindingReceipt();receipt.putAll(open);receipt.put("closedStage",closed);
            receipt.put("buyer",receiptActor(buyer));receipt.put("buyerMissingSupplierRead",receiptActor(denied));
            receipt.put("externalERPOutcomeSynthetic",true);receipt.put("nativeTrustedPermit",true);receipt.put("publicERPAdmissionVerified",false);
            receipt.put("receiptWriterVerified",false);receipt.put("retainedAfterTest",true);
            persistTriple("PROCUREMENT_BUYER_FIXTURE_RECEIPT",receipt);
        } finally {second.close();context();}
    }
    private Map<String,Object> bindingReceipt() throws Exception {
        var bound=new LinkedHashMap<String,Object>();bound.put("schemaVersion",input.path("schemaVersion").intValue());bound.put("runtime","procurement-web-stack-20261010");
        bound.put("database","enterprise_22");bound.put("tenantId",tenant.toString());bound.put("nativeHostBaseCommit",ProcurementLinkedForeignNativeAdmission.mainBase(input));
        bound.put("productJarSha256",input.path("productJarSha256").asText());
        String testFile="platform/src/test/java/com/auraboot/framework/plugin/pf4j/ProcurementPluginHostRealDbIT.java";
        bound.put("nativeHostTestSha256",input.path("nativeSourceSha256").path(testFile).asText());
        bound.put("sourceSet",ProcurementOrderUiFixtureProducerIT.canonicalSources(input.path("sourceSet")));
        bound.put("actualApplicationCoreCommit",input.path("applicationCoreCommit").asText());
        bound.put("actualApplicationCoreRoot",input.path("applicationCoreRoot").asText());
        bound.put("nativeClassProof",input.path("classSha256"));bound.put("encryptionConfigProofSha256",input.path("encryptionConfigProofSha256").asText());
        try(var sql=postgres.getConnection()) {
            var uri=java.net.URI.create(sql.getMetaData().getURL().substring("jdbc:".length()));
            assertEquals("postgresql",uri.getScheme());assertTrue(Set.of("localhost","127.0.0.1").contains(uri.getHost()));
            assertEquals(5432,uri.getPort());assertEquals("/enterprise_22",uri.getPath());assertEquals("enterprise_22",sql.getCatalog());
            bound.put("databaseConnection",Map.of("host",uri.getHost(),"port",uri.getPort(),"database",sql.getCatalog()));
        }
        return bound;
    }
    private void persistTriple(String receiptEnvironment,Map<String,Object> receipt) throws Exception {
        Path destination=Path.of(required(receiptEnvironment)).toAbsolutePath();assertFalse(Files.exists(destination));
        Path attempt=Path.of(required("PROCUREMENT_PUBLIC_SOURCE_EVIDENCE_DIR")).toRealPath();assertEquals(attempt,destination.getParent().toRealPath());
        Path sourceBinding=attempt.resolve("source-binding.json");
        var bound=bindingReceipt();Files.writeString(sourceBinding,json.writerWithDefaultPrettyPrinter().writeValueAsString(bound),StandardOpenOption.CREATE_NEW);
        receipt.put("sourceBindingSha256",sha(sourceBinding));
        var preparation=Map.of("baseCommit",ProcurementLinkedForeignNativeAdmission.mainBase(input),"testPath",Path.of(input.path("applicationCoreRoot").asText()).resolve(
                "platform/src/test/java/com/auraboot/framework/plugin/pf4j/ProcurementPluginHostRealDbIT.java").toString(),
                "sealedTestSha256",bound.get("nativeHostTestSha256"),"method",testInfo.getTestMethod().orElseThrow().getName());
        Files.writeString(attempt.resolve("preparation.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(preparation),StandardOpenOption.CREATE_NEW);
        Files.writeString(destination,json.writerWithDefaultPrettyPrinter().writeValueAsString(receipt),StandardOpenOption.CREATE_NEW);
    }
    void produceForeign() throws Exception {
        var second=application.getAutowireCapableBeanFactory().createBean(ProcurementLinkedForeignNativeFixture.class);
        try {
            second.prepare(testInfo);assertNotEquals(tenant,second.tenant);
            String foreignOnly=second.initialize("pr_supplier_pcba_contact",Map.of("pr_spc_supplier_id",second.supplierPid,
                    "pr_spc_contact_name","Foreign exclusive phone","qo_pr_contact_state","active","qo_pr_contact_normalized_phone",foreignOnlyPhone));
            preparePhoneProvider();
            MetaContext.clear();
            var bound=mvc.perform(post("/api/auth/wechat/mini/bind").header("Authorization","Bearer "+supplierJwt).contentType("application/json")
                    .content(json.writeValueAsBytes(Map.of("code",provider+"-login")))).andReturn();
            assertEquals(200,bound.getResponse().getStatus());assertEquals("0",json.readTree(bound.getResponse().getContentAsByteArray()).path("code").asText());context();
            var local=action(supplierJwt,"qo_procurement_membership_common:bind_verified_phone",null,
                    Map.of("loginCode",provider+"-login","phoneCode",provider+"-local"));
            assertEquals(supplierPid,local.path("supplierPid").asText());assertEquals(contactPid,local.path("contactPid").asText());
            var before=foreignSnapshots(second);
            MetaContext.clear();
            var request=Map.of("payload",Map.of("loginCode",provider+"-login","phoneCode",provider+"-foreign"),"clientRequestId",UUID.randomUUID().toString());
            var rejected=mvc.perform(post("/api/meta/commands/execute/qo_procurement_membership_common:bind_verified_phone")
                    .header("Authorization","Bearer "+supplierJwt).contentType("application/json").content(json.writeValueAsBytes(request))).andReturn();
            assertEquals(400,rejected.getResponse().getStatus());
            var error=json.readTree(rejected.getResponse().getContentAsByteArray());
            requireForeignRejection(error);context();
            assertEquals(before,foreignSnapshots(second));
            var receipt=bindingReceipt();receipt.put("kind","foreign-supplier-directory");receipt.put("tenantA",tenant.toString());receipt.put("tenantB",second.tenant.toString());
            receipt.put("foreignSupplierPid",second.supplierPid);receipt.put("foreignContactPid",second.contactPid);receipt.put("foreignOnlyContactPid",foreignOnly);
            receipt.put("foreignOnlyNormalizedPhone",foreignOnlyPhone);receipt.put("localSupplierPid",supplierPid);receipt.put("localContactPid",contactPid);receipt.put("localNormalizedPhone",normalizedPhone);
            receipt.put("localNativeBindingVerified",true);receipt.put("foreignOnlyNativeRejectionKey","qo_pr.error.phone_not_registered");receipt.put("retainedAfterTest",true);
            receipt.put("nativeTrustedPermit",true);receipt.put("publicHTTPVerified",false);receipt.put("publicMockMvcVerified",true);receipt.put("externalWechatSynthetic",true);
            receipt.put("snapshots",before);receipt.put("producedAt",Instant.now().toString());persistTriple("PROCUREMENT_FOREIGN_DIRECTORY_RECEIPT",receipt);
        } finally {second.close();context();}
    }
    static void requireForeignRejection(JsonNode response) {
        assertEquals("35000",response.path("code").asText());assertTrue(response.path("data").isNull() || response.path("data").isMissingNode());
        assertEquals("$i18n:qo_pr.error.phone_not_registered",response.path("context").path("messageKey").asText());
    }
    private static final List<String> FOREIGN_MODELS=List.of("pr_supplier_common","pr_supplier_pcba_contact","s2p_sourcing_event","pr_purchase_request","s2p_bid",
            "pr_purchase_order","pr_purchase_order_line","qo_procurement_membership_common","qo_procurement_quote_line_common","qo_procurement_history_common",
            "qo_procurement_allocation_common","qo_procurement_execution_common","qo_procurement_address_common","qo_procurement_shipment_common",
            "qo_procurement_shipment_line_common","qo_procurement_receipt_common","qo_procurement_reminder_common","qo_procurement_submission_common",
            "qo_procurement_submission_line_common","qo_procurement_category_policy_common","qo_procurement_category_supplier_common",
            "qo_procurement_inquiry_request_common");
    static List<String> foreignSnapshotModels() { return FOREIGN_MODELS; }
    static Map<String,Object> foreignSnapshotMetadata(Map<String,Object> actualModel,String table) {
        assertTrue(actualModel.get("pid") instanceof String && !actualModel.get("pid").toString().isBlank(),"Actual published model PID is required");
        return Map.of("pid",actualModel.get("pid"),"tableName",table);
    }
    private Map<String,Object> foreignSnapshots(ProcurementLinkedForeignNativeFixture foreign) {
        var snapshot=new LinkedHashMap<String,Object>();
        for(String model:FOREIGN_MODELS) {
            context();String table=actualMetadata.getTableName(model);assertTrue(table.matches("[A-Za-z_][A-Za-z0-9_]{0,62}"));
            var tenants=new LinkedHashMap<String,Object>();
            for(var current:List.of(this,foreign)) {
                current.context();var definition=current.actualMetadata.getModelDefinitionFromDb(model).orElseThrow();assertEquals(table,definition.getTableName());
                var actualModel=new JdbcTemplate(postgres).queryForMap("SELECT id,pid,tenant_id,status FROM ab_meta_model WHERE tenant_id=? AND code=? AND is_current=TRUE AND deleted_flag=FALSE",current.tenant,model);
                assertEquals(current.tenant,actualModel.get("tenant_id"));assertEquals(definition.getId(),actualModel.get("id"));assertEquals("published",actualModel.get("status"));
                assertEquals(table,new JdbcTemplate(postgres).queryForObject("SELECT to_regclass(?)::text",String.class,table));
                tenants.put(current.tenant.toString(),Map.of("metadata",List.of(foreignSnapshotMetadata(actualModel,table)),"rows",current.rows(model)));
            }
            snapshot.put(model,Map.of("physicalTable",table,"tableExists",true,"tenants",tenants));
        }
        context();return snapshot;
    }
    private void preparePhoneProvider() {
        originalMiniAppId=miniProperties.getAppId();originalMiniSecret=miniProperties.getAppSecret();originalMiniBase=miniProperties.getApiBaseUrl();
        miniProperties.setAppId("linked-native-app");miniProperties.setAppSecret("linked-native-secret");miniProperties.setApiBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());
        server.createContext("/sns/jscode2session",http->{byte[] data=("{\"openid\":\""+provider+"-openid\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            http.sendResponseHeaders(200,data.length);http.getResponseBody().write(data);http.close();});
        server.createContext("/cgi-bin/stable_token",http->{byte[] data="{\"errcode\":0,\"access_token\":\"linked-native-token\",\"expires_in\":7200}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            http.sendResponseHeaders(200,data.length);http.getResponseBody().write(data);http.close();});
        server.createContext("/wxa/business/getuserphonenumber",http->{var request=json.readTree(http.getRequestBody().readAllBytes());assertEquals(provider+"-openid",request.path("openid").asText());
            String code=request.path("code").asText();assertTrue(Set.of(provider+"-local",provider+"-foreign").contains(code));
            String phone=code.endsWith("-foreign")?foreignOnlyPhone:normalizedPhone;
            byte[] data=json.writeValueAsBytes(Map.of("errcode",0,"phone_info",Map.of("countryCode","86","purePhoneNumber",phone.substring(3),
                    "watermark",Map.of("appid","linked-native-app","timestamp",Instant.now().getEpochSecond()))));
            http.sendResponseHeaders(200,data.length);http.getResponseBody().write(data);http.close();});
    }
    @Override void close() throws Exception {
        if(closedFixture)return;closedFixture=true;
        try {super.close();} finally {
            if(originalMiniBase!=null){miniProperties.setAppId(originalMiniAppId);miniProperties.setAppSecret(originalMiniSecret);miniProperties.setApiBaseUrl(originalMiniBase);}
        }
    }
    @Override protected <T> T fixture(String code, java.util.function.Supplier<T> action) {
        throw new IllegalStateException("Private production command permits are forbidden for linked fixtures");
    }

}
