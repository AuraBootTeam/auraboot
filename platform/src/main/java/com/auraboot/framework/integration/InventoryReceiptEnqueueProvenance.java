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
    private static final String PREFIX = "core.inventory.producer.";
    private record Invocation(CommandHandlerExtension handler, CommandHandlerExtension.CommandContext context,
                              AuraPluginManager.LoadedPluginArtifact artifact,
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
                || plugins.getExtensionsOfType(CommandHandlerExtension.class).stream().noneMatch(registered -> registered == handler)
                || !COMMAND.equals(context.commandType()) || !"inv_inbound".equals(context.modelCode())
                || context.tenantId() == null || !context.tenantId().equals(MetaContext.getCurrentTenantId())
                || !COMMAND.equals(MetaContext.getAuthorizedCommandCode())
                || context.recordId() == null || !context.recordId().equals(MetaContext.getCommandPermitTargetFor("inv_inbound")))
            throw denied();
        var artifact = plugins.observeLoadedArtifact(handler.getClass())
                .filter(value -> "com.auraboot.inventory".equals(value.pluginId())).orElseThrow(InventoryReceiptEnqueueProvenance::denied);
        var model = models.resolve(context.tenantId(), "inv_inbound", true);
        current.set(new Invocation(handler, context, artifact, model));
        try { return handler.execute(context); } finally { current.remove(); }
    }

    static void rejectReservedHeaders(IntegrationEventEnvelope event) {
        if (event.headers().keySet().stream().anyMatch(key -> key.startsWith(PREFIX))) throw denied();
    }

    IntegrationEventEnvelope stamp(IntegrationEventEnvelope event) {
        rejectReservedHeaders(event);
        if (!EVENT.equals(event.eventType())) return event;
        Invocation invocation = current.get();
        if (invocation == null || !StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                .walk(frames -> frames.map(StackWalker.StackFrame::getDeclaringClass)
                        .filter(type -> plugins.observeLoadedArtifact(type).isPresent()).findFirst()
                        .map(type -> type == invocation.handler().getClass()).orElse(false))) throw denied();
        validateReceiptIdentity(event);
        if (!Long.valueOf(event.tenantId()).equals(invocation.context().tenantId())
                || !invocation.context().recordId().equals(event.payload().get("receiptId"))
                || !COMMAND.equals(MetaContext.getAuthorizedCommandCode())
                || !invocation.context().recordId().equals(MetaContext.getCommandPermitTargetFor("inv_inbound"))
                || !Long.valueOf(event.tenantId()).equals(MetaContext.getCurrentTenantId())) throw denied();
        var artifact = plugins.observeLoadedArtifact(invocation.handler().getClass()).orElseThrow(InventoryReceiptEnqueueProvenance::denied);
        if (!artifact.equals(invocation.artifact()) || !artifact.digest().matches("[a-f0-9]{64}")) throw denied();
        InventoryOwnerModelAuthority.assertUnchanged(invocation.model(), models.resolve(event.tenantId(), "inv_inbound", true));
        Map<String,String> headers = new HashMap<>(event.headers());
        headers.put(PREFIX + "schema", "1"); headers.put(PREFIX + "plugin", artifact.pluginId());
        headers.put(PREFIX + "artifact", artifact.digest()); headers.put(PREFIX + "command", COMMAND);
        headers.put(PREFIX + "implementation", IMPLEMENTATION);
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
        if (!"1".equals(event.headers().get(PREFIX + "schema"))
                || !"com.auraboot.inventory".equals(event.headers().get(PREFIX + "plugin"))
                || !COMMAND.equals(event.headers().get(PREFIX + "command"))
                || !IMPLEMENTATION.equals(event.headers().get(PREFIX + "implementation"))
                || event.headers().get(PREFIX + "artifact") == null
                || !event.headers().get(PREFIX + "artifact").matches("[a-f0-9]{64}")) throw denied();
        OutboxEvent expected = new OutboxEvent(); expected.setTenantId(event.tenantId()); expected.setEventId(event.eventId());
        expected.setEventType(event.eventType()); expected.setCommandCode(COMMAND); expected.setSchemaVersion(event.schemaVersion());
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
