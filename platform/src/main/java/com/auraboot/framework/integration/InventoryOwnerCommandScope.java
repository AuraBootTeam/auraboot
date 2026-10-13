package com.auraboot.framework.integration;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelope;
import com.auraboot.framework.plugin.extension.integration.ReliableEventConsumerExtension;
import com.auraboot.framework.plugin.pf4j.AuraPluginManager;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Host-only authorization for the registered Inventory intake consumer, never request-selected. */
public final class InventoryOwnerCommandScope {
    static final String CONSUMER = "inventory.public-command-adapter.v1";
    static final String EVENT = "procurement.purchase-order.receipt-requested.v1";
    private static final String COMMAND = "inv:receive_source_receipt";
    private static final String IMPLEMENTATION = "com.auraboot.plugins.inventory.ReliableInventoryEventConsumer";
    private static final Set<String> WRITES = Set.of("inv_inbound", "inv_inbound_line");
    private static final Set<String> READS = Set.of("inv_inbound", "inv_inbound_line", "inv_warehouse",
            "inv_warehouse_location", "prod_product", "pe_product");
    private record Permit(long tenantId, String eventId, InventoryOwnerModelAuthority authority,
                          Map<String, InventoryOwnerModelAuthority.Binding> bindings) {}
    private static final ThreadLocal<Permit> CURRENT = new ThreadLocal<>();
    private InventoryOwnerCommandScope() {}

    static void consume(IntegrationEventEnvelope event, ReliableEventConsumerExtension consumer,
                        AuraPluginManager plugins, InventoryOwnerModelAuthority authority, Runnable work) {
        if (!CONSUMER.equals(consumer.consumerCode()) || !EVENT.equals(event.eventType())) {
            if (CURRENT.get() != null)
                throw new SecurityException("Nested delivery cannot inherit Inventory owner authority");
            work.run();
            return;
        }
        if (plugins == null || authority == null || !IMPLEMENTATION.equals(consumer.getClass().getName())
                || plugins.observeLoadedArtifact(consumer.getClass())
                    .filter(artifact -> "com.auraboot.inventory".equals(artifact.pluginId())).isEmpty()
                || !plugins.isDiscoveredExtensionInstance(ReliableEventConsumerExtension.class, consumer))
            throw new SecurityException("Inventory intake consumer is not its registered started plugin");
        if (!"1.0".equals(event.schemaVersion()) || !"procurement".equals(event.source())
                || event.tenantId() <= 0 || !Long.valueOf(event.tenantId()).equals(MetaContext.getCurrentTenantId())
                || event.eventId() == null || event.eventId().isBlank()
                || !COMMAND.equals(event.payload().get("targetCommand"))
                || !Set.of("procurement.purchase_receipt", "procurement.purchase_order")
                    .contains(event.payload().get("sourceType"))
                || event.payload().containsKey("tenantId") || CURRENT.get() != null)
            throw new SecurityException("Inventory intake envelope does not match its fixed owner contract");
        CURRENT.set(new Permit(event.tenantId(), event.eventId(), authority, new HashMap<>()));
        try { work.run(); } finally { CURRENT.remove(); }
    }

    /** Called by the host accessor only; this API cannot create an authorization permit. */
    public static <T> T access(long tenantId, String model, String operation, Supplier<T> work) {
        Permit permit = CURRENT.get();
        if (permit == null) return work.get();
        boolean read = "read".equals(operation);
        if (tenantId != permit.tenantId() || !Long.valueOf(tenantId).equals(MetaContext.getCurrentTenantId())
                || model == null || !(read ? READS.contains(model) : "create".equals(operation) && WRITES.contains(model)))
            throw new SecurityException("Inventory owner intake access is outside its tenant/model/operation boundary");
        InventoryOwnerModelAuthority.Binding observed = permit.authority().resolve(tenantId, model, !read);
        InventoryOwnerModelAuthority.Binding approved = permit.bindings().putIfAbsent(model, observed);
        if (approved == null) approved = observed;
        InventoryOwnerModelAuthority.assertUnchanged(approved, observed);
        InventoryOwnerModelAuthority.Binding lease = approved;
        // The fixed row grade applies only to this approved model, never a null/global target.
        return MetaContext.runWithCommandPermitPlan("ALL", null, model, null,
                () -> MetaContext.runWithAuthorizedCommandCode(COMMAND, () -> {
                    T result = work.get();
                    InventoryOwnerModelAuthority.assertUnchanged(lease, permit.authority().resolve(tenantId, model, !read));
                    return result;
                }));
    }

    public static boolean active() { return CURRENT.get() != null; }
}
