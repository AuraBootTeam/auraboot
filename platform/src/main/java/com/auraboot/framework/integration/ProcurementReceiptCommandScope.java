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
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/** Private delivery authority: persisted Inventory facts advance qualified counters and their exact source state. */
public final class ProcurementReceiptCommandScope {
    static final String CONSUMER = "procurement.confirmed-receipt-counter.v1";
    static final String COMMAND = "pr:consume_confirmed_inventory_receipt";
    static final String MODEL = "pr_purchase_order_line";
    private static final String IMPLEMENTATION = "com.auraboot.plugins.procurement.ReliableProcurementReceiptConsumer";
    private static final Set<String> READS = Set.of(MODEL, "pr_purchase_order", "pr_purchase_receipt", "pr_purchase_receipt_line",
            "pr_inventory_operation_request", "prod_product", "pe_product");
    private record Permit(IntegrationEventEnvelope event, String lease, InventoryReceiptEnqueueProvenance provenance,
                          InventoryOwnerModelAuthority authority, Map<String, Boolean> targets,
                          Map<String, InventoryOwnerModelAuthority.Binding> bindings, Set<String> applied,
                          java.util.concurrent.atomic.AtomicBoolean sourceApplied) {}
    private static final ThreadLocal<Permit> CURRENT = new ThreadLocal<>();
    private record CounterWrite(Permit permit, String record, long version, BigDecimal quantity,
                                String parentPid, long parentVersion, boolean managed, java.util.concurrent.atomic.AtomicBoolean claimed) {}
    private static final ThreadLocal<CounterWrite> COUNTER_WRITE = new ThreadLocal<>();

