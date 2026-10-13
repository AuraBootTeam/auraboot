package com.auraboot.framework.integration;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.mapper.OutboxEventMapper;
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
    BackgroundDataAccessorImpl accessor = new BackgroundDataAccessorImpl(data);
    ReliableEventConsumerExtension consumer;
    Consumer<IntegrationEventEnvelope> effect = event -> {};
    URLClassLoader loader;

    @BeforeEach
    void setup() throws Exception {
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
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(receipts.claimReceipt(anyLong(), anyString(), anyString(), anyString())).thenReturn(1);
        when(receipts.markReceiptApplied(anyLong(), anyString(), anyString(), anyString())).thenReturn(1);
    }

    @AfterEach void cleanup() throws Exception { MetaContext.clear(); loader.close(); }

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
        when(plugins.getExtensionsOfType(ReliableEventConsumerExtension.class)).thenReturn(List.of());
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
        new ReliableEventDeliveryExecutor(receipts, metrics, transactions, plugins)
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

    @Test void nestedUnrelatedDeliveryCannotInheritTheOuterOwnerScope() {
        var other = new ReliableEventConsumerExtension() {
            public String consumerCode() { return "other"; }
            public Set<String> subscribedEventTypes() { return Set.of(InventoryOwnerCommandScope.EVENT); }
            public void consume(IntegrationEventEnvelope e) { throw new AssertionError("Nested consumer must not run"); }
        };
        effect = event -> {
            assertThat(InventoryOwnerCommandScope.active()).isTrue();
            assertThatThrownBy(() -> new ReliableEventDeliveryExecutor(receipts, metrics, transactions, plugins)
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
        assertThatThrownBy(() -> new ReliableEventDeliveryExecutor(receipts, metrics, transactions, plugins)
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
        new ReliableEventDeliveryExecutor(receipts, metrics, transactions, plugins).deliver(event, consumer, "lease");
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
