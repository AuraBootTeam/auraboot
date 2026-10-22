package com.auraboot.framework.notification.sourcebinding;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.ReliableEventDeliveryExecutor;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.CommandExecutor;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.auth.service.AuthService;
import com.auraboot.framework.auth.dto.AuthenticationRequest;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.constant.SystemFieldConstants;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.notification.dto.NotificationRuleRequest;
import com.auraboot.framework.notification.dto.NotificationTemplateCreateRequest;
import com.auraboot.framework.notification.service.NotificationRuleService;
import com.auraboot.framework.notification.service.NotificationTemplateService;
import com.auraboot.framework.notification.service.impl.PluginNotificationDeliveryAccessorImpl;
import com.auraboot.framework.notification.service.impl.StrictInAppDeliveryWriter;
import com.auraboot.framework.notification.sourcebinding.SourceBindingTypes.Approval;
import com.auraboot.framework.notification.sourcebinding.SourceBindingTypes.Binding;
import com.auraboot.framework.notification.sourcebinding.SourceBindingTypes.Deadline;
import com.auraboot.framework.notification.sourcebinding.SourceBindingTypes.DeadlineRelation;
import com.auraboot.framework.notification.sourcebinding.SourceBindingTypes.Entity;
import com.auraboot.framework.notification.sourcebinding.SourceBindingTypes.Exact;
import com.auraboot.framework.notification.sourcebinding.SourceBindingTypes.Owner;
import com.auraboot.framework.notification.sourcebinding.SourceBindingTypes.Principal;
import com.auraboot.framework.notification.sourcebinding.SourceBindingTypes.Source;
import com.auraboot.framework.notification.sourcebinding.SourceBindingTypes.Target;
import com.auraboot.framework.notification.sourcebinding.SourceBindingTypes.Verification;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.extension.DataAccessor;
import com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelope;
import com.auraboot.framework.plugin.extension.integration.ReliableIntegrationAccessor;
import com.auraboot.framework.plugin.extension.BackgroundDataAccessor;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import com.auraboot.framework.plugin.service.PluginImportService;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.service.TenantBootstrapService;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.InvocationTargetException;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** An additional facet of the existing native method, not a suite, production authority or phone/consent proof. */
final class ProductQuoteReminderNativeFacet {
    record Services(DataSource dataSource, JdbcTemplate jdbc, ObjectMapper json,
                    PlatformTransactionManager manager, TenantService tenants, UserService users,
                    TenantMemberService members, TenantBootstrapService bootstrap, PluginImportService imports,
                    DynamicDataService data, MetaModelService metadata, CommandExecutor commands, AuthService authentication,
                    BackgroundDataAccessor background, NotificationTemplateService templates, NotificationRuleService rules,
                    ImportedNotificationMetadataReader reader, ImportedNotificationMetadataValidator validator,
                    ReliableIntegrationAccessor reliable, StrictInAppDeliveryWriter writer,
                    ReliableEventDeliveryExecutor executor, ExtensionRegistry extensions, List<Long> protectedTenantIds) {}
    private static final List<String> MODELS = List.of("qo_procurement_reminder_common", "s2p_bid",
            "pr_supplier_common", "qo_procurement_membership_common", "pr_supplier_pcba_contact");
    private static final List<String> BUSINESS_MODELS = List.of("pr_supplier_common", "pr_supplier_pcba_contact",
            "qo_procurement_membership_common", "s2p_sourcing_event", "s2p_bid", "qo_procurement_reminder_common");
    private static final Instant SLOT = Instant.parse("2026-10-13T01:30:00Z"); // Existing Tuesday 09:30 Beijing quote slot.
    private final String namespace = "sn" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private final Services s;
    private final Path evidence;
    private final Map<String, Object> trace = new LinkedHashMap<>();
    private final FacetClock clock = new FacetClock();
    private final Map<String, String> tables = new LinkedHashMap<>();
    private Tenant tenant;
    private User first, second, initializer;
    private Binding binding;
    private String sourcePid;
    private String stage = "product-admission";
    private boolean completed;
    private Map<String, Object> protectedBaseline;
    private JsonNode canonicalOrgManifest;
    private Map<String, JsonNode> canonicalOrgFields;

    private ProductQuoteReminderNativeFacet(Services services, Path parent) throws Exception {
        s = services;
        evidence = Files.createDirectory(parent.resolve("product-quote-facet"));
    }

    // BEGIN additive controlled-tick setup. Existing facet methods remain unchanged.
    record TickFixture(long tenantId, String namespace, String supplierPid, String taskPid, String quotePid,
                       List<Long> recipientIds, Binding binding, Map<String, String> tables,
                       Path productJar, Path setupEvidence, String setupSha256) {
        TickFixture {
            recipientIds = List.copyOf(recipientIds);
            tables = Map.copyOf(tables);
        }
    }

    static TickFixture prepareTickFixture(Services services, Path parent) throws Exception {
        MetaContext.Snapshot previous = MetaContext.snapshot();
        try {
            var fixture = new ProductQuoteReminderNativeFacet(services, parent);
            JsonNode input = fixture.admission();
            fixture.loadCanonicalNativeOrg(input);
            fixture.initializeTenant();
            fixture.importProduct(input);
            fixture.importInitializationAppendix(input);
            fixture.authenticateInitializer();
            String supplier = fixture.create("pr_supplier_common", "pr_supplier_pcba_contact:sync_directory", Map.of(
                    "pr_supplier_code", fixture.namespace, "pr_supplier_name", "Synthetic S06 supplier", "pr_supplier_status", "enabled"));
            String firstContact = fixture.contact(supplier, "first");
            String secondContact = fixture.contact(supplier, "second");
            fixture.membership(fixture.first, supplier, firstContact);
            fixture.membership(fixture.second, supplier, secondContact);
            String task = fixture.create("s2p_sourcing_event", "qo_procurement_task_common:create", Map.of(
                    "s2p_se_code", fixture.namespace, "s2p_se_title", "Synthetic S06 quotation", "s2p_se_event_type", "rfq",
                    "s2p_se_status", "bidding", "s2p_se_collaboration_managed", true));
            String quote = fixture.create("s2p_bid", "qo_procurement_task_common:dispatch", Map.of(
                    "s2p_bid_sourcing_event_id", task, "s2p_bid_supplier_id", supplier, "s2p_bid_status", "draft",
                    "s2p_bid_collaboration_managed", true, "qo_pr_quote_cutoff_at", SLOT.plusSeconds(3600)));
            fixture.bind();
            Binding binding = fixture.approval(fixture.ruleAndTemplate());
            fixture.trace.put("stage", "controlled-tick-fixture-prepared");
            fixture.trace.put("nativeTickExecuted", false);
            fixture.trace.put("tenantId", fixture.tenant.getId());
            fixture.trace.put("supplierPid", supplier);
            fixture.trace.put("taskPid", task);
            fixture.trace.put("quotePid", quote);
            fixture.trace.put("recipientIds", List.of(fixture.first.getId(), fixture.second.getId()));
            fixture.trace.put("binding", binding);
            Path sealed = fixture.evidence.resolve("tick-fixture-setup.json");
            Files.writeString(sealed, services.json.writerWithDefaultPrettyPrinter().writeValueAsString(fixture.trace),
                    StandardOpenOption.CREATE_NEW);
            return new TickFixture(fixture.tenant.getId(), fixture.namespace, supplier, task, quote,
                    List.of(fixture.first.getId(), fixture.second.getId()), binding, fixture.tables,
                    Path.of(input.path("productJarPath").asText()).toRealPath(), sealed, sha(sealed));
        } finally {
            MetaContext.clear();
            MetaContext.restore(previous);
        }
    }
    // BEGIN additive PF4J automatic fixture.
    record AutomaticFixture(TickFixture quote, String orderPid, String linePid, String productPid,
                            Binding shipmentBinding, Path setupEvidence, String setupSha256) {}