    /** Read-only proof returned only while the verified host CAS delegate is executing. */
    public record VerifiedCounterParent(String pid, long version, boolean managed) {}
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
                || !plugins.isDiscoveredExtensionInstance(ReliableEventConsumerExtension.class, consumer)) throw denied();
        provenance.lockPersistedDelivery(event, lease);
        Object raw = event.payload().get("lines");
        if (!(raw instanceof List<?> lines) || lines.isEmpty() || lines.size() > 4096) throw denied();
        Map<String, Boolean> targets = new HashMap<>();
        boolean ownerOrigin = hasReceiptOwnerOrigin(event);
        for (Object value : lines) {
            if (!(value instanceof Map<?,?> line)) throw denied();
            Object origin = line.get("sourceLine");
            if (!(origin instanceof Map<?,?> source)) continue;
            boolean managed = Boolean.TRUE.equals(source.get("collaborationQualified"));
            boolean ordinary = ownerOrigin && Boolean.FALSE.equals(source.get("collaborationQualified"));
            if (managed || ordinary) {
                Object target = source.get("purchaseOrderLinePid");
                if (!(target instanceof String pid) || pid.isBlank() || pid.length() > 128) throw denied();
                if (ordinary && (!"pr_purchase_receipt_line".equals(source.get("modelCode"))
                        || !(source.get("linePid") instanceof String sourceLine) || sourceLine.isBlank()
                        || sourceLine.length() > 128)) throw denied();
                Boolean previous = targets.putIfAbsent(pid, managed);
                if (previous != null && previous != managed) throw denied();
            }
        }
        if (new HashSet<>(targets.values()).size() > 1) throw denied();
        CURRENT.set(new Permit(event, lease, provenance, authority, Map.copyOf(targets), new HashMap<>(), new HashSet<>(), new java.util.concurrent.atomic.AtomicBoolean()));
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
        if ("pr_purchase_receipt".equals(model))
            return confirmSource(permit, tenant, record, field, expected, patch, data);
        if (!MODEL.equals(model) || !permit.targets().containsKey(record) || permit.applied().contains(record)
                || !"row_version".equals(field) || patch == null || !patch.keySet().equals(Set.of("pr_pol_received_qty"))) throw denied();
        boolean managed = permit.targets().get(record);
        Map<String,Object> immutablePatch = Map.copyOf(patch);
        var binding = binding(permit, model, true);
        return MetaContext.runWithCommandPermitPlan("ALL", null, model, record, () -> {
            Map<String,Object> row = data.getById(model, record);
            if (row == null || !record.equals(row.get("pid")) || number(row.get("tenant_id")) != tenant
                    || markerMode(row.get("pr_pol_collaboration_managed")) != managed) throw denied();
            long version = number(row.get("row_version"));
            if (version < 0 || version != number(expected)) throw denied();
            BigDecimal next = decimal(immutablePatch.get("pr_pol_received_qty"));
            BigDecimal previous = decimal(row.get("pr_pol_received_qty"));
            BigDecimal ordered = decimal(row.get("pr_pol_qty"));
            if (next.compareTo(previous) <= 0 || next.compareTo(ordered) > 0) throw denied();
            Object parentIdentity = row.get("pr_pol_order_id");
            if (!(parentIdentity instanceof String parentPid) || parentPid.isBlank() || parentPid.length() > 128) throw denied();
            binding(permit, "pr_purchase_order", false);
            Map<String,Object> parent = MetaContext.runWithCommandPermitPlan("ALL", null, "pr_purchase_order", parentPid,
                    () -> data.getById("pr_purchase_order", parentPid));
            if (parent == null || !parentPid.equals(parent.get("pid")) || number(parent.get("tenant_id")) != tenant
                    || markerMode(parent.get("pr_po_collaboration_managed")) != managed) throw denied();
            if (!managed) assertOrdinaryOwnerRequest(permit, tenant, parentPid, data);
            long parentVersion = number(parent.get("row_version"));
            if (parentVersion < 0 || COUNTER_WRITE.get() != null) throw denied();
            binding(permit, "pr_purchase_order", false);
            verify(permit);
            InventoryOwnerModelAuthority.assertUnchanged(binding, permit.authority().resolveProcurement(tenant, model, true));
            return MetaContext.runWithCommandPermitPlan("ALL", version, model, record,
                    () -> MetaContext.runWithAuthorizedCommandCode(COMMAND, () -> {
                        COUNTER_WRITE.set(new CounterWrite(permit, record, version, next, parentPid, parentVersion, managed,
                                new java.util.concurrent.atomic.AtomicBoolean()));
                        try {
                            boolean result = data.compareAndSet(model, record, field, version, immutablePatch);
                            InventoryOwnerModelAuthority.assertUnchanged(binding, permit.authority().resolveProcurement(tenant, model, true));
                            binding(permit, "pr_purchase_order", false);
                            verify(permit);
                            if (result) permit.applied().add(record);
                            return result;
                        } finally {
                            COUNTER_WRITE.remove();
                        }
                    }));
        });
    }

    /** Complete only the source of this fact after every qualified counter CAS succeeded. */
    private static boolean confirmSource(Permit permit, long tenant, String record, String field,
            Object expected, Map<String,Object> patch, DynamicDataService data) {
        String model = "pr_purchase_receipt";
        if (!"row_version".equals(field) || !Map.of("pr_rcpt_status", "confirmed").equals(patch)
                || permit.targets().isEmpty() || !permit.applied().equals(permit.targets().keySet())
                || permit.sourceApplied().get()
                || !"procurement.purchase_receipt".equals(permit.event().payload().get("sourceType"))
                || record == null || !record.equals(permit.event().payload().get("sourcePid"))) throw denied();
        Object originValue = permit.event().payload().get("sourceOrigin");
        if (!(originValue instanceof Map<?,?> origin) || !(origin.get("eventId") instanceof String originId)
                || !(origin.get("intentHash") instanceof String hash) || !hash.matches("[a-f0-9]{64}")) throw denied();
        var sourceBinding = binding(permit, model, true);
        return MetaContext.runWithCommandPermitPlan("ALL", null, model, record, () -> {
            Map<String,Object> source = data.getById(model, record);
            if (source == null || !record.equals(source.get("pid")) || number(source.get("tenant_id")) != tenant
                    || !"draft".equals(source.get("pr_rcpt_status"))
                    || !"enqueued".equals(source.get("pr_inventory_integration_status"))
                    || !hash.equals(source.get("pr_inventory_request_hash"))
                    || !(source.get("pr_inventory_request_id") instanceof String requestId) || requestId.isBlank()
                    || number(source.get("row_version")) != number(expected) || number(expected) < 0) throw denied();
            binding(permit, "pr_inventory_operation_request", false);
            Map<String,Object> request = MetaContext.runWithCommandPermitPlan("ALL", null,
                    "pr_inventory_operation_request", requestId, () -> data.getById("pr_inventory_operation_request", requestId));
            if (request == null || !requestId.equals(request.get("pid")) || number(request.get("tenant_id")) != tenant
                    || !"pr_purchase_receipt".equals(request.get("pr_ior_source_model"))
                    || !record.equals(request.get("pr_ior_source_id"))
                    || !"purchase_receipt".equals(request.get("pr_ior_operation"))
                    || !"enqueued".equals(request.get("pr_ior_status"))
                    || !originId.equals(request.get("pr_ior_event_id"))
                    || !hash.equals(request.get("pr_ior_intent_hash"))
                    || !(source.get("pr_inventory_request_key") instanceof String key) || key.isBlank()
                    || !key.equals(request.get("pr_ior_request_key"))
                    || number(request.get("pr_ior_source_version")) != number(origin.get("sourceVersion"))) throw denied();
            verify(permit);
            return MetaContext.runWithCommandPermitPlan("ALL", number(expected), model, record,
                    () -> MetaContext.runWithAuthorizedCommandCode(COMMAND, () -> {
                        if (!permit.sourceApplied().compareAndSet(false, true)) throw denied();
                        boolean result = data.compareAndSet(model, record, field, expected, Map.of("pr_rcpt_status", "confirmed"));
                        InventoryOwnerModelAuthority.assertUnchanged(sourceBinding, permit.authority().resolveProcurement(tenant, model, true));
                        binding(permit, "pr_inventory_operation_request", false);
                        verify(permit);
                        return result;
                    }));
        });
    }

    /**
     * A command name or an active consumer is insufficient. Match the final scoped SQL inputs
     * to this one host-verified CAS frame, including the normalized single counter value.
     */
    public static Optional<VerifiedCounterParent> verifiedCounterParent(long tenant, String model, String record,
            Object expectedVersion, String compareColumn, Object compareValue, Map<String,Object> columns) {
        CounterWrite write = COUNTER_WRITE.get();
        if (write == null) return Optional.empty();
        Permit permit = require(tenant);
        if (permit != write.permit() || !MODEL.equals(model) || !write.record().equals(record)
                || !COMMAND.equals(MetaContext.getAuthorizedCommandCode())
                || !Long.valueOf(write.version()).equals(MetaContext.getCommandExpectedVersion(model, record))
                || !"row_version".equals(compareColumn) || number(expectedVersion) != write.version()
                || number(compareValue) != write.version() || columns == null
                || !columns.containsKey("pr_pol_received_qty")
                || !Set.of("pr_pol_received_qty", "updated_at", "updated_by").containsAll(columns.keySet())
                || decimal(columns.get("pr_pol_received_qty")).compareTo(write.quantity()) != 0) throw denied();
        binding(permit, MODEL, true);
        binding(permit, "pr_purchase_order", false);
        if (!write.claimed().compareAndSet(false, true)) throw denied();
        return Optional.of(new VerifiedCounterParent(write.parentPid(), write.parentVersion(), write.managed()));
    }

    private static boolean markerMode(Object value) {
        if (value == null) return false;
        if (!(value instanceof Boolean flag)) throw denied();
        return flag;
    }

    private static boolean hasReceiptOwnerOrigin(IntegrationEventEnvelope event) {
        if (!"procurement.purchase_receipt".equals(event.payload().get("sourceType"))
                || !event.payload().containsKey("sourceOrigin")) return false;
        if (!(event.payload().get("sourcePid") instanceof String pid) || pid.isBlank() || pid.length() > 128
                || !(event.payload().get("sourceOrigin") instanceof Map<?,?> origin)
                || !(origin.get("eventId") instanceof String id) || id.isBlank() || id.length() > 128
                || !(origin.get("intentHash") instanceof String hash) || !hash.matches("[a-f0-9]{64}")
                || number(origin.get("sourceVersion")) < 0 || number(origin.get("sourceVersion")) > Integer.MAX_VALUE) throw denied();
        return true;
    }

    /** Ordinary rows need the same bound private source claim before any counter write. */
    private static void assertOrdinaryOwnerRequest(Permit permit, long tenant, String parentPid, DynamicDataService data) {
        if (!hasReceiptOwnerOrigin(permit.event())) throw denied();
        String pid = (String) permit.event().payload().get("sourcePid");
        Map<?,?> origin = (Map<?,?>) permit.event().payload().get("sourceOrigin");
        Map<String,Object> source = readOwnerRecord(permit, "pr_purchase_receipt", pid, data);
        if (source == null || !pid.equals(source.get("pid")) || number(source.get("tenant_id")) != tenant
                || !parentPid.equals(source.get("pr_rcpt_po_id")) || number(source.get("row_version")) < 0
                || !"draft".equals(source.get("pr_rcpt_status"))
                || !"enqueued".equals(source.get("pr_inventory_integration_status"))
                || !origin.get("intentHash").equals(source.get("pr_inventory_request_hash"))
                || !(source.get("pr_inventory_request_id") instanceof String requestPid) || requestPid.isBlank()
                || !(source.get("pr_inventory_request_key") instanceof String key) || key.isBlank()) throw denied();
        Map<String,Object> request = readOwnerRecord(permit, "pr_inventory_operation_request", requestPid, data);
        if (request == null || !requestPid.equals(request.get("pid")) || number(request.get("tenant_id")) != tenant
                || !"pr_purchase_receipt".equals(request.get("pr_ior_source_model"))
                || !pid.equals(request.get("pr_ior_source_id")) || !"purchase_receipt".equals(request.get("pr_ior_operation"))
                || !"enqueued".equals(request.get("pr_ior_status")) || !key.equals(request.get("pr_ior_request_key"))
                || !origin.get("eventId").equals(request.get("pr_ior_event_id"))
                || !origin.get("intentHash").equals(request.get("pr_ior_intent_hash"))
                || number(origin.get("sourceVersion")) != number(request.get("pr_ior_source_version"))) throw denied();
    }

    private static Map<String,Object> readOwnerRecord(Permit permit, String model, String pid, DynamicDataService data) {
        binding(permit, model, false);
        Map<String,Object> row = MetaContext.runWithCommandPermitPlan("ALL", null, model, pid, () -> data.getById(model, pid));
        binding(permit, model, false);verify(permit);return row;
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
