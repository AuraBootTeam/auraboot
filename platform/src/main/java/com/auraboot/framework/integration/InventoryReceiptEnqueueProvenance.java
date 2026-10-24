package com.auraboot.framework.integration;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.entity.OutboxEvent;
import com.auraboot.framework.meta.mapper.OutboxEventMapper;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelope;
import com.auraboot.framework.plugin.pf4j.AuraPluginManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Private host provenance for posted Inventory facts; no public raw apply or stamp setter. */
@Component
public final class InventoryReceiptEnqueueProvenance {
    static final String EVENT = "inventory.receipt.confirmed.v1";
    static final String COMMAND = "inv:confirm_warehouse_in";
    private static final String IMPLEMENTATION = "com.auraboot.plugins.inventory.handler.ConfirmWarehouseInHandler";
    static final String FINISHED_GOODS_COMMAND = "inv:confirm_finished_goods_receipt";
    private static final String FINISHED_GOODS_IMPLEMENTATION = "com.auraboot.plugins.inventory.handler.FinishedGoodsPackagingHandler";
    private static final String FINISHED_GOODS_MODEL = "inv_fg_pack_task";
    private static final String PREFIX = "core.inventory.producer.";
    private enum Producer { INBOUND, FINISHED_GOODS }
    private record Invocation(CommandHandlerExtension handler, CommandHandlerExtension.CommandContext context,
                              AuraPluginManager.LoadedPluginArtifact artifact, Producer producer,
                              InventoryOwnerModelAuthority.Binding model) {}
    private final ThreadLocal<Invocation> current = new ThreadLocal<>();
    private final AuraPluginManager plugins;
    private final InventoryOwnerModelAuthority models;
    private final OutboxEventMapper outbox;
    private final ObjectMapper json;

    public InventoryReceiptEnqueueProvenance(AuraPluginManager plugins, InventoryOwnerModelAuthority models,
                                             OutboxEventMapper outbox, ObjectMapper json) {
        this.plugins = plugins; this.models = models; this.outbox = outbox; this.json = json;
    }

    /** The host pipeline supplies the actual registered instance; callers cannot supply stamp values. */
    public Object invoke(CommandHandlerExtension handler, CommandHandlerExtension.CommandContext context) throws Exception {
        if (!COMMAND.equals(handler.getCommandType())) return handler.execute(context);
        if (current.get() != null || !IMPLEMENTATION.equals(handler.getClass().getName())
                || !plugins.isDiscoveredExtensionInstance(CommandHandlerExtension.class, handler)
                || !COMMAND.equals(context.commandType()) || !"inv_inbound".equals(context.modelCode())
                || context.tenantId() == null || !context.tenantId().equals(MetaContext.getCurrentTenantId())
                || !COMMAND.equals(MetaContext.getAuthorizedCommandCode())
                || context.recordId() == null || !context.recordId().equals(MetaContext.getCommandPermitTargetFor("inv_inbound")))
            throw denied();
        var artifact = plugins.observeLoadedArtifact(handler.getClass())
                .filter(value -> "com.auraboot.inventory".equals(value.pluginId())).orElseThrow(InventoryReceiptEnqueueProvenance::denied);
        var model = models.resolve(context.tenantId(), "inv_inbound", true);
        current.set(new Invocation(handler, context, artifact, Producer.INBOUND, model));
        try { return handler.execute(context); } finally { current.remove(); }
    }

    /** Route a pipeline-executed primary to the trusted producer entry its authorized command owns. */
    public Object route(CommandHandlerExtension handler, CommandHandlerExtension.CommandContext context) throws Exception {
        if (FINISHED_GOODS_COMMAND.equals(MetaContext.getAuthorizedCommandCode())
                && FINISHED_GOODS_COMMAND.equals(handler.getCommandType())) {
            return invokeFinishedGoodsReceipt(handler, context);
        }
        return invoke(handler, context);
    }