    static AutomaticFixture prepareAutomaticFixture(Services services, Path parent) throws Exception {
        var previous = MetaContext.snapshot();
        try {
            var f = new ProductQuoteReminderNativeFacet(services, parent);
            JsonNode input = f.admission();
            f.loadCanonicalNativeOrg(input);
            f.initializeTenant();
            f.importProduct(input);
            f.importInitializationAppendix(input);
            f.importAutomaticAppendices(input);
            f.authenticateInitializer();
            String supplier = f.create("pr_supplier_common", "pr_supplier_pcba_contact:sync_directory", Map.of(
                    "pr_supplier_code", f.namespace, "pr_supplier_name", "Synthetic S06 automatic supplier", "pr_supplier_status", "enabled"));
            f.membership(f.first, supplier, f.contact(supplier, "automatic-first"));
            f.membership(f.second, supplier, f.contact(supplier, "automatic-second"));
            String task = f.create("s2p_sourcing_event", "qo_procurement_task_common:create", Map.of(
                    "s2p_se_code", f.namespace, "s2p_se_title", "Synthetic S06 automatic quote", "s2p_se_event_type", "rfq",
                    "s2p_se_status", "bidding", "s2p_se_collaboration_managed", true));
            String quote = f.create("s2p_bid", "qo_procurement_task_common:dispatch", Map.of(
                    "s2p_bid_sourcing_event_id", task, "s2p_bid_supplier_id", supplier, "s2p_bid_status", "draft",
                    "s2p_bid_collaboration_managed", true, "qo_pr_quote_cutoff_at", SLOT.plusSeconds(3600)));
            String product = f.automaticCreate("prod_product", Map.of("prod_name", "Synthetic S06 automatic material",
                    "prod_unit", "pcs", "prod_type", "raw_material", "prod_status", "planned"));
            String order = f.automaticCreate("pr_purchase_order", Map.of("pr_po_code", f.namespace + "-auto",
                    "pr_po_supplier", supplier, "pr_po_date", "2026-10-17", "pr_po_status", "approved",
                    "pr_po_collaboration_managed", true, "qo_pr_order_state", "open"));
            String line = f.automaticCreate("pr_purchase_order_line", Map.of("pr_pol_order_id", order,
                    "pr_pol_product_id", product, "pr_pol_qty", new java.math.BigDecimal("100"),
                    "pr_pol_received_qty", java.math.BigDecimal.ZERO, "pr_pol_collaboration_managed", true,
                    "qo_pr_order_line_submitted_qty", java.math.BigDecimal.ZERO));
            f.bind();
            Binding quotation = f.approval(f.ruleAndTemplate());
            Binding shipment = f.automaticShipmentApproval(f.automaticShipmentRule());
            f.trace.put("stage", "automatic-registry-fixture-prepared");
            f.trace.put("nativeTickExecuted", false);
            f.trace.put("automaticTimerVerified", false);
            f.trace.put("tenantId", f.tenant.getId());
            f.trace.put("namespace", f.namespace);
            f.trace.put("supplierPid", supplier);
            f.trace.put("quotePid", quote);
            f.trace.put("orderPid", order);
            f.trace.put("orderLinePid", line);
            f.trace.put("productPid", product);
            f.trace.put("recipientIds", List.of(f.first.getId(), f.second.getId()));
            f.trace.put("quoteBinding", quotation);
            f.trace.put("shipmentBinding", shipment);
            // No guessed mt_* name: use the actual published model projection for the two new targets.
            for (String model : List.of("pr_purchase_order", "pr_purchase_order_line", "prod_product")) {
                String table = services.metadata.getTableName(model);
                ProcurementReminderTickNativeIT.identifier(table);
                assertThat(table).startsWith("mt_");
                assertThat(services.jdbc.queryForObject("SELECT to_regclass(?)::text", String.class, table)).isEqualTo(table);
                f.tables.put(model, table);
            }
            Path sealed = f.evidence.resolve("automatic-fixture-setup.json");
            Files.writeString(sealed, services.json.writerWithDefaultPrettyPrinter().writeValueAsString(f.trace), StandardOpenOption.CREATE_NEW);
            var q = new TickFixture(f.tenant.getId(), f.namespace, supplier, task, quote,
                    List.of(f.first.getId(), f.second.getId()), quotation, f.tables,
                    Path.of(input.path("productJarPath").asText()).toRealPath(), sealed, sha(sealed));
            return new AutomaticFixture(q, order, line, product, shipment, sealed, sha(sealed));
        } finally { MetaContext.clear(); MetaContext.restore(previous); }
    }

