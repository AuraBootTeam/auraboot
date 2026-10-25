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
import com.auraboot.framework.meta.service.impl.RecordCommandWriterGuard;
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
class ProcurementReceiptCommandScopeTest {
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
    InventoryReceiptEnqueueProvenance provenance = new InventoryReceiptEnqueueProvenance(plugins, authority, receipts, new com.fasterxml.jackson.databind.ObjectMapper());
    BackgroundDataAccessorImpl accessor = new BackgroundDataAccessorImpl(data);
    ReliableEventConsumerExtension consumer;
    Consumer<IntegrationEventEnvelope> effect = event -> {};
    URLClassLoader loader;

    @BeforeEach
    void setup() throws Exception {
        Path source = directory.resolve("ReliableProcurementReceiptConsumer.java");
        Files.writeString(source, """
            package com.auraboot.plugins.procurement;
            import com.auraboot.framework.plugin.extension.integration.*;
            import java.util.*;
            import java.util.function.Consumer;
            public class ReliableProcurementReceiptConsumer implements ReliableEventConsumerExtension {
              private final Consumer<IntegrationEventEnvelope> effect;
              public ReliableProcurementReceiptConsumer(Consumer<IntegrationEventEnvelope> effect) { this.effect=effect; }
              public String consumerCode() { return "procurement.confirmed-receipt-counter.v1"; }
              public Set<String> subscribedEventTypes() { return Set.of("inventory.receipt.confirmed.v1"); }
              public void consume(IntegrationEventEnvelope event) { effect.accept(event); }
            }
            """);
        int compiled = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-proc:none", "-classpath", System.getProperty("java.class.path"),
                "-d", directory.toString(), source.toString());
        assertThat(compiled).isZero();
        loader = new URLClassLoader(new java.net.URL[]{directory.toUri().toURL()}, getClass().getClassLoader());
        Class<?> implementation = loader.loadClass("com.auraboot.plugins.procurement.ReliableProcurementReceiptConsumer");
        consumer = (ReliableEventConsumerExtension) implementation.getConstructor(Consumer.class)
                .newInstance((Consumer<IntegrationEventEnvelope>) event -> effect.accept(event));
        when(plugins.observeLoadedArtifact(implementation)).thenReturn(Optional.of(
                new AuraPluginManager.LoadedPluginArtifact("com.auraboot.procurement", directory, "verified-by-host")));
        when(plugins.getExtensionsOfType(ReliableEventConsumerExtension.class)).thenReturn(List.of(consumer));
        when(plugins.isDiscoveredExtensionInstance(ReliableEventConsumerExtension.class,consumer)).thenReturn(true);
        for (String code : List.of("pr_purchase_order_line", "pr_purchase_order", "pr_purchase_receipt", "pr_purchase_receipt_line", "pr_inventory_operation_request", "prod_product", "pe_product")) {
            Model model = new Model(); model.setId((long) actualModels.size() + 1);
            model.setPid(code + "-definition"); model.setTenantId(31L); model.setCode(code);
            model.setPluginPid(code.startsWith("pr_") ? "procurement-owner" : "product-owner");
            model.setStatus("published"); model.setIsCurrent(true); model.setDeletedFlag(false);
            model.setVersion(1); model.setRowVersion(1); // Raw nullable table uses the production generator.
            actualModels.put(code, model);
            effectiveModels.put(code, ModelDefinition.builder().id(model.getId()).code(code).version(1)
                    .status("published").sourceType("physical").tableName(SystemFieldConstants.generateTableName(code)).build());
        }
        actualOwners.put("procurement-owner", PluginRecord.builder().pid("procurement-owner").tenantId(31L)
                .pluginId("com.auraboot.procurement").deletedFlag(false).status("installed").build());
        actualOwners.put("product-owner", PluginRecord.builder().pid("product-owner").tenantId(31L)
                .pluginId("com.auraboot.product-catalog").deletedFlag(false).status("installed").build());
        when(models.findCurrentByCode(anyString())).thenAnswer(call -> actualModels.get(call.getArgument(0)));
        when(definitions.getModelDefinition(anyString())).thenAnswer(call -> Optional.ofNullable(effectiveModels.get(call.getArgument(0))));
        when(owners.findByPid(anyString())).thenAnswer(call -> actualOwners.get(call.getArgument(0)));
        when(receipts.countMatchingEnvelope(any())).thenReturn(1);
        when(receipts.countActiveDeliveryLease(anyLong(), anyString(), anyString())).thenReturn(1);
        when(data.getById("pr_purchase_order_line", "line")).thenReturn(row());
        when(data.getById("pr_purchase_order", "order")).thenReturn(parentRow());
        when(data.compareAndSet(anyString(), anyString(), anyString(), any(), anyMap())).thenReturn(true);
        when(receipts.lockActiveDeliveryLease(anyLong(), anyString(), anyString())).thenReturn(1L);
        // The memory transaction delegate exposes Spring's active-T01 marker, not PG atomicity.
        when(transactions.getTransaction(any())).thenAnswer(call -> {
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
            return new SimpleTransactionStatus();
        });
        doAnswer(call -> { org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false); return null; }).when(transactions).commit(any());
        doAnswer(call -> { org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false); return null; }).when(transactions).rollback(any());
        when(receipts.claimReceipt(anyLong(), anyString(), anyString(), anyString())).thenReturn(1);
        when(receipts.markReceiptApplied(anyLong(), anyString(), anyString(), anyString())).thenReturn(1);
    }

    @AfterEach void cleanup() throws Exception { MetaContext.clear(); org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false); loader.close(); }


    @Test void exactCasAdvancesOnlyVerifiedLineAndRestoresCommandScope() {
        effect = event -> assertThat(accessor.compareAndSet(31, "pr_purchase_order_line", "line", "row_version", 7,
                Map.of("pr_pol_received_qty", "25.000001"))).isTrue();
        when(data.compareAndSet(anyString(), anyString(), anyString(), any(), anyMap())).thenAnswer(call -> {
            assertThat(MetaContext.getAuthorizedCommandCode()).isEqualTo(ProcurementReceiptCommandScope.COMMAND);
            assertThat(MetaContext.getCommandExpectedVersion("pr_purchase_order_line", "line")).isEqualTo(7L);
            assertThat(call.getArgument(4, Map.class)).containsOnlyKeys("pr_pol_received_qty");
            return true;
        });
        MetaContext.setContext(99L, 17L, "outer", "outer");
        deliver();
        assertThat(MetaContext.getCurrentTenantId()).isEqualTo(99L);
        assertThat(MetaContext.getAuthorizedCommandCode()).isNull();
        assertThat(ProcurementReceiptCommandScope.active()).isFalse();
        verify(receipts).markReceiptApplied(31L, event().eventId(), ProcurementReceiptCommandScope.CONSUMER, "lease");
    }
    @Test void extraPatchOtherTargetsWrongVersionAndOrdinaryWritesNeverReachDelegate() {
        // A real same-tenant managed row still lacks this event's target authority.
        when(data.getById("pr_purchase_order_line", "other")).thenReturn(changed("pid", "other"));
        for (Runnable bad : List.<Runnable>of(
            () -> accessor.compareAndSet(31, "pr_purchase_order_line", "line", "row_version", 7, Map.of("pr_pol_received_qty", 25, "pr_pol_qty", 999)),
            () -> accessor.compareAndSet(31, "pr_purchase_order_line", "line", "row_version", 7, Map.of("pr_pol_received_qty", 25, "row_version", 8)),
            () -> accessor.compareAndSet(31, "pr_purchase_order_line", "other", "row_version", 7, Map.of("pr_pol_received_qty", 25)),
            () -> accessor.compareAndSet(32, "pr_purchase_order_line", "line", "row_version", 7, Map.of("pr_pol_received_qty", 25)),
            () -> accessor.compareAndSet(31, "pr_purchase_order_line", "line", "row_version", 6, Map.of("pr_pol_received_qty", 25)),
            () -> accessor.compareAndSet(31, "pr_purchase_order_line", "line", "pr_pol_received_qty", 20, 25),
            () -> accessor.update(31, "pr_purchase_order_line", "line", Map.of("pr_pol_received_qty", 25)),
            () -> accessor.create(31, "pr_purchase_order_line", Map.of()),
            () -> accessor.delete(31, "pr_purchase_order_line", "line"),
            () -> accessor.incrementWithinCap(31, "pr_purchase_order_line", "line", "pr_pol_received_qty", 1, "pr_pol_qty"))) {
            effect = event -> bad.run();
            assertThatThrownBy(this::deliver).isInstanceOf(SecurityException.class);
            assertThat(ProcurementReceiptCommandScope.active()).isFalse();
        }
        verify(data, never()).compareAndSet(anyString(), anyString(), anyString(), any(), anyMap());
        verify(data, never()).update(anyString(), anyString(), anyMap());
        verify(data, never()).create(anyString(), anyMap());
        verify(data, never()).delete(anyString(), anyString());
    }
    @Test void actualOwnerTableTenantAndQuantityBoundsAreNotNameBasedAuthority() {
        for (Map<String,Object> invalid : List.of(
                changed("tenant_id", 32L), changed("pid", "foreign"), changed("pr_pol_collaboration_managed", false))) {
            when(data.getById("pr_purchase_order_line", "line")).thenReturn(invalid);
            effect = event -> accessor.compareAndSet(31, "pr_purchase_order_line", "line", "row_version", 7, Map.of("pr_pol_received_qty", 25));
            assertThatThrownBy(this::deliver).isInstanceOf(SecurityException.class);
        }
        when(data.getById("pr_purchase_order_line", "line")).thenReturn(row());
        for (String quantity : List.of("20", "101", "25.0000000000001", "-1")) {
            effect = event -> accessor.compareAndSet(31, "pr_purchase_order_line", "line", "row_version", 7, Map.of("pr_pol_received_qty", quantity));
            assertThatThrownBy(this::deliver).isInstanceOf(SecurityException.class);
        }
        actualOwners.get("procurement-owner").setPluginId("com.auraboot.inventory");
        effect = event -> accessor.compareAndSet(31, "pr_purchase_order_line", "line", "row_version", 7, Map.of("pr_pol_received_qty", 25));
        assertThatThrownBy(this::deliver).isInstanceOf(SecurityException.class);
        verify(data, never()).compareAndSet(anyString(), anyString(), anyString(), any(), anyMap());
    }
    @Test void leaseLossAfterEffectCannotMarkApplied() {
        when(data.compareAndSet(anyString(), anyString(), anyString(), any(), anyMap())).thenAnswer(call -> {
            when(receipts.countActiveDeliveryLease(anyLong(), anyString(), anyString())).thenReturn(0);
            return true;
        });
        effect = event -> accessor.compareAndSet(31, "pr_purchase_order_line", "line", "row_version", 7, Map.of("pr_pol_received_qty", 25));
        assertThatThrownBy(this::deliver).isInstanceOf(SecurityException.class);
        verify(receipts, never()).markReceiptApplied(anyLong(), anyString(), anyString(), anyString());
        verify(transactions).rollback(any());
    }
    @Test void missingExactLeaseRowRejectsBeforeConsumerOrDataEffect() {
        when(receipts.lockActiveDeliveryLease(anyLong(),anyString(),anyString())).thenReturn(null);
        effect = event -> accessor.compareAndSet(31,"pr_purchase_order_line","line","row_version",7,Map.of("pr_pol_received_qty",25));
        assertThatThrownBy(this::deliver).isInstanceOf(IllegalStateException.class).hasMessageContaining("lease");
        verifyNoInteractions(data);
        verify(receipts,never()).markReceiptApplied(anyLong(),anyString(),anyString(),anyString());
        verify(transactions).rollback(any());
    }
    @Test void fullEnvelopeMustBeRecheckedAfterLockBeforeAnyPluginCallback() {
        when(receipts.lockActiveDeliveryLease(anyLong(),anyString(),anyString())).thenAnswer(call -> {
            when(receipts.countMatchingEnvelope(any())).thenReturn(0); return 1L;
        });
        java.util.concurrent.atomic.AtomicBoolean called = new java.util.concurrent.atomic.AtomicBoolean();
        effect = event -> called.set(true);
        assertThatThrownBy(this::deliver).isInstanceOf(SecurityException.class);
        assertThat(called).isFalse();verifyNoInteractions(data);
        verify(receipts,never()).markReceiptApplied(anyLong(),anyString(),anyString(),anyString());
        verify(transactions).rollback(any());
    }
    @Test void missingT01TransactionMarkerCannotAcquireLockOrAuthority() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
        effect = event -> accessor.compareAndSet(31,"pr_purchase_order_line","line","row_version",7,Map.of("pr_pol_received_qty",25));
        assertThatThrownBy(this::deliver).isInstanceOf(SecurityException.class);
        verify(receipts,never()).lockActiveDeliveryLease(anyLong(),anyString(),anyString());
        verifyNoInteractions(data);
        verify(transactions).rollback(any());
    }
    @Test void everyCanonicalPrReadRejectsWrongActualOwnerBeforeDelegate() {
        actualOwners.get("procurement-owner").setPluginId("com.auraboot.inventory");
        for (String code:List.of("pr_purchase_order","pr_purchase_order_line","pr_purchase_receipt","pr_purchase_receipt_line","pr_inventory_operation_request")) {
            effect=event->accessor.getById(31,code,"source");
            assertThatThrownBy(this::deliver).isInstanceOf(SecurityException.class);
        }
        verifyNoInteractions(data);
    }
    @Test void ambiguousRawOrUnpublishedDeletedReadCannotBecomeOriginalRequestAuthority() {
        String code="pr_inventory_operation_request";
        effect=event->accessor.getById(31,code,"source");
        actualModels.get(code).setStatus("draft");assertThatThrownBy(this::deliver).isInstanceOf(SecurityException.class);
        actualModels.get(code).setStatus("published");actualModels.get(code).setDeletedFlag(true);
        assertThatThrownBy(this::deliver).isInstanceOf(SecurityException.class);
        actualModels.get(code).setDeletedFlag(false);
        actualOwners.get("procurement-owner").setDeletedFlag(true);assertThatThrownBy(this::deliver).isInstanceOf(SecurityException.class);
        actualOwners.get("procurement-owner").setDeletedFlag(false);
        effectiveModels.put(code,ModelDefinition.builder().id(999L).code(code).version(1).status("published")
            .tableName(SystemFieldConstants.generateTableName(code)).build());
        assertThatThrownBy(this::deliver).isInstanceOf(SecurityException.class);
        when(models.findCurrentByCode(code)).thenThrow(new org.apache.ibatis.exceptions.TooManyResultsException("Synthetic ambiguous current model"));
        assertThatThrownBy(this::deliver).isInstanceOf(org.apache.ibatis.exceptions.TooManyResultsException.class);
        verifyNoInteractions(data);
    }
    @Test void productReadsKeepTheirActualLegitimateOwnerRatherThanUsingPrPrefixInference() {
        effect=event->{accessor.getById(31,"prod_product","product");accessor.getById(31,"pe_product","legacy");};
        deliver();verify(data).getById("prod_product","product");verify(data).getById("pe_product","legacy");
    }
    @Test void metadataDriftDuringCasCannotMarkApplied() {
        when(data.compareAndSet(anyString(), anyString(), anyString(), any(), anyMap())).thenAnswer(call -> {
            actualModels.get("pr_purchase_order_line").setRowVersion(2);
            return true;
        });
        effect = event -> accessor.compareAndSet(31, "pr_purchase_order_line", "line", "row_version", 7, Map.of("pr_pol_received_qty", 25));
        assertThatThrownBy(this::deliver).isInstanceOf(SecurityException.class);
        verify(receipts, never()).markReceiptApplied(anyLong(), anyString(), anyString(), anyString());
        verify(transactions).rollback(any());
    }
    @Test void repeatedLineCasAndUnregisteredInstanceAreRejected() {
        effect = event -> {
            accessor.compareAndSet(31, "pr_purchase_order_line", "line", "row_version", 7, Map.of("pr_pol_received_qty", 25));
            accessor.compareAndSet(31, "pr_purchase_order_line", "line", "row_version", 7, Map.of("pr_pol_received_qty", 25));
        };
        assertThatThrownBy(this::deliver).isInstanceOf(SecurityException.class);
        when(plugins.isDiscoveredExtensionInstance(ReliableEventConsumerExtension.class,consumer)).thenReturn(false);
        assertThatThrownBy(this::deliver).isInstanceOf(SecurityException.class);
    }
    @Test void qualifiedCounterSqlFencesExactStoredParentAndBothManagedFlags() {
        installWriterDefinitions();
        when(data.compareAndSet(anyString(), anyString(), anyString(), any(), anyMap())).thenAnswer(call -> {
            Map<String,Object> params = new HashMap<>();
            String sql = counterSql(31, "line", 7, "row_version", 7, Map.of("pr_pol_received_qty", "25.000001"), params);
            assertThat(sql).contains("AND EXISTS", "pr_pol_collaboration_managed IS TRUE",
                    "record_writer_parent.pid = #{params.receiptParentPid}",
                    "record_writer_parent.pid = mt_pr_purchase_order_line.pr_pol_order_id",
                    "record_writer_parent.tenant_id = mt_pr_purchase_order_line.tenant_id",
                    "record_writer_parent.pr_po_collaboration_managed IS TRUE",
                    "record_writer_parent.row_version = #{params.receiptParentVersion}").doesNotContain("NOT EXISTS");
            assertThat(params).containsEntry("receiptParentPid", "order").containsEntry("receiptParentVersion", 4L);
            assertThatThrownBy(() -> counterSql(31, "line", 7, "row_version", 7,
                    Map.of("pr_pol_received_qty", "25.000001"), new HashMap<>())).isInstanceOf(SecurityException.class);
            return true;
        });
        effect = event -> accessor.compareAndSet(31, "pr_purchase_order_line", "line", "row_version", 7,
                Map.of("pr_pol_received_qty", "25.000001"));
        deliver();
        assertThat(ProcurementReceiptCommandScope.verifiedCounterParent(31, "pr_purchase_order_line", "line",
                7, "row_version", 7, Map.of("pr_pol_received_qty", "25.000001"))).isEmpty();
    }

    @Test void counterSqlCannotBorrowFrameForAnotherTenantTargetVersionOrPatch() {
        installWriterDefinitions();
        when(data.compareAndSet(anyString(), anyString(), anyString(), any(), anyMap())).thenAnswer(call -> {
            for (Runnable invalid : List.<Runnable>of(
                    () -> counterSql(31, "line", 7, null, null, Map.of("pr_pol_received_qty", 25), new HashMap<>()),
                    () -> counterSql(32, "line", 7, "row_version", 7, Map.of("pr_pol_received_qty", 25), new HashMap<>()),
                    () -> counterSql(31, "other", 7, "row_version", 7, Map.of("pr_pol_received_qty", 25), new HashMap<>()),
                    () -> counterSql(31, "line", 6, "row_version", 7, Map.of("pr_pol_received_qty", 25), new HashMap<>()),
                    () -> counterSql(31, "line", 7, "row_version", 6, Map.of("pr_pol_received_qty", 25), new HashMap<>()),
                    () -> counterSql(31, "line", 7, "pr_pol_received_qty", 7, Map.of("pr_pol_received_qty", 25), new HashMap<>()),
                    () -> counterSql(31, "line", 7, "row_version", 7, Map.of("pr_pol_received_qty", 26), new HashMap<>()),
                    () -> counterSql(31, "line", 7, "row_version", 7, Map.of("pr_pol_received_qty", 25, "pr_pol_qty", 200), new HashMap<>()))) {
                assertThatThrownBy(invalid::run).isInstanceOf(SecurityException.class);
            }
            assertThat(counterSql(31, "line", 7, "row_version", 7,
                    Map.of("pr_pol_received_qty", 25, "updated_by", 0L), new HashMap<>())).contains("AND EXISTS");
            return true;
        });
        effect = event -> accessor.compareAndSet(31, "pr_purchase_order_line", "line", "row_version", 7,
                Map.of("pr_pol_received_qty", 25));
        deliver();
    }

    @Test void activeConsumerAndMatchingCommandAloneStillDenyManagedParent() {
        installWriterDefinitions();
        effect = event -> {
            assertThat(counterSql(31, "line", 7, "row_version", 7,
                    Map.of("pr_pol_received_qty", 25), new HashMap<>())).contains("NOT EXISTS");
            MetaContext.runWithCommandPermitPlan("ALL", 7L, "pr_purchase_order_line", "line", () ->
                    MetaContext.runWithAuthorizedCommandCode(ProcurementReceiptCommandScope.COMMAND, () -> {
                        assertThat(counterSql(31, "line", 7, "row_version", 7,
                                Map.of("pr_pol_received_qty", 25), new HashMap<>())).contains("NOT EXISTS");
                    }));
        };
        deliver();
        verify(data, never()).compareAndSet(anyString(), anyString(), anyString(), any(), anyMap());
    }

    @Test void wrongParentIdentityTenantMarkerVersionOrOwnerCannotOpenCounterFrame() {
        for (Map<String,Object> invalid : List.of(
                parentChanged("pid", "other"), parentChanged("tenant_id", 32L),
                parentChanged("pr_po_collaboration_managed", false), parentChanged("row_version", -1L))) {
            when(data.getById("pr_purchase_order", "order")).thenReturn(invalid);
            effect = event -> accessor.compareAndSet(31, "pr_purchase_order_line", "line", "row_version", 7,
                    Map.of("pr_pol_received_qty", 25));
            assertThatThrownBy(this::deliver).isInstanceOf(SecurityException.class);
        }
        when(data.getById("pr_purchase_order", "order")).thenReturn(parentRow());
        actualModels.get("pr_purchase_order").setPluginPid("product-owner");
        assertThatThrownBy(this::deliver).isInstanceOf(SecurityException.class);
        verify(data, never()).compareAndSet(anyString(), anyString(), anyString(), any(), anyMap());
    }

    @Test void lostLeaseInsideCounterSqlFrameFailsClosedAndFrameIsRemoved() {
        installWriterDefinitions();
        when(data.compareAndSet(anyString(), anyString(), anyString(), any(), anyMap())).thenAnswer(call -> {
            when(receipts.countActiveDeliveryLease(anyLong(), anyString(), anyString())).thenReturn(0);
            return !counterSql(31, "line", 7, "row_version", 7,
                    Map.of("pr_pol_received_qty", 25), new HashMap<>()).isEmpty();
        });
        effect = event -> accessor.compareAndSet(31, "pr_purchase_order_line", "line", "row_version", 7,
                Map.of("pr_pol_received_qty", 25));
        assertThatThrownBy(this::deliver).isInstanceOf(SecurityException.class);
        assertThat(ProcurementReceiptCommandScope.verifiedCounterParent(31, "pr_purchase_order_line", "line",
                7, "row_version", 7, Map.of("pr_pol_received_qty", 25))).isEmpty();
        verify(receipts, never()).markReceiptApplied(anyLong(), anyString(), anyString(), anyString());
        verify(transactions).rollback(any());
    }

    private String counterSql(long tenant, String record, Object version, String compare, Object expected,
            Map<String,Object> columns, Map<String,Object> params) {
        Long previous = MetaContext.getCurrentTenantId();
        if (tenant != previous) {
            MetaContext.setCurrentTenantId(tenant);
            try {
                return counterSql(tenant, record, version, compare, expected, columns, params);
            } finally {
                MetaContext.setCurrentTenantId(previous);
            }
        }
        StringBuilder sql = new StringBuilder("UPDATE mt_pr_purchase_order_line SET pr_pol_received_qty=? WHERE pid=?");
        RecordCommandWriterGuard.appendCounterStoredPredicate(sql, effectiveModels.get("pr_purchase_order_line"), "update",
                effectiveModels::get, record, version, compare, expected, columns, params);
        return sql.toString();
    }

    private void installWriterDefinitions() {
        var reference = new FieldDefinition.RefTarget();
        reference.setTargetEntity("pr_purchase_order"); reference.setValueField("pid");
        effectiveModels.get("pr_purchase_order_line").setFields(List.of(
                FieldDefinition.builder().code("pr_pol_order_id").refTarget(reference).build(),
                FieldDefinition.builder().code("pr_pol_collaboration_managed").dataType("boolean").immutable(true).build()));
        effectiveModels.get("pr_purchase_order_line").setExtension(Map.of("parentModel", "pr_purchase_order",
                "parentField", "pr_pol_order_id", "recordCommandWriters", Map.of("field", "pr_pol_collaboration_managed",
                "commands", Map.of("create", List.of(), "update", List.of(ProcurementReceiptCommandScope.COMMAND), "delete", List.of()))));
        effectiveModels.get("pr_purchase_order").setFields(List.of(
                FieldDefinition.builder().code("pr_po_collaboration_managed").dataType("boolean").immutable(true).build()));
        effectiveModels.get("pr_purchase_order").setExtension(Map.of("recordCommandWriters", Map.of("field", "pr_po_collaboration_managed",
                "commands", Map.of("create", List.of(), "update", List.of("qo_procurement_order_common:sync_orders"), "delete", List.of()))));
    }

    private static Map<String,Object> parentChanged(String key, Object value) {
        Map<String,Object> parent = new HashMap<>(parentRow()); parent.put(key, value); return parent;
    }

    private void deliver() {
        new ReliableEventDeliveryExecutor(receipts, metrics, transactions, plugins, authority, provenance).deliver(event(), consumer, "lease");
    }
    private static Map<String,Object> row() { return Map.of("pid", "line", "tenant_id", 31L, "row_version", 7,
        "pr_pol_collaboration_managed", true, "pr_pol_received_qty", "20", "pr_pol_qty", "100", "pr_pol_order_id", "order"); }
    private static Map<String,Object> parentRow() {
        return Map.of("pid", "order", "tenant_id", 31L, "row_version", 4L, "pr_po_collaboration_managed", true);
    }
    private static Map<String,Object> changed(String key, Object value) { Map<String,Object> row = new HashMap<>(row()); row.put(key,value); return row; }
    private static IntegrationEventEnvelope event() {
        String id = java.util.UUID.nameUUIDFromBytes("inventory.receipt.confirmed.v1:31:receipt:3".getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        return new IntegrationEventEnvelope("1.0", id, "inventory.receipt.confirmed.v1", "inventory", "inv_inbound/receipt", Instant.EPOCH,
            31, "correlation", "causation", "31:inv_inbound:receipt", 3,
            Map.of("schemaVersion",1, "tenantId",31L, "receiptId","receipt", "receiptVersion",3, "eventId",id,
                "lines",List.of(Map.of("sourceLine",Map.of("collaborationQualified",true,"purchaseOrderLinePid","line")))),
            Map.of("core.inventory.producer.schema","1", "core.inventory.producer.plugin","com.auraboot.inventory",
                "core.inventory.producer.artifact","a".repeat(64), "core.inventory.producer.command","inv:confirm_warehouse_in",
                "core.inventory.producer.implementation","com.auraboot.plugins.inventory.handler.ConfirmWarehouseInHandler"));
    }
}
