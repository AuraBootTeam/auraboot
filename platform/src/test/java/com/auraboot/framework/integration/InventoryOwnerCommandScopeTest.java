package com.auraboot.framework.integration;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.mapper.OutboxEventMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.constant.SystemFieldConstants;
import com.auraboot.framework.plugin.entity.PluginRecord;
import com.auraboot.framework.plugin.mapper.PluginRecordMapper;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.impl.FieldWriterGuard;
import com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelope;
import com.auraboot.framework.plugin.extension.integration.ReliableEventConsumerExtension;
import com.auraboot.framework.plugin.pf4j.AuraPluginManager;
import com.auraboot.framework.plugin.pf4j.BackgroundDataAccessorImpl;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real host scope/field guard with hermetic registry/transaction/data delegates; not native PF4J. */
class InventoryOwnerCommandScopeTest {
    @TempDir Path directory;
    AuraPluginManager plugins = mock(AuraPluginManager.class);
    OutboxEventMapper receipts = mock(OutboxEventMapper.class);
    ReliableIntegrationMetrics metrics = mock(ReliableIntegrationMetrics.class);
    PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    DynamicDataService data = mock(DynamicDataService.class);
    MetaModelService definitions = mock(MetaModelService.class);
    MetaModelMapper models = mock(MetaModelMapper.class);
    PluginRecordMapper owners = mock(PluginRecordMapper.class);
    InventoryOwnerModelAuthority authority = new InventoryOwnerModelAuthority(definitions, models, owners);
    Map<String, Model> actualModels = new HashMap<>();
    Map<String, ModelDefinition> effectiveModels = new HashMap<>();
    Map<String, PluginRecord> actualOwners = new HashMap<>();
    BackgroundDataAccessorImpl accessor = new BackgroundDataAccessorImpl(data);
    ReliableEventConsumerExtension consumer;
    Consumer<IntegrationEventEnvelope> effect = event -> {};
    URLClassLoader loader;

