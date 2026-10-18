package com.auraboot.framework.notification.sourcebinding;

import com.auraboot.framework.plugin.extension.BackgroundDataAccessor;
import com.auraboot.framework.plugin.extension.DataPageQuery;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Hermetic boundary checks only; these do not claim that a production tick or database write occurred. */
class ProcurementReminderTickContractTest {
    private final List<String> forwarded = new ArrayList<>();
    private final BackgroundDataAccessor delegate = (BackgroundDataAccessor) Proxy.newProxyInstance(
            BackgroundDataAccessor.class.getClassLoader(), new Class<?>[]{BackgroundDataAccessor.class}, (proxy, method, args) -> {
                forwarded.add(method.getName());
                if (method.getName().equals("executeInTransaction")) ((Runnable) args[0]).run();
                return null;
            });
    private final ProcurementReminderTickNativeIT.TenantBoundBackground frame =
            new ProcurementReminderTickNativeIT.TenantBoundBackground(123, "supplier-owned", "quote-owned", delegate);

    @Test
    void exactBoundedSupplierAndOwnedQuoteReadsForwardWithoutReplacingTheHost() {
        frame.proxy().queryPage(123, "pr_supplier_common", Map.of("pr_supplier_status", "enabled"), null, 100);
        frame.proxy().queryPage(123, "s2p_bid", query("supplier-owned"));
        assertThat(forwarded).containsExactly("queryPage", "queryPage");
        assertThat(frame.actual).isSameAs(delegate);
    }

