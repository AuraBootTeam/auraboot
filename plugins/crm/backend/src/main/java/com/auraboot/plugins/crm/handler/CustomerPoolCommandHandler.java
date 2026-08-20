package com.auraboot.plugins.crm.handler;

import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.DataAccessor;
import com.auraboot.framework.plugin.extension.FileAccessor;
import com.auraboot.framework.plugin.extension.RecordShareAccessor;
import com.auraboot.plugins.crm.engine.CustomerPoolRules;
import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Cordys-parity customer-pool state machine. All mutations are tenant-scoped and transactional. */
@Extension
public class CustomerPoolCommandHandler implements CommandHandlerExtension {

    private static final Logger log = LoggerFactory.getLogger(CustomerPoolCommandHandler.class);

    public static final String MOVE = "crm:move_customer_to_pool";
    public static final String CLAIM = "crm:claim_pool_customer";
    public static final String ASSIGN = "crm:assign_pool_customer";
    public static final String UPDATE_CUSTOMER = "crm:update_pool_customer";
    public static final String DELETE_CUSTOMER = "crm:delete_pool_customer";
    public static final String TOGGLE = "crm:toggle_customer_pool";
    public static final String DELETE_POOL = "crm:delete_customer_pool";
    public static final String RUN_RECYCLE = "crm:run_customer_pool_recycle";
    public static final String DOWNLOAD_IMPORT_TEMPLATE = "crm:download_customer_pool_import_template";
    public static final String PRECHECK_IMPORT = "crm:precheck_customer_pool_import";
    public static final String IMPORT_CUSTOMERS = "crm:import_customer_pool_customers";
    private static final Set<String> TYPES = Set.of(
            MOVE, CLAIM, ASSIGN, UPDATE_CUSTOMER, DELETE_CUSTOMER, TOGGLE, DELETE_POOL, RUN_RECYCLE,
            DOWNLOAD_IMPORT_TEMPLATE, PRECHECK_IMPORT, IMPORT_CUSTOMERS);
    private static final Map<String, String> CUSTOMER_SNAPSHOT_FIELDS = Map.of(
            "crm_acc_name", "crm_cpi_account_name",
            "crm_acc_industry", "crm_cpi_industry",
            "crm_acc_phone", "crm_cpi_phone",
            "crm_acc_rating", "crm_cpi_rating");
    private static final Map<String, String> SNAPSHOT_PAYLOAD_FIELDS = Map.of(
            "crm_cpi_account_name", "crm_acc_name",
            "crm_cpi_industry", "crm_acc_industry",
            "crm_cpi_phone", "crm_acc_phone",
            "crm_cpi_rating", "crm_acc_rating");
    private static final Set<String> POOL_CUSTOMER_EDITABLE_FIELDS = Set.of(
            "crm_acc_name", "crm_acc_industry", "crm_acc_website", "crm_acc_phone",
            "crm_acc_address", "crm_acc_rating", "crm_acc_status", "crm_acc_remark");
    private static final Set<String> OPEN_CUSTOMER_STATES = Set.of("active");
    private static final Set<String> RECYCLE_SOURCE_STATES = Set.of("claimed", "assigned");
    private static final Set<String> RECYCLE_LEASE_STATES = Set.of("recycling", "recycling_retry");
    private static final Duration DEFAULT_RECYCLE_LEASE_TIMEOUT = Duration.ofMinutes(15);

    @Override
    public String getCommandType() {
        return MOVE;
    }

    @Override
    public Set<String> getSupportedCommandTypes() {
        return TYPES;
    }

    @Override
    public boolean supports(String commandType) {
        return TYPES.contains(commandType);
    }

    @Override
    public Object execute(CommandContext context) {
        return switch (context.commandType()) {
            case MOVE -> moveToPool(requireDb(context), required(context.recordId(), "Customer id is required"),
                    required(first(payload(context, "poolId"), payload(context, "crm_acc_last_pool_id")), "poolId is required"), requireActor(context),
                    string(payload(context, "reason")), "moved_to_pool", Instant.now(), requireShares(context), context.tenantId());
            case CLAIM -> claim(requireDb(context), required(context.recordId(), "Pool item id is required"), requireActor(context),
                    Instant.now(), requireShares(context), context.tenantId());
            case ASSIGN -> assign(requireDb(context), required(context.recordId(), "Pool item id is required"), requireActor(context),
                    required(first(payload(context, "assigneeId"), payload(context, "crm_cpi_claimed_by")), "assigneeId is required"),
                    Instant.now(), requireShares(context), context.tenantId());
            case UPDATE_CUSTOMER -> updatePoolCustomer(requireDb(context),
                    required(context.recordId(), "Pool item id is required"), requireActor(context), context.payload());
            case DELETE_CUSTOMER -> deletePoolCustomer(requireDb(context),
                    required(context.recordId(), "Pool item id is required"), requireActor(context));
            case TOGGLE -> toggle(requireDb(context), required(context.recordId(), "Pool id is required"), requireActor(context));
            case DELETE_POOL -> deletePool(requireDb(context), required(context.recordId(), "Pool id is required"), requireActor(context));
            case RUN_RECYCLE -> recycleDetailed(requireDb(context), requireShares(context), context.tenantId(), requireActor(context), Instant.now(),
                    DEFAULT_RECYCLE_LEASE_TIMEOUT).asMap();
            case DOWNLOAD_IMPORT_TEMPLATE -> CustomerPoolImportService.downloadTemplate();
            case PRECHECK_IMPORT -> CustomerPoolImportService.precheck(requireDb(context), requireFiles(context),
                    required(context.recordId(), "Pool id is required"), requireActor(context),
                    requireUploadOwner(context), context.payload());
            case IMPORT_CUSTOMERS -> CustomerPoolImportService.importCustomers(requireDb(context), requireFiles(context),
                    requireShares(context), context.tenantId(), required(context.recordId(), "Pool id is required"),
                    requireActor(context), requireUploadOwner(context), context.payload());
            default -> throw new IllegalArgumentException("Unsupported customer-pool command: " + context.commandType());
        };
    }

