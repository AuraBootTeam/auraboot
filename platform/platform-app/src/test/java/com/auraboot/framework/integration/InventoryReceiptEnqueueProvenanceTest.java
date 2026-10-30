package com.auraboot.framework.integration;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.constant.SystemFieldConstants;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.OutboxEvent;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.mapper.OutboxEventMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.plugin.entity.PluginRecord;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelope;
import com.auraboot.framework.plugin.mapper.PluginRecordMapper;
import com.auraboot.framework.plugin.pf4j.AuraPluginManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Mock-backed host invocation/artifact/outbox proof; assembled PF4J and PG run separately. */
class InventoryReceiptEnqueueProvenanceTest {
    @TempDir Path directory;
    AuraPluginManager plugins = mock(AuraPluginManager.class);
    OutboxEventMapper outbox = mock(OutboxEventMapper.class);
    MetaModelService definitions = mock(MetaModelService.class);
    MetaModelMapper models = mock(MetaModelMapper.class);
    PluginRecordMapper owners = mock(PluginRecordMapper.class);
    ObjectMapper json = new ObjectMapper();
    InventoryReceiptEnqueueProvenance provenance;
    ReliableIntegrationAccessorImpl accessor;
    CommandHandlerExtension producer;
    CommandHandlerExtension finishedGoodsProducer;
    Runnable effect = () -> {};
    URLClassLoader loader;
    Model model;
    AuraPluginManager.LoadedPluginArtifact artifact;
    CommandHandlerExtension.CommandContext context;

