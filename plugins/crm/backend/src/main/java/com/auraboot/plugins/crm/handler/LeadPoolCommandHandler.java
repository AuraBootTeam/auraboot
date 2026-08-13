package com.auraboot.plugins.crm.handler;

import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.DataAccessor;
import com.auraboot.framework.plugin.extension.RecordShareAccessor;
import com.auraboot.plugins.crm.engine.LeadPoolRules;
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

/** Cordys-parity lead-pool state machine. All mutations are tenant-scoped and transactional. */
@Extension
public class LeadPoolCommandHandler implements CommandHandlerExtension {

    private static final Logger log = LoggerFactory.getLogger(LeadPoolCommandHandler.class);

    public static final String MOVE = "crm:move_lead_to_pool";
    public static final String CLAIM = "crm:claim_pool_lead";
    public static final String ASSIGN = "crm:assign_pool_lead";
    public static final String TOGGLE = "crm:toggle_lead_pool";
    public static final String DELETE_POOL = "crm:delete_lead_pool";
    public static final String RUN_RECYCLE = "crm:run_lead_pool_recycle";
    private static final Set<String> TYPES = Set.of(MOVE, CLAIM, ASSIGN, TOGGLE, DELETE_POOL, RUN_RECYCLE);
    private static final Set<String> OPEN_LEAD_STATES = Set.of("new", "contacted", "qualified");
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
        DataAccessor db = requireDb(context);
        RecordShareAccessor shares = requireShares(context);
        String actor = requireActor(context);
        return switch (context.commandType()) {
            case MOVE -> moveToPool(db, required(context.recordId(), "Lead id is required"),
                    required(first(payload(context, "poolId"), payload(context, "crm_lead_last_pool_id")), "poolId is required"), actor,
                    string(payload(context, "reason")), "moved_to_pool", Instant.now(), shares, context.tenantId());
            case CLAIM -> claim(db, required(context.recordId(), "Pool item id is required"), actor,
                    Instant.now(), shares, context.tenantId());
            case ASSIGN -> assign(db, required(context.recordId(), "Pool item id is required"), actor,
                    required(first(payload(context, "assigneeId"), payload(context, "crm_lpi_claimed_by")), "assigneeId is required"),
                    Instant.now(), shares, context.tenantId());
            case TOGGLE -> toggle(db, required(context.recordId(), "Pool id is required"), actor);
            case DELETE_POOL -> deletePool(db, required(context.recordId(), "Pool id is required"), actor);
            case RUN_RECYCLE -> recycleDetailed(db, shares, context.tenantId(), actor, Instant.now(),
                    DEFAULT_RECYCLE_LEASE_TIMEOUT).asMap();
            default -> throw new IllegalArgumentException("Unsupported lead-pool command: " + context.commandType());
        };
    }

    static Map<String, Object> moveToPool(DataAccessor db, String leadId, String poolId, String actor,
                                           String reason, String event, Instant now,
                                           RecordShareAccessor shares, long tenantId) {
        Map<String, Object> pool = requireRecord(db, "crm_lead_pool", poolId, "Lead pool");
        if (!"enabled".equals(string(pool.get("crm_lp_status")))) {
            throw new IllegalStateException("Lead pool is disabled: " + poolId);
        }
        boolean systemRecycle = "auto_recycled".equals(event) && "system".equals(actor);
        if (!systemRecycle && !LeadPoolRules.isAdministrator(pool.get("crm_lp_admin_user_ids"), actor)
                && !LeadPoolRules.isMember(pool.get("crm_lp_member_user_ids"), pool.get("crm_lp_admin_user_ids"), actor)) {
            throw new SecurityException("Current user is not a member of lead pool " + poolId);
        }
        Map<String, Object> lead = requireRecord(db, "crm_lead_common", leadId, "Lead");
        if ("in_pool".equals(string(lead.get("crm_lead_pool_state")))) {
            throw new IllegalStateException("Lead is already in a lead pool: " + leadId);
        }
        String previousOwner = string(lead.get("crm_lead_assigned_to"));
        Map<String, Object> itemPatch = poolItemSnapshot(lead, leadId, poolId, previousOwner, actor, reason, now,
                intValue(pool.get("crm_lp_new_cooldown_days")));
        List<Map<String, Object>> existing = db.query("crm_lead_pool_item", Map.of("crm_lpi_lead_key", leadId));
        Map<String, Object> item = existing == null || existing.isEmpty()
                ? db.create("crm_lead_pool_item", itemPatch)
                : db.update("crm_lead_pool_item", string(existing.getFirst().get("pid")), itemPatch);

        HashMap<String, Object> leadPatch = new HashMap<>();
        leadPatch.put("crm_lead_assigned_to", null);
        leadPatch.put("crm_lead_pool_state", "in_pool");
        leadPatch.put("crm_lead_last_pool_id", poolId);
        db.update("crm_lead_common", leadId, leadPatch);
        Map<String, Object> history = appendHistory(db, leadId, poolId, event, previousOwner, null,
                systemRecycle ? null : actor, reason, now);
        syncPoolRecordShares(shares, tenantId, pool, "crm_lead_common", leadId);
        syncPoolRecordShares(shares, tenantId, pool, "crm_lead_pool_item", string(item.get("pid")));
        syncPoolRecordShares(shares, tenantId, pool, "crm_lead_owner_history", string(history.get("pid")));
        return Map.of("leadId", leadId, "poolId", poolId, "poolItemId", string(item.get("pid")), "status", "available");
    }

    private static Map<String, Object> claim(DataAccessor db, String itemId, String actor, Instant now,
                                              RecordShareAccessor shares, long tenantId) {
        Map<String, Object> item = requireRecord(db, "crm_lead_pool_item", itemId, "Pool item");
        String poolId = required(item.get("crm_lpi_pool_id"), "Pool item has no pool");
        Map<String, Object> pool = requirePoolMember(db, poolId, actor);
        boolean administrator = LeadPoolRules.isAdministrator(pool.get("crm_lp_admin_user_ids"), actor);
        validateCapacity(db, actor);
        Instant enteredAt = instant(item.get("crm_lpi_entered_at"));
        if (!administrator && !LeadPoolRules.cooldownElapsed(enteredAt,
                intValue(pool.get("crm_lp_new_cooldown_days")), now)) {
            throw new IllegalStateException("New lead cooldown has not elapsed; claim available at "
                    + LeadPoolRules.releaseAt(enteredAt, intValue(pool.get("crm_lp_new_cooldown_days"))));
        }
        String previousOwner = string(item.get("crm_lpi_previous_owner"));
        if (!administrator && actor.equals(previousOwner) && !LeadPoolRules.cooldownElapsed(enteredAt,
                intValue(pool.get("crm_lp_previous_owner_cooldown_days")), now)) {
            throw new IllegalStateException("Previous owner cooldown has not elapsed");
        }
        if (!administrator) incrementDailyQuota(db, poolId, actor, intValue(pool.get("crm_lp_daily_pick_limit")));
        if (!db.compareAndSet("crm_lead_pool_item", itemId, "crm_lpi_status", "available", "claiming")) {
            throw new IllegalStateException("Lead was already claimed or assigned");
        }
        return finishOwnership(db, item, itemId, actor, actor, "claimed", now, shares, tenantId, pool);
    }

    private static Map<String, Object> assign(DataAccessor db, String itemId, String actor, String assignee, Instant now,
                                               RecordShareAccessor shares, long tenantId) {
        Map<String, Object> item = requireRecord(db, "crm_lead_pool_item", itemId, "Pool item");
        String poolId = required(item.get("crm_lpi_pool_id"), "Pool item has no pool");
        Map<String, Object> pool = requirePoolMember(db, poolId, actor);
        if (!LeadPoolRules.isAdministrator(pool.get("crm_lp_admin_user_ids"), actor)) {
            throw new SecurityException("Only a lead-pool administrator may assign leads");
        }
        validateCapacity(db, assignee);
        if (!db.compareAndSet("crm_lead_pool_item", itemId, "crm_lpi_status", "available", "claiming")) {
            throw new IllegalStateException("Lead was already claimed or assigned");
        }
        return finishOwnership(db, item, itemId, actor, assignee, "assigned", now, shares, tenantId, pool);
    }

    private static Map<String, Object> finishOwnership(DataAccessor db, Map<String, Object> item, String itemId,
                                                        String actor, String owner, String event, Instant now,
                                                        RecordShareAccessor shares, long tenantId,
                                                        Map<String, Object> pool) {
        String leadId = required(item.get("crm_lpi_lead_id"), "Pool item has no lead");
        String poolId = required(item.get("crm_lpi_pool_id"), "Pool item has no pool");
        db.update("crm_lead_common", leadId, Map.of(
                "crm_lead_assigned_to", owner,
                "crm_lead_pool_state", "owned",
                "crm_lead_last_pool_id", poolId,
                "crm_lead_claimed_at", now.toString()));
        db.update("crm_lead_pool_item", itemId, Map.of(
                "crm_lpi_status", event,
                "crm_lpi_claimed_at", now.toString(),
                "crm_lpi_claimed_by", owner));
        HashMap<String, Object> clearedLease = new HashMap<>();
        clearedLease.put("crm_lpi_recycle_token", null);
        db.update("crm_lead_pool_item", itemId, clearedLease);
        Map<String, Object> history = appendHistory(db, leadId, poolId, event,
                string(item.get("crm_lpi_previous_owner")), owner, actor, null, now);
        shares.replaceReadUpdateSharesForUsers(
                tenantId, "crm_lead_pool_item", itemId, Set.of(owner));
        shares.replaceReadUpdateSharesForUsers(
                tenantId, "crm_lead_common", leadId, Set.of(owner));
        syncPoolRecordShares(shares, tenantId, pool, "crm_lead_owner_history", string(history.get("pid")));
        return Map.of("leadId", leadId, "poolId", poolId, "ownerId", owner, "status", event);
    }

    private static Map<String, Object> toggle(DataAccessor db, String poolId, String actor) {
        Map<String, Object> pool = requirePoolAdministrator(db, poolId, actor);
        String next = "enabled".equals(string(pool.get("crm_lp_status"))) ? "disabled" : "enabled";
        db.update("crm_lead_pool", poolId, Map.of("crm_lp_status", next));
        return Map.of("poolId", poolId, "status", next);
    }

    private static Map<String, Object> deletePool(DataAccessor db, String poolId, String actor) {
        requirePoolAdministrator(db, poolId, actor);
        List<Map<String, Object>> available = db.query("crm_lead_pool_item", Map.of(
                "crm_lpi_pool_id", poolId, "crm_lpi_status", "available"));
        if (available != null && !available.isEmpty()) {
            throw new IllegalStateException("Lead pool contains available leads and cannot be deleted");
        }
        db.delete("crm_lead_pool", poolId);
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
        List<Map<String, Object>> pools = db.query("crm_lead_pool", Map.of(
                "crm_lp_status", "enabled", "crm_lp_auto_recycle", true));
        int recycled = 0;
        int recovered = 0;
        int activeLeases = 0;
        int failed = 0;
        if (pools == null) return new RecycleResult(0, 0, 0, 0);
        for (Map<String, Object> pool : pools) {
            String poolId = string(pool.get("pid"));
            int days = intValue(pool.get("crm_lp_recycle_after_days"));

            for (String leaseState : RECYCLE_LEASE_STATES) {
                List<Map<String, Object>> leasedItems = db.query("crm_lead_pool_item", Map.of(
                        "crm_lpi_pool_id", poolId, "crm_lpi_status", leaseState));
                if (leasedItems == null) continue;
                for (Map<String, Object> item : leasedItems) {
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
                        // Per-item tolerance: one corrupt or unavailable lead must not block every
                        // other tenant-scoped recycle candidate in the same scheduler tick.
                        failed++;
                        log.warn("Failed to recover lead-pool recycle item {} in pool {}",
                                string(item.get("pid")), poolId, error);
                    }
                }
            }

            List<Map<String, Object>> leads = db.query("crm_lead_common", Map.of(
                    "crm_lead_last_pool_id", poolId, "crm_lead_pool_state", "owned"));
            if (leads == null) continue;
            for (Map<String, Object> lead : leads) {
                if (!OPEN_LEAD_STATES.contains(string(lead.get("crm_lead_status")))) continue;
                Object basisValue = "claimed_at".equals(string(pool.get("crm_lp_recycle_basis")))
                        ? lead.get("crm_lead_claimed_at")
                        : Optional.ofNullable(lead.get("crm_lead_last_activity_at"))
                                .orElse(lead.get("crm_lead_claimed_at"));
                if (LeadPoolRules.shouldRecycle(instant(basisValue), days, now)) {
                    try {
                        LeaseAttempt attempt = acquireRecycleLease(db, pool, lead, actor, days, now, leaseTimeout);
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
                    } catch (RuntimeException error) {
                        // Per-item tolerance: surface the failed candidate and continue independent work.
                        failed++;
                        log.warn("Failed to recycle lead {} in pool {}", string(lead.get("pid")), poolId, error);
                    }
                }
            }
        }
        return new RecycleResult(recycled, recovered, activeLeases, failed);
    }

    private static LeaseAttempt acquireRecycleLease(DataAccessor db, Map<String, Object> pool,
                                                      Map<String, Object> lead, String actor, int days,
                                                      Instant now, Duration leaseTimeout) {
        String leadId = required(lead.get("pid"), "Lead has no pid");
        String poolId = required(pool.get("pid"), "Lead pool has no pid");
        List<Map<String, Object>> existing = db.query("crm_lead_pool_item", Map.of("crm_lpi_lead_key", leadId));
        if (existing == null || existing.isEmpty()) {
            String token = newRecycleToken(null);
            Map<String, Object> itemData = poolItemSnapshot(lead, leadId, poolId,
                    string(lead.get("crm_lead_assigned_to")), actor,
                    "Automatic recycle after " + days + " days", now,
                    intValue(pool.get("crm_lp_new_cooldown_days")));
            itemData.put("crm_lpi_status", "recycling");
            itemData.put("crm_lpi_recycle_token", token);
            Optional<Map<String, Object>> created = db.tryCreate("crm_lead_pool_item", itemData);
            if (created.isPresent()) return new LeaseAttempt(created.get(), false, false);
            existing = db.query("crm_lead_pool_item", Map.of("crm_lpi_lead_key", leadId));
        }
        if (existing == null || existing.isEmpty()) {
            throw new IllegalStateException("Lead-pool item disappeared during recycle acquisition: " + leadId);
        }
        Map<String, Object> item = existing.getFirst();
        String status = string(item.get("crm_lpi_status"));
        if (RECYCLE_SOURCE_STATES.contains(status)) {
            String itemId = required(item.get("pid"), "Pool item has no pid");
            if (!db.compareAndSet("crm_lead_pool_item", itemId, "crm_lpi_status", status, "recycling")) {
                return new LeaseAttempt(null, false, true);
            }
            item = requireRecord(db, "crm_lead_pool_item", itemId, "Pool item");
            return new LeaseAttempt(ensureRecycleToken(db, item), false, false);
        }
        if (RECYCLE_LEASE_STATES.contains(status)) {
            return acquireStaleLease(db, item, now, leaseTimeout);
        }
        return new LeaseAttempt(null, false, false);
    }

    private static LeaseAttempt acquireStaleLease(DataAccessor db, Map<String, Object> item,
                                                   Instant now, Duration leaseTimeout) {
        String status = string(item.get("crm_lpi_status"));
        if (!RECYCLE_LEASE_STATES.contains(status)) return new LeaseAttempt(null, false, false);
        Instant leaseUpdatedAt = instant(item.get("updated_at"));
        if (leaseUpdatedAt != null && leaseUpdatedAt.plus(leaseTimeout).isAfter(now)) {
            return new LeaseAttempt(null, false, true);
        }
        String itemId = required(item.get("pid"), "Pool item has no pid");
        String priorToken = string(item.get("crm_lpi_recycle_token"));
        String nextToken = newRecycleToken(priorToken);
        if (!db.compareAndSet("crm_lead_pool_item", itemId, "crm_lpi_recycle_token",
                priorToken, nextToken)) {
            return new LeaseAttempt(null, false, true);
        }
        Map<String, Object> acquired = requireRecord(db, "crm_lead_pool_item", itemId, "Pool item");
        return new LeaseAttempt(acquired, true, false);
    }

    private static Map<String, Object> ensureRecycleToken(DataAccessor db, Map<String, Object> item) {
        if (string(item.get("crm_lpi_recycle_token")) != null) return item;
        String itemId = required(item.get("pid"), "Pool item has no pid");
        return db.update("crm_lead_pool_item", itemId,
                Map.of("crm_lpi_recycle_token", newRecycleToken(null)));
    }

    static boolean completeRecycle(DataAccessor db, RecordShareAccessor shares, long tenantId,
                                   Map<String, Object> pool, Map<String, Object> leasedItem,
                                   String actor, int days, Instant now) {
        String itemId = required(leasedItem.get("pid"), "Pool item has no pid");
        String leadId = required(leasedItem.get("crm_lpi_lead_id"), "Pool item has no lead");
        String poolId = required(leasedItem.get("crm_lpi_pool_id"), "Pool item has no pool");
        String leaseToken = required(leasedItem.get("crm_lpi_recycle_token"),
                "Recycle lease has no operation token");
        String operationKey = recycleOperationKey(leaseToken);
        String commitToken = operationKey + ":commit";
        if (!db.compareAndSet("crm_lead_pool_item", itemId, "crm_lpi_recycle_token",
                leaseToken, commitToken)) {
            return false;
        }
        Map<String, Object> lead = requireRecord(db, "crm_lead_common", leadId, "Lead");
        String previousOwner = Optional.ofNullable(string(lead.get("crm_lead_assigned_to")))
                .orElse(string(leasedItem.get("crm_lpi_previous_owner")));
        String reason = "Automatic recycle after " + days + " days";

        Map<String, Object> snapshot = poolItemSnapshot(lead, leadId, poolId, previousOwner, actor,
                reason, now, intValue(pool.get("crm_lp_new_cooldown_days")));
        snapshot.remove("crm_lpi_status");
        snapshot.put("crm_lpi_recycle_token", commitToken);
        db.update("crm_lead_pool_item", itemId, snapshot);

        HashMap<String, Object> leadPatch = new HashMap<>();
        leadPatch.put("crm_lead_assigned_to", null);
        leadPatch.put("crm_lead_pool_state", "in_pool");
        leadPatch.put("crm_lead_last_pool_id", poolId);
        db.update("crm_lead_common", leadId, leadPatch);

        Map<String, Object> history = appendHistoryIdempotent(db, operationKey, leadId, poolId,
                previousOwner, "system".equals(actor) ? null : actor, reason, now);
        syncPoolRecordShares(shares, tenantId, pool, "crm_lead_common", leadId);
        syncPoolRecordShares(shares, tenantId, pool, "crm_lead_pool_item", itemId);
        syncPoolRecordShares(shares, tenantId, pool, "crm_lead_owner_history", string(history.get("pid")));
        HashMap<String, Object> completedLease = new HashMap<>();
        completedLease.put("crm_lpi_status", "available");
        completedLease.put("crm_lpi_recycle_token", null);
        return db.compareAndSet("crm_lead_pool_item", itemId, "crm_lpi_recycle_token",
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
                                                                String leadId, String poolId,
                                                                String previousOwner, String actor,
                                                                String reason, Instant now) {
        HashMap<String, Object> row = historyRow(leadId, poolId, "auto_recycled", previousOwner,
                null, actor, reason, now);
        row.put("crm_loh_operation_key", operationKey);
        Optional<Map<String, Object>> created = db.tryCreate("crm_lead_owner_history", row);
        if (created.isPresent()) return created.get();
        List<Map<String, Object>> existing = db.query("crm_lead_owner_history",
                Map.of("crm_loh_operation_key", operationKey));
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
        List<Map<String, Object>> rows = db.query("crm_lead_pool_quota", Map.of("crm_lpq_key", key));
        Map<String, Object> quota;
        if (rows == null || rows.isEmpty()) {
            quota = db.create("crm_lead_pool_quota", Map.of(
                    "crm_lpq_key", key,
                    "crm_lpq_pool_id", poolId,
                    "crm_lpq_user_id", actor,
                    "crm_lpq_local_date", day,
                    "crm_lpq_pick_count", 0,
                    "crm_lpq_limit_snapshot", limit));
        } else {
            quota = rows.getFirst();
            if (intValue(quota.get("crm_lpq_limit_snapshot")) != limit) {
                quota = db.update("crm_lead_pool_quota", string(quota.get("pid")),
                        Map.of("crm_lpq_limit_snapshot", limit));
            }
        }
        if (db.incrementWithinCap("crm_lead_pool_quota", string(quota.get("pid")),
                "crm_lpq_pick_count", 1, "crm_lpq_limit_snapshot").isEmpty()) {
            throw new IllegalStateException("Daily lead-pool claim limit reached");
        }
    }

    private static void validateCapacity(DataAccessor db, String owner) {
        List<Map<String, Object>> configs = db.query("crm_lead_capacity", Map.of(
                "crm_lcap_user_id", owner, "crm_lcap_status", "active"));
        if (configs == null || configs.isEmpty()) return;
        int capacity = intValue(configs.getFirst().get("crm_lcap_capacity"));
        List<Map<String, Object>> owned = db.query("crm_lead_common", Map.of("crm_lead_assigned_to", owner));
        long open = owned == null ? 0 : owned.stream()
                .filter(lead -> OPEN_LEAD_STATES.contains(string(lead.get("crm_lead_status"))))
                .filter(lead -> !"in_pool".equals(string(lead.get("crm_lead_pool_state"))))
                .count();
        if (open >= capacity) throw new IllegalStateException("Lead capacity reached for user " + owner);
    }

    private static Map<String, Object> requirePoolMember(DataAccessor db, String poolId, String actor) {
        Map<String, Object> pool = requireRecord(db, "crm_lead_pool", poolId, "Lead pool");
        if (!"enabled".equals(string(pool.get("crm_lp_status")))) throw new IllegalStateException("Lead pool is disabled");
        if (!LeadPoolRules.isMember(pool.get("crm_lp_member_user_ids"), pool.get("crm_lp_admin_user_ids"), actor)) {
            throw new SecurityException("Current user is not a member of lead pool " + poolId);
        }
        return pool;
    }

    private static Map<String, Object> requirePoolAdministrator(DataAccessor db, String poolId, String actor) {
        Map<String, Object> pool = requireRecord(db, "crm_lead_pool", poolId, "Lead pool");
        if (!LeadPoolRules.isAdministrator(pool.get("crm_lp_admin_user_ids"), actor)) {
            throw new SecurityException("Current user is not an administrator of lead pool " + poolId);
        }
        return pool;
    }

    private static Map<String, Object> poolItemSnapshot(Map<String, Object> lead, String leadId, String poolId,
                                                         String previousOwner, String actor, String reason,
                                                         Instant now, int cooldownDays) {
        HashMap<String, Object> data = new HashMap<>();
        data.put("crm_lpi_lead_key", leadId);
        data.put("crm_lpi_lead_id", leadId);
        data.put("crm_lpi_pool_id", poolId);
        data.put("crm_lpi_status", "available");
        copy(data, "crm_lpi_lead_code", lead, "crm_lead_code");
        copy(data, "crm_lpi_company", lead, "crm_lead_company");
        copy(data, "crm_lpi_contact_name", lead, "crm_lead_contact_name");
        copy(data, "crm_lpi_contact_phone", lead, "crm_lead_contact_phone");
        copy(data, "crm_lpi_source", lead, "crm_lead_source");
        copy(data, "crm_lpi_score", lead, "crm_lead_score");
        data.put("crm_lpi_previous_owner", previousOwner);
        data.put("crm_lpi_entered_at", now.toString());
        data.put("crm_lpi_entered_by", actor);
        data.put("crm_lpi_reason", reason);
        data.put("crm_lpi_claim_release_at", LeadPoolRules.releaseAt(now, cooldownDays).toString());
        data.put("crm_lpi_claimed_at", null);
        data.put("crm_lpi_claimed_by", null);
        data.put("crm_lpi_recycle_token", null);
        return data;
    }

    private static Map<String, Object> appendHistory(DataAccessor db, String leadId, String poolId, String event,
                                      String previousOwner, String nextOwner, String actor, String reason, Instant now) {
        return db.create("crm_lead_owner_history", historyRow(leadId, poolId, event,
                previousOwner, nextOwner, actor, reason, now));
    }

    private static HashMap<String, Object> historyRow(String leadId, String poolId, String event,
                                                       String previousOwner, String nextOwner, String actor,
                                                       String reason, Instant now) {
        HashMap<String, Object> row = new HashMap<>();
        row.put("crm_loh_lead_id", leadId);
        row.put("crm_loh_pool_id", poolId);
        row.put("crm_loh_event", event);
        row.put("crm_loh_previous_owner", previousOwner);
        row.put("crm_loh_next_owner", nextOwner);
        row.put("crm_loh_actor", actor);
        row.put("crm_loh_reason", reason);
        row.put("crm_loh_occurred_at", now.toString());
        return row;
    }

    private static void syncPoolRecordShares(RecordShareAccessor shares, long tenantId,
                                             Map<String, Object> pool, String resourceCode,
                                             String recordPid) {
        Set<String> users = new java.util.LinkedHashSet<>(
                LeadPoolRules.userIds(pool.get("crm_lp_member_user_ids")));
        users.addAll(LeadPoolRules.userIds(pool.get("crm_lp_admin_user_ids")));
        if ("crm_lead_pool_item".equals(resourceCode) || "crm_lead_common".equals(resourceCode)) {
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

    private static String requireActor(CommandContext context) {
        return required(context.currentUserPid(),
                "Authenticated actor context is required");
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