    static Map<String, Object> moveToPool(DataAccessor db, String customerId, String poolId, String actor,
                                           String reason, String event, Instant now,
                                           RecordShareAccessor shares, long tenantId) {
        Map<String, Object> pool = requireRecord(db, "crm_customer_pool_common", poolId, "Customer pool");
        if (!"enabled".equals(string(pool.get("crm_cp_status")))) {
            throw new IllegalStateException("Customer pool is disabled: " + poolId);
        }
        boolean systemRecycle = "auto_recycled".equals(event) && "system".equals(actor);
        if (!systemRecycle && !CustomerPoolRules.isAdministrator(pool.get("crm_cp_admin_user_ids"), actor)
                && !CustomerPoolRules.isMember(pool.get("crm_cp_member_user_ids"), pool.get("crm_cp_admin_user_ids"), actor)) {
            throw new SecurityException("Current user is not a member of customer pool " + poolId);
        }
        Map<String, Object> customer = requireRecord(db, "crm_account_common", customerId, "Customer");
        if (!OPEN_CUSTOMER_STATES.contains(string(customer.get("crm_acc_status")))) {
            throw new IllegalStateException("Only active customers may enter a public pool");
        }
        if ("in_pool".equals(string(customer.get("crm_acc_pool_state")))) {
            throw new IllegalStateException("Customer is already in a customer pool: " + customerId);
        }
        String previousOwner = string(customer.get("crm_acc_owner"));
        if (!systemRecycle && !CustomerPoolRules.isAdministrator(pool.get("crm_cp_admin_user_ids"), actor)
                && !actor.equals(previousOwner)) {
            throw new SecurityException("Only the current owner or a pool administrator may move a customer");
        }
        Map<String, Object> itemPatch = poolItemSnapshot(customer, customerId, poolId, previousOwner, actor, reason, now,
                intValue(pool.get("crm_cp_new_cooldown_days")));
        List<Map<String, Object>> existing = db.query("crm_customer_pool_item_common", Map.of("crm_cpi_account_key", customerId));
        Map<String, Object> item = existing == null || existing.isEmpty()
                ? db.create("crm_customer_pool_item_common", itemPatch)
                : db.update("crm_customer_pool_item_common", string(existing.getFirst().get("pid")), itemPatch);

        HashMap<String, Object> customerPatch = new HashMap<>();
        customerPatch.put("crm_acc_owner", null);
        customerPatch.put("crm_acc_pool_state", "in_pool");
        customerPatch.put("crm_acc_last_pool_id", poolId);
        db.update("crm_account_common", customerId, customerPatch);
        Map<String, Object> history = appendHistory(db, customerId, poolId, event, previousOwner, null,
                systemRecycle ? null : actor, reason, now);
        syncPoolRecordShares(shares, tenantId, pool, "crm_account_common", customerId);
        syncPoolRecordShares(shares, tenantId, pool, "crm_customer_pool_item_common", string(item.get("pid")));
        syncPoolRecordShares(shares, tenantId, pool, "crm_customer_owner_history_common", string(history.get("pid")));
        return Map.of("customerId", customerId, "poolId", poolId, "poolItemId", string(item.get("pid")), "status", "available");
    }

    private static Map<String, Object> claim(DataAccessor db, String itemId, String actor, Instant now,
                                              RecordShareAccessor shares, long tenantId) {
        Map<String, Object> item = requireRecord(db, "crm_customer_pool_item_common", itemId, "Pool item");
        String poolId = required(item.get("crm_cpi_pool_id"), "Pool item has no pool");
        Map<String, Object> pool = requirePoolMember(db, poolId, actor);
        boolean administrator = CustomerPoolRules.isAdministrator(pool.get("crm_cp_admin_user_ids"), actor);
        validateCapacity(db, actor);
        Instant enteredAt = instant(item.get("crm_cpi_entered_at"));
        if (!administrator && !CustomerPoolRules.cooldownElapsed(enteredAt,
                intValue(pool.get("crm_cp_new_cooldown_days")), now)) {
            throw new IllegalStateException("New customer cooldown has not elapsed; claim available at "
                    + CustomerPoolRules.releaseAt(enteredAt, intValue(pool.get("crm_cp_new_cooldown_days"))));
        }
        String previousOwner = string(item.get("crm_cpi_previous_owner"));
        if (!administrator && actor.equals(previousOwner) && !CustomerPoolRules.cooldownElapsed(enteredAt,
                intValue(pool.get("crm_cp_previous_owner_cooldown_days")), now)) {
            throw new IllegalStateException("Previous owner cooldown has not elapsed");
        }
        if (!administrator) incrementDailyQuota(db, poolId, actor, intValue(pool.get("crm_cp_daily_pick_limit")));
        if (!db.compareAndSet("crm_customer_pool_item_common", itemId, "crm_cpi_status", "available", "claiming")) {
            throw new IllegalStateException("Customer was already claimed or assigned");
        }
        return finishOwnership(db, item, itemId, actor, actor, "claimed", now, shares, tenantId, pool);
    }

    private static Map<String, Object> assign(DataAccessor db, String itemId, String actor, String assignee, Instant now,
                                               RecordShareAccessor shares, long tenantId) {
        Map<String, Object> item = requireRecord(db, "crm_customer_pool_item_common", itemId, "Pool item");
        String poolId = required(item.get("crm_cpi_pool_id"), "Pool item has no pool");
        Map<String, Object> pool = requirePoolMember(db, poolId, actor);
        if (!CustomerPoolRules.isAdministrator(pool.get("crm_cp_admin_user_ids"), actor)) {
            throw new SecurityException("Only a customer-pool administrator may assign customers");
        }
        if (!CustomerPoolRules.isMember(pool.get("crm_cp_member_user_ids"),
                pool.get("crm_cp_admin_user_ids"), assignee)) {
            throw new IllegalArgumentException("Assignee must be a member of the customer pool");
        }
        validateCapacity(db, assignee);
        if (!db.compareAndSet("crm_customer_pool_item_common", itemId, "crm_cpi_status", "available", "claiming")) {
            throw new IllegalStateException("Customer was already claimed or assigned");
        }
        return finishOwnership(db, item, itemId, actor, assignee, "assigned", now, shares, tenantId, pool);
    }

