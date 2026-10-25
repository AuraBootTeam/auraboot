package com.auraboot.framework.integration;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.constant.SystemFieldConstants;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.plugin.entity.PluginRecord;
import com.auraboot.framework.plugin.mapper.PluginRecordMapper;
import java.util.Objects;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Resolves actual tenant metadata before granting the fixed intake model boundary. */
@Component
public final class InventoryOwnerModelAuthority {
    // Exact canonical procurement/plugin.json + procurement/config/models.json ownership.
    private static final Map<String,String> RECEIPT_READ_OWNERS = Map.of(
            "pr_purchase_order", "com.auraboot.procurement",
            "pr_purchase_order_line", "com.auraboot.procurement",
            "pr_purchase_receipt", "com.auraboot.procurement",
            "pr_purchase_receipt_line", "com.auraboot.procurement",
            "pr_inventory_operation_request", "com.auraboot.procurement");
    private static final Set<String> PRODUCT_READS = Set.of("prod_product", "pe_product");
    private final MetaModelService definitions;
    private final MetaModelMapper models;
    private final PluginRecordMapper plugins;

    public InventoryOwnerModelAuthority(MetaModelService definitions, MetaModelMapper models,
                                        PluginRecordMapper plugins) {
        this.definitions = definitions;
        this.models = models;
        this.plugins = plugins;
    }

    record Binding(Long id, String pid, Integer version, Integer rowVersion,
                   String ownerPid, String ownerId, String table) {}

    Binding resolve(long tenantId, String modelCode, boolean write) {
        return resolveOwned(tenantId, modelCode, write ? "com.auraboot.inventory" : null);
    }

    Binding resolveProcurement(long tenantId, String modelCode, boolean write) {
        if (write && !Set.of("pr_purchase_order_line", "pr_purchase_receipt").contains(modelCode)) throw denied();
        if (!RECEIPT_READ_OWNERS.containsKey(modelCode) && !PRODUCT_READS.contains(modelCode)) throw denied();
        return resolveOwned(tenantId, modelCode, RECEIPT_READ_OWNERS.get(modelCode));
    }

    private Binding resolveOwned(long tenantId, String modelCode, String writeOwner) {
        if (!Long.valueOf(tenantId).equals(MetaContext.getCurrentTenantId())) throw denied();
        Model model = models.findCurrentByCode(modelCode);
        ModelDefinition effective = definitions.getModelDefinition(modelCode).orElseThrow(InventoryOwnerModelAuthority::denied);
        if (model == null || !Long.valueOf(tenantId).equals(model.getTenantId())
                || !modelCode.equals(model.getCode()) || !Boolean.TRUE.equals(model.getIsCurrent())
                || !Boolean.FALSE.equals(model.getDeletedFlag()) || !"published".equals(model.getStatus())
                || model.getId() == null || model.getPid() == null || model.getPluginPid() == null
                || model.getVersion() == null || model.getRowVersion() == null
                || (model.getSourceType() != null && !"physical".equals(model.getSourceType()))) throw denied();
        PluginRecord owner = plugins.findByPid(model.getPluginPid());
        if (owner == null || !Long.valueOf(tenantId).equals(owner.getTenantId())
                || !model.getPluginPid().equals(owner.getPid()) || !Boolean.FALSE.equals(owner.getDeletedFlag())
                || owner.getPluginId() == null || owner.getPluginId().isBlank()
                || ((writeOwner != null && !writeOwner.equals(owner.getPluginId()))
                    || (modelCode.startsWith("inv_") && !"com.auraboot.inventory".equals(owner.getPluginId())))) throw denied();
        String expectedTable = SystemFieldConstants.generateTableName(modelCode);
        String storedTable = model.getTableName() == null || model.getTableName().isBlank()
                ? expectedTable : model.getTableName().trim();
        if (!expectedTable.equals(storedTable) || !expectedTable.equals(effective.getTableName())
                || !modelCode.equals(effective.getCode()) || !model.getId().equals(effective.getId())
                || !model.getVersion().equals(effective.getVersion()) || !"published".equals(effective.getStatus())
                || (effective.getSourceType() != null && !"physical".equals(effective.getSourceType()))) throw denied();
        return new Binding(model.getId(), model.getPid(), model.getVersion(), model.getRowVersion(),
                owner.getPid(), owner.getPluginId(), storedTable);
    }

    static void assertUnchanged(Binding approved, Binding observed) {
        if (!Objects.equals(approved, observed)) throw denied();
    }

    private static SecurityException denied() {
        return new SecurityException("Inventory intake model metadata does not match its approved tenant/owner/storage binding");
    }
}
