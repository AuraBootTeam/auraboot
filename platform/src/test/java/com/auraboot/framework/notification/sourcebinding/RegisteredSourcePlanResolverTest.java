package com.auraboot.framework.notification.sourcebinding;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.integration.HostSerializableEventConsumer;
import com.auraboot.framework.integration.ReliableEventDeliveryExecutor;
import com.auraboot.framework.integration.ReliableIntegrationMetrics;
import com.auraboot.framework.meta.mapper.OutboxEventMapper;
import com.auraboot.framework.notification.entity.Notification;
import com.auraboot.framework.notification.mapper.NotificationMapper;
import com.auraboot.framework.notification.service.NotificationDeliveryPlanResolver.Plan;
import com.auraboot.framework.notification.service.NotificationDeliveryPlanResolver;
import com.auraboot.framework.notification.service.NotificationSseService;
import com.auraboot.framework.notification.service.impl.PluginNotificationDeliveryAccessorImpl;
import com.auraboot.framework.notification.service.impl.StrictInAppDeliveryWriter;
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
import com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelope;
import com.auraboot.framework.plugin.extension.integration.ReliableEventConsumerExtension;
import com.auraboot.framework.plugin.extension.integration.ReliableIntegrationAccessor;
import com.fasterxml.jackson.databind.JsonNode;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.auraboot.framework.notification.sourcebinding.SourceBindingTypes.require;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
        NotificationMapper mapper = mock(NotificationMapper.class);
        var writer = new StrictInAppDeliveryWriter(mapper, mock(NotificationSseService.class));
        ReliableIntegrationAccessor reliable = mock(ReliableIntegrationAccessor.class);
        var plan = new Plan(1, ref, "user1", "ordinary-config", "ordinary", "object", Instant.EPOCH, "Title", "Body");
        NotificationDeliveryPlanResolver ordinary =(t, r) -> r.equals(ref) ? Optional.of(plan) : Optional.empty();
        var host = new PluginNotificationDeliveryAccessorImpl(List.of(f.resolver, ordinary), reliable, writer);
        ReflectionTestUtils.setField(host, "enabled", true);
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
    void approvedPhysicalEnumPredicatesRetainExactStringAndChildSemantics() throws Exception {
        remainingFixture(1000);
        for (String model : List.of("source", "target", "owner", "link", "contact", "child")) {
            metadataField(model, "state").put("data_type", "enum").put("dict_code", "approved_states");
        }
        f.reapprove();
        assertThat(f.resolver.plan(f.binding, "src")).hasSize(2);
        f.put("target", "t1", Map.of("owner", "o1", "state", "OPEN"));
        assertThatThrownBy(() -> f.resolver.plan(f.binding, "src")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reapprovedStringPredicatesRejectEveryNonStringCarrier() throws Exception {
        for (String model : List.of("source", "target", "owner", "link", "contact", "child")) {
            for (String type : List.of("boolean", "integer", "decimal", "json", "reference", "datetime", "unknown")) {
                f = new Fixture();
                remainingFixture(1000);
                metadataField(model, "state").put("data_type", type);
                f.reapprove();
                assertThatThrownBy(() -> f.resolver.plan(f.binding, "src"))
                        .as("%s predicate must reject %s", model, type).isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    @Test
    void booleanPredicateCannotUseAnEnumOrStringCarrier() {
        Binding b = f.binding;
        Binding bool = new Binding(b.schemaVersion(), b.code(), b.tenantId(), b.approvals(), b.source(),
                new Entity("owner", List.of(new Exact("state", true))), b.principal(), b.verification(),
                b.ruleId(), b.templateCode(), b.variableToSourceField());
        for (String type : List.of("string", "enum")) {
            metadataField("owner", "state").put("data_type", type);
            f.binding = bool;
            f.reapprove();
            assertThatThrownBy(() -> new ImportedNotificationMetadataValidator().validate(f.binding, f))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        metadataField("owner", "state").put("data_type", "boolean");
        f.reapprove();
        assertThat(new ImportedNotificationMetadataValidator().validate(f.binding, f))
                .containsKey("owner");
    }

    @Test
    void approvedPhysicalPidReferencesResolveEveryDeclaredModelEdge() throws Exception {
        remainingFixture(1000);
        for (String[] edge : List.of(new String[]{"source", "owner", "owner"},
                new String[]{"target", "owner", "owner"}, new String[]{"link", "owner", "owner"},
                new String[]{"link", "contact", "contact"}, new String[]{"contact", "owner", "owner"},
                new String[]{"child", "parent", "target"})) {
            referenceField(edge[0], edge[1], Map.of("targetEntity", edge[2], "valueField", "pid"));
        }
        f.reapprove();
        assertThat(f.resolver.plan(f.binding, "src")).hasSize(2);
        referenceField("target", "owner", Map.of("targetModel", "owner", "valueField", "pid"));
        f.reapprove();
        assertThat(f.resolver.plan(f.binding, "src")).hasSize(2);
        metadataField("child", "parent").put("ref_target", "{\"targetEntity\":\"target\",\"valueField\":\"pid\"}");
        f.reapprove();
        assertThat(f.resolver.plan(f.binding, "src")).hasSize(2);
    }

    @Test
    void reapprovedReferenceCannotChangeDeclaredTargetOrPidValueSemantics() {
        for (Map<String, Object> invalid : List.<Map<String, Object>>of(
                Map.of("targetEntity", "other", "valueField", "pid"),
                Map.of("targetEntity", "owner", "valueField", "code"),
                Map.of("targetEntity", "owner"), Map.of("valueField", "pid"),
                Map.of("targetEntity", "owner", "targetModel", "other", "valueField", "pid"),
                Map.of("targetEntity", "owner", "valueField", "pid", "refType", "api"),
                Map.of("targetEntity", "owner", "valueField", "pid", "targetTable", "external_table"),
                Map.of("targetEntity", "owner", "valueField", "pid", "apiConfig", Map.of("url", "https://example.invalid")),
                Map.of("targetEntity", "owner", "valueField", "pid", "unknownSource", "lookup"))) {
            referenceField("target", "owner", invalid);
            f.reapprove();
            assertThatThrownBy(() -> f.resolver.plan(f.binding, "src")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void referenceRawMetadataChangesStillRequireExactApprovalAndPhysicalStorage() {
        referenceField("target", "owner", Map.of("targetEntity", "owner", "valueField", "pid"));
        assertThatThrownBy(() -> f.resolver.plan(f.binding, "src")).isInstanceOf(IllegalArgumentException.class);
        f.reapprove();
        assertThat(f.resolver.plan(f.binding, "src")).hasSize(2);
        metadataField("target", "owner").set("feature", SourceBindingCanonical.tree(Map.of("computeExpression", "1")));
        f.reapprove();
        assertThatThrownBy(() -> f.resolver.plan(f.binding, "src")).isInstanceOf(IllegalArgumentException.class);
        metadataField("target", "owner").set("feature", SourceBindingCanonical.tree(Map.of()));
        metadataField("target", "owner").remove("ref_target");
        f.reapprove();
        assertThatThrownBy(() -> f.resolver.plan(f.binding, "src")).isInstanceOf(IllegalArgumentException.class);
        referenceField("target", "owner", Map.of("targetEntity", "owner", "valueField", "pid"));
        f.reapprove();
        assertThat(f.resolver.plan(f.binding, "src")).hasSize(2);
        referenceField("link", "user", Map.of("targetEntity", "owner", "valueField", "pid"));
        f.reapprove();
        assertThatThrownBy(() -> f.resolver.plan(f.binding, "src")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void approvedImporterOwningCardinalityKeepsExactPidResolutionAndRawApproval() throws Exception {
        remainingFixture(1000);
        for (String[] edge : List.of(new String[]{"source", "owner", "owner"},
                new String[]{"target", "owner", "owner"}, new String[]{"link", "owner", "owner"},
                new String[]{"link", "contact", "contact"}, new String[]{"contact", "owner", "owner"},
                new String[]{"child", "parent", "target"})) {
            referenceField(edge[0], edge[1], Map.of("targetEntity", edge[2], "valueField", "pid",
                    "refType", "entity", "relationship", "many_to_one",
                    "targetField", "display_name", "displayField", "display_name"));
        }
        assertThatThrownBy(() -> f.resolver.plan(f.binding, "src")).isInstanceOf(IllegalArgumentException.class);
        f.reapprove();
        assertThat(f.resolver.plan(f.binding, "src")).hasSize(2);
        referenceField("target", "owner", Map.of("targetModel", "owner", "valueField", "pid",
                "relationship", "many_to_one"));
        f.reapprove();
        assertThat(f.resolver.plan(f.binding, "src")).hasSize(2);
        metadataField("child", "parent").put("ref_target",
                "{\"targetEntity\":\"target\",\"valueField\":\"pid\",\"relationship\":\"many_to_one\"}");
        f.reapprove();
        assertThat(f.resolver.plan(f.binding, "src")).hasSize(2);
    }

    @Test
    void importedCardinalityRejectsOtherRelationsAndNonTextEvenAfterReapproval() {
        for (Object relationship : List.of("one_to_many", "many_to_many", "one_to_one", "MANY_TO_ONE", "", false, 1,
                List.of("many_to_one"), Map.of("kind", "many_to_one"))) {
            referenceField("target", "owner", Map.of("targetEntity", "owner", "valueField", "pid", "relationship", relationship));
            f.reapprove();
            assertThatThrownBy(() -> f.resolver.plan(f.binding, "src")).isInstanceOf(IllegalArgumentException.class);
        }
        referenceField("target", "owner", Map.of("targetEntity", "owner", "valueField", "pid"));
        ((ObjectNode) metadataField("target", "owner").path("ref_target")).putNull("relationship");
        f.reapprove();
        assertThatThrownBy(() -> f.resolver.plan(f.binding, "src")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void owningCardinalityNeverGrantsAlternateTargetValueOrDynamicAuthority() {
        for (Map<String, Object> bad : List.<Map<String, Object>>of(
                Map.of("targetEntity", "other"), Map.of("valueField", "code"),
                Map.of("targetTable", "other_table"), Map.of("refType", "api"),
                Map.of("apiConfig", Map.of("url", "https://example.invalid")),
                Map.of("queryCondition", Map.of("limit", 1)), Map.of("extensions", Map.of("enabled", true)),
                Map.of("bidirectional", Map.of("relationType", "many_to_many")),
                Map.of("unknownSource", "lookup"))) {
            Map<String, Object> target = new HashMap<>(Map.of("targetEntity", "owner", "valueField", "pid", "relationship", "many_to_one"));
            target.putAll(bad);
            referenceField("target", "owner", target);
            f.reapprove();
            assertThatThrownBy(() -> f.resolver.plan(f.binding, "src")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private ObjectNode metadataField(String model, String code) {
        for (JsonNode field : f.graphs.get(model).path("fields")) {
            if (code.equals(field.path("code").asText())) return (ObjectNode) field;
        }
        throw new AssertionError("Missing metadata fixture field");
    }

    private void referenceField(String model, String code, Map<String, Object> target) {
        metadataField(model, code).put("data_type", "reference")
                .set("ref_target", SourceBindingCanonical.tree(target));
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
        f.records.get("source/src").put("at", java.sql.Timestamp.from(Instant.parse("2026-10-13T01:00:00.123456Z")));
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
    void workerExplicitWechatOptInQueuesBothChannelsForTheSameAuthorizedUsers() {
        var delivery = mock(NotificationDeliveryAccessor.class);
        var tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(delivery.enqueue(any())).thenReturn(new NotificationDeliveryAccessor.Result(NotificationDeliveryAccessor.Status.QUEUED, null, "k"));
        var properties = new HostSourceBindingProperties(); properties.setEnabled(true);
        properties.getWorker().setWechatSubscriptionEnabled(true);
        var reservations = new com.auraboot.framework.notification.wechat.WechatSubscriptionReservationProperties();
        reservations.setEnabled(true); reservations.setSourceTypes(Set.of("notice"));
        var worker = new RegisteredSourceWorker(f.resolver, f, delivery, tx, properties, reservations);
        assertThat(worker.process(1, "notice", "src")).isEqualTo(2);
        var requests = org.mockito.ArgumentCaptor.forClass(NotificationDeliveryAccessor.Request.class);
        verify(delivery, org.mockito.Mockito.times(4)).enqueue(requests.capture());
        assertThat(requests.getAllValues().stream().map(NotificationDeliveryAccessor.Request::channel)).containsExactly(
                NotificationDeliveryAccessor.Channel.IN_APP, NotificationDeliveryAccessor.Channel.IN_APP,
                NotificationDeliveryAccessor.Channel.WECHAT_SUBSCRIPTION, NotificationDeliveryAccessor.Channel.WECHAT_SUBSCRIPTION);
        assertThat(requests.getAllValues().subList(0,2).stream().map(NotificationDeliveryAccessor.Request::resolutionReference))
                .containsExactlyElementsOf(requests.getAllValues().subList(2,4).stream().map(NotificationDeliveryAccessor.Request::resolutionReference).toList());
        verify(tx).commit(any()); verify(tx, never()).rollback(any());
    }
    @Test
    void workerBlockedWechatRetainsInAppCommitAndReportsPartialChannelToPolling() {
        var delivery = mock(NotificationDeliveryAccessor.class);
        var tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(delivery.enqueue(any())).thenAnswer(call -> {
            NotificationDeliveryAccessor.Request request = call.getArgument(0);
            return request.channel() == NotificationDeliveryAccessor.Channel.IN_APP
                    ? new NotificationDeliveryAccessor.Result(NotificationDeliveryAccessor.Status.QUEUED, null, "k")
                    : NotificationDeliveryAccessor.Result.blocked(NotificationDeliveryAccessor.Reason.WECHAT_NOT_CONFIGURED);
        });
        f.scanPage = new DataPage(List.of(f.records.get("source/src")), 1, 1, 10);
        MetaContext.setContext(9L, 10L, "prior", "prior");
        var result = wechatWorker(delivery, tx).runPage(1, "notice", 1, 10);
        assertThat(result.processedPlans()).isEqualTo(2);
        assertThat(result.selectedSources()).isEqualTo(1);
        assertThat(result.failures()).containsExactly(new RegisteredSourceWorker.SourceFailure("src", null, SourceFailureCode.WECHAT_DELIVERY_BLOCKED));
        verify(tx, org.mockito.Mockito.times(2)).commit(any()); verify(tx, never()).rollback(any());
        verify(delivery, org.mockito.Mockito.times(4)).enqueue(any());
        assertThat(MetaContext.getCurrentTenantId()).isEqualTo(9);
        assertThat(MetaContext.getCurrentUserPid()).isEqualTo("prior");
    }
    @Test
    void directWorkerCallCannotPresentBlockedWechatAsCompleteAndDoesNotUndoInApp() {
        var delivery = mock(NotificationDeliveryAccessor.class);
        var tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(delivery.enqueue(any())).thenAnswer(call -> {
            NotificationDeliveryAccessor.Request request = call.getArgument(0);
            return request.channel() == NotificationDeliveryAccessor.Channel.WECHAT_SUBSCRIPTION
                    ? NotificationDeliveryAccessor.Result.blocked(NotificationDeliveryAccessor.Reason.WECHAT_NOT_CONFIGURED)
                    : new NotificationDeliveryAccessor.Result(NotificationDeliveryAccessor.Status.REPLAYED, null, "k");
        });
        assertThatThrownBy(() -> wechatWorker(delivery,tx).process(1,"notice","src"))
                .hasMessage("NOTIFICATION_SOURCE_WECHAT_DELIVERY_BLOCKED");
        verify(tx).commit(any()); verify(tx, never()).rollback(any());
    }
    @Test
    void actualWechatEnqueueFailureRollsBackSourceTransactionWithoutSwallowingIt() {
        var delivery = mock(NotificationDeliveryAccessor.class);
        var tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(delivery.enqueue(any())).thenAnswer(call -> {
            NotificationDeliveryAccessor.Request request = call.getArgument(0);
            if (request.channel() == NotificationDeliveryAccessor.Channel.WECHAT_SUBSCRIPTION)
                throw new IllegalStateException("RELIABLE_ENVELOPE_CONFLICT");
            return new NotificationDeliveryAccessor.Result(NotificationDeliveryAccessor.Status.QUEUED,null,"k");
        });
        assertThatThrownBy(() -> wechatWorker(delivery,tx).processChannels(1,"notice","src"))
                .hasMessage("RELIABLE_ENVELOPE_CONFLICT");
        verify(tx).rollback(any()); verify(tx,never()).commit(any());
        verify(delivery,org.mockito.Mockito.times(3)).enqueue(any());
    }
    @Test
    void configuredReservationResolverDoesNotImplicitlyEnableAutomaticWechat() {
        var delivery = mock(NotificationDeliveryAccessor.class);
        var tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(delivery.enqueue(any())).thenReturn(new NotificationDeliveryAccessor.Result(NotificationDeliveryAccessor.Status.QUEUED,null,"k"));
        var policy = new com.auraboot.framework.notification.wechat.WechatSubscriptionReservationProperties();
        policy.setEnabled(true); policy.setSourceTypes(Set.of("notice"));
        assertThat(new RegisteredSourceWorker(f.resolver,f,delivery,tx,new HostSourceBindingProperties(),policy).process(1,"notice","src")).isEqualTo(2);
        var requests=org.mockito.ArgumentCaptor.forClass(NotificationDeliveryAccessor.Request.class);
        verify(delivery,org.mockito.Mockito.times(2)).enqueue(requests.capture());
        assertThat(requests.getAllValues()).allMatch(request -> request.channel()==NotificationDeliveryAccessor.Channel.IN_APP);
    }
    @Test
    void automaticWechatRequiresEnabledReservationsAndRegisteredSourceTypes() {
        var p=new HostSourceBindingProperties();p.setEnabled(true);p.getWorker().setWechatSubscriptionEnabled(true);
        var policy=new com.auraboot.framework.notification.wechat.WechatSubscriptionReservationProperties();
        var delivery=mock(NotificationDeliveryAccessor.class);var tx=mock(PlatformTransactionManager.class);
        assertThatThrownBy(() -> new RegisteredSourceWorker(f.resolver,f,delivery,tx,p,policy)).hasMessage("NOTIFICATION_SOURCE_BINDING_INVALID");
        policy.setEnabled(true);
        assertThatThrownBy(() -> new RegisteredSourceWorker(f.resolver,f,delivery,tx,p,policy)).hasMessage("NOTIFICATION_SOURCE_BINDING_INVALID");
        policy.setSourceTypes(Set.of("unregistered"));
        assertThatThrownBy(() -> new RegisteredSourceWorker(f.resolver,f,delivery,tx,p,policy)).hasMessage("NOTIFICATION_SOURCE_BINDING_INVALID");
        verifyNoInteractions(delivery,tx);
    }
    @Test
    void optedInWechatCannotBypassCompleteRecipientValidationOrInAppFailure() {
        var delivery=mock(NotificationDeliveryAccessor.class);var tx=mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var worker=wechatWorker(delivery,tx);
        f.records.get("link/l2").put("owner","foreign");
        assertThatThrownBy(() -> worker.processChannels(1,"notice","src")).hasMessage("NOTIFICATION_SOURCE_BINDING_INVALID");
        verifyNoInteractions(delivery);
        f.records.get("link/l2").put("owner","o1");
        when(delivery.enqueue(any())).thenReturn(NotificationDeliveryAccessor.Result.blocked(NotificationDeliveryAccessor.Reason.DISABLED));
        assertThatThrownBy(() -> worker.processChannels(1,"notice","src")).hasMessage("NOTIFICATION_SOURCE_DELIVERY_BLOCKED");
        verify(delivery).enqueue(any());verify(tx,org.mockito.Mockito.times(2)).rollback(any());verify(tx,never()).commit(any());
    }
    private RegisteredSourceWorker wechatWorker(NotificationDeliveryAccessor delivery,PlatformTransactionManager tx) {
        var p=new HostSourceBindingProperties();p.setEnabled(true);p.getWorker().setWechatSubscriptionEnabled(true);
        var policy=new com.auraboot.framework.notification.wechat.WechatSubscriptionReservationProperties();
        policy.setEnabled(true);policy.setSourceTypes(Set.of("notice"));
        return new RegisteredSourceWorker(f.resolver,f,delivery,tx,p,policy);
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
    @Test
    void composedOrdinaryHostNotificationKeepsDefaultConsumerIsolation() {
        ComposedDelivery pipeline = new ComposedDelivery();
        var event = pipeline.queue("ordinary-reference");
        pipeline.deliver(event, pipeline.host);
        assertThat(pipeline.isolation).isEqualTo(TransactionDefinition.ISOLATION_DEFAULT);
        assertThat(pipeline.host.requiresSerializableTransaction(event)).isFalse();
        verify(pipeline.notifications).insert(any(Notification.class));
        assertThat(f.reads).isZero();
    }
    @Test
    void composedRegisteredHostNotificationUsesSerializableAndPersistsAfterFullResolution() {
        ComposedDelivery pipeline = new ComposedDelivery();
        var event = pipeline.queue(SourceBindingCanonical.reference("notice", "src", 1));
        assertThat(pipeline.host.requiresSerializableTransaction(event)).isTrue();
        f.reads = 0;
        pipeline.deliver(event, pipeline.host);
        assertThat(pipeline.isolation).isEqualTo(TransactionDefinition.ISOLATION_SERIALIZABLE);
        assertThat(f.reads).isGreaterThan(0);
        verify(pipeline.notifications).insert(any(Notification.class));
    }
    @Test
    void composedMalformedRegisteredReferenceSelectsIsolationButNeverGrantsAuthority() {
        ComposedDelivery pipeline = new ComposedDelivery();
        var ordinary = pipeline.queue("ordinary-reference");
        var malformed = pipeline.event(ordinary, ordinary.eventType(), "ns1.invalid");
        assertThat(pipeline.host.requiresSerializableTransaction(malformed)).isTrue();
        assertThatThrownBy(() -> pipeline.deliver(malformed, pipeline.host)).isInstanceOf(IllegalArgumentException.class);
        assertThat(pipeline.isolation).isEqualTo(TransactionDefinition.ISOLATION_SERIALIZABLE);
        verify(pipeline.transactions).rollback(any());
        verify(pipeline.notifications, never()).insert(any(Notification.class));
        assertThat(f.reads).isZero();
    }
    @Test
    void composedOtherEventTypeCannotUseRegisteredPrefixToChangeIsolation() {
        ComposedDelivery pipeline = new ComposedDelivery();
        var ordinary = pipeline.queue("ordinary-reference");
        var other = pipeline.event(ordinary, "other.event", SourceBindingCanonical.reference("notice", "src", 1));
        assertThat(pipeline.host.requiresSerializableTransaction(other)).isFalse();
        assertThatThrownBy(() -> pipeline.deliver(other, pipeline.host)).hasMessage("NOTIFICATION_DELIVERY_UNAVAILABLE");
        assertThat(pipeline.isolation).isEqualTo(TransactionDefinition.ISOLATION_DEFAULT);
        verify(pipeline.notifications, never()).insert(any(Notification.class));
    }
    @Test
    void composedForeignClassloaderCannotOptInOrResolveRegisteredReferenceInDefaultTransaction() {
        ComposedDelivery pipeline = new ComposedDelivery();
        var event = pipeline.queue(SourceBindingCanonical.reference("notice", "src", 1));
        f.reads = 0;
        AtomicInteger optInCalls = new AtomicInteger();
        ClassLoader foreign = new ClassLoader(getClass().getClassLoader()) {
        };
        Object proxy = Proxy.newProxyInstance(foreign, new Class<?>[] {
            ReliableEventConsumerExtension.class, HostSerializableEventConsumer.class
        },(instance, method, args) -> {
            return switch (method.getName()) {
                case "consumerCode" -> pipeline.host.consumerCode();
                case "subscribedEventTypes" -> pipeline.host.subscribedEventTypes();
                case "requiresSerializableTransaction" -> {
                    optInCalls.incrementAndGet();
                    yield true;
                }
                case "consume" -> {
                    pipeline.host.consume((IntegrationEventEnvelope) args[0]);
                    yield null;
                }
                default -> null;
            };
        });
        assertThatThrownBy(() -> pipeline.deliver(event,(ReliableEventConsumerExtension) proxy)).isInstanceOf(IllegalArgumentException.class);
        assertThat(pipeline.isolation).isEqualTo(TransactionDefinition.ISOLATION_DEFAULT);
        assertThat(optInCalls).hasValue(0);
        assertThat(f.reads).isZero();
        verify(pipeline.notifications, never()).insert(any(Notification.class));
    }
    @Test
    void approvedFutureQuoteDeadlineAllowsStablePlansBeforeExpiry() throws Exception {
        var clock = deadlineFixture("2026-10-13T04:00:00Z");
        var before = f.resolver.plan(f.binding, "src");
        clock.now.set(Instant.parse("2026-10-13T03:30:00Z"));
        assertThat(f.resolver.plan(f.binding, "src")).isEqualTo(before).hasSize(2);
    }
    @Test
    void cutoffEqualBeforeMissingOrMalformedRejectsBeforeAnyQueue() throws Exception {
        deadlineFixture("2026-10-13T04:00:00Z");
        for (Object cutoff : List.of("2026-10-13T03:00:00Z", "2026-10-13T02:00:00Z",
                "2026-10-13T04:00:00", "invalid")) {
            f.records.get("target/t1").put("cutoff", cutoff);
            assertThatThrownBy(() -> f.resolver.plan(f.binding, "src")).isInstanceOf(RuntimeException.class);
        }
        f.records.get("target/t1").remove("cutoff");
        assertThatThrownBy(() -> f.resolver.plan(f.binding, "src")).isInstanceOf(IllegalArgumentException.class);
        f.records.get("target/t1").put("cutoff", null);
        assertThatThrownBy(() -> f.resolver.plan(f.binding, "src")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void finalExpiredTargetPreventsFirstUserEnqueue() throws Exception {
        deadlineFixture("2026-10-13T04:00:00Z");
        f.put("target", "t2", Map.of("owner", "o1", "state", "open", "cutoff", "2026-10-13T03:00:00Z"));
        f.records.get("source/src").put("targets", List.of("t1", "t2"));
        var pipeline = new ComposedDelivery();
        assertThatThrownBy(() -> pipeline.queue(SourceBindingCanonical.reference("notice", "src", 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(pipeline.queued).isEmpty();
        verify(pipeline.notifications, never()).insert(any(Notification.class));
    }
    @Test
    void delayedConsumeRechecksExpiryAndRollsBackReceiptBeforeInbox() throws Exception {
        var clock = deadlineFixture("2026-10-13T04:00:00Z");
        var pipeline = new ComposedDelivery();
        var event = pipeline.queue(SourceBindingCanonical.reference("notice", "src", 1));
        clock.now.set(Instant.parse("2026-10-13T04:00:00Z"));
        assertThatThrownBy(() -> pipeline.deliver(event, pipeline.host)).isInstanceOf(IllegalArgumentException.class);
        verify(pipeline.transactions).rollback(any());
        verify(pipeline.receipts, never()).markReceiptApplied(anyLong(), anyString(), anyString(), anyString());
        verify(pipeline.notifications, never()).insert(any(Notification.class));
        assertThat(pipeline.queued).containsExactly(event);
    }
    @Test
    void changedFutureDeadlineCannotConsumeOldEnvelope() throws Exception {
        deadlineFixture("2026-10-13T04:00:00Z");
        var pipeline = new ComposedDelivery();
        var event = pipeline.queue(SourceBindingCanonical.reference("notice", "src", 1));
        f.records.get("target/t1").put("cutoff", "2026-10-13T05:00:00Z");
        assertThatThrownBy(() -> pipeline.deliver(event, pipeline.host))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("NOTIFICATION_ENVELOPE_CONFLICT");
        verify(pipeline.notifications, never()).insert(any(Notification.class));
    }
    @Test
    void deadlineRequiresBoundPhysicalDatetimeMetadataAndExplicitRelation() throws Exception {
        deadlineFixture("2026-10-13T04:00:00Z");
        JsonNode graph = f.graphs.get("target");
        for (JsonNode field : graph.path("fields")) {
            if ("cutoff".equals(field.path("code").asText())) ((ObjectNode) field).put("data_type", "string");
        }
        f.reapprove();
        assertThatThrownBy(() -> f.resolver.plan(f.binding, "src")).isInstanceOf(IllegalArgumentException.class);
        for (String descriptor : List.of("{}", "{\"fieldCode\":\"cutoff\"}",
                "{\"fieldCode\":\"cutoff\",\"relation\":\"BEFORE_HOST_NOW\"}")) {
            assertThatThrownBy(() -> deadlineTarget(descriptor)).isInstanceOf(Exception.class);
        }
    }
    @Test
    void absentDeadlineKeepsLegacyCanonicalBytesAndNonQuoteBehavior() {
        Target target = f.binding.source().targets();
        var oldShape = SourceBindingCanonical.tree(Map.of("listField", target.listField(),
                "entity", target.entity(), "ownerField", target.ownerField()));
        assertThat(SourceBindingCanonical.tree(target)).isEqualTo(oldShape);
        assertThat(SourceBindingCanonical.hash(target)).isEqualTo(SourceBindingCanonical.hash(oldShape));
        f.records.get("target/t1").put("cutoff", "already-expired-unconfigured-field");
        assertThat(f.resolver.plan(f.binding, "src")).hasSize(2);
    }
    private MutableDeadlineClock deadlineFixture(String cutoff) throws Exception {
        ObjectNode graph = (ObjectNode) f.graphs.get("target");
        long modelId = graph.path("model").path("id").longValue();
        ((ArrayNode) graph.path("fields")).add(SourceBindingCanonical.tree(Map.of(
                "tenant_id", 1, "id", 9999, "pid", "cutoff-field", "code", "cutoff",
                "plugin_pid", "plugin", "is_current", true, "deleted_flag", false,
                "data_type", "datetime", "feature", Map.of())));
        ((ArrayNode) graph.path("bindings")).add(SourceBindingCanonical.tree(Map.of(
                "tenant_id", 1, "model_id", modelId, "field_id", 9999,
                "pid", "cutoff-binding", "alias_code", "cutoff", "deleted_flag", false)));
        ((ArrayNode) graph.path("tracking")).add(SourceBindingCanonical.tree(Map.of(
                "tenant_id", 1, "plugin_pid", "plugin", "resource_pid", "cutoff-binding",
                "resource_type", "model_field_binding")));
        Binding b = f.binding;
        Source source = b.source();
        Target target = deadlineTarget("{\"fieldCode\":\"cutoff\",\"relation\":\"AFTER_HOST_NOW\"}");
        f.binding = new Binding(b.schemaVersion(), b.code(), b.tenantId(), b.approvals(),
                new Source(source.entity(), source.identityField(), source.ownerField(), source.occurredAtField(), target),
                b.owner(), b.principal(), b.verification(), b.ruleId(), b.templateCode(), b.variableToSourceField());
        f.reapprove();
        f.records.get("target/t1").put("cutoff", cutoff);
        var clock = new MutableDeadlineClock();
        ReflectionTestUtils.setField(f.resolver, "clock", clock);
        return clock;
    }
    private Target deadlineTarget(String descriptor) throws Exception {
        ObjectNode target = (ObjectNode) SourceBindingCanonical.tree(f.binding.source().targets());
        target.set("deadline", SourceBindingCanonical.parse(descriptor));
        return new ObjectMapper().treeToValue(target, Target.class);
    }
    private static final class MutableDeadlineClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-13T03:00:00Z"));
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now.get(), zone); }
        @Override public Instant instant() { return now.get(); }
    }
    @Test
    void remainingChildProgressRechecksEligibilityWithoutChangingEnvelope() throws Exception {
        remainingFixture(1000);
        var plans = f.resolver.plan(f.binding, "src");
        f.records.get("child/d001").put("submitted", new java.math.BigDecimal("60.000001"));
        assertThat(f.resolver.plan(f.binding, "src")).isEqualTo(plans);
        f.records.get("child/d001").put("submitted", "100");
        assertThatThrownBy(() -> f.resolver.plan(f.binding, "src")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void delayedConsumeRejectsFullySuppliedChildrenWithoutInboxOrReceipt() throws Exception {
        remainingFixture(1000);
        var pipeline = new ComposedDelivery();
        var event = pipeline.queue(SourceBindingCanonical.reference("notice", "src", 1));
        f.records.get("child/d001").put("submitted", "100");
        assertThatThrownBy(() -> pipeline.deliver(event, pipeline.host)).isInstanceOf(IllegalArgumentException.class);
        verify(pipeline.transactions).rollback(any());
        verify(pipeline.notifications, never()).insert(any(Notification.class));
        verify(pipeline.receipts, never()).markReceiptApplied(anyLong(), anyString(), anyString(), anyString());
        assertThat(pipeline.queued).containsExactly(event);
    }
    @Test
    void positiveFirstChildCannotHideInvalidLaterRecord() throws Exception {
        remainingFixture(1000);
        f.put("child", "d002", Map.of("state", "active", "parent", "t1", "ordered", "10", "submitted", "10"));
        var clean = new HashMap<>(f.records.get("child/d002"));
        for (var bad : List.of(Map.of("parent", (Object) "foreign"), Map.of("tenant_id", (Object) 2L),
                Map.of("ordered", (Object) 1.0d), Map.of("submitted", (Object) "NaN"),
                Map.of("submitted", (Object) "11"))) {
            f.records.put("child/d002", new HashMap<>(clean));
            f.records.get("child/d002").putAll(bad);
            // Keep the actual scanned PID present, then change the independent locked record proof.
            f.childPages.put(1, new DataPage(List.of(f.records.get("child/d001"), clean), 2, 1, 100));
            assertThatThrownBy(() -> f.resolver.plan(f.binding, "src")).isInstanceOf(IllegalArgumentException.class);
        }
        f.records.put("child/d002", new HashMap<>(clean));
        f.records.get("child/d002").remove("submitted");
        assertThatThrownBy(() -> f.resolver.plan(f.binding, "src")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void childPagesRejectEmptyTruncationDriftDuplicatesOrderAndConfiguredCapacity() throws Exception {
        remainingFixture(2);
        var row = f.records.get("child/d001");
        for (var page : List.of(new DataPage(List.of(), 0, 1, 100), new DataPage(List.of(row), 2, 1, 100),
                new DataPage(List.of(row), 3, 1, 100), new DataPage(List.of(row, row), 2, 1, 100),
                new DataPage(List.of(row), 1, 2, 100))) {
            f.childPages.put(1, page);
            assertThatThrownBy(() -> f.resolver.plan(f.binding, "src")).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test
    void allPagesAreValidatedEvenAfterPositiveFirstRow() throws Exception {
        remainingFixture(1000);
        for (int n = 2; n <= 101; n++) f.put("child", String.format("d%03d", n),
                Map.of("state", "active", "parent", "t1", "ordered", "10", "submitted", "10"));
        assertThat(f.resolver.plan(f.binding, "src")).hasSize(2);
        f.records.get("child/d101").put("ordered", "bad");
        assertThatThrownBy(() -> f.resolver.plan(f.binding, "src")).isInstanceOf(IllegalArgumentException.class);
        f.childPages.put(2, new DataPage(List.of(), 101, 2, 100));
        assertThatThrownBy(() -> f.resolver.plan(f.binding, "src")).isInstanceOf(IllegalArgumentException.class);
        f.records.get("child/d101").put("ordered", "10");
        f.childPages.put(2, new DataPage(List.of(f.records.get("child/d101")), 102, 2, 100));
        assertThatThrownBy(() -> f.resolver.plan(f.binding, "src")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void childRequiresApprovedPhysicalDecimalAndExactParentField() throws Exception {
        remainingFixture(1000);
        for (JsonNode field : f.graphs.get("child").path("fields")) {
            if ("ordered".equals(field.path("code").asText())) ((ObjectNode) field).put("data_type", "string");
        }
        f.reapprove();
        assertThatThrownBy(() -> f.resolver.plan(f.binding, "src")).isInstanceOf(IllegalArgumentException.class);
        Binding b = f.binding;
        assertThatThrownBy(() -> new Binding(b.schemaVersion(), b.code(), b.tenantId(),
                b.approvals().stream().filter(a -> !a.modelCode().equals("child")).toList(), b.source(),
                b.owner(), b.principal(), b.verification(), b.ruleId(), b.templateCode(), b.variableToSourceField()))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void legacyTargetCanonicalShapeAndInvalidChildDescriptorsRemainStrict() {
        assertThat(SourceBindingCanonical.tree(f.binding.source().targets()).has("remainingChild")).isFalse();
        for (String child : List.of("{}", "{\"entity\":null}",
                "{\"entity\":{\"modelCode\":\"child\",\"equals\":[{\"fieldCode\":\"state\",\"value\":{\"type\":\"STRING\",\"value\":\"active\"}}]},\"parentField\":\"parent\",\"orderedField\":\"ordered\",\"submittedField\":\"submitted\",\"maxRows\":1001}")) {
            assertThatThrownBy(() -> childTarget(child)).isInstanceOf(Exception.class);
        }
    }
    private Target childTarget(String descriptor) throws Exception {
        ObjectNode target = (ObjectNode) SourceBindingCanonical.tree(f.binding.source().targets());
        target.set("remainingChild", SourceBindingCanonical.parse(descriptor));
        return new ObjectMapper().treeToValue(target, Target.class);
    }
    private void remainingFixture(int maxRows) throws Exception {
        var graph = (ObjectNode) f.graphs.get("target").deepCopy();
        ((ObjectNode) graph.path("model")).put("id", 90).put("code", "child").put("pid", "m90").put("table_name", "table_child");
        var fields = graph.putArray("fields");
        var bindings = graph.putArray("bindings");
        var tracking = graph.putArray("tracking");
        int id = 9000;
        for (var field : Map.of("state", "string", "parent", "string", "ordered", "decimal", "submitted", "decimal").entrySet()) {
            id++;
            fields.add(SourceBindingCanonical.tree(Map.of("tenant_id", 1, "id", id, "pid", "f" + id,
                    "code", field.getKey(), "plugin_pid", "plugin", "is_current", true, "deleted_flag", false,
                    "data_type", field.getValue(), "feature", Map.of())));
            bindings.add(SourceBindingCanonical.tree(Map.of("tenant_id", 1, "model_id", 90, "field_id", id,
                    "pid", "b" + id, "alias_code", field.getKey(), "deleted_flag", false)));
            tracking.add(SourceBindingCanonical.tree(Map.of("tenant_id", 1, "plugin_pid", "plugin", "resource_pid", "b" + id, "resource_type", "model_field_binding")));
        }
        f.graphs.put("child", graph);
        var approvals = new ArrayList<>(f.binding.approvals());
        approvals.add(new Approval("METADATA_SHAPE_V1", "child", SourceBindingCanonical.hash(graph), List.of(f.owner), Set.of()));
        Target target = childTarget("{\"entity\":{\"modelCode\":\"child\",\"equals\":[{\"fieldCode\":\"state\",\"value\":{\"type\":\"STRING\",\"value\":\"active\"}}]},\"parentField\":\"parent\",\"orderedField\":\"ordered\",\"submittedField\":\"submitted\",\"maxRows\":" + maxRows + "}");
        Binding b = f.binding;
        f.binding = new Binding(b.schemaVersion(), b.code(), b.tenantId(), approvals,
                new Source(b.source().entity(), b.source().identityField(), b.source().ownerField(), b.source().occurredAtField(), target),
                b.owner(), b.principal(), b.verification(), b.ruleId(), b.templateCode(), b.variableToSourceField());
        f.reapprove();
        f.put("child", "d001", Map.of("state", "active", "parent", "t1", "ordered", "100", "submitted", "0"));
    }
    /** Actual accessor/writer/executor composition with hermetic DB/transaction dependencies. */
    private final class ComposedDelivery {
        final NotificationMapper notifications = mock(NotificationMapper.class);
        final OutboxEventMapper receipts = mock(OutboxEventMapper.class);
        final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        final List<IntegrationEventEnvelope> queued = new ArrayList<>();
        final PluginNotificationDeliveryAccessorImpl host;
        final ReliableEventDeliveryExecutor executor;
        int isolation;
        ComposedDelivery() {
            var reliable = mock(ReliableIntegrationAccessor.class);
            var writer = new StrictInAppDeliveryWriter(notifications, mock(NotificationSseService.class));
            NotificationDeliveryPlanResolver ordinary =(tenant, reference) -> {
                if (!"ordinary-reference".equals(reference)) return Optional.empty();
                return Optional.of(new Plan(1, reference, "ordinary-user", "ordinary-config", "ordinary", "object", Instant.EPOCH, "Title", "Body"));
            };
            host = new PluginNotificationDeliveryAccessorImpl(List.of(f.resolver, ordinary), reliable, writer);
            ReflectionTestUtils.setField(host, "enabled", true);
            executor = new ReliableEventDeliveryExecutor(receipts, mock(ReliableIntegrationMetrics.class), transactions);
            when(notifications.findActiveRecipient(anyLong(), anyString())).thenReturn(1L);
            when(notifications.insert(any(Notification.class))).thenAnswer(call -> {
                Notification row = call.getArgument(0);
                row.setId(101L);
                return 1;
            });
            when(reliable.enqueueIdempotent(any())).thenAnswer(call -> {
                queued.add(call.getArgument(0));
                return true;
            });
            when(receipts.claimReceipt(anyLong(), anyString(), anyString(), anyString())).thenReturn(1);
            when(receipts.markReceiptApplied(anyLong(), anyString(), anyString(), anyString())).thenReturn(1);
            when(transactions.getTransaction(any())).thenAnswer(call -> {
                TransactionDefinition definition = call.getArgument(0);
                isolation = definition.getIsolationLevel();
                TransactionSynchronizationManager.setActualTransactionActive(true);
                TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(isolation == TransactionDefinition.ISOLATION_DEFAULT ? null : isolation);
                if (!TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.initSynchronization();
                return new SimpleTransactionStatus();
            });
            doAnswer(call -> {
                TransactionSynchronizationManager.clear();
                return null;
            }).when(transactions).commit(any());
            doAnswer(call -> {
                TransactionSynchronizationManager.clear();
                return null;
            }).when(transactions).rollback(any());
        }
        IntegrationEventEnvelope queue(String reference) {
            MetaContext.setContext(1L, 0L, "host", "host");
            assertThat(host.enqueue(new NotificationDeliveryAccessor.Request(1, reference, NotificationDeliveryAccessor.Channel.IN_APP)).status()).isEqualTo(NotificationDeliveryAccessor.Status.QUEUED);
            return queued.getLast();
        }
        void deliver(IntegrationEventEnvelope envelope, ReliableEventConsumerExtension consumer) {
            when(receipts.lockActiveDeliveryLease(envelope.tenantId(), envelope.eventId(), "test-lease")).thenReturn(1L);
            when(receipts.countActiveDeliveryLease(envelope.tenantId(), envelope.eventId(), "test-lease")).thenReturn(1);
            executor.deliver(envelope, consumer, "test-lease");
        }
        IntegrationEventEnvelope event(IntegrationEventEnvelope input, String type, String reference) {
            Map<String, Object> payload = new HashMap<>(input.payload());
            payload.put("resolutionReference", reference);
            return new IntegrationEventEnvelope(input.schemaVersion(), input.eventId(), type, input.source(), input.subject(), input.occurredAt(), input.tenantId(), input.correlationId(), input.causationId(), input.orderingKey(), input.sequence(), payload, input.headers());
        }
    }
    static class Fixture implements SourceBindingReader {
        int reads, links = 1;
        long invalidUser;
        Map<String, Object> lastFilters;
        DataPage scanPage;
        Map<Integer, DataPage> childPages = new HashMap<>();
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
            Map<String, Object> row = records.get(model + "/" + pid);
            return row == null ? null : new SourceBindingReader.LiveRecord(row, true);
        }
        public DataPage page(Binding b, String model, Map<String, Object> filters, int page, int size) {
            reads++;
            lastFilters = filters;
            if (model.equals("source")) return scanPage;
            if (model.equals("child")) {
                if (childPages.containsKey(page)) return childPages.get(page);
                var rows = records.entrySet().stream().filter(e -> e.getKey().startsWith("child/"))
                        .map(Map.Entry::getValue).filter(r -> filters.entrySet().stream().allMatch(e -> e.getValue().equals(r.get(e.getKey()))))
                        .sorted(java.util.Comparator.comparing(r -> r.get("pid").toString())).toList();
                int start = Math.min(rows.size(), (page - 1) * size);
                return new DataPage(rows.subList(start, Math.min(rows.size(), start + size)), rows.size(), page, size);
            }
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
