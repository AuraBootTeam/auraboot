package com.auraboot.framework.notification.sourcebinding;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.crypto.FieldEncryptionService;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.ReliableEventDeliveryExecutor;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.notification.service.impl.PluginNotificationDeliveryAccessorImpl;
import com.auraboot.framework.plugin.extension.BackgroundDataAccessor;
import com.auraboot.framework.plugin.extension.BackgroundTenantAccessor;
import com.auraboot.framework.plugin.extension.DataPageQuery;
import com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelope;
import com.auraboot.framework.scheduler.service.SchedulerEngine;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;

/** Explicit controlled tick over one new synthetic tenant; never a global timer or external delivery proof. */
@SpringBootTest(classes = TestApplication.class, properties = {
        "spring.datasource.url=${TEST_DATABASE_URL}", "spring.data.redis.database=${TEST_REDIS_DATABASE}",
        "spring.datasource.hikari.connection-init-sql=SET TIME ZONE 'UTC'",
        "s06.native.source-binding.fixture=true", "aura.notification.wechat-subscription.enabled=false",
        "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.slf4j.Slf4jImpl",
        "logging.level.com.auraboot.framework=INFO", "logging.level.org.mybatis=INFO"})
@ActiveProfiles("integration-test")
@Import(RegisteredSourceBindingPostgresIT.PollerIsolation.class)
@EnabledIfEnvironmentVariable(named = "PROCUREMENT_REMINDER_TICK_NATIVE", matches = "true")
class ProcurementReminderTickNativeIT {
    static final Instant SLOT = Instant.parse("2026-10-13T01:30:00Z");
    static final List<String> BUSINESS = List.of("prod_product", "pr_purchase_request", "s2p_sourcing_event", "s2p_bid",
            "qo_procurement_quote_line_common", "qo_procurement_history_common", "qo_procurement_submission_common",
            "qo_procurement_submission_line_common", "qo_procurement_allocation_common", "qo_procurement_execution_common",
            "pr_purchase_order", "pr_purchase_order_line", "pr_supplier_common", "pr_supplier_pcba_contact",
            "qo_procurement_membership_common", "pr_spend_category", "qo_procurement_category_policy_common",
            "qo_procurement_category_supplier_common", "customer_pcba_int_external_ref");
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired DataSource dataSource;
    @MockitoBean SchedulerEngine schedulerEngine;

    @DynamicPropertySource
    static void matchingCryptoAndProductDirectory(DynamicPropertyRegistry properties) {
        String key = S06NativeEncryptionAdmission.requiredKey(System.getenv());
        properties.add("security.field-encryption.key", () -> key);
        ProductQuoteNativePluginDirectory.select(true, Path.of(required("S06_NATIVE_PRODUCT_INPUT")),
                required("S06_NATIVE_PRODUCT_INPUT_SHA256"), Path.of(required("S06_NATIVE_PRODUCT_PLUGIN_DIR")),
                NativeProcurementWorkspace.root().resolve(".workspace/runtimes").resolve(NativeProcurementWorkspace.runtime()))
                .ifPresent(path -> properties.add("aura.plugins.dir", path::toString));
    }

