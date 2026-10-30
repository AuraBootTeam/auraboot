package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.application.security.MachineDataAccessContext;
import com.auraboot.framework.application.security.ExternalMachineAuthenticator.MachinePrincipal;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.*;
import com.auraboot.framework.openplatform.mapper.OpenPlatformAuthMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import java.time.Instant;
import java.util.*;
import static com.auraboot.framework.openplatform.service.OpenApiResourceContract.*;
import static org.assertj.core.api.Assertions.*;

/** Real production authentication, publication and dynamic data services against PostgreSQL. */
@org.springframework.context.annotation.Import(OpenApiMachineDataIntegrationTest.EventFaultConfiguration.class)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class OpenApiMachineDataIntegrationTest extends BaseIntegrationTest {
    @Autowired private MetaModelService models;
    @Autowired private MetaFieldService metadataFields;
    @Autowired private SchemaManagementService schemas;
    @Autowired private DynamicDataService data;
    @Autowired private OpenApiPublicationService publications;
    @Autowired private OpenApiResourceAuthorizer authorizer;
    @Autowired private OpenPlatformManagementService management;
    @Autowired private OpenPlatformTokenService tokens;
    @Autowired private OpenPlatformSecretCodec secrets;
    @Autowired private OpenPlatformAuthMapper auth;
    @Autowired private OpenApiResourceService resources;
    @Autowired private org.springframework.test.web.servlet.MockMvc http;
    @Autowired private com.fasterxml.jackson.databind.ObjectMapper json;
    @Autowired private com.auraboot.framework.meta.mapper.MetaModelMapper modelMapper;
    @Autowired private CommandService commands;
    @Autowired private com.auraboot.framework.plugin.service.impl.PluginResourceImporter commandImporter;
    @Autowired private CommandExecutor executor;
    @Autowired private OpenApiLegacyAuthority legacy;
    @Autowired private OpenApiActionPublicationService actionPublications;
    @Autowired private OpenApiEndpointPublicationService endpointPublications;
    @Autowired private OpenApiEndpointValidator endpointValidator;
    @Autowired private OpenApiEndpointService publicEndpoints;

    @Autowired private OpenApiActionValidator actionValidator;
    @Autowired private OpenApiActionService publicActions;
    @Autowired private IntegrationTokenService integrationTokens;
    @Autowired private com.auraboot.framework.meta.controller.config.ModelController modelAdministration;

    @Autowired private org.springframework.jdbc.core.JdbcTemplate eventJdbc;
    @Autowired private org.springframework.transaction.PlatformTransactionManager eventTransactions;

    @org.springframework.boot.test.context.TestConfiguration
    static class EventFaultConfiguration {
        @org.springframework.context.annotation.Bean
        EventFaultObserver eventFaultObserver() { return new EventFaultObserver(); }
    }

    static class EventFaultObserver implements ModelMutationObserver {
        boolean fail;
        int failAfter = -1;
        @Override public void observe(ModelDefinition model, String pid, Operation operation) {
            if (fail || failAfter == 0 || (failAfter > 0 && --failAfter == 0)) throw new IllegalStateException("Fixture event persistence failure");
        }
    }

    @Autowired private EventFaultObserver eventFaultObserver;
    @Autowired private OpenApiModelEventConsumer modelEvents;
    @Autowired private com.auraboot.framework.integration.ReliableEventDeliveryExecutor eventDelivery;
    @Autowired private com.auraboot.framework.webhook.service.WebhookService webhooks;
    @Autowired private com.auraboot.framework.webhook.service.WebhookDispatcher webhookDispatcher;
    @Autowired private com.auraboot.framework.webhook.service.impl.WebhookDeliveryWorker webhookWorker;

    private Object webhookClaimMonitor() {
        // Lock the actual synchronized poll target, including when Spring wraps it.
        return org.springframework.test.util.AopTestUtils.getUltimateTargetObject(webhookWorker);
    }
    @Autowired private com.auraboot.framework.webhook.mapper.WebhookDeliveryLogMapper deliveries;

    @Autowired private com.auraboot.framework.meta.service.impl.CommandFieldMapExecutor fieldMap;
    @Autowired private com.auraboot.framework.meta.service.impl.CommandStateCheckExecutor stateCheck;

    @Autowired private com.auraboot.framework.meta.service.impl.CommandSideEffectExecutor sideEffects;

    @Autowired private com.auraboot.framework.meta.service.impl.CommandCascadeDeleteExecutor cascadeDeletes;

    private OpenApiResourceContract contract;
    private MachinePrincipal principal;
    private String credentialPid;
    private String clientId;
    private String clientSecret;
    private String allowedPid;
    private String deniedPid;
    private String rawToken;

    @BeforeEach void fixture(org.junit.jupiter.api.TestInfo testInfo) {
        grantCommittedPermissionToTestRole("sys.connector.update", "system", "connector", "update", "Manage Open Platform");
        String suffix = UniqueIdGenerator.generate().toLowerCase();
        String modelCode = "open_machine_" + suffix;
        var reference = FieldDefinition.RefTarget.builder().targetEntity(modelCode).valueField("pid").build();
        String tablePrefix = testInfo.getTestMethod().orElseThrow().getName()
                .matches("customPhysicalTablePreservesPublicHttpCrudVersions|humanCustomTableMutationInvalidatesMachineEtagAndEmitsEvent|atomicCustomTableAdvancesVersionAndEmitsEvent") ? "custom_" : "mt_";
        models.saveDefinition(ModelDefinition.builder().code(modelCode).tableName(tablePrefix + modelCode)
                .displayName("Machine permission fixture").status("published").sourceType("physical").primaryKey("id")
                .extension(Map.of("softDelete", testInfo.getTestMethod().orElseThrow().getName()
                        .matches("softBatchDeleteKeepsTombstonesAndRollsBackPartialFailure|softCascadeDeletesKeepTombstonesAndOwnerEvents|humanSingleCrudUsesTheSamePublishedSourceEventHook")))
                .capabilities(ModelCapabilities.fullPhysical().toBuilder()
                        .filterableFields(List.of("name", "zone", "secret")).sortableFields(List.of("name", "zone", "secret")).build())
                .fields(List.of(FieldDefinition.builder().code("name").columnName("name").dataType("STRING").displayName("Name").sortable(true).filterable(true).build(),
                        FieldDefinition.builder().code("zone").columnName("zone").dataType("STRING").displayName("Zone").sortable(true).filterable(true).build(),
                        FieldDefinition.builder().code("secret").columnName("secret").dataType("STRING").displayName("Secret").sortable(true).filterable(true).build(),
                        FieldDefinition.builder().code("counter").columnName("counter").dataType("INTEGER").displayName("Counter").filterable(true).build(),
                        FieldDefinition.builder().code("cap").columnName("cap").dataType("INTEGER").displayName("Cap").build(),
                        FieldDefinition.builder().code("related").columnName("related").dataType("REFERENCE").displayName("Related").refTarget(reference).build())).build());
        var schema = schemas.createTableByModel(modelCode);
        assertThat(schema.isSuccess()).as("production schema creation: %s", schema.getErrorMessage()).isTrue();
        var scopes = new EnumMap<Operation, String>(Operation.class);
        for (Operation operation : Operation.values()) scopes.put(operation, "fixture." + suffix + "." + operation.code());
        var fields = new LinkedHashMap<String, Field>();
        fields.put("pid", new Field("pid", Type.PID, true, false, false, false, Set.of(), false, null, Map.of(), null));
        fields.put("name", new Field("name", Type.STRING, true, true, false, true, Set.of("EQ"), true, null, Map.of(), null));
        fields.put("zone", new Field("zone", Type.STRING, true, true, false, true, Set.of("EQ"), true, null, Map.of(), null));
        contract = new OpenApiResourceContract("fixture." + suffix, modelCode, 1, Set.of(Operation.values()), scopes,
                DataPolicy.INSTALLATION_FILTER, fields, 50, 8, 5000, Set.of());
        publications.saveDraft(contract.code(), new OpenApiPublicationService.DraftRequest(0, contract));
        publications.publish(contract.code(), 1);
        var application = management.createApplication(new CreateApplicationRequest("Machine fixture", "Real data guard test"));
        var installation = management.install(application.pid(), new InstallApplicationRequest("staging", new HashSet<>(scopes.values()), 600));
        var credential = management.createCredential(installation.pid());
        credentialPid = credential.credentialPid();
        clientId = credential.clientId();
        clientSecret = credential.clientSecret();
        var token = tokens.issue("client_credentials", credential.clientId(), credential.clientSecret(), null);
        rawToken = token.accessToken();
        var identity = auth.findToken(secrets.sha256(token.accessToken()), OpenPlatformTokenService.AUDIENCE, Instant.now());
        principal = new MachinePrincipal(identity.tenantId(), identity.tokenPid(), "machine", new HashSet<>(scopes.values()),
                identity.applicationPid(), identity.installationPid(), identity.environment(), identity.tokenPid());
        publications.grant(installation.pid(), contract.code(), new OpenApiInstallationPolicy(Set.of(Operation.values()),
                fields.keySet(), Set.of("name", "zone"), List.of(new OpenApiInstallationPolicy.RowConstraint("zone", List.of("A")))));
        allowedPid = MetaContext.runWithCommandPermitScope("ALL", () -> data.create(modelCode,
                new HashMap<>(Map.of("name", "Visible", "zone", "A", "secret", "Private")))).get("pid").toString();
        deniedPid = MetaContext.runWithCommandPermitScope("ALL", () -> data.create(modelCode,
                new HashMap<>(Map.of("name", "Hidden", "zone", "B", "secret", "Other")))).get("pid").toString();
    }

    private void publishEvents() {
        contract = new OpenApiResourceContract(contract.code(), contract.modelCode(), 2, contract.operations(),
                contract.scopes(), contract.dataPolicy(), contract.fields(), 50, 8, 5000,
                Set.of("created", "updated", "deleted"));
        publications.saveDraft(contract.code(), new OpenApiPublicationService.DraftRequest(1, contract));
        publications.publish(contract.code(), 2);
    }

    private List<Map<String, Object>> sourceEvents() {
        return eventJdbc.queryForList("SELECT event_id, event_type, event_sequence, occurred_at, correlation_id, subject, payload::text AS payload FROM ab_outbox"
                + " WHERE tenant_id = ? AND ordering_key LIKE ? ORDER BY id", principal.tenantId(),
                "open.resource:" + contract.code() + ":%");
    }

    @Test void machineGrantCannotFollowAChangedPhysicalStorageBinding() throws Exception {
        assertThat(authorizer.authorize(principal, contract.code(), Operation.GET).contract()).isEqualTo(contract);
        var model = models.getModelDefinition(contract.modelCode()).orElseThrow();
        model.setTableName("unowned_" + contract.modelCode());
        models.saveDefinition(model);
        assertThatThrownBy(() -> authorizer.authorize(principal, contract.code(), Operation.GET))
                .isInstanceOf(OpenApiPublicationService.PublicationConflictException.class).hasMessageContaining("source binding");
        assertThat(publications.active(principal.tenantId(), contract.code())).contains(contract);
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/open/v1/resources/" + contract.code() + "/" + allowedPid)
                .header("Authorization", "Bearer " + rawToken))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("open_api_source_changed"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("unowned_"))));
    }

    @Test void machineGrantCannotFollowAChangedColumnBinding() {
        var model = models.getModelDefinition(contract.modelCode()).orElseThrow();
        model.getFields().stream().filter(field -> "name".equals(field.getCode())).findFirst().orElseThrow()
                .setColumnName("secret");
        models.saveDefinition(model);
        assertThatThrownBy(() -> authorizer.authorize(principal, contract.code(), Operation.GET))
                .isInstanceOf(OpenApiPublicationService.PublicationConflictException.class).hasMessageContaining("source binding");
    }

    @Test void administrationMetadataRebindingRejectsPublicHttpAndRestorationKeepsOriginalEtag() throws Exception {
        grantCommittedPermissionToTestRole("meta.model.update", "meta", "model", "update", "Manage model metadata");
        String code = contract.code();
        String path = "/api/open/v1/resources/" + code + "/" + allowedPid;
        var baseline = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                        .header("Authorization", "Bearer " + rawToken))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
        applyTestMetaContext();
        var model = models.getModelDefinition(contract.modelCode()).orElseThrow();
        var originalFields = json.convertValue(model.getFields(), new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() { });
        var changedFields = json.convertValue(model.getFields(), new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() { });
        changedFields.stream().filter(field -> "name".equals(field.get("code"))).findFirst().orElseThrow().put("columnName", "secret");
        var update = new MetaModelUpdateRequest(); update.setDisplayName("Machine permission fixture");
        update.setExtension(Map.of("fields", changedFields));
        String modelPid = modelMapper.findCurrentByCode(contract.modelCode()).getPid();
        modelAdministration.updateModel(modelPid, update);
        assertThat(models.getModelDefinition(contract.modelCode()).orElseThrow().getCapabilities())
                .isEqualTo(model.getCapabilities());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).header("Authorization", "Bearer " + rawToken))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("open_api_source_changed"));
        applyTestMetaContext();
        update.setExtension(Map.of("fields", originalFields));
        modelAdministration.updateModel(modelPid, update);
        assertThat(models.getModelDefinition(contract.modelCode()).orElseThrow().getCapabilities())
                .isEqualTo(model.getCapabilities());
        var restored = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                        .header("Authorization", "Bearer " + rawToken))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
        assertThat(restored.getResponse().getContentAsString()).isEqualTo(baseline.getResponse().getContentAsString());
        assertThat(restored.getResponse().getHeader("ETag")).isEqualTo(baseline.getResponse().getHeader("ETag"));
        applyTestMetaContext();
        try {
            update.setExtension(Map.of("fields", originalFields.stream()
                    .filter(field -> !"name".equals(field.get("code"))).toList()));
            modelAdministration.updateModel(modelPid, update);
            assertThat(models.getModelDefinition(contract.modelCode()).orElseThrow().getFields())
                    .extracting(com.auraboot.framework.meta.dto.FieldDefinition::getCode).doesNotContain("name");
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                            .header("Authorization", "Bearer " + rawToken))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                            .value("open_api_source_changed"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                            .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Published source field"))));
        } finally {
            applyTestMetaContext();
            update.setExtension(Map.of("fields", originalFields));
            modelAdministration.updateModel(modelPid, update);
        }
        var fieldRestored = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                        .header("Authorization", "Bearer " + rawToken))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
        assertThat(fieldRestored.getResponse().getContentAsString()).isEqualTo(baseline.getResponse().getContentAsString());
        assertThat(fieldRestored.getResponse().getHeader("ETag")).isEqualTo(baseline.getResponse().getHeader("ETag"));
        var semantics = List.of(Map.of("dataType", "INTEGER"), Map.of("virtualType", "transient"),
                Map.of("virtualType", "computed_readonly"));
        org.junit.jupiter.api.Assertions.assertAll("published field semantics", semantics.stream()
                .map(change -> (org.junit.jupiter.api.function.Executable) () -> {
                    applyTestMetaContext();
                    var altered = json.convertValue(originalFields,
                            new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() { });
                    altered.stream().filter(field -> "name".equals(field.get("code"))).findFirst().orElseThrow().putAll(change);
                    try {
                        update.setExtension(Map.of("fields", altered));
                        modelAdministration.updateModel(modelPid, update);
                        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                                        .header("Authorization", "Bearer " + rawToken))
                                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
                                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                                        .value("open_api_source_changed"))
                                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Private"))));
                    } finally {
                        applyTestMetaContext();
                        update.setExtension(Map.of("fields", originalFields));
                        modelAdministration.updateModel(modelPid, update);
                    }
                    var recovered = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                                    .header("Authorization", "Bearer " + rawToken))
                            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
                    assertThat(recovered.getResponse().getContentAsString()).isEqualTo(baseline.getResponse().getContentAsString());
                    assertThat(recovered.getResponse().getHeader("ETag")).isEqualTo(baseline.getResponse().getHeader("ETag"));
                }));
    }

    @Test void modelEventsShareCrudTransactionAndIdempotentReplay() throws Exception {
        publishEvents();
        // Writer cannot read name; the trusted source still captures it for separately authorized consumers.
        publications.grant(principal.installationPid(), contract.code(), new OpenApiInstallationPolicy(contract.operations(),
                Set.of("pid", "zone"), Set.of("name", "zone"),
                List.of(new OpenApiInstallationPolicy.RowConstraint("zone", List.of("A")))));
        var previous = MetaContext.snapshot();
        MetaContext.setContext(principal.tenantId(), null, principal.subjectPid(), "machine");
        try (var machine = MachineDataAccessContext.begin(principal)) {
            var created = resources.write(principal, contract.code(), Operation.CREATE, null, "event-create-key1", null,
                    Map.of("name", "Captured", "zone", "A"));
            assertThat(created.body()).doesNotContainKey("name");
            assertThat(resources.write(principal, contract.code(), Operation.CREATE, null, "event-create-key1", null,
                    Map.of("name", "Captured", "zone", "A")).replay()).isTrue();
            String pid = created.body().get("pid").toString();
            var changed = resources.write(principal, contract.code(), Operation.UPDATE, pid, "event-update-key1",
                    created.headers().get("ETag"), Map.of("name", "Changed"));
            resources.write(principal, contract.code(), Operation.DELETE, pid, "event-delete-key1", changed.headers().get("ETag"), null);
            var events = sourceEvents();
            assertThat(events).hasSize(3);
            assertThat(events.stream().map(row -> row.get("event_id")).distinct()).hasSize(3);
            assertThat(events.stream().map(row -> ((Number) row.get("event_sequence")).longValue()).toList())
                    .isSorted().doesNotHaveDuplicates();
            var first = json.readTree(events.getFirst().get("payload").toString());
            assertThat(first.path("source").path("name").asText()).isEqualTo("Captured");
            assertThat(first.path("source").has("secret")).isFalse();
            assertThat(first.path("source").has("tenant_id")).isFalse();
            var deleted = json.readTree(events.getLast().get("payload").toString());
            assertThat(deleted.path("source").path("name").asText()).isEqualTo("Changed");
            assertThat(deleted.path("schemaVersion").asInt()).isEqualTo(2);
        } finally { MetaContext.restore(previous); }
    }

    @Test void changedSourceBindingRollsBackHumanMutationAndEventCapture() {
        publishEvents();
        var model = models.getModelDefinition(contract.modelCode()).orElseThrow();
        var field = model.getFields().stream().filter(value -> "name".equals(value.getCode())).findFirst().orElseThrow();
        String originalColumn = field.getColumnName();
        field.setColumnName("secret");
        models.saveDefinition(model);
        var nested = new org.springframework.transaction.support.TransactionTemplate(eventTransactions);
        nested.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED);
        try {
            assertThatThrownBy(() -> MetaContext.runWithCommandPermitScope("ALL", () -> nested.execute(status -> {
                data.update(contract.modelCode(), allowedPid, new HashMap<>(Map.of("name", "Must roll back")));
                return null;
            }))).isInstanceOf(com.auraboot.framework.meta.exception.MetaServiceException.class)
                    .hasRootCauseInstanceOf(OpenApiPublicationService.SourceBindingConflictException.class);
            assertThat(sourceEvents()).isEmpty();
            assertThat(eventJdbc.queryForMap("SELECT name, secret FROM " + model.getTableName() + " WHERE tenant_id = ? AND pid = ?",
                    principal.tenantId(), allowedPid)).containsEntry("name", "Visible").containsEntry("secret", "Private");
        } finally {
            field.setColumnName(originalColumn);
            models.saveDefinition(model);
        }
        MetaContext.runWithCommandPermitScope("ALL", () -> data.update(contract.modelCode(), allowedPid,
                new HashMap<>(Map.of("name", "Restored source"))));
        assertThat(sourceEvents()).hasSize(1);
        assertThat(MetaContext.runWithCommandPermitScope("ALL", () -> data.getById(contract.modelCode(), allowedPid)))
                .containsEntry("name", "Restored source");
    }

    private com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelope sourceEnvelope() throws Exception {
        var row = sourceEvents().getFirst();
        Map<String, Object> payload = json.readerFor(new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { })
                .with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .readValue(row.get("payload").toString());
        return new com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelope("1.0",
                row.get("event_id").toString(), row.get("event_type").toString(), "dynamic-model", row.get("subject").toString(),
                ((java.sql.Timestamp) row.get("occurred_at")).toInstant(), principal.tenantId(), row.get("correlation_id").toString(),
                null, "open.resource:" + contract.code() + ":" + row.get("subject"), ((Number) row.get("event_sequence")).longValue(),
                payload, Map.of());
    }

    private com.auraboot.framework.webhook.entity.WebhookSubscription eventSubscription(String installation, String url, String secret) {
        var request = new com.auraboot.framework.webhook.dto.WebhookCreateRequest();
        request.setName("Published model fixture"); request.setInstallationPid(installation);
        request.setEventType("open.resource." + contract.code() + ".created"); request.setEventVersion(2);
        request.setTargetUrl(url); request.setSecret(secret);
        return webhooks.create(request);
    }

    @Test
    void publishedModelSubscriptionsRequireSecretAndCannotClearIt() {
        publishEvents();
        var nested = new org.springframework.transaction.support.TransactionTemplate(eventTransactions);
        nested.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED);
        assertThatThrownBy(() -> nested.execute(status -> eventSubscription(principal.installationPid(),
                "https://example.com/model-events", null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> nested.execute(status -> eventSubscription(principal.installationPid(),
                "https://example.com/model-events", "  "))).isInstanceOf(IllegalArgumentException.class);
        var subscription = eventSubscription(principal.installationPid(), "https://example.com/model-events", "valid-model-secret");
        var request = new com.auraboot.framework.webhook.dto.WebhookCreateRequest();
        request.setName("Changed model subscription"); request.setInstallationPid(principal.installationPid());
        request.setEventType(subscription.getEventType()); request.setEventVersion(2); request.setTargetUrl(subscription.getTargetUrl());
        request.setSecret("****");
        webhooks.update(subscription.getPid(), request);
        String persistedSecret = webhooks.getByPid(subscription.getPid()).getSecret();
        request.setSecret(null);
        assertThatThrownBy(() -> nested.execute(status -> webhooks.update(subscription.getPid(), request)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(webhooks.getByPid(subscription.getPid()).getSecret()).isEqualTo(persistedSecret);
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void failedModelNotificationPersistsJitteredRetryAndRecovers() throws Exception {
        publishEvents();
        var leaseEntered = new java.util.concurrent.CountDownLatch(1);
        var leaseRelease = new java.util.concurrent.CountDownLatch(1);
        String[] leaseSubscription = new String[1];
        var received = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var signatures = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var timestamps = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var receiver = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/receive", exchange -> {
            received.add(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            signatures.add(exchange.getRequestHeaders().getFirst("X-Webhook-Signature"));
            timestamps.add(exchange.getRequestHeaders().getFirst("X-Webhook-Timestamp"));
            if (received.size() == 3) {
                leaseEntered.countDown();
                try {
                    if (!leaseRelease.await(15, java.util.concurrent.TimeUnit.SECONDS))
                        throw new java.io.IOException("Owned lease fixture was not released");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException("Owned lease fixture interrupted", error);
                }
            }
            exchange.sendResponseHeaders(received.size() == 1 ? 503 : 200, -1);
            exchange.close();
        });
        String previousHosts = System.getProperty("AURA_SSRF_ALLOWED_PRIVATE_HOSTS");
        System.setProperty("AURA_SSRF_ALLOWED_PRIVATE_HOSTS", "127.0.0.1");
        receiver.start();
        String subscriptionPid = null;
        try {
            var subscription = eventSubscription(principal.installationPid(),
                    "http://127.0.0.1:" + receiver.getAddress().getPort() + "/receive", "jitter-fixture-secret");
            subscriptionPid = subscription.getPid();
            com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelope source;
            com.auraboot.framework.webhook.entity.WebhookDeliveryLog claimed;
            // Only the explicit first attempt owns this lane here; release the real
            // poll monitor before waiting for scheduled retry and lease recovery.
            synchronized (webhookClaimMonitor()) {
                MetaContext.runWithCommandPermitScope("ALL", () -> data.create(contract.modelCode(),
                        new HashMap<>(Map.of("name", "Jittered retry", "zone", "A", "secret", "Never public"))));
                source = sourceEnvelope();
                eventDelivery.deliver(source, modelEvents, "jitter-consumer-lease");
                claimed = deliveries.claimReady(50, "jitter-worker", "jitter-first-claim", Instant.now().plusSeconds(30))
                        .stream().filter(log -> source.eventId().equals(log.getEventId())).findFirst().orElseThrow();
                Instant before = Instant.now();
                webhookDispatcher.processClaimed(claimed);
                Instant after = Instant.now();
                var failed = deliveries.selectById(claimed.getId());
                assertThat(failed.getDeliveryStatus()).isEqualTo("pending");
                assertThat(failed.getRetryCount()).isEqualTo(1);
                assertThat(failed.getResponseStatus()).isEqualTo(503);
                assertThat(failed.getNextRetryAt()).isAfterOrEqualTo(before.plusMillis(500)).isBefore(after.plusSeconds(1));
                assertThat(failed.getLeaseToken()).isNull();
            }
            // The scheduled production worker owns recovery; another claimant need not win its lease.
            org.awaitility.Awaitility.await().pollInSameThread().atMost(java.time.Duration.ofSeconds(5))
                    .until(() -> "success".equals(deliveries.selectById(claimed.getId()).getDeliveryStatus()));
            var success = deliveries.selectById(claimed.getId());
            assertThat(success.getDeliveryStatus()).isEqualTo("success");
            assertThat(success.getRetryCount()).isEqualTo(1);
            assertThat(success.getResponseStatus()).isEqualTo(200);
            assertThat(success.getEventId()).isEqualTo(source.eventId());
            assertThat(received).hasSize(2);
            assertThat(received.getLast()).isEqualTo(received.getFirst());
            var payload = json.readTree(received.getFirst());
            assertThat(payload.path("id").asText()).isEqualTo(source.eventId());
            assertThat(payload.path("data").path("name").asText()).isEqualTo("Jittered retry");
            assertThat(payload.path("data").has("secret")).isFalse();
            for (int attempt = 0; attempt < 2; attempt++) {
                assertThat(new com.auraboot.framework.webhook.service.WebhookSignature().verify("jitter-fixture-secret",
                        timestamps.get(attempt), received.get(attempt), signatures.get(attempt),
                        Instant.now(), java.time.Duration.ofMinutes(1))).isTrue();
            }
            assertThat(eventJdbc.queryForObject("SELECT COUNT(*) FROM ab_webhook_delivery_log WHERE tenant_id = ? AND event_id = ?",
                    Long.class, principal.tenantId(), source.eventId())).isEqualTo(1);
            var leaseRequest = new com.auraboot.framework.webhook.dto.WebhookCreateRequest();
            leaseRequest.setName("Receiver lease fence fixture");
            leaseRequest.setEventType("fixture.receiver-lease." + contract.code());
            leaseRequest.setTargetUrl(subscription.getTargetUrl());
            leaseRequest.setSecret("lease-fixture-secret");
            var leaseSub = webhooks.create(leaseRequest);
            leaseSubscription[0] = leaseSub.getPid();
            var queued = webhookDispatcher.dispatchTracked(leaseSub.getEventType(),
                    Map.of("_eventId", "lease-fixture-" + leaseSub.getPid()), principal.tenantId());
            assertThat(queued.receipts()).hasSize(1);
            String deliveryPid = queued.receipts().getFirst().deliveryLogPid();
            assertThat(leaseEntered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var held = deliveries.selectOne(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<
                    com.auraboot.framework.webhook.entity.WebhookDeliveryLog>().eq("pid", deliveryPid));
            assertThat(held.getDeliveryStatus()).isEqualTo("processing");
            assertThat(held.getLeaseToken()).isNotBlank();
            assertThat(deliveries.markFailure(held.getId(), "stale-fixture-token", 503, null,
                    "stale attempt", Instant.now())).isZero();
            var unchanged = deliveries.selectById(held.getId());
            assertThat(unchanged.getDeliveryStatus()).isEqualTo("processing");
            assertThat(unchanged.getRetryCount()).isZero();
            assertThat(unchanged.getLeaseToken()).isEqualTo(held.getLeaseToken());
            assertThat(eventJdbc.queryForObject("SELECT lease_token FROM ab_webhook_receiver_lane WHERE tenant_id=? AND receiver_key=?",
                    String.class, held.getTenantId(), held.getReceiverKey())).isEqualTo(held.getLeaseToken());
            leaseRelease.countDown();
            org.awaitility.Awaitility.await().pollInSameThread().atMost(java.time.Duration.ofSeconds(5))
                    .until(() -> "success".equals(deliveries.selectById(held.getId()).getDeliveryStatus()));
            assertThat(deliveries.selectById(held.getId()).getEventId()).isEqualTo("lease-fixture-" + leaseSub.getPid());
            assertThat(eventJdbc.queryForObject("SELECT lease_token FROM ab_webhook_receiver_lane WHERE tenant_id=? AND receiver_key=?",
                    String.class, held.getTenantId(), held.getReceiverKey())).isNull();
        } finally {
            leaseRelease.countDown();
            if (leaseSubscription[0] != null) webhooks.delete(leaseSubscription[0]);
            receiver.stop(0);
            if (previousHosts == null) System.clearProperty("AURA_SSRF_ALLOWED_PRIVATE_HOSTS");
            else System.setProperty("AURA_SSRF_ALLOWED_PRIVATE_HOSTS", previousHosts);
            if (subscriptionPid != null) webhooks.delete(subscriptionPid);
            cleanupCommittedFixture();
        }
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void durableModelConsumerProjectsEachInstallationAndRechecksQueuedAuthority() throws Exception {
        // This scenario drives every claim explicitly. Prevent a scheduled poll
        // from consuming a freshly committed fixture between its manual steps.
        synchronized (webhookClaimMonitor()) {
            publishEvents();
            var received = new java.util.concurrent.CopyOnWriteArrayList<String>();
            var signatures = new java.util.concurrent.CopyOnWriteArrayList<String>();
            var signedAt = new java.util.concurrent.CopyOnWriteArrayList<String>();
            var signatureHeaders = new java.util.concurrent.CopyOnWriteArrayList<List<String>>();
            var protocolHeaders = new java.util.concurrent.CopyOnWriteArrayList<Map<String, List<String>>>();
            var receiver = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            receiver.createContext("/receive", exchange -> {
                received.add(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                signatures.add(exchange.getRequestHeaders().getFirst("X-Webhook-Signature"));
                signatureHeaders.add(exchange.getRequestHeaders().get("X-Webhook-Signature"));
                var headers = new HashMap<String, List<String>>();
                for (var header : List.of("X-Webhook-Event", "X-Webhook-Event-Version", "X-Webhook-Delivery", "X-Webhook-Timestamp", "Content-Type"))
                    headers.put(header, exchange.getRequestHeaders().get(header));
                protocolHeaders.add(headers);
                signedAt.add(exchange.getRequestHeaders().getFirst("X-Webhook-Timestamp"));
                exchange.sendResponseHeaders(200, -1); exchange.close();
            });
            String previousHosts = System.getProperty("AURA_SSRF_ALLOWED_PRIVATE_HOSTS");
            System.setProperty("AURA_SSRF_ALLOWED_PRIVATE_HOSTS", "127.0.0.1"); receiver.start();
            String secondInstallation = null;
            var subscriptions = new ArrayList<com.auraboot.framework.webhook.entity.WebhookSubscription>();
            try {
                String url = "http://127.0.0.1:" + receiver.getAddress().getPort() + "/receive";
                var a = eventSubscription(principal.installationPid(), url, "model-fixture-secret"); subscriptions.add(a);
                var headerRequest = new com.auraboot.framework.webhook.dto.WebhookCreateRequest();
                headerRequest.setName(a.getName()); headerRequest.setInstallationPid(a.getInstallationPid());
                headerRequest.setEventType(a.getEventType()); headerRequest.setEventVersion(a.getEventVersion());
                headerRequest.setTargetUrl(url); headerRequest.setSecret("****");
                headerRequest.setHeaders(json.writeValueAsString(Map.of("x-webhook-signature", "forged", "x-webhook-event", "forged",
                        "x-webhook-event-version", "999", "x-webhook-delivery", "forged", "x-webhook-timestamp", "1", "content-type", "text/plain")));
                webhooks.update(a.getPid(), headerRequest);
                var application = management.createApplication(new CreateApplicationRequest("Second model event installation", "Isolation"));
                var b = management.install(application.pid(), new InstallApplicationRequest("staging", new HashSet<>(contract.scopes().values()), 600));
                secondInstallation = b.pid();
                publications.grant(b.pid(), contract.code(), new OpenApiInstallationPolicy(contract.operations(), contract.fields().keySet(),
                        Set.of("name", "zone"), List.of(new OpenApiInstallationPolicy.RowConstraint("zone", List.of("B")))));
                subscriptions.add(eventSubscription(b.pid(), url, "other-secret"));
                subscriptions.add(eventSubscription(null, url, "legacy-secret"));
                MetaContext.runWithCommandPermitScope("ALL", () -> data.create(contract.modelCode(),
                        new HashMap<>(Map.of("name", "Source name", "zone", "A", "secret", "Never public"))));
                var source = sourceEnvelope();
                eventDelivery.deliver(source, modelEvents, "fixture-consumer-lease");
                eventDelivery.deliver(source, modelEvents, "duplicate-consumer-lease");
                assertThat(eventJdbc.queryForObject("SELECT COUNT(*) FROM ab_webhook_delivery_log WHERE tenant_id = ? AND event_id = ?",
                        Long.class, principal.tenantId(), source.eventId())).isEqualTo(1);
                assertThat(eventJdbc.queryForObject("SELECT status FROM ab_integration_receipt WHERE tenant_id = ? AND event_id = ? AND consumer_code = ?",
                        String.class, principal.tenantId(), source.eventId(), modelEvents.consumerCode())).isEqualTo("applied");
                var queued = deliveries.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.auraboot.framework.webhook.entity.WebhookDeliveryLog>()
                        .eq(com.auraboot.framework.webhook.entity.WebhookDeliveryLog::getTenantId, principal.tenantId())
                        .eq(com.auraboot.framework.webhook.entity.WebhookDeliveryLog::getEventId, source.eventId())).getFirst();
                assertThat(json.readTree(queued.getRequestBody()).path("data").path("name").asText()).isEqualTo("Source name");
                assertThat(json.writeValueAsString(queued)).doesNotContain("authorizationContext", "Never public");
                var model = models.getModelDefinition(contract.modelCode()).orElseThrow();
                var field = model.getFields().stream().filter(value -> "name".equals(value.getCode())).findFirst().orElseThrow();
                String originalColumn = field.getColumnName();
                try {
                    field.setColumnName("secret"); models.saveDefinition(model);
                    var rebound = deliveries.claimReady(50, "model-fixture-worker", "rebound-claim", Instant.now().plusSeconds(30)).stream()
                            .filter(log -> source.eventId().equals(log.getEventId())).findFirst().orElseThrow();
                    webhookDispatcher.processClaimed(rebound);
                    assertThat(received).isEmpty();
                    assertThat(deliveries.selectById(rebound.getId()).getDeliveryStatus()).isEqualTo("dead_letter");
                } finally {
                    field.setColumnName(originalColumn); models.saveDefinition(model);
                }
                assertThat(webhooks.replayDelivery(queued.getPid())).isTrue();
                publications.grant(principal.installationPid(), contract.code(), new OpenApiInstallationPolicy(contract.operations(),
                        Set.of("pid", "zone"), Set.of("name", "zone"),
                        List.of(new OpenApiInstallationPolicy.RowConstraint("zone", List.of("A")))));
                // Rehydrate durable state through the production mapper, then claim with the worker's lease protocol.
                var claimed = deliveries.claimReady(50, "model-fixture-worker", "model-fixture-claim", Instant.now().plusSeconds(30)).stream()
                        .filter(log -> source.eventId().equals(log.getEventId())).findFirst().orElseThrow();
                webhookDispatcher.processClaimed(claimed);
                assertThat(received).hasSize(1); assertThat(new com.auraboot.framework.webhook.service.WebhookSignature().verify("model-fixture-secret",
                        signedAt.getFirst(), received.getFirst(), signatures.getFirst(), Instant.now(), java.time.Duration.ofMinutes(1))).isTrue();
                assertThat(signatureHeaders.getFirst()).containsExactly(signatures.getFirst());
                assertThat(protocolHeaders.getFirst()).containsEntry("X-Webhook-Event", List.of(a.getEventType()))
                        .containsEntry("X-Webhook-Event-Version", List.of("2"))
                        .containsEntry("X-Webhook-Delivery", List.of(claimed.getPid()))
                        .containsEntry("X-Webhook-Timestamp", List.of(signedAt.getFirst()))
                        .containsEntry("Content-Type", List.of("application/json"));
                var publicEvent = json.readTree(received.getFirst());
                assertThat(publicEvent.path("id").asText()).isEqualTo(source.eventId());
                assertThat(publicEvent.path("recordVersion").asLong()).isEqualTo(source.sequence());
                assertThat(publicEvent.path("data").has("name")).isFalse();
                assertThat(publicEvent.path("data").has("secret")).isFalse();
                assertThat(publicEvent.has("source")).isFalse();
                assertThat(deliveries.selectById(claimed.getId()).getDeliveryStatus()).isEqualTo("success");
                // A second source event is queued, then revoked before its first attempt.
                MetaContext.runWithCommandPermitScope("ALL", () -> data.create(contract.modelCode(), new HashMap<>(Map.of("name", "Revoked", "zone", "A"))));
                var secondRow = sourceEvents().getLast();
                var secondSource = new com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelope("1.0", secondRow.get("event_id").toString(),
                        secondRow.get("event_type").toString(), "dynamic-model", secondRow.get("subject").toString(), Instant.now(), principal.tenantId(),
                        "second-correlation", null, "open.resource:" + contract.code() + ":" + secondRow.get("subject"), ((Number) secondRow.get("event_sequence")).longValue(),
                        json.readValue(secondRow.get("payload").toString(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { }), Map.of());
                eventDelivery.deliver(secondSource, modelEvents, "second-consumer-lease");
                publications.revoke(principal.installationPid(), contract.code());
                var revoked = deliveries.claimReady(50, "model-fixture-worker", "revoked-claim", Instant.now().plusSeconds(30)).stream()
                        .filter(log -> secondSource.eventId().equals(log.getEventId())).findFirst().orElseThrow();
                webhookDispatcher.processClaimed(revoked);
                assertThat(received).hasSize(1);
                assertThat(deliveries.selectById(revoked.getId()).getDeliveryStatus()).isEqualTo("dead_letter");
                assertThat(webhooks.replayDelivery(revoked.getPid())).isTrue();
                var replay = deliveries.claimReady(50, "model-fixture-worker", "replayed-claim", Instant.now().plusSeconds(30)).stream()
                        .filter(log -> secondSource.eventId().equals(log.getEventId())).findFirst().orElseThrow();
                webhookDispatcher.processClaimed(replay);
                assertThat(received).hasSize(1);
                assertThat(deliveries.selectById(replay.getId()).getDeliveryStatus()).isEqualTo("dead_letter");
            } finally {
                receiver.stop(0);
                if (previousHosts == null) System.clearProperty("AURA_SSRF_ALLOWED_PRIVATE_HOSTS"); else System.setProperty("AURA_SSRF_ALLOWED_PRIVATE_HOSTS", previousHosts);
                for (var subscription : subscriptions) webhooks.delete(subscription.getPid());
                if (secondInstallation != null) management.disableInstallation(secondInstallation);
                cleanupCommittedFixture();
            }
        }
    }

    @Test void bulkCasAndAtomicOriginsEmitOnlyForAffectedRows() {
        publishEvents();
        MetaContext.runWithCommandPermitScope("ALL", () -> {
            var rows = data.bulkCreate(contract.modelCode(), List.of(Map.of("name", "Bulk one", "zone", "A", "counter", 0, "cap", 5),
                    Map.of("name", "Bulk two", "zone", "A", "counter", 0, "cap", 5)));
            String first = rows.getFirst().get("pid").toString(), second = rows.getLast().get("pid").toString();
            assertThat(sourceEvents()).hasSize(2);
            assertThat(data.compareAndSet(contract.modelCode(), first, "name", "Bulk one", Map.of("name", "CAS one"))).isTrue();
            assertThat(data.compareAndSet(contract.modelCode(), first, "name", "Bulk one", Map.of("name", "Must not write"))).isFalse();
            assertThat(sourceEvents()).hasSize(3);
            data.compareAndSetBatch(contract.modelCode(), "name", List.of(
                    new com.auraboot.framework.plugin.extension.CompareAndSetUpdate(first, "CAS one", Map.of("name", "Batch one")),
                    new com.auraboot.framework.plugin.extension.CompareAndSetUpdate(second, "Bulk two", Map.of("name", "Batch two"))));
            assertThat(sourceEvents()).hasSize(5);
            var nested = new org.springframework.transaction.support.TransactionTemplate(eventTransactions);
            nested.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED);
            assertThatThrownBy(() -> nested.execute(status -> {
                data.compareAndSetBatch(contract.modelCode(), "name", List.of(
                        new com.auraboot.framework.plugin.extension.CompareAndSetUpdate(first, "Batch one", Map.of("name", "Partial forbidden")),
                        new com.auraboot.framework.plugin.extension.CompareAndSetUpdate(second, "Wrong expectation", Map.of("name", "Partial forbidden"))));
                return null;
            })).isInstanceOf(com.auraboot.framework.meta.exception.MetaServiceException.class);
            assertThat(sourceEvents()).hasSize(5);
            assertThat(data.getById(contract.modelCode(), first)).containsEntry("name", "Batch one");
            assertThat(data.incrementWithinCap(contract.modelCode(), first, "counter", 2, "cap")).contains(2L);
            assertThat(data.incrementWithinCap(contract.modelCode(), first, "counter", 10, "cap")).isEmpty();
            assertThat(sourceEvents()).hasSize(6);
            data.batchDelete(contract.modelCode(), List.of(first, second));
            assertThat(sourceEvents()).hasSize(8);
            assertThat(sourceEvents().stream().filter(row -> first.equals(row.get("subject")))
                    .map(row -> ((Number) row.get("event_sequence")).longValue()).toList()).containsExactly(1L, 2L, 3L, 4L, 5L);
            return null;
        });
    }

    @Test void commandFieldMapOriginsShareSourceVersionsAndNumericIdentity() {
        publishEvents();
        MetaContext.runWithCommandPermitScope("ALL", () -> {
            var rule = new com.auraboot.framework.meta.entity.BindingRule();
            rule.setRuleType("FIELD_MAP"); rule.setTargetModel(contract.modelCode());
            rule.setSourceField("name"); rule.setTargetField("name");
            var request = new com.auraboot.framework.meta.dto.CommandExecuteRequest();
            request.setOperationType("create");
            var created = fieldMap.executeFieldMapPhase(List.of(rule), Map.of("name", "Command explicit"), principal.tenantId(), request);
            String pid = created.get("recordPid").toString();
            long id = eventJdbc.queryForObject("SELECT id FROM mt_" + contract.modelCode() + " WHERE pid = ? AND tenant_id = ?",
                    Long.class, pid, principal.tenantId());
            request.setOperationType("update"); request.setTargetRecordId(Long.toString(id));
            fieldMap.executeFieldMapPhase(List.of(rule), Map.of("name", "Command numeric update"), principal.tenantId(), request);
            assertThat(data.getById(contract.modelCode(), pid)).containsEntry("name", "Command numeric update");
            request.setOperationType("delete");
            fieldMap.executeFieldMapPhase(List.of(rule), Map.of(), principal.tenantId(), request);
            assertThat(sourceEvents()).hasSize(3);
            assertThat(sourceEvents().stream().map(row -> row.get("subject")).toList()).containsOnly(pid);
            assertThat(sourceEvents().stream().map(row -> ((Number) row.get("event_sequence")).longValue()).toList())
                    .containsExactly(1L, 2L, 3L);
            var command = new com.auraboot.framework.meta.entity.CommandDefinition();
            command.setCode("fixture.create"); command.setModelCode(contract.modelCode());
            var implicit = new com.auraboot.framework.meta.dto.CommandExecuteRequest(); implicit.setOperationType("create");
            fieldMap.executeImplicitFieldMapPhase(Map.of("type", "create", "inputFields", List.of("name", "zone")),
                    Map.of("name", "Command implicit", "zone", "A"), principal.tenantId(), implicit, command);
            assertThat(sourceEvents()).hasSize(4);
            return null;
        });
    }

    @Test void commandStateOriginAdvancesVersionAndRollsBackObserverFailure() {
        publishEvents();
        MetaContext.runWithCommandPermitScope("ALL", () -> {
            var command = new com.auraboot.framework.meta.entity.CommandDefinition();
            command.setCode("fixture.transition"); command.setModelCode(contract.modelCode());
            var request = new com.auraboot.framework.meta.dto.CommandExecuteRequest(); request.setTargetRecordId(allowedPid);
            var config = Map.<String, Object>of("stateField", "name", "fromStates", List.of("Visible"), "toState", "Approved");
            assertThat(stateCheck.executeStateCheckPhase(command, Map.of(), principal.tenantId(), request, config)).isEqualTo("Approved");
            assertThat(data.getById(contract.modelCode(), allowedPid)).containsEntry("name", "Approved");
            assertThat(sourceEvents()).hasSize(1);
            assertThat(((Number) sourceEvents().getFirst().get("event_sequence")).longValue()).isEqualTo(2L);
            var nested = new org.springframework.transaction.support.TransactionTemplate(eventTransactions);
            nested.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED);
            var next = Map.<String, Object>of("stateField", "name", "fromStates", List.of("Approved"), "toState", "Final");
            assertThatThrownBy(() -> nested.execute(status -> MetaContext.runWithCommandPermitPlan("ALL", 1L,
                    contract.modelCode(), allowedPid, () -> stateCheck.executeStateCheckPhase(command, Map.of(), principal.tenantId(), request, next))))
                    .isInstanceOf(com.auraboot.framework.exception.ConflictException.class);
            assertThat(sourceEvents()).hasSize(1);
            long id = eventJdbc.queryForObject("SELECT id FROM mt_" + contract.modelCode() + " WHERE pid = ? AND tenant_id = ?",
                    Long.class, allowedPid, principal.tenantId()); request.setTargetRecordId(Long.toString(id));
            try {
                eventFaultObserver.fail = true;
                assertThatThrownBy(() -> nested.execute(status -> stateCheck.executeStateCheckPhase(command, Map.of(), principal.tenantId(), request, next)))
                        .isInstanceOf(com.auraboot.framework.exception.BusinessException.class);
            } finally { eventFaultObserver.fail = false; }
            assertThat(data.getById(contract.modelCode(), allowedPid)).containsEntry("name", "Approved");
            assertThat(sourceEvents()).hasSize(1);
            stateCheck.executeStateCheckPhase(command, Map.of(), principal.tenantId(), request, next);
            assertThat(sourceEvents()).hasSize(2);
            assertThat(((Number) sourceEvents().getLast().get("event_sequence")).longValue()).isEqualTo(3L);
            return null;
        });
    }

    @Test void commandRejectedDeleteRollsBackItsCapturedSourceEvent() {
        publishEvents();
        var rule = new com.auraboot.framework.meta.entity.BindingRule();
        rule.setRuleType("FIELD_MAP"); rule.setTargetModel(contract.modelCode());
        rule.setSourceField("name"); rule.setTargetField("name");
        var request = new com.auraboot.framework.meta.dto.CommandExecuteRequest();
        request.setOperationType("delete"); request.setTargetRecordId(allowedPid);
        var nested = new org.springframework.transaction.support.TransactionTemplate(eventTransactions);
        nested.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED);
        assertThatThrownBy(() -> nested.execute(status -> MetaContext.runWithCommandPermitScope("NONE", () ->
                fieldMap.executeFieldMapPhase(List.of(rule), Map.of(), principal.tenantId(), request))))
                .isInstanceOf(com.auraboot.framework.exception.BusinessException.class);
        assertThat(sourceEvents()).isEmpty();
        assertThat(MetaContext.runWithCommandPermitScope("ALL", () -> data.getById(contract.modelCode(), allowedPid)))
                .containsEntry("name", "Visible");
    }

    @Test void commandSideEffectOriginsShareVersionsAndOwnerRollback() {
        publishEvents();
        MetaContext.runWithCommandPermitScope("ALL", () -> {
            var single = Map.<String, Object>of("sideEffects", List.of(Map.of("action", "update_record", "targetModel", contract.modelCode(),
                    "targetIdField", "target", "fieldMapping", Map.of("name", "Side effect"))));
            sideEffects.executeSideEffectPhase(single, Map.of("target", allowedPid), principal.tenantId(), MetaContext.getCurrentUserId(), null, null, null);
            assertThat(data.getById(contract.modelCode(), allowedPid)).containsEntry("name", "Side effect");
            assertThat(sourceEvents()).hasSize(1);
            var batch = Map.<String, Object>of("sideEffects", List.of(Map.of("action", "batch_update_record", "targetModel", contract.modelCode(),
                    "sourceField", "items", "targetIdField", "target", "fieldMapping", Map.of("name", "${item.name}"))));
            sideEffects.executeSideEffectPhase(batch, Map.of("items", List.of(Map.of("target", allowedPid, "name", "Batch side"),
                    Map.of("target", deniedPid, "name", "Other side"))), principal.tenantId(), MetaContext.getCurrentUserId(), null, null, null);
            assertThat(sourceEvents()).hasSize(3);
            assertThat(sourceEvents().stream().filter(row -> allowedPid.equals(row.get("subject")))
                    .map(row -> ((Number) row.get("event_sequence")).longValue()).toList()).containsExactly(2L, 3L);
            data.create(contract.modelCode(), new HashMap<>(Map.of("name", "Aggregate child one", "zone", "A", "counter", 2, "related", allowedPid)));
            data.create(contract.modelCode(), new HashMap<>(Map.of("name", "Aggregate child two", "zone", "A", "counter", 3, "related", allowedPid)));
            var aggregate = Map.<String, Object>of("sideEffects", List.of(Map.of("action", "aggregate", "targetModel", contract.modelCode(),
                    "childModel", contract.modelCode(), "childField", "counter", "parentField", "counter", "parentFk", "related", "function", "sum")));
            sideEffects.executeSideEffectPhase(aggregate, Map.of("related", allowedPid), principal.tenantId(), MetaContext.getCurrentUserId(), null, null, null);
            assertThat(((Number) data.getById(contract.modelCode(), allowedPid).get("counter")).intValue()).isEqualTo(5);
            assertThat(sourceEvents()).hasSize(6);
            assertThat(sourceEvents().stream().filter(row -> allowedPid.equals(row.get("subject")))
                    .map(row -> ((Number) row.get("event_sequence")).longValue()).toList()).containsExactly(2L, 3L, 4L);
            var nested = new org.springframework.transaction.support.TransactionTemplate(eventTransactions);
            nested.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED);
            try {
                eventFaultObserver.fail = true;
                assertThatThrownBy(() -> nested.execute(status -> {
                    sideEffects.executeSideEffectPhase(single, Map.of("target", allowedPid), principal.tenantId(), MetaContext.getCurrentUserId(), null, null, null);
                    return null;
                })).isInstanceOf(com.auraboot.framework.exception.BusinessException.class);
            } finally { eventFaultObserver.fail = false; }
            assertThat(data.getById(contract.modelCode(), allowedPid)).containsEntry("name", "Batch side");
            assertThat(sourceEvents()).hasSize(6);
            return null;
        });
    }

    @Test void machineSideEffectsCannotExpandFieldOrProspectiveRowAuthority() {
        publishEvents();
        var previous = MetaContext.snapshot();
        MetaContext.setContext(principal.tenantId(), null, principal.subjectPid(), "machine");
        try (var machine = MachineDataAccessContext.begin(principal);
             var permission = authorizer.authorize(principal, contract.code(), Operation.UPDATE).openScope()) {
            var allowed = Map.<String, Object>of("sideEffects", List.of(Map.of("action", "update_record", "targetModel", contract.modelCode(),
                    "targetIdField", "target", "fieldMapping", Map.of("name", "Machine side"))));
            sideEffects.executeSideEffectPhase(allowed, Map.of("target", allowedPid), principal.tenantId(), null, null, null, null);
            assertThat(sourceEvents()).hasSize(1);
            for (var mapping : List.of(Map.of("secret", "Must not change"), Map.of("zone", "B"))) {
                var denied = Map.<String, Object>of("sideEffects", List.of(Map.of("action", "update_record", "targetModel", contract.modelCode(),
                        "targetIdField", "target", "fieldMapping", mapping)));
                assertThatThrownBy(() -> sideEffects.executeSideEffectPhase(denied, Map.of("target", allowedPid), principal.tenantId(), null, null, null, null))
                        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            }
            assertThat(sourceEvents()).hasSize(1);
            assertThat(eventJdbc.queryForMap("SELECT name, zone, secret FROM mt_" + contract.modelCode() + " WHERE pid = ? AND tenant_id = ?",
                    allowedPid, principal.tenantId())).containsEntry("name", "Machine side").containsEntry("zone", "A").containsEntry("secret", "Private");
        } finally { MetaContext.restore(previous); }
    }

    @Test void cascadeDeletesAreTenantBoundRecursiveAndAtomic() {
        verifyCascade(false);
    }

    @Test void softCascadeDeletesKeepTombstonesAndOwnerEvents() {
        verifyCascade(true);
    }

    private void verifyCascade(boolean softDelete) {
        publishEvents();
        var definition = models.getModelDefinition(contract.modelCode()).orElseThrow();
        assertThat(definition.isSoftDelete()).isEqualTo(softDelete);
        MetaContext.runWithCommandPermitScope("ALL", () -> {
            String child = data.create(contract.modelCode(), new HashMap<>(Map.of("name", "Child", "zone", "A", "related", allowedPid))).get("pid").toString();
            String sibling = data.create(contract.modelCode(), new HashMap<>(Map.of("name", "Sibling", "zone", "A", "related", allowedPid))).get("pid").toString();
            String grandchild = data.create(contract.modelCode(), new HashMap<>(Map.of("name", "Grandchild", "zone", "A", "related", child))).get("pid").toString();
            String foreignPid = UniqueIdGenerator.generate();
            eventJdbc.update("INSERT INTO " + definition.getTableName() + " (pid, tenant_id, name, zone, related) VALUES (?, ?, ?, ?, ?)",
                    foreignPid, principal.tenantId() + 1000000L, "Other tenant", "A", child);
            var leaf = Map.<String, Object>of("childModel", contract.modelCode(), "parentField", "related");
            var config = Map.<String, Object>of("cascadeDelete", List.of(Map.of("childModel", contract.modelCode(),
                    "parentField", "related", "cascadeDelete", List.of(leaf))));
            var request = new CommandExecuteRequest(); request.setTargetRecordId(allowedPid);
            assertThatThrownBy(() -> cascadeDeletes.executeCascadeDeletePhase(config, principal.tenantId() + 1, request))
                    .isInstanceOf(AccessDeniedException.class);
            assertThat(sourceEvents()).hasSize(3);
            var nested = new org.springframework.transaction.support.TransactionTemplate(eventTransactions);
            nested.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED);
            try {
                eventFaultObserver.failAfter = 2;
                assertThatThrownBy(() -> nested.execute(status -> {
                    cascadeDeletes.executeCascadeDeletePhase(config, principal.tenantId(), request); return null;
                })).isInstanceOf(com.auraboot.framework.exception.BusinessException.class);
            } finally { eventFaultObserver.failAfter = -1; }
            assertThat(sourceEvents()).hasSize(3);
            for (String pid : List.of(child, sibling, grandchild)) assertThat(data.getById(contract.modelCode(), pid)).containsEntry("pid", pid);
            cascadeDeletes.executeCascadeDeletePhase(config, principal.tenantId(), request);
            assertThat(sourceEvents()).hasSize(6);
            assertThat(sourceEvents().subList(3, 6)).allSatisfy(row -> {
                assertThat(row.get("event_type")).isEqualTo("open.resource." + contract.code() + ".deleted");
                assertThat(((Number) row.get("event_sequence")).longValue()).isEqualTo(2L);
            });
            for (String pid : List.of(child, sibling, grandchild)) {
                if (softDelete) {
                    var tombstone = eventJdbc.queryForMap("SELECT deleted_flag, row_version FROM " + definition.getTableName() + " WHERE pid = ?", pid);
                    assertThat(tombstone).containsEntry("deleted_flag", true);
                    assertThat(((Number) tombstone.get("row_version")).longValue()).isEqualTo(2L);
                }
                else assertThat(eventJdbc.queryForObject("SELECT COUNT(*) FROM " + definition.getTableName() + " WHERE pid = ?", Long.class, pid)).isZero();
            }
            assertThat(eventJdbc.queryForObject("SELECT COUNT(*) FROM " + definition.getTableName() + " WHERE pid = ?", Long.class, foreignPid)).isEqualTo(1L);
            assertThat(data.getById(contract.modelCode(), allowedPid)).containsEntry("name", "Visible");
            // Repeating a soft cascade ignores tombstones and must not emit duplicate deletes.
            cascadeDeletes.executeCascadeDeletePhase(config, principal.tenantId(), request);
            assertThat(sourceEvents()).hasSize(6);
            return null;
        });
    }

    @Test void softBatchDeleteKeepsTombstonesAndRollsBackPartialFailure() {
        var definition = models.getModelDefinition(contract.modelCode()).orElseThrow();
        assertThat(definition.isSoftDelete()).isTrue();
        publishEvents();
        MetaContext.runWithCommandPermitScope("ALL", () -> {
            var nested = new org.springframework.transaction.support.TransactionTemplate(eventTransactions);
            nested.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED);
            assertThatThrownBy(() -> nested.execute(status -> {
                data.batchDelete(contract.modelCode(), List.of(allowedPid, UniqueIdGenerator.generate()));
                return null;
            })).isInstanceOf(com.auraboot.framework.meta.exception.MetaRecordNotFoundException.class);
            assertThat(sourceEvents()).isEmpty();
            assertThat(data.getById(contract.modelCode(), allowedPid)).containsEntry("name", "Visible");
            data.batchDelete(contract.modelCode(), List.of(allowedPid, deniedPid));
            assertThat(sourceEvents()).hasSize(2);
            var tombstones = eventJdbc.queryForList("SELECT pid, deleted_flag, row_version FROM " + definition.getTableName()
                    + " WHERE tenant_id = ? ORDER BY pid", principal.tenantId());
            assertThat(tombstones).hasSize(2).allSatisfy(row -> {
                assertThat(row.get("deleted_flag")).isEqualTo(true);
                assertThat(((Number) row.get("row_version")).longValue()).isEqualTo(2L);
            });
            assertThat(data.list(contract.modelCode(), DynamicQueryRequest.builder().pageNum(1).pageSize(100).build()).getTotal()).isZero();
            assertThat(sourceEvents().stream().map(row -> ((Number) row.get("event_sequence")).longValue()).toList())
                    .containsExactly(2L, 2L);
            return null;
        });
    }

    @Test void batchAndImportRowsHaveSavepointsAndUseOwnerCreate() throws Exception {
        publishEvents();
        var importFile = java.nio.file.Files.createTempFile("aura-model-event-import-", ".json");
        try {
            java.nio.file.Files.writeString(importFile, "[{\"name\":\"Imported\",\"zone\":\"A\",\"counter\":0},{\"name\":\"Invalid import\",\"zone\":\"A\",\"counter\":\"invalid-integer\"}]");
            MetaContext.runWithCommandPermitScope("ALL", () -> {
                var batch = data.batchCreate(contract.modelCode(), List.of(new HashMap<>(Map.of("name", "Batch created", "zone", "A", "counter", 0)),
                        new HashMap<>(Map.of("name", "Invalid batch", "zone", "A", "counter", "invalid-integer"))));
                assertThat(batch.getSuccess()).isEqualTo(1); assertThat(batch.getFailed()).isEqualTo(1);
                var imported = data.importData(contract.modelCode(), DataImportRequest.builder().filePath(importFile.toString())
                        .format(DataImportRequest.ImportFormat.JSON).batchSize(2).build());
                assertThat(imported.getSuccessCount()).isEqualTo(1); assertThat(imported.getFailedCount()).isEqualTo(1);
                assertThat(sourceEvents()).hasSize(2);
                var updated = data.batchUpdate(contract.modelCode(), List.of(new HashMap<>(Map.of("pid", allowedPid, "name", "Batch updated")),
                        new HashMap<>(Map.of("pid", deniedPid, "counter", "invalid-integer"))));
                assertThat(updated.getSuccess()).isEqualTo(1); assertThat(updated.getFailed()).isEqualTo(1);
                assertThat(sourceEvents()).hasSize(3);
                assertThat(data.list(contract.modelCode(), DynamicQueryRequest.builder().pageNum(1).pageSize(100).build()).getTotal()).isEqualTo(4);
                return null;
            });
        } finally { java.nio.file.Files.deleteIfExists(importFile); }
    }

    @Test void observerFailureRollsBackEveryBatchRowBeforeReportingFailure() {
        publishEvents();
        MetaContext.runWithCommandPermitScope("ALL", () -> {
            try {
                eventFaultObserver.fail = true;
                var failed = data.batchCreate(contract.modelCode(), List.of(new HashMap<>(Map.of("name", "Failed one", "zone", "A")),
                        new HashMap<>(Map.of("name", "Failed two", "zone", "A"))));
                assertThat(failed.getSuccess()).isZero(); assertThat(failed.getFailed()).isEqualTo(2);
                assertThat(sourceEvents()).isEmpty();
                assertThat(data.list(contract.modelCode(), DynamicQueryRequest.builder().pageNum(1).pageSize(100).build()).getTotal()).isEqualTo(2);
                var before = data.getById(contract.modelCode(), allowedPid);
                var failedUpdate = data.batchUpdate(contract.modelCode(), List.of(new HashMap<>(Map.of("pid", allowedPid, "name", "Failed update"))));
                assertThat(failedUpdate.getFailed()).isEqualTo(1);
                assertThat(data.getById(contract.modelCode(), allowedPid)).containsEntry("name", before.get("name"))
                        .containsEntry("row_version", before.get("row_version"));
                assertThat(sourceEvents()).isEmpty();
                var failedJointCreate = data.saveWithRelations(contract.modelCode(), JointSubTableSaveRequest.builder()
                        .masterData(new HashMap<>(Map.of("name", "Failed joint create", "zone", "A"))).build());
                assertThat(failedJointCreate.getSuccess()).isFalse();
                var failedJointUpdate = data.saveWithRelations(contract.modelCode(), JointSubTableSaveRequest.builder()
                        .masterData(new HashMap<>(Map.of("pid", allowedPid, "name", "Failed joint update"))).build());
                assertThat(failedJointUpdate.getSuccess()).isFalse();
                assertThat(data.getById(contract.modelCode(), allowedPid)).containsEntry("name", before.get("name"))
                        .containsEntry("row_version", before.get("row_version"));
                assertThat(data.list(contract.modelCode(), DynamicQueryRequest.builder().pageNum(1).pageSize(100).build()).getTotal()).isEqualTo(2);
                assertThat(sourceEvents()).isEmpty();
            } finally { eventFaultObserver.fail = false; }
            var recovered = data.batchCreate(contract.modelCode(), List.of(new HashMap<>(Map.of("name", "Recovered", "zone", "A"))));
            assertThat(recovered.getSuccess()).isEqualTo(1); assertThat(sourceEvents()).hasSize(1);
            return null;
        });
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"hard", "soft"})
    void humanSingleCrudUsesTheSamePublishedSourceEventHook(String mode) {
        boolean softDelete = "soft".equals(mode);
        var definition = models.getModelDefinition(contract.modelCode()).orElseThrow();
        definition.getExtension().put("softDelete", softDelete);
        var navigationFields = new ArrayList<>(definition.getFields());
        navigationFields.add(FieldDefinition.builder().code("children").dataType("STRING").virtualType("transient").build());
        definition.setFields(navigationFields);
        models.saveDefinition(definition);
        var modelRow = modelMapper.findCurrentByCode(contract.modelCode());
        var relationCreate = new MetaFieldCreateRequest();
        relationCreate.setCode("children");
        relationCreate.setDataType("string");
        relationCreate.setStatus("published");
        relationCreate.setRefTarget(Map.of("targetEntity", contract.modelCode(), "targetTable", definition.getTableName(),
                "valueField", "pid", "targetField", "related", "bidirectional", Map.of("relationType", "ONE_TO_MANY")));
        var relationField = metadataFields.create(relationCreate);
        models.bindFieldToModel(modelRow.getId(), relationField.getId(), 1, false, true, true, null, null, null, null);
        models.refreshModelCache(contract.modelCode());
        assertThat(models.getModelDefinition(contract.modelCode()).orElseThrow().getRelations()).hasSize(1);
        publishEvents();
        MetaContext.runWithCommandPermitScope("ALL", () -> {
            String pid = data.create(contract.modelCode(), new HashMap<>(Map.of("name", "Human", "zone", "A")))
                    .get("pid").toString();
            data.update(contract.modelCode(), pid, new HashMap<>(Map.of("name", "Human update")));
            data.delete(contract.modelCode(), pid);
            assertThat(sourceEvents()).hasSize(3);
            var first = data.create(contract.modelCode(), new HashMap<>(Map.of("name", "Child one", "zone", "A", "related", allowedPid)));
            var second = data.create(contract.modelCode(), new HashMap<>(Map.of("name", "Child two", "zone", "A", "related", allowedPid)));
            var replacement = JointSubTableSaveRequest.builder()
                    .masterData(new HashMap<>(Map.of("pid", allowedPid, "name", "Joint master")))
                    .tables(Map.of("children", List.of(Map.of("name", "Replacement", "zone", "A"))))
                    .replaceExisting(true).build();
            try {
                eventFaultObserver.failAfter = 3;
                var failed = data.saveWithRelations(contract.modelCode(), replacement);
                assertThat(failed.getSuccess()).isFalse();
                assertThat(failed.getErrors()).containsExactly("Sub-table 'children': Fixture event persistence failure");
            } finally { eventFaultObserver.failAfter = -1; }
            // Master success is retained, while the whole failed replacement is restored.
            assertThat(sourceEvents()).hasSize(6);
            assertThat(data.getById(contract.modelCode(), first.get("pid").toString())).containsEntry("name", "Child one");
            assertThat(data.getById(contract.modelCode(), second.get("pid").toString())).containsEntry("name", "Child two");
            var saved = data.saveWithRelations(contract.modelCode(), replacement);
            assertThat(saved.getSuccess()).as("joint errors: %s", saved.getErrors()).isTrue();
            assertThat(saved.getSubTableCounts()).containsEntry("children", 1);
            assertThat(saved.getSavedRecords().get("children").get(0)).containsEntry("related", allowedPid);
            var oldChildren = eventJdbc.queryForList("SELECT pid, deleted_flag, row_version FROM " + definition.getTableName()
                    + " WHERE tenant_id = ? AND pid IN (?, ?) ORDER BY pid", principal.tenantId(), first.get("pid"), second.get("pid"));
            if (softDelete) {
                assertThat(oldChildren).hasSize(2).allSatisfy(row -> {
                    assertThat(row.get("deleted_flag")).isEqualTo(true);
                    assertThat(((Number) row.get("row_version")).longValue()).isEqualTo(2L);
                });
            } else { assertThat(oldChildren).isEmpty(); }
            // Empty replacement deletes only current children, never old tombstones again.
            var empty = data.saveWithRelations(contract.modelCode(), JointSubTableSaveRequest.builder()
                    .masterData(new HashMap<>(Map.of("pid", allowedPid, "name", "Empty replacement")))
                    .tables(Map.of("children", List.of())).replaceExisting(true).build());
            assertThat(empty.getSuccess()).as("empty replacement errors: %s", empty.getErrors()).isTrue();
            assertThat(empty.getSubTableCounts()).containsEntry("children", 0);
            assertThat(sourceEvents()).hasSize(12);
            if (softDelete) {
                assertThat(eventJdbc.queryForList("SELECT pid, deleted_flag, row_version FROM " + definition.getTableName()
                        + " WHERE tenant_id = ? AND pid IN (?, ?) ORDER BY pid", principal.tenantId(), first.get("pid"), second.get("pid")))
                        .isEqualTo(oldChildren);
            }
            try {
                eventFaultObserver.failAfter = 3;
                var partial = data.saveWithRelations(contract.modelCode(), JointSubTableSaveRequest.builder()
                        .masterData(new HashMap<>(Map.of("pid", allowedPid, "name", "Partial children")))
                        .tables(Map.of("children", List.of(Map.of("name", "Retained child", "zone", "A"),
                                Map.of("name", "Rolled back child", "zone", "A")))).build());
                assertThat(partial.getSuccess()).isFalse();
                assertThat(partial.getSubTableCounts()).containsEntry("children", 1);
                assertThat(partial.getSavedRecords().get("children")).hasSize(1);
                assertThat(partial.getSubTableErrors().get("children")).singleElement().satisfies(error -> {
                    assertThat(error.getRowIndex()).isEqualTo(1);
                    assertThat(error.getMessage()).isEqualTo("Fixture event persistence failure");
                });
            } finally { eventFaultObserver.failAfter = -1; }
            assertThat(eventJdbc.queryForObject("SELECT count(*) FROM " + definition.getTableName()
                    + " WHERE tenant_id = ? AND name = ?", Long.class, principal.tenantId(), "Rolled back child")).isZero();
            assertThat(eventJdbc.queryForObject("SELECT count(*) FROM " + definition.getTableName()
                    + " WHERE tenant_id = ? AND name = ? AND related = ?", Long.class, principal.tenantId(), "Retained child", allowedPid))
                    .isEqualTo(1L);
            assertThat(data.getById(contract.modelCode(), deniedPid)).containsEntry("zone", "B");
            return null;
        });
        assertThat(sourceEvents()).hasSize(14);
    }

    @Test void guardedDeleteFailureRollsBackItsBeforeDeleteEvent() {
        publishEvents();
        var nested = new org.springframework.transaction.support.TransactionTemplate(eventTransactions);
        nested.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED);
        var previous = MetaContext.snapshot();
        MetaContext.setContext(principal.tenantId(), null, principal.subjectPid(), "machine");
        try (var machine = MachineDataAccessContext.begin(principal);
             var permission = authorizer.authorize(principal, contract.code(), Operation.DELETE).openScope()) {
            var row = data.getById(contract.modelCode(), allowedPid);
            long invalidVersion = ((Number) row.get("row_version")).longValue() + 10;
            assertThatThrownBy(() -> nested.execute(status -> {
                try (var version = MachineDataAccessContext.withExpectedVersion(contract.modelCode(), allowedPid, invalidVersion)) {
                    data.delete(contract.modelCode(), allowedPid);
                }
                return null;
            })).isInstanceOf(com.auraboot.framework.exception.ConflictException.class);
            assertThat(sourceEvents()).isEmpty();
            assertThat(data.getById(contract.modelCode(), allowedPid)).containsEntry("pid", allowedPid);
        } finally { MetaContext.restore(previous); }
    }

    @Test void modelMutationRollbackRemovesSourceEventAndIdempotencyClaim() {
        publishEvents();
        var nested = new org.springframework.transaction.support.TransactionTemplate(eventTransactions);
        nested.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED);
        var previous = MetaContext.snapshot();
        MetaContext.setContext(principal.tenantId(), null, principal.subjectPid(), "machine");
        try (var machine = MachineDataAccessContext.begin(principal)) {
            String pid = nested.execute(status -> {
                var result = resources.write(principal, contract.code(), Operation.CREATE, null, "event-rollback-key", null,
                        Map.of("name", "Rolled back", "zone", "A"));
                assertThat(sourceEvents()).hasSize(1);
                status.setRollbackOnly();
                return result.body().get("pid").toString();
            });
            assertThat(sourceEvents()).isEmpty();
            assertThat(eventJdbc.queryForObject("SELECT COUNT(*) FROM " + models.getModelDefinition(contract.modelCode())
                    .orElseThrow().getTableName() + " WHERE tenant_id = ? AND pid = ?", Long.class, principal.tenantId(), pid)).isZero();
            var retry = resources.write(principal, contract.code(), Operation.CREATE, null, "event-rollback-key", null,
                    Map.of("name", "Rolled back", "zone", "A"));
            assertThat(retry.replay()).isFalse();
            assertThat(sourceEvents()).hasSize(1);
        } finally { MetaContext.restore(previous); }
    }

    @Test void liveGrantBoundsSingleReadsListsCountsAndFieldInference() {
        MetaContext.setContext(principal.tenantId(), null, principal.subjectPid(), "machine");
        try (var machine = MachineDataAccessContext.begin(principal)) {
            assertThatThrownBy(() -> data.getById(contract.modelCode(), allowedPid)).isInstanceOf(AccessDeniedException.class);
            try (var permit = authorizer.authorize(principal, contract.code(), Operation.LIST).openScope()) {
                var row = data.getById(contract.modelCode(), allowedPid);
                assertThat(row).containsEntry("name", "Visible").doesNotContainKey("secret");
                assertThatThrownBy(() -> data.getById(contract.modelCode(), deniedPid)).isInstanceOf(com.auraboot.framework.meta.exception.MetaRecordNotFoundException.class);
                var result = data.list(contract.modelCode(), DynamicQueryRequest.builder().pageNum(1).pageSize(10).build());
                assertThat(result.getTotal()).isEqualTo(1);
                assertThat(result.getRecords()).hasSize(1);
                assertThat(result.getRecords().getFirst()).doesNotContainKey("secret");
                assertThatThrownBy(() -> data.list(contract.modelCode(), DynamicQueryRequest.builder().pageNum(1).pageSize(10)
                        .conditions(List.of(QueryCondition.builder().fieldName("secret").operator(QueryCondition.Operator.EQ).value("Private").build())).build()))
                        .isInstanceOf(AccessDeniedException.class);
                assertThatThrownBy(() -> MetaContext.runWithCommandPermitScope("ALL", () -> data.getById(contract.modelCode(), deniedPid)))
                        .isInstanceOf(com.auraboot.framework.meta.exception.MetaRecordNotFoundException.class);
            }
        } finally { applyTestMetaContext(); }
    }

    @Test void referenceWritesRequireTheDeclaredModelAndCurrentTargetReadAuthority() {
        var fields = new LinkedHashMap<>(contract.fields());
        fields.put("related", new Field("related", Type.PID, true, true, false, true, Set.of(), false,
                contract.code(), Map.of(), null));
        contract = new OpenApiResourceContract(contract.code(), contract.modelCode(), 2, contract.operations(), contract.scopes(),
                contract.dataPolicy(), fields, 50, 8, 5000, Set.of());
        publications.saveDraft(contract.code(), new OpenApiPublicationService.DraftRequest(1, contract));
        publications.publish(contract.code(), 2);
        var grant = new OpenApiInstallationPolicy(contract.operations(), fields.keySet(), Set.of("name", "zone", "related"),
                List.of(new OpenApiInstallationPolicy.RowConstraint("zone", List.of("A"))));
        publications.grant(principal.installationPid(), contract.code(), grant);
        String otherModel = contract.modelCode() + "_other";
        models.saveDefinition(ModelDefinition.builder().code(otherModel).tableName("mt_" + otherModel)
                .displayName("Other reference target").status("published").sourceType("physical").primaryKey("id")
                .capabilities(ModelCapabilities.fullPhysical().toBuilder().filterableFields(List.of("name", "zone")).sortableFields(List.of("name", "zone")).build())
                .fields(List.of(FieldDefinition.builder().code("name").columnName("name").dataType("STRING").displayName("Name").filterable(true).sortable(true).build(),
                        FieldDefinition.builder().code("zone").columnName("zone").dataType("STRING").displayName("Zone").filterable(true).sortable(true).build())).build());
        assertThat(schemas.createTableByModel(otherModel).isSuccess()).isTrue();
        String otherPid = MetaContext.runWithCommandPermitScope("ALL", () -> data.create(otherModel,
                new HashMap<>(Map.of("name", "Other model visible", "zone", "A")))).get("pid").toString();
        var targetFields = new LinkedHashMap<>(fields); targetFields.remove("related");
        var otherContract = new OpenApiResourceContract(contract.code() + ".other", otherModel, 1,
                Set.of(Operation.GET), Map.of(Operation.GET, contract.scopes().get(Operation.GET)),
                contract.dataPolicy(), targetFields, 50, 8, 5000, Set.of());
        publications.saveDraft(otherContract.code(), new OpenApiPublicationService.DraftRequest(0, otherContract));
        publications.publish(otherContract.code(), 1);
        publications.grant(principal.installationPid(), otherContract.code(), new OpenApiInstallationPolicy(
                Set.of(Operation.GET), targetFields.keySet(), Set.of(), grant.rowConstraints()));
        var mismatchedFields = new LinkedHashMap<>(fields);
        mismatchedFields.put("related", new Field("related", Type.PID, true, true, false, true, Set.of(), false,
                otherContract.code(), Map.of(), null));
        var mismatchedContract = new OpenApiResourceContract(contract.code() + ".mismatch", contract.modelCode(), 1,
                contract.operations(), contract.scopes(), contract.dataPolicy(), mismatchedFields, 50, 8, 5000, Set.of());
        publications.saveDraft(mismatchedContract.code(), new OpenApiPublicationService.DraftRequest(0, mismatchedContract));
        var oldSnapshot = publications.publishedForAdministration(contract.code());
        assertThatThrownBy(() -> publications.publish(mismatchedContract.code(), 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Reference target requires GET on its declared model");
        assertThat(publications.active(principal.tenantId(), mismatchedContract.code())).isEmpty();
        assertThat(publications.snapshot(principal.tenantId(), mismatchedContract.code(), 1)).isEmpty();
        assertThat(publications.installationGrants(principal.installationPid())).noneMatch(view -> view.code().equals(mismatchedContract.code()));
        assertThat(publications.publishedForAdministration(contract.code())).isEqualTo(oldSnapshot);
        MetaContext.setContext(principal.tenantId(), null, principal.subjectPid(), "machine");
        try (var machine = MachineDataAccessContext.begin(principal)) {
            var created = resources.write(principal, contract.code(), Operation.CREATE, null, "reference-ok-001", null,
                    Map.of("name", "Related row", "zone", "A", "related", allowedPid));
            assertThat(created.body()).containsEntry("related", allowedPid);
            String ownerPid = created.body().get("pid").toString();
            var originalOwner = resources.get(principal, contract.code(), ownerPid);
            assertThat(originalOwner.body()).isEqualTo(created.body());
            assertThatThrownBy(() -> resources.write(principal, contract.code(), Operation.UPDATE, ownerPid,
                    "reference-patch-hidden", created.headers().get("ETag"), Map.of("related", deniedPid)))
                    .isInstanceOf(AccessDeniedException.class).hasMessageContaining("Reference target");
            var afterHidden = resources.get(principal, contract.code(), ownerPid);
            assertThat(afterHidden.body()).isEqualTo(originalOwner.body());
            assertThat(afterHidden.headers().get("ETag")).isEqualTo(originalOwner.headers().get("ETag"));
            assertThatThrownBy(() -> resources.write(principal, contract.code(), Operation.CREATE, null, "reference-deny-01", null,
                    Map.of("zone", "A", "related", deniedPid)))
                    .isInstanceOf(AccessDeniedException.class).hasMessageContaining("Reference target");
            applyTestMetaContext();
            var operations = new HashSet<>(contract.operations()); operations.remove(Operation.GET);
            publications.grant(principal.installationPid(), contract.code(), new OpenApiInstallationPolicy(operations,
                    grant.readableFields(), grant.writableFields(), grant.rowConstraints()));
            MetaContext.setContext(principal.tenantId(), null, principal.subjectPid(), "machine");
            assertThatThrownBy(() -> resources.write(principal, contract.code(), Operation.CREATE, null, "reference-no-get1", null,
                    Map.of("zone", "A", "related", allowedPid))).isInstanceOf(AccessDeniedException.class);
            Map<String, Object> patch = Map.of("name", "Reference restored", "related", allowedPid);
            assertThatThrownBy(() -> resources.write(principal, contract.code(), Operation.UPDATE, ownerPid,
                    "reference-patch-restore", created.headers().get("ETag"), patch)).isInstanceOf(AccessDeniedException.class);
            applyTestMetaContext();
            publications.grant(principal.installationPid(), contract.code(), grant);
            MetaContext.setContext(principal.tenantId(), null, principal.subjectPid(), "machine");
            var afterRestoredAuthority = resources.get(principal, contract.code(), ownerPid);
            assertThat(afterRestoredAuthority.body()).isEqualTo(originalOwner.body());
            assertThat(afterRestoredAuthority.headers().get("ETag")).isEqualTo(originalOwner.headers().get("ETag"));
            var updated = resources.write(principal, contract.code(), Operation.UPDATE, ownerPid,
                    "reference-patch-restore", created.headers().get("ETag"), patch);
            assertThat(updated.status()).isEqualTo(200);
            assertThat(updated.replay()).isFalse();
            assertThat(updated.body()).containsEntry("pid", ownerPid).containsEntry("name", "Reference restored").containsEntry("related", allowedPid);
            assertThat(updated.headers().get("ETag")).isNotEqualTo(created.headers().get("ETag"));
            var replayed = resources.write(principal, contract.code(), Operation.UPDATE, ownerPid,
                    "reference-patch-restore", created.headers().get("ETag"), patch);
            assertThat(replayed.replay()).isTrue();
            assertThat(replayed.body()).isEqualTo(updated.body());
            assertThat(replayed.headers()).isEqualTo(updated.headers());
            var readableOther = resources.get(principal, otherContract.code(), otherPid);
            assertThat(readableOther.status()).isEqualTo(200);
            assertThat(readableOther.body()).containsEntry("pid", otherPid).containsEntry("name", "Other model visible");
            var ownerUnchanged = resources.get(principal, contract.code(), ownerPid);
            assertThat(ownerUnchanged.body()).isEqualTo(updated.body());
            assertThat(ownerUnchanged.headers().get("ETag")).isEqualTo(updated.headers().get("ETag"));
            var otherUnchanged = resources.get(principal, otherContract.code(), otherPid);
            assertThat(otherUnchanged.body()).isEqualTo(readableOther.body());
            assertThat(otherUnchanged.headers().get("ETag")).isEqualTo(readableOther.headers().get("ETag"));
        } finally { applyTestMetaContext(); }
    }

    @Test void scopedWritesRetainRowVersionChecksAndCannotExpandFieldsOrRows() {
        MetaContext.setContext(principal.tenantId(), null, principal.subjectPid(), "machine");
        try (var machine = MachineDataAccessContext.begin(principal);
             var permit = authorizer.authorize(principal, contract.code(), Operation.UPDATE).openScope()) {
            var row = data.getById(contract.modelCode(), allowedPid);
            long version = ((Number) row.get("row_version")).longValue();
            var updated = data.update(contract.modelCode(), allowedPid, new HashMap<>(Map.of("name", "Updated", "_expectedVersion", version)));
            assertThat(updated).containsEntry("name", "Updated").doesNotContainKey("secret");
            assertThatThrownBy(() -> data.update(contract.modelCode(), allowedPid, new HashMap<>(Map.of("name", "Stale", "_expectedVersion", version))))
                    .isInstanceOf(com.auraboot.framework.meta.exception.RecordVersionConflictException.class);
            assertThatThrownBy(() -> data.update(contract.modelCode(), allowedPid, new HashMap<>(Map.of("secret", "Leak"))))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> data.update(contract.modelCode(), deniedPid, new HashMap<>(Map.of("name", "Denied"))))
                    .isInstanceOf(com.auraboot.framework.meta.exception.MetaServiceException.class)
                    .hasRootCauseInstanceOf(com.auraboot.framework.meta.exception.MetaRecordNotFoundException.class);
            assertThatThrownBy(() -> data.update(contract.modelCode(), allowedPid, new HashMap<>(Map.of("zone", "B"))))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> data.delete(contract.modelCode(), allowedPid)).isInstanceOf(AccessDeniedException.class);
        } finally { applyTestMetaContext(); }
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void revocationIsEnforcedAgainstAnAlreadyAuthenticatedPrincipal() throws Exception {
        record Replay(Operation operation, String pid, String key, String ifMatch,
                      Map<String, Object> input, OpenApiResourceService.Result original) { }
        String origin = "credential-" + UniqueIdGenerator.generate();
        String collection = "/api/open/v1/resources/" + contract.code();
        var cases = new java.util.ArrayList<Replay>();
        try {
            MetaContext.runWithCommandPermitScope("ALL", () -> data.update(contract.modelCode(), deniedPid, Map.of("zone", "A")));
            publishEvents();
            MetaContext.setContext(principal.tenantId(), null, principal.subjectPid(), "machine");
            try (var machine = MachineDataAccessContext.begin(principal)) {
                var createInput = Map.<String, Object>of("name", "Revocation create", "zone", "A");
                var created = resources.write(principal, contract.code(), Operation.CREATE, null, origin + "-create", null, createInput);
                assertThat(created.status()).isEqualTo(201);
                cases.add(new Replay(Operation.CREATE, null, origin + "-create", null, createInput, created));
                var beforeUpdate = resources.get(principal, contract.code(), allowedPid);
                var updateInput = Map.<String, Object>of("name", "Revocation update");
                var updated = resources.write(principal, contract.code(), Operation.UPDATE, allowedPid, origin + "-update", beforeUpdate.headers().get("ETag"), updateInput);
                assertThat(updated.status()).isEqualTo(200);
                cases.add(new Replay(Operation.UPDATE, allowedPid, origin + "-update", beforeUpdate.headers().get("ETag"), updateInput, updated));
                var beforeDelete = resources.get(principal, contract.code(), deniedPid);
                var deleted = resources.write(principal, contract.code(), Operation.DELETE, deniedPid, origin + "-delete", beforeDelete.headers().get("ETag"), null);
                assertThat(deleted.status()).isEqualTo(204);
                cases.add(new Replay(Operation.DELETE, deniedPid, origin + "-delete", beforeDelete.headers().get("ETag"), null, deleted));
                for (var replay : cases) {
                    var result = resources.write(principal, contract.code(), replay.operation(), replay.pid(), replay.key(), replay.ifMatch(), replay.input());
                    assertThat(result.replay()).isTrue();
                    assertThat(result.status()).isEqualTo(replay.original().status());
                    assertThat(result.body()).isEqualTo(replay.original().body());
                    assertThat(result.headers()).isEqualTo(replay.original().headers());
                }
            }
            applyTestMetaContext();
            assertThat(sourceEvents()).hasSize(3);
            management.revokeCredential(principal.installationPid(), credentialPid);
            var checks = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
            checks.add(() -> {
                try (var machine = MachineDataAccessContext.begin(principal)) {
                    assertThatThrownBy(() -> authorizer.authorize(principal, contract.code(), Operation.GET)).isInstanceOf(AccessDeniedException.class);
                }
            });
            for (var replay : cases) {
                checks.add(() -> {
                    MetaContext.setContext(principal.tenantId(), null, principal.subjectPid(), "machine");
                    try (var machine = MachineDataAccessContext.begin(principal)) {
                        assertThatThrownBy(() -> resources.write(principal, contract.code(), replay.operation(), replay.pid(), replay.key(), replay.ifMatch(), replay.input()))
                                .isInstanceOf(AccessDeniedException.class);
                    } finally { applyTestMetaContext(); }
                });
                checks.add(() -> {
                    String method = replay.operation() == Operation.CREATE ? "POST" : replay.operation() == Operation.UPDATE ? "PATCH" : "DELETE";
                    String path = replay.pid() == null ? collection : collection + "/" + replay.pid();
                    var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method), path)
                            .header("Authorization", "Bearer " + rawToken).header("X-Request-Id", origin + "-denied-" + replay.operation().code())
                            .header("Idempotency-Key", replay.key());
                    if (replay.ifMatch() != null) request.header("If-Match", replay.ifMatch());
                    if (replay.input() != null) request.contentType("application/json").content(json.writeValueAsString(replay.input()));
                    try {
                        var response = http.perform(request).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized()).andReturn().getResponse();
                        assertThat(json.readTree(response.getContentAsString())).isEqualTo(json.valueToTree(Map.of("success", false, "code", "invalid_token", "message", "invalid_token")));
                        assertThat(response.getHeader("ETag")).isNull();
                        assertThat(response.getHeader("Location")).isNull();
                        assertThat(response.getHeader("Idempotent-Replay")).isNull();
                        assertThat(response.getContentAsString()).doesNotContain(rawToken, "Revocation create", "Revocation update");
                    } finally { applyTestMetaContext(); }
                });
            }
            org.junit.jupiter.api.Assertions.assertAll("Revoked credential cannot recover any stored write result", checks);
            applyTestMetaContext();
            assertThat(eventJdbc.queryForObject("SELECT COUNT(*) FROM ab_application_access_token WHERE tenant_id=? AND pid=? AND revoked_at IS NOT NULL",
                    Long.class, principal.tenantId(), principal.tokenPid())).isEqualTo(1);
            assertThat(eventJdbc.queryForObject("SELECT COUNT(*) FROM ab_open_api_idempotency WHERE tenant_id=? AND route_code LIKE ? AND status='completed'",
                    Long.class, principal.tenantId(), "resource:" + contract.code() + ":%")).isEqualTo(3);
            var createdRow = MetaContext.runWithCommandPermitScope("ALL", () -> data.getById(contract.modelCode(), cases.getFirst().original().body().get("pid").toString()));
            assertThat(createdRow).containsEntry("name", "Revocation create").containsEntry("zone", "A");
            assertThat(((Number) createdRow.get("row_version")).longValue()).isEqualTo(1);
            var updatedRow = MetaContext.runWithCommandPermitScope("ALL", () -> data.getById(contract.modelCode(), allowedPid));
            assertThat(updatedRow).containsEntry("name", "Revocation update").containsEntry("zone", "A");
            assertThat(((Number) updatedRow.get("row_version")).longValue()).isEqualTo(2);
            assertThatThrownBy(() -> MetaContext.runWithCommandPermitScope("ALL", () -> data.getById(contract.modelCode(), deniedPid)))
                    .isInstanceOf(com.auraboot.framework.meta.exception.MetaRecordNotFoundException.class);
            assertThat(sourceEvents()).hasSize(3);
            for (var replay : cases) {
                String requestId = origin + "-denied-" + replay.operation().code();
                assertThat(management.listCallAudits(principal.installationPid(), requestId, null, 20))
                        .singleElement().satisfies(audit -> {
                            assertThat(audit.actorKind()).isEqualTo("unauthenticated");
                            assertThat(audit.status()).isEqualTo(401);
                            assertThat(audit.idempotentReplay()).isNull();
                        });
                assertAuthenticationAudit(requestId, 401, "invalid_token", true,
                        replay.operation() == Operation.CREATE ? "/api/open/v1/resources/{resource}"
                                : "/api/open/v1/resources/{resource}/{pid}",
                        replay.operation() == Operation.CREATE ? "POST" : replay.operation() == Operation.UPDATE ? "PATCH" : "DELETE");
            }
        } finally { cleanupCommittedFixture(); }
    }

    @Test void creationChecksBothFieldAndProspectiveRowAuthorityBeforeInsert() {
        MetaContext.setContext(principal.tenantId(), null, principal.subjectPid(), "machine");
        try (var machine = MachineDataAccessContext.begin(principal);
             var permit = authorizer.authorize(principal, contract.code(), Operation.CREATE).openScope()) {
            var row = data.create(contract.modelCode(), new HashMap<>(Map.of("name", "Created", "zone", "A")));
            assertThat(row).containsEntry("zone", "A").containsEntry("name", "Created").doesNotContainKey("secret");
            assertThatThrownBy(() -> data.create(contract.modelCode(), new HashMap<>(Map.of("name", "Outside", "zone", "B"))))
                    .isInstanceOf(AccessDeniedException.class).hasMessageContaining("row policy");
            assertThatThrownBy(() -> data.create(contract.modelCode(), new HashMap<>(Map.of("name", "Outside", "zone", "A", "secret", "Private"))))
                    .isInstanceOf(AccessDeniedException.class).hasMessageContaining("field write");
            var result = data.list(contract.modelCode(), DynamicQueryRequest.builder().pageNum(1).pageSize(10).build());
            assertThat(result.getTotal()).isEqualTo(2);
        } finally { applyTestMetaContext(); }
    }

    @Test void deletionUsesMachineCasWithoutGrantingCommandMutationAuthority() {
        MetaContext.setContext(principal.tenantId(), null, principal.subjectPid(), "machine");
        try (var machine = MachineDataAccessContext.begin(principal);
             var permit = authorizer.authorize(principal, contract.code(), Operation.DELETE).openScope()) {
            long version = ((Number) data.getById(contract.modelCode(), allowedPid).get("row_version")).longValue();
            try (var stale = MachineDataAccessContext.withExpectedVersion(contract.modelCode(), allowedPid, version + 1)) {
                assertThatThrownBy(() -> data.delete(contract.modelCode(), allowedPid))
                        .isInstanceOf(com.auraboot.framework.exception.ConflictException.class);
            }
            try (var current = MachineDataAccessContext.withExpectedVersion(contract.modelCode(), allowedPid, version)) {
                assertThat(MetaContext.hasCommandPermitScope()).isFalse();
                data.delete(contract.modelCode(), allowedPid);
            }
            assertThatThrownBy(() -> data.getById(contract.modelCode(), allowedPid))
                    .isInstanceOf(com.auraboot.framework.meta.exception.MetaRecordNotFoundException.class);
            assertThatThrownBy(() -> data.delete(contract.modelCode(), deniedPid))
                    .isInstanceOf(com.auraboot.framework.meta.exception.MetaRecordNotFoundException.class);
        } finally { applyTestMetaContext(); }
    }

    @Test void publicCreatePatchDeletePersistAndReplayTheirOriginalProtocolResults() {
        MetaContext.setContext(principal.tenantId(), null, principal.subjectPid(), "machine");
        try (var machine = MachineDataAccessContext.begin(principal)) {
            Map<String, Object> input = Map.of("name", "Public create", "zone", "A");
            var created = resources.write(principal, contract.code(), Operation.CREATE, null, "create-key-0001", null, input);
            assertThat(created.status()).isEqualTo(201); assertThat(created.replay()).isFalse();
            assertThat(created.body()).containsEntry("name", "Public create").doesNotContainKeys("id", "tenant_id", "row_version", "secret");
            String pid = created.body().get("pid").toString();
            var replay = resources.write(principal, contract.code(), Operation.CREATE, null, "create-key-0001", null, input);
            assertThat(replay.replay()).isTrue(); assertThat(replay.status()).isEqualTo(201);
            assertThat(replay.body()).isEqualTo(created.body()); assertThat(replay.headers()).isEqualTo(created.headers());
            var changed = resources.write(principal, contract.code(), Operation.UPDATE, pid, "patch-key-00001", created.headers().get("ETag"), Map.of("name", "Changed"));
            assertThat(changed.status()).isEqualTo(200); assertThat(changed.headers().get("ETag")).isNotEqualTo(created.headers().get("ETag"));
            assertThat(changed.body()).containsEntry("name", "Changed").containsEntry("zone", "A");
            var changedAgain = resources.write(principal, contract.code(), Operation.UPDATE, pid, "patch-key-00001", created.headers().get("ETag"), Map.of("name", "Changed"));
            assertThat(changedAgain.replay()).isTrue(); assertThat(changedAgain.body()).isEqualTo(changed.body());
            assertThat(changedAgain.headers()).isEqualTo(changed.headers());
            var deleted = resources.write(principal, contract.code(), Operation.DELETE, pid, "delete-key-0001", changed.headers().get("ETag"), null);
            assertThat(deleted.status()).isEqualTo(204); assertThat(deleted.body()).isNull();
            var deletedAgain = resources.write(principal, contract.code(), Operation.DELETE, pid, "delete-key-0001", changed.headers().get("ETag"), null);
            assertThat(deletedAgain.replay()).isTrue(); assertThat(deletedAgain.status()).isEqualTo(204); assertThat(deletedAgain.body()).isNull();
            assertThatThrownBy(() -> resources.get(principal, contract.code(), pid)).isInstanceOf(com.auraboot.framework.meta.exception.MetaRecordNotFoundException.class);
        } finally { applyTestMetaContext(); }
    }

    @Test void staleDifferentIntentAndRevokedReplayCannotMutateOrRecoverData() {
        MetaContext.setContext(principal.tenantId(), null, principal.subjectPid(), "machine");
        try (var machine = MachineDataAccessContext.begin(principal)) {
            var original = resources.get(principal, contract.code(), allowedPid);
            var changed = resources.write(principal, contract.code(), Operation.UPDATE, allowedPid, "patch-key-00002", original.headers().get("ETag"), Map.of("name", "Changed"));
            assertThatThrownBy(() -> resources.write(principal, contract.code(), Operation.UPDATE, allowedPid, "patch-key-00002", original.headers().get("ETag"), Map.of("name", "Another")))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                    .satisfies(error -> assertThat(((org.springframework.web.server.ResponseStatusException) error).getStatusCode().value()).isEqualTo(409));
            assertThatThrownBy(() -> resources.write(principal, contract.code(), Operation.UPDATE, allowedPid, "stale-key-00001", original.headers().get("ETag"), Map.of("name", "Stale")))
                    .isInstanceOf(OpenApiPreconditionException.class);
            assertThat(resources.get(principal, contract.code(), allowedPid).body()).isEqualTo(changed.body());
        } finally { applyTestMetaContext(); }
        publications.revoke(principal.installationPid(), contract.code());
        try (var machine = MachineDataAccessContext.begin(principal)) {
            assertThatThrownBy(() -> resources.write(principal, contract.code(), Operation.UPDATE, allowedPid, "patch-key-00002", "invalid", Map.of("name", "Changed")))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    @Test void filteredCompositeCursorOrdersTiesAndNullsAndRejectsChangedInputs() {
        MetaContext.runWithCommandPermitScope("ALL", () -> data.create(contract.modelCode(), new HashMap<>(Map.of("zone", "A"))));
        MetaContext.runWithCommandPermitScope("ALL", () -> data.create(contract.modelCode(), new HashMap<>(Map.of("name", "Visible", "zone", "A"))));
        MetaContext.setContext(principal.tenantId(), null, principal.subjectPid(), "machine");
        try (var machine = MachineDataAccessContext.begin(principal)) {
            List<String> expectedNames = new ArrayList<>(Arrays.asList("Visible", "Visible", null));
            for (String order : List.of("ASC", "DESC")) {
                List<String> seen = new ArrayList<>(); List<Object> pids = new ArrayList<>(); String cursor = null;
                for (int index = 0; index < 4; index++) {
                    var page = resources.list(principal, contract.code(), 1, cursor, null, "name", order);
                    @SuppressWarnings("unchecked") var items = (List<Map<String, Object>>) page.get("items");
                    for (var item : items) { seen.add((String) item.get("name")); pids.add(item.get("pid")); }
                    cursor = (String) page.get("nextCursor"); if (cursor == null) break;
                }
                assertThat(seen).containsExactlyElementsOf("ASC".equals(order) ? expectedNames : Arrays.asList(null, "Visible", "Visible"));
                assertThat(pids).hasSize(3).doesNotHaveDuplicates();
            }
            var first = resources.list(principal, contract.code(), 1, null, null, "name", "ASC");
            String cursor = (String) first.get("nextCursor");
            assertThatThrownBy(() -> resources.list(principal, contract.code(), 2, cursor, null, "name", "ASC")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> resources.list(principal, contract.code(), 1, cursor, null, "name", "DESC")).isInstanceOf(IllegalArgumentException.class);
            var filtered = resources.list(principal, contract.code(), 10, null, "[{\"field\":\"name\",\"operator\":\"EQ\",\"value\":\"Visible\"}]", null, null);
            assertThat((List<?>) filtered.get("items")).hasSize(2);
            assertThatThrownBy(() -> resources.list(principal, contract.code(), 10, null, "[{\"field\":\"secret\",\"operator\":\"EQ\",\"value\":\"Private\"}]", null, null))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally { applyTestMetaContext(); }
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void publicHttpCrudTraversesBearerFilterPublicationPolicyAndStableErrors() throws Exception {
        String collection = "/api/open/v1/resources/" + contract.code();
        String bearer = "Bearer " + rawToken;
        String auditOrigin = "authentication-" + UniqueIdGenerator.generate();
        try {
            verifyOAuthFailureAudits();
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(collection + "/" + allowedPid)
                            .header("X-Request-Id", auditOrigin + "-missing").header("X-Tenant-Id", "999999"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
            assertAuthenticationAudit(auditOrigin + "-missing", 401, "invalid_token", false,
                    "/api/open/v1/resources/{resource}/{pid}");
            new org.springframework.transaction.support.TransactionTemplate(
                    applicationContext.getBean(org.springframework.transaction.PlatformTransactionManager.class))
                    .executeWithoutResult(transaction -> {
                        try {
                            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(collection + "/" + allowedPid)
                                            .header("X-Request-Id", auditOrigin + "-rollback"))
                                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
                        } catch (Exception exception) {
                            throw new IllegalStateException("Controlled authentication request failed", exception);
                        } finally { transaction.setRollbackOnly(); }
                    });
            assertAuthenticationAudit(auditOrigin + "-rollback", 401, "invalid_token", false,
                    "/api/open/v1/resources/{resource}/{pid}");
            String unknown = "unknown-credential-" + UniqueIdGenerator.generate();
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(collection + "/" + allowedPid)
                            .header("Authorization", "Bearer " + unknown).header("X-Request-Id", auditOrigin + "-unknown")
                            .header("X-Tenant-Id", principal.tenantId()).header("X-Installation-Pid", principal.installationPid()))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
            assertAuthenticationAudit(auditOrigin + "-unknown", 401, "invalid_token", false,
                    "/api/open/v1/resources/{resource}/{pid}");
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/open/v1/whoami")
                            .header("Authorization", bearer).header("X-Request-Id", auditOrigin + "-scope"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
            assertAuthenticationAudit(auditOrigin + "-scope", 403, "insufficient_scope", true, "/api/open/v1/whoami");
            assertThat(eventJdbc.queryForObject("SELECT last_used_at FROM ab_application_access_token WHERE pid = ?",
                    java.sql.Timestamp.class, principal.tokenPid())).isNull();
            assertThat(eventJdbc.queryForObject("SELECT COUNT(*) FROM ab_open_api_rate_window WHERE installation_id = "
                    + "(SELECT installation_id FROM ab_application_access_token WHERE pid = ?)", Long.class, principal.tokenPid())).isZero();
            var expiry = eventJdbc.queryForObject("SELECT expires_at FROM ab_application_access_token WHERE pid = ?",
                    java.sql.Timestamp.class, principal.tokenPid());
            try {
                eventJdbc.update("UPDATE ab_application_access_token SET expires_at = NOW() - INTERVAL '1 second' WHERE pid = ?", principal.tokenPid());
                http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(collection + "/" + allowedPid)
                                .header("Authorization", bearer).header("X-Request-Id", auditOrigin + "-expired"))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
                assertAuthenticationAudit(auditOrigin + "-expired", 401, "invalid_token", true,
                        "/api/open/v1/resources/{resource}/{pid}");
            } finally {
                eventJdbc.update("UPDATE ab_application_access_token SET expires_at = ? WHERE pid = ?", expiry, principal.tokenPid());
            }
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/open/v1/internal/" + rawToken)
                            .header("Authorization", bearer).header("X-Request-Id", auditOrigin + "-unpublished"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
            assertAuthenticationAudit(auditOrigin + "-unpublished", 404, "open_api_capability_not_found", true,
                    "/api/open/v1/{unpublished}");
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(collection + "/" + allowedPid)
                            .header("Authorization", bearer))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
            var lastUse = eventJdbc.queryForObject("SELECT last_used_at FROM ab_application_access_token WHERE pid = ?",
                    java.sql.Timestamp.class, principal.tokenPid());
            try {
                // Populate this installation's current and next minute to avoid a wall-clock boundary race.
                eventJdbc.update("INSERT INTO ab_open_api_rate_window (installation_id, window_start, request_count) "
                        + "SELECT t.installation_id, date_trunc('minute', NOW()) + n * INTERVAL '1 minute', 600 "
                        + "FROM ab_application_access_token t CROSS JOIN generate_series(0, 1) n WHERE t.pid = ? "
                        + "ON CONFLICT (installation_id, window_start) DO UPDATE SET request_count = 600", principal.tokenPid());
                http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(collection + "/" + allowedPid)
                                .header("Authorization", bearer).header("X-Request-Id", auditOrigin + "-rate"))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isTooManyRequests());
                assertAuthenticationAudit(auditOrigin + "-rate", 429, "rate_limit_exceeded", true,
                        "/api/open/v1/resources/{resource}/{pid}");
                assertThat(eventJdbc.queryForObject("SELECT last_used_at FROM ab_application_access_token WHERE pid = ?",
                        java.sql.Timestamp.class, principal.tokenPid())).isEqualTo(lastUse);
                assertThat(eventJdbc.queryForList("SELECT request_count FROM ab_open_api_rate_window WHERE installation_id = "
                        + "(SELECT installation_id FROM ab_application_access_token WHERE pid = ?)", Integer.class, principal.tokenPid()))
                        .containsExactlyInAnyOrder(600, 600);
            } finally {
                eventJdbc.update("DELETE FROM ab_open_api_rate_window WHERE installation_id = "
                        + "(SELECT installation_id FROM ab_application_access_token WHERE pid = ?)", principal.tokenPid());
            }
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(collection + "/" + deniedPid).header("Authorization", bearer))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/open/v1/resources/not-published").header("Authorization", bearer))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
            var create = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(collection)
                    .header("Authorization", bearer).header("Idempotency-Key", "http-create-0001")
                    .header("X-Tenant-Id", principal.tenantId() + 99999).contentType("application/json")
                    .content("{\"name\":\"HTTP resource\",\"zone\":\"A\"}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated()).andReturn().getResponse();
            var body = json.readTree(create.getContentAsString()); String pid = body.path("pid").asText();
            assertThat(body.path("name").asText()).isEqualTo("HTTP resource");
            assertThat(body.has("tenant_id")).isFalse(); assertThat(body.has("row_version")).isFalse();
            String etag = create.getHeader("ETag"); assertThat(etag).isNotBlank();
            assertThat(create.getHeader("Location")).isEqualTo(collection + "/" + pid);
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(collection + "/" + pid)
                    .header("Authorization", bearer).header("Idempotency-Key", "http-patch-0001").contentType("application/json").content("{\"name\":\"Updated over HTTP\"}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isPreconditionFailed())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("precondition_failed"));
            var patch = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(collection + "/" + pid)
                    .header("Authorization", bearer).header("Idempotency-Key", "http-patch-0001").header("If-Match", etag)
                    .contentType("application/json").content("{\"name\":\"Updated over HTTP\"}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse();
            String current = patch.getHeader("ETag"); assertThat(current).isNotEqualTo(etag);
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(collection + "/" + pid)
                    .header("Authorization", bearer).header("Idempotency-Key", "http-delete-0001").header("If-Match", etag))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isPreconditionFailed());
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(collection + "/" + pid)
                    .header("Authorization", bearer).header("Idempotency-Key", "http-delete-0001").header("If-Match", current))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(collection + "/" + pid)
                    .header("Authorization", bearer).header("Idempotency-Key", "http-delete-0001").header("If-Match", current))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Idempotent-Replay", "true"));
            assertThat(MachineDataAccessContext.isActive()).isFalse();
        } finally { cleanupCommittedFixture(); }
    }

    private void verifyOAuthFailureAudits() throws Exception {
        long tokensBefore = eventJdbc.queryForObject("SELECT COUNT(*) FROM ab_application_access_token WHERE installation_id = "
                + "(SELECT id FROM ab_application_installation WHERE pid=?)", Long.class, principal.installationPid());
        Object usageBefore = eventJdbc.queryForObject("SELECT last_used_at FROM ab_application_credential WHERE pid=?",
                java.sql.Timestamp.class, credentialPid);
        oauthDenial("Basic !invalid!", null, null, null, 401, "invalid_client", false);
        oauthDenial(null, clientId, "wrong-secret", null, 401, "invalid_client", false);
        oauthDenial(null, "unknown-client-" + UniqueIdGenerator.generate(), clientSecret, null, 401, "invalid_client", false);
        oauthDenial(null, clientId, clientSecret, "ungranted.scope", 400, "invalid_scope", true);
        String basic = "Basic " + java.util.Base64.getEncoder().encodeToString((clientId + ":" + clientSecret)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        oauthDenial(basic, null, null, "ungranted.scope", 400, "invalid_scope", true);
        oauthDenial(basic, clientId, clientSecret, null, 401, "invalid_client", false);
        Object expires = eventJdbc.queryForObject("SELECT expires_at FROM ab_application_credential WHERE pid=?",
                java.sql.Timestamp.class, credentialPid);
        try {
            eventJdbc.update("UPDATE ab_application_credential SET expires_at=NOW()-INTERVAL '1 second' WHERE pid=?", credentialPid);
            oauthDenial(null, clientId, clientSecret, null, 401, "invalid_client", true);
        } finally { eventJdbc.update("UPDATE ab_application_credential SET expires_at=? WHERE pid=?", expires, credentialPid); }
        String[] rolledBack = new String[1];
        new org.springframework.transaction.support.TransactionTemplate(eventTransactions).executeWithoutResult(transaction -> {
            try { rolledBack[0] = oauthDenial(null, clientId, "wrong-secret", null, 401, "invalid_client", false); }
            catch (Exception exception) { throw new IllegalStateException("Controlled OAuth denial failed", exception); }
            finally { transaction.setRollbackOnly(); }
        });
        assertOAuthAudit(rolledBack[0], 401, "invalid_client", false);
        assertThat(eventJdbc.queryForObject("SELECT COUNT(*) FROM ab_application_access_token WHERE installation_id = "
                + "(SELECT id FROM ab_application_installation WHERE pid=?)", Long.class, principal.installationPid())).isEqualTo(tokensBefore);
        assertThat(eventJdbc.queryForObject("SELECT last_used_at FROM ab_application_credential WHERE pid=?",
                java.sql.Timestamp.class, credentialPid)).isEqualTo(usageBefore);
        var issued = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/oauth2/token").servletPath("/oauth2/token")
                .contentType("application/x-www-form-urlencoded").param("grant_type", "client_credentials")
                .param("client_id", clientId).param("client_secret", clientSecret))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse();
        assertThat(json.readTree(issued.getContentAsString()).path("access_token").asText()).startsWith("ab_at_");
        assertThat(eventJdbc.queryForObject("SELECT COUNT(*) FROM ab_application_access_token WHERE installation_id = "
                + "(SELECT id FROM ab_application_installation WHERE pid=?)", Long.class, principal.installationPid())).isEqualTo(tokensBefore + 1);
        applyTestMetaContext();
    }

    private String oauthDenial(String authorization, String id, String secret, String scope,
                               int status, String code, boolean known) throws Exception {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/oauth2/token").servletPath("/oauth2/token")
                .contentType("application/x-www-form-urlencoded").param("grant_type", "client_credentials")
                .header("X-Request-Id", clientSecret).header("X-Tenant-Id", principal.tenantId())
                .header("X-Installation-Pid", principal.installationPid());
        if (authorization != null) request.header("Authorization", authorization);
        if (id != null) request.param("client_id", id);
        if (secret != null) request.param("client_secret", secret);
        if (scope != null) request.param("scope", scope);
        var response = http.perform(request)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(status))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error").value(code))
                .andReturn().getResponse();
        String requestId = response.getHeader("X-Request-Id");
        assertThat(requestId).isNotNull().isNotEqualTo(clientSecret);
        assertOAuthAudit(requestId, status, code, known);
        return requestId;
    }

    private void assertOAuthAudit(String requestId, int status, String code, boolean known) throws Exception {
        var rows = eventJdbc.queryForList("SELECT tenant_id, application_pid, installation_pid, token_pid, "
                + "http_method, request_path, response_status, duration_ms, business_details FROM ab_open_api_call_audit WHERE request_id=?", requestId);
        assertThat(rows).hasSize(1);
        var row = rows.getFirst();
        assertThat(row.get("tenant_id")).isEqualTo(known ? principal.tenantId() : null);
        assertThat(row.get("application_pid")).isEqualTo(known ? principal.applicationPid() : null);
        assertThat(row.get("installation_pid")).isEqualTo(known ? principal.installationPid() : null);
        assertThat(row.get("token_pid")).isNull();
        assertThat(row.get("http_method")).isEqualTo("POST");
        assertThat(row.get("request_path")).isEqualTo("/oauth2/token");
        assertThat(row.get("response_status")).isEqualTo(status);
        assertThat(((Number) row.get("duration_ms")).longValue()).isGreaterThanOrEqualTo(0);
        assertThat(json.readTree(row.get("business_details").toString())).isEqualTo(json.readTree(
                "{\"actorKind\":\"unauthenticated\",\"phase\":\"authentication\",\"code\":\"" + code + "\"}"));
        assertThat(row.toString()).doesNotContain(clientSecret, clientId, rawToken);
        applyTestMetaContext();
    }

    private void assertAuthenticationAudit(String requestId, int status, String code,
                                           boolean knownIdentity, String path) throws Exception {
        assertAuthenticationAudit(requestId, status, code, knownIdentity, path, "GET");
    }

    private void assertAuthenticationAudit(String requestId, int status, String code,
                                           boolean knownIdentity, String path, String method) throws Exception {
        var rows = eventJdbc.queryForList("SELECT tenant_id, application_pid, installation_pid, token_pid, "
                + "http_method, request_path, response_status, duration_ms, occurred_at, business_details "
                + "FROM ab_open_api_call_audit WHERE request_id = ?", requestId);
        assertThat(rows).hasSize(1);
        var row = rows.get(0);
        assertThat(row.get("tenant_id")).isEqualTo(knownIdentity ? principal.tenantId() : null);
        assertThat(row.get("application_pid")).isEqualTo(knownIdentity ? principal.applicationPid() : null);
        assertThat(row.get("installation_pid")).isEqualTo(knownIdentity ? principal.installationPid() : null);
        assertThat(row.get("token_pid")).isEqualTo(knownIdentity ? principal.tokenPid() : null);
        assertThat(row.get("response_status")).isEqualTo(status);
        assertThat(row.get("http_method")).isEqualTo(method);
        assertThat(row.get("occurred_at")).isNotNull();
        assertThat(((Number) row.get("duration_ms")).longValue()).isGreaterThanOrEqualTo(0);
        assertThat(row.get("request_path")).isEqualTo(path);
        assertThat(json.readTree(row.get("business_details").toString())).isEqualTo(json.valueToTree(
                Map.of("actorKind", "unauthenticated", "phase", "authentication", "code", code)));
        assertThat(row.toString()).doesNotContain(rawToken, "Revocation create", "Revocation update");
        assertThat(MachineDataAccessContext.isActive()).isFalse();
        applyTestMetaContext();
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void machineHttpAuditPersistsPublicActorAndOriginalReplayIntent() throws Exception {
        publishEvents();
        String collection = "/api/open/v1/resources/" + contract.code();
        String origin = "audit-" + UniqueIdGenerator.generate();
        String bearer = "Bearer " + rawToken;
        String requestBody = "{\"name\":\"Secret-free audit\",\"zone\":\"A\"}";
        try {
            var created = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(collection)
                    .header("Authorization", bearer).header("X-Request-Id", origin)
                    .header("X-Actor-Kind", "human").header("Idempotency-Key", origin)
                    .contentType("application/json").content(requestBody))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
                    .andReturn().getResponse();
            String pid = json.readTree(created.getContentAsString()).path("pid").asText();
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(collection)
                    .header("Authorization", bearer).header("X-Request-Id", origin + "-replay")
                    .header("Idempotency-Key", origin).contentType("application/json").content(requestBody))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Idempotent-Replay", "true"));
            applyTestMetaContext();
            var originalPolicy = new OpenApiInstallationPolicy(contract.operations(), Set.of("pid", "name", "zone"),
                    Set.of("name", "zone"), List.of(new OpenApiInstallationPolicy.RowConstraint("zone", List.of("A"))));
            publications.grant(principal.installationPid(), contract.code(), new OpenApiInstallationPolicy(contract.operations(),
                    Set.of("pid", "zone"), Set.of("name", "zone"), originalPolicy.rowConstraints()));
            assertCreateReplayAuthority(collection, bearer, origin, "-policy-denied", requestBody, 403, created);
            applyTestMetaContext();
            publications.grant(principal.installationPid(), contract.code(), originalPolicy);
            assertCreateReplayAuthority(collection, bearer, origin, "-policy-restored", requestBody, 201, created);
            applyTestMetaContext();
            MetaContext.runWithCommandPermitScope("ALL", () -> data.update(contract.modelCode(), pid, Map.of("zone", "B")));
            assertCreateReplayAuthority(collection, bearer, origin, "-row-denied", requestBody, 403, created);
            applyTestMetaContext();
            var outside = MetaContext.runWithCommandPermitScope("ALL", () -> data.getById(contract.modelCode(), pid));
            assertThat(outside).containsEntry("name", "Secret-free audit").containsEntry("zone", "B");
            assertThat(((Number) outside.get("row_version")).longValue()).isEqualTo(2);
            MetaContext.runWithCommandPermitScope("ALL", () -> data.update(contract.modelCode(), pid, Map.of("zone", "A")));
            assertCreateReplayAuthority(collection, bearer, origin, "-row-restored", requestBody, 201, created);
            var current = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(collection + "/" + pid)
                    .header("Authorization", bearer).header("X-Request-Id", origin + "-current"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse();
            assertThat(json.readTree(current.getContentAsString())).isEqualTo(json.readTree(created.getContentAsString()));
            assertThat(current.getHeader("ETag")).isNotEqualTo(created.getHeader("ETag"));
            applyTestMetaContext();
            var restoredRow = MetaContext.runWithCommandPermitScope("ALL", () -> data.getById(contract.modelCode(), pid));
            assertThat(((Number) restoredRow.get("row_version")).longValue()).isEqualTo(3);
            assertThat(eventJdbc.queryForObject("SELECT COUNT(*) FROM ab_open_api_idempotency WHERE tenant_id=? AND route_code=? AND idempotency_key=? AND status='completed'",
                    Long.class, principal.tenantId(), "resource:" + contract.code() + ":create", origin)).isEqualTo(1);
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(collection + "/" + pid)
                    .header("Authorization", bearer).header("X-Request-Id", origin + "-stale")
                    .header("Idempotency-Key", origin + "-patch").contentType("application/json").content("{\"name\":\"Denied\"}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isPreconditionFailed());
            String updateBody = "{\"name\":\"Secret-free update\"}";
            String updateMatch = current.getHeader("ETag");
            String updateKey = origin + "-update";
            var updated = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(collection + "/" + pid)
                    .header("Authorization", bearer).header("X-Request-Id", updateKey)
                    .header("Idempotency-Key", updateKey).header("If-Match", updateMatch)
                    .contentType("application/json").content(updateBody))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse();
            assertThat(json.readTree(updated.getContentAsString())).isEqualTo(json.valueToTree(Map.of("pid", pid, "name", "Secret-free update", "zone", "A")));
            assertThat(updated.getHeader("ETag")).isNotEqualTo(updateMatch);
            assertThat(updated.getHeader("Idempotent-Replay")).isNull();
            assertWriteReplayAuthority("PATCH", collection + "/" + pid, bearer, origin + "-update-replay", updateKey, updateMatch, updateBody, 200, updated);
            applyTestMetaContext();
            publications.grant(principal.installationPid(), contract.code(), new OpenApiInstallationPolicy(contract.operations(),
                    Set.of("pid", "zone"), Set.of("name", "zone"), originalPolicy.rowConstraints()));
            assertWriteReplayAuthority("PATCH", collection + "/" + pid, bearer, origin + "-update-policy-denied", updateKey, updateMatch, updateBody, 403, updated);
            applyTestMetaContext();
            publications.grant(principal.installationPid(), contract.code(), originalPolicy);
            assertWriteReplayAuthority("PATCH", collection + "/" + pid, bearer, origin + "-update-policy-restored", updateKey, updateMatch, updateBody, 200, updated);
            applyTestMetaContext();
            MetaContext.runWithCommandPermitScope("ALL", () -> data.update(contract.modelCode(), pid, Map.of("zone", "B")));
            assertWriteReplayAuthority("PATCH", collection + "/" + pid, bearer, origin + "-update-row-denied", updateKey, updateMatch, updateBody, 403, updated);
            applyTestMetaContext();
            var updateOutside = MetaContext.runWithCommandPermitScope("ALL", () -> data.getById(contract.modelCode(), pid));
            assertThat(updateOutside).containsEntry("name", "Secret-free update").containsEntry("zone", "B");
            assertThat(((Number) updateOutside.get("row_version")).longValue()).isEqualTo(5);
            MetaContext.runWithCommandPermitScope("ALL", () -> data.update(contract.modelCode(), pid, Map.of("zone", "A")));
            assertWriteReplayAuthority("PATCH", collection + "/" + pid, bearer, origin + "-update-row-restored", updateKey, updateMatch, updateBody, 200, updated);
            applyTestMetaContext();
            var noUpdate = new HashSet<>(contract.operations()); noUpdate.remove(Operation.UPDATE);
            publications.grant(principal.installationPid(), contract.code(), new OpenApiInstallationPolicy(noUpdate,
                    originalPolicy.readableFields(), originalPolicy.writableFields(), originalPolicy.rowConstraints()));
            assertWriteReplayAuthority("PATCH", collection + "/" + pid, bearer, origin + "-update-operation-denied", updateKey, updateMatch, updateBody, 403, updated);
            applyTestMetaContext();
            publications.grant(principal.installationPid(), contract.code(), originalPolicy);
            assertWriteReplayAuthority("PATCH", collection + "/" + pid, bearer, origin + "-update-operation-restored", updateKey, updateMatch, updateBody, 200, updated);
            current = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(collection + "/" + pid)
                    .header("Authorization", bearer).header("X-Request-Id", origin + "-update-current"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse();
            assertThat(json.readTree(current.getContentAsString())).isEqualTo(json.readTree(updated.getContentAsString()));
            assertThat(current.getHeader("ETag")).isNotEqualTo(updated.getHeader("ETag"));
            applyTestMetaContext();
            var updateRestored = MetaContext.runWithCommandPermitScope("ALL", () -> data.getById(contract.modelCode(), pid));
            assertThat(updateRestored).containsEntry("name", "Secret-free update").containsEntry("zone", "A");
            assertThat(((Number) updateRestored.get("row_version")).longValue()).isEqualTo(6);
            assertThat(eventJdbc.queryForObject("SELECT COUNT(*) FROM ab_open_api_idempotency WHERE tenant_id=? AND route_code=? AND idempotency_key=? AND status='completed'",
                    Long.class, principal.tenantId(), "resource:" + contract.code() + ":update", updateKey)).isEqualTo(1);
            String deleteMatch = current.getHeader("ETag");
            String deleteKey = origin + "-delete";
            var deleted = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(collection + "/" + pid)
                    .header("Authorization", bearer).header("X-Request-Id", deleteKey)
                    .header("Idempotency-Key", deleteKey).header("If-Match", deleteMatch))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent()).andReturn().getResponse();
            assertWriteReplayAuthority("DELETE", collection + "/" + pid, bearer, origin + "-delete-replay", deleteKey, deleteMatch, null, 204, deleted);
            applyTestMetaContext();
            publications.grant(principal.installationPid(), contract.code(), new OpenApiInstallationPolicy(contract.operations(),
                    Set.of("pid", "zone"), Set.of("name", "zone"), originalPolicy.rowConstraints()));
            assertWriteReplayAuthority("DELETE", collection + "/" + pid, bearer, origin + "-delete-policy-denied", deleteKey, deleteMatch, null, 403, deleted);
            applyTestMetaContext();
            publications.grant(principal.installationPid(), contract.code(), originalPolicy);
            assertWriteReplayAuthority("DELETE", collection + "/" + pid, bearer, origin + "-delete-policy-restored", deleteKey, deleteMatch, null, 204, deleted);
            applyTestMetaContext();
            var noDelete = new HashSet<>(contract.operations()); noDelete.remove(Operation.DELETE);
            publications.grant(principal.installationPid(), contract.code(), new OpenApiInstallationPolicy(noDelete,
                    originalPolicy.readableFields(), originalPolicy.writableFields(), originalPolicy.rowConstraints()));
            assertWriteReplayAuthority("DELETE", collection + "/" + pid, bearer, origin + "-delete-operation-denied", deleteKey, deleteMatch, null, 403, deleted);
            applyTestMetaContext();
            publications.grant(principal.installationPid(), contract.code(), originalPolicy);
            assertWriteReplayAuthority("DELETE", collection + "/" + pid, bearer, origin + "-delete-operation-restored", deleteKey, deleteMatch, null, 204, deleted);
            applyTestMetaContext();
            assertThatThrownBy(() -> MetaContext.runWithCommandPermitScope("ALL", () -> data.getById(contract.modelCode(), pid)))
                    .isInstanceOf(com.auraboot.framework.meta.exception.MetaRecordNotFoundException.class);
            assertThat(eventJdbc.queryForObject("SELECT COUNT(*) FROM ab_open_api_idempotency WHERE tenant_id=? AND route_code=? AND idempotency_key=? AND status='completed'",
                    Long.class, principal.tenantId(), "resource:" + contract.code() + ":delete", deleteKey)).isEqualTo(1);
            var events = sourceEvents();
            assertThat(events).hasSize(7);
            assertThat(events.stream().map(row -> row.get("event_id")).distinct()).hasSize(7);
            assertThat(json.readTree(events.getFirst().get("payload").toString()).path("source").path("name").asText()).isEqualTo("Secret-free audit");
            assertThat(json.readTree(events.getLast().get("payload").toString()).path("source").path("name").asText()).isEqualTo("Secret-free update");
            applyTestMetaContext();
            for (var suffix : List.of("", "-replay", "-policy-denied", "-policy-restored", "-row-denied", "-row-restored", "-current", "-stale", "-update", "-update-replay", "-update-policy-denied", "-update-policy-restored", "-update-row-denied", "-update-row-restored", "-update-operation-denied", "-update-operation-restored", "-update-current", "-delete", "-delete-replay", "-delete-policy-denied", "-delete-policy-restored", "-delete-operation-denied", "-delete-operation-restored")) {
                var audits = management.listCallAudits(principal.installationPid(), origin + suffix, null, 20);
                assertThat(audits).hasSize(1);
                var audit = audits.getFirst();
                assertThat(audit.actorKind()).isEqualTo("machine");
                assertThat(audit.applicationPid()).isEqualTo(principal.applicationPid());
                assertThat(audit.installationPid()).isEqualTo(principal.installationPid());
                assertThat(audit.resource()).isEqualTo(contract.code());
                boolean deniedReplay = suffix.endsWith("-denied");
                assertThat(audit.recordPid()).isEqualTo(deniedReplay && !suffix.startsWith("-update") && !suffix.startsWith("-delete") ? null : pid);
                String operation = suffix.endsWith("-current") ? "get" : suffix.startsWith("-update") || suffix.equals("-stale") ? "update" : suffix.startsWith("-delete") ? "delete" : "create";
                assertThat(audit.operation()).isEqualTo(operation);
                assertThat(audit.capability()).isEqualTo("resource." + contract.code() + "." + operation);
                assertThat(audit.status()).isEqualTo(suffix.equals("-stale") ? 412 : deniedReplay ? 403 : suffix.startsWith("-delete") ? 204 : suffix.startsWith("-update") || suffix.equals("-current") ? 200 : 201);
                assertThat(audit.idempotentReplay()).isEqualTo(suffix.equals("-stale") || deniedReplay || suffix.endsWith("-current") ? null : suffix.endsWith("replay") || suffix.endsWith("-restored"));
                assertThat(audit.durationMs()).isNotNegative();
                assertThat(json.writeValueAsString(audit)).doesNotContain(rawToken, "tokenPid", "tenantId", "Secret-free audit", "Secret-free update");
                String persisted = eventJdbc.queryForObject("SELECT business_details::text FROM ab_open_api_call_audit WHERE tenant_id=? AND installation_pid=? AND request_id=?",
                        String.class, principal.tenantId(), principal.installationPid(), origin + suffix);
                assertThat(json.readTree(persisted).path("actorKind").asText()).isEqualTo("machine");
                assertThat(persisted).doesNotContain(rawToken, "tokenPid", "tenantId", "Secret-free audit", "Secret-free update");
            }
            assertThat(eventJdbc.queryForObject("SELECT COUNT(*) FROM ab_audit_trail WHERE tenant_id=? AND actor_name=?",
                    Long.class, principal.tenantId(), principal.tokenPid())).isZero();
        } finally { cleanupCommittedFixture(); }
    }

    private void assertCreateReplayAuthority(String collection, String bearer, String origin, String suffix,
                                            String body, int status, org.springframework.mock.web.MockHttpServletResponse original) throws Exception {
        assertWriteReplayAuthority("POST", collection, bearer, origin + suffix, origin, null, body, status, original);
    }

    private void assertWriteReplayAuthority(String method, String path, String bearer, String requestId, String key,
                                           String ifMatch, String body, int status,
                                           org.springframework.mock.web.MockHttpServletResponse original) throws Exception {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method), path)
                .header("Authorization", bearer).header("X-Request-Id", requestId).header("Idempotency-Key", key);
        if (ifMatch != null) request.header("If-Match", ifMatch);
        if (body != null) request.contentType("application/json").content(body);
        var response = http.perform(request)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(status)).andReturn().getResponse();
        if (status != 403) {
            if (status == 204) assertThat(response.getContentAsString()).isEmpty();
            else assertThat(json.readTree(response.getContentAsString())).isEqualTo(json.readTree(original.getContentAsString()));
            assertThat(response.getHeader("ETag")).isEqualTo(original.getHeader("ETag"));
            assertThat(response.getHeader("Location")).isEqualTo(original.getHeader("Location"));
            assertThat(response.getHeader("Idempotent-Replay")).isEqualTo("true");
        } else {
            assertThat(json.readTree(response.getContentAsString()).path("code").asText()).isEqualTo("403");
            assertThat(json.readTree(response.getContentAsString()).path("message").asText()).isEqualTo("Access forbidden");
            assertThat(json.readTree(response.getContentAsString()).path("context").asText())
                    .isEqualTo("Current resource or query authorization required");
            assertThat(response.getContentAsString()).doesNotContain("Secret-free audit", "Secret-free update", rawToken, "Replay authorization changed", "Replay target");
            assertThat(response.getHeader("ETag")).isNull();
            assertThat(response.getHeader("Location")).isNull();
            assertThat(response.getHeader("Idempotent-Replay")).isNull();
        }
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void rejectedCommandHttpAuditKeepsPublishedTargetAndStableFailure() throws Exception {
        management.updateScopes(principal.installationPid(),
                new com.auraboot.framework.openplatform.dto.OpenPlatformDtos.UpdateInstallationScopesRequest(Set.of("assets.manage")));
        var credential = management.createCredential(principal.installationPid());
        String commandToken = tokens.issue("client_credentials", credential.clientId(), credential.clientSecret(), null).accessToken();
        String origin = "command-audit-" + UniqueIdGenerator.generate();
        String path = "/api/open/v1/commands/assets.assign:execute";
        try {
            for (String kind : List.of("missing-etag", "unknown-input", "malformed-input")) {
                Map<String, Object> input = kind.equals("unknown-input") ? Map.of("privateField", "private-value")
                        : Map.of("assignee", "private-value");
                Object bodyInput = kind.equals("malformed-input") ? "private-value" : input;
                var response = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                        .header("Authorization", "Bearer " + commandToken).header("X-Request-Id", origin + "-" + kind)
                        .header("X-Actor-Kind", "human").header("Idempotency-Key", origin + "-" + kind)
                        .contentType("application/json").content(json.writeValueAsString(Map.of("targetPid", allowedPid, "input", bodyInput))))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .is(kind.equals("missing-etag") ? 412 : 400)).andReturn().getResponse();
                assertThat(json.readTree(response.getContentAsString()).path("code").asText())
                        .isEqualTo(kind.equals("missing-etag") ? "precondition_failed" : "invalid_request");
                applyTestMetaContext();
                var audits = management.listCallAudits(principal.installationPid(), origin + "-" + kind, null, 20);
                assertThat(audits).hasSize(1);
                var audit = audits.getFirst();
                assertThat(audit.actorKind()).isEqualTo("machine");
                assertThat(audit.applicationPid()).isEqualTo(principal.applicationPid());
                assertThat(audit.installationPid()).isEqualTo(principal.installationPid());
                assertThat(audit.capability()).isEqualTo("assets.assign");
                assertThat(audit.resource()).isEqualTo("assets");
                assertThat(audit.recordPid()).isEqualTo(allowedPid);
                assertThat(audit.operation()).isEqualTo("execute");
                assertThat(audit.status()).isEqualTo(response.getStatus());
                assertThat(audit.idempotentReplay()).isNull();
                assertThat(json.writeValueAsString(audit)).doesNotContain(commandToken, "privateField", "private-value", "tokenPid", "tenantId");
                String persisted = eventJdbc.queryForObject("SELECT business_details::text FROM ab_open_api_call_audit WHERE tenant_id=? AND installation_pid=? AND request_id=?",
                        String.class, principal.tenantId(), principal.installationPid(), origin + "-" + kind);
                assertThat(json.readTree(persisted).path("resource").asText()).isEqualTo("assets");
                assertThat(persisted).doesNotContain(commandToken, "privateField", "private-value");
            }
            var row = MetaContext.runWithCommandPermitScope("ALL", () -> data.getById(contract.modelCode(), allowedPid));
            assertThat(row).containsEntry("name", "Visible").containsEntry("zone", "A");
            assertThat(((Number) row.get("row_version")).longValue()).isEqualTo(1);
            assertThat(eventJdbc.queryForObject("SELECT COUNT(*) FROM ab_idempotency_record WHERE tenant_id=? AND client_request_id LIKE ?",
                    Long.class, principal.tenantId(), "%" + origin + "%")).isZero();
        } finally { cleanupCommittedFixture(); }
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void customPhysicalTablePreservesPublicHttpCrudVersions() throws Exception {
        // Reuse the bearer/row-policy/CAS/replay assertions against a non-mt_ physical table.
        publicHttpCrudTraversesBearerFilterPublicationPolicyAndStableErrors();
    }

    @Test void humanCustomTableMutationInvalidatesMachineEtagAndEmitsEvent() throws Exception {
        publishEvents();
        String etag;
        MetaContext.setContext(principal.tenantId(), null, principal.subjectPid(), "machine");
        try (var machine = MachineDataAccessContext.begin(principal)) {
            etag = resources.get(principal, contract.code(), allowedPid).headers().get("ETag");
        } finally { applyTestMetaContext(); }
        var changed = MetaContext.runWithCommandPermitScope("ALL", () ->
                data.update(contract.modelCode(), allowedPid, new HashMap<>(Map.of("name", "Human changed"))));
        assertThat(((Number) eventJdbc.queryForMap("SELECT row_version FROM "
                + models.getModelDefinition(contract.modelCode()).orElseThrow().getTableName() + " WHERE pid = ?", allowedPid)
                .get("row_version")).longValue()).isEqualTo(2);
        assertThat(changed).containsEntry("row_version", 2);
        var events = sourceEvents();
        assertThat(events).hasSize(1);
        assertThat(json.readTree(events.getFirst().get("payload").toString()).path("source").path("name").asText())
                .isEqualTo("Human changed");
        MetaContext.setContext(principal.tenantId(), null, principal.subjectPid(), "machine");
        try (var machine = MachineDataAccessContext.begin(principal)) {
            assertThat(resources.get(principal, contract.code(), allowedPid).headers().get("ETag")).isNotEqualTo(etag);
            assertThatThrownBy(() -> resources.write(principal, contract.code(), Operation.UPDATE, allowedPid,
                    "human-stale-key1", etag, Map.of("name", "Stale overwrite")))
                    .isInstanceOf(OpenApiPreconditionException.class);
        } finally { applyTestMetaContext(); }
        assertThat(sourceEvents()).hasSize(1);
        assertThat(eventJdbc.queryForObject("SELECT name FROM " + models.getModelDefinition(contract.modelCode()).orElseThrow().getTableName()
                + " WHERE pid = ?", String.class, allowedPid)).isEqualTo("Human changed");
    }

    private void publishCounterEvents(boolean writable) {
        var fields = new LinkedHashMap<>(contract.fields());
        fields.put("counter", new Field("counter", Type.INTEGER, true, true, false, true, Set.of("EQ"), false, null, Map.of(), null));
        fields.put("cap", new Field("cap", Type.INTEGER, true, false, false, true, Set.of(), false, null, Map.of(), null));
        contract = new OpenApiResourceContract(contract.code(), contract.modelCode(), 2, contract.operations(),
                contract.scopes(), contract.dataPolicy(), fields, 50, 8, 5000, Set.of("created", "updated", "deleted"));
        publications.saveDraft(contract.code(), new OpenApiPublicationService.DraftRequest(1, contract));
        publications.publish(contract.code(), 2);
        publications.grant(principal.installationPid(), contract.code(), new OpenApiInstallationPolicy(contract.operations(),
                fields.keySet(), writable ? Set.of("name", "zone", "counter") : Set.of("name", "zone"),
                List.of(new OpenApiInstallationPolicy.RowConstraint("zone", List.of("A")))));
    }

    @Test void atomicCustomTableAdvancesVersionAndEmitsEvent() throws Exception {
        publishCounterEvents(true);
        assertThat(MetaContext.runWithCommandPermitScope("ALL", () ->
                data.incrementWithinCap(contract.modelCode(), allowedPid, "counter", 1, null))).contains(1L);
        assertThat(eventJdbc.queryForObject("SELECT row_version FROM " + models.getModelDefinition(contract.modelCode())
                .orElseThrow().getTableName() + " WHERE pid = ?", Long.class, allowedPid)).isEqualTo(2L);
        assertThat(sourceEvents()).hasSize(1);
        assertThat(json.readTree(sourceEvents().getFirst().get("payload").toString()).path("source").path("counter").asInt()).isEqualTo(1);
    }

    @Test void machineAtomicIncrementRejectsUnwritableCounter() {
        publishCounterEvents(false);
        MetaContext.setContext(principal.tenantId(), null, principal.subjectPid(), "machine");
        try (var machine = MachineDataAccessContext.begin(principal);
             var permission = authorizer.authorize(principal, contract.code(), Operation.UPDATE).openScope()) {
            assertThatThrownBy(() -> data.incrementWithinCap(contract.modelCode(), allowedPid, "counter", 1, null))
                    .isInstanceOf(AccessDeniedException.class);
        } finally { applyTestMetaContext(); }
        assertThat(MetaContext.runWithCommandPermitScope("ALL", () -> data.getById(contract.modelCode(), allowedPid)).get("counter")).isNull();
        assertThat(sourceEvents()).isEmpty();
    }

    @Test void machineAtomicIncrementCannotMutateOutsideInstallationRows() {
        publishCounterEvents(true);
        MetaContext.setContext(principal.tenantId(), null, principal.subjectPid(), "machine");
        try (var machine = MachineDataAccessContext.begin(principal);
             var permission = authorizer.authorize(principal, contract.code(), Operation.UPDATE).openScope()) {
            assertThat(data.incrementWithinCap(contract.modelCode(), deniedPid, "counter", 1, null)).isEmpty();
        } finally { applyTestMetaContext(); }
        assertThat(MetaContext.runWithCommandPermitScope("ALL", () -> data.getById(contract.modelCode(), deniedPid)).get("counter")).isNull();
        assertThat(sourceEvents()).isEmpty();
    }

    @Test void machineAtomicIncrementRespectsCapVersionProspectiveScopeAndRollback() {
        MetaContext.runWithCommandPermitScope("ALL", () -> data.update(contract.modelCode(), allowedPid,
                new HashMap<>(Map.of("counter", 0, "cap", 5))));
        publishCounterEvents(true);
        MetaContext.setContext(principal.tenantId(), null, principal.subjectPid(), "machine");
        try (var machine = MachineDataAccessContext.begin(principal);
             var permission = authorizer.authorize(principal, contract.code(), Operation.UPDATE).openScope()) {
            assertThat(data.incrementWithinCap(contract.modelCode(), allowedPid, "counter", 2, "cap")).contains(2L);
            assertThat(data.incrementWithinCap(contract.modelCode(), allowedPid, "counter", 100, "cap")).isEmpty();
            try (var stale = MachineDataAccessContext.withExpectedVersion(contract.modelCode(), allowedPid, 2)) {
                assertThat(data.incrementWithinCap(contract.modelCode(), allowedPid, "counter", 1, null)).isEmpty();
            }
        } finally { applyTestMetaContext(); }
        assertThat(sourceEvents()).hasSize(1);
        var constrained = new OpenApiInstallationPolicy(contract.operations(), contract.fields().keySet(), Set.of("counter"),
                List.of(new OpenApiInstallationPolicy.RowConstraint("zone", List.of("A")),
                        new OpenApiInstallationPolicy.RowConstraint("counter", List.of("2"))));
        publications.grant(principal.installationPid(), contract.code(), constrained);
        MetaContext.setContext(principal.tenantId(), null, principal.subjectPid(), "machine");
        try (var machine = MachineDataAccessContext.begin(principal);
             var permission = authorizer.authorize(principal, contract.code(), Operation.UPDATE).openScope()) {
            assertThat(data.incrementWithinCap(contract.modelCode(), allowedPid, "counter", 1, null)).isEmpty();
        } finally { applyTestMetaContext(); }
        var nested = new org.springframework.transaction.support.TransactionTemplate(eventTransactions);
        nested.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED);
        var readable = new HashSet<>(contract.fields().keySet()); readable.remove("cap");
        publications.grant(principal.installationPid(), contract.code(), new OpenApiInstallationPolicy(contract.operations(),
                readable, Set.of("counter"), List.of(new OpenApiInstallationPolicy.RowConstraint("zone", List.of("A")))));
        MetaContext.setContext(principal.tenantId(), null, principal.subjectPid(), "machine");
        try (var machine = MachineDataAccessContext.begin(principal);
             var permission = authorizer.authorize(principal, contract.code(), Operation.UPDATE).openScope()) {
            assertThatThrownBy(() -> nested.execute(status -> data.incrementWithinCap(contract.modelCode(), allowedPid, "counter", 1, "cap")))
                    .isInstanceOf(AccessDeniedException.class);
        } finally { applyTestMetaContext(); }
        eventFaultObserver.fail = true;
        try {
            assertThatThrownBy(() -> nested.execute(status -> MetaContext.runWithCommandPermitScope("ALL", () ->
                    data.incrementWithinCap(contract.modelCode(), allowedPid, "counter", 1, null))))
                    .isInstanceOf(IllegalStateException.class);
        } finally { eventFaultObserver.fail = false; }
        var current = MetaContext.runWithCommandPermitScope("ALL", () -> data.getById(contract.modelCode(), allowedPid));
        assertThat(current).containsEntry("counter", 2).containsEntry("row_version", 3);
        assertThat(sourceEvents()).hasSize(1);
    }

    private void cleanupCommittedFixture() {
        applyTestMetaContext();
        publications.withdraw(contract.code(), publications.list().stream().filter(view -> view.code().equals(contract.code()))
                .findFirst().orElseThrow().draftRevision());
        management.disableInstallation(principal.installationPid());
        var result = schemas.dropTableByModel(contract.modelCode());
        assertThat(result.isSuccess()).as("fixture table cleanup: %s", result.getErrorMessage()).isTrue();
        models.delete(modelMapper.findCurrentByCode(contract.modelCode()).getPid());
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void concurrentTransactionsWriteOnceForOneKeyAndOneVersion() throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var start = new java.util.concurrent.CountDownLatch(1);
            var ready = new java.util.concurrent.CountDownLatch(2);
            var first = concurrent(pool, ready, start, () -> resources.write(principal, contract.code(), Operation.CREATE,
                    null, "concurrent-create-01", null, Map.of("name", "Concurrent", "zone", "A")));
            var second = concurrent(pool, ready, start, () -> resources.write(principal, contract.code(), Operation.CREATE,
                    null, "concurrent-create-01", null, Map.of("zone", "A", "name", "Concurrent")));
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); start.countDown();
            var a = first.get(30, java.util.concurrent.TimeUnit.SECONDS); var b = second.get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(a.body()).isEqualTo(b.body()); assertThat(a.headers()).isEqualTo(b.headers());
            assertThat(List.of(a.replay(), b.replay())).containsExactlyInAnyOrder(false, true);
            assertThat(MetaContext.runWithCommandPermitScope("ALL", () -> data.list(contract.modelCode(),
                    DynamicQueryRequest.builder().pageNum(1).pageSize(10).build())).getTotal()).isEqualTo(3);
            String etag;
            MetaContext.setContext(principal.tenantId(), null, principal.subjectPid(), "machine");
            try (var machine = MachineDataAccessContext.begin(principal)) {
                etag = resources.get(principal, contract.code(), allowedPid).headers().get("ETag");
            } finally { applyTestMetaContext(); }
            var updateStart = new java.util.concurrent.CountDownLatch(1);
            var updateReady = new java.util.concurrent.CountDownLatch(2);
            var updateA = concurrent(pool, updateReady, updateStart, () -> conditionalWrite(etag, "concurrent-update-01", "Winner A"));
            var updateB = concurrent(pool, updateReady, updateStart, () -> conditionalWrite(etag, "concurrent-update-02", "Winner B"));
            assertThat(updateReady.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); updateStart.countDown();
            assertThat(List.of(updateA.get(30, java.util.concurrent.TimeUnit.SECONDS), updateB.get(30, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 412);
            var row = MetaContext.runWithCommandPermitScope("ALL", () -> data.getById(contract.modelCode(), allowedPid));
            assertThat(((Number) row.get("row_version")).longValue()).isEqualTo(2);
            assertThat(row.get("name")).isIn("Winner A", "Winner B");
        } finally { pool.shutdownNow(); cleanupCommittedFixture(); }
    }

    private int conditionalWrite(String etag, String key, String name) {
        try { return resources.write(principal, contract.code(), Operation.UPDATE, allowedPid, key, etag, Map.of("name", name)).status(); }
        catch (OpenApiPreconditionException conflict) { return 412; }
    }

    private OpenApiActionContract publishBusinessAction(String kind) {
        String code = "business." + UniqueIdGenerator.generate().toLowerCase();
        String source = "core.visible-page";
        if ("command".equals(kind)) {
            var request = new CommandDefinitionCreateRequest();
            source = "fixture_transition_" + UniqueIdGenerator.generate().toLowerCase();
            request.setCode(source); request.setDisplayName("Controlled installed transition"); request.setModelCode(contract.modelCode());
            request.setCmdRiskLevel("L1"); request.setInputSchema("{\"type\":\"object\",\"properties\":{\"zone\":{\"type\":\"string\"}}}");
            request.setExecutionConfig("{\"type\":\"state_transition\",\"stateField\":\"name\",\"fromStates\":[\"Visible\"],\"toState\":\"Assigned\",\"inputFields\":[\"zone\"],\"permissions\":[\"fixture.action.manage\"]}");
            var draft = commands.create(request); commands.publish(draft.getPid());
            publications.grant(principal.installationPid(), contract.code(), new OpenApiInstallationPolicy(contract.operations(),
                    contract.fields().keySet(), Set.of("zone"), List.of(new OpenApiInstallationPolicy.RowConstraint("zone", List.of("A")))));
        }
        var descriptor = actionValidator.describe(principal.tenantId(), contract.code(), kind, source);
        var action = new OpenApiActionContract(code, kind, source, contract.code(), 1, descriptor.fingerprint(), descriptor.permission(),
                Map.of("execute", code + ".execute"), descriptor.parameters(), descriptor.inputFields(), descriptor.effectFields(), descriptor.outputFields(), 5000, 2);
        actionPublications.saveDraft(code, new OpenApiActionPublicationService.DraftRequest(0, action)); actionPublications.publish(code, 1);
        actionPublications.grant(principal.installationPid(), code, new OpenApiActionPolicy(true, action.effectFields()));
        var scopes = new HashSet<>(principal.scopes()); scopes.add(code + ".execute");
        management.updateScopes(principal.installationPid(), new UpdateInstallationScopesRequest(scopes));
        var issued = integrationTokens.create(principal.installationPid(),
                new com.auraboot.framework.openplatform.dto.IntegrationTokenDtos.Create("Business interface", "never", null), "business-token-" + code);
        rawToken = issued.token();
        var identity = auth.findToken(secrets.sha256(rawToken), OpenPlatformTokenService.AUDIENCE, Instant.now());
        principal = new MachinePrincipal(identity.tenantId(), identity.tokenPid(), "machine", scopes,
                identity.applicationPid(), identity.installationPid(), identity.environment(), identity.tokenPid());
        return action;
    }

    private <T> T installedBusinessCall(java.util.function.Supplier<T> call) {
        var human = MetaContext.snapshot(); MetaContext.setContext(principal.tenantId(), null, principal.subjectPid(), "machine");
        try (var machine = MachineDataAccessContext.begin(principal)) { return call.get(); }
        finally { MetaContext.restore(human); }
    }

    @Test void readOnlyHandlerExecutesRealGrantedRowsAndRejectsUnknownInputsAndTargets() throws Exception {
        var action = publishBusinessAction("handler");
        var result = installedBusinessCall(() -> publicActions.execute(principal, action.code(), null, null, null, Map.of("limit", 10)));
        assertThat(result.body()).containsOnlyKeys("countOnPage", "hasMore").containsEntry("hasMore", false);
        assertThat(((Number) result.body().get("countOnPage")).intValue()).isEqualTo(1);
        String route = "/api/open/v1/actions/" + action.code() + ":execute";
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(route)
                .header("Authorization", "Bearer " + rawToken).contentType("application/json").content("{\"input\":{\"limit\":10}}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.countOnPage").value(1));
        assertThatThrownBy(() -> installedBusinessCall(() -> publicActions.execute(principal, action.code(), null, null, null, Map.of("tenantId", 1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> installedBusinessCall(() -> publicActions.execute(principal, action.code(), "unneeded-write-key", null, allowedPid, Map.of("limit", 10))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> installedBusinessCall(() -> publicActions.execute(principal, action.code(), null, null, null, Map.of("limit", 1000))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void publicBusinessCommandUsesCurrentResourceEtagAndPersistentReplayWithoutAllAuthority() {
        publishEvents();
        var action = publishBusinessAction("command");
        try {
            String etag = installedBusinessCall(() -> resources.get(principal, contract.code(), allowedPid)).headers().get("ETag");
            var result = installedBusinessCall(() -> publicActions.execute(principal, action.code(), "business-command-once", etag, allowedPid, Map.of("zone", "A")));
            assertThat(((Map<?, ?>) result.body().get("resource")).get("name")).isEqualTo("Assigned");
            assertThat(result.body()).containsEntry("idempotentReplay", false);
            assertThat(result.headers().get("ETag")).isNotBlank().isNotEqualTo(etag);
            var row = MetaContext.runWithCommandPermitScope("ALL", () -> data.getById(contract.modelCode(), allowedPid));
            assertThat(row).containsEntry("name", "Assigned").containsEntry("zone", "A");
            long version = ((Number) row.get("row_version")).longValue();
            assertThat(version).isGreaterThan(1);
            var events = sourceEvents();
            assertThat(events).hasSize(Math.toIntExact(version - 1));
            for (int index = 0; index < events.size(); index++) {
                try {
                    var payload = json.readTree(events.get(index).get("payload").toString());
                    assertThat(payload.path("recordVersion").asLong()).isEqualTo(index + 2L);
                    assertThat(payload.path("data").has("secret")).isFalse();
                } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new AssertionError("Invalid model event artifact", invalid); }
            }
            var replay = installedBusinessCall(() -> publicActions.execute(principal, action.code(), "business-command-once", etag, allowedPid, Map.of("zone", "A")));
            assertThat(replay.replay()).isTrue(); assertThat(replay.body()).containsEntry("idempotentReplay", true);
            assertThat(sourceEvents()).isEqualTo(events);
            assertThat(MetaContext.runWithCommandPermitScope("ALL", () -> data.getById(contract.modelCode(), allowedPid)).get("row_version")).isEqualTo((int) version);
            assertThatThrownBy(() -> installedBusinessCall(() -> publicActions.execute(principal, action.code(), "business-command-stale", etag, allowedPid, Map.of("zone", "A"))))
                    .isInstanceOf(OpenApiPreconditionException.class);
            assertThatThrownBy(() -> installedBusinessCall(() -> publicActions.execute(principal, action.code(), "business-command-once", etag, allowedPid, Map.of("zone", "B"))))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
            assertThat(row.get("secret")).isEqualTo("Private");
            assertThat(((Map<?, ?>) result.body().get("resource")).containsKey("secret")).isFalse();
        } finally {
            actionPublications.withdraw(action.code(), 1);
            try { commands.delete(commands.findByCode(action.sourceCode()).getPid()); }
            finally { cleanupCommittedFixture(); }
        }
    }

    @Test void businessActionRequiresBothItsEffectGrantAndCurrentResourceAuthority() {
        var action = publishBusinessAction("command");
        String etag = installedBusinessCall(() -> resources.get(principal, contract.code(), allowedPid)).headers().get("ETag");
        actionPublications.grant(principal.installationPid(), action.code(), new OpenApiActionPolicy(true, Set.of("zone")));
        assertThatThrownBy(() -> installedBusinessCall(() -> publicActions.execute(principal, action.code(), "denied-action-effects", etag, allowedPid, Map.of("zone", "A"))))
                .isInstanceOf(AccessDeniedException.class);
        actionPublications.grant(principal.installationPid(), action.code(), new OpenApiActionPolicy(true, action.effectFields()));
        publications.grant(principal.installationPid(), contract.code(), new OpenApiInstallationPolicy(contract.operations(), Set.of("pid", "zone"), Set.of("zone"),
                List.of(new OpenApiInstallationPolicy.RowConstraint("zone", List.of("A")))));
        assertThatThrownBy(() -> installedBusinessCall(() -> publicActions.execute(principal, action.code(), "denied-resource-effects", null, allowedPid, Map.of("zone", "A"))))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(MetaContext.runWithCommandPermitScope("ALL", () -> data.getById(contract.modelCode(), allowedPid))).containsEntry("name", "Visible");
    }

    @Test void businessHandlerRechecksResourceRevocationTokenRevocationAndWithdrawal() {
        var action = publishBusinessAction("handler");
        assertThat(installedBusinessCall(() -> publicActions.execute(principal, action.code(), null, null, null, Map.of("limit", 10))).status()).isEqualTo(200);
        publications.revoke(principal.installationPid(), contract.code());
        assertThatThrownBy(() -> installedBusinessCall(() -> publicActions.execute(principal, action.code(), null, null, null, Map.of("limit", 10))))
                .isInstanceOf(AccessDeniedException.class);
        publications.grant(principal.installationPid(), contract.code(), new OpenApiInstallationPolicy(contract.operations(), contract.fields().keySet(), Set.of("name", "zone"),
                List.of(new OpenApiInstallationPolicy.RowConstraint("zone", List.of("A")))));
        assertThat(installedBusinessCall(() -> publicActions.execute(principal, action.code(), null, null, null, Map.of("limit", 10))).status()).isEqualTo(200);
        integrationTokens.revoke(principal.installationPid(), principal.tokenPid());
        assertThatThrownBy(() -> installedBusinessCall(() -> publicActions.execute(principal, action.code(), null, null, null, Map.of("limit", 10))))
                .isInstanceOf(AccessDeniedException.class);
        actionPublications.withdraw(action.code(), 1);
        assertThatThrownBy(() -> installedBusinessCall(() -> publicActions.execute(principal, action.code(), null, null, null, Map.of("limit", 10))))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test void businessPublicationArtifactsDescribeDirectBearerAndHidePrivateSourceAndModel() {
        var action = publishBusinessAction("handler");
        var document = OpenApiContractArtifacts.action(action, contract);
        var encoded = json.valueToTree(document).toString();
        assertThat(encoded).contains("integrationToken", "bearer", "countOnPage", "/actions/" + action.code())
                .doesNotContain(action.sourceCode(), contract.modelCode(), action.sourceFingerprint());
        assertThat(OpenApiContractArtifacts.actionTypes(action, contract)).contains("ActionRequest", "ActionResult", "countOnPage", "number").doesNotContain(action.sourceCode());
        assertThatThrownBy(() -> actionPublications.publish(action.code(), 99)).isInstanceOf(OpenApiPublicationService.PublicationConflictException.class);
        var human = MetaContext.snapshot();
        try {
            MetaContext.setContext(principal.tenantId(), null, principal.subjectPid(), "machine");
            assertThatThrownBy(() -> actionPublications.list()).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> actionPublications.grant(principal.installationPid(), action.code(), new OpenApiActionPolicy(true, Set.of())))
                    .isInstanceOf(AccessDeniedException.class);
        } finally { MetaContext.restore(human); }
    }

    @Test void businessPublicationLifecycleListsSourcesGrantsSnapshotsAndExplicitRevocation() {
        var action = publishBusinessAction("handler");
        assertThat(actionPublications.sources(contract.code())).extracting(OpenApiActionValidator.SourceView::sourceCode).contains("core.visible-page");
        assertThat(actionPublications.list()).extracting(OpenApiActionPublicationService.View::code).contains(action.code());
        assertThat(actionPublications.installationGrants(principal.installationPid())).filteredOn(row -> row.code().equals(action.code()))
                .singleElement().satisfies(row -> assertThat(row.policy().execute()).isTrue());
        actionPublications.revoke(principal.installationPid(), action.code());
        assertThatThrownBy(() -> installedBusinessCall(() -> publicActions.execute(principal, action.code(), null, null, null, Map.of("limit", 10))))
                .isInstanceOf(AccessDeniedException.class);
        actionPublications.grant(principal.installationPid(), action.code(), new OpenApiActionPolicy(true, Set.of()));
        var next = new OpenApiActionContract(action.code(), action.sourceKind(), action.sourceCode(), action.resourceCode(), 2,
                action.sourceFingerprint(), action.permission(), action.scopes(), action.parameters(), action.inputFields(), action.effectFields(), action.outputFields(), 6000, 3);
        assertThat(actionPublications.saveDraft(action.code(), new OpenApiActionPublicationService.DraftRequest(1, next)).draftRevision()).isEqualTo(2);
        assertThat(actionPublications.publish(action.code(), 2).activeVersion()).isEqualTo(2);
        assertThat(actionPublications.publishedForAdministration(action.code())).isEqualTo(next);
        assertThat(actionPublications.published(principal.tenantId())).contains(next);
        actionPublications.withdraw(action.code(), 2);
        assertThat(actionPublications.active(principal.tenantId(), action.code())).isEmpty();
    }

    @Test void unsupportedCommandCandidateRemainsVisibleAndCannotBlockARegisteredReadOnlyHandler() {
        var request = new CommandDefinitionCreateRequest();
        request.setCode("private_input_" + UniqueIdGenerator.generate().toLowerCase()); request.setDisplayName("Unpublished private input");
        request.setModelCode(contract.modelCode()); request.setCmdRiskLevel("L1");
        request.setExecutionConfig("{\"type\":\"state_transition\",\"stateField\":\"name\",\"fromStates\":[\"Visible\"],\"toState\":\"Assigned\",\"inputFields\":[\"secret\"],\"permissions\":[\"fixture.action.manage\"]}");
        var created = commands.create(request); commands.publish(created.getPid());
        var sources = actionPublications.sources(contract.code());
        assertThat(sources).filteredOn(row -> row.sourceCode().equals(request.getCode())).singleElement().satisfies(row -> {
            assertThat(row.available()).isFalse(); assertThat(row.reason()).contains("writable public resource field");
        });
        assertThat(sources).filteredOn(row -> row.sourceCode().equals("core.visible-page")).singleElement().satisfies(row -> {
            assertThat(row.available()).isTrue(); assertThat(row.minTimeoutMs()).isEqualTo(contract.timeoutMs());
        });
        assertThatThrownBy(() -> actionValidator.describe(principal.tenantId(), contract.code(), "command", request.getCode()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"handler", "postActions", "preActions", "stateTransitionRules", "bpmTrigger", "computedFields"})
    void publicCommandSourceRejectsUndeclaredExecutionPaths(String option) {
        var request = new CommandDefinitionCreateRequest();
        request.setCode("unsupported_path_" + UniqueIdGenerator.generate().toLowerCase()); request.setDisplayName("Undeclared execution path");
        request.setModelCode(contract.modelCode()); request.setCmdRiskLevel("L1");
        request.setExecutionConfig("{\"type\":\"state_transition\",\"stateField\":\"name\",\"toState\":\"Assigned\",\"permissions\":[\"fixture.action.manage\"],\"" + option + "\":\"unexpected\"}");
        var created = commands.create(request); commands.publish(created.getPid());
        assertThatThrownBy(() -> actionValidator.describe(principal.tenantId(), contract.code(), "command", request.getCode()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unsupported public state-transition configuration");
    }

    @Test void publicCommandSourceRejectsAdditionalSideEffectsBeforeExecution() {
        var request = new CommandDefinitionCreateRequest();
        request.setCode("side_effect_" + UniqueIdGenerator.generate().toLowerCase()); request.setDisplayName("Additional effect source");
        request.setModelCode(contract.modelCode()); request.setCmdRiskLevel("L1");
        request.setExecutionConfig("{\"type\":\"state_transition\",\"stateField\":\"name\",\"fromStates\":[\"Visible\"],\"toState\":\"Assigned\",\"permissions\":[\"fixture.action.manage\"],\"sideEffects\":[{\"action\":\"create_record\",\"targetModel\":\"another_model\"}]}");
        var created = commands.create(request); commands.publish(created.getPid());
        assertThatThrownBy(() -> actionValidator.describe(principal.tenantId(), contract.code(), "command", request.getCode()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("additional side effects");
        assertThat(actionPublications.sources(contract.code())).filteredOn(row -> row.sourceCode().equals(request.getCode()))
                .singleElement().satisfies(row -> assertThat(row.available()).isFalse());
    }

    @Test void publishedBusinessSourceCannotFloatAndDisabledApplicationsCannotReceiveNewGrants() throws Exception {
        var action = publishBusinessAction("command"); var source = commands.findByCode(action.sourceCode());
        var changed = json.readValue(source.getExecutionConfig().replace("Assigned", "Changed"),
                com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO.class);
        changed.setCode(source.getCode()); changed.setDisplayName(source.getDisplayName());
        changed.setModelCode(source.getModelCode()); changed.setCmdRiskLevel(source.getCmdRiskLevel());
        changed.setInputSchema(json.readValue(source.getInputSchema(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
        assertThat(changed.getConsolidatedExecutionConfig())
                .containsEntry("type", "state_transition").containsEntry("toState", "Changed");
        // Plugin reimport updates the published source in place. Delete/create allocates
        // another historical version and PID, which would test identity drift instead.
        var imported = commandImporter.importCommand(changed, null, UniqueIdGenerator.generate(), principal.tenantId(),
                com.auraboot.framework.plugin.dto.imports.ImportRequest.ConflictStrategy.OVERWRITE, true);
        var replaced = commands.findByCode(action.sourceCode());
        assertThat(imported.getResourcePid()).isEqualTo(source.getPid());
        assertThat(replaced.getPid()).isEqualTo(source.getPid());
        assertThat(replaced.getVersion()).isEqualTo(source.getVersion());
        assertThat(replaced.getStatus()).isEqualTo(source.getStatus());
        assertThat(commands.findByCode(action.sourceCode()).getExecutionConfig()).contains("Changed");
        assertThatThrownBy(() -> installedBusinessCall(() -> publicActions.execute(principal, action.code(), "changed-source-denied", null, allowedPid, Map.of("zone", "A"))))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .satisfies(error -> assertThat(((org.springframework.web.server.ResponseStatusException) error).getStatusCode().value()).isEqualTo(409));
        assertThat(MetaContext.runWithCommandPermitScope("ALL", () -> data.getById(contract.modelCode(), allowedPid))).containsEntry("name", "Visible");
        management.disableApplication(principal.applicationPid());
        assertThatThrownBy(() -> actionPublications.grant(principal.installationPid(), action.code(), new OpenApiActionPolicy(true, action.effectFields())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Active application installation");
        assertThat(actionPublications.installationGrants(principal.installationPid())).filteredOn(row -> row.code().equals(action.code()))
                .singleElement().satisfies(row -> assertThat(row.policy()).isNotNull());
        actionPublications.revoke(principal.installationPid(), action.code());
        assertThat(actionPublications.installationGrants(principal.installationPid())).filteredOn(row -> row.code().equals(action.code()))
                .singleElement().satisfies(row -> assertThat(row.policy()).isNull());
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void explicitCommandAuthorityKeepsTargetFieldsAndAdvancesVersionAcrossStateAndInputWrites() {
        String permission = "fixture.machine.manage";
        var definition = new CommandDefinitionCreateRequest();
        definition.setCode("fixture_state_" + UniqueIdGenerator.generate().toLowerCase());
        definition.setDisplayName("Machine conditional command fixture"); definition.setModelCode(contract.modelCode());
        definition.setInputSchema("{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}");
        definition.setExecutionConfig("{\"type\":\"state_transition\",\"stateField\":\"zone\",\"fromStates\":[\"A\"],\"toState\":\"B\",\"inputFields\":[\"name\"],\"permissions\":[\"" + permission + "\"]}");
        var draft = commands.create(definition); commands.publish(draft.getPid());
        var resource = new OpenApiPublicationRegistry.ResourcePublication("fixture", contract.modelCode(), 1,
                Map.of("pid", "pid", "name", "name", "zone", "zone"));
        var publication = new OpenApiPublicationRegistry.CommandPublication("fixture.transition", definition.getCode(),
                permission, "fixture", Map.of("name", "name"), "fixture.changed", 1);
        var request = new CommandExecuteRequest(); request.setTargetRecordPid(allowedPid); request.setExpectedVersion(1);
        request.setPayload(Map.of("name", "Command result")); request.setClientRequestId("machine-fixture-" + UniqueIdGenerator.generate());
        MetaContext.setContext(principal.tenantId(), null, principal.subjectPid(), "machine");
        try (var machine = MachineDataAccessContext.begin(principal)) {
            var result = legacy.command(publication, resource, allowedPid, () -> MetaContext.runWithExternalCommandPermission(permission,
                    () -> executor.execute(definition.getCode(), request)));
            assertThat(result.getCommandCode()).isEqualTo(definition.getCode());
            var row = legacy.read(resource, () -> data.getById(contract.modelCode(), allowedPid));
            assertThat(row).containsEntry("zone", "B").containsEntry("name", "Command result").doesNotContainKey("secret");
            assertThat(((Number) row.get("row_version")).longValue()).isGreaterThan(1);
            assertThatThrownBy(() -> legacy.command(publication, resource, allowedPid,
                    () -> data.update(contract.modelCode(), deniedPid, new HashMap<>(Map.of("name", "Other target")))))
                    .isInstanceOf(com.auraboot.framework.meta.exception.MetaServiceException.class)
                    .hasRootCauseInstanceOf(com.auraboot.framework.meta.exception.MetaRecordNotFoundException.class);
        } finally {
            applyTestMetaContext();
            try { commands.delete(draft.getPid()); }
            finally { cleanupCommittedFixture(); }
        }
    }

    private <T> java.util.concurrent.Future<T> concurrent(java.util.concurrent.ExecutorService pool,
            java.util.concurrent.CountDownLatch ready, java.util.concurrent.CountDownLatch start, java.util.concurrent.Callable<T> action) {
        return pool.submit(() -> {
            MetaContext.setContext(principal.tenantId(), null, principal.subjectPid(), "machine");
            try (var machine = MachineDataAccessContext.begin(principal)) {
                ready.countDown();
                if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Concurrent transaction start timed out");
                return action.call();
            } finally { MetaContext.clear(); }
        });
    }
    private OpenApiEndpointContract publishControllerEndpoint(String candidate, String binding) {
        String code = "endpoint." + UniqueIdGenerator.generate().toLowerCase();
        var source = endpointPublications.source(candidate, binding);
        var endpoint = new OpenApiEndpointContract(code, candidate, source.candidate().adapterCode(), binding, 1,
                source.fingerprint(), Map.of("invoke", code + ".invoke"), Math.max(5000, source.minTimeoutMs()), 2);
        endpointPublications.saveDraft(code, new OpenApiEndpointPublicationService.DraftRequest(0, endpoint));
        endpointPublications.publish(code, 1);
        endpointPublications.grant(principal.installationPid(), code, new OpenApiEndpointPolicy(true));
        var scopes = new HashSet<>(principal.scopes()); scopes.add(code + ".invoke");
        management.updateScopes(principal.installationPid(), new UpdateInstallationScopesRequest(scopes));
        var issued = integrationTokens.create(principal.installationPid(),
                new com.auraboot.framework.openplatform.dto.IntegrationTokenDtos.Create("Controller endpoint", "never", null), "endpoint-token-" + code);
        rawToken = issued.token();
        var identity = auth.findToken(secrets.sha256(rawToken), OpenPlatformTokenService.AUDIENCE, Instant.now());
        principal = new MachinePrincipal(identity.tenantId(), identity.tokenPid(), "machine", scopes,
                identity.applicationPid(), identity.installationPid(), identity.environment(), identity.tokenPid());
        return endpoint;
    }

    @Test void controllerEndpointsUseActualRoutesCurrentRowsAndDirectPermanentTokenOverHttp() throws Exception {
        var get = publishControllerEndpoint("dynamic.get", contract.code());
        var list = publishControllerEndpoint("dynamic.list", contract.code());
        assertThat(endpointPublications.candidates("/api/dynamic")).anySatisfy(candidate -> {
            assertThat(candidate.code()).isEqualTo("dynamic.get");
            assertThat(candidate.internalPath()).isEqualTo("/api/dynamic/{pageKey}/{recordPid}");
        });
        String path = get.path().replace("{recordPid}", allowedPid);
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).header("Authorization", "Bearer " + rawToken))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.name").value("Visible"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.secret").doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().exists("ETag"));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(get.path().replace("{recordPid}", deniedPid)).header("Authorization", "Bearer " + rawToken))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(list.path()).param("limit","10")
                .param("filters", "[{\"field\":\"zone\",\"operator\":\"EQ\",\"value\":\"A\"}]").header("Authorization", "Bearer " + rawToken))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items.length()").value(1));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Authorization", "Bearer " + rawToken))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    }

    @Test void controllerEndpointsPreserveWriteEtagReplayAndIndependentExternalRouteKeys() {
        var create = publishControllerEndpoint("dynamic.create", contract.code());
        var other = publishControllerEndpoint("dynamic.create", contract.code());
        var update = publishControllerEndpoint("dynamic.update", contract.code());
        var delete = publishControllerEndpoint("dynamic.delete", contract.code());
        var first = installedBusinessCall(() -> publicEndpoints.invoke(principal, create.code(), "POST", null, Map.of(),
                Map.of("name", "Created by endpoint", "zone", "A"), "same-client-key", null));
        var replay = installedBusinessCall(() -> publicEndpoints.invoke(principal, create.code(), "POST", null, Map.of(),
                Map.of("zone", "A", "name", "Created by endpoint"), "same-client-key", null));
        var separate = installedBusinessCall(() -> publicEndpoints.invoke(principal, other.code(), "POST", null, Map.of(),
                Map.of("name", "Created by endpoint", "zone", "A"), "same-client-key", null));
        assertThat(first.status()).isEqualTo(201); assertThat(replay.replay()).isTrue(); assertThat(replay.body()).isEqualTo(first.body());
        assertThat(separate.body().get("pid")).isNotEqualTo(first.body().get("pid"));
        String pid = first.body().get("pid").toString();
        var changed = installedBusinessCall(() -> publicEndpoints.invoke(principal, update.code(), "PATCH", pid, Map.of(), Map.of("name", "Updated endpoint"),
                "update-endpoint-key", first.headers().get("ETag")));
        assertThat(changed.body()).containsEntry("name", "Updated endpoint");
        var removed = installedBusinessCall(() -> publicEndpoints.invoke(principal, delete.code(), "DELETE", pid, Map.of(), null,
                "delete-endpoint-key", changed.headers().get("ETag")));
        assertThat(removed.status()).isEqualTo(204);
        assertThatThrownBy(() -> installedBusinessCall(() -> publicEndpoints.invoke(principal, update.code(), "PATCH", allowedPid, Map.of(), Map.of("name","Stale"),
                "stale-endpoint-key", first.headers().get("ETag")))).isInstanceOf(OpenApiPreconditionException.class);
    }

    @Test void controllerEndpointAuthorityNeverReplacesResourceGrantsAndRevocationIsImmediate() {
        var get = publishControllerEndpoint("dynamic.get", contract.code());
        assertThat(installedBusinessCall(() -> publicEndpoints.invoke(principal,get.code(),"GET",allowedPid,Map.of(),null,null,null)).body()).containsEntry("name","Visible");
        endpointPublications.revoke(principal.installationPid(), get.code());
        assertThatThrownBy(() -> installedBusinessCall(() -> publicEndpoints.invoke(principal,get.code(),"GET",allowedPid,Map.of(),null,null,null))).isInstanceOf(AccessDeniedException.class);
    }

    @Test void controllerEndpointRejectsResourceRevocationEvenWithItsOwnGrant() {
        var get = publishControllerEndpoint("dynamic.get", contract.code()); publications.revoke(principal.installationPid(), contract.code());
        assertThatThrownBy(() -> installedBusinessCall(() -> publicEndpoints.invoke(principal,get.code(),"GET",allowedPid,Map.of(),null,null,null))).isInstanceOf(AccessDeniedException.class);
    }

    @Test void controllerEndpointChangedSourceReturns409OverHttpWithoutLeakingStorageOrFingerprint() throws Exception {
        var endpoint = publishControllerEndpoint("dynamic.get", contract.code());
        publishEvents();
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(endpoint.path().replace("{recordPid}", allowedPid))
                        .header("Authorization", "Bearer " + rawToken))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("open_api_endpoint_source_changed"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(
                        org.hamcrest.Matchers.allOf(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(contract.modelCode())),
                                org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(endpoint.sourceFingerprint())))));
    }

    @Test void controllerEndpointPublicationLifecycleExportsPublicContractsWithoutInternalRoutes() {
        var endpoint = publishControllerEndpoint("dynamic.get", contract.code());
        assertThat(endpointPublications.list()).extracting(OpenApiEndpointPublicationService.View::code).contains(endpoint.code());
        assertThat(endpointPublications.installationGrants(principal.installationPid())).filteredOn(row -> row.code().equals(endpoint.code())).singleElement().satisfies(row -> assertThat(row.policy().invoke()).isTrue());
        assertThat(json.valueToTree(endpointPublications.document(endpoint.code())).toString()).contains(endpoint.path(),"integrationToken","oauth2ClientCredentials")
                .doesNotContain("/api/dynamic",contract.modelCode(),endpoint.sourceFingerprint());
        assertThat(endpointPublications.types(endpoint.code())).contains("EndpointRequest", "EndpointResult", "Resource").doesNotContain("/api/dynamic");
        var next = new OpenApiEndpointContract(endpoint.code(),endpoint.candidateCode(),endpoint.adapterCode(),endpoint.bindingCode(),2,endpoint.sourceFingerprint(),endpoint.scopes(),6000,3);
        endpointPublications.saveDraft(endpoint.code(),new OpenApiEndpointPublicationService.DraftRequest(1,next)); endpointPublications.publish(endpoint.code(),2);
        assertThat(endpointPublications.active(principal.tenantId(),endpoint.code())).contains(next);
        endpointPublications.withdraw(endpoint.code(),2); assertThat(endpointPublications.active(principal.tenantId(),endpoint.code())).isEmpty();
        for (String artifact : List.of("openapi", "types")) assertThatThrownBy(() -> {
            if ("openapi".equals(artifact)) endpointPublications.document(endpoint.code()); else endpointPublications.types(endpoint.code());
        }).isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .satisfies(error -> assertThat(((org.springframework.web.server.ResponseStatusException) error).getStatusCode().value()).isEqualTo(404));
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void controllerEndpointDelegatesRegisteredBusinessCommandWithItsSeparateDataAuthority() {
        var action = publishBusinessAction("command"); var endpoint = publishControllerEndpoint("command.execute", action.code());
        try {
        String etag = installedBusinessCall(() -> resources.get(principal,contract.code(),allowedPid)).headers().get("ETag");
        var result = installedBusinessCall(() -> publicEndpoints.invoke(principal,endpoint.code(),"POST",null,Map.of(),Map.of("targetPid",allowedPid,"input",Map.of("zone","A")),"controller-command-key",etag));
        assertThat(((Map<?, ?>) result.body().get("resource")).get("name")).isEqualTo("Assigned");
        assertThat(endpointPublications.types(endpoint.code())).contains("ActionRequest","EndpointResult");
        assertThat(endpointPublications.document(endpoint.code()).get("paths").toString()).contains(endpoint.path());
        } finally {
            endpointPublications.withdraw(endpoint.code(),1); actionPublications.withdraw(action.code(),1);
            try { commands.delete(commands.findByCode(action.sourceCode()).getPid()); }
            finally { cleanupCommittedFixture(); }
        }
    }

}
