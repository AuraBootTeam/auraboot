package com.auraboot.framework.notification.sourcebinding;

import com.auraboot.framework.plugin.extension.BackgroundDataAccessor;
import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.HostSerializableEventConsumer;
import com.auraboot.framework.integration.ReliableEventDeliveryExecutor;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.ModelFieldBindingService;
import com.auraboot.framework.notification.dto.NotificationRuleRequest;
import com.auraboot.framework.notification.dto.NotificationTemplateCreateRequest;
import com.auraboot.framework.notification.service.NotificationRuleService;
import com.auraboot.framework.notification.service.NotificationTemplateService;
import com.auraboot.framework.notification.service.impl.PluginNotificationDeliveryAccessorImpl;
import com.auraboot.framework.notification.service.impl.StrictInAppDeliveryWriter;
import com.auraboot.framework.notification.wechat.WechatSubscriptionProperties;
import com.auraboot.framework.common.crypto.FieldEncryptionService;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.extension.DataPage;
import com.auraboot.framework.plugin.extension.NotificationDeliveryAccessor;
import com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelope;
import com.auraboot.framework.plugin.extension.integration.ReliableEventConsumerExtension;
import com.auraboot.framework.plugin.extension.integration.ReliableIntegrationAccessor;
import com.auraboot.framework.plugin.service.PluginImportService;
import com.auraboot.framework.scheduler.service.SchedulerEngine;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.tenant.service.TenantBootstrapService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import com.auraboot.framework.meta.constant.SystemFieldConstants;
import com.auraboot.framework.notification.sourcebinding.SourceBindingTypes.Approval;
import com.auraboot.framework.notification.sourcebinding.SourceBindingTypes.Binding;
import com.auraboot.framework.notification.sourcebinding.SourceBindingTypes.Deadline;
import com.auraboot.framework.notification.sourcebinding.SourceBindingTypes.DeadlineRelation;
import com.auraboot.framework.notification.sourcebinding.SourceBindingTypes.Entity;
import com.auraboot.framework.notification.sourcebinding.SourceBindingTypes.Exact;
import com.auraboot.framework.notification.sourcebinding.SourceBindingTypes.Owner;
import com.auraboot.framework.notification.sourcebinding.SourceBindingTypes.Principal;
import com.auraboot.framework.notification.sourcebinding.SourceBindingTypes.RemainingChild;
import com.auraboot.framework.notification.sourcebinding.SourceBindingTypes.Source;
import com.auraboot.framework.notification.sourcebinding.SourceBindingTypes.Target;
import com.auraboot.framework.notification.sourcebinding.SourceBindingTypes.Verification;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real host source authority over officially imported, synthetic DSL data. No human policy or external delivery. */
@SpringBootTest(classes = TestApplication.class, properties = {
        "spring.datasource.url=${TEST_DATABASE_URL}", "spring.data.redis.database=${TEST_REDIS_DATABASE}",
        "s06.native.source-binding.fixture=true",
        "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.slf4j.Slf4jImpl",
        "logging.level.com.auraboot.framework=INFO",
        "logging.level.org.mybatis=INFO",
        "logging.level.org.springframework.security=WARN",
        "logging.level.org.springframework.cache=WARN",
        "s06.native.product-quote-facet.enabled=${S06_NATIVE_PRODUCT_QUOTE_FACET:false}"})
@ActiveProfiles("integration-test")
@Import(RegisteredSourceBindingPostgresIT.PollerIsolation.class)
@EnabledIfEnvironmentVariable(named = "S06_NATIVE_SOURCE_BINDING", matches = "true")
class RegisteredSourceBindingPostgresIT {
    @DynamicPropertySource
    static void productPluginDirectory(DynamicPropertyRegistry properties) {
        String matchingKey = S06NativeEncryptionAdmission.requiredKey(System.getenv());
        properties.add("security.field-encryption.key", () -> matchingKey);
        boolean selected = "true".equals(System.getenv("S06_NATIVE_PRODUCT_QUOTE_FACET"));
        String file = System.getenv("S06_NATIVE_PRODUCT_INPUT");
        String directory = System.getenv("S06_NATIVE_PRODUCT_PLUGIN_DIR");
        ProductQuoteNativePluginDirectory.select(selected, file == null ? null : Path.of(file),
                System.getenv("S06_NATIVE_PRODUCT_INPUT_SHA256"), directory == null ? null : Path.of(directory),
                NativeProcurementWorkspace.root().resolve(".workspace/runtimes/procurement-web-stack-20261010"))
                .ifPresent(path -> properties.add("aura.plugins.dir", path::toString));
    }

    @TestConfiguration(proxyBeanMethods = false)
    @ConditionalOnProperty(
            name = "s06.native.source-binding.fixture", havingValue = "true")
    static class PollerIsolation {
        @Bean
        static BeanFactoryPostProcessor preventRegisteredSourceFixtureAutomaticPollers() {
            return factory -> {
                if (!(factory instanceof BeanDefinitionRegistry registry)) {
                    throw new IllegalStateException("Native source fixture cannot isolate scheduling");
                }
                String name = TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME;
                if (registry.containsBeanDefinition(name)) {
                    registry.removeBeanDefinition(name);
                }
            };
        }
    }

    @Autowired ApplicationContext context;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager manager;
    @Autowired TenantService tenants;
    @Autowired TenantBootstrapService tenantBootstrap;
    @Autowired ExtensionRegistry extensions;
    @Value("${s06.native.product-quote-facet.enabled:false}") private boolean productQuoteFacetEnabled;
    @Autowired UserService users;
    @Autowired TenantMemberService members;
    @Autowired PluginImportService imports;
    @Autowired DynamicDataService data;
    @Autowired BackgroundDataAccessor background;
    @Autowired MetaModelService metadata;
    @Autowired com.auraboot.framework.meta.service.CommandExecutor commands;
    @Autowired com.auraboot.framework.auth.service.AuthService authentication;
    @Autowired ModelFieldBindingService bindings;
    @Autowired NotificationRuleService rules;
    @Autowired NotificationTemplateService templates;
    @Autowired ImportedNotificationMetadataReader reader;
    @Autowired ImportedNotificationMetadataValidator validator;
    @Autowired ReliableIntegrationAccessor reliable;
    @Autowired ReliableEventDeliveryExecutor executor;
    @Autowired StrictInAppDeliveryWriter writer;
    @Autowired PluginNotificationDeliveryAccessorImpl defaultHost;
    @Autowired WechatSubscriptionProperties wechat;
    @Autowired FieldEncryptionService encryption;
    // Scheduling entry point only. No domain service, mapper, authority reader, writer or transport is mocked.
    @MockitoBean SchedulerEngine scheduler;

