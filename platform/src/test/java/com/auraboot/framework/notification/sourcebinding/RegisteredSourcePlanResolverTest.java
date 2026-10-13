package com.auraboot.framework.notification.sourcebinding;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.notification.sourcebinding.SourceBindingTypes.Approval;
import com.auraboot.framework.notification.sourcebinding.SourceBindingTypes.Binding;
import com.auraboot.framework.notification.sourcebinding.SourceBindingTypes.Entity;
import com.auraboot.framework.notification.sourcebinding.SourceBindingTypes.Exact;
import com.auraboot.framework.notification.sourcebinding.SourceBindingTypes.Owner;
import com.auraboot.framework.notification.sourcebinding.SourceBindingTypes.Principal;
import com.auraboot.framework.notification.sourcebinding.SourceBindingTypes.Source;
import com.auraboot.framework.notification.sourcebinding.SourceBindingTypes.Target;
import com.auraboot.framework.notification.sourcebinding.SourceBindingTypes.Verification;
import com.auraboot.framework.plugin.extension.BackgroundDataAccessor;
import com.auraboot.framework.plugin.extension.DataPage;
import com.auraboot.framework.plugin.extension.NotificationDeliveryAccessor;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.auraboot.framework.notification.sourcebinding.SourceBindingTypes.require;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Hermetic host-authority contract tests. No PG, real notification, or user policy proof. */
class RegisteredSourcePlanResolverTest {
    Fixture f;
    @BeforeEach
    void setup() {
        f = new Fixture();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    }
    @AfterEach
    void cleanup() {
        TransactionSynchronizationManager.clear();
        MetaContext.clear();
    }
    @Test
    void disabledAndMissingTenantHaveNoReadOrWrite() {
        var p = new HostSourceBindingProperties();
        var r = new RegisteredSourcePlanResolver(p, f, new ImportedNotificationMetadataValidator());
        r.initialize();
        assertThat(r.resolve(1, "anything")).isEmpty();
        assertThat(f.reads).isZero();
        assertThat(f.resolver.resolve(2, SourceBindingCanonical.reference("notice", "src", 1))).isEmpty();
    }
    @Test
    void otherResolverProtocolsRemainUnclaimed() {
        assertThat(f.resolver.resolve(1, "trusted-fact-reference")).isEmpty();
        assertThat(f.resolver.resolve(1, null)).isEmpty();
        assertThat(f.reads).isZero();
        assertThatThrownBy(() -> f.resolver.resolve(1, "ns1.invalid")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void ordinaryNotificationResolverRemainsFunctionalAlongsideRegisteredProtocol() {
        String ref = "trusted-other-reference";
        com.auraboot.framework.notification.mapper.NotificationMapper mapper = mock(com.auraboot.framework.notification.mapper.NotificationMapper.class);
        var writer = new com.auraboot.framework.notification.service.impl.StrictInAppDeliveryWriter(mapper, mock(com.auraboot.framework.notification.service.NotificationSseService.class));
        com.auraboot.framework.plugin.extension.integration.ReliableIntegrationAccessor reliable = mock(com.auraboot.framework.plugin.extension.integration.ReliableIntegrationAccessor.class);
        var plan = new com.auraboot.framework.notification.service.NotificationDeliveryPlanResolver.Plan(1, ref, "user1", "ordinary-config", "ordinary", "object", java.time.Instant.EPOCH, "Title", "Body");
        com.auraboot.framework.notification.service.NotificationDeliveryPlanResolver ordinary =(t, r) -> r.equals(ref) ? java.util.Optional.of(plan) : java.util.Optional.empty();
        var host = new com.auraboot.framework.notification.service.impl.PluginNotificationDeliveryAccessorImpl(List.of(f.resolver, ordinary), reliable, writer);
        org.springframework.test.util.ReflectionTestUtils.setField(host, "enabled", true);
        MetaContext.setContext(1L, 0L, "host", "host");
        TransactionSynchronizationManager.initSynchronization();
        when(mapper.findActiveRecipient(1, "user1")).thenReturn(1L);
        when(reliable.enqueueIdempotent(any())).thenReturn(true);
        assertThat(host.enqueue(new NotificationDeliveryAccessor.Request(1, ref, NotificationDeliveryAccessor.Channel.IN_APP)).status()).isEqualTo(NotificationDeliveryAccessor.Status.QUEUED);
        verify(reliable).enqueueIdempotent(any());
        assertThat(f.reads).isZero();
    }
    @Test
    void completeExplicitUsersHaveStableLocatorAndSourceTimestamp() {
        var plans = f.resolver.plan(f.binding, "src");
        assertThat(plans).hasSize(2);
        assertThat(plans.get(0).recipientUserPid()).isEqualTo("user1");
        assertThat(plans.get(1).recipientUserPid()).isEqualTo("user2");
        assertThat(plans.get(0).occurredAt()).hasToString("2026-10-13T01:00:00Z");
        assertThat(f.resolver.resolve(1, plans.getFirst().reference())).contains(plans.getFirst());
        assertThat(plans.getFirst().reference()).isEqualTo(SourceBindingCanonical.reference("notice", "src", 1));
    }
    @Test
    void everySelectedUserIsCheckedWithoutOwnerFilter() {
        f.links = 2;
        assertThatThrownBy(() -> f.resolver.plan(f.binding, "src")).isInstanceOf(IllegalArgumentException.class);
        assertThat(f.lastFilters).containsEntry("user", "user1").doesNotContainKey("owner");
    }
    @Test
    void oneWrongOwnerInExplicitListRejectsWholePlan() {
        f.records.get("link/l2").put("owner", "other");
        assertThatThrownBy(() -> f.resolver.plan(f.binding, "src")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void inactiveContactAndForeignTargetFailClosed() {
        f.records.get("contact/c2").put("state", "inactive");
        assertThatThrownBy(() -> f.resolver.plan(f.binding, "src")).isInstanceOf(IllegalArgumentException.class);
        f.records.get("contact/c2").put("state", "active");
        f.records.get("target/t1").put("tenant_id", 2L);
        assertThatThrownBy(() -> f.resolver.plan(f.binding, "src")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void invalidPlatformAccountFailsBeforeAnyPlan() {
        f.invalidUser = 2;
        assertThatThrownBy(() -> f.resolver.plan(f.binding, "src")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void changedMetadataIsNotApprovedBySelfHash() {
        ((com.fasterxml.jackson.databind.node.ObjectNode) f.graphs.get("source").get("model")).put("table_name", "changed_table");
        assertThatThrownBy(() -> f.resolver.plan(f.binding, "src")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void configAndSourceChangedKeepSameReferenceAndAlterEnvelopeVersion() {
        var old = f.resolver.plan(f.binding, "src").getFirst();
        ((com.fasterxml.jackson.databind.node.ObjectNode) f.template).put("body_template", "Changed");
        var changed = f.resolver.plan(f.binding, "src").getFirst();
        assertThat(changed.reference()).isEqualTo(old.reference());
        assertThat(changed.configurationVersion()).isNotEqualTo(old.configurationVersion());
        f.records.get("source/src").put("fact", "new");
        assertThat(f.resolver.plan(f.binding, "src").getFirst().configurationVersion()).isNotEqualTo(changed.configurationVersion());
    }
    @Test
    void evaluationCountersDoNotInventConfigVersion() {
        String hash = f.resolver.plan(f.binding, "src").getFirst().configurationVersion();
        ((com.fasterxml.jackson.databind.node.ObjectNode) f.rule).put("send_count", 100).put("last_evaluated_at", "now");
        assertThat(f.resolver.plan(f.binding, "src").getFirst().configurationVersion()).isEqualTo(hash);
    }
    @Test
    void arbitraryRecipientAndAlternateReferenceEncodingDenied() {
        assertThat(f.resolver.resolve(1, SourceBindingCanonical.reference("notice", "src", 99))).isEmpty();
        String ref = SourceBindingCanonical.reference("notice", "src", 1);
        assertThatThrownBy(() -> f.resolver.resolve(1, ref + "=")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceBindingCanonical.locator("ns1." + Base64.getUrlEncoder().withoutPadding().encodeToString("[\"notice\",\"src\",1.0]".getBytes()))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void invalidRecipientListsAndFiltersNeverFallback() {
        for (String users : List.of("", "1,1", "01", "1, 2", "-1", "99999999999999999999999")) assertThatThrownBy(() -> RegisteredSourcePlanResolver.users(users)).isInstanceOf(IllegalArgumentException.class);
        ((com.fasterxml.jackson.databind.node.ObjectNode) f.rule).set("condition_filter", SourceBindingCanonical.tree(List.of(Map.of("fieldName", "state", "operator", "NE", "value", "pending"))));
        assertThatThrownBy(() -> f.resolver.plan(f.binding, "src")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void duplicateAndEmptyTargetsCannotProducePlan() {
        f.records.get("source/src").put("targets", List.of("t1", "t1"));
        assertThatThrownBy(() -> f.resolver.plan(f.binding, "src")).isInstanceOf(IllegalArgumentException.class);
        f.records.get("source/src").put("targets", List.of());
        assertThatThrownBy(() -> f.resolver.plan(f.binding, "src")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void ordinarySdkTransactionCannotAuthorizeRegisteredSource() {
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThatThrownBy(() -> f.resolver.resolve(1, SourceBindingCanonical.reference("notice", "src", 1))).isInstanceOf(IllegalArgumentException.class);
        assertThat(f.reads).isZero();
    }
    @Test
    void sourceOutsideTransactionRejected() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThatThrownBy(() -> f.resolver.plan(f.binding, "src")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void unknownTemplateVariableAndOtherChannelRejected() {
        ((com.fasterxml.jackson.databind.node.ObjectNode) f.template).put("body_template", "${unknown}");
        assertThatThrownBy(() -> f.resolver.plan(f.binding, "src")).isInstanceOf(IllegalArgumentException.class);
        ((com.fasterxml.jackson.databind.node.ObjectNode) f.template).put("body_template", "Body").put("channels", "[\"email\"]");
        assertThatThrownBy(() -> f.resolver.plan(f.binding, "src")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void strongApprovalModeAndDuplicateBindingReject() {
        assertThatThrownBy(() -> new Approval("PACKAGE_SHA", "source", "a".repeat(64), List.of(f.owner), Set.of())).isInstanceOf(IllegalArgumentException.class);
        var p = new HostSourceBindingProperties();
        p.setEnabled(true);
        p.setBindings(List.of(f.binding, f.binding));
        assertThatThrownBy(() -> new RegisteredSourcePlanResolver(p, f, new ImportedNotificationMetadataValidator()).initialize()).isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void occurrenceUsesExplicitOffsetOrTypedTimestampWithoutTimezoneGuess() {
        f.records.get("source/src").put("at", java.sql.Timestamp.from(java.time.Instant.parse("2026-10-13T01:00:00.123456Z")));
        assertThat(f.resolver.plan(f.binding, "src").getFirst().occurredAt()).hasToString("2026-10-13T01:00:00.123456Z");
        f.records.get("source/src").put("at", "2026-10-13T01:00:00");
        assertThatThrownBy(() -> f.resolver.plan(f.binding, "src")).isInstanceOf(java.time.format.DateTimeParseException.class);
    }
    @Test
    void malformedAndDuplicateJsonAndTypeDistinctHashes() {
        assertThatThrownBy(() -> SourceBindingCanonical.parse("{\"x\":1,\"x\":2}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceBindingCanonical.parse("{} {}")).isInstanceOf(IllegalArgumentException.class);
        assertThat(SourceBindingCanonical.hash(Map.of("a", true))).isNotEqualTo(SourceBindingCanonical.hash(Map.of("a", "true")));
    }
    @Test
    void workerBadSecondUserHasZeroEnqueueAndRestoresContext() {
        f.records.get("link/l2").put("owner", "wrong");
        NotificationDeliveryAccessor delivery = mock(NotificationDeliveryAccessor.class);
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        MetaContext.setContext(9L, 10L, "prior", "prior");
        var worker = new RegisteredSourceWorker(f.resolver, f, delivery, tx);
        assertThatThrownBy(() -> worker.process(1, "notice", "src")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(delivery);
        verify(tx).rollback(any());
        assertThat(MetaContext.getCurrentTenantId()).isEqualTo(9);
        assertThat(MetaContext.getCurrentUserPid()).isEqualTo("prior");
    }
    @Test
    void workerAllPlansUseSameSerializableUnitAndBlockedResultRollsBack() {
        NotificationDeliveryAccessor delivery = mock(NotificationDeliveryAccessor.class);
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenAnswer(a -> {
            TransactionDefinition d = a.getArgument(0);
            assertThat(d.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_SERIALIZABLE);
            assertThat(d.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            return new SimpleTransactionStatus();
        });
        when(delivery.enqueue(any())).thenReturn(new NotificationDeliveryAccessor.Result(NotificationDeliveryAccessor.Status.QUEUED, null, "k"));
        assertThat(new RegisteredSourceWorker(f.resolver, f, delivery, tx).process(1, "notice", "src")).isEqualTo(2);
        verify(tx).commit(any());
        when(delivery.enqueue(any())).thenReturn(NotificationDeliveryAccessor.Result.blocked(NotificationDeliveryAccessor.Reason.DISABLED));
        assertThatThrownBy(() -> new RegisteredSourceWorker(f.resolver, f, delivery, tx).process(1, "notice", "src")).isInstanceOf(IllegalStateException.class);
        verify(tx).rollback(any());
    }
    @Test
    void trustedHashStillCannotAuthorizeDanglingDuplicateOrComputedBinding() {
        var graph =(com.fasterxml.jackson.databind.node.ObjectNode) f.graphs.get("source");
        var fields =(com.fasterxml.jackson.databind.node.ArrayNode) graph.path("fields");
        ((com.fasterxml.jackson.databind.node.ObjectNode) fields.get(0)).set("feature", SourceBindingCanonical.tree(Map.of("computeExpression", "secret SQL")));
        f.reapprove();
        assertThatThrownBy(() -> f.resolver.plan(f.binding, "src")).isInstanceOf(IllegalArgumentException.class);
        ((com.fasterxml.jackson.databind.node.ObjectNode) fields.get(0)).set("feature", SourceBindingCanonical.tree(Map.of()));
        var bindings =(com.fasterxml.jackson.databind.node.ArrayNode) graph.path("bindings");
        ((com.fasterxml.jackson.databind.node.ObjectNode) bindings.get(1)).put("alias_code", bindings.get(0).path("alias_code").asText());
        f.reapprove();
        assertThatThrownBy(() -> f.resolver.plan(f.binding, "src")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void canonicalPhysicalFeatureValidationRejectsUnknownMissingAndNestedProjection() {
        var field =(com.fasterxml.jackson.databind.node.ObjectNode) f.graphs.get("source").path("fields").get(0);
        for (Object invalid : List.of(Map.of("virtual_type", "transient"), Map.of("virtualType", "transient"), Map.of("rollUp", Map.of()), List.of("not-object"))) {
            field.set("feature", SourceBindingCanonical.tree(invalid));
            f.reapprove();
            assertThatThrownBy(() -> f.resolver.plan(f.binding, "src")).isInstanceOf(IllegalArgumentException.class);
        }
        field.remove("feature");
        f.reapprove();
        assertThatThrownBy(() -> f.resolver.plan(f.binding, "src")).isInstanceOf(IllegalArgumentException.class);
        field.put("feature", "{}");
        field.set("extension", SourceBindingCanonical.tree(Map.of("extension", Map.of("jsonbColumn", "payload", "jsonbPath", "value"))));
        f.reapprove();
        assertThatThrownBy(() -> f.resolver.plan(f.binding, "src")).isInstanceOf(IllegalArgumentException.class);
        field.set("extension", SourceBindingCanonical.tree(Map.of()));
        f.reapprove();
        assertThat(f.resolver.plan(f.binding, "src")).hasSize(2);
    }
    @Test
    void wrongImportedOwnerOrVersionFailsEvenWithTrustedShape() {
        var graph = f.graphs.get("source");
        ((com.fasterxml.jackson.databind.node.ObjectNode) graph.path("plugins").get(0)).put("version", "2.0.0");
        f.reapprove();
        assertThatThrownBy(() -> f.resolver.plan(f.binding, "src")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void incompleteOrDuplicateScansCannotEnqueue() {
        NotificationDeliveryAccessor delivery = mock(NotificationDeliveryAccessor.class);
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        f.scanPage = new DataPage(List.of(f.records.get("source/src")), 101, 1, 100);
        assertThatThrownBy(() -> new RegisteredSourceWorker(f.resolver, f, delivery, tx).runTenant(1)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(delivery);
        f.scanPage = new DataPage(List.of(f.records.get("source/src"), f.records.get("source/src")), 2, 1, 100);
        assertThatThrownBy(() -> new RegisteredSourceWorker(f.resolver, f, delivery, tx).runTenant(1)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(delivery);
    }
    @Test
    void invalidApprovalPreventsSourceScan() {
        ((com.fasterxml.jackson.databind.node.ObjectNode) f.graphs.get("source").get("model")).put("table_name", "changed");
        NotificationDeliveryAccessor delivery = mock(NotificationDeliveryAccessor.class);
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        assertThatThrownBy(() -> new RegisteredSourceWorker(f.resolver, f, delivery, tx).runTenant(1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(f.lastFilters).isNull();
        verifyNoInteractions(delivery);
    }
    static class Fixture implements SourceBindingReader {
        int reads, links = 1;
        long invalidUser;
        Map<String, Object> lastFilters;
        DataPage scanPage;
        Map<String, JsonNode> graphs = new HashMap<>();
        Map<String, Map<String, Object>> records = new HashMap<>();
        Owner owner = new Owner("plugin", "test-package", "test", "1.0.0");
        Binding binding;
        RegisteredSourcePlanResolver resolver;
        JsonNode rule, template;
        Fixture() {
            Map<String, Map<String, String>> schema = Map.of("source", Map.of("owner", "string", "at", "datetime", "targets", "json", "state", "string", "fact", "string"), "target", Map.of("owner", "string", "state", "string"), "owner", Map.of("state", "string"), "link", Map.of("user", "string", "owner", "string", "contact", "string", "state", "string"), "contact", Map.of("owner", "string", "state", "string"));
            long m = 1;
            for (var entry : new TreeMap<>(schema).entrySet()) {
                Map<String, Object> model = new HashMap<>(Map.of("tenant_id", 1, "id", m, "pid", "m" + m, "code", entry.getKey(), "plugin_pid", "plugin", "table_name", "table_" + entry.getKey(), "source_type", "physical", "is_current", true, "deleted_flag", false));
                List<Map<String, Object>> bindings = new ArrayList<>(), fields = new ArrayList<>(), tracking = new ArrayList<>();
                long i = m * 100;
                for (var field : new TreeMap<>(entry.getValue()).entrySet()) {
                    i++;
                    fields.add(Map.of("tenant_id", 1, "id", i, "pid", "f" + i, "code", field.getKey(), "plugin_pid", "plugin", "is_current", true, "deleted_flag", false, "data_type", field.getValue(), "feature", Map.of()));
                    bindings.add(Map.of("tenant_id", 1, "model_id", m, "field_id", i, "pid", "b" + i, "alias_code", field.getKey(), "deleted_flag", false));
                    tracking.add(Map.of("tenant_id", 1, "plugin_pid", "plugin", "resource_pid", "b" + i, "resource_type", "model_field_binding"));
                }
                graphs.put(entry.getKey(), SourceBindingCanonical.tree(Map.of("model", model, "bindings", bindings, "fields", fields, "tracking", tracking, "plugins", List.of(Map.of("tenant_id", 1, "pid", "plugin", "plugin_id", "test-package", "namespace", "test", "version", "1.0.0", "deleted_flag", false)))));
                m++;
            }
            List<Approval> approvals = graphs.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e -> new Approval("METADATA_SHAPE_V1", e.getKey(), SourceBindingCanonical.hash(e.getValue()), List.of(owner), Set.of())).toList();
            Entity src = new Entity("source", List.of(new Exact("state", "pending")));
            Entity target = new Entity("target", List.of(new Exact("state", "open")));
            Entity activeOwner = new Entity("owner", List.of(new Exact("state", "active")));
            Entity link = new Entity("link", List.of(new Exact("state", "active")));
            Entity contact = new Entity("contact", List.of(new Exact("state", "active")));
            binding = new Binding(1, "notice", 1, approvals, new Source(src, "pid", "owner", "at", new Target("targets", target, "owner")), activeOwner, new Principal(link, "user", "owner", "contact"), new Verification(contact, "owner"), 10, "template", Map.of());
            put("source", "src", Map.of("owner", "o1", "at", "2026-10-13T01:00:00Z", "targets", List.of("t1"), "state", "pending", "fact", "original"));
            put("target", "t1", Map.of("owner", "o1", "state", "open"));
            put("owner", "o1", Map.of("state", "active"));
            for (int u = 1; u <= 2; u++) {
                put("link", "l" + u, Map.of("user", "user" + u, "owner", "o1", "contact", "c" + u, "state", "active"));
                put("contact", "c" + u, Map.of("owner", "o1", "state", "active"));
            }
            rule = SourceBindingCanonical.tree(Map.ofEntries(Map.entry("id", 10), Map.entry("tenant_id", 1), Map.entry("enabled", true), Map.entry("deleted_flag", false), Map.entry("action_channel", "IN_APP"), Map.entry("recipient_type", "SPECIFIC_USERS"), Map.entry("recipient_field", "1,2"), Map.entry("action_template_code", "template"), Map.entry("condition_model_code", "source"), Map.entry("condition_filter", List.of(Map.of("fieldName", "state", "operator", "EQ", "value", "pending"))), Map.entry("trigger_type", "EVENT"), Map.entry("trigger_config", Map.of("modelCode", "source", "event", "created"))));
            template = SourceBindingCanonical.tree(Map.of("tenant_id", 1, "code", "template", "enabled", true, "channels", "[\"in_app\"]", "subject_template", "Title", "body_template", "Body"));
            var p = new HostSourceBindingProperties();
            p.setEnabled(true);
            p.setBindings(List.of(binding));
            resolver = new RegisteredSourcePlanResolver(p, this, new ImportedNotificationMetadataValidator());
            resolver.initialize();
        }
        void reapprove() {
            var approvals = binding.approvals().stream().map(a -> new Approval(a.mode(), a.modelCode(), SourceBindingCanonical.hash(graphs.get(a.modelCode())), a.owners(), a.nativeFieldPids())).toList();
            binding = new Binding(binding.schemaVersion(), binding.code(), binding.tenantId(), approvals, binding.source(), binding.owner(), binding.principal(), binding.verification(), binding.ruleId(), binding.templateCode(), binding.variableToSourceField());
            var p = new HostSourceBindingProperties();
            p.setEnabled(true);
            p.setBindings(List.of(binding));
            resolver = new RegisteredSourcePlanResolver(p, this, new ImportedNotificationMetadataValidator());
            resolver.initialize();
        }
        void put(String model, String pid, Map<String, Object> data) {
            var row = new HashMap<>(data);
            row.put("pid", pid);
            row.put("tenant_id", 1L);
            row.put("deleted_flag", false);
            records.put(model + "/" + pid, row);
        }
        public JsonNode metadata(long t, Approval a) {
            reads++;
            return graphs.get(a.modelCode());
        }
        public Map<String, Object> record(Binding b, String model, String pid) {
            reads++;
            return records.get(model + "/" + pid);
        }
        public DataPage page(Binding b, String model, Map<String, Object> filters, int page, int size) {
            reads++;
            lastFilters = filters;
            if (model.equals("source")) return scanPage;
            String user =(String) filters.get("user");
            return new DataPage(List.of(records.get("link/l" + user.substring(4))), links, page, size);
        }
        public JsonNode rule(long t, long id) {
            return rule;
        }
        public JsonNode template(long t, String code) {
            return template;
        }
        public String userPid(long t, long id) {
            require(id != invalidUser);
            return "user" + id;
        }
    }
}
