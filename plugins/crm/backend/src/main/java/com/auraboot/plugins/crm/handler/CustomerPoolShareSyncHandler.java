package com.auraboot.plugins.crm.handler;

import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.DataAccessor;
import com.auraboot.framework.plugin.extension.RecordShareAccessor;
import com.auraboot.plugins.crm.engine.CustomerPoolRules;
import org.pf4j.Extension;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Synchronises pool membership into record ACLs after declarative pool create/update. */
@Extension
public class CustomerPoolShareSyncHandler implements CommandHandlerExtension {

    public static final String CREATE = "crm:create_customer_pool";
    public static final String UPDATE = "crm:update_customer_pool";
    private static final Set<String> TYPES = Set.of(CREATE, UPDATE);

    @Override public String getCommandType() { return CREATE; }
    @Override public Set<String> getSupportedCommandTypes() { return TYPES; }
    @Override public boolean supports(String commandType) { return TYPES.contains(commandType); }
    @Override public boolean chainsAfterPrimary() { return true; }

    @Override
    public Object execute(CommandContext context) {
        DataAccessor db = context.dataAccessor();
        RecordShareAccessor shares = context.recordShareAccessor();
        if (db == null || shares == null) {
            throw new IllegalStateException("Customer-pool ACL synchronisation bridge unavailable");
        }
        String poolId = required(context.recordId(), "Customer pool id is required after persistence");
        Map<String, Object> pool = db.getById("crm_customer_pool_common", poolId);
        if (pool == null) throw new IllegalArgumentException("Customer pool not found: " + poolId);

        Set<String> poolUsers = poolUsers(pool);
        shares.replaceReadSharesForUsers(context.tenantId(), "crm_customer_pool_common", poolId, poolUsers);

        List<Map<String, Object>> items = db.query("crm_customer_pool_item_common", Map.of("crm_cpi_pool_id", poolId));
        if (items != null) {
            for (Map<String, Object> item : items) {
                String itemId = required(item.get("pid"), "Pool item pid is required");
                String status = String.valueOf(item.get("crm_cpi_status"));
                if ("available".equals(status) || "claiming".equals(status)) {
                    shares.replaceReadUpdateSharesForUsers(
                            context.tenantId(), "crm_customer_pool_item_common", itemId, poolUsers);
                } else {
                    Object owner = item.get("crm_cpi_claimed_by");
                    shares.replaceReadUpdateSharesForUsers(context.tenantId(), "crm_customer_pool_item_common", itemId,
                            owner == null ? Set.of() : Set.of(String.valueOf(owner)));
                }
            }
        }

        List<Map<String, Object>> history = db.query(
                "crm_customer_owner_history_common", Map.of("crm_coh_pool_id", poolId));
        if (history != null) {
            for (Map<String, Object> row : history) {
                shares.replaceReadSharesForUsers(context.tenantId(), "crm_customer_owner_history_common",
                        required(row.get("pid"), "Ownership-history pid is required"), poolUsers);
            }
        }
        return Map.of("poolSharesSynchronized", true);
    }

    static Set<String> poolUsers(Map<String, Object> pool) {
        LinkedHashSet<String> users = new LinkedHashSet<>(
                CustomerPoolRules.userIds(pool.get("crm_cp_member_user_ids")));
        users.addAll(CustomerPoolRules.userIds(pool.get("crm_cp_admin_user_ids")));
        return Set.copyOf(users);
    }

    private static String required(Object value, String message) {
        if (value == null || value.toString().isBlank()) throw new IllegalArgumentException(message);
        return value.toString().trim();
    }
}