    private static Map<String, Object> finishOwnership(DataAccessor db, Map<String, Object> item, String itemId,
                                                        String actor, String owner, String event, Instant now,
                                                        RecordShareAccessor shares, long tenantId,
                                                        Map<String, Object> pool) {
        String customerId = required(item.get("crm_cpi_account_id"), "Pool item has no customer");
        String poolId = required(item.get("crm_cpi_pool_id"), "Pool item has no pool");
        db.update("crm_account_common", customerId, Map.of(
                "crm_acc_owner", owner,
                "crm_acc_pool_state", "owned",
                "crm_acc_last_pool_id", poolId,
                "crm_acc_claimed_at", now.toString()));
        db.update("crm_customer_pool_item_common", itemId, Map.of(
                "crm_cpi_status", event,
                "crm_cpi_claimed_at", now.toString(),
                "crm_cpi_claimed_by", owner));
        HashMap<String, Object> clearedLease = new HashMap<>();
        clearedLease.put("crm_cpi_recycle_token", null);
        db.update("crm_customer_pool_item_common", itemId, clearedLease);
        Map<String, Object> history = appendHistory(db, customerId, poolId, event,
                string(item.get("crm_cpi_previous_owner")), owner, actor, null, now);
        shares.replaceReadUpdateSharesForUsers(
                tenantId, "crm_customer_pool_item_common", itemId, Set.of(owner));
        shares.replaceReadUpdateSharesForUsers(
                tenantId, "crm_account_common", customerId, Set.of(owner));
        syncPoolRecordShares(shares, tenantId, pool, "crm_customer_owner_history_common", string(history.get("pid")));
        return Map.of("customerId", customerId, "poolId", poolId, "ownerId", owner, "status", event);
    }

    private static Map<String, Object> toggle(DataAccessor db, String poolId, String actor) {
        Map<String, Object> pool = requirePoolAdministrator(db, poolId, actor);
        String next = "enabled".equals(string(pool.get("crm_cp_status"))) ? "disabled" : "enabled";
        db.update("crm_customer_pool_common", poolId, Map.of("crm_cp_status", next));
        return Map.of("poolId", poolId, "status", next);
    }

    private static Map<String, Object> updatePoolCustomer(DataAccessor db, String itemId, String actor,
                                                           Map<String, Object> payload) {
        Map<String, Object> item = requireRecord(db, "crm_customer_pool_item_common", itemId, "Pool item");
        requirePoolMember(db, required(item.get("crm_cpi_pool_id"), "Pool item has no pool"), actor);
        String customerId = required(item.get("crm_cpi_account_id"), "Pool item has no customer");
        requireRecord(db, "crm_account_common", customerId, "Customer");

        HashMap<String, Object> customerPatch = new HashMap<>();
        if (payload != null) {
            for (String field : POOL_CUSTOMER_EDITABLE_FIELDS) {
                if (payload.containsKey(field)) customerPatch.put(field, payload.get(field));
            }
            for (Map.Entry<String, String> mapping : SNAPSHOT_PAYLOAD_FIELDS.entrySet()) {
                if (payload.containsKey(mapping.getKey())) {
                    customerPatch.put(mapping.getValue(), payload.get(mapping.getKey()));
                }
            }
        }
        if (customerPatch.isEmpty()) {
            throw new IllegalArgumentException("At least one editable customer field is required");
        }
        if (customerPatch.containsKey("crm_acc_name")) {
            required(customerPatch.get("crm_acc_name"), "Customer name is required");
        }

        db.update("crm_account_common", customerId, customerPatch);
        HashMap<String, Object> snapshotPatch = new HashMap<>();
        for (Map.Entry<String, String> mapping : CUSTOMER_SNAPSHOT_FIELDS.entrySet()) {
            if (customerPatch.containsKey(mapping.getKey())) {
                snapshotPatch.put(mapping.getValue(), customerPatch.get(mapping.getKey()));
            }
        }
        if (!snapshotPatch.isEmpty()) db.update("crm_customer_pool_item_common", itemId, snapshotPatch);
        return Map.of(
                "customerId", customerId,
                "poolItemId", itemId,
                "updatedFields", List.copyOf(customerPatch.keySet()));
    }

    private static Map<String, Object> deletePoolCustomer(DataAccessor db, String itemId, String actor) {
        Map<String, Object> item = requireRecord(db, "crm_customer_pool_item_common", itemId, "Pool item");
        requirePoolMember(db, required(item.get("crm_cpi_pool_id"), "Pool item has no pool"), actor);
        String customerId = required(item.get("crm_cpi_account_id"), "Pool item has no customer");
        requireRecord(db, "crm_account_common", customerId, "Customer");

        if (!db.query("crm_contact_common", Map.of("crm_ct_account_id", customerId)).isEmpty()
                || !db.query("crm_opportunity_common", Map.of("crm_opp_account_id", customerId)).isEmpty()) {
            throw new IllegalStateException(
                    "Customer has related contacts or opportunities and cannot be permanently deleted");
        }

        // The account-key constraint guarantees one pool projection per customer. We already
        // resolved that projection above, so querying it again before deletion only adds a
        // redundant metadata/data-scope round trip to every single and batch delete.
        db.delete("crm_customer_pool_item_common", itemId);
        deleteMatching(db, "crm_customer_owner_history_common", "crm_coh_customer_id", customerId);
        deleteMatching(db, "crm_activity_relation_common", Map.of(
                "crm_ar_object_type", "account", "crm_ar_object_id", customerId));
        deleteMatching(db, "crm_activity_common", Map.of(
                "crm_act_related_model", "crm_account_common", "crm_act_related_id", customerId));
        db.delete("crm_account_common", customerId);
        return Map.of("customerId", customerId, "poolItemId", itemId, "deleted", true);
    }