    /**
     * Trusted entry for the finished-goods receipt producer. The hosting pipeline authorizes
     * inv:confirm_finished_goods_receipt against the inv_fg_pack_task permit; the handler posts
     * the canonical receipt fact for the inv_inbound record linked to that task. Every stamp input
     * remains a pipeline authorize fact - the caller supplies no stamp values.
     */
    public Object invokeFinishedGoodsReceipt(CommandHandlerExtension handler, CommandHandlerExtension.CommandContext context) throws Exception {
        if (current.get() != null || !FINISHED_GOODS_IMPLEMENTATION.equals(handler.getClass().getName())
                || !plugins.isDiscoveredExtensionInstance(CommandHandlerExtension.class, handler)
                || !FINISHED_GOODS_COMMAND.equals(context.commandType()) || !FINISHED_GOODS_MODEL.equals(context.modelCode())
                || context.tenantId() == null || !context.tenantId().equals(MetaContext.getCurrentTenantId())
                || !FINISHED_GOODS_COMMAND.equals(MetaContext.getAuthorizedCommandCode())
                || context.recordId() == null || !context.recordId().equals(MetaContext.getCommandPermitTargetFor(FINISHED_GOODS_MODEL)))
            throw denied();
        var artifact = plugins.observeLoadedArtifact(handler.getClass())
                .filter(value -> "com.auraboot.inventory".equals(value.pluginId())).orElseThrow(InventoryReceiptEnqueueProvenance::denied);
        var model = models.resolve(context.tenantId(), FINISHED_GOODS_MODEL, true);
        current.set(new Invocation(handler, context, artifact, Producer.FINISHED_GOODS, model));
        try { return handler.execute(context); } finally { current.remove(); }
    }

    static void rejectReservedHeaders(IntegrationEventEnvelope event) {
        if (event.headers().keySet().stream().anyMatch(key -> key.startsWith(PREFIX))) throw denied();
    }

    IntegrationEventEnvelope stamp(IntegrationEventEnvelope event) {
        rejectReservedHeaders(event);
        if (!EVENT.equals(event.eventType())) return event;
        Invocation invocation = current.get();
        if (invocation == null) throw denied();
        // The inbound producer must be the innermost artifact-bearing frame; the finished-goods
        // producer reaches the fact through the nested warehouse-in confirmation, so any frame of
        // its own execution suffices.
        boolean inbound = invocation.producer() == Producer.INBOUND;
        boolean producerInStack = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                .walk(frames -> {
                    var artifactFrames = frames.map(StackWalker.StackFrame::getDeclaringClass)
                            .filter(type -> plugins.observeLoadedArtifact(type).isPresent());
                    return inbound
                            ? artifactFrames.findFirst().map(type -> type == invocation.handler().getClass()).orElse(false)
                            : artifactFrames.anyMatch(type -> type == invocation.handler().getClass());
                });
        if (!producerInStack) throw denied();
        validateReceiptIdentity(event);
        String producerCommand = inbound ? COMMAND : FINISHED_GOODS_COMMAND;
        String producerPermitModel = inbound ? "inv_inbound" : FINISHED_GOODS_MODEL;
        if (!Long.valueOf(event.tenantId()).equals(invocation.context().tenantId())
                || (!inbound && !invocation.context().recordId().equals(event.payload().get("receiptId")))
                || !producerCommand.equals(MetaContext.getAuthorizedCommandCode())
                || !invocation.context().recordId().equals(MetaContext.getCommandPermitTargetFor(producerPermitModel))
                || !Long.valueOf(event.tenantId()).equals(MetaContext.getCurrentTenantId())) throw denied();
        var artifact = plugins.observeLoadedArtifact(invocation.handler().getClass()).orElseThrow(InventoryReceiptEnqueueProvenance::denied);
        if (!artifact.equals(invocation.artifact()) || !artifact.digest().matches("sha256:[a-f0-9]{64}")) throw denied();
        InventoryOwnerModelAuthority.assertUnchanged(invocation.model(), models.resolve(event.tenantId(), producerPermitModel, true));
        Map<String,String> headers = new HashMap<>(event.headers());
        headers.put(PREFIX + "schema", "1"); headers.put(PREFIX + "plugin", artifact.pluginId());
        headers.put(PREFIX + "artifact", artifact.digest().substring("sha256:".length())); headers.put(PREFIX + "command", producerCommand);
        headers.put(PREFIX + "implementation", invocation.handler().getClass().getName());
        return copy(event, headers);
    }

