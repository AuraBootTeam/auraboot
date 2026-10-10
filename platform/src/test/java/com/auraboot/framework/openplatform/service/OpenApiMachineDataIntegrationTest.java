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

    @Autowired private org.springframework.jdbc.core.JdbcTemplate eventJdbc;
    @Autowired private org.springframework.transaction.PlatformTransactionManager eventTransactions;

    @org.springframework.boot.test.context.TestConfiguration
    static class EventFaultConfiguration {
        @org.springframework.context.annotation.Bean
        EventFaultObserver eventFaultObserver() { return new EventFaultObserver(); }
    }

    static class EventFaultObserver implements ModelMutationObserver {
        boolean fail;
        @Override public void observe(ModelDefinition model, String pid, Operation operation) {
            if (fail) throw new IllegalStateException("Fixture event persistence failure");
        }
    }

    @Autowired private EventFaultObserver eventFaultObserver;
    @Autowired private OpenApiModelEventConsumer modelEvents;
    @Autowired private com.auraboot.framework.integration.ReliableEventDeliveryExecutor eventDelivery;
    @Autowired private com.auraboot.framework.webhook.service.WebhookService webhooks;
    @Autowired private com.auraboot.framework.webhook.service.WebhookDispatcher webhookDispatcher;
    @Autowired private com.auraboot.framework.webhook.mapper.WebhookDeliveryLogMapper deliveries;

    private OpenApiResourceContract contract;
    private MachinePrincipal principal;
    private String credentialPid;
    private String allowedPid;
    private String deniedPid;
    private String rawToken;

    @BeforeEach void fixture() {
        grantCommittedPermissionToTestRole("sys.connector.update", "system", "connector", "update", "Manage Open Platform");
        String suffix = UniqueIdGenerator.generate().toLowerCase();
        String modelCode = "open_machine_" + suffix;
        var reference = FieldDefinition.RefTarget.builder().targetEntity(modelCode).valueField("pid").build();
        models.saveDefinition(ModelDefinition.builder().code(modelCode).tableName("mt_" + modelCode)
                .displayName("Machine permission fixture").status("published").sourceType("physical").primaryKey("id")
                .capabilities(ModelCapabilities.fullPhysical().toBuilder()
                        .filterableFields(List.of("name", "zone", "secret")).sortableFields(List.of("name", "zone", "secret")).build())
                .fields(List.of(FieldDefinition.builder().code("name").columnName("name").dataType("STRING").displayName("Name").sortable(true).filterable(true).build(),
                        FieldDefinition.builder().code("zone").columnName("zone").dataType("STRING").displayName("Zone").sortable(true).filterable(true).build(),
                        FieldDefinition.builder().code("secret").columnName("secret").dataType("STRING").displayName("Secret").sortable(true).filterable(true).build(),
                        FieldDefinition.builder().code("counter").columnName("counter").dataType("INTEGER").displayName("Counter").build(),
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
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void durableModelConsumerProjectsEachInstallationAndRechecksQueuedAuthority() throws Exception {
        publishEvents();
        var received = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var signatures = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var signedAt = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var receiver = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/receive", exchange -> {
            received.add(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            signatures.add(exchange.getRequestHeaders().getFirst("X-Webhook-Signature"));
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