    private static void deleteMatching(DataAccessor db, String model, String field, String value) {
        deleteMatching(db, model, Map.of(field, value));
    }

    private static void deleteMatching(DataAccessor db, String model, Map<String, Object> filters) {
        List<String> ids = db.query(model, filters).stream()
                .map(record -> string(record.get("pid")))
                .filter(java.util.Objects::nonNull)
                .toList();
        db.batchDelete(model, ids);
    }

    private static Map<String, Object> deletePool(DataAccessor db, String poolId, String actor) {
        requirePoolAdministrator(db, poolId, actor);
        List<Map<String, Object>> available = db.query("crm_customer_pool_item_common", Map.of(
                "crm_cpi_pool_id", poolId, "crm_cpi_status", "available"));
        if (available != null && !available.isEmpty()) {
            throw new IllegalStateException("Customer pool contains available customers and cannot be deleted");
        }
        db.delete("crm_customer_pool_common", poolId);
        return Map.of("poolId", poolId, "deleted", true);
    }

    public static int recycle(DataAccessor db, RecordShareAccessor shares, long tenantId,
                              String actor, Instant now) {
        return recycleDetailed(db, shares, tenantId, actor, now, DEFAULT_RECYCLE_LEASE_TIMEOUT).recycled();
    }

    public static RecycleResult recycleDetailed(DataAccessor db, RecordShareAccessor shares, long tenantId,
                                                 String actor, Instant now, Duration leaseTimeout) {
        if (leaseTimeout == null || leaseTimeout.isNegative() || leaseTimeout.isZero()) {
            throw new IllegalArgumentException("Recycle lease timeout must be positive");
        }
        List<Map<String, Object>> pools = db.query("crm_customer_pool_common", Map.of(
                "crm_cp_status", "enabled", "crm_cp_auto_recycle", true));
        int recycled = 0;
        int recovered = 0;
        int activeLeases = 0;
        int failed = 0;
        if (pools == null) return new RecycleResult(0, 0, 0, 0);
        for (Map<String, Object> pool : pools) {
            String poolId = string(pool.get("pid"));
            int days = intValue(pool.get("crm_cp_recycle_after_days"));
            List<Map<String, Object>> configuredRules = activeRecycleRules(db, poolId);
            List<Map<String, Object>> poolItems = Optional.ofNullable(db.query(
                    "crm_customer_pool_item_common", Map.of("crm_cpi_pool_id", poolId)))
                    .orElse(List.of());
            Map<String, Map<String, Object>> itemByCustomerId = new HashMap<>();
            for (Map<String, Object> item : poolItems) {
                String customerId = string(item.get("crm_cpi_account_id"));
                if (customerId != null) itemByCustomerId.put(customerId, item);
            }

            for (String leaseState : RECYCLE_LEASE_STATES) {
                for (Map<String, Object> item : poolItems) {
                    if (!leaseState.equals(string(item.get("crm_cpi_status")))) continue;
                    try {
                        LeaseAttempt attempt = acquireStaleLease(db, item, now, leaseTimeout);
                        if (attempt.activeLease()) {
                            activeLeases++;
                        } else if (attempt.item() != null) {
                            if (completeRecycle(db, shares, tenantId, pool, attempt.item(), actor, days, now)) {
                                recycled++;
                                recovered++;
                            } else {
                                activeLeases++;
                            }
                        }
                    } catch (RuntimeException error) {
                        // Per-item tolerance: one corrupt or unavailable customer must not block every
                        // other tenant-scoped recycle candidate in the same scheduler tick.
                        failed++;
                        log.warn("Failed to recover customer-pool recycle item {} in pool {}",
                                string(item.get("pid")), poolId, error);
                    }
                }
            }

            List<Map<String, Object>> customers = db.query("crm_account_common", Map.of(
                    "crm_acc_last_pool_id", poolId, "crm_acc_pool_state", "owned"));
            if (customers == null) continue;
            Map<String, Instant> latestActivityByCustomer = usesLastActivity(pool, configuredRules)
                    ? latestAccountActivities(db, customers)
                    : Map.of();
            for (Map<String, Object> customer : customers) {
                if (!OPEN_CUSTOMER_STATES.contains(string(customer.get("crm_acc_status")))) continue;
                try {
                    String customerId = required(customer.get("pid"), "Customer has no pid");
                    Map<String, Object> ruleCustomer = withLatestAccountActivity(
                            customer, latestActivityByCustomer.get(customerId));
                    Map<String, Object> poolItem = itemByCustomerId.get(customerId);
                    if (shouldRecycle(pool, ruleCustomer, poolItem, configuredRules, days, now)) {
                        LeaseAttempt attempt = acquireRecycleLease(db, pool, ruleCustomer, poolItem,
                                actor, days, now, leaseTimeout);
                        if (attempt.activeLease()) {
                            activeLeases++;
                        } else if (attempt.item() != null) {
                            if (completeRecycle(db, shares, tenantId, pool, attempt.item(), actor, days, now)) {
                                recycled++;
                                if (attempt.recovered()) recovered++;
                            } else {
                                activeLeases++;
                            }
                        }
                    }
                } catch (RuntimeException error) {
                    // Per-item tolerance: surface the failed candidate and continue independent work.
                    failed++;
                    log.warn("Failed to recycle customer {} in pool {}", string(customer.get("pid")), poolId, error);
                }
            }
        }
        return new RecycleResult(recycled, recovered, activeLeases, failed);
    }

    private static List<Map<String, Object>> activeRecycleRules(DataAccessor db, String poolId) {
        List<Map<String, Object>> rules = db.query("crm_customer_pool_recycle_rule_common", Map.of(
                "crm_cprr_pool_id", poolId,
                "crm_cprr_status", "active"));
        if (rules == null || rules.isEmpty()) return List.of();
        return rules.stream()
                .sorted(java.util.Comparator.comparingInt(rule -> intValue(rule.get("crm_cprr_sort_order"))))
                .toList();
    }

