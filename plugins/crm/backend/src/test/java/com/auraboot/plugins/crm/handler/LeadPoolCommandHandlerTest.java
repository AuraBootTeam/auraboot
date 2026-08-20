package com.auraboot.plugins.crm.handler;

import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension.CommandContext;
import com.auraboot.framework.plugin.extension.DataAccessor;
import com.auraboot.framework.plugin.extension.RecordShareAccessor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class LeadPoolCommandHandlerTest {

    private final LeadPoolCommandHandler handler = new LeadPoolCommandHandler();

    @Test
    void moveCreatesSharedProjectionClearsOwnerAndAppendsHistory() {
        FakeDb db = baseline();
        FakeShares shares = new FakeShares();
        Map<?, ?> result = execute(db, shares, LeadPoolCommandHandler.MOVE, "lead-1", "member-a",
                Map.of("poolId", "pool-1", "reason", "No progress"));

        assertEquals("available", result.get("status"));
        assertNull(db.getById("crm_lead_common", "lead-1").get("crm_lead_assigned_to"));
        assertEquals("in_pool", db.getById("crm_lead_common", "lead-1").get("crm_lead_pool_state"));
        assertEquals(1, db.query("crm_lead_pool_item", Map.of("crm_lpi_status", "available")).size());
        assertEquals("moved_to_pool", db.query("crm_lead_owner_history", Map.of()).getFirst().get("crm_loh_event"));
        assertEquals(3, shares.calls.size());
        assertTrue(shares.calls.stream().allMatch(call ->
                call.userPids().equals(java.util.Set.of("member-a", "rep-b", "manager"))));
        assertTrue(shares.calls.stream().anyMatch(call ->
                call.resourceCode().equals("crm_lead_pool_item")
                        && call.permissionMask().equals("read,update")));
        assertTrue(shares.calls.stream().anyMatch(call ->
                call.resourceCode().equals("crm_lead_common")
                        && call.permissionMask().equals("read,update")));
        assertTrue(shares.calls.stream().anyMatch(call ->
                call.resourceCode().equals("crm_lead_owner_history")
                        && call.permissionMask().equals("read")));
    }

    @Test
    void claimEnforcesMembershipCapacityDailyLimitAndBothCooldowns() {
        FakeDb cooldown = availableItem(baseline(), "item-1", "lead-1", "member-a",
                Instant.now().minus(Duration.ofDays(1)));
        cooldown.getById("crm_lead_pool", "pool-1").put("crm_lp_new_cooldown_days", 2);
        assertThrows(IllegalStateException.class, () -> execute(cooldown, LeadPoolCommandHandler.CLAIM,
                "item-1", "member-a", Map.of()));

        FakeDb capacity = availableItem(baseline(), "item-1", "lead-1", "other-owner",
                Instant.parse("2026-08-01T00:00:00Z"));
        capacity.put("crm_lead_capacity", "cap-1", map("crm_lcap_user_id", "member-a",
                "crm_lcap_capacity", 1, "crm_lcap_status", "active"));
        capacity.put("crm_lead_common", "owned-2", lead("owned-2", "member-a"));
        assertThrows(IllegalStateException.class, () -> execute(capacity, LeadPoolCommandHandler.CLAIM,
                "item-1", "member-a", Map.of()));

        FakeDb daily = availableItem(baseline(), "item-1", "lead-1", "other-owner",
                Instant.parse("2026-08-01T00:00:00Z"));
        daily.getById("crm_lead_pool", "pool-1").put("crm_lp_daily_pick_limit", 1);
        execute(daily, LeadPoolCommandHandler.CLAIM, "item-1", "member-a", Map.of());
        daily.put("crm_lead_common", "lead-2", lead("lead-2", "other-owner"));
        availableItem(daily, "item-2", "lead-2", "other-owner", Instant.parse("2026-08-01T00:00:00Z"));
        assertThrows(IllegalStateException.class, () -> execute(daily, LeadPoolCommandHandler.CLAIM,
                "item-2", "member-a", Map.of()));

        FakeDb previous = availableItem(baseline(), "item-1", "lead-1", "member-a",
                Instant.now().minus(Duration.ofDays(1)));
        previous.getById("crm_lead_pool", "pool-1").put("crm_lp_new_cooldown_days", 0);
        previous.getById("crm_lead_pool", "pool-1").put("crm_lp_previous_owner_cooldown_days", 7);
        assertThrows(IllegalStateException.class, () -> execute(previous, LeadPoolCommandHandler.CLAIM,
                "item-1", "member-a", Map.of()));

        assertThrows(SecurityException.class, () -> execute(previous, LeadPoolCommandHandler.CLAIM,
                "item-1", "stranger", Map.of()));
    }

    @Test
    void administratorAssignsAndClaimCasAllowsOnlyOneWinner() throws Exception {
        FakeDb assign = availableItem(baseline(), "item-1", "lead-1", "member-a",
                Instant.parse("2026-08-14T00:00:00Z"));
        Map<?, ?> result = execute(assign, LeadPoolCommandHandler.ASSIGN, "item-1", "manager",
                Map.of("assigneeId", "rep-b"));
        assertEquals("rep-b", result.get("ownerId"));
        assertEquals("assigned", assign.getById("crm_lead_pool_item", "item-1").get("crm_lpi_status"));

        FakeDb race = availableItem(baseline(), "item-1", "lead-1", "member-a",
                Instant.parse("2026-08-01T00:00:00Z"));
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Callable<Boolean>> attempts = List.of(
                    () -> succeeds(() -> execute(race, LeadPoolCommandHandler.CLAIM, "item-1", "manager", Map.of())),
                    () -> succeeds(() -> execute(race, LeadPoolCommandHandler.CLAIM, "item-1", "manager", Map.of())));
            long winners = executor.invokeAll(attempts).stream().filter(future -> {
                try { return future.get(); } catch (Exception error) { throw new RuntimeException(error); }
            }).count();
            assertEquals(1, winners);
        }
    }

    @Test
    void claimAcceptsPostgresTimestampTextReturnedByDynamicDataAccessor() {
        FakeDb db = availableItem(baseline(), "item-1", "lead-1", "other-owner",
                Instant.parse("2026-08-01T00:00:00Z"));
        FakeShares shares = new FakeShares();
        db.getById("crm_lead_pool_item", "item-1")
                .put("crm_lpi_available_at", "2026-08-01 00:00:00.123456");

        Map<?, ?> result = execute(db, shares, LeadPoolCommandHandler.CLAIM, "item-1", "manager", Map.of());

        assertEquals("claimed", result.get("status"));
        assertEquals("manager", result.get("ownerId"));
        assertTrue(shares.calls.stream().anyMatch(call ->
                call.resourceCode().equals("crm_lead_common")
                        && call.userPids().equals(java.util.Set.of("manager"))
                        && call.permissionMask().equals("read,update")));
    }

    @Test
    void poolToggleDeleteGuardAndAutomaticRecycleAreGoverned() {
        FakeDb db = availableItem(baseline(), "item-1", "lead-1", "member-a",
                Instant.parse("2026-08-01T00:00:00Z"));
        assertThrows(IllegalStateException.class, () -> execute(db, LeadPoolCommandHandler.DELETE_POOL,
                "pool-1", "manager", Map.of()));
        assertEquals("disabled", execute(db, LeadPoolCommandHandler.TOGGLE, "pool-1", "manager", Map.of()).get("status"));

        FakeDb recycle = baseline();
        recycle.getById("crm_lead_pool", "pool-1").put("crm_lp_auto_recycle", true);
        recycle.getById("crm_lead_pool", "pool-1").put("crm_lp_recycle_after_days", 5);
        recycle.getById("crm_lead_common", "lead-1").put("crm_lead_last_pool_id", "pool-1");
        recycle.getById("crm_lead_common", "lead-1").put("crm_lead_claimed_at", "2026-08-01T00:00:00Z");
        int count = LeadPoolCommandHandler.recycle(recycle, new FakeShares(), 1L,
                "system", Instant.parse("2026-08-14T00:00:00Z"));
        assertEquals(1, count);
        assertEquals("in_pool", recycle.getById("crm_lead_common", "lead-1").get("crm_lead_pool_state"));
        assertEquals("auto_recycled", recycle.query("crm_lead_owner_history", Map.of()).getFirst().get("crm_loh_event"));
    }

    @Test
    void configuredRecycleRulesRespectAllAndAnyCompositionWithoutLegacyMigration() {
        Instant now = Instant.parse("2026-08-14T00:00:00Z");
        FakeDb all = configuredRuleBaseline();
        all.getById("crm_lead_pool", "pool-1").put("crm_lp_recycle_match_mode", "all");

        LeadPoolCommandHandler.RecycleResult allResult = LeadPoolCommandHandler.recycleDetailed(
                all, new FakeShares(), 1L, "system", now, Duration.ofMinutes(15));

        assertEquals(0, allResult.recycled(), "recent activity must keep an ALL policy from matching");
        assertEquals("owned", all.getById("crm_lead_common", "lead-1").get("crm_lead_pool_state"));

        FakeDb any = configuredRuleBaseline();
        any.getById("crm_lead_pool", "pool-1").put("crm_lp_recycle_match_mode", "any");

        LeadPoolCommandHandler.RecycleResult anyResult = LeadPoolCommandHandler.recycleDetailed(
                any, new FakeShares(), 1L, "system", now, Duration.ofMinutes(15));

        assertEquals(1, anyResult.recycled(), "old claim time must satisfy an ANY policy");
        assertEquals("in_pool", any.getById("crm_lead_common", "lead-1").get("crm_lead_pool_state"));
    }

    @Test
    void fixedRecycleIntervalsAndMissingCordysTimeSemanticsAreExplicit() {
        Instant now = Instant.parse("2026-08-14T00:00:00Z");
        Map<String, Object> lead = map(
                "crm_lead_claimed_at", "2026-08-05T12:00:00Z",
                "crm_lead_last_activity_at", "2026-08-13T12:00:00Z");
        Map<String, Object> item = map("crm_lpi_entered_at", "2026-08-01T00:00:00Z");

        assertTrue(LeadPoolCommandHandler.matchesRecycleRule(map(
                "crm_lprr_time_source", "claimed_at",
                "crm_lprr_operator", "fixed_between",
                "crm_lprr_start_at", "2026-08-05T00:00:00Z",
                "crm_lprr_end_at", "2026-08-06T00:00:00Z"), lead, item, now));
        assertFalse(LeadPoolCommandHandler.matchesRecycleRule(map(
                "crm_lprr_time_source", "last_activity_at",
                "crm_lprr_operator", "older_than_days",
                "crm_lprr_days", 5), lead, item, now));
        assertTrue(LeadPoolCommandHandler.matchesRecycleRule(map(
                "crm_lprr_time_source", "last_activity_at",
                "crm_lprr_operator", "older_than_days",
                "crm_lprr_days", 5), Map.of(), item, now),
                "Cordys treats a missing selected time as satisfying the condition");
        assertThrows(IllegalArgumentException.class, () -> LeadPoolCommandHandler.matchesRecycleRule(map(
                "crm_lprr_time_source", "claimed_at",
                "crm_lprr_operator", "fixed_between",
                "crm_lprr_start_at", "2026-08-07T00:00:00Z",
                "crm_lprr_end_at", "2026-08-06T00:00:00Z"), lead, item, now));
    }

    @Test
    void concurrentSchedulersProduceOneRecycleAndOneHistoryRecord() throws Exception {
        FakeDb db = baseline();
        db.getById("crm_lead_pool", "pool-1").put("crm_lp_auto_recycle", true);
        db.getById("crm_lead_pool", "pool-1").put("crm_lp_recycle_after_days", 5);
        db.getById("crm_lead_common", "lead-1").put("crm_lead_last_pool_id", "pool-1");
        db.getById("crm_lead_common", "lead-1").put("crm_lead_claimed_at", "2026-08-01T00:00:00Z");
        Instant now = Instant.now();

        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Callable<LeadPoolCommandHandler.RecycleResult>> attempts = List.of(
                    () -> LeadPoolCommandHandler.recycleDetailed(db, new FakeShares(), 1L,
                            "system", now, Duration.ofMinutes(15)),
                    () -> LeadPoolCommandHandler.recycleDetailed(db, new FakeShares(), 1L,
                            "system", now, Duration.ofMinutes(15)));
            int recycled = executor.invokeAll(attempts).stream().mapToInt(future -> {
                try { return future.get().recycled(); } catch (Exception error) { throw new RuntimeException(error); }
            }).sum();
            assertEquals(1, recycled);
        }

        assertEquals(1, db.query("crm_lead_owner_history", Map.of(
                "crm_loh_event", "auto_recycled")).size());
        assertEquals("available", db.query("crm_lead_pool_item", Map.of()).getFirst()
                .get("crm_lpi_status"));
        assertNull(db.query("crm_lead_pool_item", Map.of()).getFirst()
                .get("crm_lpi_recycle_token"));
        assertEquals("in_pool", db.getById("crm_lead_common", "lead-1")
                .get("crm_lead_pool_state"));
    }

    @Test
    void staleRecycleLeaseResumesWithoutDuplicatingHistory() {
        FakeDb db = baseline();
        db.getById("crm_lead_pool", "pool-1").put("crm_lp_auto_recycle", true);
        db.getById("crm_lead_pool", "pool-1").put("crm_lp_recycle_after_days", 5);
        db.getById("crm_lead_common", "lead-1").put("crm_lead_last_pool_id", "pool-1");
        db.getById("crm_lead_common", "lead-1").put("crm_lead_pool_state", "in_pool");
        db.getById("crm_lead_common", "lead-1").put("crm_lead_assigned_to", null);
        db.put("crm_lead_pool_item", "item-1", map(
                "crm_lpi_lead_key", "lead-1", "crm_lpi_lead_id", "lead-1",
                "crm_lpi_pool_id", "pool-1", "crm_lpi_status", "recycling",
                "crm_lpi_previous_owner", "member-a", "crm_lpi_recycle_token", "recycle-op-1",
                "updated_at", "2026-08-01T00:00:00Z"));
        db.put("crm_lead_owner_history", "history-1", map(
                "crm_loh_operation_key", "recycle-op-1", "crm_loh_event", "auto_recycled",
                "crm_loh_lead_id", "lead-1", "crm_loh_pool_id", "pool-1"));

        LeadPoolCommandHandler.RecycleResult result = LeadPoolCommandHandler.recycleDetailed(
                db, new FakeShares(), 1L, "system", Instant.parse("2026-08-14T00:00:00Z"),
                Duration.ofMinutes(15));

        assertEquals(1, result.recycled());
        assertEquals(1, result.recovered());
        assertEquals(0, result.failed());
        assertEquals("available", db.getById("crm_lead_pool_item", "item-1").get("crm_lpi_status"));
        assertNull(db.getById("crm_lead_pool_item", "item-1").get("crm_lpi_recycle_token"));
        assertEquals(1, db.query("crm_lead_owner_history", Map.of(
                "crm_loh_operation_key", "recycle-op-1")).size());
    }

    @Test
    void completedRecycleClearsTokenSoLaterOwnershipCycleGetsNewHistory() {
        FakeDb db = baseline();
        db.getById("crm_lead_pool", "pool-1").put("crm_lp_auto_recycle", true);
        db.getById("crm_lead_pool", "pool-1").put("crm_lp_recycle_after_days", 5);
        Map<String, Object> lead = db.getById("crm_lead_common", "lead-1");
        lead.put("crm_lead_last_pool_id", "pool-1");
        lead.put("crm_lead_claimed_at", "2026-08-01T00:00:00Z");

        assertEquals(1, LeadPoolCommandHandler.recycleDetailed(
                db, new FakeShares(), 1L, "system", Instant.parse("2026-08-14T00:00:00Z"),
                Duration.ofMinutes(15)).recycled());

        Map<String, Object> item = db.query("crm_lead_pool_item", Map.of()).getFirst();
        String firstOperation = java.util.Objects.toString(db.query("crm_lead_owner_history", Map.of()).getFirst()
                .get("crm_loh_operation_key"), null);
        item.put("crm_lpi_status", "assigned");
        item.put("crm_lpi_previous_owner", "member-b");
        lead.put("crm_lead_pool_state", "owned");
        lead.put("crm_lead_assigned_to", "member-b");
        lead.put("crm_lead_claimed_at", "2026-08-15T00:00:00Z");

        assertEquals(1, LeadPoolCommandHandler.recycleDetailed(
                db, new FakeShares(), 1L, "system", Instant.parse("2026-08-22T00:00:00Z"),
                Duration.ofMinutes(15)).recycled());

        List<Map<String, Object>> histories = db.query("crm_lead_owner_history", Map.of(
                "crm_loh_event", "auto_recycled"));
        assertNotNull(firstOperation);
        assertEquals(2, histories.size());
        assertEquals(2, histories.stream().map(row -> row.get("crm_loh_operation_key")).distinct().count());
        assertNull(item.get("crm_lpi_recycle_token"));
    }

    @Test
    void freshRecycleLeaseIsNotStolenByAnotherScheduler() {
        FakeDb db = baseline();
        db.getById("crm_lead_pool", "pool-1").put("crm_lp_auto_recycle", true);
        db.put("crm_lead_pool_item", "item-1", map(
                "crm_lpi_lead_key", "lead-1", "crm_lpi_lead_id", "lead-1",
                "crm_lpi_pool_id", "pool-1", "crm_lpi_status", "recycling",
                "crm_lpi_recycle_token", "recycle-op-1", "updated_at", "2026-08-14T00:00:00Z"));

        LeadPoolCommandHandler.RecycleResult result = LeadPoolCommandHandler.recycleDetailed(
                db, new FakeShares(), 1L, "system", Instant.parse("2026-08-14T00:05:00Z"),
                Duration.ofMinutes(15));

        assertEquals(0, result.recycled());
        assertEquals(1, result.activeLeases());
        assertEquals("recycling", db.getById("crm_lead_pool_item", "item-1").get("crm_lpi_status"));
    }

    @Test
    void staleWorkerCannotCommitAfterItsLeaseTokenWasFenced() {
        FakeDb db = baseline();
        db.put("crm_lead_pool_item", "item-1", map(
                "crm_lpi_lead_key", "lead-1", "crm_lpi_lead_id", "lead-1",
                "crm_lpi_pool_id", "pool-1", "crm_lpi_status", "recycling",
                "crm_lpi_previous_owner", "member-a",
                "crm_lpi_recycle_token", "operation-1:old-lease",
                "updated_at", "2026-08-01T00:00:00Z"));
        Map<String, Object> staleSnapshot = new HashMap<>(
                db.getById("crm_lead_pool_item", "item-1"));
        db.getById("crm_lead_pool_item", "item-1")
                .put("crm_lpi_recycle_token", "operation-1:new-lease");
        FakeShares shares = new FakeShares();

        boolean completed = LeadPoolCommandHandler.completeRecycle(
                db, shares, 1L, db.getById("crm_lead_pool", "pool-1"), staleSnapshot,
                "system", 5, Instant.parse("2026-08-14T00:00:00Z"));

        assertFalse(completed);
        assertEquals("owned", db.getById("crm_lead_common", "lead-1").get("crm_lead_pool_state"));
        assertTrue(db.query("crm_lead_owner_history", Map.of()).isEmpty());
        assertTrue(shares.calls.isEmpty());
        assertEquals("operation-1:new-lease",
                db.getById("crm_lead_pool_item", "item-1").get("crm_lpi_recycle_token"));
    }

    private Map<String, Object> execute(FakeDb db, String command, String recordId, String actor,
                                        Map<String, Object> payload) {
        return execute(db, new FakeShares(), command, recordId, actor, payload);
    }

    private Map<String, Object> execute(FakeDb db, FakeShares shares, String command,
                                        String recordId, String actor, Map<String, Object> payload) {
        CommandContext context = new CommandContext(1L, "com.auraboot.crm", "crm", command,
                command.contains("pool_lead") ? "crm_lead_pool_item" : "crm_lead_common",
                recordId, payload, Map.of(
                        "__dataAccessor", db,
                        CommandHandlerExtension.CURRENT_USER_PID_KEY, actor,
                        RecordShareAccessor.SETTINGS_KEY, shares), false);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) handler.execute(context);
        return result;
    }

    private static boolean succeeds(Runnable work) {
        try { work.run(); return true; } catch (RuntimeException ignored) { return false; }
    }

    private static final class FakeShares implements RecordShareAccessor {
        private final List<ShareCall> calls = new ArrayList<>();

        @Override
        public void replaceReadSharesForUsers(long tenantId, String resourceCode,
                                              String recordPid, Collection<String> userPids) {
            calls.add(new ShareCall(resourceCode, recordPid, java.util.Set.copyOf(userPids), "read"));
        }

        @Override
        public void replaceReadUpdateSharesForUsers(long tenantId, String resourceCode,
                                                    String recordPid, Collection<String> userPids) {
            calls.add(new ShareCall(
                    resourceCode, recordPid, java.util.Set.copyOf(userPids), "read,update"));
        }
    }

    private record ShareCall(String resourceCode, String recordPid,
                             java.util.Set<String> userPids, String permissionMask) {}

    private static FakeDb baseline() {
        FakeDb db = new FakeDb();
        db.put("crm_lead_pool", "pool-1", map(
                "crm_lp_status", "enabled", "crm_lp_member_user_ids", "member-a,rep-b",
                "crm_lp_admin_user_ids", "manager", "crm_lp_daily_pick_limit", 20,
                "crm_lp_new_cooldown_days", 0, "crm_lp_previous_owner_cooldown_days", 0,
                "crm_lp_auto_recycle", false, "crm_lp_recycle_after_days", 30,
                "crm_lp_recycle_basis", "claimed_at"));
        db.put("crm_lead_common", "lead-1", lead("lead-1", "member-a"));
        return db;
    }

    private static FakeDb configuredRuleBaseline() {
        FakeDb db = baseline();
        Map<String, Object> pool = db.getById("crm_lead_pool", "pool-1");
        pool.put("crm_lp_auto_recycle", true);
        pool.put("crm_lp_recycle_after_days", 365);
        Map<String, Object> lead = db.getById("crm_lead_common", "lead-1");
        lead.put("crm_lead_last_pool_id", "pool-1");
        lead.put("crm_lead_claimed_at", "2026-08-01T00:00:00Z");
        lead.put("crm_lead_last_activity_at", "2026-08-13T00:00:00Z");
        db.put("crm_lead_pool_item", "item-1", map(
                "crm_lpi_lead_key", "lead-1", "crm_lpi_lead_id", "lead-1",
                "crm_lpi_pool_id", "pool-1", "crm_lpi_status", "claimed",
                "crm_lpi_entered_at", "2026-07-25T00:00:00Z",
                "crm_lpi_claimed_at", "2026-08-01T00:00:00Z",
                "crm_lpi_claimed_by", "member-a"));
        db.put("crm_lead_pool_recycle_rule", "rule-1", map(
                "crm_lprr_pool_id", "pool-1", "crm_lprr_status", "active",
                "crm_lprr_time_source", "claimed_at", "crm_lprr_operator", "older_than_days",
                "crm_lprr_days", 5, "crm_lprr_sort_order", 10));
        db.put("crm_lead_pool_recycle_rule", "rule-2", map(
                "crm_lprr_pool_id", "pool-1", "crm_lprr_status", "active",
                "crm_lprr_time_source", "last_activity_at", "crm_lprr_operator", "older_than_days",
                "crm_lprr_days", 5, "crm_lprr_sort_order", 20));
        return db;
    }

    private static FakeDb availableItem(FakeDb db, String itemId, String leadId, String previousOwner, Instant entered) {
        db.put("crm_lead_pool_item", itemId, map(
                "crm_lpi_lead_key", leadId, "crm_lpi_lead_id", leadId, "crm_lpi_pool_id", "pool-1",
                "crm_lpi_status", "available", "crm_lpi_previous_owner", previousOwner,
                "crm_lpi_entered_at", entered.toString()));
        return db;
    }

    private static Map<String, Object> lead(String id, String owner) {
        return map("crm_lead_code", id.toUpperCase(), "crm_lead_company", "Acme " + id,
                "crm_lead_status", "new", "crm_lead_pool_state", "owned",
                "crm_lead_assigned_to", owner, "crm_lead_score", 80);
    }

    private static Map<String, Object> map(Object... values) {
        HashMap<String, Object> result = new HashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put(values[i].toString(), values[i + 1]);
        return result;
    }

    private static final class FakeDb implements DataAccessor {
        private final Map<String, LinkedHashMap<String, Map<String, Object>>> rows = new HashMap<>();
        private long sequence = 100;

        void put(String model, String id, Map<String, Object> values) {
            HashMap<String, Object> copy = new HashMap<>(values);
            copy.put("pid", id);
            rows.computeIfAbsent(model, ignored -> new LinkedHashMap<>()).put(id, copy);
        }

        @Override public synchronized Map<String, Object> getById(String modelCode, String recordId) {
            return rows.getOrDefault(modelCode, new LinkedHashMap<>()).get(recordId);
        }

        @Override public synchronized List<Map<String, Object>> query(String modelCode, Map<String, Object> filters) {
            return rows.getOrDefault(modelCode, new LinkedHashMap<>()).values().stream()
                    .filter(row -> filters.entrySet().stream().allMatch(entry ->
                            java.util.Objects.equals(row.get(entry.getKey()), entry.getValue())))
                    .toList();
        }

        @Override public synchronized Map<String, Object> create(String modelCode, Map<String, Object> data) {
            String id = "generated-" + (++sequence);
            put(modelCode, id, data);
            getById(modelCode, id).putIfAbsent("updated_at", Instant.now().toString());
            return getById(modelCode, id);
        }

        @Override public synchronized Optional<Map<String, Object>> tryCreate(
                String modelCode, Map<String, Object> data) {
            String uniqueField = switch (modelCode) {
                case "crm_lead_pool_item" -> "crm_lpi_lead_key";
                case "crm_lead_owner_history" -> "crm_loh_operation_key";
                default -> null;
            };
            if (uniqueField != null && data.get(uniqueField) != null
                    && !query(modelCode, Map.of(uniqueField, data.get(uniqueField))).isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(create(modelCode, data));
        }

        @Override public synchronized Map<String, Object> update(String modelCode, String recordId, Map<String, Object> data) {
            Map<String, Object> row = getById(modelCode, recordId);
            if (row == null) return null;
            row.putAll(data);
            row.put("updated_at", Instant.now().toString());
            return row;
        }

        @Override public synchronized boolean compareAndSet(String modelCode, String recordId, String fieldCode,
                                                            Object expectedValue, Object nextValue) {
            Map<String, Object> nextValues = new HashMap<>();
            nextValues.put(fieldCode, nextValue);
            return compareAndSet(modelCode, recordId, fieldCode, expectedValue, nextValues);
        }

        @Override public synchronized boolean compareAndSet(String modelCode, String recordId, String fieldCode,
                                                            Object expectedValue,
                                                            Map<String, Object> nextValues) {
            Map<String, Object> row = getById(modelCode, recordId);
            if (row == null || !java.util.Objects.equals(row.get(fieldCode), expectedValue)) return false;
            row.putAll(nextValues);
            row.put("updated_at", Instant.now().toString());
            return true;
        }

        @Override public synchronized Optional<Long> incrementWithinCap(String modelCode, String recordId,
                                                                         String counterCode, long delta, String capCode) {
            Map<String, Object> row = getById(modelCode, recordId);
            long current = ((Number) row.get(counterCode)).longValue();
            long cap = ((Number) row.get(capCode)).longValue();
            if (current + delta > cap) return Optional.empty();
            row.put(counterCode, current + delta);
            return Optional.of(current + delta);
        }

        @Override public List<Map<String, Object>> batchCreate(String modelCode, List<Map<String, Object>> dataList) {
            return dataList.stream().map(data -> create(modelCode, data)).toList();
        }

        @Override public synchronized void delete(String modelCode, String recordId) {
            rows.getOrDefault(modelCode, new LinkedHashMap<>()).remove(recordId);
        }

        @Override public void batchDelete(String modelCode, Collection<String> recordIds) {
            recordIds.forEach(id -> delete(modelCode, id));
        }
    }
}