    /** Called only at trusted counter entry inside the host's independent delivery transaction. */
    void lockPersistedDelivery(IntegrationEventEnvelope event, String leaseToken) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) throw denied();
        assertPersistedDelivery(event, leaseToken);
        if (outbox.lockActiveDeliveryLease(event.tenantId(), event.eventId(), leaseToken) == null) throw denied();
        assertPersistedDelivery(event, leaseToken);
    }

    /** Only the delivery host uses persisted evidence; header presence is never sufficient. */
    void assertPersistedDelivery(IntegrationEventEnvelope event, String leaseToken) {
        validateReceiptIdentity(event);
        String producerCommand = event.headers().get(PREFIX + "command");
        boolean inbound = COMMAND.equals(producerCommand);
        String producerImplementation = inbound ? IMPLEMENTATION : FINISHED_GOODS_IMPLEMENTATION;
        if (!"1".equals(event.headers().get(PREFIX + "schema"))
                || !"com.auraboot.inventory".equals(event.headers().get(PREFIX + "plugin"))
                || (!inbound && !FINISHED_GOODS_COMMAND.equals(producerCommand))
                || !producerImplementation.equals(event.headers().get(PREFIX + "implementation"))
                || event.headers().get(PREFIX + "artifact") == null
                || !event.headers().get(PREFIX + "artifact").matches("[a-f0-9]{64}")) throw denied();
        OutboxEvent expected = new OutboxEvent(); expected.setTenantId(event.tenantId()); expected.setEventId(event.eventId());
        expected.setEventType(event.eventType()); expected.setCommandCode(producerCommand); expected.setSchemaVersion(event.schemaVersion());
        expected.setEventSource(event.source()); expected.setSubject(event.subject()); expected.setOccurredAt(event.occurredAt());
        expected.setCorrelationId(event.correlationId()); expected.setCausationId(event.causationId());
        expected.setOrderingKey(event.orderingKey()); expected.setEventSequence(event.sequence());
        try { expected.setPayload(json.writeValueAsString(event.payload())); expected.setHeaders(json.writeValueAsString(event.headers())); }
        catch (JsonProcessingException failure) { throw new IllegalArgumentException("Inventory posted fact is not serializable", failure); }
        if (outbox.countMatchingEnvelope(expected) != 1
                || outbox.countActiveDeliveryLease(event.tenantId(), event.eventId(), leaseToken) != 1) throw denied();
    }

    private static void validateReceiptIdentity(IntegrationEventEnvelope event) {
        Object receipt = event.payload().get("receiptId");
        if (!IntegrationEventEnvelope.VERSION_1.equals(event.schemaVersion()) || !EVENT.equals(event.eventType())
                || !"inventory".equals(event.source()) || event.tenantId() <= 0 || event.sequence() <= 0
                || !(receipt instanceof String pid) || pid.isBlank() || pid.length() > 128
                || integer(event.payload().get("schemaVersion")) != 1
                || integer(event.payload().get("tenantId")) != event.tenantId()
                || integer(event.payload().get("receiptVersion")) != event.sequence()
                || !event.eventId().equals(event.payload().get("eventId"))
                || !event.subject().equals("inv_inbound/" + pid)
                || !event.orderingKey().equals(event.tenantId() + ":inv_inbound:" + pid)
                || !event.eventId().equals(UUID.nameUUIDFromBytes((EVENT + ":" + event.tenantId() + ":" + pid + ":"
                    + event.sequence()).getBytes(StandardCharsets.UTF_8)).toString())) throw denied();
    }
    private static long integer(Object value) {
        if (!(value instanceof Number)) throw denied();
        try { return new BigDecimal(value.toString()).longValueExact(); }
        catch (NumberFormatException | ArithmeticException failure) { throw denied(); }
    }
    private static IntegrationEventEnvelope copy(IntegrationEventEnvelope event, Map<String,String> headers) {
        return new IntegrationEventEnvelope(event.schemaVersion(),event.eventId(),event.eventType(),event.source(),event.subject(),
                event.occurredAt(),event.tenantId(),event.correlationId(),event.causationId(),event.orderingKey(),event.sequence(),event.payload(),headers);
    }
    private static SecurityException denied() { return new SecurityException("Inventory posted receipt lacks its trusted host producer provenance"); }
}