    private static boolean shouldRecycle(Map<String, Object> pool,
                                         Map<String, Object> customer,
                                         Map<String, Object> item,
                                         List<Map<String, Object>> configuredRules,
                                         int legacyDays, Instant now) {
        if (configuredRules == null || configuredRules.isEmpty()) {
            Object basisValue = "claimed_at".equals(string(pool.get("crm_cp_recycle_basis")))
                    ? customer.get("crm_acc_claimed_at")
                    : Optional.ofNullable(customer.get("crm_acc_last_activity_at"))
                            .orElse(customer.get("crm_acc_claimed_at"));
            return CustomerPoolRules.shouldRecycle(instant(basisValue), legacyDays, now);
        }
        Map<String, Object> ruleItem = item == null ? Map.of() : item;
        boolean matchAll = !"any".equals(string(pool.get("crm_cp_recycle_match_mode")));
        return matchAll
                ? configuredRules.stream().allMatch(rule -> matchesRecycleRule(rule, customer, ruleItem, now))
                : configuredRules.stream().anyMatch(rule -> matchesRecycleRule(rule, customer, ruleItem, now));
    }

    private static boolean usesLastActivity(Map<String, Object> pool,
                                            List<Map<String, Object>> configuredRules) {
        return configuredRules == null || configuredRules.isEmpty()
                ? !"claimed_at".equals(string(pool.get("crm_cp_recycle_basis")))
                : configuredRules.stream().anyMatch(rule ->
                        "last_activity_at".equals(string(rule.get("crm_cprr_time_source"))));
    }

    private static Map<String, Instant> latestAccountActivities(DataAccessor db,
                                                                 List<Map<String, Object>> customers) {
        List<String> customerIds = customers.stream()
                .map(customer -> string(customer.get("pid")))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (customerIds.isEmpty()) return Map.of();
        Map<String, Instant> latestByCustomer = new HashMap<>();
        for (Map<String, Object> customer : customers) {
            String customerId = string(customer.get("pid"));
            Instant storedLatest = instant(customer.get("crm_acc_last_activity_at"));
            if (customerId != null && storedLatest != null) latestByCustomer.put(customerId, storedLatest);
        }

        List<Map<String, Object>> relations = db.queryIn(
                "crm_activity_relation_common", "crm_ar_object_id", customerIds);
        Map<String, String> relationCustomerByActivity = new HashMap<>();
        if (relations != null) {
            for (Map<String, Object> relation : relations) {
                if (!"account".equals(string(relation.get("crm_ar_object_type")))) continue;
                String customerId = string(relation.get("crm_ar_object_id"));
                String activityId = string(relation.get("crm_ar_activity_id"));
                if (customerId != null && activityId != null) {
                    relationCustomerByActivity.put(activityId, customerId);
                }
            }
        }
        if (!relationCustomerByActivity.isEmpty()) {
            List<Map<String, Object>> relatedActivities = db.queryIn(
                    "crm_activity_common", "pid", relationCustomerByActivity.keySet());
            for (Map<String, Object> activity : relatedActivities) {
                String activityId = string(activity.get("pid"));
                String customerId = relationCustomerByActivity.get(activityId);
                Instant activityAt = instant(activity.get("crm_act_date"));
                if (customerId != null && activityAt != null) {
                    latestByCustomer.merge(customerId, activityAt, CustomerPoolCommandHandler::later);
                }
            }
        }

        // Some imported activities use the direct polymorphic anchor. Supporting both shapes
        // keeps the state machine aligned with the customer timeline without mutating source rows.
        List<Map<String, Object>> directActivities = db.queryIn(
                "crm_activity_common", "crm_act_related_id", customerIds);
        if (directActivities != null) {
            for (Map<String, Object> activity : directActivities) {
                if (!"crm_account_common".equals(string(activity.get("crm_act_related_model")))) continue;
                String customerId = string(activity.get("crm_act_related_id"));
                Instant activityAt = instant(activity.get("crm_act_date"));
                if (customerId != null && activityAt != null) {
                    latestByCustomer.merge(customerId, activityAt, CustomerPoolCommandHandler::later);
                }
            }
        }
        return latestByCustomer;
    }

    private static Map<String, Object> withLatestAccountActivity(Map<String, Object> customer,
                                                                  Instant latest) {
        if (latest == null) return customer;
        HashMap<String, Object> enriched = new HashMap<>(customer);
        enriched.put("crm_acc_last_activity_at", latest);
        return enriched;
    }

    private static Instant later(Instant current, Instant candidate) {
        if (current == null) return candidate;
        if (candidate == null) return current;
        return candidate.isAfter(current) ? candidate : current;
    }

    static boolean matchesRecycleRule(Map<String, Object> rule, Map<String, Object> customer,
                                      Map<String, Object> item, Instant now) {
        String source = required(rule.get("crm_cprr_time_source"), "Recycle rule time source is required");
        String operator = required(rule.get("crm_cprr_operator"), "Recycle rule operator is required");
        List<Instant> timestamps = switch (source) {
            case "pool_entered_or_claimed" -> nonNullInstants(
                    instant(item.get("crm_cpi_entered_at")),
                    instant(customer.get("crm_acc_claimed_at")));
            case "pool_entered_at" -> nonNullInstants(instant(item.get("crm_cpi_entered_at")));
            case "claimed_at" -> nonNullInstants(instant(customer.get("crm_acc_claimed_at")));
            case "last_activity_at" -> nonNullInstants(instant(customer.get("crm_acc_last_activity_at")));
            default -> throw new IllegalArgumentException("Unsupported recycle rule time source: " + source);
        };
        // Cordys treats an absent selected time as satisfying that recycle condition.
        if (timestamps.isEmpty()) return true;
        int days = intValue(rule.get("crm_cprr_days"));
        Instant start = instant(rule.get("crm_cprr_start_at"));
        Instant end = instant(rule.get("crm_cprr_end_at"));
        return switch (operator) {
            case "older_than_days" -> timestamps.stream()
                    .anyMatch(value -> !value.isAfter(now.minus(Duration.ofDays(days))));
            case "newer_than_days" -> timestamps.stream()
                    .anyMatch(value -> !value.isBefore(now.minus(Duration.ofDays(days))));
            case "fixed_between" -> {
                if (start == null || end == null || start.isAfter(end)) {
                    throw new IllegalArgumentException("Fixed interval requires an ordered start and end");
                }
                yield timestamps.stream().anyMatch(value -> !value.isBefore(start) && !value.isAfter(end));
            }
            case "fixed_before" -> {
                if (end == null) throw new IllegalArgumentException("Fixed-before rule requires an end time");
                yield timestamps.stream().anyMatch(value -> value.isBefore(end));
            }
            case "fixed_after" -> {
                if (start == null) throw new IllegalArgumentException("Fixed-after rule requires a start time");
                yield timestamps.stream().anyMatch(value -> value.isAfter(start));
            }
            default -> throw new IllegalArgumentException("Unsupported recycle rule operator: " + operator);
        };
    }

