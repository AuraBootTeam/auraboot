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
    @Autowired private CommandExecutor executor;
    @Autowired private OpenApiLegacyAuthority legacy;
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
    @Autowired private com.auraboot.framework.webhook.mapper.WebhookDeliveryLogMapper deliveries;

    @Autowired private com.auraboot.framework.meta.service.impl.CommandFieldMapExecutor fieldMap;
    @Autowired private com.auraboot.framework.meta.service.impl.CommandStateCheckExecutor stateCheck;

    @Autowired private com.auraboot.framework.meta.service.impl.CommandSideEffectExecutor sideEffects;

    @Autowired private com.auraboot.framework.meta.service.impl.CommandCascadeDeleteExecutor cascadeDeletes;

    private OpenApiResourceContract contract;
    private MachinePrincipal principal;
    private String credentialPid;
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
                        .matches("softBatchDeleteKeepsTombstonesAndRollsBackPartialFailure|softCascadeDeletesKeepTombstonesAndOwnerEvents")))
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
        String code = contract.code() + ".binding";
        var readOnly = new OpenApiResourceContract(code, contract.modelCode(), 1, Set.of(Operation.GET),
                Map.of(Operation.GET, contract.scopes().get(Operation.GET)), DataPolicy.TENANT_WIDE,
                Map.of("pid", contract.fields().get("pid"), "name", new Field("name", Type.STRING,
                        true, false, false, false, Set.of(), false, null, Map.of(), null)), 1, 1, 5000, Set.of());
        publications.saveDraft(code, new OpenApiPublicationService.DraftRequest(0, readOnly));
        publications.publish(code, 1);
        publications.grant(principal.installationPid(), code,
                new OpenApiInstallationPolicy(Set.of(Operation.GET), Set.of("pid", "name"), Set.of(), List.of()));
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
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).header("Authorization", "Bearer " + rawToken))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("open_api_source_changed"));
        applyTestMetaContext();
        update.setExtension(Map.of("fields", originalFields));
        modelAdministration.updateModel(modelPid, update);
        var restored = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                        .header("Authorization", "Bearer " + rawToken))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
        assertThat(restored.getResponse().getContentAsString()).isEqualTo(baseline.getResponse().getContentAsString());
        assertThat(restored.getResponse().getHeader("ETag")).isEqualTo(baseline.getResponse().getHeader("ETag"));
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
        var received = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var signatures = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var timestamps = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var receiver = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/receive", exchange -> {
            received.add(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            signatures.add(exchange.getRequestHeaders().getFirst("X-Webhook-Signature"));
            timestamps.add(exchange.getRequestHeaders().getFirst("X-Webhook-Timestamp"));
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
            MetaContext.runWithCommandPermitScope("ALL", () -> data.create(contract.modelCode(),
                    new HashMap<>(Map.of("name", "Jittered retry", "zone", "A", "secret", "Never public"))));
            var source = sourceEnvelope();
            eventDelivery.deliver(source, modelEvents, "jitter-consumer-lease");
            var claimed = deliveries.claimReady(50, "jitter-worker", "jitter-first-claim", Instant.now().plusSeconds(30))
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
            var recoveredClaim = new java.util.concurrent.atomic.AtomicReference<com.auraboot.framework.webhook.entity.WebhookDeliveryLog>();
            org.awaitility.Awaitility.await().pollInSameThread().atMost(java.time.Duration.ofSeconds(5)).until(() -> {
                deliveries.claimReady(50, "jitter-worker", "jitter-second-claim", Instant.now().plusSeconds(30))
                        .stream().filter(log -> source.eventId().equals(log.getEventId())).findFirst().ifPresent(recoveredClaim::set);
                return recoveredClaim.get() != null;
            });
            webhookDispatcher.processClaimed(recoveredClaim.get());
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
        } finally {
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
            } finally { eventFaultObserver.fail = false; }
            var recovered = data.batchCreate(contract.modelCode(), List.of(new HashMap<>(Map.of("name", "Recovered", "zone", "A"))));
            assertThat(recovered.getSuccess()).isEqualTo(1); assertThat(sourceEvents()).hasSize(1);
            return null;
        });
    }

    @Test void humanSingleCrudUsesTheSamePublishedSourceEventHook() {
        publishEvents();
        MetaContext.runWithCommandPermitScope("ALL", () -> {
            String pid = data.create(contract.modelCode(), new HashMap<>(Map.of("name", "Human", "zone", "A")))
                    .get("pid").toString();
            data.update(contract.modelCode(), pid, new HashMap<>(Map.of("name", "Human update")));
            data.delete(contract.modelCode(), pid);
            return null;
        });
        assertThat(sourceEvents()).hasSize(3);
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
        MetaContext.setContext(principal.tenantId(), null, principal.subjectPid(), "machine");
        try (var machine = MachineDataAccessContext.begin(principal)) {
            var created = resources.write(principal, contract.code(), Operation.CREATE, null, "reference-ok-001", null,
                    Map.of("name", "Related row", "zone", "A", "related", allowedPid));
            assertThat(created.body()).containsEntry("related", allowedPid);
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

    @Test void revocationIsEnforcedAgainstAnAlreadyAuthenticatedPrincipal() {
        management.revokeCredential(principal.installationPid(), credentialPid);
        try (var machine = MachineDataAccessContext.begin(principal)) {
            assertThatThrownBy(() -> authorizer.authorize(principal, contract.code(), Operation.GET)).isInstanceOf(AccessDeniedException.class);
        }
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
        try {
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(collection + "/" + allowedPid))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
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

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void machineHttpAuditPersistsPublicActorAndOriginalReplayIntent() throws Exception {
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
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(collection + "/" + pid)
                    .header("Authorization", bearer).header("X-Request-Id", origin + "-stale")
                    .header("Idempotency-Key", origin + "-patch").contentType("application/json").content("{\"name\":\"Denied\"}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isPreconditionFailed());
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(collection + "/" + pid)
                    .header("Authorization", bearer).header("X-Request-Id", origin + "-delete")
                    .header("Idempotency-Key", origin + "-delete").header("If-Match", created.getHeader("ETag")))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(collection + "/" + pid)
                    .header("Authorization", bearer).header("X-Request-Id", origin + "-delete-replay")
                    .header("Idempotency-Key", origin + "-delete").header("If-Match", created.getHeader("ETag")))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());
            applyTestMetaContext();
            for (var suffix : List.of("", "-replay", "-stale", "-delete", "-delete-replay")) {
                var audits = management.listCallAudits(principal.installationPid(), origin + suffix, null, 20);
                assertThat(audits).hasSize(1);
                var audit = audits.getFirst();
                assertThat(audit.actorKind()).isEqualTo("machine");
                assertThat(audit.applicationPid()).isEqualTo(principal.applicationPid());
                assertThat(audit.installationPid()).isEqualTo(principal.installationPid());
                assertThat(audit.resource()).isEqualTo(contract.code());
                assertThat(audit.recordPid()).isEqualTo(pid);
                String operation = suffix.equals("-stale") ? "update" : suffix.startsWith("-delete") ? "delete" : "create";
                assertThat(audit.operation()).isEqualTo(operation);
                assertThat(audit.capability()).isEqualTo("resource." + contract.code() + "." + operation);
                assertThat(audit.status()).isEqualTo(suffix.equals("-stale") ? 412 : suffix.startsWith("-delete") ? 204 : 201);
                assertThat(audit.idempotentReplay()).isEqualTo(suffix.equals("-stale") ? null : suffix.endsWith("replay"));
                assertThat(audit.durationMs()).isNotNegative();
                assertThat(json.writeValueAsString(audit)).doesNotContain(rawToken, "tokenPid", "tenantId", "Secret-free audit");
                String persisted = eventJdbc.queryForObject("SELECT business_details::text FROM ab_open_api_call_audit WHERE tenant_id=? AND installation_pid=? AND request_id=?",
                        String.class, principal.tenantId(), principal.installationPid(), origin + suffix);
                assertThat(json.readTree(persisted).path("actorKind").asText()).isEqualTo("machine");
                assertThat(persisted).doesNotContain(rawToken, "tokenPid", "tenantId", "Secret-free audit");
            }
            assertThat(eventJdbc.queryForObject("SELECT COUNT(*) FROM ab_audit_trail WHERE tenant_id=? AND actor_name=?",
                    Long.class, principal.tenantId(), principal.tokenPid())).isZero();
        } finally { cleanupCommittedFixture(); }
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
}