    private final String namespace = "sn" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private final List<String> kinds = List.of("source", "target", "owner", "principal", "verification", "child");
    private final Map<String, String> modelPids = new LinkedHashMap<>();
    private final Map<String, String> tables = new LinkedHashMap<>();
    private final Map<String, Object> trace = new LinkedHashMap<>();
    private final List<String> checkpoints = new ArrayList<>();
    private static final Instant BEFORE_DEADLINE = Instant.parse("2026-10-13T03:00:00Z");
    private static final Instant TARGET_DEADLINE = Instant.parse("2026-10-13T04:00:00Z");
    private final NativeDeadlineClock clock = new NativeDeadlineClock();
    private boolean softTargetFixture;
    private boolean referenceRelationFixture;
    private Tenant tenant, foreignTenant;
    private User first, second, foreign;
    private TenantMember secondMember;
    private String ownOwner, otherOwner, verification1, verification2, otherVerification;
    private String link1, link2, target, foreignTarget, sourcePid, templatePid, remainingChild, fullChild;
    private long ruleId;
    private Binding approved;
    private RegisteredSourcePlanResolver resolver;
    private PluginNotificationDeliveryAccessorImpl host;
    private RegisteredSourceWorker worker;
    private Path evidence;
    private String stage = "fixture-not-started";
    private boolean businessStarted;
    private Map<String, Object> foreignBaseline;

