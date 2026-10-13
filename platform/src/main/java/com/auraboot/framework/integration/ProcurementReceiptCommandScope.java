package com.auraboot.framework.integration;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelope;
import com.auraboot.framework.plugin.extension.integration.ReliableEventConsumerExtension;
import com.auraboot.framework.plugin.pf4j.AuraPluginManager;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Private delivery authority: persisted Inventory facts can advance only their qualified PO lines. */
public final class ProcurementReceiptCommandScope {
    static final String CONSUMER = "procurement.confirmed-receipt-counter.v1";
    static final String COMMAND = "pr:consume_confirmed_inventory_receipt";
    static final String MODEL = "pr_purchase_order_line";
    private static final String IMPLEMENTATION = "com.auraboot.plugins.procurement.ReliableProcurementReceiptConsumer";
    private static final Set<String> READS = Set.of(MODEL, "pr_purchase_order", "pr_purchase_receipt", "pr_purchase_receipt_line",
            "pr_inventory_operation_request", "prod_product", "pe_product");
    private record Permit(IntegrationEventEnvelope event, String lease, InventoryReceiptEnqueueProvenance provenance,
                          InventoryOwnerModelAuthority authority, Set<String> targets,
                          Map<String, InventoryOwnerModelAuthority.Binding> bindings, Set<String> applied) {}
    private static final ThreadLocal<Permit> CURRENT = new ThreadLocal<>();
    private ProcurementReceiptCommandScope() {}

    static void consume(IntegrationEventEnvelope event, ReliableEventConsumerExtension consumer,
                        AuraPluginManager plugins, InventoryOwnerModelAuthority authority,
                        InventoryReceiptEnqueueProvenance provenance, String lease, Runnable work) {
        if (!CONSUMER.equals(consumer.consumerCode())) {
            if (CURRENT.get() != null) throw denied();
            work.run(); return;
        }
        if (CURRENT.get() != null || InventoryOwnerCommandScope.active() || plugins == null || authority == null
                || provenance == null || !IMPLEMENTATION.equals(consumer.getClass().getName())
                || !InventoryReceiptEnqueueProvenance.EVENT.equals(event.eventType())
                || !Long.valueOf(event.tenantId()).equals(MetaContext.getCurrentTenantId())
                || plugins.observeLoadedArtifact(consumer.getClass()).filter(a -> "com.auraboot.procurement".equals(a.pluginId())).isEmpty()
                || plugins.getExtensionsOfType(ReliableEventConsumerExtension.class).stream().noneMatch(c -> c == consumer)) throw denied();
        provenance.lockPersistedDelivery(event, lease);
        Object raw = event.payload().get("lines");
        if (!(raw instanceof List<?> lines) || lines.isEmpty() || lines.size() > 4096) throw denied();
        Set<String> targets = new HashSet<>();
        for (Object value : lines) {
            if (!(value instanceof Map<?,?> line)) throw denied();
            Object origin = line.get("sourceLine");
            if (origin instanceof Map<?,?> source && Boolean.TRUE.equals(source.get("collaborationQualified"))) {
                Object target = source.get("purchaseOrderLinePid");
                if (!(target instanceof String pid) || pid.isBlank() || pid.length() > 128) throw denied();
                targets.add(pid);
            }
        }
        CURRENT.set(new Permit(event, lease, provenance, authority, Set.copyOf(targets), new HashMap<>(), new HashSet<>()));
        try { work.run(); verify(CURRENT.get()); } finally { CURRENT.remove(); }
    }

    public static boolean active() { return CURRENT.get() != null; }

    public static <T> T access(long tenant, String model, String operation, Supplier<T> work) {
        Permit permit = require(tenant);
        if (!"read".equals(operation) || !READS.contains(model)) throw denied();
        var binding = binding(permit, model, false);
        return MetaContext.runWithCommandPermitPlan("ALL", null, model, null, () -> {
            T result = work.get();
            InventoryOwnerModelAuthority.assertUnchanged(binding, permit.authority().resolveProcurement(tenant, model, false));
            verify(permit); return result;
        });
    }

    /** No general update authority: exact single-field CAS, server-read version and approved target. */
    public static boolean compareAndSet(long tenant, String model, String record, String field,
                                       Object expected, Map<String,Object> patch, DynamicDataService data) {
        Permit permit = require(tenant);
        if (!MODEL.equals(model) || !permit.targets().contains(record) || permit.applied().contains(record)
                || !"row_version".equals(field) || patch == null || !patch.keySet().equals(Set.of("pr_pol_received_qty"))) throw denied();
        Map<String,Object> immutablePatch = Map.copyOf(patch);
        var binding = binding(permit, model, true);
        return MetaContext.runWithCommandPermitPlan("ALL", null, model, record, () -> {
            Map<String,Object> row = data.getById(model, record);
            if (row == null || !record.equals(row.get("pid")) || number(row.get("tenant_id")) != tenant
                    || !Boolean.TRUE.equals(row.get("pr_pol_collaboration_managed"))) throw denied();
            long version = number(row.get("row_version"));
            if (version < 0 || version != number(expected)) throw denied();
            BigDecimal next = decimal(immutablePatch.get("pr_pol_received_qty"));
            BigDecimal previous = decimal(row.get("pr_pol_received_qty"));
            BigDecimal ordered = decimal(row.get("pr_pol_qty"));
            if (next.compareTo(previous) <= 0 || next.compareTo(ordered) > 0) throw denied();
            verify(permit);
            InventoryOwnerModelAuthority.assertUnchanged(binding, permit.authority().resolveProcurement(tenant, model, true));
            return MetaContext.runWithCommandPermitPlan("ALL", version, model, record,
                    () -> MetaContext.runWithAuthorizedCommandCode(COMMAND, () -> {
                        boolean result = data.compareAndSet(model, record, field, version, immutablePatch);
                        InventoryOwnerModelAuthority.assertUnchanged(binding, permit.authority().resolveProcurement(tenant, model, true));
                        verify(permit);
                        if (result) permit.applied().add(record);
                        return result;
                    }));
        });
    }

    private static Permit require(long tenant) {
        Permit permit = CURRENT.get();
        if (permit == null || tenant != permit.event().tenantId()
                || !Long.valueOf(tenant).equals(MetaContext.getCurrentTenantId())) throw denied();
        verify(permit); return permit;
    }
    private static void verify(Permit permit) { permit.provenance().assertPersistedDelivery(permit.event(), permit.lease()); }
    private static InventoryOwnerModelAuthority.Binding binding(Permit permit, String model, boolean write) {
        var observed = permit.authority().resolveProcurement(permit.event().tenantId(), model, write);
        var approved = permit.bindings().putIfAbsent(model, observed);
        if (approved == null) approved = observed;
        InventoryOwnerModelAuthority.assertUnchanged(approved, observed); return approved;
    }
    private static long number(Object value) {
        if (!(value instanceof Number)) throw denied();
        try { return new BigDecimal(value.toString()).longValueExact(); }
        catch (ArithmeticException | NumberFormatException failure) { throw denied(); }
    }
    private static BigDecimal decimal(Object value) {
        if (!(value instanceof Number) && !(value instanceof String)) throw denied();
        try {
            BigDecimal number = new BigDecimal(value.toString());
            if (number.signum() < 0 || number.scale() > 12 || number.precision() > 38) throw denied();
            return number;
        } catch (NumberFormatException failure) { throw denied(); }
    }
    private static SecurityException denied() { return new SecurityException("Confirmed receipt access is outside its fixed PO-line CAS authority"); }
}