    private static List<Instant> nonNullInstants(Instant... values) {
        return java.util.Arrays.stream(values).filter(java.util.Objects::nonNull).toList();
    }

    private static LeaseAttempt acquireRecycleLease(DataAccessor db, Map<String, Object> pool,
                                                      Map<String, Object> customer,
                                                      Map<String, Object> existingItem,
                                                      String actor, int days,
                                                      Instant now, Duration leaseTimeout) {
        String customerId = required(customer.get("pid"), "Customer has no pid");
        String poolId = required(pool.get("pid"), "Customer pool has no pid");
        Map<String, Object> item = existingItem;
        if (item == null) {
            String token = newRecycleToken(null);
            Map<String, Object> itemData = poolItemSnapshot(customer, customerId, poolId,
                    string(customer.get("crm_acc_owner")), actor,
                    "Automatic recycle after " + days + " days", now,
                    intValue(pool.get("crm_cp_new_cooldown_days")));
            itemData.put("crm_cpi_status", "recycling");
            itemData.put("crm_cpi_recycle_token", token);
            Optional<Map<String, Object>> created = db.tryCreate("crm_customer_pool_item_common", itemData);
            if (created.isPresent()) return new LeaseAttempt(created.get(), false, false);
            List<Map<String, Object>> existing = db.query(
                    "crm_customer_pool_item_common", Map.of("crm_cpi_account_key", customerId));
            if (existing == null || existing.isEmpty()) {
                throw new IllegalStateException(
                        "Customer-pool item disappeared during recycle acquisition: " + customerId);
            }
            item = existing.getFirst();
        }
        String status = string(item.get("crm_cpi_status"));
        if (RECYCLE_SOURCE_STATES.contains(status)) {
            String itemId = required(item.get("pid"), "Pool item has no pid");
            if (!db.compareAndSet("crm_customer_pool_item_common", itemId, "crm_cpi_status", status, "recycling")) {
                return new LeaseAttempt(null, false, true);
            }
            item = requireRecord(db, "crm_customer_pool_item_common", itemId, "Pool item");
            return new LeaseAttempt(ensureRecycleToken(db, item), false, false);
        }
        if (RECYCLE_LEASE_STATES.contains(status)) {
            return acquireStaleLease(db, item, now, leaseTimeout);
        }
        return new LeaseAttempt(null, false, false);
    }

    private static LeaseAttempt acquireStaleLease(DataAccessor db, Map<String, Object> item,
                                                   Instant now, Duration leaseTimeout) {
        String status = string(item.get("crm_cpi_status"));
        if (!RECYCLE_LEASE_STATES.contains(status)) return new LeaseAttempt(null, false, false);
        Instant leaseUpdatedAt = instant(item.get("updated_at"));
        if (leaseUpdatedAt != null && leaseUpdatedAt.plus(leaseTimeout).isAfter(now)) {
            return new LeaseAttempt(null, false, true);
        }
        String itemId = required(item.get("pid"), "Pool item has no pid");
        String priorToken = string(item.get("crm_cpi_recycle_token"));
        String nextToken = newRecycleToken(priorToken);
        if (!db.compareAndSet("crm_customer_pool_item_common", itemId, "crm_cpi_recycle_token",
                priorToken, nextToken)) {
            return new LeaseAttempt(null, false, true);
        }
        Map<String, Object> acquired = requireRecord(db, "crm_customer_pool_item_common", itemId, "Pool item");
        return new LeaseAttempt(acquired, true, false);
    }

    private static Map<String, Object> ensureRecycleToken(DataAccessor db, Map<String, Object> item) {
        if (string(item.get("crm_cpi_recycle_token")) != null) return item;
        String itemId = required(item.get("pid"), "Pool item has no pid");
        return db.update("crm_customer_pool_item_common", itemId,
                Map.of("crm_cpi_recycle_token", newRecycleToken(null)));
    }