    private void importAutomaticAppendices(JsonNode input) throws Exception {
        var owners = new LinkedHashMap<String, Path>();
        for (JsonNode row : input.path("orderedImportDirectories")) {
            Path directory = Path.of(row.path("path").asText()).toRealPath();
            String id = s.json.readTree(directory.resolve("plugin.json").toFile()).path("pluginId").asText();
            if (Set.of("com.auraboot.procurement", "com.auraboot.quote-procurement").contains(id))
                assertThat(owners.put(id, directory)).isNull();
        }
        assertThat(owners.keySet()).containsExactlyInAnyOrder("com.auraboot.procurement", "com.auraboot.quote-procurement");
        var nativeCopy = ProductQuoteNativeInitialization.prepareAutomaticProcurementOwner(owners.get("com.auraboot.procurement"),
                evidence.resolve("automatic-native-owner"), s.json);
        // Use the already amended Q copy so this reimport cannot erase the old S06 initialization commands/writers.
        Path quoteCopy = evidence.resolve("initialization-appendix");
        assertThat(Files.isDirectory(quoteCopy)).isTrue();
        var quote = ProductQuoteNativeInitialization.prepareAutomaticQuoteOwner(quoteCopy, evidence.resolve("automatic-quote-owner"), s.json);
        trace.put("automaticNativeOwnerAppendix", nativeCopy);
        trace.put("automaticQuoteOwnerAppendix", quote);
        for (Map<String, Object> copy : List.of(nativeCopy, quote)) {
            var preview = s.imports.parseDirectory((String) copy.get("testDirectory"), true);
            assertThat(preview.isValid()).as("Automatic exact owner appendix: %s", preview.getErrors()).isTrue();
            var request = ImportRequest.builder().importId(preview.getImportId())
                    .conflictStrategy(ImportRequest.ConflictStrategy.OVERWRITE).autoPublishFields(true)
                    .autoPublishModels(true).autoPublishCommands(true).autoPublishPages(true).createResourcePermissions(true).build();
            assertThat(s.imports.execute(preview.getImportId(), request).isSuccess()).isTrue();
        }
        assertThat(s.imports.verifyImportReferenceIntegrity()).isEmpty();
        for (var entry : ProductQuoteNativeInitialization.AUTOMATIC_FIELDS.entrySet()) {
            var definition = s.metadata.getModelDefinitionFromDb(entry.getKey()).orElseThrow();
            assertThat(definition.getStatus()).isEqualTo("published");
            assertThat(definition.getFields().stream().map(field -> field.getCode()).toList()).containsAll(entry.getValue());
            // A newly required non-system field without a default must stop BEFORE any fixture record creation.
            for (var field : definition.getFields()) {
                if (field.isRequired() && field.getDefaultValue() == null && !SystemFieldConstants.ALL_INFRASTRUCTURE.contains(field.getCode()))
                    assertThat(entry.getValue()).as("Exact required published field %s", field.getCode()).contains(field.getCode());
            }
        }
    }
    private String automaticCreate(String model, Map<String, Object> facts) {
        ProductQuoteNativeInitialization.validateAutomaticPayload(model, facts);
        var request = new CommandExecuteRequest();
        request.setPayload(new LinkedHashMap<>(facts));
        request.setClientRequestId(namespace + "-automatic-" + UUID.randomUUID());
        var result = s.commands.execute(ProductQuoteNativeInitialization.automaticCommand(model), request);
        assertThat(result.getPhaseReached()).isEqualTo("completed");
        assertThat(result.getData().get("recordPid")).isInstanceOf(String.class);
        String pid = (String) result.getData().get("recordPid");
        assertThat(pid).isNotBlank();
        @SuppressWarnings("unchecked")
        var commands = (List<Map<String, Object>>) trace.computeIfAbsent("automaticInitializationCommands", ignored -> new ArrayList<Map<String, Object>>());
        commands.add(Map.of("command", ProductQuoteNativeInitialization.automaticCommand(model), "model", model,
                "recordPid", pid, "phase", result.getPhaseReached()));
        return pid;
    }
    static Entity automaticShipmentEntity() {
        return new Entity("qo_procurement_reminder_common", List.of(new Exact("qo_pr_reminder_kind", "shipment"),
                new Exact("qo_pr_reminder_state", "pending")));
    }
    static Target automaticShipmentTarget() {
        return new Target("qo_pr_reminder_targets", new Entity("pr_purchase_order", List.of(
                new Exact("pr_po_collaboration_managed", true), new Exact("qo_pr_order_state", "open"))), "pr_po_supplier", null,
                new SourceBindingTypes.RemainingChild(new Entity("pr_purchase_order_line", List.of(
                        new Exact("pr_pol_collaboration_managed", true))), "pr_pol_order_id", "pr_pol_qty",
                        "qo_pr_order_line_submitted_qty", 1000));
    }
    private long automaticShipmentRule() {
        var template = new NotificationTemplateCreateRequest();
        template.setCode(namespace + "_shipment_template");
        template.setName("Synthetic S06 shipment template");
        template.setChannel("IN_APP");
        template.setEnabled(true);
        template.setSubjectTemplate("Synthetic shipment reminder");
        template.setBodyTemplate("Synthetic open order pending shipment");
        s.templates.create(template);
        var rule = new NotificationRuleRequest();
        rule.setCode(namespace + "_shipment_rule");
        rule.setName("Synthetic shipment explicit recipients");
        rule.setEnabled(true);
        rule.setTriggerType("EVENT");
        rule.setTriggerConfig("{\"modelCode\":\"qo_procurement_reminder_common\",\"event\":\"created\"}");
        rule.setConditionModelCode("qo_procurement_reminder_common");
        rule.setConditionFilter(SourceBindingCanonical.tree(automaticShipmentEntity().equals().stream().map(predicate ->
                Map.of("fieldName", predicate.fieldCode(), "operator", "EQ", "value", predicate.value().raw())).toList()).toString());
        rule.setActionChannel("IN_APP");
        rule.setActionTemplateCode(namespace + "_shipment_template");
        rule.setRecipientType("SPECIFIC_USERS");
        rule.setRecipientField(first.getId() + "," + second.getId());
        return s.rules.createRule(rule).getId();
    }
    private Binding automaticShipmentApproval(long rule) {
        return tx(() -> {
            List<Approval> approvals = new ArrayList<>();
            for (String model : List.of("qo_procurement_reminder_common", "pr_purchase_order", "pr_purchase_order_line",
                    "pr_supplier_common", "qo_procurement_membership_common", "pr_supplier_pcba_contact")) {
                // Only actual contributors of this imported model/binding/field graph are explicitly approved in this test.
                List<String> contributors = s.jdbc.queryForList("""
                        SELECT DISTINCT contributor FROM (
                          SELECT m.plugin_pid AS contributor FROM ab_meta_model m WHERE m.tenant_id=? AND m.code=? AND m.is_current=TRUE AND m.deleted_flag=FALSE
                          UNION ALL
                          SELECT f.plugin_pid FROM ab_meta_model m JOIN ab_meta_model_field_binding b ON b.model_id=m.id JOIN ab_meta_field f ON f.id=b.field_id
                            WHERE m.tenant_id=? AND m.code=? AND m.is_current=TRUE AND m.deleted_flag=FALSE AND b.tenant_id=? AND b.deleted_flag=FALSE
                          UNION ALL
                          SELECT r.plugin_pid FROM ab_meta_model m JOIN ab_meta_model_field_binding b ON b.model_id=m.id JOIN ab_plugin_resource r ON r.resource_pid=b.pid
                            WHERE m.tenant_id=? AND m.code=? AND m.is_current=TRUE AND m.deleted_flag=FALSE AND b.tenant_id=? AND b.deleted_flag=FALSE
                              AND r.tenant_id=? AND r.resource_type='model_field_binding'
                        ) actual WHERE contributor IS NOT NULL ORDER BY contributor
                        """, String.class, tenant.getId(), model, tenant.getId(), model, tenant.getId(),
                        tenant.getId(), model, tenant.getId(), tenant.getId());
                assertThat(contributors).isNotEmpty();
                List<Owner> owners = new ArrayList<>();
                for (String pid : contributors) {
                    var plugin = s.jdbc.queryForMap("SELECT pid,plugin_id,namespace,version FROM ab_plugin WHERE tenant_id=? AND pid=? AND deleted_flag=FALSE", tenant.getId(), pid);
                    owners.add(new Owner(pid, (String) plugin.get("plugin_id"), (String) plugin.get("namespace"), (String) plugin.get("version")));
                }
                List<Map<String, Object>> systemBindings = s.jdbc.queryForList("""
                        SELECT f.pid,f.code,f.data_type,f.plugin_pid AS field_plugin_pid,
                               f.tenant_id AS field_tenant_id,b.tenant_id AS binding_tenant_id,
                               b.is_system_binding,b.editable,b.alias_code,p.pid AS plugin_pid,
                               p.tenant_id AS plugin_tenant_id,p.plugin_id,p.namespace,p.version
                        FROM ab_meta_model m JOIN ab_meta_model_field_binding b ON b.model_id=m.id
                          JOIN ab_meta_field f ON f.id=b.field_id
                          LEFT JOIN ab_plugin p ON p.pid=f.plugin_pid AND p.tenant_id=? AND p.deleted_flag=FALSE
                        WHERE m.tenant_id=? AND m.code=? AND m.is_current=TRUE AND m.deleted_flag=FALSE
                          AND b.tenant_id=? AND b.deleted_flag=FALSE AND b.is_system_binding=TRUE
                          AND f.tenant_id=? AND f.deleted_flag=FALSE AND f.is_current=TRUE
                        """, tenant.getId(), tenant.getId(), model, tenant.getId(), tenant.getId());
                Set<String> nativeFields = nativeSystemFieldPids(systemBindings, tenant.getId(), canonicalOrgManifest, canonicalOrgFields);
                trace.put("nativeSystemFieldPids:" + model, nativeFields);
                Approval lookup = new Approval("METADATA_SHAPE_V1", model, "0".repeat(64), owners, Set.copyOf(nativeFields));
                JsonNode graph = s.reader.metadata(tenant.getId(), lookup);
                approvals.add(new Approval(lookup.mode(), model, SourceBindingCanonical.hash(graph), owners, lookup.nativeFieldPids()));
            }
            return new Binding(1, "sn" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "_notice", tenant.getId(), approvals,
                    new Source(automaticShipmentEntity(), "pid", "qo_pr_reminder_supplier_pid", "qo_pr_reminder_slot_at", automaticShipmentTarget()),
                    new Entity("pr_supplier_common", List.of(new Exact("pr_supplier_status", "enabled"))),
                    new Principal(new Entity("qo_procurement_membership_common", List.of(new Exact("qo_pr_membership_state", "active"))),
                            "qo_pr_membership_user_pid", "qo_pr_membership_supplier_pid", "qo_pr_membership_contact_pid"),
                    new Verification(new Entity("pr_supplier_pcba_contact", List.of(new Exact("qo_pr_contact_state", "active"))), "pr_spc_supplier_id"),
                    rule, namespace + "_shipment_template", Map.of());
        });
    }
    // END additive PF4J automatic fixture.
    // END additive controlled-tick setup.

    static void run(Services services, Path evidence) throws Exception {
        MetaContext.Snapshot previous = MetaContext.snapshot();
        try {
            new ProductQuoteReminderNativeFacet(services, evidence).run();
        } finally {
            MetaContext.clear();
            MetaContext.restore(previous);
        }
    }