    @BeforeEach
    void fixture(TestInfo info) throws Exception {
        stage = "fixture-admission";
        assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
        assertThat(System.getenv("TEST_REDIS_DATABASE")).isEqualTo("13");
        assertThat(System.getenv("S06_NATIVE_SOURCE_EVIDENCE")).isNotBlank();
        evidence = Path.of(System.getenv("S06_NATIVE_SOURCE_EVIDENCE")).toRealPath();
        var defaultProperties = context.getBean(HostSourceBindingProperties.class);
        var defaultAdapter = context.getBean(RegisteredSourcePollingAdapter.class);
        assertThat(defaultProperties.getWorker().isEnabled()).isFalse();
        assertThat(ReflectionTestUtils.getField(defaultAdapter, "scheduler")).isNull();
        assertThat(ReflectionTestUtils.getField(defaultAdapter, "settings")).isNull();
        trace.put("matchingEncryptionAdmission", S06NativeEncryptionAdmission.admit(
                System.getenv(), json, encryption, getClass(), context.getEnvironment().getActiveProfiles()));
        trace.put("defaultPollingResourceAdmission", Map.of("workerEnabled", false,
                "schedulerCreated", false, "settingsAdmitted", false));
        referenceRelationFixture = info.getTestMethod().orElseThrow().getName()
                .equals("actualImportedMetadataExplicitRecipientsReplayAndConsumePersistExactlyOnce");
        softTargetFixture = info.getTestMethod().orElseThrow().getName()
                .equals("actualBindingMutationCannotBeAuthorizedByCachedMetadataOrOldApproval");
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(connection.getCatalog()).isEqualTo("enterprise_22");
        }
        assertThat(wechat.isEnabled()).isFalse();
        trace.put("test", info.getTestMethod().orElseThrow().getName());
        trace.put("namespace", namespace);
        trace.put("authority", "test-owned host deployment descriptor; never Product/package self-approval");
        trace.put("referenceRelationFixture", referenceRelationFixture);
        trace.put("externalProviderCalls", 0);
        stage = "fixture-native-identity-apis";
        MetaContext.clear();
        tenant = tenant("own");
        foreignTenant = tenant("foreign");
        first = user("first");
        second = user("second");
        foreign = user("foreign");
        members.addMember(first.getId(), tenant.getId(), "active");
        secondMember = members.addMember(second.getId(), tenant.getId(), "active");
        members.addMember(foreign.getId(), foreignTenant.getId(), "active");
        bind(tenant, first);
        assertThat(defaultHost.enqueue(new NotificationDeliveryAccessor.Request(tenant.getId(), "unknown", NotificationDeliveryAccessor.Channel.IN_APP)).status())
                .isEqualTo(NotificationDeliveryAccessor.Status.BLOCKED);
        stage = "fixture-formal-plugin-import";
        importFixture(tenant);
        importFixture(foreignTenant);
        bind(tenant, first);
        for (String kind : kinds) {
            Map<String, Object> model = jdbc.queryForMap("SELECT pid,table_name,status FROM ab_meta_model WHERE tenant_id=? AND code=? AND is_current=TRUE AND deleted_flag=FALSE", tenant.getId(), model(kind));
            modelPids.put(kind, (String) model.get("pid"));
            String table = (String) model.get("table_name");
            assertThat(model.get("status")).isEqualTo("published");
            assertThat(table).isEqualTo(SystemFieldConstants.generateTableName(model(kind)));
            assertThat(jdbc.queryForObject("SELECT to_regclass(?)::text", String.class, table)).isEqualTo(table);
            tables.put(kind, table);
        }
        if (referenceRelationFixture) {
            var relationFacts = jdbc.queryForList("""
                    SELECT code,data_type,ref_target FROM ab_meta_field
                    WHERE tenant_id=? AND code IN (?,?,?) AND is_current=TRUE AND deleted_flag=FALSE
                    ORDER BY code
                    """, tenant.getId(), field("owner"), field("verification"), field("parent"));
            assertThat(relationFacts).hasSize(3);
            assertThat(relationFacts).allSatisfy(row -> assertThat(row.get("data_type")).isEqualTo("reference"));
            trace.put("actualImportedReferenceMetadata", relationFacts);
        }
        stage = "fixture-native-data-apis";
        ownOwner = create("owner", Map.of(field("state"), "active"));
        otherOwner = create("owner", Map.of(field("state"), "active"));
        verification1 = create("verification", Map.of(field("state"), "active", field("owner"), ownOwner));
        verification2 = create("verification", Map.of(field("state"), "active", field("owner"), ownOwner));
        otherVerification = create("verification", Map.of(field("state"), "active", field("owner"), otherOwner));
        link1 = principal(first, ownOwner, verification1);
        link2 = principal(second, ownOwner, verification2);
        target = create("target", Map.of(field("state"), "ready", field("owner"), ownOwner, field("cutoff"), TARGET_DEADLINE));
        bind(foreignTenant, foreign);
        String foreignOwner = create("owner", Map.of(field("state"), "active"));
        foreignTarget = create("target", Map.of(field("state"), "ready", field("owner"), foreignOwner, field("cutoff"), TARGET_DEADLINE));
        bind(tenant, first);
        remainingChild = create("child", Map.of(field("state"), "active", field("parent"), target,
                field("ordered"), new BigDecimal("100"), field("submitted"), BigDecimal.ZERO));
        fullChild = create("child", Map.of(field("state"), "active", field("parent"), target,
                field("ordered"), new BigDecimal("10"), field("submitted"), new BigDecimal("10")));
        sourcePid = create("source", Map.of(field("state"), "pending", field("owner"), ownOwner,
                field("occurred"), Instant.parse("2026-10-13T03:00:00.123456Z"), field("targets"), List.of(target)));
        NotificationTemplateCreateRequest template = templateRequest("Synthetic registered source body");
        templatePid = templates.create(template).getPid();
        ruleId = rules.createRule(ruleRequest(first.getId() + "," + second.getId())).getId();
        stage = "fixture-test-only-host-approval";
        // Admission diagnostics only: expose actual linked metadata ownership, not any authority fallback.
        trace.put("metadataOwnershipAdmission", jdbc.queryForList("""
                SELECT m.code AS model_code,m.tenant_id AS model_tenant,m.plugin_pid AS model_plugin,
                       b.pid AS binding_pid,b.tenant_id AS binding_tenant,b.is_system_binding,
                       f.pid AS field_pid,f.code AS field_code,f.tenant_id AS field_tenant,
                       f.plugin_pid AS field_plugin,f.data_type,f.is_current,f.deleted_flag
                FROM ab_meta_model m JOIN ab_meta_model_field_binding b ON b.model_id=m.id
                JOIN ab_meta_field f ON f.id=b.field_id
                WHERE m.tenant_id=? AND m.code IN (?,?,?,?,?,?) AND m.is_current=TRUE AND m.deleted_flag=FALSE
                ORDER BY m.code,b.field_order,b.id
                """, tenant.getId(), model("source"), model("target"), model("owner"), model("principal"), model("verification"), model("child")));
        approved = approveActualFixtureMetadata();
        connect(approved, reader);
        serializable(() -> {
            validator.validate(approved, reader);
            Map<String, Object> projectedTarget = reader.record(approved, model("target"), target);
            Object projectedCutoff = projectedTarget.get(field("cutoff"));
            trace.put("actualDeadlineProjection", Map.of("field", field("cutoff"),
                    "declaredType", "datetime", "javaType", projectedCutoff == null ? "null" : projectedCutoff.getClass().getName(),
                    "value", String.valueOf(projectedCutoff), "controlledHostNow", clock.instant().toString()));
            Map<String, Object> projectedChild = reader.record(approved, model("child"), remainingChild);
            Object ordered = projectedChild.get(field("ordered"));
            Object submitted = projectedChild.get(field("submitted"));
            trace.put("actualChildQuantityProjection", Map.of("orderedJavaType", ordered.getClass().getName(),
                    "submittedJavaType", submitted.getClass().getName(), "ordered", ordered.toString(),
                    "submitted", submitted.toString(), "declaredType", "decimal", "approvedChildModel", model("child")));
            assertThat(new BigDecimal(ordered.toString())).isEqualByComparingTo("100");
            assertThat(new BigDecimal(submitted.toString())).isEqualByComparingTo("0");
            assertThat(resolver.plan(approved, sourcePid)).hasSize(2);
            return null;
        });
        assertThat(counts()).isEqualTo(Map.of("outbox", 0, "receipt", 0, "inbox", 0));
        foreignBaseline = foreignSnapshot();
        trace.put("identities", Map.of("tenantId", tenant.getId(), "foreignTenantId", foreignTenant.getId(),
                "users", List.of(first.getPid(), second.getPid(), foreign.getPid()), "sourcePid", sourcePid));
        trace.put("approvedBinding", approved);
        trace.put("initialBusinessSnapshotSha256", SourceBindingCanonical.hash(businessSnapshot()));
        stage = "fixture-ready-business-did-not-run";
    }

    @AfterEach
    void evidence() throws Exception {
        if (evidence != null) {
            trace.put("stage", stage);
            trace.put("businessStarted", businessStarted);
            trace.put("checkpoints", checkpoints);
            trace.put("retention", "API-created model/business/identity facts and inbox/receipt retained; only explicitly sealed owned pending/failed notification intents torn down after trace");
            if (tenant != null && sourcePid != null) {
                bind(tenant, first);
                trace.put("finalCounts", counts());
                trace.put("finalBusinessSnapshotSha256", SourceBindingCanonical.hash(businessSnapshot()));
                if (foreignBaseline != null) {
                    trace.put("foreignBaselineUnchanged", foreignBaseline.equals(foreignSnapshot()));
                    assertThat(foreignSnapshot()).isEqualTo(foreignBaseline);
                }
            }
            List<JsonNode> ownedRows = tenant == null ? List.of() : jdbc.queryForList(
                    "SELECT to_jsonb(r)::text FROM ab_outbox r WHERE tenant_id=? ORDER BY id", String.class, tenant.getId())
                    .stream().map(SourceBindingCanonical::parse).toList();
            trace.put("finalOwnedOutboxRowsBeforeTeardown", ownedRows);
            Path sealedTrace = evidence.resolve(namespace + ".json");
            Files.writeString(sealedTrace, json.writerWithDefaultPrettyPrinter().writeValueAsString(trace), StandardOpenOption.CREATE_NEW);
            if (tenant != null && sourcePid != null && approved != null) {
                Map<String, Object> beforeBusiness = businessSnapshot();
                TestOwnedNotificationIntentTeardown.run(dataSource,
                        new TestOwnedNotificationIntentTeardown.Scope(tenant.getId(), approved.code(), sourcePid,
                                Set.of(SourceBindingCanonical.reference(approved.code(), sourcePid, first.getId()),
                                        SourceBindingCanonical.reference(approved.code(), sourcePid, second.getId()))),
                        ownedRows, sealedTrace, evidence.resolve(namespace + "-teardown.json"));
                assertThat(businessSnapshot()).isEqualTo(beforeBusiness);
                if (foreignBaseline != null) assertThat(foreignSnapshot()).isEqualTo(foreignBaseline);
            }
        }
        MetaContext.clear();
    }

    private Tenant tenant(String suffix) {
        Tenant result = new Tenant();
        result.setPid(UniqueIdGenerator.generate());
        result.setName(namespace + "-" + suffix);
        result.setStatus("active");
        return tenants.createTenant(result);
    }

    private User user(String suffix) {
        String name = namespace + "_" + suffix;
        // One transient synthetic password in memory, never written to trace/log/env by this fixture.
        return users.signUp(name + "@example.invalid", "Native-" + UUID.randomUUID(), "Native S06 " + suffix, name);
    }

    private String model(String kind) { return namespace + "_" + kind; }
    private String field(String name) { return namespace + "_" + name; }
    private void bind(Tenant selected, User actor) { MetaContext.setContext(selected.getId(), actor.getId(), actor.getPid(), "native-source-fixture"); }

    private void importFixture(Tenant selected) throws Exception {
        bind(selected, selected == foreignTenant ? foreign : first);
        Map<String, String> types = Map.ofEntries(Map.entry("owner", "string"), Map.entry("state", "string"),
                Map.entry("principal", "string"), Map.entry("verification", "string"), Map.entry("occurred", "datetime"),
                Map.entry("targets", "json"), Map.entry("cutoff", "datetime"), Map.entry("parent", "string"),
                Map.entry("ordered", "decimal"), Map.entry("submitted", "decimal"));
        Map<String, List<String>> bound = Map.of("source", List.of("owner", "state", "occurred", "targets"),
                "target", List.of("owner", "state", "cutoff"), "owner", List.of("state"),
                "principal", List.of("owner", "state", "principal", "verification"), "verification", List.of("owner", "state"),
                "child", List.of("state", "parent", "ordered", "submitted"));
        List<Map<String, Object>> fields = new ArrayList<>(), models = new ArrayList<>(), bindings = new ArrayList<>();
        types.forEach((name, type) -> {
            Map<String, Object> definition = new LinkedHashMap<>(Map.of("code", field(name),
                    "displayName", name, "dataType", type));
            if (referenceRelationFixture && Set.of("owner", "verification", "parent").contains(name)) {
                String targetKind = name.equals("parent") ? "target" : name;
                definition.put("dataType", "reference");
                definition.put("refTarget", Map.of("targetModel", model(targetKind), "valueField", "pid"));
            }
            fields.add(definition);
        });
        for (String kind : kinds) {
            Map<String, Object> declaredModel = new LinkedHashMap<>(Map.of("code", model(kind),
                    "displayName", kind, "modelType", "entity", "tableName", SystemFieldConstants.generateTableName(model(kind))));
            if (softTargetFixture && kind.equals("target")) declaredModel.put("extension", Map.of("softDelete", true));
            models.add(declaredModel);
            for (String name : bound.get(kind)) {
                bindings.add(Map.of("modelCode", model(kind), "fieldCode", field(name), "sequence", bindings.size() + 1));
            }
        }
        String manifest = json.writeValueAsString(Map.of("pluginId", "com.test.s06." + namespace, "namespace", namespace,
                "version", "1.0.0", "displayName", "Synthetic host source authority", "pluginType", "config",
                "fields", fields, "models", models, "modelFieldBindings", bindings));
        var preview = imports.parseJson(manifest, namespace + ".json");
        assertThat(preview.isValid()).as("actual import preview %s", preview.getErrors()).isTrue();
        var result = imports.execute(preview.getImportId(), new ImportRequest());
        assertThat(result.isSuccess()).as("actual import %s", result.getErrorMessage()).isTrue();
    }

    private String create(String kind, Map<String, Object> values) {
        return MetaContext.runWithCommandPermitScope("ALL", () -> (String) data.create(model(kind), new LinkedHashMap<>(values)).get("pid"));
    }

    private void update(String kind, String pid, Map<String, Object> values) {
        MetaContext.runWithCommandPermitScope("ALL", () -> data.update(model(kind), pid, new LinkedHashMap<>(values)));
    }

    private String principal(User user, String owner, String verification) {
        return create("principal", Map.of(field("state"), "active", field("owner"), owner,
                field("principal"), user.getPid(), field("verification"), verification));
    }

    private Entity entity(String kind, String state) {
        return new Entity(model(kind), List.of(new Exact(field("state"), state)));
    }

    private Binding approveActualFixtureMetadata() {
        return serializable(() -> {
            Map<String, Object> plugin = jdbc.queryForMap("SELECT pid,plugin_id,namespace,version FROM ab_plugin WHERE tenant_id=? AND plugin_id=? AND deleted_flag=FALSE", tenant.getId(), "com.test.s06." + namespace);
            Owner owner = new Owner((String) plugin.get("pid"), (String) plugin.get("plugin_id"), (String) plugin.get("namespace"), (String) plugin.get("version"));
            List<Approval> approvals = new ArrayList<>();
            for (String kind : kinds) {
                // Explicit native fields are ONLY this fixture's actual tenant-scoped system bindings with null owner.
                List<String> nativeFields = jdbc.queryForList("SELECT f.pid FROM ab_meta_field f JOIN ab_meta_model_field_binding b ON b.field_id=f.id JOIN ab_meta_model m ON m.id=b.model_id WHERE m.tenant_id=? AND m.code=? AND m.is_current=TRUE AND m.deleted_flag=FALSE AND b.tenant_id=? AND b.deleted_flag=FALSE AND b.is_system_binding=TRUE AND f.tenant_id=? AND f.deleted_flag=FALSE AND f.is_current=TRUE AND f.plugin_pid IS NULL", String.class, tenant.getId(), model(kind), tenant.getId(), tenant.getId());
                Approval lookup = new Approval("METADATA_SHAPE_V1", model(kind), "0".repeat(64), List.of(owner), Set.copyOf(nativeFields));
                JsonNode actual = reader.metadata(tenant.getId(), lookup);
                assertThat(actual.path("model").path("tenant_id").longValue()).isEqualTo(tenant.getId());
                approvals.add(new Approval(lookup.mode(), lookup.modelCode(), SourceBindingCanonical.hash(actual), lookup.owners(), lookup.nativeFieldPids()));
            }
            return new Binding(1, namespace + "_notice", tenant.getId(), approvals,
                    new Source(entity("source", "pending"), "pid", field("owner"), field("occurred"), new Target(field("targets"), entity("target", "ready"), field("owner"),
                            new Deadline(field("cutoff"), DeadlineRelation.AFTER_HOST_NOW),
                            new RemainingChild(entity("child", "active"), field("parent"), field("ordered"), field("submitted"), 1000))),
                    entity("owner", "active"), new Principal(entity("principal", "active"), field("principal"), field("owner"), field("verification")),
                    new Verification(entity("verification", "active"), field("owner")), ruleId, namespace + "_template", Map.of());
        });
    }

    private void connect(Binding binding, SourceBindingReader selectedReader) {
        HostSourceBindingProperties properties = new HostSourceBindingProperties();
        properties.setEnabled(true);
        properties.setBindings(List.of(binding));
        resolver = new RegisteredSourcePlanResolver(properties, selectedReader, validator, clock);
        resolver.initialize();
        host = new PluginNotificationDeliveryAccessorImpl(List.of(resolver), reliable, writer);
        ReflectionTestUtils.setField(host, "enabled", true);
        worker = new RegisteredSourceWorker(resolver, selectedReader, host, manager);
    }

    private RegisteredSourcePollingAdapter nativePollingAdapter() {
        var properties = new HostSourceBindingProperties();
        properties.setEnabled(true);
        properties.setBindings(List.of(approved));
        properties.getWorker().setEnabled(true);
        properties.getWorker().setPollIntervalMs(1000L);
        properties.getWorker().setMaxBindings(1);
        properties.getWorker().setMaxSourcesPerBinding(1);
        var adapter = new RegisteredSourcePollingAdapter(properties, resolver, worker, () -> {
            throw new IllegalStateException("Native test must not start autonomous polling resources");
        });
        adapter.initialize();
        return adapter;
    }

    private NotificationRuleRequest ruleRequest(String recipients) {
        NotificationRuleRequest request = new NotificationRuleRequest();
        request.setCode(namespace + "_rule"); request.setName("Native explicit recipient proof"); request.setEnabled(true);
        request.setTriggerType("EVENT"); request.setTriggerConfig("{\"modelCode\":\"" + model("source") + "\",\"event\":\"created\"}");
        request.setConditionModelCode(model("source")); request.setConditionFilter("[{\"fieldName\":\"" + field("state") + "\",\"operator\":\"EQ\",\"value\":\"pending\"}]");
        request.setActionChannel("IN_APP"); request.setActionTemplateCode(namespace + "_template");
        request.setRecipientType("SPECIFIC_USERS"); request.setRecipientField(recipients);
        return request;
    }

    private NotificationTemplateCreateRequest templateRequest(String body) {
        NotificationTemplateCreateRequest request = new NotificationTemplateCreateRequest();
        request.setCode(namespace + "_template"); request.setName("Native source template"); request.setChannel("IN_APP");
        request.setEnabled(true); request.setSubjectTemplate("Native source approval proof"); request.setBodyTemplate(body);
        return request;
    }

    /** Test-only deterministic host clock. No sleep, production time change or subscription grant. */
    private static final class NativeDeadlineClock extends Clock {
        private volatile Instant now = BEFORE_DEADLINE;

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private <T> T serializable(Supplier<T> work) {
        TransactionTemplate tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        return tx.execute(status -> work.get());
    }

    private Map<String, Integer> counts() {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (var entry : Map.of("outbox", "ab_outbox", "receipt", "ab_integration_receipt", "inbox", "ab_notification").entrySet()) {
            result.put(entry.getKey(), jdbc.queryForObject("SELECT COUNT(*) FROM " + entry.getValue() + " WHERE tenant_id=?", Integer.class, tenant.getId()));
        }
        return result;
    }

    private Map<String, Object> businessSnapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String kind : kinds) result.put(kind, jdbc.queryForList("SELECT to_jsonb(r)::text AS row FROM \"" + tables.get(kind) + "\" r WHERE tenant_id=? ORDER BY pid", tenant.getId()));
        return result;
    }

    private Map<String, Object> foreignSnapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String kind : kinds) result.put(kind, jdbc.queryForList("SELECT to_jsonb(r)::text AS row FROM \"" + tables.get(kind) + "\" r WHERE tenant_id=? ORDER BY pid", foreignTenant.getId()));
        for (String table : List.of("ab_outbox", "ab_integration_receipt", "ab_notification")) result.put(table, jdbc.queryForList("SELECT to_jsonb(r)::text AS row FROM " + table + " r WHERE tenant_id=? ORDER BY id", foreignTenant.getId()));
        return result;
    }

    private List<IntegrationEventEnvelope> events() {
        return jdbc.query("SELECT * FROM ab_outbox WHERE tenant_id=? ORDER BY event_id", (row, index) -> {
            try {
                return new IntegrationEventEnvelope(row.getString("schema_version"), row.getString("event_id"), row.getString("event_type"), row.getString("event_source"), row.getString("subject"), row.getTimestamp("occurred_at").toInstant(), row.getLong("tenant_id"), row.getString("correlation_id"), row.getString("causation_id"), row.getString("ordering_key"), row.getLong("event_sequence"), json.readValue(row.getString("payload"), new TypeReference<Map<String, Object>>() {}), json.readValue(row.getString("headers"), new TypeReference<Map<String, String>>() {}));
            } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
                // CATCH: expected persisted JSON validation error in fixture read, no envelope fallback.
                throw new IllegalStateException("Invalid persisted native fixture envelope", error);
            }
        }, tenant.getId());
    }

    private void startBusiness() { businessStarted = true; stage = "business-running"; }
    private void proved(String name) { checkpoints.add(name); stage = "business-checkpoints-completed"; }

    @Test
    void actualImportedMetadataExplicitRecipientsReplayAndConsumePersistExactlyOnce() throws Exception {
        startBusiness();
        RegisteredSourcePollingAdapter adapter = nativePollingAdapter();
        Map<String, Object> expiryBefore = businessSnapshot();
        clock.now = TARGET_DEADLINE;
        var expired = adapter.tick();
        assertThat(expired.status()).isEqualTo(RegisteredSourcePollingAdapter.Status.FAILED);
        assertThat(expired.selectedSources()).isEqualTo(1);
        assertThat(expired.failures()).hasSize(1);
        assertThat(expired.failures().getFirst().sourcePid()).isEqualTo(sourcePid);
        assertThat(businessSnapshot()).isEqualTo(expiryBefore);
        assertThat(counts()).isEqualTo(Map.of("outbox", 0, "receipt", 0, "inbox", 0));
        proved("approved physical DATETIME cutoff equal to host now rejects enqueue before any outbox/inbox/receipt");
        clock.now = BEFORE_DEADLINE;
        Map<String, Object> before = businessSnapshot();
        var selected = adapter.tick();
        assertThat(selected.status()).isEqualTo(RegisteredSourcePollingAdapter.Status.SUCCEEDED);
        assertThat(selected.processedPlans()).isEqualTo(2);
        assertThat(selected.matchedSources()).isEqualTo(1);
        assertThat(selected.selectedSources()).isEqualTo(1);
        assertThat(counts()).isEqualTo(Map.of("outbox", 2, "receipt", 0, "inbox", 0));
        List<IntegrationEventEnvelope> original = events();
        assertThat(original).hasSize(2);
        assertThat(original).extracting(IntegrationEventEnvelope::occurredAt)
                .containsOnly(Instant.parse("2026-10-13T03:00:00.123456Z"));
        var replay = adapter.tick();
        assertThat(replay.status()).isEqualTo(RegisteredSourcePollingAdapter.Status.SUCCEEDED);
        assertThat(replay.processedPlans()).isEqualTo(2);
        assertThat(events()).isEqualTo(original);
        for (IntegrationEventEnvelope event : original) {
            executor.deliver(event, host, UniqueIdGenerator.generate());
        }
        assertThat(counts()).isEqualTo(Map.of("outbox", 2, "receipt", 2, "inbox", 2));
        List<Map<String, Object>> inbox = jdbc.queryForList("SELECT id,user_id,source_id,title,content,created_at FROM ab_notification WHERE tenant_id=? ORDER BY user_id", tenant.getId());
        assertThat(inbox).extracting(row -> row.get("user_id")).containsExactlyInAnyOrder(first.getId(), second.getId());
        assertThat(inbox).allSatisfy(row -> {
            assertThat(row.get("source_id")).isEqualTo(sourcePid);
            assertThat(row.get("title")).isEqualTo("Native source approval proof");
            assertThat(row.get("content")).isEqualTo("Synthetic registered source body");
        });
        original.forEach(event -> executor.deliver(event, host, UniqueIdGenerator.generate()));
        assertThat(jdbc.queryForList("SELECT id,user_id,source_id,title,content,created_at FROM ab_notification WHERE tenant_id=? ORDER BY user_id", tenant.getId())).isEqualTo(inbox);
        assertThat(businessSnapshot()).isEqualTo(before);
        List<IntegrationEventEnvelope> sealedOriginal = events();
        update("child", remainingChild, Map.of(field("submitted"), new BigDecimal("60.000001")));
        assertThat(worker.process(tenant.getId(), approved.code(), sourcePid)).isEqualTo(2);
        assertThat(events()).isEqualTo(sealedOriginal);
        String finalChild = null;
        for (int index = 3; index <= 101; index++) {
            finalChild = create("child", Map.of(field("state"), "active", field("parent"), target,
                    field("ordered"), new BigDecimal("10"), field("submitted"), new BigDecimal("10")));
        }
        assertThat(worker.process(tenant.getId(), approved.code(), sourcePid)).isEqualTo(2);
        assertThat(events()).isEqualTo(sealedOriginal);
        update("child", finalChild, Map.of(field("submitted"), new BigDecimal("11")));
        rejectWithoutEffects();
        update("child", finalChild, Map.of(field("submitted"), new BigDecimal("10")));
        update("child", remainingChild, Map.of(field("submitted"), new BigDecimal("100")));
        rejectWithoutEffects();
        assertThat(events()).isEqualTo(sealedOriginal);
        proved("actual sixth approved child model: exact DECIMAL partial progress replays identical envelope; all101 rows scanned even after positive early row; oversubmitted later row and allfull zero new queue");
        proved("actual host adapter selected bounded source from approved registry; raw metadata approved; two explicit recipients; stable queue replay; receipt/inbox duplicate consume suppressed; immutable source");
        trace.put("productQuoteFacetSelected", productQuoteFacetEnabled);
        if (productQuoteFacetEnabled) {
            ProductQuoteReminderNativeFacet.run(new ProductQuoteReminderNativeFacet.Services(dataSource, jdbc, json,
                    manager, tenants, users, members, tenantBootstrap, imports, data, metadata, commands, authentication, background, templates, rules,
                    reader, validator, reliable, writer, executor, extensions, List.of(tenant.getId(), foreignTenant.getId())), evidence);
            proved("actual full Product DSL import and ReminderService queue facet completed with test-owned station notification authority");
        } else {
            trace.put("productQuoteFacet", "not_selected; original seven-method baseline only");
        }
    }

    @Test
    void foreignWrongOwnerAmbiguousAndInvalidExplicitUserCannotPartiallyEnqueue() {
        startBusiness();
        update("principal", link2, Map.of(field("owner"), otherOwner));
        rejectWithoutEffects();
        update("principal", link2, Map.of(field("owner"), ownOwner));
        update("source", sourcePid, Map.of(field("targets"), List.of(foreignTarget)));
        rejectWithoutEffects();
        update("source", sourcePid, Map.of(field("targets"), List.of(target)));
        rules.updateRule(ruleId, ruleRequest(first.getId() + "," + foreign.getId()));
        rejectWithoutEffects();
        rules.updateRule(ruleId, ruleRequest(first.getId() + "," + second.getId()));
        String ambiguous = principal(first, otherOwner, otherVerification);
        rejectWithoutEffects();
        update("principal", ambiguous, Map.of(field("state"), "inactive"));
        assertThat(members.deactivateMember(secondMember.getId())).isTrue();
        rejectWithoutEffects();
        assertThat(worker.process(foreignTenant.getId(), approved.code(), sourcePid)).isZero();
        proved("second user owner mismatch, foreign target, foreign platform member, cross-owner active-link ambiguity and inactive member: whole source zero enqueue");
    }

    @Test
    void changedTemplateSourceAndMembershipInvalidateQueuedAuthorityWithoutChangingOldEnvelope() {
        startBusiness();
        assertThat(worker.process(tenant.getId(), approved.code(), sourcePid)).isEqualTo(2);
        List<IntegrationEventEnvelope> original = events();
        Map<String, Object> beforeExpiry = businessSnapshot();
        clock.now = TARGET_DEADLINE;
        assertThatThrownBy(() -> worker.process(tenant.getId(), approved.code(), sourcePid))
                .isInstanceOf(IllegalArgumentException.class);
        for (IntegrationEventEnvelope event : original) {
            assertThatThrownBy(() -> executor.deliver(event, host, UniqueIdGenerator.generate()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(events()).isEqualTo(original);
        assertThat(counts()).isEqualTo(Map.of("outbox", 2, "receipt", 0, "inbox", 0));
        assertThat(businessSnapshot()).isEqualTo(beforeExpiry);
        proved("delayed actual consume rechecks approved DATETIME expiry; both original envelopes immutable; zero inbox/receipt and unchanged source facts");
        // Reset the synthetic clock for the remaining independent existing configuration facets.
        clock.now = BEFORE_DEADLINE;
        update("child", remainingChild, Map.of(field("submitted"), new BigDecimal("100")));
        for (IntegrationEventEnvelope event : original) {
            assertThatThrownBy(() -> executor.deliver(event, host, UniqueIdGenerator.generate()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(events()).isEqualTo(original);
        assertThat(counts()).isEqualTo(Map.of("outbox", 2, "receipt", 0, "inbox", 0));
        update("child", remainingChild, Map.of(field("submitted"), new BigDecimal("60.000001")));
        assertThat(worker.process(tenant.getId(), approved.code(), sourcePid)).isEqualTo(2);
        assertThat(events()).isEqualTo(original);
        proved("delayed consume rechecks complete child eligibility; allfull zero receipt/inbox; partial progress restores eligibility without changing immutable queued envelopes");
        templates.update(templatePid, templateRequest("Changed current configuration"));
        assertThatThrownBy(() -> worker.process(tenant.getId(), approved.code(), sourcePid)).hasMessage("RELIABLE_ENVELOPE_CONFLICT");
        assertThatThrownBy(() -> executor.deliver(original.getFirst(), host, "changed-template"))
                .hasMessage("NOTIFICATION_ENVELOPE_CONFLICT");
        assertThat(events()).isEqualTo(original);
        assertThat(counts()).isEqualTo(Map.of("outbox", 2, "receipt", 0, "inbox", 0));
        templates.update(templatePid, templateRequest("Synthetic registered source body"));
        update("source", sourcePid, Map.of(field("state"), "cancelled"));
        assertThatThrownBy(() -> executor.deliver(original.getFirst(), host, "changed-source"))
                .isInstanceOf(IllegalArgumentException.class);
        update("source", sourcePid, Map.of(field("state"), "pending"));
        assertThat(members.deactivateMember(secondMember.getId())).isTrue();
        assertThatThrownBy(() -> executor.deliver(original.getFirst(), host, "changed-membership"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(events()).isEqualTo(original);
        assertThat(counts()).isEqualTo(Map.of("outbox", 2, "receipt", 0, "inbox", 0));
        proved("same identity changed template conflict; current source eligibility and every explicit recipient membership rechecked; receipt/inbox rolled back");
    }

    @Test
    void sourceRuleAndTemplateLifecycleRecoverOriginalEnvelopeWithoutDuplicateDelivery() {
        startBusiness();
        assertThat(worker.process(tenant.getId(), approved.code(), sourcePid)).isEqualTo(2);
        List<IntegrationEventEnvelope> original = events();
        Map<String, Object> before = businessSnapshot();
        List<String> sourceBusiness = sourceBusinessSnapshot();
        String recipients = first.getId() + "," + second.getId();

        update("source", sourcePid, Map.of(field("state"), "cancelled"));
        rejectQueued(original);
        update("source", sourcePid, Map.of(field("state"), "pending"));
        assertThat(worker.process(tenant.getId(), approved.code(), sourcePid)).isEqualTo(2);
        assertThat(events()).isEqualTo(original);

        NotificationRuleRequest disabledRule = ruleRequest(recipients);
        disabledRule.setEnabled(false);
        rules.updateRule(ruleId, disabledRule);
        rejectQueued(original);
        rules.updateRule(ruleId, ruleRequest(recipients));
        assertThat(worker.process(tenant.getId(), approved.code(), sourcePid)).isEqualTo(2);
        assertThat(events()).isEqualTo(original);

        NotificationTemplateCreateRequest disabledTemplate = templateRequest("Synthetic registered source body");
        disabledTemplate.setEnabled(false);
        templates.update(templatePid, disabledTemplate);
        rejectQueued(original);
        templates.update(templatePid, templateRequest("Synthetic registered source body"));
        assertThat(worker.process(tenant.getId(), approved.code(), sourcePid)).isEqualTo(2);
        assertThat(events()).isEqualTo(original);
        proved("source cancellation, rule disable and template disable reject enqueue and both delayed consumers; exact configuration restoration preserves original envelopes");

        templates.update(templatePid, templateRequest("Configuration generation two"));
        assertThatThrownBy(() -> worker.process(tenant.getId(), approved.code(), sourcePid))
                .hasMessage("RELIABLE_ENVELOPE_CONFLICT");
        for (var event : original) assertThatThrownBy(() -> executor.deliver(event, host, UniqueIdGenerator.generate()))
                .hasMessage("NOTIFICATION_ENVELOPE_CONFLICT");
        assertThat(events()).isEqualTo(original);
        assertThat(counts()).isEqualTo(Map.of("outbox", 2, "receipt", 0, "inbox", 0));
        templates.update(templatePid, templateRequest("Synthetic registered source body"));
        Map<String, Object> restored = businessSnapshot();
        before.forEach((kind, rows) -> {
            if (!"source".equals(kind)) assertThat(restored.get(kind)).isEqualTo(rows);
        });
        assertThat(sourceBusinessSnapshot()).isEqualTo(sourceBusiness);

        for (var event : original) executor.deliver(event, host, UniqueIdGenerator.generate());
        assertThat(counts()).isEqualTo(Map.of("outbox", 2, "receipt", 2, "inbox", 2));
        assertThat(jdbc.queryForList("SELECT user_id FROM ab_notification WHERE tenant_id=? ORDER BY user_id",
                Long.class, tenant.getId())).containsExactlyInAnyOrder(first.getId(), second.getId());
        var inbox = jdbc.queryForList("SELECT to_jsonb(n)::text FROM ab_notification n WHERE tenant_id=? ORDER BY id", String.class, tenant.getId());
        var receipts = jdbc.queryForList("SELECT to_jsonb(r)::text FROM ab_integration_receipt r WHERE tenant_id=? ORDER BY id", String.class, tenant.getId());
        for (var event : original) executor.deliver(event, host, UniqueIdGenerator.generate());
        assertThat(jdbc.queryForList("SELECT to_jsonb(n)::text FROM ab_notification n WHERE tenant_id=? ORDER BY id", String.class, tenant.getId())).isEqualTo(inbox);
        assertThat(jdbc.queryForList("SELECT to_jsonb(r)::text FROM ab_integration_receipt r WHERE tenant_id=? ORDER BY id", String.class, tenant.getId())).isEqualTo(receipts);
        assertThat(events()).isEqualTo(original);
        assertThat(businessSnapshot()).isEqualTo(restored);
        trace.put("lifecycle", Map.of("disabledFacets", List.of("source", "rule", "template"),
                "restoredDeliveryCount", 2, "duplicateEffectCount", 0, "configurationConflictRejected", true,
                "originalEnvelopesUnchanged", true, "nonSourceBusinessRowsUnchanged", true,
                "sourceBusinessFieldsRestored", true, "sourceAuditMetadataChangeExpected", true));
        proved("changed configuration cannot rewrite queued immutable events; restoring exact semantics delivers once per explicit recipient; duplicate consume changes no inbox or receipt");
    }

    private List<String> sourceBusinessSnapshot() {
        // Intentional cancellation/restoration updates audit metadata; business facts must still restore exactly.
        return jdbc.queryForList("SELECT (to_jsonb(s) - 'row_version' - 'updated_at' - 'updated_by')::text FROM \""
                + tables.get("source") + "\" s WHERE tenant_id=? ORDER BY pid", String.class, tenant.getId());
    }

    private void rejectQueued(List<IntegrationEventEnvelope> original) {
        assertThatThrownBy(() -> worker.process(tenant.getId(), approved.code(), sourcePid))
                .isInstanceOf(IllegalArgumentException.class);
        for (var event : original) assertThatThrownBy(() -> executor.deliver(event, host, UniqueIdGenerator.generate()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(events()).isEqualTo(original);
        assertThat(counts()).isEqualTo(Map.of("outbox", 2, "receipt", 0, "inbox", 0));
    }

    @Test
    void actualSecondEnqueueFailureRollsBackFirstQueueAndRestoresCallerContext() {
        startBusiness();
        Map<String, Object> before = businessSnapshot();
        AtomicBoolean firstQueued = new AtomicBoolean();
        NotificationDeliveryAccessor fault = new NotificationDeliveryAccessor() {
            @Override
            public Result enqueue(Request request) {
                if (firstQueued.compareAndSet(false, true)) {
                    Result result = host.enqueue(request);
                    assertThat(counts().get("outbox")).isEqualTo(1);
                    return result;
                }
                throw new IllegalStateException("native fault before second enqueue");
            }
        };
        RegisteredSourceWorker failing = new RegisteredSourceWorker(resolver, reader, fault, manager);
        MetaContext.Snapshot previous = MetaContext.snapshot();
        assertThatThrownBy(() -> failing.process(tenant.getId(), approved.code(), sourcePid))
                .hasMessage("native fault before second enqueue");
        assertThat(firstQueued).isTrue();
        assertThat(MetaContext.snapshot()).isEqualTo(previous);
        assertThat(counts()).isEqualTo(Map.of("outbox", 0, "receipt", 0, "inbox", 0));
        assertThat(businessSnapshot()).isEqualTo(before);
        proved("real first enqueue visible within source transaction; second fault rolls it back on same physical resource; context restored");
    }

    @Test
    void actualInboxAndReceiptRollbackTogetherBeforeSuccessfulDuplicateConsume() {
        startBusiness();
        worker.process(tenant.getId(), approved.code(), sourcePid);
        IntegrationEventEnvelope event = events().getFirst();
        FailingAfterPersist failing = new FailingAfterPersist();
        assertThatThrownBy(() -> executor.deliver(event, failing, "native-after-persist"))
                .hasMessage("native fault after persisted inbox");
        assertThat(counts()).isEqualTo(Map.of("outbox", 2, "receipt", 0, "inbox", 0));
        executor.deliver(event, host, "native-success");
        List<Map<String, Object>> before = jdbc.queryForList("SELECT to_jsonb(n)::text AS row FROM ab_notification n WHERE tenant_id=? ORDER BY id", tenant.getId());
        executor.deliver(event, host, "native-duplicate");
        assertThat(jdbc.queryForList("SELECT to_jsonb(n)::text AS row FROM ab_notification n WHERE tenant_id=? ORDER BY id", tenant.getId())).isEqualTo(before);
        assertThat(counts()).isEqualTo(Map.of("outbox", 2, "receipt", 1, "inbox", 1));
        proved("actual persisted inbox fault rolls back inbox plus receipt; one later successful effect and duplicate suppression");
    }

    @Test
    void actualBindingMutationCannotBeAuthorizedByCachedMetadataOrOldApproval() {
        startBusiness();
        assertThat(softTargetFixture).isTrue();
        serializable(() -> {
            Map<String, Object> live = reader.record(approved, model("target"), target);
            assertThat(live).isInstanceOf(SourceBindingReader.LiveRecord.class).containsEntry("deleted_flag", false);
            return null;
        });
        MetaContext.runWithCommandPermitScope("ALL", () -> data.delete(model("target"), target));
        assertThat(jdbc.queryForObject("SELECT deleted_flag FROM \"" + tables.get("target")
                + "\" WHERE tenant_id=? AND pid=?", Boolean.class, tenant.getId(), target)).isTrue();
        assertThatThrownBy(() -> serializable(() -> reader.record(approved, model("target"), target)))
                .isInstanceOf(IllegalArgumentException.class);
        rejectWithoutEffects();
        target = create("target", Map.of(field("state"), "ready", field("owner"), ownOwner, field("cutoff"), TARGET_DEADLINE));
        update("source", sourcePid, Map.of(field("targets"), List.of(target)));
        update("child", remainingChild, Map.of(field("parent"), target));
        update("child", fullChild, Map.of(field("parent"), target));
        proved("official softDelete=true target: physical flag false eligible; native delete retains true row and typed reader rejects without queue; remaining models retain hard-delete defaults");
        String fieldPid = jdbc.queryForObject("SELECT pid FROM ab_meta_field WHERE tenant_id=? AND code=? AND is_current=TRUE AND deleted_flag=FALSE", String.class, tenant.getId(), field("state"));
        bindings.updateFieldConfig(modelPids.get("source"), fieldPid, true, false, true);
        rejectWithoutEffects();
        proved("official binding configuration API changes raw PG shape; old host approval rejected before business read/queue");
    }

    @Test
    void actualSerializableCrossOwnerPhantomCycleFailsWithoutAutomaticRetryOrQueue() throws Exception {
        startBusiness();
        CountDownLatch predicateRead = new CountDownLatch(1), foreignCommitted = new CountDownLatch(1);
        AtomicBoolean barrierUsed = new AtomicBoolean();
        SourceBindingReader barrier = new DelegatingReader(reader) {
            @Override
            public DataPage page(Binding binding, String model, Map<String, Object> filters, int page, int size) {
                DataPage result = super.page(binding, model, filters, page, size);
                if (model.equals(model("principal")) && first.getPid().equals(filters.get(field("principal"))) && barrierUsed.compareAndSet(false, true)) {
                    assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_SERIALIZABLE);
                    assertThat(result.total()).isEqualTo(1);
                    predicateRead.countDown();
                    await(foreignCommitted);
                }
                return result;
            }
        };
        connect(approved, barrier);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var inserted = pool.submit(() -> {
                bind(tenant, first);
                try {
                    await(predicateRead);
                    String pid = serializable(() -> {
                        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ab_outbox WHERE tenant_id=?", Integer.class, tenant.getId())).isZero();
                        return principal(first, otherOwner, otherVerification);
                    });
                    foreignCommitted.countDown();
                    return pid;
                } finally {
                    MetaContext.clear();
                }
            });
            Throwable failure = org.assertj.core.api.Assertions.catchThrowable(() -> worker.process(tenant.getId(), approved.code(), sourcePid));
            assertThat(failure).isNotNull();
            assertThat(sqlState(failure)).as("actual dangerous read/write predicate cycle must be PostgreSQL serialization failure, not fixture or permission failure").isEqualTo("40001");
            assertThat(inserted.get(30, TimeUnit.SECONDS)).isNotBlank();
            assertThat(barrierUsed).isTrue();
            assertThat(counts()).isEqualTo(Map.of("outbox", 0, "receipt", 0, "inbox", 0));
            connect(approved, reader);
            rejectWithoutEffects();
            proved("two real SERIALIZABLE transactions: active-link predicate/outbox predicate dangerous cycle; PG 40001, zero enqueue, no automatic retry; fresh ambiguous link refused");
            String ambiguous = inserted.get(30, TimeUnit.SECONDS);
            MetaContext.runWithCommandPermitScope("ALL", () -> data.delete(model("principal"), ambiguous));
            connect(approved, reader);
            CountDownLatch childRead = new CountDownLatch(1), childCommitted = new CountDownLatch(1);
            AtomicBoolean childBarrierUsed = new AtomicBoolean();
            SourceBindingReader childBarrier = new DelegatingReader(reader) {
                @Override
                public DataPage page(Binding binding, String model, Map<String, Object> filters, int page, int size) {
                    DataPage result = super.page(binding, model, filters, page, size);
                    if (model.equals(model("child")) && childBarrierUsed.compareAndSet(false, true)) {
                        assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                                .isEqualTo(TransactionDefinition.ISOLATION_SERIALIZABLE);
                        assertThat(result.total()).isEqualTo(2);
                        childRead.countDown();
                        await(childCommitted);
                    }
                    return result;
                }
            };
            connect(approved, childBarrier);
            var childInsert = pool.submit(() -> {
                bind(tenant, first);
                try {
                    await(childRead);
                    String pid = serializable(() -> {
                        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ab_outbox WHERE tenant_id=?", Integer.class, tenant.getId())).isZero();
                        return create("child", Map.of(field("state"), "active", field("parent"), target,
                                field("ordered"), new BigDecimal("10"), field("submitted"), new BigDecimal("11")));
                    });
                    childCommitted.countDown();
                    return pid;
                } finally {
                    childCommitted.countDown();
                    MetaContext.clear();
                }
            });
            Throwable childFailure = org.assertj.core.api.Assertions.catchThrowable(
                    () -> worker.process(tenant.getId(), approved.code(), sourcePid));
            assertThat(childFailure).isNotNull();
            assertThat(sqlState(childFailure)).isEqualTo("40001");
            assertThat(childInsert.get(30, TimeUnit.SECONDS)).isNotBlank();
            trace.put("actualChildPhantomSqlState", sqlState(childFailure));
            assertThat(childBarrierUsed).isTrue();
            assertThat(counts()).isEqualTo(Map.of("outbox", 0, "receipt", 0, "inbox", 0));
            connect(approved, reader);
            rejectWithoutEffects();
            proved("actual child predicate/outbox predicate SSI cycle: PG40001 and zero queue without retry; fresh full relation sees invalid phantom child and refuses eligibility");
        } finally {
            foreignCommitted.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void rejectWithoutEffects() {
        Map<String, Object> before = businessSnapshot();
        Map<String, Integer> beforeCounts = counts();
        assertThatThrownBy(() -> worker.process(tenant.getId(), approved.code(), sourcePid)).isInstanceOf(IllegalArgumentException.class);
        assertThat(counts()).isEqualTo(beforeCounts);
        assertThat(businessSnapshot()).isEqualTo(before);
    }

    private void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(20, TimeUnit.SECONDS)).as("native fixture concurrency barrier").isTrue();
        } catch (InterruptedException error) {
            // CATCH: test barrier cancellation, never retry a real transaction or hide a business failure.
            Thread.currentThread().interrupt();
            throw new IllegalStateException("native barrier interrupted", error);
        }
    }

    private String sqlState(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) return sql.getSQLState();
        }
        return null;
    }

    private final class FailingAfterPersist implements ReliableEventConsumerExtension, HostSerializableEventConsumer {
        @Override public String consumerCode() { return host.consumerCode(); }
        @Override public Set<String> subscribedEventTypes() { return host.subscribedEventTypes(); }
        @Override
        public boolean requiresSerializableTransaction(IntegrationEventEnvelope envelope) {
            return host.requiresSerializableTransaction(envelope);
        }
        @Override public void consume(IntegrationEventEnvelope event) {
            host.consume(event);
            assertThat(counts().get("inbox")).isEqualTo(1);
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_SERIALIZABLE);
            throw new IllegalStateException("native fault after persisted inbox");
        }
    }

    private static class DelegatingReader implements SourceBindingReader {
        private final SourceBindingReader delegate;
        DelegatingReader(SourceBindingReader delegate) { this.delegate = delegate; }
        @Override public JsonNode metadata(long tenant, Approval approval) { return delegate.metadata(tenant, approval); }
        @Override public Map<String, Object> record(Binding binding, String model, String pid) { return delegate.record(binding, model, pid); }
        @Override public DataPage page(Binding binding, String model, Map<String, Object> filters, int page, int size) { return delegate.page(binding, model, filters, page, size); }
        @Override public JsonNode rule(long tenant, long id) { return delegate.rule(tenant, id); }
        @Override public JsonNode template(long tenant, String code) { return delegate.template(tenant, code); }
        @Override public String userPid(long tenant, long id) { return delegate.userPid(tenant, id); }
    }
}