    static boolean completeRecycle(DataAccessor db, RecordShareAccessor shares, long tenantId,
                                   Map<String, Object> pool, Map<String, Object> leasedItem,
                                   String actor, int days, Instant now) {
        String itemId = required(leasedItem.get("pid"), "Pool item has no pid");
        String customerId = required(leasedItem.get("crm_cpi_account_id"), "Pool item has no customer");
        String poolId = required(leasedItem.get("crm_cpi_pool_id"), "Pool item has no pool");
        String leaseToken = required(leasedItem.get("crm_cpi_recycle_token"),
                "Recycle lease has no operation token");
        String operationKey = recycleOperationKey(leaseToken);
        String commitToken = operationKey + ":commit";
        if (!db.compareAndSet("crm_customer_pool_item_common", itemId, "crm_cpi_recycle_token",
                leaseToken, commitToken)) {
            return false;
        }
        Map<String, Object> customer = requireRecord(db, "crm_account_common", customerId, "Customer");
        String previousOwner = Optional.ofNullable(string(customer.get("crm_acc_owner")))
                .orElse(string(leasedItem.get("crm_cpi_previous_owner")));
        String reason = "Automatic recycle after " + days + " days";

        Map<String, Object> snapshot = poolItemSnapshot(customer, customerId, poolId, previousOwner, actor,
                reason, now, intValue(pool.get("crm_cp_new_cooldown_days")));
        snapshot.remove("crm_cpi_status");
        snapshot.put("crm_cpi_recycle_token", commitToken);
        db.update("crm_customer_pool_item_common", itemId, snapshot);

        HashMap<String, Object> customerPatch = new HashMap<>();
        customerPatch.put("crm_acc_owner", null);
        customerPatch.put("crm_acc_pool_state", "in_pool");
        customerPatch.put("crm_acc_last_pool_id", poolId);
        db.update("crm_account_common", customerId, customerPatch);

        Map<String, Object> history = appendHistoryIdempotent(db, operationKey, customerId, poolId,
                previousOwner, "system".equals(actor) ? null : actor, reason, now);
        syncPoolRecordShares(shares, tenantId, pool, "crm_account_common", customerId);
        syncPoolRecordShares(shares, tenantId, pool, "crm_customer_pool_item_common", itemId);
        syncPoolRecordShares(shares, tenantId, pool, "crm_customer_owner_history_common", string(history.get("pid")));
        HashMap<String, Object> completedLease = new HashMap<>();
        completedLease.put("crm_cpi_status", "available");
        completedLease.put("crm_cpi_recycle_token", null);
        return db.compareAndSet("crm_customer_pool_item_common", itemId, "crm_cpi_recycle_token",
                commitToken, completedLease);
    }

    private static String newRecycleToken(String priorToken) {
        return recycleOperationKey(priorToken) + ":" + UUID.randomUUID();
    }

    private static String recycleOperationKey(String token) {
        if (token == null || token.isBlank()) return UUID.randomUUID().toString();
        int separator = token.indexOf(':');
        return separator < 0 ? token : token.substring(0, separator);
    }

    private static Map<String, Object> appendHistoryIdempotent(DataAccessor db, String operationKey,
                                                                String customerId, String poolId,
                                                                String previousOwner, String actor,
                                                                String reason, Instant now) {
        HashMap<String, Object> row = historyRow(customerId, poolId, "auto_recycled", previousOwner,
                null, actor, reason, now);
        row.put("crm_coh_operation_key", operationKey);
        Optional<Map<String, Object>> created = db.tryCreate("crm_customer_owner_history_common", row);
        if (created.isPresent()) return created.get();
        List<Map<String, Object>> existing = db.query("crm_customer_owner_history_common",
                Map.of("crm_coh_operation_key", operationKey));
        if (existing == null || existing.isEmpty()) {
            throw new IllegalStateException("Recycle history duplicate was not readable: " + operationKey);
        }
        return existing.getFirst();
    }

    public record RecycleResult(int recycled, int recovered, int activeLeases, int failed) {
        Map<String, Object> asMap() {
            return Map.of("recycled", recycled, "recovered", recovered,
                    "activeLeases", activeLeases, "failed", failed);
        }
    }

    private record LeaseAttempt(Map<String, Object> item, boolean recovered, boolean activeLease) {}

    private static void incrementDailyQuota(DataAccessor db, String poolId, String actor, int limit) {
        if (limit <= 0) throw new IllegalStateException("Daily pick limit must be positive");
        String day = LocalDate.now(ZoneOffset.UTC).toString();
        String key = poolId + ":" + actor + ":" + day;
        List<Map<String, Object>> rows = db.query("crm_customer_pool_quota_common", Map.of("crm_cpq_key", key));
        Map<String, Object> quota;
        if (rows == null || rows.isEmpty()) {
            quota = db.create("crm_customer_pool_quota_common", Map.of(
                    "crm_cpq_key", key,
                    "crm_cpq_pool_id", poolId,
                    "crm_cpq_user_id", actor,
                    "crm_cpq_local_date", day,
                    "crm_cpq_pick_count", 0,
                    "crm_cpq_limit_snapshot", limit));
        } else {
            quota = rows.getFirst();
            if (intValue(quota.get("crm_cpq_limit_snapshot")) != limit) {
                quota = db.update("crm_customer_pool_quota_common", string(quota.get("pid")),
                        Map.of("crm_cpq_limit_snapshot", limit));
            }
        }
        if (db.incrementWithinCap("crm_customer_pool_quota_common", string(quota.get("pid")),
                "crm_cpq_pick_count", 1, "crm_cpq_limit_snapshot").isEmpty()) {
            throw new IllegalStateException("Daily customer-pool claim limit reached");
        }
    }

    private static void validateCapacity(DataAccessor db, String owner) {
        List<Map<String, Object>> configs = db.query("crm_customer_capacity_common", Map.of(
                "crm_ccap_user_id", owner, "crm_ccap_status", "active"));
        if (configs == null || configs.isEmpty()) return;
        int capacity = intValue(configs.getFirst().get("crm_ccap_capacity"));
        List<Map<String, Object>> owned = db.query("crm_account_common", Map.of("crm_acc_owner", owner));
        long open = owned == null ? 0 : owned.stream()
                .filter(customer -> OPEN_CUSTOMER_STATES.contains(string(customer.get("crm_acc_status"))))
                .filter(customer -> !"in_pool".equals(string(customer.get("crm_acc_pool_state"))))
                .count();
        if (open >= capacity) throw new IllegalStateException("Customer capacity reached for user " + owner);
    }

    private static Map<String, Object> requirePoolMember(DataAccessor db, String poolId, String actor) {
        Map<String, Object> pool = requireRecord(db, "crm_customer_pool_common", poolId, "Customer pool");
        if (!"enabled".equals(string(pool.get("crm_cp_status")))) throw new IllegalStateException("Customer pool is disabled");
        if (!CustomerPoolRules.isMember(pool.get("crm_cp_member_user_ids"), pool.get("crm_cp_admin_user_ids"), actor)) {
            throw new SecurityException("Current user is not a member of customer pool " + poolId);
        }
        return pool;
    }