    @Test
    void actualControlledTickQueuesOnceAndDeliversTwoExplicitInAppRecipients() throws Exception {
        Path evidence = Path.of(required("PROCUREMENT_REMINDER_TICK_EVIDENCE_DIR")).toRealPath();
        assertThat(evidence.startsWith(NativeProcurementWorkspace.root().resolve(".workspace/evidence").resolve(NativeProcurementWorkspace.runtime()).toRealPath())).isTrue();
        assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
        assertThat(context.getBean(com.auraboot.framework.notification.wechat.WechatSubscriptionProperties.class).isEnabled()).isFalse();
        assertThat(System.getenv("TEST_REDIS_DATABASE")).isEqualTo("13");
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(connection.getCatalog()).isEqualTo("enterprise_22");
            assertThat(connection.getMetaData().getURL()).matches("jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):5432/enterprise_22(?:\\?.*)?");
        }
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("matchingEncryptionAdmission", S06NativeEncryptionAdmission.admit(System.getenv(), json,
                context.getBean(FieldEncryptionService.class), getClass(), context.getEnvironment().getActiveProfiles()));
        var baselineTables = physicalTables(Long.parseLong(required("TEST_TENANT_ID")));
        Map<String, Object> protectedBefore = snapshot(baselineTables, -1L, false);
        var fixture = ProductQuoteReminderNativeFacet.prepareTickFixture(services(), evidence);
        Map<String, Object> newMetadataBefore = metadata(fixture.tenantId(), true);
        Map<String, Object> businessBeforeTick = snapshot(fixture.tables(), fixture.tenantId(), true);
        ControlledClock clock = new ControlledClock();
        var restricted = new TenantBoundBackground(fixture.tenantId(), fixture.supplierPid(), fixture.quotePid(),
                context.getBean(BackgroundDataAccessor.class));
        String stage = "controlled-tick-starting";
        boolean completed = false;
        try (var loader = new URLClassLoader(new java.net.URL[]{fixture.productJar().toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> type = loader.loadClass("com.auraboot.quote.engine.procurement.ProcurementReminderScheduler");
            assertThat(type.getClassLoader()).isSameAs(loader);
            assertThat(Files.isSameFile(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()), fixture.productJar())).isTrue();
            var constructor = type.getDeclaredConstructor(Clock.class);
            constructor.setAccessible(true);
            Object scheduler = constructor.newInstance(clock);
            ReflectionTestUtils.setField(scheduler, "background", restricted.proxy());
            ReflectionTestUtils.setField(scheduler, "tenants", (BackgroundTenantAccessor) () -> List.of(fixture.tenantId()));
            ReflectionTestUtils.setField(scheduler, "enabled", true);
            Method tick = type.getMethod("tick");
            MetaContext.clear();
            invokeTick(tick, scheduler);
            assertThat(MetaContext.exists()).isFalse();
            List<String> reminders = rows(fixture.tables().get("qo_procurement_reminder_common"), fixture.tenantId());
            assertThat(reminders).hasSize(1);
            JsonNode reminder = json.readTree(reminders.getFirst());
            assertThat(reminder.path("qo_pr_reminder_kind").asText()).isEqualTo("quote");
            assertThat(reminder.path("qo_pr_reminder_state").asText()).isEqualTo("pending");
            JsonNode targets = reminder.path("qo_pr_reminder_targets");
            if (targets.isTextual()) targets = json.readTree(targets.textValue());
            assertThat(targets).isEqualTo(json.valueToTree(List.of(fixture.quotePid())));
            String sourcePid = reminder.path("pid").asText();
            assertThat(sourcePid).isNotBlank();
            assertThat(count("ab_outbox", fixture.tenantId())).isZero();
            invokeTick(tick, scheduler);
            assertThat(rows(fixture.tables().get("qo_procurement_reminder_common"), fixture.tenantId())).isEqualTo(reminders);
            int calls = restricted.calls.size();
            clock.now = SLOT.plusSeconds(60);
            invokeTick(tick, scheduler);
            assertThat(restricted.calls).hasSize(calls);
            clock.now = SLOT.plusSeconds(7200);
            invokeTick(tick, scheduler);
            assertThat(rows(fixture.tables().get("qo_procurement_reminder_common"), fixture.tenantId())).isEqualTo(reminders);
            assertThat(restricted.tryCreates).isEqualTo(2);
            assertThat(count("ab_outbox", fixture.tenantId())).isZero();
            assertThat(count("ab_notification", fixture.tenantId())).isZero();
            assertThat(count("ab_integration_receipt", fixture.tenantId())).isZero();
            Map<String, Object> afterTick = snapshot(fixture.tables(), fixture.tenantId(), true);
            Map<String, Object> nonReminderBefore = new LinkedHashMap<>(businessBeforeTick);
            Map<String, Object> nonReminderAfter = new LinkedHashMap<>(afterTick);
            nonReminderBefore.remove("qo_procurement_reminder_common");
            nonReminderAfter.remove("qo_procurement_reminder_common");
            assertThat(nonReminderAfter).isEqualTo(nonReminderBefore);
            clock.now = SLOT;
            stage = "actual-registered-worker-and-consumer";
            HostSourceBindingProperties settings = new HostSourceBindingProperties();
            settings.setEnabled(true);
            settings.setBindings(List.of(fixture.binding()));
            var planner = new RegisteredSourcePlanResolver(settings, context.getBean(ImportedNotificationMetadataReader.class),
                    context.getBean(ImportedNotificationMetadataValidator.class), clock);
            planner.initialize();
            var host = new PluginNotificationDeliveryAccessorImpl(List.of(planner),
                    context.getBean(com.auraboot.framework.plugin.extension.integration.ReliableIntegrationAccessor.class),
                    context.getBean(com.auraboot.framework.notification.service.impl.StrictInAppDeliveryWriter.class));
            ReflectionTestUtils.setField(host, "enabled", true);
            var worker = new RegisteredSourceWorker(planner, context.getBean(ImportedNotificationMetadataReader.class), host,
                    context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
            assertThat(worker.process(fixture.tenantId(), fixture.binding().code(), sourcePid)).isEqualTo(2);
            List<IntegrationEventEnvelope> events = events(fixture.tenantId());
            assertThat(events).hasSize(2);
            List<String> originalOutbox = rows("ab_outbox", fixture.tenantId());
            assertThat(worker.process(fixture.tenantId(), fixture.binding().code(), sourcePid)).isEqualTo(2);
            assertThat(rows("ab_outbox", fixture.tenantId())).isEqualTo(originalOutbox);
            var executor = context.getBean(ReliableEventDeliveryExecutor.class);
            for (var event : events) executor.deliver(event, host, UniqueIdGenerator.generate());
            assertThat(jdbc.queryForList("SELECT user_id FROM ab_notification WHERE tenant_id=? ORDER BY user_id", Long.class,
                    fixture.tenantId())).containsExactlyInAnyOrderElementsOf(fixture.recipientIds());
            assertThat(count("ab_integration_receipt", fixture.tenantId())).isEqualTo(2);
            List<String> inbox = rows("ab_notification", fixture.tenantId());
            List<String> receipts = rows("ab_integration_receipt", fixture.tenantId());
            for (var event : events) executor.deliver(event, host, UniqueIdGenerator.generate());
            assertThat(rows("ab_notification", fixture.tenantId())).isEqualTo(inbox);
            assertThat(rows("ab_integration_receipt", fixture.tenantId())).isEqualTo(receipts);
            assertThat(snapshot(fixture.tables(), fixture.tenantId(), true)).isEqualTo(afterTick);
            assertThat(snapshot(baselineTables, fixture.tenantId(), false)).isEqualTo(protectedBefore);
            assertThat(metadata(fixture.tenantId(), true)).isEqualTo(newMetadataBefore);
            receipt.put("sourcePid", sourcePid);
            receipt.put("ownedOutboxRowsBeforeTeardown", originalOutbox.stream().map(SourceBindingCanonical::parse).toList());
            receipt.put("protectedSnapshot", protectedBefore);
            receipt.put("businessAfterTick", afterTick);
            receipt.put("inboxCount", inbox.size());
            receipt.put("receiptCount", receipts.size());
            receipt.put("nativeTickExecuted", true);
            receipt.put("tickInvocations", Map.of("sameSlot", 2, "offSlot", 1, "expired", 1));
            receipt.put("explicitRecipientIds", fixture.recipientIds());
            receipt.put("testTenantOnly", true);
            receipt.put("backgroundBeanClass", restricted.actual.getClass().getName());
            receipt.put("sameSlotTryCreateCalls", restricted.tryCreates);
            receipt.put("newTenantMetadataBeforeAndAfter", newMetadataBefore);
            receipt.put("automaticTimerVerified", false);
            receipt.put("formalRecipientPolicyApproved", false);
            receipt.put("providerCalls", 0);
            receipt.put("wechatCalls", 0);
            receipt.put("productJarSha256", hash(fixture.productJar()));
            receipt.put("schedulerClass", type.getName());
            stage = "controlled-tick-completed";
            completed = true;
        } finally {
            MetaContext.clear();
            receipt.put("stage", stage);
            receipt.put("completed", completed);
            receipt.put("tenantId", Long.toString(fixture.tenantId()));
            receipt.put("fixtureSetupPath", fixture.setupEvidence().toString());
            receipt.put("fixtureSetupSha256", fixture.setupSha256());
            receipt.put("backgroundCalls", restricted.calls);
            Path sealed = evidence.resolve("reminder-tick-receipt.json");
            Files.writeString(sealed, json.writerWithDefaultPrettyPrinter().writeValueAsString(receipt), StandardOpenOption.CREATE_NEW);
            if (completed) {
                String sourcePid = (String) receipt.get("sourcePid");
                Set<String> references = fixture.recipientIds().stream().map(id -> SourceBindingCanonical.reference(
                        fixture.binding().code(), sourcePid, id)).collect(java.util.stream.Collectors.toSet());
                TestOwnedNotificationIntentTeardown.run(dataSource, new TestOwnedNotificationIntentTeardown.Scope(
                        fixture.tenantId(), fixture.binding().code(), sourcePid, references),
                        rows("ab_outbox", fixture.tenantId()).stream().map(SourceBindingCanonical::parse).toList(), sealed,
                        evidence.resolve("reminder-tick-teardown.json"));
                assertThat(snapshot(baselineTables, fixture.tenantId(), false)).isEqualTo(protectedBefore);
                assertThat(metadata(fixture.tenantId(), true)).isEqualTo(newMetadataBefore);
                assertThat(snapshot(fixture.tables(), fixture.tenantId(), true)).isEqualTo(receipt.get("businessAfterTick"));
                assertThat(count("ab_notification", fixture.tenantId())).isEqualTo(2);
                assertThat(count("ab_integration_receipt", fixture.tenantId())).isEqualTo(2);
            }
        }
    }

    private ProductQuoteReminderNativeFacet.Services services() {
        return new ProductQuoteReminderNativeFacet.Services(dataSource, jdbc, json,
                context.getBean(org.springframework.transaction.PlatformTransactionManager.class),
                context.getBean(com.auraboot.framework.tenant.service.TenantService.class),
                context.getBean(com.auraboot.framework.user.service.UserService.class),
                context.getBean(com.auraboot.framework.tenant.service.TenantMemberService.class),
                context.getBean(com.auraboot.framework.tenant.service.TenantBootstrapService.class),
                context.getBean(com.auraboot.framework.plugin.service.PluginImportService.class),
                context.getBean(com.auraboot.framework.meta.service.DynamicDataService.class),
                context.getBean(MetaModelService.class), context.getBean(com.auraboot.framework.meta.service.CommandExecutor.class),
                context.getBean(com.auraboot.framework.auth.service.AuthService.class), context.getBean(BackgroundDataAccessor.class),
                context.getBean(com.auraboot.framework.notification.service.NotificationTemplateService.class),
                context.getBean(com.auraboot.framework.notification.service.NotificationRuleService.class),
                context.getBean(ImportedNotificationMetadataReader.class), context.getBean(ImportedNotificationMetadataValidator.class),
                context.getBean(com.auraboot.framework.plugin.extension.integration.ReliableIntegrationAccessor.class),
                context.getBean(com.auraboot.framework.notification.service.impl.StrictInAppDeliveryWriter.class),
                context.getBean(ReliableEventDeliveryExecutor.class), context.getBean(com.auraboot.framework.plugin.pf4j.ExtensionRegistry.class), List.of());
    }

    private Map<String, String> physicalTables(long baselineTenant) {
        if (baselineTenant <= 0) throw new IllegalArgumentException("Positive sealed baseline tenant required");
        Map<String, String> result = new LinkedHashMap<>();
        MetaContext.Snapshot prior = MetaContext.snapshot();
        try {
            MetaContext.setContext(baselineTenant, 0L, null, "system");
            for (String model : BUSINESS) {
                String table = context.getBean(MetaModelService.class).getTableName(model);
                identifier(table);
                assertThat(table).startsWith("mt_");
                result.put(model, table);
            }
        } finally { MetaContext.clear(); MetaContext.restore(prior); }
        return Map.copyOf(result);
    }

    private Map<String, Object> snapshot(Map<String, String> tables, long tenant, boolean onlyTenant) {
        Map<String, Object> result = new LinkedHashMap<>();
        tables.forEach((model, table) -> result.put(model, summary(table, tenant, onlyTenant)));
        if (!onlyTenant) result.put("metadataAndDelivery", metadata(tenant, false));
        return result;
    }

    private Map<String, Object> metadata(long tenant, boolean onlyTenant) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String table : List.of("ab_meta_model", "ab_meta_field", "ab_meta_model_field_binding", "ab_plugin", "ab_plugin_resource",
                "ab_command_definition", "ab_notification_rule", "ab_notification_template")) {
            result.put(table, summary(table, tenant, onlyTenant));
        }
        if (!onlyTenant) {
            for (String table : List.of("ab_notification", "ab_integration_receipt", "ab_outbox"))
                result.put(table, summary(table, tenant, false));
        }
        return result;
    }

    private Map<String, Object> summary(String table, long tenant, boolean onlyTenant) {
        identifier(table);
        List<String> values = jdbc.queryForList("SELECT row_to_json(t)::text FROM \"" + table + "\" t WHERE tenant_id "
                + (onlyTenant ? "=" : "IS DISTINCT FROM") + " ? ORDER BY row_to_json(t)::text", String.class, tenant);
        return Map.of("count", values.size(), "sha256", SourceBindingCanonical.hash(values));
    }

    private List<String> rows(String table, long tenant) {
        identifier(table);
        return jdbc.queryForList("SELECT row_to_json(t)::text FROM \"" + table + "\" t WHERE tenant_id=? ORDER BY row_to_json(t)::text", String.class, tenant);
    }

    private long count(String table, long tenant) {
        identifier(table);
        return jdbc.queryForObject("SELECT count(*) FROM \"" + table + "\" WHERE tenant_id=?", Long.class, tenant);
    }

    private List<IntegrationEventEnvelope> events(long tenant) {
        return jdbc.query("SELECT * FROM ab_outbox WHERE tenant_id=? ORDER BY event_id", (rs, n) -> {
            try {
                return new IntegrationEventEnvelope(rs.getString("schema_version"), rs.getString("event_id"), rs.getString("event_type"),
                        rs.getString("event_source"), rs.getString("subject"), rs.getTimestamp("occurred_at").toInstant(), rs.getLong("tenant_id"),
                        rs.getString("correlation_id"), rs.getString("causation_id"), rs.getString("ordering_key"), rs.getLong("event_sequence"),
                        json.readValue(rs.getString("payload"), new TypeReference<Map<String, Object>>() {}),
                        json.readValue(rs.getString("headers"), new TypeReference<Map<String, String>>() {}));
            } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
                throw new IllegalStateException("Malformed actual persisted envelope", error);
            }
        }, tenant);
    }

    static void identifier(String value) {
        if (value == null || !value.matches("[a-z][a-z0-9_]*")) throw new IllegalArgumentException("Unsafe physical identifier");
    }

    static void invokeTick(Method method, Object scheduler) throws Exception {
        try { method.invoke(scheduler); }
        catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception cause) throw cause;
            throw error;
        }
    }

    static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing required fixture environment: " + key);
        return value;
    }

    static String hash(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    static final class ControlledClock extends Clock {
        Instant now = SLOT;
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) {
            if (!zone.equals(getZone())) throw new IllegalArgumentException("Only controlled UTC clock supported");
            return this;
        }
        @Override public Instant instant() { return now; }
    }

    /** Adds only a test-owned tenant/model boundary; all admitted work uses the real host bean unchanged. */
    static final class TenantBoundBackground implements java.lang.reflect.InvocationHandler {
        final long tenant;
        final String supplier;
        final String quote;
        final BackgroundDataAccessor actual;
        final List<Map<String, Object>> calls = new ArrayList<>();
        int tryCreates;
        TenantBoundBackground(long tenant, String supplier, String quote, BackgroundDataAccessor actual) {
            if (tenant <= 0 || supplier == null || supplier.isBlank() || quote == null || quote.isBlank())
                throw new IllegalArgumentException("Exact new fixture identity required");
            this.tenant = tenant; this.supplier = supplier; this.quote = quote; this.actual = java.util.Objects.requireNonNull(actual);
        }
        BackgroundDataAccessor proxy() {
            return (BackgroundDataAccessor) Proxy.newProxyInstance(BackgroundDataAccessor.class.getClassLoader(),
                    new Class<?>[]{BackgroundDataAccessor.class}, this);
        }
        @Override public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            Object[] args = arguments == null ? new Object[0] : arguments;
            validate(method.getName(), args);
            calls.add(Map.of("operation", method.getName(), "tenantId", Long.toString(tenant),
                    "model", args.length > 1 ? String.valueOf(args[1]) : "transaction"));
            try { return method.invoke(actual, args); }
            catch (InvocationTargetException error) { throw error.getCause(); }
        }
        static String reminderKey(String supplier) {
            StringBuilder key = new StringBuilder();
            for (String part : List.of(supplier, "quote", "2026-10-13", "09:30"))
                key.append(part.length()).append(':').append(part);
            return key.toString();
        }
        void validate(String operation, Object[] args) {
            if (operation.equals("executeInTransaction") && args.length == 1 && args[0] instanceof Runnable) return;
            if (args.length < 2 || !(args[0] instanceof Long id) || id != tenant)
                throw new SecurityException("Tick tenant boundary rejected");
            boolean allowed = false;
            if (operation.equals("queryPage") && args.length == 5 && args[1].equals("pr_supplier_common")) {
                allowed = Map.of("pr_supplier_status", "enabled").equals(args[2]) && (args[3] == null || args[3] instanceof String)
                        && Integer.valueOf(100).equals(args[4]);
            }
            if (operation.equals("queryPage") && args.length == 3 && args[1].equals("s2p_bid") && args[2] instanceof DataPageQuery page) {
                allowed = page.exactFilters().equals(Map.of("s2p_bid_supplier_id", supplier, "s2p_bid_collaboration_managed", true))
                        && page.anyOfFilters().isEmpty() && page.keyword().isEmpty() && page.page() >= 1
                        && page.size() == DataPageQuery.MAX_SIZE && page.sorts().equals(List.of(new DataPageQuery.Sort("pid", false)));
            }
            if (operation.equals("tryCreate") && args.length == 3 && args[1].equals("qo_procurement_reminder_common") && args[2] instanceof Map<?, ?> values) {
                allowed = values.keySet().equals(Set.of("qo_pr_reminder_key", "qo_pr_reminder_supplier_pid", "qo_pr_reminder_kind",
                        "qo_pr_reminder_slot_at", "qo_pr_reminder_state", "qo_pr_reminder_revision", "qo_pr_reminder_targets"))
                        && supplier.equals(values.get("qo_pr_reminder_supplier_pid")) && "quote".equals(values.get("qo_pr_reminder_kind"))
                        && SLOT.toString().equals(values.get("qo_pr_reminder_slot_at")) && "pending".equals(values.get("qo_pr_reminder_state"))
                        && Integer.valueOf(0).equals(values.get("qo_pr_reminder_revision")) && List.of(quote).equals(values.get("qo_pr_reminder_targets"))
                        && reminderKey(supplier).equals(values.get("qo_pr_reminder_key"));
                if (allowed) tryCreates++;
            }
            if (!allowed) throw new SecurityException("Tick operation boundary rejected");
        }
    }
    // BEGIN additive PF4J automatic Native.
    static final Instant SHIPMENT_SLOT = Instant.parse("2026-10-17T06:00:00Z");

    @Test
    void actualPf4jRegisteredTimerQueuesQuoteAndShipmentAndDeliversOnlySyntheticInApp() throws Exception {
        Path parent = Path.of(required("PROCUREMENT_REMINDER_TICK_EVIDENCE_DIR")).toRealPath();
        assertThat(parent.startsWith(NativeProcurementWorkspace.root().resolve(".workspace/evidence").resolve(NativeProcurementWorkspace.runtime()).toRealPath())).isTrue();
        Path evidence = Files.createDirectory(parent.resolve("pf4j-automatic-native"));
        assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
        assertThat(context.getBean(com.auraboot.framework.notification.wechat.WechatSubscriptionProperties.class).isEnabled()).isFalse();
        assertThat(System.getenv("TEST_REDIS_DATABASE")).isEqualTo("13");
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(connection.getCatalog()).isEqualTo("enterprise_22");
            assertThat(connection.getMetaData().getURL()).matches("jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):5432/enterprise_22(?:\\?.*)?");
        }
        var receipt = new LinkedHashMap<String, Object>();
        receipt.put("kind", "procurement-pf4j-automatic-quote-shipment-native-v1");
        receipt.put("matchingEncryptionAdmission", S06NativeEncryptionAdmission.admit(System.getenv(), json,
                context.getBean(FieldEncryptionService.class), getClass(), context.getEnvironment().getActiveProfiles()));
        var protectedTables = physicalTables(Long.parseLong(required("TEST_TENANT_ID")));
        var protectedBefore = automaticSnapshot(protectedTables, -1L, false);
        ProductQuoteReminderNativeFacet.AutomaticFixture fixture = null;
        Map<String, Object> metadataBefore = null;
        Map<String, Object> ownedBusinessBefore = null;
        List<Map<String, Object>> phases = new ArrayList<>();
        var outsideInApp = new java.util.concurrent.atomic.AtomicInteger();
        String stage = "automatic-fixture-starting";
        boolean completed = false;
        try {
            fixture = ProductQuoteReminderNativeFacet.prepareAutomaticFixture(services(), evidence);
            var q = fixture.quote();
            var tables = new LinkedHashMap<>(physicalTables(q.tenantId()));
            tables.put("qo_procurement_reminder_common", q.tables().get("qo_procurement_reminder_common"));
            metadataBefore = automaticMetadata(q.tenantId(), true);
            ownedBusinessBefore = automaticSnapshot(tables, q.tenantId(), true);
            assertThat(count("ab_outbox", q.tenantId())).isZero();
            assertThat(count("ab_notification", q.tenantId())).isZero();
            assertThat(count("ab_integration_receipt", q.tenantId())).isZero();
            for (String kind : List.of("quote", "shipment")) {
                stage = "automatic-" + kind;
                Instant slot = kind.equals("quote") ? SLOT : SHIPMENT_SLOT;
                String target = kind.equals("quote") ? q.quotePid() : fixture.orderPid();
                var binding = kind.equals("quote") ? q.binding() : fixture.shipmentBinding();
                var clock = Clock.fixed(slot, ZoneId.of("UTC"));
                var frame = new AutomaticBackground(q.tenantId(), q.supplierPid(), q.quotePid(), fixture.orderPid(),
                        target, kind, slot, context.getBean(BackgroundDataAccessor.class));
                var phase = new LinkedHashMap<String, Object>();
                phase.put("kind", kind); phase.put("slot", slot.toString());
                phase.put("backgroundCalls", frame.calls); phase.put("transactionThreads", frame.transactionThreads);
                phases.add(phase);
                automaticallyQueue(frame, clock, q.productJar());
                assertThat(frame.failure.get()).isNull();
                assertThat(frame.completedTransactions.get()).isGreaterThanOrEqualTo(2);
                assertThat(frame.attempts.get()).isGreaterThanOrEqualTo(2);
                // The annotation must execute on the isolated timer, not this JUnit thread.
                assertThat(frame.transactionThreads).isNotEmpty().allMatch(name -> name.startsWith("s06-owned-pf4j-"));
                List<JsonNode> matches = new ArrayList<>();
                for (String raw : rows(tables.get("qo_procurement_reminder_common"), q.tenantId())) {
                    JsonNode row = json.readTree(raw);
                    if (row.path("qo_pr_reminder_kind").asText().equals(kind)) matches.add(row);
                }
                assertThat(matches).hasSize(1);
                assertThat(rows(tables.get("qo_procurement_reminder_common"), q.tenantId())).hasSize(kind.equals("quote") ? 1 : 2);
                JsonNode reminder = matches.getFirst();
                assertThat(reminder.path("qo_pr_reminder_state").asText()).isEqualTo("pending");
                assertThat(reminder.path("qo_pr_reminder_supplier_pid").asText()).isEqualTo(q.supplierPid());
                assertThat(reminder.path("qo_pr_reminder_key").asText()).isEqualTo(AutomaticBackground.key(q.supplierPid(), kind, slot));
                JsonNode targets = reminder.path("qo_pr_reminder_targets");
                if (targets.isTextual()) targets = json.readTree(targets.textValue());
                assertThat(targets).isEqualTo(json.valueToTree(List.of(target)));
                String source = reminder.path("pid").asText();
                assertThat(source).isNotBlank();
                var settings = new HostSourceBindingProperties();
                settings.setEnabled(true);
                settings.setBindings(List.of(binding));
                var planner = new RegisteredSourcePlanResolver(settings, context.getBean(ImportedNotificationMetadataReader.class),
                        context.getBean(ImportedNotificationMetadataValidator.class), clock);
                planner.initialize();
                // The no-WeChat constructor cannot configure a provider; every actual enqueue is counted below.
                var host = new PluginNotificationDeliveryAccessorImpl(List.of(planner),
                        context.getBean(com.auraboot.framework.plugin.extension.integration.ReliableIntegrationAccessor.class),
                        context.getBean(com.auraboot.framework.notification.service.impl.StrictInAppDeliveryWriter.class));
                assertThat(ReflectionTestUtils.getField(host, "wechat")).isNull();
                ReflectionTestUtils.setField(host, "enabled", true);
                var audited = new com.auraboot.framework.plugin.extension.NotificationDeliveryAccessor() {
                    @Override public Result enqueue(Request request) {
                        if (request.channel() != Channel.IN_APP) {
                            outsideInApp.incrementAndGet();
                            throw new SecurityException("Only explicit synthetic IN_APP is admitted");
                        }
                        return host.enqueue(request);
                    }
                };
                var worker = new RegisteredSourceWorker(planner, (SourceBindingReader) context.getBean(ImportedNotificationMetadataReader.class),
                        audited, context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
                assertThat(worker.process(q.tenantId(), binding.code(), source)).isEqualTo(2);
                var outbox = rows("ab_outbox", q.tenantId());
                assertThat(outbox).hasSize(2);
                assertThat(worker.process(q.tenantId(), binding.code(), source)).isEqualTo(2);
                assertThat(rows("ab_outbox", q.tenantId())).isEqualTo(outbox);
                var events = events(q.tenantId()).stream().filter(event -> source.equals(event.payload().get("sourceId"))).toList();
                assertThat(events).hasSize(2);
                for (var event : events) {
                    assertThat(event.eventType()).isEqualTo(PluginNotificationDeliveryAccessorImpl.EVENT_TYPE);
                    assertThat(event.payload().get("sourceType")).isEqualTo(binding.code());
                    assertThat(event.payload().get("sourceId")).isEqualTo(source);
                    assertThat(event.payload().get("title")).isEqualTo(kind.equals("quote")
                            ? "Synthetic Product quotation reminder" : "Synthetic shipment reminder");
                    assertThat(event.payload().get("content")).isEqualTo(kind.equals("quote")
                            ? "Synthetic Product pending quotation facts" : "Synthetic open order pending shipment");
                }
                var executor = context.getBean(ReliableEventDeliveryExecutor.class);
                for (var event : events) executor.deliver(event, host, UniqueIdGenerator.generate());
                assertThat(jdbc.queryForList("SELECT user_id FROM ab_notification WHERE tenant_id=? AND source_type=? AND source_id=? ORDER BY user_id",
                        Long.class, q.tenantId(), binding.code(), source)).containsExactlyInAnyOrderElementsOf(q.recipientIds());
                var inbox = rows("ab_notification", q.tenantId());
                var deliveryReceipts = rows("ab_integration_receipt", q.tenantId());
                for (var event : events) executor.deliver(event, host, UniqueIdGenerator.generate());
                assertThat(rows("ab_notification", q.tenantId())).isEqualTo(inbox);
                assertThat(rows("ab_integration_receipt", q.tenantId())).isEqualTo(deliveryReceipts);
                phase.put("sourcePid", source);
                phase.put("binding", binding.code()); phase.put("targetPid", target);
                phase.put("automaticTransactions", frame.completedTransactions.get());
                phase.put("transactionThreads", frame.transactionThreads); phase.put("backgroundCalls", frame.calls);
                assertThat(outbox.stream().map(SourceBindingCanonical::parse).map(row -> row.path("event_id").asText()).collect(java.util.stream.Collectors.toSet()))
                        .isEqualTo(events.stream().map(IntegrationEventEnvelope::eventId).collect(java.util.stream.Collectors.toSet()));
                // The unchanged teardown snapshot uses ORDER BY id, not event hash or JSON lexical order.
                phase.put("ownedOutboxRows", actualOwnedOutboxRows(outbox));
                var after = new LinkedHashMap<>(automaticSnapshot(tables, q.tenantId(), true));
                var before = new LinkedHashMap<>(ownedBusinessBefore);
                after.remove("qo_procurement_reminder_common"); before.remove("qo_procurement_reminder_common");
                assertThat(after).isEqualTo(before);
                assertThat(automaticMetadata(q.tenantId(), true)).isEqualTo(metadataBefore);
                assertThat(automaticSnapshot(protectedTables, q.tenantId(), false)).isEqualTo(protectedBefore);
                // The unchanged teardown helper requires ALL of this new tenant's outbox to equal this exact two-event scope.
                // Seal real delivery/replay evidence first, then remove only this phase's pending intents before the next phase.
                phase.put("deliveryAndReplayVerified", true);
                phase.put("inboxBeforeTeardown", inbox);
                phase.put("integrationReceiptsBeforeTeardown", deliveryReceipts);
                phase.put("businessBeforeTeardown", automaticSnapshot(tables, q.tenantId(), true));
                Path phaseTrace = evidence.resolve("automatic-" + kind + "-phase-trace.json");
                Files.writeString(phaseTrace, json.writerWithDefaultPrettyPrinter().writeValueAsString(phase), StandardOpenOption.CREATE_NEW);
                @SuppressWarnings("unchecked") var phaseRows = (List<JsonNode>) phase.get("ownedOutboxRows");
                Set<String> references = q.recipientIds().stream().map(id -> SourceBindingCanonical.reference(binding.code(), source, id))
                        .collect(java.util.stream.Collectors.toSet());
                Path teardown = evidence.resolve("automatic-" + kind + "-intent-teardown.json");
                TestOwnedNotificationIntentTeardown.run(dataSource, new TestOwnedNotificationIntentTeardown.Scope(q.tenantId(), binding.code(), source, references),
                        phaseRows, phaseTrace, teardown);
                assertThat(rows("ab_outbox", q.tenantId())).isEmpty();
                assertThat(rows("ab_notification", q.tenantId())).isEqualTo(inbox);
                assertThat(rows("ab_integration_receipt", q.tenantId())).isEqualTo(deliveryReceipts);
                assertThat(automaticSnapshot(tables, q.tenantId(), true)).isEqualTo(phase.get("businessBeforeTeardown"));
                phase.put("phaseTrace", Map.of("path", phaseTrace.toString(), "sha256", hash(phaseTrace)));
                phase.put("intentTeardown", Map.of("path", teardown.toString(), "sha256", hash(teardown)));
                phase.put("outboxAfterIntentTeardown", List.of());
            }
            assertThat(count("ab_notification", fixture.quote().tenantId())).isEqualTo(4);
            assertThat(count("ab_integration_receipt", fixture.quote().tenantId())).isEqualTo(4);
            assertThat(outsideInApp.get()).isZero();
            receipt.put("nativeTickExecuted", true);
            receipt.put("automaticTimerVerified", true);
            receipt.put("actualPf4jRegistryUsed", true);
            receipt.put("formalRecipientPolicyApproved", false);
            receipt.put("automaticSourceWorkerVerified", false);
            receipt.put("externalProviderConfigured", false);
            receipt.put("nonInAppEnqueueRequests", outsideInApp.get());
            receipt.put("providerCalls", outsideInApp.get()); receipt.put("wechatCalls", outsideInApp.get());
            stage = "automatic-quote-shipment-completed";
            completed = true;
        } finally {
            MetaContext.clear();
            receipt.put("completed", false); receipt.put("businessCompleted", completed);
            receipt.put("stage", stage); receipt.put("phases", phases);
            receipt.put("protectedBefore", protectedBefore);
            if (fixture != null) {
                receipt.put("tenantId", Long.toString(fixture.quote().tenantId()));
                receipt.put("fixtureSetupPath", fixture.setupEvidence().toString()); receipt.put("fixtureSetupSha256", fixture.setupSha256());
                receipt.put("protectedAfter", automaticSnapshot(protectedTables, fixture.quote().tenantId(), false));
            }
            Path sealed = evidence.resolve("pf4j-automatic-reminder-trace.json");
            Files.writeString(sealed, json.writerWithDefaultPrettyPrinter().writeValueAsString(receipt), StandardOpenOption.CREATE_NEW);
            try {
            if (completed) {
                var q = fixture.quote();
                assertThat(count("ab_notification", q.tenantId())).isEqualTo(4);
                assertThat(count("ab_integration_receipt", q.tenantId())).isEqualTo(4);
                assertThat(count("ab_outbox", q.tenantId())).isZero();
                assertThat(automaticSnapshot(protectedTables, q.tenantId(), false)).isEqualTo(protectedBefore);
                assertThat(automaticMetadata(q.tenantId(), true)).isEqualTo(metadataBefore);
                var afterTeardown = new LinkedHashMap<>(automaticSnapshot(physicalTables(q.tenantId()), q.tenantId(), true));
                var beforeTeardown = new LinkedHashMap<>(ownedBusinessBefore);
                beforeTeardown.remove("qo_procurement_reminder_common");
                assertThat(afterTeardown).isEqualTo(beforeTeardown);
                receipt.put("completed", true);
                receipt.put("stage", "automatic-quote-shipment-and-intent-teardown-completed");
                receipt.put("sealedTrace", Map.of("path", sealed.toString(), "sha256", hash(sealed)));
                receipt.put("intentTeardowns", phases.stream().map(phase -> phase.get("intentTeardown")).toList());
            }
            } finally {
                Files.writeString(evidence.resolve("pf4j-automatic-reminder-receipt.json"),
                        json.writerWithDefaultPrettyPrinter().writeValueAsString(receipt), StandardOpenOption.CREATE_NEW);
            }
        }
    }

    static void registerAutomaticScheduling(org.springframework.context.annotation.AnnotationConfigApplicationContext child) {
        // Same real infrastructure processor created by Spring SchedulingConfiguration, registered only in this child.
        child.registerBean(org.springframework.scheduling.config.TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME,
                ScheduledAnnotationBeanPostProcessor.class,
                definition -> definition.setRole(org.springframework.beans.factory.config.BeanDefinition.ROLE_INFRASTRUCTURE));
    }

    static com.auraboot.framework.plugin.pf4j.AuraPluginManager registrationSpy(
            com.auraboot.framework.plugin.pf4j.AuraPluginManager manager) {
        // Mockito must observe the target, not Spring CGLIB proxy infrastructure.
        com.auraboot.framework.plugin.pf4j.AuraPluginManager target =
                org.springframework.test.util.AopTestUtils.getUltimateTargetObject(manager);
        return org.mockito.Mockito.spy(target);
    }

    void automaticallyQueue(AutomaticBackground frame, Clock fixedClock, Path admittedProductJar) throws Exception {
        var manager = context.getBean(com.auraboot.framework.plugin.pf4j.AuraPluginManager.class);
        String owner = "com.auraboot.quote-engine";
        // Observe the registry's real PF4J return values. The inventory-only discovery
        // proof API deliberately does not admit background component types.
        var discoveredInstances = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Object, Boolean>());
        var registrationManager = registrationSpy(manager);
        org.mockito.Mockito.doAnswer(invocation -> {
            Object returned = invocation.callRealMethod();
            assertThat(returned).isInstanceOf(List.class);
            ((List<?>) returned).forEach(discoveredInstances::add);
            return returned;
        }).when(registrationManager).getExtensionsOfType(
                com.auraboot.framework.plugin.extension.BackgroundComponentExtension.class, owner);
        var captured = new RegisteredAutomaticClock();
        BackgroundDataAccessor bounded = frame.proxy();
        BackgroundTenantAccessor tenantAccessor = () -> List.of(frame.tenant);
        var timer = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        timer.setPoolSize(1); timer.setThreadNamePrefix("s06-owned-pf4j-"); timer.setRemoveOnCancelPolicy(true);
        timer.setWaitForTasksToCompleteOnShutdown(true); timer.setAwaitTerminationSeconds(2);
        timer.setErrorHandler(error -> frame.failure.compareAndSet(null, error));
        java.util.concurrent.ScheduledThreadPoolExecutor executor = null;
        try (var child = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            child.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("synthetic-only", Map.of(
                    "quote.procurement.reminder.enabled", "true", "quote.procurement.reminder.poll-ms", "25")));
            registerAutomaticScheduling(child);
            child.registerBean("taskScheduler", org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class, () -> timer);
            child.registerBean(BackgroundDataAccessor.class, () -> bounded);
            child.registerBean(BackgroundTenantAccessor.class, () -> tenantAccessor);
            // PF4J factories can return a NEW object on each discovery. Capture the real registration object here,
            // before ScheduledAnnotationBeanPostProcessor.afterInitialization, rather than prefetching another object.
            child.registerBean("automaticClockAtRegistration", org.springframework.beans.factory.config.BeanPostProcessor.class,
                    () -> new org.springframework.beans.factory.config.BeanPostProcessor() {
                        @Override public Object postProcessBeforeInitialization(Object bean, String name) {
                            if (bean instanceof com.auraboot.framework.plugin.extension.BackgroundComponentExtension) {
                                assertThat(name).isEqualTo("procurementReminderScheduler");
                                assertThat(bean.getClass().getName()).isEqualTo("com.auraboot.quote.engine.procurement.ProcurementReminderScheduler");
                                assertThat(discoveredInstances.contains(bean)).as("exact object returned by real registry discovery").isTrue();
                                assertThat(manager.getPlugin(owner).getPluginState()).isEqualTo(org.pf4j.PluginState.STARTED);
                                assertThat(bean.getClass().getClassLoader()).isSameAs(manager.getPluginClassLoader(owner));
                                try {
                                    assertThat(Files.isSameFile(admittedProductJar, Path.of(bean.getClass().getProtectionDomain().getCodeSource().getLocation().toURI()))).isTrue();
                                } catch (java.io.IOException | java.net.URISyntaxException failure) {
                                    throw new IllegalStateException("Actual PF4J artifact path is unavailable", failure);
                                }
                                assertThat(ReflectionTestUtils.getField(bean, "background")).isSameAs(bounded);
                                assertThat(ReflectionTestUtils.getField(bean, "tenants")).isSameAs(tenantAccessor);
                                assertThat(ReflectionTestUtils.getField(bean, "enabled")).isEqualTo(Boolean.TRUE);
                                captured.install(bean, fixedClock);
                            }
                            return bean;
                        }
                    });
            child.refresh();
            executor = timer.getScheduledThreadPoolExecutor();
            var processor = child.getBean(ScheduledAnnotationBeanPostProcessor.class);
            assertThat(processor.getScheduledTasks()).isEmpty();
            var registry = new com.auraboot.framework.plugin.pf4j.BackgroundComponentRegistry(child, registrationManager);
            try {
                registry.register(owner);
                org.mockito.Mockito.verify(registrationManager).getExtensionsOfType(
                        com.auraboot.framework.plugin.extension.BackgroundComponentExtension.class, owner);
                assertThat(registry.registeredBeanNames(owner)).containsExactly("procurementReminderScheduler");
                assertThat(captured.bean.get()).isNotNull();
                assertThat(child.getBean("procurementReminderScheduler")).isSameAs(captured.bean.get());
                assertThat(processor.getScheduledTasks()).hasSize(1);
                assertThat(frame.committed.await(5, java.util.concurrent.TimeUnit.SECONDS)).as("two real automatic commits").isTrue();
            } finally {
                registry.unregister(owner);
                assertThat(processor.getScheduledTasks()).isEmpty();
                assertThat(child.getBeanFactory().containsSingleton("procurementReminderScheduler")).isFalse();
            }
        } finally {
            Object production = captured.bean.get();
            // This is the newly discovered child registration, not the root registry's separate object.
            if (production != null) ReflectionTestUtils.setField(production, "enabled", false);
            timer.shutdown();
            boolean terminated = executor == null || executor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS);
            if (terminated && production != null) ReflectionTestUtils.setField(production, "clock", captured.oldClock);
            MetaContext.clear();
            assertThat(terminated).as("no timer thread may survive its bounded context").isTrue();
        }
        assertThat(frame.failure.get()).isNull();
        assertThat(timer.getScheduledThreadPoolExecutor().isTerminated()).isTrue();
        assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
    }

    static List<JsonNode> actualOwnedOutboxRows(List<String> raw) {
        var rows = raw.stream().map(SourceBindingCanonical::parse).toList();
        assertThat(rows).allMatch(row -> row.path("id").isIntegralNumber() && row.path("id").canConvertToLong());
        assertThat(rows.stream().map(row -> row.path("id").longValue()).toList()).doesNotHaveDuplicates();
        return rows.stream().sorted(java.util.Comparator.comparingLong(row -> row.path("id").longValue())).toList();
    }

    static final class RegisteredAutomaticClock {
        final java.util.concurrent.atomic.AtomicReference<Object> bean = new java.util.concurrent.atomic.AtomicReference<>();
        Clock oldClock;
        void install(Object registered, Clock fixed) {
            assertThat(bean.compareAndSet(null, registered)).as("one exact registration object only").isTrue();
            assertThat(ReflectionTestUtils.getField(registered, "clock")).isInstanceOf(Clock.class);
            oldClock = (Clock) ReflectionTestUtils.getField(registered, "clock");
            ReflectionTestUtils.setField(registered, "clock", fixed);
            assertThat(ReflectionTestUtils.getField(registered, "clock")).isSameAs(fixed);
        }
    }

    static final class AutomaticBackground implements java.lang.reflect.InvocationHandler {
        final long tenant;
        final String supplier, quote, order, target, kind;
        final Instant slot;
        final BackgroundDataAccessor actual;
        final java.util.concurrent.CountDownLatch committed = new java.util.concurrent.CountDownLatch(2);
        final java.util.concurrent.atomic.AtomicInteger completedTransactions = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        final List<String> transactionThreads = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<Map<String, Object>> calls = new java.util.concurrent.CopyOnWriteArrayList<>();
        final ThreadLocal<Boolean> transaction = ThreadLocal.withInitial(() -> false);
        AutomaticBackground(long tenant, String supplier, String quote, String order, String target, String kind, Instant slot, BackgroundDataAccessor actual) {
            if (tenant <= 0 || List.of(supplier, quote, order, target).stream().anyMatch(String::isBlank)
                    || !(kind.equals("quote") && target.equals(quote) && slot.equals(SLOT)
                    || kind.equals("shipment") && target.equals(order) && slot.equals(SHIPMENT_SLOT)))
                throw new IllegalArgumentException("Exact synthetic automatic phase required");
            this.tenant = tenant; this.supplier = supplier; this.quote = quote; this.order = order; this.target = target;
            this.kind = kind; this.slot = slot; this.actual = java.util.Objects.requireNonNull(actual);
        }
        BackgroundDataAccessor proxy() {
            return (BackgroundDataAccessor) Proxy.newProxyInstance(BackgroundDataAccessor.class.getClassLoader(), new Class<?>[]{BackgroundDataAccessor.class}, this);
        }
        @Override public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            Object[] args = arguments == null ? new Object[0] : arguments;
            try {
                validate(method.getName(), args);
                calls.add(Map.of("operation", method.getName(), "tenantId", Long.toString(tenant), "thread", Thread.currentThread().getName()));
                if (method.getName().equals("executeInTransaction")) {
                    if (MetaContext.exists()) throw new SecurityException("Automatic scheduler thread inherited a tenant");
                    Runnable work = (Runnable) args[0];
                    args = new Object[]{(Runnable) () -> {
                        transaction.set(true);
                        try { work.run(); } finally { transaction.remove(); }
                    }};
                    Object result = method.invoke(actual, args);
                    if (MetaContext.exists()) throw new SecurityException("Automatic host transaction leaked a tenant");
                    transactionThreads.add(Thread.currentThread().getName());
                    completedTransactions.incrementAndGet(); committed.countDown();
                    return result;
                }
                if (method.getName().equals("tryCreate")) attempts.incrementAndGet();
                return method.invoke(actual, args);
            } catch (InvocationTargetException error) {
                failure.compareAndSet(null, error.getCause()); throw error.getCause();
            } catch (Throwable error) { failure.compareAndSet(null, error); throw error; }
        }
        static String key(String supplier, String kind, Instant slot) {
            var local = slot.atZone(ZoneId.of("Asia/Shanghai"));
            StringBuilder result = new StringBuilder();
            for (String part : List.of(supplier, kind, local.toLocalDate().toString(), local.toLocalTime().toString()))
                result.append(part.length()).append(':').append(part);
            return result.toString();
        }
        Map<String, Object> exactReminder() {
            return Map.of("qo_pr_reminder_key", key(supplier, kind, slot), "qo_pr_reminder_supplier_pid", supplier,
                    "qo_pr_reminder_kind", kind, "qo_pr_reminder_slot_at", slot.toString(), "qo_pr_reminder_state", "pending",
                    "qo_pr_reminder_revision", 0, "qo_pr_reminder_targets", List.of(target));
        }
        void validate(String op, Object[] args) {
            if (op.equals("executeInTransaction") && args.length == 1 && args[0] instanceof Runnable && !transaction.get()) return;
            if (args.length < 2 || !(args[0] instanceof Long id) || id != tenant) throw new SecurityException("Automatic tenant boundary rejected");
            if (op.equals("queryPage") && args.length == 5 && args[1].equals("pr_supplier_common")
                    && Map.of("pr_supplier_status", "enabled").equals(args[2]) && (args[3] == null || args[3] instanceof String)
                    && Integer.valueOf(100).equals(args[4])) return;
            if (op.equals("queryPage") && args.length == 3 && transaction.get() && args[2] instanceof DataPageQuery page) {
                Map<String, Object> expected = null;
                if (args[1].equals("s2p_bid") && kind.equals("quote")) expected = Map.of("s2p_bid_supplier_id", supplier, "s2p_bid_collaboration_managed", true);
                if (args[1].equals("pr_purchase_order") && kind.equals("shipment")) expected = Map.of("pr_po_supplier", supplier, "pr_po_collaboration_managed", true);
                if (args[1].equals("pr_purchase_order_line") && kind.equals("shipment")) expected = Map.of("pr_pol_order_id", order, "pr_pol_collaboration_managed", true);
                if (expected != null && page.exactFilters().equals(expected) && page.anyOfFilters().isEmpty() && page.keyword().isEmpty()
                        && page.page() >= 1 && page.size() == DataPageQuery.MAX_SIZE && page.sorts().equals(List.of(new DataPageQuery.Sort("pid", false)))) return;
            }
            if (op.equals("tryCreate") && args.length == 3 && transaction.get() && args[1].equals("qo_procurement_reminder_common")
                    && exactReminder().equals(args[2])) return;
            throw new SecurityException("Automatic operation boundary rejected");
        }
    }
    private Map<String, Object> automaticSnapshot(Map<String, String> tables, long tenant, boolean onlyTenant) {
        return automaticReadonly(() -> {
            Map<String, Object> result = new LinkedHashMap<>();
            tables.forEach((model, table) -> result.put(model, automaticSummary(table, tenant, onlyTenant)));
            if (!onlyTenant) result.put("metadataAndDelivery", automaticMetadata(tenant, false));
            return result;
        });
    }

    private Map<String, Object> automaticMetadata(long tenant, boolean onlyTenant) {
        return automaticReadonly(() -> {
            Map<String, Object> result = new LinkedHashMap<>();
            for (String table : List.of("ab_meta_model", "ab_meta_field", "ab_meta_model_field_binding", "ab_plugin", "ab_plugin_resource",
                    "ab_command_definition", "ab_notification_rule", "ab_notification_template")) {
                result.put(table, automaticSummary(table, tenant, onlyTenant));
            }
            if (!onlyTenant) {
                for (String table : List.of("ab_notification", "ab_integration_receipt", "ab_outbox"))
                    result.put(table, automaticSummary(table, tenant, false));
            }
            return result;
        });
    }

    private Map<String, Object> automaticReadonly(java.util.function.Supplier<Map<String, Object>> action) {
        var transaction = new org.springframework.transaction.support.TransactionTemplate(
                context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        // PG JDBC uses a server cursor only inside an active transaction with positive fetchSize.
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        Map<String, Object> result = transaction.execute(status -> action.get());
        if (result == null) throw new IllegalStateException("AUTOMATIC_SNAPSHOT_MISSING");
        return result;
    }

    private Map<String, Object> automaticSummary(String table, long tenant, boolean onlyTenant) {
        identifier(table);
        String sql = "SELECT row_to_json(t)::text FROM \"" + table + "\" t WHERE tenant_id "
                + (onlyTenant ? "=" : "IS DISTINCT FROM") + " ? ORDER BY row_to_json(t)::text";
        AutomaticDigest result = jdbc.query(connection -> {
            if (connection.getAutoCommit() || !connection.isReadOnly()) throw new java.sql.SQLException("AUTOMATIC_SNAPSHOT_CURSOR_REQUIRES_READONLY_TRANSACTION");
            var statement = connection.prepareStatement(sql);
            statement.setLong(1, tenant);
            statement.setFetchSize(16);
            return statement;
        }, (org.springframework.jdbc.core.ResultSetExtractor<AutomaticDigest>) rows -> {
            try (var digest = new AutomaticRowDigest()) {
                while (rows.next()) {
                    try (java.io.Reader row = rows.getCharacterStream(1)) { digest.append(row); }
                }
                return digest.finish();
            } catch (java.io.IOException invalid) {
                throw new IllegalStateException("AUTOMATIC_SNAPSHOT_STREAM_FAILED", invalid);
            }
        });
        if (result == null) throw new IllegalStateException("AUTOMATIC_SNAPSHOT_MISSING");
        return Map.of("count", result.count(), "sha256", result.sha256());
    }

    record AutomaticDigest(long count, String sha256) {}

    /** Same canonical array-of-strings bytes, without a List, JSON tree or whole-array String. */
    static final class AutomaticRowDigest implements AutoCloseable {
        private final java.security.MessageDigest digest;
        private final com.fasterxml.jackson.core.JsonGenerator generator;
        private long count;
        private boolean finished;
        private boolean failed;
        AutomaticRowDigest() throws java.io.IOException {
            try { digest = java.security.MessageDigest.getInstance("SHA-256"); }
            catch (java.security.NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
            // Writer encoding matches SourceBindingCanonical.json(...).getBytes(UTF_8), including supplementary Unicode.
            var output = new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), digest);
            generator = new com.fasterxml.jackson.core.JsonFactory().createGenerator(
                    new java.io.OutputStreamWriter(output, java.nio.charset.StandardCharsets.UTF_8));
            generator.writeStartArray();
        }
        void append(java.io.Reader row) throws java.io.IOException {
            if (finished || failed || row == null) throw new IllegalStateException("AUTOMATIC_SNAPSHOT_STREAM_INVALID");
            try {
                generator.writeString(row, -1);
                count = Math.incrementExact(count);
            } catch (java.io.IOException | RuntimeException invalid) {
                failed = true; throw invalid;
            }
        }
        AutomaticDigest finish() throws java.io.IOException {
            if (finished) throw new IllegalStateException("AUTOMATIC_SNAPSHOT_STREAM_ALREADY_FINISHED");
            if (failed) throw new IllegalStateException("AUTOMATIC_SNAPSHOT_STREAM_INVALID");
            generator.writeEndArray(); generator.close(); finished = true;
            return new AutomaticDigest(count, java.util.HexFormat.of().formatHex(digest.digest()));
        }
        @Override public void close() throws java.io.IOException { generator.close(); }
    }

    // END additive PF4J automatic Native.
}