    @Test
    void foreignTenantAndWrongSupplierAreRejectedBeforeDelegation() {
        assertThatThrownBy(() -> frame.proxy().queryPage(124, "pr_supplier_common", Map.of("pr_supplier_status", "enabled"), null, 100))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> frame.proxy().queryPage(123, "s2p_bid", query("foreign-supplier"))).isInstanceOf(SecurityException.class);
        assertThat(forwarded).isEmpty();
    }

    @Test
    void arbitraryWritesQueriesAndBroaderPaginationAreRejected() {
        assertThatThrownBy(() -> frame.proxy().update(123, "s2p_bid", "quote-owned", Map.of("s2p_bid_status", "submitted")))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> frame.proxy().create(123, "qo_procurement_reminder_common", reminder())).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> frame.proxy().query(123, "pr_supplier_common", Map.of())).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> frame.proxy().queryPage(123, "pr_supplier_common", Map.of("pr_supplier_status", "enabled"), null, 1000))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> frame.proxy().queryPage(123, "s2p_bid", new DataPageQuery(Map.of(), Map.of(), List.of(), 1, 1000)))
                .isInstanceOf(SecurityException.class);
        assertThat(forwarded).isEmpty();
    }

    @Test
    void onlyTheExactSameSlotReminderCanReachRealIdempotentCreate() {
        frame.proxy().tryCreate(123, "qo_procurement_reminder_common", reminder());
        assertThat(forwarded).containsExactly("tryCreate");
        for (String field : List.of("qo_pr_reminder_key", "qo_pr_reminder_supplier_pid", "qo_pr_reminder_kind",
                "qo_pr_reminder_slot_at", "qo_pr_reminder_state", "qo_pr_reminder_revision", "qo_pr_reminder_targets")) {
            Map<String, Object> bad = new LinkedHashMap<>(reminder());
            bad.put(field, field.equals("qo_pr_reminder_targets") ? List.of("foreign-quote") : "forged");
            assertThatThrownBy(() -> frame.proxy().tryCreate(123, "qo_procurement_reminder_common", bad)).isInstanceOf(SecurityException.class);
        }
        Map<String, Object> extra = new LinkedHashMap<>(reminder());
        extra.put("tenant_id", 124L);
        assertThatThrownBy(() -> frame.proxy().tryCreate(123, "qo_procurement_reminder_common", extra)).isInstanceOf(SecurityException.class);
        assertThat(forwarded).containsExactly("tryCreate");
    }

    @Test
    void actualHostTransactionRunsWithTheSameRestrictedAccessorForNestedWork() {
        frame.proxy().executeInTransaction(() -> frame.proxy().tryCreate(123, "qo_procurement_reminder_common", reminder()));
        assertThat(forwarded).containsExactly("executeInTransaction", "tryCreate");
        assertThatThrownBy(() -> frame.proxy().executeInTransaction(() -> frame.proxy().delete(124, "s2p_bid", "foreign")))
                .isInstanceOf(SecurityException.class);
        assertThat(forwarded).containsExactly("executeInTransaction", "tryCreate", "executeInTransaction");
    }

    @Test
    void allExistingFacetSourceRemainsByteIdenticalAfterRemovingOnlyTheAddedFactory() throws Exception {
        Path source = Path.of("src/test/java/com/auraboot/framework/notification/sourcebinding/ProductQuoteReminderNativeFacet.java");
        if (!Files.exists(source)) source = Path.of("platform").resolve(source);
        String current = Files.readString(source);
        String start = "    // BEGIN additive controlled-tick setup. Existing facet methods remain unchanged.\n";
        String end = "    // END additive controlled-tick setup.\n\n";
        int first = current.indexOf(start);
        int last = current.indexOf(end, first);
        assertThat(first).isNotNegative();
        assertThat(current.indexOf(start, first + 1)).isEqualTo(-1);
        assertThat(last).isGreaterThan(first);
        String original = current.substring(0, first) + current.substring(last + end.length());
        assertThat(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(original.getBytes(java.nio.charset.StandardCharsets.UTF_8)))).isEqualTo("4958d64035d321914b2357caece3fcc47b6adc5941a156cd45b9b7b159ee7e28");
        assertThat(original).contains("\"nativeTickExecuted\", false");
    }

    @Test
    void clockAndPhysicalIdentifiersCannotExpandTheNativeBoundary() {
        var clock = new ProcurementReminderTickNativeIT.ControlledClock();
        assertThat(clock.instant()).isEqualTo(ProcurementReminderTickNativeIT.SLOT);
        assertThatThrownBy(() -> clock.withZone(java.time.ZoneId.of("Asia/Shanghai"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProcurementReminderTickNativeIT.identifier("mt_safe;delete from ab_user"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ProcurementReminderTickNativeIT.BUSINESS).hasSize(19).doesNotHaveDuplicates();
    }

    private static DataPageQuery query(String supplier) {
        return new DataPageQuery(Map.of("s2p_bid_supplier_id", supplier, "s2p_bid_collaboration_managed", true), Map.of(),
                List.of(new DataPageQuery.Sort("pid", false)), 1, DataPageQuery.MAX_SIZE);
    }
    private static Map<String, Object> reminder() {
        return Map.of("qo_pr_reminder_key", ProcurementReminderTickNativeIT.TenantBoundBackground.reminderKey("supplier-owned"),
                "qo_pr_reminder_supplier_pid", "supplier-owned", "qo_pr_reminder_kind", "quote",
                "qo_pr_reminder_slot_at", ProcurementReminderTickNativeIT.SLOT.toString(), "qo_pr_reminder_state", "pending",
                "qo_pr_reminder_revision", 0, "qo_pr_reminder_targets", List.of("quote-owned"));
    }
    // BEGIN additive automatic-registry contracts.
    @org.junit.jupiter.api.io.TempDir Path automaticResources;

    private ProcurementReminderTickNativeIT.AutomaticBackground automaticFrame(String kind, BackgroundDataAccessor actual) {
        return new ProcurementReminderTickNativeIT.AutomaticBackground(123, "supplier-owned", "quote-owned", "order-owned",
                kind.equals("quote") ? "quote-owned" : "order-owned", kind,
                kind.equals("quote") ? ProcurementReminderTickNativeIT.SLOT : ProcurementReminderTickNativeIT.SHIPMENT_SLOT, actual);
    }

    @Test
    void automaticFrameForwardsRealTransactionThenCountsCompletionAndBothExactKinds() {
        for (String kind : List.of("quote", "shipment")) {
            var f = automaticFrame(kind, delegate);
            f.proxy().executeInTransaction(() -> f.proxy().tryCreate(123, "qo_procurement_reminder_common", f.exactReminder()));
            assertThat(f.completedTransactions.get()).isEqualTo(1);
            assertThat(f.committed.getCount()).isEqualTo(1);
            assertThat(f.attempts.get()).isEqualTo(1);
            assertThat(f.failure.get()).isNull();
        }
        assertThat(forwarded).containsExactly("executeInTransaction", "tryCreate", "executeInTransaction", "tryCreate");
    }

    @Test
    void automaticFrameRejectsForeignScopeUnknownWritesAndWritesOutsideItsTransaction() {
        var f = automaticFrame("shipment", delegate);
        assertThatThrownBy(() -> f.proxy().queryPage(124, "pr_supplier_common", Map.of("pr_supplier_status", "enabled"), null, 100))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> f.proxy().tryCreate(123, "qo_procurement_reminder_common", f.exactReminder())).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> f.proxy().update(123, "pr_purchase_order", "order-owned", Map.of("qo_pr_order_state", "closed")))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> f.proxy().executeInTransaction(() -> f.proxy().queryPage(123, "pr_purchase_order_line",
                new DataPageQuery(Map.of("pr_pol_order_id", "foreign-order", "pr_pol_collaboration_managed", true), Map.of(),
                        List.of(new DataPageQuery.Sort("pid", false)), 1, DataPageQuery.MAX_SIZE)))).isInstanceOf(SecurityException.class);
        assertThat(forwarded).containsExactly("executeInTransaction");
    }

    @Test
    void everyAutomaticReminderFieldAndAddedAuthorityAreRejectedBeforeTheHostWrite() {
        for (String field : List.of("qo_pr_reminder_key", "qo_pr_reminder_supplier_pid", "qo_pr_reminder_kind", "qo_pr_reminder_slot_at",
                "qo_pr_reminder_state", "qo_pr_reminder_revision", "qo_pr_reminder_targets", "tenant_id")) {
            var f = automaticFrame("shipment", delegate);
            var bad = new LinkedHashMap<String, Object>(f.exactReminder());
            bad.put(field, field.equals("qo_pr_reminder_targets") ? List.of("foreign-order") : "forged");
            assertThatThrownBy(() -> f.proxy().executeInTransaction(() -> f.proxy().tryCreate(123, "qo_procurement_reminder_common", bad)))
                    .isInstanceOf(SecurityException.class);
        }
        assertThat(forwarded).allMatch(name -> name.equals("executeInTransaction"));
    }

    @Test
    void swallowedProductionCallbackFailureCannotCountAsACommittedTimerCallback() {
        BackgroundDataAccessor failing = (BackgroundDataAccessor) Proxy.newProxyInstance(BackgroundDataAccessor.class.getClassLoader(),
                new Class<?>[]{BackgroundDataAccessor.class}, (proxy, method, args) -> { throw new IllegalStateException("synthetic host failure"); });
        var f = automaticFrame("quote", failing);
        assertThatThrownBy(() -> f.proxy().executeInTransaction(() -> {})).isInstanceOf(IllegalStateException.class);
        assertThat(f.completedTransactions.get()).isZero();
        assertThat(f.committed.getCount()).isEqualTo(2);
        assertThat(f.failure.get()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shipmentBindingRequiresOpenManagedParentAndActualPositiveRemainingChild() {
        var target = ProductQuoteReminderNativeFacet.automaticShipmentTarget();
        assertThat(target.entity().modelCode()).isEqualTo("pr_purchase_order");
        assertThat(target.entity().equals()).containsExactly(new SourceBindingTypes.Exact("pr_po_collaboration_managed", true),
                new SourceBindingTypes.Exact("qo_pr_order_state", "open"));
        assertThat(target.ownerField()).isEqualTo("pr_po_supplier");
        assertThat(target.deadline()).isNull();
        assertThat(target.remainingChild()).isEqualTo(new SourceBindingTypes.RemainingChild(
                new SourceBindingTypes.Entity("pr_purchase_order_line", List.of(new SourceBindingTypes.Exact("pr_pol_collaboration_managed", true))),
                "pr_pol_order_id", "pr_pol_qty", "qo_pr_order_line_submitted_qty", 1000));
        assertThat(ProductQuoteReminderNativeFacet.automaticShipmentEntity().equals())
                .contains(new SourceBindingTypes.Exact("qo_pr_reminder_kind", "shipment"));
        assertThatThrownBy(() -> new ProcurementReminderTickNativeIT.AutomaticBackground(123, "s", "q", "o", "q", "shipment",
                ProcurementReminderTickNativeIT.SHIPMENT_SLOT, delegate)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProductQuoteNativeInitialization.validateAutomaticPayload("pr_purchase_order_line", Map.of("pr_pol_order_id", "o")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void automaticNativeAppendixPreservesAllExistingWritersAndOnlyAddsExactCreateAndParentDependency() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        Path original = automaticResources.resolve("original");
        Files.createDirectories(original.resolve("config/fields"));
        Files.writeString(original.resolve("plugin.json"), "{\"pluginId\":\"com.auraboot.procurement\",\"namespace\":\"pr\"}");
        for (String marker : List.of("pr_po_collaboration_managed", "pr_pol_collaboration_managed")) {
            Files.writeString(original.resolve("config/fields/" + marker + ".json"), json.writeValueAsString(Map.of(
                    "code", marker, "dataType", "boolean", "immutable", true, "extension", Map.of("systemManaged", true),
                    "allowedWriterCommands", List.of("existing:create"))));
        }
        var models = List.of(Map.of("code", "pr_purchase_order", "extension", Map.of("recordCommandWriters", Map.of(
                "field", "pr_po_collaboration_managed", "commands", Map.of("create", List.of("existing:create"), "update", List.of("existing:update"), "delete", List.of("existing:delete"))))),
                Map.of("code", "pr_purchase_order_line", "extension", Map.of("recordCommandWriters", Map.of("field", "pr_pol_collaboration_managed",
                        "commands", Map.of("create", List.of("existing:create"), "update", List.of("existing:update"), "delete", List.of("existing:delete"))))));
        String originalModels = json.writeValueAsString(models);
        Files.writeString(original.resolve("config/models.json"), originalModels);
        Path destination = automaticResources.resolve("copy");
        ProductQuoteNativeInitialization.prepareAutomaticProcurementOwner(original, destination, json);
        var result = json.readTree(destination.resolve("config/models.json").toFile());
        for (var model : result) {
            String code = model.path("code").asText();
            var policy = model.path("extension").path("recordCommandWriters");
            com.auraboot.framework.meta.security.RecordCommandWriterDeclaration.validate(json.convertValue(policy, Map.class));
            assertThat(json.convertValue(policy.path("commands").path("create"), List.class)).containsExactly("existing:create", ProductQuoteNativeInitialization.automaticCommand(code));
            assertThat(json.convertValue(policy.path("commands").path("delete"), List.class)).containsExactly("existing:delete");
            assertThat(json.convertValue(policy.path("commands").path("update"), List.class)).containsExactlyElementsOf(code.equals("pr_purchase_order")
                    ? List.of("existing:update", ProductQuoteNativeInitialization.automaticCommand("pr_purchase_order_line")) : List.of("existing:update"));
        }
        assertThat(Files.readString(original.resolve("config/models.json"))).isEqualTo(originalModels);
        // A wrong owner/ordinary marker must not be converted into test authority.
        var marker = json.readTree(original.resolve("config/fields/pr_po_collaboration_managed.json").toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode) marker).put("immutable", false);
        Files.writeString(original.resolve("config/fields/pr_po_collaboration_managed.json"), marker.toString());
        assertThatThrownBy(() -> ProductQuoteNativeInitialization.prepareAutomaticProcurementOwner(original, automaticResources.resolve("bad"), json))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isolatedAutomaticContextRetainsProductionAnnotationDiscoveryAndLeavesAllPriorSourcesUnchanged() throws Exception {
        assertThat(java.util.Arrays.stream(ProcurementReminderTickNativeIT.class.getDeclaredClasses())
                .noneMatch(type -> org.springframework.core.annotation.AnnotatedElementUtils.hasAnnotation(type,
                        org.springframework.context.annotation.Configuration.class))).isTrue();
        Path root = Path.of("src/test/java/com/auraboot/framework/notification/sourcebinding");
        if (!Files.isDirectory(root)) root = Path.of("platform").resolve(root);
        Map<String, String> originalHashes = Map.of(
                "ProcurementReminderTickNativeIT.java", "b837f476d5bc8b31b582c336e053ec0c88702e415ca5a53aefd5cc451ca20f88",
                "ProductQuoteNativeInitialization.java", "af7f97c3c9308efc4f39063392c8dbe76dbd0f34b1d6410b8955ede3fa7b936a",
                "ProductQuoteReminderNativeFacet.java", "759436112d6412371baceae75519b84b2104e94948f306a2a239a62e4a8234d0");
        for (var entry : originalHashes.entrySet()) {
            String content = Files.readString(root.resolve(entry.getKey()));
            String start = entry.getKey().equals("ProcurementReminderTickNativeIT.java") ? "    // BEGIN additive PF4J automatic Native.\n"
                    : entry.getKey().equals("ProductQuoteNativeInitialization.java") ? "    // BEGIN additive automatic-registry fixture.\n"
                    : "    // BEGIN additive PF4J automatic fixture.\n";
            String end = entry.getKey().equals("ProcurementReminderTickNativeIT.java") ? "    // END additive PF4J automatic Native.\n"
                    : entry.getKey().equals("ProductQuoteNativeInitialization.java") ? "    // END additive automatic-registry fixture.\n"
                    : "    // END additive PF4J automatic fixture.\n";
            int first = content.indexOf(start), last = content.indexOf(end, first);
            assertThat(first).isNotNegative(); assertThat(last).isGreaterThan(first);
            assertThat(content.indexOf(start, first + 1)).isEqualTo(-1);
            String original = content.substring(0, first) + content.substring(last + end.length());
            assertThat(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(original.getBytes(java.nio.charset.StandardCharsets.UTF_8)))).isEqualTo(entry.getValue());
        }
    }
    public static final class FactoryClockProbe implements com.auraboot.framework.plugin.extension.BackgroundComponentExtension {
        private java.time.Clock clock = java.time.Clock.systemUTC();
        public FactoryClockProbe() {}
    }
    @Test
    void actualSpringExtensionFactoryCreatesDistinctObjectsAndClockIsInstalledOnRegistrationNotPrefetch() {
        try (var spring = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            spring.refresh();
            var manager = new org.pf4j.spring.SpringPluginManager(java.util.List.of(automaticResources));
            manager.setApplicationContext(spring);
            var factory = manager.getExtensionFactory();
            var prefetched = factory.create(FactoryClockProbe.class);
            var registered = factory.create(FactoryClockProbe.class);
            assertThat(registered).isNotSameAs(prefetched);
            var originalClock = prefetched.clock;
            var fixed = java.time.Clock.fixed(ProcurementReminderTickNativeIT.SLOT, java.time.ZoneId.of("UTC"));
            var captured = new ProcurementReminderTickNativeIT.RegisteredAutomaticClock();
            captured.install(registered, fixed);
            assertThat(captured.bean.get()).isSameAs(registered);
            assertThat(registered.clock).isSameAs(fixed);
            assertThat(prefetched.clock).isSameAs(originalClock).isNotSameAs(fixed);
            assertThatThrownBy(() -> captured.install(prefetched, fixed)).isInstanceOf(AssertionError.class);
        }
    }
    @Test
    void intentTeardownEvidenceUsesActualNumericIdOrderRatherThanEventHashOrJsonLexicalOrder() {
        var rows = ProcurementReminderTickNativeIT.actualOwnedOutboxRows(List.of(
                "{\"id\":10,\"event_id\":\"notification-a\"}", "{\"id\":9,\"event_id\":\"notification-z\"}"));
        assertThat(rows.stream().map(row -> row.path("id").longValue()).toList()).containsExactly(9L, 10L);
        assertThatThrownBy(() -> ProcurementReminderTickNativeIT.actualOwnedOutboxRows(List.of("{\"id\":1}", "{\"id\":1}")))
                .isInstanceOf(AssertionError.class);
    }
    public static final class AutomaticScheduledProbe {
        final java.util.concurrent.CountDownLatch callback = new java.util.concurrent.CountDownLatch(1);
        @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 25)
        public void scheduledCallback() { callback.countDown(); }
    }
    @Test
    void explicitChildSchedulingRunsRealCallbackWithoutImportingRootSchedulingConfiguration() throws Exception {
        try (var root = new org.springframework.context.annotation.AnnotationConfigApplicationContext();
                var child = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            root.refresh();
            assertThat(root.getBeansOfType(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
            var timer = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler(); timer.setPoolSize(1);
            child.registerBean("taskScheduler", org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class, () -> timer);
            ProcurementReminderTickNativeIT.registerAutomaticScheduling(child);
            child.refresh();
            var probe = new AutomaticScheduledProbe();
            Object initialized = child.getBeanFactory().initializeBean(probe, "boundedProbe");
            child.getBeanFactory().registerSingleton("boundedProbe", initialized);
            assertThat(probe.callback.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(child.getBean(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()).hasSize(1);
            assertThat(root.getBeansOfType(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
        }
    }
    // END additive automatic-registry contracts.
}