    private static Map<String, Object> requirePoolAdministrator(DataAccessor db, String poolId, String actor) {
        Map<String, Object> pool = requireRecord(db, "crm_customer_pool_common", poolId, "Customer pool");
        if (!CustomerPoolRules.isAdministrator(pool.get("crm_cp_admin_user_ids"), actor)) {
            throw new SecurityException("Current user is not an administrator of customer pool " + poolId);
        }
        return pool;
    }

    private static Map<String, Object> poolItemSnapshot(Map<String, Object> customer, String customerId, String poolId,
                                                         String previousOwner, String actor, String reason,
                                                         Instant now, int cooldownDays) {
        HashMap<String, Object> data = new HashMap<>();
        data.put("crm_cpi_account_key", customerId);
        data.put("crm_cpi_account_id", customerId);
        data.put("crm_cpi_pool_id", poolId);
        data.put("crm_cpi_status", "available");
        copy(data, "crm_cpi_account_code", customer, "crm_acc_code");
        copy(data, "crm_cpi_account_name", customer, "crm_acc_name");
        copy(data, "crm_cpi_rating", customer, "crm_acc_rating");
        copy(data, "crm_cpi_phone", customer, "crm_acc_phone");
        copy(data, "crm_cpi_industry", customer, "crm_acc_industry");
        copy(data, "crm_cpi_health_score", customer, "crm_acc_health_score");
        data.put("crm_cpi_previous_owner", previousOwner);
        data.put("crm_cpi_entered_at", now.toString());
        data.put("crm_cpi_entered_by", actor);
        data.put("crm_cpi_reason", reason);
        data.put("crm_cpi_claim_release_at", CustomerPoolRules.releaseAt(now, cooldownDays).toString());
        data.put("crm_cpi_claimed_at", null);
        data.put("crm_cpi_claimed_by", null);
        data.put("crm_cpi_recycle_token", null);
        return data;
    }

    private static Map<String, Object> appendHistory(DataAccessor db, String customerId, String poolId, String event,
                                      String previousOwner, String nextOwner, String actor, String reason, Instant now) {
        return db.create("crm_customer_owner_history_common", historyRow(customerId, poolId, event,
                previousOwner, nextOwner, actor, reason, now));
    }

    private static HashMap<String, Object> historyRow(String customerId, String poolId, String event,
                                                       String previousOwner, String nextOwner, String actor,
                                                       String reason, Instant now) {
        HashMap<String, Object> row = new HashMap<>();
        row.put("crm_coh_customer_id", customerId);
        row.put("crm_coh_pool_id", poolId);
        row.put("crm_coh_event", event);
        row.put("crm_coh_previous_owner", previousOwner);
        row.put("crm_coh_next_owner", nextOwner);
        row.put("crm_coh_actor", actor);
        row.put("crm_coh_reason", reason);
        row.put("crm_coh_occurred_at", now.toString());
        return row;
    }

    private static void syncPoolRecordShares(RecordShareAccessor shares, long tenantId,
                                             Map<String, Object> pool, String resourceCode,
                                             String recordPid) {
        Set<String> users = new java.util.LinkedHashSet<>(
                CustomerPoolRules.userIds(pool.get("crm_cp_member_user_ids")));
        users.addAll(CustomerPoolRules.userIds(pool.get("crm_cp_admin_user_ids")));
        if ("crm_customer_pool_item_common".equals(resourceCode) || "crm_account_common".equals(resourceCode)) {
            shares.replaceReadUpdateSharesForUsers(tenantId, resourceCode, recordPid, users);
        } else {
            shares.replaceReadSharesForUsers(tenantId, resourceCode, recordPid, users);
        }
    }

    private static void copy(Map<String, Object> target, String targetKey, Map<String, Object> source, String sourceKey) {
        target.put(targetKey, source.get(sourceKey));
    }

    private static DataAccessor requireDb(CommandContext context) {
        if (context.dataAccessor() == null) throw new IllegalStateException("DataAccessor unavailable");
        return context.dataAccessor();
    }

    private static RecordShareAccessor requireShares(CommandContext context) {
        if (context.recordShareAccessor() == null) {
            throw new IllegalStateException("RecordShareAccessor unavailable");
        }
        return context.recordShareAccessor();
    }

    private static FileAccessor requireFiles(CommandContext context) {
        if (context.fileAccessor() == null) throw new IllegalStateException("FileAccessor unavailable");
        return context.fileAccessor();
    }

    private static String requireActor(CommandContext context) {
        return required(context.currentUserPid(),
                "Authenticated actor context is required");
    }

    private static String requireUploadOwner(CommandContext context) {
        Object value = context.settings() == null ? null : context.settings().get("__currentUser");
        return required(value, "Authenticated upload owner context is required");
    }

    private static Object payload(CommandContext context, String key) {
        return context.payload() == null ? null : context.payload().get(key);
    }

    private static Object first(Object primary, Object fallback) {
        return primary == null || primary.toString().isBlank() ? fallback : primary;
    }

    private static Map<String, Object> requireRecord(DataAccessor db, String model, String id, String label) {
        Map<String, Object> record = db.getById(model, id);
        if (record == null) throw new IllegalArgumentException(label + " not found: " + id);
        return record;
    }

    private static String required(Object value, String message) {
        String result = string(value);
        if (result == null || result.isBlank()) throw new IllegalArgumentException(message);
        return result;
    }

    private static String string(Object value) {
        return value == null ? null : value.toString().trim();
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value == null || value.toString().isBlank()) return 0;
        return (int) Math.round(Double.parseDouble(value.toString()));
    }

    private static Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof Instant instant) return instant;
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof OffsetDateTime offsetDateTime) return offsetDateTime.toInstant();
        if (value instanceof LocalDateTime localDateTime) return localDateTime.toInstant(ZoneOffset.UTC);
        if (value instanceof Number number) return Instant.ofEpochMilli(number.longValue());
        String raw = value.toString().trim();
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException ignored) {
            String isoLocal = raw.replace(' ', 'T');
            try {
                return OffsetDateTime.parse(isoLocal).toInstant();
            } catch (DateTimeParseException noOffset) {
                return LocalDateTime.parse(isoLocal).toInstant(ZoneOffset.UTC);
            }
        }
    }
}