    @BeforeEach
    void setup() throws Exception {
        when(receipts.lockActiveDeliveryLease(anyLong(), anyString(), anyString())).thenReturn(1L);
        when(receipts.countActiveDeliveryLease(anyLong(), anyString(), anyString())).thenReturn(1);
        Path source = directory.resolve("ReliableInventoryEventConsumer.java");
        Files.writeString(source, """
            package com.auraboot.plugins.inventory;
            import com.auraboot.framework.plugin.extension.integration.*;
            import java.util.*;
            import java.util.function.Consumer;
            public class ReliableInventoryEventConsumer implements ReliableEventConsumerExtension {
              private final Consumer<IntegrationEventEnvelope> effect;
              public ReliableInventoryEventConsumer(Consumer<IntegrationEventEnvelope> effect) { this.effect=effect; }
              public String consumerCode() { return "inventory.public-command-adapter.v1"; }
              public Set<String> subscribedEventTypes() { return Set.of("procurement.purchase-order.receipt-requested.v1"); }
              public void consume(IntegrationEventEnvelope event) { effect.accept(event); }
            }
            """);
        int compiled = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-proc:none", "-classpath", System.getProperty("java.class.path"),
                "-d", directory.toString(), source.toString());
        assertThat(compiled).isZero();
        loader = new URLClassLoader(new java.net.URL[]{directory.toUri().toURL()}, getClass().getClassLoader());
        Class<?> implementation = loader.loadClass("com.auraboot.plugins.inventory.ReliableInventoryEventConsumer");
        consumer = (ReliableEventConsumerExtension) implementation.getConstructor(Consumer.class)
                .newInstance((Consumer<IntegrationEventEnvelope>) event -> effect.accept(event));
        when(plugins.observeLoadedArtifact(implementation)).thenReturn(Optional.of(
                new AuraPluginManager.LoadedPluginArtifact("com.auraboot.inventory", directory, "verified-by-host")));
        when(plugins.getExtensionsOfType(ReliableEventConsumerExtension.class)).thenReturn(List.of(consumer));
        when(plugins.isDiscoveredExtensionInstance(ReliableEventConsumerExtension.class,consumer)).thenReturn(true);
        for (String code : List.of("inv_inbound", "inv_inbound_line", "inv_warehouse", "inv_warehouse_location", "prod_product", "pe_product")) {
            Model model = new Model(); model.setId((long) actualModels.size() + 1);
            model.setPid(code + "-definition"); model.setTenantId(31L); model.setCode(code);
            model.setPluginPid(code.startsWith("inv_") ? "inventory-owner" : "product-owner");
            model.setStatus("published"); model.setIsCurrent(true); model.setDeletedFlag(false);
            model.setVersion(1); model.setRowVersion(1); // Raw nullable table uses the production generator.
            actualModels.put(code, model);
            effectiveModels.put(code, ModelDefinition.builder().id(model.getId()).code(code).version(1)
                    .status("published").sourceType("physical").tableName(SystemFieldConstants.generateTableName(code)).build());
        }
        actualOwners.put("inventory-owner", PluginRecord.builder().pid("inventory-owner").tenantId(31L)
                .pluginId("com.auraboot.inventory").deletedFlag(false).status("installed").build());
        actualOwners.put("product-owner", PluginRecord.builder().pid("product-owner").tenantId(31L)
                .pluginId("com.auraboot.product-catalog").deletedFlag(false).status("installed").build());
        when(models.findCurrentByCode(anyString())).thenAnswer(call -> actualModels.get(call.getArgument(0)));
        when(definitions.getModelDefinition(anyString())).thenAnswer(call -> Optional.ofNullable(effectiveModels.get(call.getArgument(0))));
        when(owners.findByPid(anyString())).thenAnswer(call -> actualOwners.get(call.getArgument(0)));
        when(transactions.getTransaction(any())).thenAnswer(call -> {
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
            return new SimpleTransactionStatus();
        });
        doAnswer(call -> { org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false); return null; }).when(transactions).commit(any());
        doAnswer(call -> { org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false); return null; }).when(transactions).rollback(any());
        when(receipts.claimReceipt(anyLong(), anyString(), anyString(), anyString())).thenReturn(1);
        when(receipts.markReceiptApplied(anyLong(), anyString(), anyString(), anyString())).thenReturn(1);
    }

    @AfterEach void cleanup() throws Exception { MetaContext.clear(); org.springframework.transaction.support.TransactionSynchronizationManager.clear(); loader.close(); }

    @Test void actualAccessorUsesExactWriterForOnlyTwoOwnedModelsAndRestoresContext() {
        when(data.create(anyString(), anyMap())).thenAnswer(call -> {
            String model = call.getArgument(0);
            assertThat(MetaContext.getCurrentTenantId()).isEqualTo(31L);
            assertThat(MetaContext.getCommandPermitScopeFor(model)).isEqualTo("ALL");
            assertThat(MetaContext.getCommandPermitScopeFor("pr_purchase_order_line")).isNull();
            FieldWriterGuard.assertCreateAllowed(protectedModel(model, "inv:receive_source_receipt"), call.getArgument(1));
            return Map.of("pid", model);
        });
        effect = event -> {
            accessor.create(31, "inv_inbound", Map.of("origin", "source-event"));
            accessor.create(31, "inv_inbound_line", Map.of("origin", "source-line"));
            assertThat(MetaContext.getAuthorizedCommandCode()).isNull();
            accessor.getById(31, "inv_warehouse", "warehouse");
            accessor.getById(31, "prod_product", "product");
        };
        MetaContext.setContext(99L, 17L, "prior", "prior");
        deliver(event("procurement", "inv:receive_source_receipt"));
        verify(data).create(eq("inv_inbound"), anyMap());
        verify(data).create(eq("inv_inbound_line"), anyMap());
        verify(receipts).markReceiptApplied(31L, "event", InventoryOwnerCommandScope.CONSUMER, "lease");
        verify(transactions).commit(any());
        assertThat(MetaContext.getCurrentTenantId()).isEqualTo(99L);
        assertThat(InventoryOwnerCommandScope.active()).isFalse();
        assertThat(MetaContext.getAuthorizedCommandCode()).isNull();
    }

    @Test void scopedOwnerCannotWriteCrossOwnerReadUnknownModelOrUseForeignTenant() {
        for (Runnable forbidden : List.<Runnable>of(
                () -> accessor.create(31, "pr_purchase_order_line", Map.of()),
                () -> accessor.create(32, "inv_inbound", Map.of()),
                () -> accessor.getById(32, "inv_warehouse", "foreign"),
                () -> accessor.getById(31, "pr_purchase_order", "foreign"),
                () -> accessor.delete(31, "inv_inbound", "draft"),
                () -> accessor.update(31, "inv_inbound", "draft", Map.of()))) {
            effect = event -> forbidden.run();
            assertThatThrownBy(() -> deliver(event("procurement", "inv:receive_source_receipt")))
                    .isInstanceOf(SecurityException.class);
            assertThat(InventoryOwnerCommandScope.active()).isFalse();
        }
        verifyNoInteractions(data);
        verify(receipts, never()).markReceiptApplied(anyLong(), anyString(), anyString(), anyString());
        verify(transactions, times(6)).rollback(any());
    }

    @Test void ownerScopeDoesNotBypassTheActualExactFieldWriterGuard() {
        when(data.create(anyString(), anyMap())).thenAnswer(call -> {
            FieldWriterGuard.assertCreateAllowed(protectedModel("inv_inbound", "inv:different"), call.getArgument(1));
            return Map.of();
        });
        effect = event -> accessor.create(31, "inv_inbound", Map.of("origin", "value"));
        assertThatThrownBy(() -> deliver(event("procurement", "inv:receive_source_receipt")))
                .hasMessageContaining("FIELD_WRITER_DENIED");
        verify(transactions).rollback(any());
        assertThat(InventoryOwnerCommandScope.active()).isFalse();
    }

    @Test void wrongPluginUnregisteredInstanceAndUnobservedLoaderCannotObtainScope() {
        var type = consumer.getClass();
        when(plugins.observeLoadedArtifact(type)).thenReturn(Optional.of(
                new AuraPluginManager.LoadedPluginArtifact("wrong.plugin", directory, "digest")));
        assertDeniedBeforeEffect(event("procurement", "inv:receive_source_receipt"));
        when(plugins.observeLoadedArtifact(type)).thenReturn(Optional.empty());
        assertDeniedBeforeEffect(event("procurement", "inv:receive_source_receipt"));
        when(plugins.observeLoadedArtifact(type)).thenReturn(Optional.of(
                new AuraPluginManager.LoadedPluginArtifact("com.auraboot.inventory", directory, "digest")));
        when(plugins.isDiscoveredExtensionInstance(ReliableEventConsumerExtension.class,consumer)).thenReturn(false);
        assertDeniedBeforeEffect(event("procurement", "inv:receive_source_receipt"));
    }

    @Test void forgedCommandWrongSourceAndForeignBodyTenantCannotObtainScope() {
        assertDeniedBeforeEffect(event("other", "inv:receive_source_receipt"));
        assertDeniedBeforeEffect(event("procurement", "inv:confirm_warehouse_in"));
        var forged = event("procurement", "inv:receive_source_receipt");
        forged = new IntegrationEventEnvelope(forged.schemaVersion(), forged.eventId(), forged.eventType(), forged.source(),
                forged.subject(), forged.occurredAt(), forged.tenantId(), forged.correlationId(), forged.causationId(),
                forged.orderingKey(), forged.sequence(), Map.of("targetCommand", "inv:receive_source_receipt",
                "sourceType", "procurement.purchase_receipt", "tenantId", 32), Map.of());
        assertDeniedBeforeEffect(forged);
    }

    @Test void wrongConsumerOrEventDoesNotInheritOwnerAuthority() {
        effect = event -> {
            assertThat(InventoryOwnerCommandScope.active()).isFalse();
            assertThatThrownBy(() -> FieldWriterGuard.assertCreateAllowed(
                    protectedModel("inv_inbound", "inv:receive_source_receipt"), Map.of("origin", "value")))
                    .hasMessageContaining("FIELD_WRITER_DENIED");
        };
        var other = new ReliableEventConsumerExtension() {
            public String consumerCode() { return "other"; }
            public Set<String> subscribedEventTypes() { return Set.of(InventoryOwnerCommandScope.EVENT); }
            public void consume(IntegrationEventEnvelope e) { effect.accept(e); }
        };
        new ReliableEventDeliveryExecutor(receipts, metrics, transactions, plugins, authority)
                .deliver(event("procurement", "inv:receive_source_receipt"), other, "lease");
        var e = event("procurement", "inv:receive_source_receipt");
        var wrong = new IntegrationEventEnvelope(e.schemaVersion(),e.eventId(),"other.event",e.source(),e.subject(),
                e.occurredAt(),e.tenantId(),e.correlationId(),e.causationId(),e.orderingKey(),e.sequence(),e.payload(),Map.of());
        deliver(wrong);
    }

    @Test void exactReceiptReplayNeverOpensScopeOrCallsConsumerAndFailureRestoresOuterPermit() {
        when(receipts.claimReceipt(anyLong(), anyString(), anyString(), anyString())).thenReturn(0);
        effect = event -> { throw new AssertionError("Duplicate cannot run effect"); };
        deliver(event("procurement", "inv:receive_source_receipt"));
        verify(receipts, never()).markReceiptApplied(anyLong(), anyString(), anyString(), anyString());
        when(receipts.claimReceipt(anyLong(), anyString(), anyString(), anyString())).thenReturn(1);
        effect = event -> { throw new IllegalArgumentException("effect failed"); };
        MetaContext.setContext(99L, 17L, "prior", "prior");
        MetaContext.runWithCommandPermitPlan("SELF", 1L, "outer_model", "outer", () ->
            MetaContext.runWithAuthorizedCommandCode("outer:command", () -> {
                assertThatThrownBy(() -> deliver(event("procurement", "inv:receive_source_receipt")))
                        .isInstanceOf(IllegalArgumentException.class);
                assertThat(MetaContext.getCurrentTenantId()).isEqualTo(99L);
                assertThat(MetaContext.getAuthorizedCommandCode()).isEqualTo("outer:command");
                assertThat(MetaContext.getCommandPermitScopeFor("outer_model")).isEqualTo("SELF");
            }));
        assertThat(InventoryOwnerCommandScope.active()).isFalse();
    }

    @Test void wrongActualOwnerUnknownModelAndWrongPhysicalTableRejectBeforeDelegate() {
        effect = event -> accessor.create(31, "inv_inbound", Map.of("origin", "value"));
        actualOwners.get("inventory-owner").setPluginId("wrong.owner");
        assertThatThrownBy(() -> deliver(event("procurement", "inv:receive_source_receipt")))
                .isInstanceOf(SecurityException.class);
        actualOwners.get("inventory-owner").setPluginId("com.auraboot.inventory");
        Model original = actualModels.remove("inv_inbound");
        assertThatThrownBy(() -> deliver(event("procurement", "inv:receive_source_receipt")))
                .isInstanceOf(SecurityException.class);
        actualModels.put("inv_inbound", original);
        original.setTableName(SystemFieldConstants.generateTableName("pr_purchase_order_line"));
        assertThatThrownBy(() -> deliver(event("procurement", "inv:receive_source_receipt")))
                .isInstanceOf(SecurityException.class);
        original.setTableName(null);
        effectiveModels.get("inv_inbound").setTableName("unapproved_storage");
        assertThatThrownBy(() -> deliver(event("procurement", "inv:receive_source_receipt")))
                .isInstanceOf(SecurityException.class);
        effectiveModels.get("inv_inbound").setTableName(SystemFieldConstants.generateTableName("inv_inbound"));
        original.setSourceType("endpoint");
        assertThatThrownBy(() -> deliver(event("procurement", "inv:receive_source_receipt")))
                .isInstanceOf(SecurityException.class);
        verifyNoInteractions(data);
        verify(transactions, times(5)).rollback(any());
    }

    @Test void foreignTenantUnpublishedNonCurrentAndDeletedMetadataCannotAuthorizeTheName() {
        effect = event -> accessor.create(31, "inv_inbound", Map.of("origin", "value"));
        Model model = actualModels.get("inv_inbound");
        model.setTenantId(32L);
        assertThatThrownBy(() -> deliver(event("procurement", "inv:receive_source_receipt")))
                .isInstanceOf(SecurityException.class);
        model.setTenantId(31L); model.setStatus("draft");
        assertThatThrownBy(() -> deliver(event("procurement", "inv:receive_source_receipt")))
                .isInstanceOf(SecurityException.class);
        model.setStatus("published"); model.setIsCurrent(false);
        assertThatThrownBy(() -> deliver(event("procurement", "inv:receive_source_receipt")))
                .isInstanceOf(SecurityException.class);
        model.setIsCurrent(true); model.setDeletedFlag(true);
        assertThatThrownBy(() -> deliver(event("procurement", "inv:receive_source_receipt")))
                .isInstanceOf(SecurityException.class);
        model.setDeletedFlag(false); actualOwners.get("inventory-owner").setTenantId(32L);
        assertThatThrownBy(() -> deliver(event("procurement", "inv:receive_source_receipt")))
                .isInstanceOf(SecurityException.class);
        verifyNoInteractions(data);
        verify(transactions, times(5)).rollback(any());
    }

    @Test void mutableEffectiveDefinitionDuringDelegateRollsBackRatherThanApplyingReceipt() {
        when(data.create(anyString(), anyMap())).thenAnswer(call -> {
            effectiveModels.get("inv_inbound").setTableName("unapproved_storage");
            return Map.of("pid", "created-before-drift-observed");
        });
        effect = event -> accessor.create(31, "inv_inbound", Map.of("origin", "value"));
        assertThatThrownBy(() -> deliver(event("procurement", "inv:receive_source_receipt")))
                .isInstanceOf(SecurityException.class);
        verify(data).create(eq("inv_inbound"), anyMap());
        verify(transactions).rollback(any());
        verify(receipts, never()).markReceiptApplied(anyLong(), anyString(), anyString(), anyString());
        assertThat(InventoryOwnerCommandScope.active()).isFalse();
    }

    @Test void approvedImmutableLeaseRejectsAChangedCurrentModelRevisionBetweenAccesses() {
        effect = event -> {
            accessor.getById(31, "inv_inbound", "existing");
            actualModels.get("inv_inbound").setRowVersion(2);
            accessor.create(31, "inv_inbound", Map.of("origin", "value"));
        };
        assertThatThrownBy(() -> deliver(event("procurement", "inv:receive_source_receipt")))
                .isInstanceOf(SecurityException.class);
        verify(data).getById("inv_inbound", "existing");
        verify(data, never()).create(anyString(), anyMap());
        verify(transactions).rollback(any());
    }

    @Test void nestedUnrelatedDeliveryCannotInheritTheOuterOwnerScope() {
        var other = new ReliableEventConsumerExtension() {
            public String consumerCode() { return "other"; }
            public Set<String> subscribedEventTypes() { return Set.of(InventoryOwnerCommandScope.EVENT); }
            public void consume(IntegrationEventEnvelope e) { throw new AssertionError("Nested consumer must not run"); }
        };
        effect = event -> {
            assertThat(InventoryOwnerCommandScope.active()).isTrue();
            assertThatThrownBy(() -> new ReliableEventDeliveryExecutor(receipts, metrics, transactions, plugins, authority)
                    .deliver(event, other, "nested-lease")).isInstanceOf(SecurityException.class);
            assertThat(InventoryOwnerCommandScope.active()).isTrue();
        };
        deliver(event("procurement", "inv:receive_source_receipt"));
        assertThat(InventoryOwnerCommandScope.active()).isFalse();
        verify(transactions).rollback(any());
        verify(transactions).commit(any());
    }

    @Test void foreignClassloaderProxyCannotBorrowTheRegisteredConsumerCode() {
        ClassLoader foreign = new ClassLoader(getClass().getClassLoader()) {};
        var forged = (ReliableEventConsumerExtension) java.lang.reflect.Proxy.newProxyInstance(
                foreign, new Class<?>[]{ReliableEventConsumerExtension.class}, (proxy, method, args) ->
                    switch (method.getName()) {
                        case "consumerCode" -> InventoryOwnerCommandScope.CONSUMER;
                        case "subscribedEventTypes" -> Set.of(InventoryOwnerCommandScope.EVENT);
                        default -> throw new AssertionError("Foreign consumer must not execute");
                    });
        assertThatThrownBy(() -> new ReliableEventDeliveryExecutor(receipts, metrics, transactions, plugins, authority)
                .deliver(event("procurement", "inv:receive_source_receipt"), forged, "lease"))
                .isInstanceOf(SecurityException.class);
        verify(plugins, never()).observeLoadedArtifact(forged.getClass());
        verify(transactions).rollback(any());
    }

    private void assertDeniedBeforeEffect(IntegrationEventEnvelope event) {
        effect = ignored -> { throw new AssertionError("Denied owner must not execute"); };
        assertThatThrownBy(() -> deliver(event)).isInstanceOf(SecurityException.class);
        verifyNoInteractions(data);
    }
    private void deliver(IntegrationEventEnvelope event) {
        new ReliableEventDeliveryExecutor(receipts, metrics, transactions, plugins, authority).deliver(event, consumer, "lease");
    }
    private static IntegrationEventEnvelope event(String source, String command) {
        return new IntegrationEventEnvelope("1.0", "event", InventoryOwnerCommandScope.EVENT, source, "receipt", Instant.EPOCH,
                31, "event", null, "order", 1, Map.of("targetCommand",command,"sourceType","procurement.purchase_receipt"),Map.of());
    }
    private static ModelDefinition protectedModel(String model, String writer) {
        FieldDefinition field = new FieldDefinition(); field.setCode("origin"); field.setAllowedWriterCommands(List.of(writer));
        return ModelDefinition.builder().code(model).fields(List.of(field)).build();
    }
}