    @BeforeEach void setup() throws Exception {
        Path source = directory.resolve("ConfirmWarehouseInHandler.java");
        Files.writeString(source, """
            package com.auraboot.plugins.inventory.handler;
            import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
            public class ConfirmWarehouseInHandler implements CommandHandlerExtension {
              private final Runnable effect;
              public ConfirmWarehouseInHandler(Runnable effect) { this.effect=effect; }
              public String getCommandType() { return "inv:confirm_warehouse_in"; }
              public Object execute(CommandContext context) { effect.run(); return null; }
            }
            """);
        // Multi-command handler: its static command type is not the receipt command, so only the
        // executing context can select the finished-goods producer entry.
        Path finishedGoodsSource = directory.resolve("FinishedGoodsPackagingHandler.java");
        Files.writeString(finishedGoodsSource, """
            package com.auraboot.plugins.inventory.handler;
            import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
            public class FinishedGoodsPackagingHandler implements CommandHandlerExtension {
              private final Runnable effect;
              public FinishedGoodsPackagingHandler(Runnable effect) { this.effect=effect; }
              public String getCommandType() { return "inv:create_fg_pack_task"; }
              public Object execute(CommandContext context) { effect.run(); return null; }
            }
            """);
        assertThat(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none","-classpath",
                System.getProperty("java.class.path"),"-d",directory.toString(),source.toString(),finishedGoodsSource.toString())).isZero();
        loader = new URLClassLoader(new java.net.URL[]{directory.toUri().toURL()},getClass().getClassLoader());
        producer = (CommandHandlerExtension) loader.loadClass("com.auraboot.plugins.inventory.handler.ConfirmWarehouseInHandler")
                .getConstructor(Runnable.class).newInstance((Runnable) () -> effect.run());
        finishedGoodsProducer = (CommandHandlerExtension) loader.loadClass("com.auraboot.plugins.inventory.handler.FinishedGoodsPackagingHandler")
                .getConstructor(Runnable.class).newInstance((Runnable) () -> effect.run());
        artifact = new AuraPluginManager.LoadedPluginArtifact("com.auraboot.inventory",directory,"sha256:"+"a".repeat(64));
        when(plugins.getExtensionsOfType(CommandHandlerExtension.class)).thenReturn(List.of(producer,finishedGoodsProducer));
        when(plugins.isDiscoveredExtensionInstance(CommandHandlerExtension.class,producer)).thenReturn(true);
        when(plugins.observeLoadedArtifact(producer.getClass())).thenReturn(Optional.of(artifact));
        when(plugins.observeLoadedArtifact(finishedGoodsProducer.getClass())).thenReturn(Optional.of(artifact));
        model = new Model(); model.setId(1L); model.setPid("model"); model.setCode("inv_inbound"); model.setTenantId(31L);
        model.setIsCurrent(true); model.setStatus("published"); model.setDeletedFlag(false); model.setVersion(1);
        model.setRowVersion(1); model.setPluginPid("owner");
        when(models.findCurrentByCode("inv_inbound")).thenReturn(model);
        when(definitions.getModelDefinition("inv_inbound")).thenReturn(Optional.of(ModelDefinition.builder()
                .id(1L).code("inv_inbound").version(1).status("published").sourceType("physical")
                .tableName(SystemFieldConstants.generateTableName("inv_inbound")).build()));
        Model task = new Model(); task.setId(2L); task.setPid("task-model"); task.setCode("inv_fg_pack_task"); task.setTenantId(31L);
        task.setIsCurrent(true); task.setStatus("published"); task.setDeletedFlag(false); task.setVersion(1);
        task.setRowVersion(1); task.setPluginPid("owner");
        when(models.findCurrentByCode("inv_fg_pack_task")).thenReturn(task);
        when(definitions.getModelDefinition("inv_fg_pack_task")).thenReturn(Optional.of(ModelDefinition.builder()
                .id(2L).code("inv_fg_pack_task").version(1).status("published").sourceType("physical")
                .tableName(SystemFieldConstants.generateTableName("inv_fg_pack_task")).build()));
        when(owners.findByPid("owner")).thenReturn(PluginRecord.builder().pid("owner").tenantId(31L)
                .pluginId("com.auraboot.inventory").deletedFlag(false).build());
        provenance = new InventoryReceiptEnqueueProvenance(plugins,new InventoryOwnerModelAuthority(definitions,models,owners),outbox,json);
        accessor = new ReliableIntegrationAccessorImpl(outbox,json,mock(ReliableIntegrationMetrics.class),provenance);
        context = CommandHandlerExtension.CommandContext.builder().tenantId(31L).commandType("inv:confirm_warehouse_in")
                .modelCode("inv_inbound").recordId("receipt").payload(Map.of()).build();
        MetaContext.setContext(31L,17L,"actor","actor");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }
    @AfterEach void clear() throws Exception {
        TransactionSynchronizationManager.clearSynchronization(); TransactionSynchronizationManager.setActualTransactionActive(false);
        MetaContext.clear(); loader.close();
    }

    @Test void registeredInstanceAndRealCallerFrameGeneratePersistedStampAndCommand() throws Exception {
        effect = () -> accessor.enqueue(event(Map.of()));
        invoke(producer);
        var captured = org.mockito.ArgumentCaptor.forClass(OutboxEvent.class); verify(outbox).insertEnvelope(captured.capture());
        OutboxEvent stored = captured.getValue();
        assertThat(stored.getCommandCode()).isEqualTo("inv:confirm_warehouse_in");
        assertThat(json.readTree(stored.getHeaders()).path("core.inventory.producer.artifact").asText()).isEqualTo("a".repeat(64));
        assertThat(json.readTree(stored.getHeaders()).path("core.inventory.producer.plugin").asText()).isEqualTo("com.auraboot.inventory");
        assertThat(stored.getPayload()).isEqualTo(json.writeValueAsString(event(Map.of()).payload()));
        assertThat(MetaContext.getAuthorizedCommandCode()).isNull();
    }

    @Test void canonicalManagerDigestUsesExactTypedPrefixAndPhysicalBytes() throws Exception {
        Path physical=directory.resolve("physical.jar");Files.write(physical,new byte[]{1,2,3,4});
        var method=AuraPluginManager.class.getDeclaredMethod("pluginArtifactDigest",Path.class);method.setAccessible(true);
        String actual=(String)method.invoke(null,physical);
        String sha=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(physical)));
        assertThat(actual).isEqualTo("sha256:"+sha);
    }

    @Test void untypedOrMalformedLoadedDigestCannotStampOrPersist() {
        effect=()->accessor.enqueue(event(Map.of()));
        for(String invalid:List.of("a".repeat(64),"SHA256:"+"a".repeat(64),"sha256:"+"A".repeat(64),"sha256:"+"a".repeat(63),"sha256:sha256:"+"a".repeat(64))){
            when(plugins.observeLoadedArtifact(producer.getClass())).thenReturn(Optional.of(new AuraPluginManager.LoadedPluginArtifact("com.auraboot.inventory",directory,invalid)));
            assertThatThrownBy(()->invoke(producer)).isInstanceOf(SecurityException.class);
        }
        verify(outbox,never()).insertEnvelope(any());
    }

    @Test void sameClassNewInstanceUnregisteredOrMissingPermitCannotCreateProvenance() throws Exception {
        effect = () -> accessor.enqueue(event(Map.of()));
        var duplicate = (CommandHandlerExtension) producer.getClass().getConstructor(Runnable.class).newInstance(effect);
        assertThatThrownBy(() -> invoke(duplicate)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> provenance.invoke(producer,context)).isInstanceOf(SecurityException.class);
        when(plugins.observeLoadedArtifact(producer.getClass())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> invoke(producer)).isInstanceOf(SecurityException.class);
        verify(outbox,never()).insertEnvelope(any());
    }

    @Test void directSourceTextForgedHeadersOrMissingCallerFrameCannotCreateStamp() {
        assertThatThrownBy(() -> accessor.enqueue(event(Map.of()))).isInstanceOf(SecurityException.class);
        effect = () -> accessor.enqueue(event(Map.of("core.inventory.producer.plugin","com.auraboot.inventory")));
        assertThatThrownBy(() -> invoke(producer)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> provenance.stamp(event(Map.of()))).isInstanceOf(SecurityException.class);
        verify(outbox,never()).insertEnvelope(any());
    }

    @Test void foreignReceiptTargetAndArtifactOrMetadataDriftRejectBeforeInsert() {
        effect = () -> accessor.enqueue(event(Map.of()));
        MetaContext.runWithCommandPermitPlan("ALL",1L,"inv_inbound","foreign-receipt", () ->
                MetaContext.runWithAuthorizedCommandCode("inv:confirm_warehouse_in", () ->
                    assertThatThrownBy(() -> provenance.invoke(producer,context)).isInstanceOf(SecurityException.class)));
        when(plugins.observeLoadedArtifact(producer.getClass())).thenReturn(Optional.of(artifact),Optional.of(
                new AuraPluginManager.LoadedPluginArtifact("com.auraboot.inventory",directory,"sha256:"+"b".repeat(64))));
        assertThatThrownBy(() -> invoke(producer)).isInstanceOf(SecurityException.class);
        when(plugins.observeLoadedArtifact(producer.getClass())).thenReturn(Optional.of(artifact));
        effect = () -> { model.setRowVersion(2); accessor.enqueue(event(Map.of())); };
        assertThatThrownBy(() -> invoke(producer)).isInstanceOf(SecurityException.class);
        verify(outbox,never()).insertEnvelope(any());
        // Failure cannot leave the private invocation live on the same thread.
        model.setRowVersion(1); effect = () -> accessor.enqueue(event(Map.of())); invoke(producer);
        verify(outbox,times(1)).insertEnvelope(any());
    }

    @Test void stampPresenceAloneIsNotPersistedAuthorityAndExpiredOrForeignLeaseRejects() {
        AtomicReference<IntegrationEventEnvelope> stamped = new AtomicReference<>();
        effect = () -> stamped.set(provenance.stamp(event(Map.of()))); invoke(producer);
        assertThatThrownBy(() -> provenance.assertPersistedDelivery(stamped.get(),"lease")).isInstanceOf(SecurityException.class);
        when(outbox.countMatchingEnvelope(any())).thenReturn(1);
        assertThatThrownBy(() -> provenance.assertPersistedDelivery(stamped.get(),"wrong-lease")).isInstanceOf(SecurityException.class);
        when(outbox.countActiveDeliveryLease(31L,stamped.get().eventId(),"lease")).thenReturn(1);
        provenance.assertPersistedDelivery(stamped.get(),"lease");
        var expected = org.mockito.ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox,times(3)).countMatchingEnvelope(expected.capture());
        assertThat(expected.getValue().getCommandCode()).isEqualTo("inv:confirm_warehouse_in");
        assertThat(expected.getValue().getPayload()).isEqualTo(jsonValue(stamped.get().payload()));
        assertThat(expected.getValue().getHeaders()).isEqualTo(jsonValue(stamped.get().headers()));
    }

    @Test void nestedForeignPluginFrameCannotBorrowTheRegisteredProducerInvocation() throws Exception {
        Path foreignSource = directory.resolve("ForeignPoster.java");
        Files.writeString(foreignSource, """
            package example.foreign;
            public class ForeignPoster implements Runnable {
              private final Runnable target;
              public ForeignPoster(Runnable target) { this.target=target; }
              public void run() { target.run(); }
            }
            """);
        assertThat(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none","-d",directory.toString(),foreignSource.toString())).isZero();
        try (var foreignLoader = new URLClassLoader(new java.net.URL[]{directory.toUri().toURL()},getClass().getClassLoader())) {
            var type = foreignLoader.loadClass("example.foreign.ForeignPoster");
            when(plugins.observeLoadedArtifact(type)).thenReturn(Optional.of(
                    new AuraPluginManager.LoadedPluginArtifact("different.plugin",directory,"sha256:"+"b".repeat(64))));
            var foreign = (Runnable) type.getConstructor(Runnable.class).newInstance((Runnable) () -> accessor.enqueue(event(Map.of())));
            effect = foreign::run;
            assertThatThrownBy(() -> invoke(producer)).isInstanceOf(SecurityException.class);
            verify(outbox,never()).insertEnvelope(any());
        }
    }

    @Test void unStampedHistoricalEventAndChangedCanonicalIdentityCannotUpgrade() {
        assertThatThrownBy(() -> provenance.assertPersistedDelivery(event(Map.of()),"lease")).isInstanceOf(SecurityException.class);
        var original=event(Map.of());
        var foreign = new IntegrationEventEnvelope(original.schemaVersion(),original.eventId(),original.eventType(),original.source(),
                original.subject(),original.occurredAt(),32,original.correlationId(),original.causationId(),original.orderingKey(),
                original.sequence(),original.payload(),original.headers());
        effect=()->accessor.enqueue(foreign);
        assertThatThrownBy(()->invoke(producer)).isInstanceOf(SecurityException.class);
        var changedId = new IntegrationEventEnvelope(original.schemaVersion(),UUID.randomUUID().toString(),original.eventType(),original.source(),
                original.subject(),original.occurredAt(),original.tenantId(),original.correlationId(),original.causationId(),original.orderingKey(),
                original.sequence(),original.payload(),original.headers());
        effect=()->accessor.enqueue(changedId);
        assertThatThrownBy(()->invoke(producer)).isInstanceOf(SecurityException.class);
        verifyNoInteractions(outbox);
    }

    @Test void unrelatedEventsKeepExistingWireButCannotCarryReservedStamp() {
        var original=event(Map.of());
        var ordinary=new IntegrationEventEnvelope("1.0","ordinary","ordinary.event","ordinary","ordinary",Instant.EPOCH,
                31,"ordinary",null,"ordinary",1,Map.of(),Map.of());
        accessor.enqueue(ordinary); verify(outbox).insertEnvelope(any());
        var forged=new IntegrationEventEnvelope(ordinary.schemaVersion(),ordinary.eventId(),ordinary.eventType(),ordinary.source(),
                ordinary.subject(),ordinary.occurredAt(),ordinary.tenantId(),ordinary.correlationId(),ordinary.causationId(),ordinary.orderingKey(),
                ordinary.sequence(),ordinary.payload(),Map.of("core.inventory.producer.artifact","a".repeat(64)));
        assertThatThrownBy(()->accessor.enqueue(forged)).isInstanceOf(SecurityException.class);
        verify(outbox,times(1)).insertEnvelope(any());
    }

    @Test void finishedGoodsProducerRoutedByExecutingCommandStampsTheLinkedInboundReceipt() throws Exception {
        // The FG task pid ("task") and the linked inbound receipt pid ("receipt") intentionally differ.
        effect = () -> accessor.enqueue(event(Map.of()));
        routeFinishedGoods(finishedGoodsProducer, finishedGoodsContext());
        var captured = org.mockito.ArgumentCaptor.forClass(OutboxEvent.class); verify(outbox).insertEnvelope(captured.capture());
        OutboxEvent stored = captured.getValue();
        assertThat(stored.getCommandCode()).isEqualTo("inv:confirm_finished_goods_receipt");
        var headers = json.readTree(stored.getHeaders());
        assertThat(headers.path("core.inventory.producer.command").asText()).isEqualTo("inv:confirm_finished_goods_receipt");
        assertThat(headers.path("core.inventory.producer.implementation").asText())
                .isEqualTo("com.auraboot.plugins.inventory.handler.FinishedGoodsPackagingHandler");
        assertThat(headers.path("core.inventory.producer.artifact").asText()).isEqualTo("a".repeat(64));
        assertThat(MetaContext.getAuthorizedCommandCode()).isNull();
        // Delivery rebuilds the expected row from the stamped header; it must match the persisted command column.
        AtomicReference<IntegrationEventEnvelope> stamped = new AtomicReference<>();
        effect = () -> stamped.set(provenance.stamp(event(Map.of())));
        routeFinishedGoods(finishedGoodsProducer, finishedGoodsContext());
        when(outbox.countMatchingEnvelope(any())).thenReturn(1);
        when(outbox.countActiveDeliveryLease(31L,stamped.get().eventId(),"lease")).thenReturn(1);
        provenance.assertPersistedDelivery(stamped.get(),"lease");
        var expected = org.mockito.ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).countMatchingEnvelope(expected.capture());
        assertThat(expected.getValue().getCommandCode()).isEqualTo(stored.getCommandCode());
        assertThat(json.readTree(expected.getValue().getHeaders())).isEqualTo(headers);
    }

    @Test void finishedGoodsEntryRejectsImpostorClassForeignPermitOrInboundAuthorization() throws Exception {
        effect = () -> accessor.enqueue(event(Map.of()));
        // The inbound producer class carries an otherwise valid FG context: only the implementation
        // class name separates it from the trusted producer, so this clause must stay in the guard.
        assertThatThrownBy(() -> routeFinishedGoods(producer, finishedGoodsContext())).isInstanceOf(SecurityException.class);
        // Permit issued for a different FG task than the executing record.
        assertThatThrownBy(() -> MetaContext.runWithCommandPermitPlan("ALL",1L,"inv_fg_pack_task","other-task", () ->
                MetaContext.runWithAuthorizedCommandCode("inv:confirm_finished_goods_receipt", () -> route(finishedGoodsProducer, finishedGoodsContext()))))
                .isInstanceOf(SecurityException.class);
        // Pipeline authorized the inbound command, not the FG receipt command.
        assertThatThrownBy(() -> MetaContext.runWithCommandPermitPlan("ALL",1L,"inv_fg_pack_task","task", () ->
                MetaContext.runWithAuthorizedCommandCode("inv:confirm_warehouse_in", () -> route(finishedGoodsProducer, finishedGoodsContext()))))
                .isInstanceOf(SecurityException.class);
        // No permit plan at all.
        assertThatThrownBy(() -> provenance.route(finishedGoodsProducer, finishedGoodsContext())).isInstanceOf(SecurityException.class);
        // A non-FG context with the FG handler passes through without a producer invocation, so the fact cannot be stamped.
        var creation = CommandHandlerExtension.CommandContext.builder().tenantId(31L).commandType("inv:create_fg_pack_task")
                .modelCode("inv_fg_pack_task").recordId("task").payload(Map.of()).build();
        assertThatThrownBy(() -> MetaContext.runWithCommandPermitPlan("ALL",1L,"inv_fg_pack_task","task", () ->
                MetaContext.runWithAuthorizedCommandCode("inv:create_fg_pack_task", () -> route(finishedGoodsProducer, creation))))
                .isInstanceOf(SecurityException.class);
        verify(outbox,never()).insertEnvelope(any());
    }
    private void invoke(CommandHandlerExtension handler) {
        MetaContext.runWithCommandPermitPlan("ALL",1L,"inv_inbound","receipt", () ->
                MetaContext.runWithAuthorizedCommandCode("inv:confirm_warehouse_in",()-> {
                    try { return provenance.invoke(handler,context); }
                    catch (RuntimeException failure) { throw failure; }
                    catch (Exception failure) { throw new IllegalStateException("Unexpected checked fixture exception",failure); }
                }));
    }
    private void routeFinishedGoods(CommandHandlerExtension handler, CommandHandlerExtension.CommandContext executing) {
        MetaContext.runWithCommandPermitPlan("ALL",1L,"inv_fg_pack_task","task", () ->
                MetaContext.runWithAuthorizedCommandCode("inv:confirm_finished_goods_receipt",()-> route(handler,executing)));
    }
    private Object route(CommandHandlerExtension handler, CommandHandlerExtension.CommandContext executing) {
        try { return provenance.route(handler,executing); }
        catch (RuntimeException failure) { throw failure; }
        catch (Exception failure) { throw new IllegalStateException("Unexpected checked fixture exception",failure); }
    }
    private static CommandHandlerExtension.CommandContext finishedGoodsContext() {
        return CommandHandlerExtension.CommandContext.builder().tenantId(31L).commandType("inv:confirm_finished_goods_receipt")
                .modelCode("inv_fg_pack_task").recordId("task").payload(Map.of()).build();
    }
    private String jsonValue(Object value) { try { return json.writeValueAsString(value); } catch(Exception e) { throw new AssertionError(e); } }
    private static IntegrationEventEnvelope event(Map<String,String> headers) {
        String id=UUID.nameUUIDFromBytes(("inventory.receipt.confirmed.v1:31:receipt:1").getBytes(StandardCharsets.UTF_8)).toString();
        return new IntegrationEventEnvelope("1.0",id,"inventory.receipt.confirmed.v1","inventory","inv_inbound/receipt",Instant.EPOCH,
                31,"receipt:31:receipt","pr:inv:confirm_warehouse_in/receipt","31:inv_inbound:receipt",1,
                Map.of("schemaVersion",1,"tenantId",31,"receiptId","receipt","receiptVersion",1,"eventId",id),headers);
    }
}