    private void run() throws Exception {
        trace.put("namespace", namespace);
        trace.put("authority", "test-owned Product metadata registration and explicit synthetic users; never formal policy approval");
        trace.put("providerCalls", 0);
        try {
            JsonNode input = admission();
            loadCanonicalNativeOrg(input);
            stage = "product-handler-admission";
            Map<String, Boolean> handlers = new LinkedHashMap<>();
            for (String command : List.of("customer_pcba_int:seed_default_kingdee_connection",
                    "customer_pcba_int:sync_sales_orders", "customer_pcba_int:sync_supplier_purchase_orders",
                    "customer_pcba_int:sync_latest_purchase_prices")) {
                handlers.put(command, s.extensions.getCommandHandler(command).isPresent());
            }
            trace.put("requiredHandlerRegistration", handlers);
            assertThat(handlers.values()).containsOnly(true);
            stage = "product-formal-bootstrap-import";
            initializeTenant();
            importProduct(input);
            assertThat(s.protectedTenantIds).hasSize(2).doesNotContain(tenant.getId());
            protectedBaseline = protectedSnapshot();
            stage = "product-test-initialization-appendix";
            importInitializationAppendix(input);
            authenticateInitializer();
            stage = "product-native-initial-facts";
            String supplier = create("pr_supplier_common", "pr_supplier_pcba_contact:sync_directory", Map.of(
                    "pr_supplier_code", namespace, "pr_supplier_name", "Synthetic S06 supplier", "pr_supplier_status", "enabled"));
            String c1 = contact(supplier, "first");
            String c2 = contact(supplier, "second");
            membership(first, supplier, c1);
            membership(second, supplier, c2);
            String task = create("s2p_sourcing_event", "qo_procurement_task_common:create", Map.of(
                    "s2p_se_code", namespace, "s2p_se_title", "Synthetic S06 quotation", "s2p_se_event_type", "rfq",
                    "s2p_se_status", "bidding", "s2p_se_collaboration_managed", true));
            String quote = create("s2p_bid", "qo_procurement_task_common:dispatch", Map.of(
                    "s2p_bid_sourcing_event_id", task, "s2p_bid_supplier_id", supplier, "s2p_bid_status", "draft",
                    "s2p_bid_collaboration_managed", true, "qo_pr_quote_cutoff_at", SLOT.plusSeconds(3600)));
            bind();
            trace.put("initialFacts", Map.of("tenantId", tenant.getId(), "users", List.of(first.getPid(), second.getPid()),
                    "supplierPid", supplier, "contactPids", List.of(c1, c2), "taskPid", task, "quotePid", quote,
                    "seedPurpose", "native API-created synthetic initial facts; not verified phone, task dispatch or subscription acceptance"));
            stage = "product-actual-reminder-queue";
            Path jar = Path.of(input.path("productJarPath").asText()).toRealPath();
            try (var loader = new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()}, getClass().getClassLoader())) {
                Class<?> service = loader.loadClass("com.auraboot.quote.engine.procurement.ProcurementReminderService");
                assertThat(service.getClassLoader()).isSameAs(loader);
                assertThat(Files.isSameFile(Path.of(service.getProtectionDomain().getCodeSource().getLocation().toURI()), jar)).isTrue();
                DataAccessor actualSchedulerData = actualSchedulerData(loader, jar, tenant.getId(), s.background);
                Class<?> adapter = actualSchedulerData.getClass();
                trace.put("reminderQueueAdapter", Map.of("adapterClass", adapter.getName(),
                        "sameActualProductJarAndLoader", true, "backgroundBeanClass", s.background.getClass().getName(),
                        "actualAutowiredBackgroundCapability", true, "nativeTickExecuted", false));
                var method = service.getMethod("queue", DataAccessor.class, Instant.class, List.class);
                Map<String, Object> supplierRecord = nativeRecord("pr_supplier_common", supplier);
                assertThat(queue(method, actualSchedulerData, List.of(supplierRecord))).isEqualTo(1);
                assertThat(queue(method, actualSchedulerData, List.of(supplierRecord))).isZero();
            }
            var sources = s.jdbc.queryForList("SELECT pid,qo_pr_reminder_targets FROM \"" + tables.get("qo_procurement_reminder_common") + "\" WHERE tenant_id=? ORDER BY pid", tenant.getId());
            assertThat(sources).hasSize(1);
            sourcePid = (String) sources.getFirst().get("pid");
            Map<String, Object> reminder = nativeRecord("qo_procurement_reminder_common", sourcePid);
            assertThat(reminder.get("qo_pr_reminder_kind")).isEqualTo("quote");
            assertThat(reminder.get("qo_pr_reminder_state")).isEqualTo("pending");
            Object targets = reminder.get("qo_pr_reminder_targets");
            JsonNode targetList = targets instanceof String text ? s.json.readTree(text) : s.json.valueToTree(targets);
            assertThat(targetList.isArray()).isTrue();
            assertThat(targetList.size()).isEqualTo(1);
            assertThat(targetList.get(0).asText()).isEqualTo(quote);
            trace.put("reminderSourcePid", sourcePid);
            stage = "product-test-only-registry";
            long rule = ruleAndTemplate();
            binding = approval(rule);
            var properties = new HostSourceBindingProperties();
            properties.setEnabled(true);
            properties.setBindings(List.of(binding));
            var planner = new RegisteredSourcePlanResolver(properties, s.reader, s.validator, clock);
            planner.initialize();
            var host = new PluginNotificationDeliveryAccessorImpl(List.of(planner), s.reliable, s.writer);
            ReflectionTestUtils.setField(host, "enabled", true);
            var worker = new RegisteredSourceWorker(planner, s.reader, host, s.manager);
            trace.put("actualTestOwnedBinding", binding);
            Map<String, Object> beforeBusiness = business();
            stage = "product-business";
            assertThat(worker.process(tenant.getId(), binding.code(), sourcePid)).isEqualTo(2);
            List<IntegrationEventEnvelope> events = events();
            assertThat(events).hasSize(2);
            assertThat(worker.process(tenant.getId(), binding.code(), sourcePid)).isEqualTo(2);
            assertThat(events()).isEqualTo(events);
            clock.now = SLOT.plusSeconds(3600);
            assertThatThrownBy(() -> worker.process(tenant.getId(), binding.code(), sourcePid)).isInstanceOf(IllegalArgumentException.class);
            for (var event : events) {
                assertThatThrownBy(() -> s.executor.deliver(event, host, UniqueIdGenerator.generate())).isInstanceOf(IllegalArgumentException.class);
            }
            assertThat(count("ab_notification")).isZero();
            assertThat(count("ab_integration_receipt")).isZero();
            assertThat(events()).isEqualTo(events);
            clock.now = SLOT;
            for (var event : events) s.executor.deliver(event, host, UniqueIdGenerator.generate());
            var inbox = s.jdbc.queryForList("SELECT to_jsonb(r)::text AS row FROM ab_notification r WHERE tenant_id=? ORDER BY id", tenant.getId());
            assertThat(inbox).hasSize(2);
            assertThat(s.jdbc.queryForList("SELECT user_id FROM ab_notification WHERE tenant_id=? ORDER BY user_id", Long.class, tenant.getId()))
                    .containsExactlyInAnyOrder(first.getId(), second.getId());
            assertThat(count("ab_integration_receipt")).isEqualTo(2);
            for (var event : events) s.executor.deliver(event, host, UniqueIdGenerator.generate());
            assertThat(s.jdbc.queryForList("SELECT to_jsonb(r)::text AS row FROM ab_notification r WHERE tenant_id=? ORDER BY id", tenant.getId())).isEqualTo(inbox);
            assertThat(count("ab_integration_receipt")).isEqualTo(2);
            assertThat(business()).isEqualTo(beforeBusiness);
            assertThat(protectedSnapshot()).isEqualTo(protectedBaseline);
            trace.put("protectedTenantsUnchanged", true);
            trace.put("protectedBaselineSha256", SourceBindingCanonical.hash(protectedBaseline));
            trace.put("businessSnapshotSha256", SourceBindingCanonical.hash(beforeBusiness));
            trace.put("inboxRows", inbox);
            trace.put("checkpoints", List.of("actual ReminderService same-slot one fact", "complete actual Product metadata approval",
                    "two explicit test users with supplier/contact membership", "same envelope replay", "deadline delayed consume zero inbox/receipt",
                    "two committed inbox/receipt pairs and duplicate suppression", "Product business facts unchanged"));
            completed = true;
            stage = "product-business-completed";
        } finally {
            trace.put("stage", stage);
            trace.put("completed", completed);
            List<JsonNode> rows = tenant == null ? List.of() : s.jdbc.queryForList(
                    "SELECT to_jsonb(r)::text FROM ab_outbox r WHERE tenant_id=? ORDER BY id", String.class, tenant.getId())
                    .stream().map(SourceBindingCanonical::parse).toList();
            trace.put("ownedOutboxRowsBeforeTeardown", rows);
            if (tables.size() == BUSINESS_MODELS.size()) {
                trace.put("actualBusinessRowsAtExit", business());
                trace.put("ownedInboxCountAtExit", count("ab_notification"));
                trace.put("ownedReceiptCountAtExit", count("ab_integration_receipt"));
            }
            Path sealed = evidence.resolve(namespace + ".json");
            Files.writeString(sealed, s.json.writerWithDefaultPrettyPrinter().writeValueAsString(trace), StandardOpenOption.CREATE_NEW);
            if (completed) {
                Map<String, Object> business = business();
                TestOwnedNotificationIntentTeardown.run(s.dataSource,
                        new TestOwnedNotificationIntentTeardown.Scope(tenant.getId(), binding.code(), sourcePid,
                                Set.of(SourceBindingCanonical.reference(binding.code(), sourcePid, first.getId()),
                                        SourceBindingCanonical.reference(binding.code(), sourcePid, second.getId()))),
                        rows, sealed, evidence.resolve(namespace + "-teardown.json"));
                assertThat(business()).isEqualTo(business);
                assertThat(protectedSnapshot()).isEqualTo(protectedBaseline);
            }
        }
    }

    private JsonNode admission() throws Exception {
        Path file = Path.of(required("S06_NATIVE_PRODUCT_INPUT")).toRealPath();
        assertThat(sha(file)).isEqualTo(required("S06_NATIVE_PRODUCT_INPUT_SHA256"));
        JsonNode input = s.json.readTree(file.toFile());
        assertThat(input.path("runtime").asText()).isEqualTo(NativeProcurementWorkspace.runtime());
        assertThat(input.path("database").asText()).isEqualTo("enterprise_22");
        assertThat(input.path("redisDatabase").asInt()).isEqualTo(13);
        assertThat(input.path("orderedImportDirectories").isArray()).isTrue();
        assertThat(input.path("orderedImportDirectories").size()).isEqualTo(23);
        JsonNode identities = input.path("verifiedSourceIdentities");
        assertThat(identities.size()).isEqualTo(5);
        List<Path> roots = new ArrayList<>();
        for (String component : List.of("oss", "quote", "enterprise", "crm", "plugins")) {
            JsonNode identity = identities.path(component);
            Path root = Path.of(identity.path("root").asText()).toRealPath();
            assertThat(identity.path("status").asText()).isEqualTo("ok");
            assertThat(identity.path("expectedCommit").asText()).matches("[a-f0-9]{40}");
            assertThat(identity.path("actualCommit").asText()).isEqualTo(identity.path("expectedCommit").asText());
            assertThat(identity.path("expectedDirtyFingerprint").asText()).isEqualTo("clean");
            assertThat(identity.path("actualDirtyFingerprint").asText()).isEqualTo("clean");
            assertThat(git(root, "rev-parse", "HEAD")).isEqualTo(identity.path("actualCommit").asText());
            assertThat(git(root, "status", "--porcelain")).isEmpty();
            roots.add(root);
        }
        Set<Path> seen = new LinkedHashSet<>();
        boolean productPresent = false;
        for (JsonNode row : input.path("orderedImportDirectories")) {
            Path directory = Path.of(row.path("path").asText()).toRealPath();
            assertThat(roots.stream().anyMatch(directory::startsWith)).isTrue();
            assertThat(seen.add(directory)).isTrue();
            assertThat(sha(directory.resolve("plugin.json"))).isEqualTo(row.path("pluginJsonSha256").asText());
            JsonNode manifest = s.json.readTree(directory.resolve("plugin.json").toFile());
            if ("com.auraboot.quote-procurement".equals(manifest.path("pluginId").asText())) productPresent = true;
            Set<String> expectedFiles = new LinkedHashSet<>();
            for (JsonNode config : row.path("configFiles")) {
                String relative = config.path("relativePath").asText();
                Path actual = directory.resolve(relative).toRealPath();
                assertThat(actual.startsWith(directory.resolve("config").toRealPath())).isTrue();
                assertThat(expectedFiles.add(relative)).isTrue();
                assertThat(sha(actual)).isEqualTo(config.path("sha256").asText());
            }
            Set<String> actualFiles = new LinkedHashSet<>();
            try (var files = Files.walk(directory.resolve("config"))) {
                files.filter(Files::isRegularFile).forEach(p -> actualFiles.add(directory.relativize(p).toString()));
            }
            assertThat(actualFiles).isEqualTo(expectedFiles);
        }
        assertThat(productPresent).isTrue();
        Path jar = Path.of(input.path("productJarPath").asText()).toRealPath();
        assertThat(sha(jar)).isEqualTo(input.path("productJarSha256").asText());
        assertThat(input.path("productJarSha256").asText()).matches("[a-f0-9]{64}");
        trace.put("inputSha256", sha(file));
        trace.put("sealedSourceIdentities", identities);
        trace.put("orderedImportDirectories", input.path("orderedImportDirectories"));
        trace.put("productJarPath", jar.toString());
        trace.put("productJarSha256", sha(jar));
        return input;
    }

    private void initializeTenant() {
        tenant = new Tenant();
        tenant.setPid(UniqueIdGenerator.generate());
        tenant.setName(namespace + "-product");
        tenant.setDisplayName(namespace + "-product");
        tenant.setStatus("active");
        tenant = s.tenants.createTenant(tenant);
        first = user("first");
        second = user("second");
        initializer = user("bootstrap");
        bind();
        assertThat(s.bootstrap.bootstrapTenant(tenant.getId(), initializer.getId()).isSuccess()).isTrue();
        bind();
    }

    private User user(String name) {
        User user = s.users.signUp(namespace + "-" + name + "@example.invalid", "S06-local-synthetic-password-123",
                "Synthetic Product S06 " + name, namespace + "_" + name);
        s.members.addMember(user.getId(), tenant.getId(), "active");
        return user;
    }

    private void importProduct(JsonNode input) {
        int ordinal = 0;
        List<Map<String, Object>> diagnostics = new ArrayList<>();
        trace.put("formalImportDiagnostics", diagnostics);
        for (JsonNode row : input.path("orderedImportDirectories")) {
            Map<String, Object> current = new LinkedHashMap<>();
            current.put("ordinal", ++ordinal);
            current.put("directory", row.path("path").asText());
            diagnostics.add(current);
            assertThat(row.path("path").asText().length()).isLessThanOrEqualTo(256);
            var preview = s.imports.parseDirectory(row.path("path").asText(), true);
            current.put("previewValid", preview.isValid());
            current.put("previewErrors", preview.getErrors());
            current.put("importId", preview.getImportId());
            assertThat(preview.isValid()).as("Actual Product preview ordinal %s: %s", ordinal, preview.getErrors()).isTrue();
            var request = ImportRequest.builder().importId(preview.getImportId())
                    .conflictStrategy(ImportRequest.ConflictStrategy.OVERWRITE).autoPublishFields(true)
                    .autoPublishModels(true).autoPublishCommands(true).autoPublishPages(true).createResourcePermissions(true).build();
            var result = s.imports.execute(preview.getImportId(), request);
            current.put("executeSuccess", result.isSuccess());
            current.put("executeError", result.getErrorMessage());
            assertThat(result.isSuccess()).as("Actual Product import ordinal %s: %s", ordinal, result.getErrorMessage()).isTrue();
        }
        assertThat(s.imports.verifyImportReferenceIntegrity()).isEmpty();
        trace.put("formalImportedDirectories", 23);
        stage = "product-physical-model-admission";
        List<Map<String, Object>> physicalModels = new ArrayList<>();
        trace.put("actualPhysicalModelDiagnostics", physicalModels);
        for (String model : BUSINESS_MODELS) {
            var registered = s.jdbc.queryForMap("SELECT id,code,tenant_id,plugin_pid,table_name,status FROM ab_meta_model WHERE tenant_id=? AND code=? AND is_current=TRUE AND deleted_flag=FALSE", tenant.getId(), model);
            var owner = s.jdbc.queryForMap("SELECT pid,tenant_id FROM ab_plugin WHERE tenant_id=? AND pid=? AND deleted_flag=FALSE",
                    tenant.getId(), registered.get("plugin_pid"));
            var definition = s.metadata.getModelDefinitionFromDb(model);
            String table = s.metadata.getTableName(model);
            String actualTable = s.jdbc.queryForObject("SELECT to_regclass(?)::text", String.class, table);
            Map<String, Object> physical = new LinkedHashMap<>();
            physical.put("registered", registered);
            physical.put("owner", owner);
            physical.put("projectedTable", table);
            physical.put("actualTable", actualTable);
            physicalModels.add(physical);
            verifyImportedPhysicalModel(registered, owner, definition, table, actualTable, tenant.getId(), model);
            tables.put(model, table);
        }
        trace.put("formalImportedDirectories", 23);
        trace.put("actualRegisteredTables", tables);
    }

    /** Validates the canonical service projection, not a made-up physical name from nullable authoring storage. */
    static void verifyImportedPhysicalModel(Map<String, Object> registered, Map<String, Object> owner,
                                           Optional<ModelDefinition> definition, String projectedTable,
                                           String actualTable, Long tenantId, String model) {
        assertThat(registered.get("status")).isEqualTo("published");
        assertThat(registered.get("code")).isEqualTo(model);
        assertThat(registered.get("tenant_id")).isEqualTo(tenantId);
        assertThat(registered.get("plugin_pid")).isInstanceOf(String.class);
        assertThat((String) registered.get("plugin_pid")).isNotBlank();
        assertThat(owner.get("pid")).isEqualTo(registered.get("plugin_pid"));
        assertThat(owner.get("tenant_id")).isEqualTo(tenantId);
        assertThat(definition).isPresent();
        assertThat(definition.orElseThrow().getId()).isEqualTo(registered.get("id"));
        assertThat(definition.orElseThrow().getCode()).isEqualTo(model);
        assertThat(definition.orElseThrow().getStatus()).isEqualTo("published");
        assertThat(projectedTable).isEqualTo(SystemFieldConstants.generateTableName(model));
        assertThat(definition.orElseThrow().getTableName()).isEqualTo(projectedTable);
        assertThat(actualTable).isEqualTo(projectedTable);
    }

    private String contact(String supplier, String label) {
        return create("pr_supplier_pcba_contact", "pr_supplier_pcba_contact:sync_directory", Map.of(
                "pr_spc_supplier_id", supplier, "pr_spc_contact_name", "Synthetic " + label, "qo_pr_contact_state", "active"));
    }

    private void membership(User user, String supplier, String contact) {
        create("qo_procurement_membership_common", "qo_procurement_membership_common:bind_verified_phone", Map.of(
                "qo_pr_membership_key", namespace + "_" + user.getPid(), "qo_pr_membership_user_pid", user.getPid(),
                "qo_pr_membership_supplier_pid", supplier, "qo_pr_membership_contact_pid", contact,
                "qo_pr_membership_state", "active"));
    }

    private void importInitializationAppendix(JsonNode input) throws Exception {
        List<Path> productDirectories = new ArrayList<>();
        List<Path> ownerDirectories = new ArrayList<>();
        for (JsonNode directory : input.path("orderedImportDirectories")) {
            Path path = Path.of(directory.path("path").asText()).toRealPath();
            if (s.json.readTree(path.resolve("plugin.json").toFile()).path("pluginId").asText()
                    .equals("com.auraboot.quote-procurement")) productDirectories.add(path);
            if (s.json.readTree(path.resolve("plugin.json").toFile()).path("pluginId").asText()
                    .equals("com.auraboot.source-to-pay")) ownerDirectories.add(path);
        }
        assertThat(productDirectories).hasSize(1);
        assertThat(ownerDirectories).hasSize(1);
        Map<String, Object> ownerAppendix = ProductQuoteNativeInitialization.prepareSourceToPay(ownerDirectories.getFirst(),
                evidence.resolve("source-to-pay-initialization-appendix"), s.json);
        trace.put("sourceToPayInitializationAppendix", ownerAppendix);
        var ownerPreview = s.imports.parseDirectory((String) ownerAppendix.get("testDirectory"), true);
        trace.put("sourceToPayInitializationPreviewErrors", ownerPreview.getErrors());
        assertThat(ownerPreview.isValid()).as("Exact source owner appendix: %s", ownerPreview.getErrors()).isTrue();
        var ownerRequest = ImportRequest.builder().importId(ownerPreview.getImportId())
                .conflictStrategy(ImportRequest.ConflictStrategy.OVERWRITE).autoPublishFields(true)
                .autoPublishModels(true).autoPublishCommands(true).autoPublishPages(true)
                .createResourcePermissions(true).build();
        var ownerResult = s.imports.execute(ownerPreview.getImportId(), ownerRequest);
        trace.put("sourceToPayInitializationImportId", ownerPreview.getImportId());
        trace.put("sourceToPayInitializationImportSuccess", ownerResult.isSuccess());
        assertThat(ownerResult.isSuccess()).as("Exact source owner import: %s", ownerResult.getErrorMessage()).isTrue();
        Map<String, Object> appendix = ProductQuoteNativeInitialization.prepare(productDirectories.getFirst(),
                evidence.resolve("initialization-appendix"), s.json);
        trace.put("testInitializationAppendix", appendix);
        var preview = s.imports.parseDirectory((String) appendix.get("testDirectory"), true);
        trace.put("initializationPreviewErrors", preview.getErrors());
        assertThat(preview.isValid()).as("Explicit test-only initialization appendix: %s", preview.getErrors()).isTrue();
        var request = ImportRequest.builder().importId(preview.getImportId())
                .conflictStrategy(ImportRequest.ConflictStrategy.OVERWRITE).autoPublishFields(true)
                .autoPublishModels(true).autoPublishCommands(true).autoPublishPages(true)
                .createResourcePermissions(true).build();
        var result = s.imports.execute(preview.getImportId(), request);
        trace.put("initializationImportId", preview.getImportId());
        trace.put("initializationImportSuccess", result.isSuccess());
        assertThat(result.isSuccess()).as("Explicit test-only initialization import: %s", result.getErrorMessage()).isTrue();
        ObjectNode expectedWriters = s.json.valueToTree(appendix.get("exactWriterAppendix"));
        expectedWriters.setAll((ObjectNode) s.json.valueToTree(ownerAppendix.get("exactWriterAppendix")));
        assertThat(expectedWriters.size()).isEqualTo(9);
        JsonNode expectedPolicies = s.json.valueToTree(ownerAppendix.get("exactModelAppendix"));
        for (String model : ProductQuoteNativeInitialization.FIELDS.keySet()) {
            var definition = s.metadata.getModelDefinitionFromDb(model).orElseThrow();
            if (expectedPolicies.has(model)) {
                assertThat((JsonNode) s.json.valueToTree(definition.getExtension().get("recordCommandWriters")))
                        .isEqualTo(expectedPolicies.path(model).path("testPolicy"));
            }
            for (String code : ProductQuoteNativeInitialization.FIELDS.get(model)) {
                var field = definition.getFields().stream().filter(f -> code.equals(f.getCode())).findFirst().orElseThrow();
                if (expectedWriters.has(code)) {
                    assertThat((JsonNode) s.json.valueToTree(field.getAllowedWriterCommands()))
                            .isEqualTo(expectedWriters.path(code).path("testWriters"));
                }
            }
        }
        trace.put("actualImportedInitializationWritersVerified", true);
        trace.put("actualImportedSourceOwnerPoliciesVerified", true);
        trace.put("actualImportedInitializationProtectedFields", 9);
    }

    private void authenticateInitializer() {
        MetaContext.setContext(tenant.getId(), initializer.getId(), initializer.getPid(), namespace);
        var member = s.members.findByTenantIdAndUserId(tenant.getId(), initializer.getId());
        assertThat(member).isNotNull();
        MetaContext.setMemberId(member.getId());
        var request = new AuthenticationRequest();
        request.setEmail(initializer.getEmail());
        request.setPassword("S06-local-synthetic-password-123");
        var authenticated = s.authentication.authenticate(request);
        assertThat(authenticated.getTenantId()).isEqualTo(tenant.getId());
        assertThat(authenticated.getUserId()).isEqualTo(initializer.getId());
        assertThat(authenticated.getJwt()).isNotBlank();
        trace.put("testInitializer", Map.of("userPid", initializer.getPid(), "memberId", member.getId(),
                "authenticationVerified", true, "authority", "actual bootstrap initializer in this synthetic tenant only"));
    }

    private String create(String model, String productionCommand, Map<String, Object> facts) {
        ProductQuoteNativeInitialization.validatePayload(model, facts);
        var request = new CommandExecuteRequest();
        request.setPayload(new LinkedHashMap<>(facts));
        request.setClientRequestId(namespace + "-" + UUID.randomUUID());
        var result = s.commands.execute(ProductQuoteNativeInitialization.command(model), request);
        assertThat(result.getPhaseReached()).isEqualTo("completed");
        assertThat(result.getData().get("recordPid")).isInstanceOf(String.class);
        String pid = (String) result.getData().get("recordPid");
        assertThat(pid).isNotBlank();
        trace.computeIfAbsent("initializationCommandResults", ignored -> new ArrayList<Map<String, Object>>());
        @SuppressWarnings("unchecked")
        var records = (List<Map<String, Object>>) trace.get("initializationCommandResults");
        records.add(Map.of("model", model, "command", ProductQuoteNativeInitialization.command(model),
                "productionCommandNotImpersonated", productionCommand, "recordPid", pid, "phase", result.getPhaseReached()));
        return pid;
    }

    private Map<String, Object> nativeRecord(String model, String pid) {
        return MetaContext.runWithCommandPermitScope("ALL", () -> s.data.getById(model, pid));
    }

    /** Instantiate only the actual artifact's scheduler adapter; never substitute request-side access. */
    static DataAccessor actualSchedulerData(URLClassLoader loader, Path jar, long tenant,
                                           BackgroundDataAccessor background) throws Exception {
        if (loader == null || jar == null || tenant <= 0 || background == null) {
            throw new IllegalArgumentException("Actual Product loader, tenant and Spring background bean required");
        }
        assertThat(background.transactionResourceId()).isNotBlank();
        Class<?> adapter = loader.loadClass("com.auraboot.quote.engine.procurement.ProcurementReminderScheduler$TenantData");
        assertThat(adapter.getClassLoader()).isSameAs(loader);
        assertThat(Files.isSameFile(Path.of(adapter.getProtectionDomain().getCodeSource().getLocation().toURI()), jar)).isTrue();
        assertThat(DataAccessor.class.isAssignableFrom(adapter)).isTrue();
        var constructor = adapter.getDeclaredConstructor(long.class, BackgroundDataAccessor.class);
        constructor.setAccessible(true);
        return (DataAccessor) constructor.newInstance(tenant, background);
    }

    private int queue(java.lang.reflect.Method method, DataAccessor actualSchedulerData, List<Map<String, Object>> suppliers) throws Exception {
        return tx(() -> MetaContext.runWithCommandPermitScope("ALL", () -> {
            try {
                return (Integer) method.invoke(null, actualSchedulerData, SLOT, suppliers);
            } catch (InvocationTargetException error) {
                // CATCH: unwrap the actual Product business failure, never fall back to synthetic queue data.
                if (error.getCause() instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("Product queue invocation failed", error.getCause());
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("Product queue method contract unavailable", error);
            }
        }));
    }

    static Entity reminderSourceEntity() {
        return new Entity("qo_procurement_reminder_common", List.of(new Exact("qo_pr_reminder_kind", "quote"), new Exact("qo_pr_reminder_state", "pending")));
    }
    static String reminderRuleConditionFilter() {
        return SourceBindingCanonical.tree(reminderSourceEntity().equals().stream().map(predicate->
                Map.of("fieldName",predicate.fieldCode(),"operator","EQ","value",predicate.value().raw())).toList()).toString();
    }

    private long ruleAndTemplate() {
        String code = namespace + "_template";
        var template = new NotificationTemplateCreateRequest();
        template.setCode(code);
        template.setName("Synthetic Product source template");
        template.setChannel("IN_APP");
        template.setEnabled(true);
        template.setSubjectTemplate("Synthetic Product quotation reminder");
        template.setBodyTemplate("Synthetic Product pending quotation facts");
        s.templates.create(template);
        var rule = new NotificationRuleRequest();
        rule.setCode(namespace + "_rule");
        rule.setName("Synthetic Product explicit recipients");
        rule.setEnabled(true);
        rule.setTriggerType("EVENT");
        rule.setTriggerConfig("{\"modelCode\":\"qo_procurement_reminder_common\",\"event\":\"created\"}");
        rule.setConditionModelCode("qo_procurement_reminder_common");
        rule.setConditionFilter(reminderRuleConditionFilter());
        rule.setActionChannel("IN_APP");
        rule.setActionTemplateCode(code);
        rule.setRecipientType("SPECIFIC_USERS");
        rule.setRecipientField(first.getId() + "," + second.getId());
        return s.rules.createRule(rule).getId();
    }

    private void loadCanonicalNativeOrg(JsonNode input) throws Exception {
        List<Path> directories = new ArrayList<>();
        for (JsonNode row : input.path("orderedImportDirectories")) {
            Path path = Path.of(row.path("path").asText()).toRealPath();
            if ("com.auraboot.org-management".equals(s.json.readTree(path.resolve("plugin.json").toFile()).path("pluginId").asText()))
                directories.add(path);
        }
        assertThat(directories).hasSize(1);
        Path org = directories.getFirst();
        canonicalOrgManifest = s.json.readTree(org.resolve("plugin.json").toFile());
        assertThat(canonicalOrgManifest.path("namespace").asText()).isEqualTo("org");
        assertThat(canonicalOrgManifest.path("version").asText()).isNotBlank();
        canonicalOrgFields = new LinkedHashMap<>();
        var hashes = new LinkedHashMap<String, String>();
        for (String code : List.of("created_at", "updated_at")) {
            Path path = org.resolve("config/fields/" + code + ".json");
            JsonNode field = s.json.readTree(path.toFile());
            assertThat(field.path("code").asText()).isEqualTo(code);
            assertThat(field.path("dataType").asText()).isEqualTo("datetime");
            canonicalOrgFields.put(code, field);
            hashes.put(code, sha(path));
        }
        trace.put("canonicalSystemFieldContribution", Map.of("directory", org.toString(),
                "pluginJsonSha256", sha(org.resolve("plugin.json")), "fieldSha256", hashes,
                "source", "exact already-sealed formal import directory; test-owned discovery only"));
    }

    /** Test-owned discovery for actual auto-bound infrastructure, never synthetic tracking or a Main exception. */
    static Set<String> nativeSystemFieldPids(List<Map<String, Object>> rows, long tenantId,
                                           JsonNode orgManifest, Map<String, JsonNode> orgFields) {
        assertThat(orgManifest.path("pluginId").asText()).isEqualTo("com.auraboot.org-management");
        assertThat(orgManifest.path("namespace").asText()).isEqualTo("org");
        assertThat(orgManifest.path("version").asText()).isNotBlank();
        assertThat(orgFields.keySet()).containsExactlyInAnyOrder("created_at", "updated_at");
        var result = new LinkedHashSet<String>();
        for (var row : rows) {
            assertThat(row.get("is_system_binding")).isEqualTo(Boolean.TRUE);
            assertThat(row.get("editable")).isEqualTo(Boolean.FALSE);
            assertThat(row.get("binding_tenant_id")).isEqualTo(tenantId);
            assertThat(row.get("field_tenant_id")).isEqualTo(tenantId);
            String code = (String) row.get("code");
            assertThat(SystemFieldConstants.AUTO_BIND).contains(code);
            assertThat(row.get("alias_code") == null || code.equals(row.get("alias_code"))).isTrue();
            if (row.get("field_plugin_pid") == null) {
                String type = switch (code) { case "id" -> "bigint"; case "pid" -> "string"; default -> "datetime"; };
                assertThat(row.get("data_type")).isEqualTo(type);
            } else {
                // Only these two actual canonical org fields are auto-bound without import tracking.
                assertThat(orgFields).containsKey(code);
                JsonNode field = orgFields.get(code);
                assertThat(field.path("code").asText()).isEqualTo(code);
                assertThat(field.path("dataType").asText()).isEqualTo("datetime");
                assertThat(row.get("data_type")).isEqualTo(field.path("dataType").asText());
                assertThat(row.get("plugin_tenant_id")).isEqualTo(tenantId);
                assertThat(row.get("plugin_pid")).isEqualTo(row.get("field_plugin_pid"));
                assertThat(row.get("plugin_id")).isEqualTo(orgManifest.path("pluginId").asText());
                assertThat(row.get("namespace")).isEqualTo(orgManifest.path("namespace").asText());
                assertThat(row.get("version")).isEqualTo(orgManifest.path("version").asText());
            }
            assertThat(row.get("pid")).isInstanceOf(String.class);
            String pid = (String) row.get("pid");
            assertThat(pid).isNotBlank();
            assertThat(result.add(pid)).isTrue();
        }
        return Set.copyOf(result);
    }

    private Binding approval(long rule) {
        return tx(() -> {
            List<Approval> approvals = new ArrayList<>();
            for (String model : MODELS) {
                // Only actual contributors of this imported model/binding/field graph are explicitly approved in this test.
                List<String> contributors = s.jdbc.queryForList("""
                        SELECT DISTINCT contributor FROM (
                          SELECT m.plugin_pid AS contributor FROM ab_meta_model m WHERE m.tenant_id=? AND m.code=? AND m.is_current=TRUE AND m.deleted_flag=FALSE
                          UNION ALL
                          SELECT f.plugin_pid FROM ab_meta_model m JOIN ab_meta_model_field_binding b ON b.model_id=m.id JOIN ab_meta_field f ON f.id=b.field_id
                            WHERE m.tenant_id=? AND m.code=? AND m.is_current=TRUE AND m.deleted_flag=FALSE AND b.tenant_id=? AND b.deleted_flag=FALSE
                          UNION ALL
                          SELECT r.plugin_pid FROM ab_meta_model m JOIN ab_meta_model_field_binding b ON b.model_id=m.id JOIN ab_plugin_resource r ON r.resource_pid=b.pid
                            WHERE m.tenant_id=? AND m.code=? AND m.is_current=TRUE AND m.deleted_flag=FALSE AND b.tenant_id=? AND b.deleted_flag=FALSE
                              AND r.tenant_id=? AND r.resource_type='model_field_binding'
                        ) actual WHERE contributor IS NOT NULL ORDER BY contributor
                        """, String.class, tenant.getId(), model, tenant.getId(), model, tenant.getId(),
                        tenant.getId(), model, tenant.getId(), tenant.getId());
                assertThat(contributors).isNotEmpty();
                List<Owner> owners = new ArrayList<>();
                for (String pid : contributors) {
                    var plugin = s.jdbc.queryForMap("SELECT pid,plugin_id,namespace,version FROM ab_plugin WHERE tenant_id=? AND pid=? AND deleted_flag=FALSE", tenant.getId(), pid);
                    owners.add(new Owner(pid, (String) plugin.get("plugin_id"), (String) plugin.get("namespace"), (String) plugin.get("version")));
                }
                List<Map<String, Object>> systemBindings = s.jdbc.queryForList("""
                        SELECT f.pid,f.code,f.data_type,f.plugin_pid AS field_plugin_pid,
                               f.tenant_id AS field_tenant_id,b.tenant_id AS binding_tenant_id,
                               b.is_system_binding,b.editable,b.alias_code,p.pid AS plugin_pid,
                               p.tenant_id AS plugin_tenant_id,p.plugin_id,p.namespace,p.version
                        FROM ab_meta_model m JOIN ab_meta_model_field_binding b ON b.model_id=m.id
                          JOIN ab_meta_field f ON f.id=b.field_id
                          LEFT JOIN ab_plugin p ON p.pid=f.plugin_pid AND p.tenant_id=? AND p.deleted_flag=FALSE
                        WHERE m.tenant_id=? AND m.code=? AND m.is_current=TRUE AND m.deleted_flag=FALSE
                          AND b.tenant_id=? AND b.deleted_flag=FALSE AND b.is_system_binding=TRUE
                          AND f.tenant_id=? AND f.deleted_flag=FALSE AND f.is_current=TRUE
                        """, tenant.getId(), tenant.getId(), model, tenant.getId(), tenant.getId());
                Set<String> nativeFields = nativeSystemFieldPids(systemBindings, tenant.getId(), canonicalOrgManifest, canonicalOrgFields);
                trace.put("nativeSystemFieldPids:" + model, nativeFields);
                Approval lookup = new Approval("METADATA_SHAPE_V1", model, "0".repeat(64), owners, Set.copyOf(nativeFields));
                JsonNode graph = s.reader.metadata(tenant.getId(), lookup);
                approvals.add(new Approval(lookup.mode(), model, SourceBindingCanonical.hash(graph), owners, lookup.nativeFieldPids()));
            }
            return new Binding(1, namespace + "_notice", tenant.getId(), approvals,
                    new Source(reminderSourceEntity(),
                            "pid", "qo_pr_reminder_supplier_pid", "qo_pr_reminder_slot_at",
                            new Target("qo_pr_reminder_targets", new Entity("s2p_bid", List.of(new Exact("s2p_bid_collaboration_managed", true), new Exact("s2p_bid_status", "draft"))),
                                    "s2p_bid_supplier_id", new Deadline("qo_pr_quote_cutoff_at", DeadlineRelation.AFTER_HOST_NOW))),
                    new Entity("pr_supplier_common", List.of(new Exact("pr_supplier_status", "enabled"))),
                    new Principal(new Entity("qo_procurement_membership_common", List.of(new Exact("qo_pr_membership_state", "active"))),
                            "qo_pr_membership_user_pid", "qo_pr_membership_supplier_pid", "qo_pr_membership_contact_pid"),
                    new Verification(new Entity("pr_supplier_pcba_contact", List.of(new Exact("qo_pr_contact_state", "active"))), "pr_spc_supplier_id"),
                    rule, namespace + "_template", Map.of());
        });
    }

    private Map<String, Object> business() {
        Map<String, Object> rows = new LinkedHashMap<>();
        for (String model : BUSINESS_MODELS) {
            rows.put(model, s.jdbc.queryForList("SELECT to_jsonb(r)::text FROM \"" + tables.get(model) + "\" r WHERE tenant_id=? ORDER BY pid", String.class, tenant.getId()));
        }
        return rows;
    }

    private Map<String, Object> protectedSnapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (long protectedTenant : s.protectedTenantIds) {
            Map<String, Object> rows = new LinkedHashMap<>();
            for (String model : BUSINESS_MODELS) {
                rows.put(model, s.jdbc.queryForList("SELECT to_jsonb(r)::text FROM \"" + tables.get(model)
                        + "\" r WHERE tenant_id=? ORDER BY pid", String.class, protectedTenant));
            }
            for (String table : List.of("ab_notification", "ab_integration_receipt", "ab_outbox")) {
                rows.put(table, s.jdbc.queryForList("SELECT to_jsonb(r)::text FROM " + table
                        + " r WHERE tenant_id=? ORDER BY id", String.class, protectedTenant));
            }
            result.put(Long.toString(protectedTenant), rows);
        }
        return result;
    }

    private List<IntegrationEventEnvelope> events() {
        return s.jdbc.query("SELECT * FROM ab_outbox WHERE tenant_id=? ORDER BY event_id", (rs, n) -> {
            try {
                return new IntegrationEventEnvelope(rs.getString("schema_version"), rs.getString("event_id"),
                        rs.getString("event_type"), rs.getString("event_source"), rs.getString("subject"),
                        rs.getTimestamp("occurred_at").toInstant(), rs.getLong("tenant_id"),
                        rs.getString("correlation_id"), rs.getString("causation_id"), rs.getString("ordering_key"),
                        rs.getLong("event_sequence"), s.json.readValue(rs.getString("payload"), new TypeReference<Map<String, Object>>() {}),
                        s.json.readValue(rs.getString("headers"), new TypeReference<Map<String, String>>() {}));
            } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
                // CATCH: persisted envelope decoding failure is a real fixture failure, never a substituted envelope.
                throw new IllegalStateException("Invalid persisted Product notification envelope", error);
            }
        }, tenant.getId());
    }

    private int count(String table) {
        assertThat(Set.of("ab_notification", "ab_integration_receipt").contains(table)).isTrue();
        return s.jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id=?", Integer.class, tenant.getId());
    }

    private <T> T tx(Supplier<T> work) {
        TransactionTemplate transaction = new TransactionTemplate(s.manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        return transaction.execute(status -> work.get());
    }

    private void bind() {
        MetaContext.setContext(tenant.getId(), first.getId(), first.getPid(), "synthetic-product-notification");
        var member = s.members.findByTenantIdAndUserId(tenant.getId(), first.getId());
        assertThat(member).isNotNull();
        MetaContext.setMemberId(member.getId());
    }
    private static String required(String name) {
        String value = System.getenv(name);
        assertThat(value).isNotBlank();
        return value;
    }
    private static String sha(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
    private static String git(Path root, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", root.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String result = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.waitFor()).isZero();
        return result.strip();
    }
    private static final class FacetClock extends Clock {
        private Instant now = SLOT;
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
}
