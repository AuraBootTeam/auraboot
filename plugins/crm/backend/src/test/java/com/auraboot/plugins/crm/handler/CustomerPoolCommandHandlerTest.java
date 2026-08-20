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

class CustomerPoolCommandHandlerTest {

    private final CustomerPoolCommandHandler handler = new CustomerPoolCommandHandler();

    @Test
    void moveCreatesSharedProjectionClearsOwnerAndAppendsHistory() {
        FakeDb db = baseline();
        FakeShares shares = new FakeShares();
        Map<?, ?> result = execute(db, shares, CustomerPoolCommandHandler.MOVE, "customer-1", "member-a",
                Map.of("poolId", "pool-1", "reason", "No progress"));

        assertEquals("available", result.get("status"));
        assertNull(db.getById("crm_account_common", "customer-1").get("crm_acc_owner"));
        assertEquals("in_pool", db.getById("crm_account_common", "customer-1").get("crm_acc_pool_state"));
        assertEquals(1, db.query("crm_customer_pool_item_common", Map.of("crm_cpi_status", "available")).size());
        assertEquals("moved_to_pool", db.query("crm_customer_owner_history_common", Map.of()).getFirst().get("crm_coh_event"));
        assertEquals(3, shares.calls.size());
        assertTrue(shares.calls.stream().allMatch(call ->
                call.userPids().equals(java.util.Set.of("member-a", "rep-b", "manager"))));
        assertTrue(shares.calls.stream().anyMatch(call ->
                call.resourceCode().equals("crm_customer_pool_item_common")
                        && call.permissionMask().equals("read,update")));
        assertTrue(shares.calls.stream().anyMatch(call ->
                call.resourceCode().equals("crm_account_common")
                        && call.permissionMask().equals("read,update")));
        assertTrue(shares.calls.stream().anyMatch(call ->
                call.resourceCode().equals("crm_customer_owner_history_common")
                        && call.permissionMask().equals("read")));
    }

    @Test
    void moveRequiresAnActiveCustomerOwnedByTheActorUnlessPoolAdministrator() {
        FakeDb inactive = baseline();
        inactive.getById("crm_account_common", "customer-1").put("crm_acc_status", "inactive");
        assertThrows(IllegalStateException.class, () -> execute(inactive,
                CustomerPoolCommandHandler.MOVE, "customer-1", "member-a", Map.of("poolId", "pool-1")));

        FakeDb otherOwner = baseline();
        assertThrows(SecurityException.class, () -> execute(otherOwner,
                CustomerPoolCommandHandler.MOVE, "customer-1", "rep-b", Map.of("poolId", "pool-1")));

        FakeDb administrator = baseline();
        assertEquals("available", execute(administrator, CustomerPoolCommandHandler.MOVE,
                "customer-1", "manager", Map.of("poolId", "pool-1")).get("status"));
    }

    @Test
    void claimEnforcesMembershipCapacityDailyLimitAndBothCooldowns() {
        FakeDb cooldown = availableItem(baseline(), "item-1", "customer-1", "member-a",
                Instant.now().minus(Duration.ofDays(1)));
        cooldown.getById("crm_customer_pool_common", "pool-1").put("crm_cp_new_cooldown_days", 2);
        assertThrows(IllegalStateException.class, () -> execute(cooldown, CustomerPoolCommandHandler.CLAIM,
                "item-1", "member-a", Map.of()));

        FakeDb capacity = availableItem(baseline(), "item-1", "customer-1", "other-owner",
                Instant.parse("2026-08-01T00:00:00Z"));
        capacity.put("crm_customer_capacity_common", "cap-1", map("crm_ccap_user_id", "member-a",
                "crm_ccap_capacity", 1, "crm_ccap_status", "active"));
        capacity.put("crm_account_common", "owned-2", customer("owned-2", "member-a"));
        assertThrows(IllegalStateException.class, () -> execute(capacity, CustomerPoolCommandHandler.CLAIM,
                "item-1", "member-a", Map.of()));

        FakeDb daily = availableItem(baseline(), "item-1", "customer-1", "other-owner",
                Instant.parse("2026-08-01T00:00:00Z"));
        daily.getById("crm_customer_pool_common", "pool-1").put("crm_cp_daily_pick_limit", 1);
        execute(daily, CustomerPoolCommandHandler.CLAIM, "item-1", "member-a", Map.of());
        daily.put("crm_account_common", "customer-2", customer("customer-2", "other-owner"));
        availableItem(daily, "item-2", "customer-2", "other-owner", Instant.parse("2026-08-01T00:00:00Z"));
        assertThrows(IllegalStateException.class, () -> execute(daily, CustomerPoolCommandHandler.CLAIM,
                "item-2", "member-a", Map.of()));

        FakeDb previous = availableItem(baseline(), "item-1", "customer-1", "member-a",
                Instant.now().minus(Duration.ofDays(1)));
        previous.getById("crm_customer_pool_common", "pool-1").put("crm_cp_new_cooldown_days", 0);
        previous.getById("crm_customer_pool_common", "pool-1").put("crm_cp_previous_owner_cooldown_days", 7);
        assertThrows(IllegalStateException.class, () -> execute(previous, CustomerPoolCommandHandler.CLAIM,
                "item-1", "member-a", Map.of()));

        assertThrows(SecurityException.class, () -> execute(previous, CustomerPoolCommandHandler.CLAIM,
                "item-1", "stranger", Map.of()));
    }

    @Test
    void administratorAssignsAndClaimCasAllowsOnlyOneWinner() throws Exception {
        FakeDb assign = availableItem(baseline(), "item-1", "customer-1", "member-a",
                Instant.parse("2026-08-14T00:00:00Z"));
        Map<?, ?> result = execute(assign, CustomerPoolCommandHandler.ASSIGN, "item-1", "manager",
                Map.of("assigneeId", "rep-b"));
        assertEquals("rep-b", result.get("ownerId"));
        assertEquals("assigned", assign.getById("crm_customer_pool_item_common", "item-1").get("crm_cpi_status"));

        FakeDb race = availableItem(baseline(), "item-1", "customer-1", "member-a",
                Instant.parse("2026-08-01T00:00:00Z"));
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Callable<Boolean>> attempts = List.of(
                    () -> succeeds(() -> execute(race, CustomerPoolCommandHandler.CLAIM, "item-1", "manager", Map.of())),
                    () -> succeeds(() -> execute(race, CustomerPoolCommandHandler.CLAIM, "item-1", "manager", Map.of())));
            long winners = executor.invokeAll(attempts).stream().filter(future -> {
                try { return future.get(); } catch (Exception error) { throw new RuntimeException(error); }
            }).count();
            assertEquals(1, winners);
        }
    }

    @Test
    void administratorCannotAssignCustomerOutsidePoolMembership() {
        FakeDb db = availableItem(baseline(), "item-1", "customer-1", "member-a",
                Instant.parse("2026-08-14T00:00:00Z"));

        assertThrows(IllegalArgumentException.class, () -> execute(db, CustomerPoolCommandHandler.ASSIGN,
                "item-1", "manager", Map.of("assigneeId", "outside-sales")));
        assertEquals("available", db.getById("crm_customer_pool_item_common", "item-1").get("crm_cpi_status"));
    }

    @Test
    void poolCustomerUpdateWritesTheCustomerAndItsSharedSnapshot() {
        FakeDb db = availableItem(baseline(), "item-1", "customer-1", "member-a",
                Instant.parse("2026-08-01T00:00:00Z"));

        Map<?, ?> result = execute(db, CustomerPoolCommandHandler.UPDATE_CUSTOMER,
                "item-1", "member-a", map(
                        "crm_acc_name", "Acme Renewed",
                        "crm_acc_industry", "manufacturing",
                        "crm_acc_phone", "0755-12345678",
                        "crm_acc_website", "https://acme.example"));

        assertEquals("customer-1", result.get("customerId"));
        assertEquals("Acme Renewed",
                db.getById("crm_account_common", "customer-1").get("crm_acc_name"));
        assertEquals("https://acme.example",
                db.getById("crm_account_common", "customer-1").get("crm_acc_website"));
        assertEquals("Acme Renewed",
                db.getById("crm_customer_pool_item_common", "item-1").get("crm_cpi_account_name"));
        assertEquals("manufacturing",
                db.getById("crm_customer_pool_item_common", "item-1").get("crm_cpi_industry"));
        assertThrows(SecurityException.class, () -> execute(db, CustomerPoolCommandHandler.UPDATE_CUSTOMER,
                "item-1", "outside-sales", Map.of("crm_acc_name", "Forbidden")));
        assertThrows(IllegalArgumentException.class, () -> execute(db, CustomerPoolCommandHandler.UPDATE_CUSTOMER,
                "item-1", "member-a", Map.of("crm_acc_owner", "outside-sales")));
    }

    @Test
    void poolCustomerDeleteBlocksBusinessReferencesAndRemovesOwnedResources() {
        FakeDb referenced = availableItem(baseline(), "item-1", "customer-1", "member-a",
                Instant.parse("2026-08-01T00:00:00Z"));
        referenced.put("crm_contact_common", "contact-1", map("crm_ct_account_id", "customer-1"));
        assertThrows(IllegalStateException.class, () -> execute(referenced,
                CustomerPoolCommandHandler.DELETE_CUSTOMER, "item-1", "member-a", Map.of()));
        assertNotNull(referenced.getById("crm_account_common", "customer-1"));

        FakeDb removable = availableItem(baseline(), "item-1", "customer-1", "member-a",
                Instant.parse("2026-08-01T00:00:00Z"));
        removable.put("crm_customer_owner_history_common", "history-1", map(
                "crm_coh_customer_id", "customer-1"));
        removable.put("crm_activity_relation_common", "relation-1", map(
                "crm_ar_object_type", "account", "crm_ar_object_id", "customer-1"));
        removable.put("crm_activity_common", "activity-1", map(
                "crm_act_related_model", "crm_account_common", "crm_act_related_id", "customer-1"));

        Map<?, ?> result = execute(removable, CustomerPoolCommandHandler.DELETE_CUSTOMER,
                "item-1", "member-a", Map.of());

        assertEquals(true, result.get("deleted"));
        assertNull(removable.getById("crm_account_common", "customer-1"));
        assertNull(removable.getById("crm_customer_pool_item_common", "item-1"));
        assertTrue(removable.query("crm_customer_owner_history_common", Map.of()).isEmpty());
        assertTrue(removable.query("crm_activity_relation_common", Map.of()).isEmpty());
        assertTrue(removable.query("crm_activity_common", Map.of()).isEmpty());
    }

    @Test
    void claimAcceptsPostgresTimestampTextReturnedByDynamicDataAccessor() {
        FakeDb db = availableItem(baseline(), "item-1", "customer-1", "other-owner",
                Instant.parse("2026-08-01T00:00:00Z"));
        FakeShares shares = new FakeShares();
        db.getById("crm_customer_pool_item_common", "item-1")
                .put("crm_cpi_available_at", "2026-08-01 00:00:00.123456");

        Map<?, ?> result = execute(db, shares, CustomerPoolCommandHandler.CLAIM, "item-1", "manager", Map.of());

        assertEquals("claimed", result.get("status"));
        assertEquals("manager", result.get("ownerId"));
        assertTrue(shares.calls.stream().anyMatch(call ->
                call.resourceCode().equals("crm_account_common")
                        && call.userPids().equals(java.util.Set.of("manager"))
                        && call.permissionMask().equals("read,update")));
    }

    @Test
    void poolToggleDeleteGuardAndAutomaticRecycleAreGoverned() {
        FakeDb db = availableItem(baseline(), "item-1", "customer-1", "member-a",
                Instant.parse("2026-08-01T00:00:00Z"));
        assertThrows(IllegalStateException.class, () -> execute(db, CustomerPoolCommandHandler.DELETE_POOL,
                "pool-1", "manager", Map.of()));
        assertEquals("disabled", execute(db, CustomerPoolCommandHandler.TOGGLE, "pool-1", "manager", Map.of()).get("status"));

        FakeDb recycle = baseline();
        recycle.getById("crm_customer_pool_common", "pool-1").put("crm_cp_auto_recycle", true);
        recycle.getById("crm_customer_pool_common", "pool-1").put("crm_cp_recycle_after_days", 5);
        recycle.getById("crm_account_common", "customer-1").put("crm_acc_last_pool_id", "pool-1");
        recycle.getById("crm_account_common", "customer-1").put("crm_acc_claimed_at", "2026-08-01T00:00:00Z");
        int count = CustomerPoolCommandHandler.recycle(recycle, new FakeShares(), 1L,
                "system", Instant.parse("2026-08-14T00:00:00Z"));
        assertEquals(1, count);
        assertEquals("in_pool", recycle.getById("crm_account_common", "customer-1").get("crm_acc_pool_state"));
        assertEquals("auto_recycled", recycle.query("crm_customer_owner_history_common", Map.of()).getFirst().get("crm_coh_event"));
    }

    @Test
    void configuredRecycleRulesRespectAllAndAnyCompositionWithoutLegacyMigration() {
        Instant now = Instant.parse("2026-08-14T00:00:00Z");
        FakeDb all = configuredRuleBaseline();
        all.getById("crm_customer_pool_common", "pool-1").put("crm_cp_recycle_match_mode", "all");

        CustomerPoolCommandHandler.RecycleResult allResult = CustomerPoolCommandHandler.recycleDetailed(
                all, new FakeShares(), 1L, "system", now, Duration.ofMinutes(15));

        assertEquals(0, allResult.recycled(), "recent activity must keep an ALL policy from matching");
        assertEquals("owned", all.getById("crm_account_common", "customer-1").get("crm_acc_pool_state"));

        FakeDb any = configuredRuleBaseline();
        any.getById("crm_customer_pool_common", "pool-1").put("crm_cp_recycle_match_mode", "any");

        CustomerPoolCommandHandler.RecycleResult anyResult = CustomerPoolCommandHandler.recycleDetailed(
                any, new FakeShares(), 1L, "system", now, Duration.ofMinutes(15));

        assertEquals(1, anyResult.recycled(), "old claim time must satisfy an ANY policy");
        assertEquals("in_pool", any.getById("crm_account_common", "customer-1").get("crm_acc_pool_state"));
    }

    @Test
    void realCustomerTimelinePreventsRecycleWhenTheCompatibilityFieldIsStaleOrMissing() {
        Instant now = Instant.parse("2026-08-14T00:00:00Z");
        FakeDb configured = configuredRuleBaseline();
        configured.getById("crm_account_common", "customer-1").remove("crm_acc_last_activity_at");
        configured.put("crm_activity_relation_common", "relation-1", map(
                "crm_ar_activity_id", "activity-1",
                "crm_ar_object_type", "account",
                "crm_ar_object_id", "customer-1"));
        configured.put("crm_activity_common", "activity-1", map(
                "crm_act_date", "2026-08-13T18:00:00Z"));

        CustomerPoolCommandHandler.RecycleResult configuredResult = CustomerPoolCommandHandler.recycleDetailed(
                configured, new FakeShares(), 1L, "system", now, Duration.ofMinutes(15));

        assertEquals(0, configuredResult.recycled());
        assertEquals("owned", configured.getById("crm_account_common", "customer-1").get("crm_acc_pool_state"));

        FakeDb legacy = baseline();
        Map<String, Object> legacyPool = legacy.getById("crm_customer_pool_common", "pool-1");
        legacyPool.put("crm_cp_auto_recycle", true);
        legacyPool.put("crm_cp_recycle_after_days", 5);
        legacyPool.put("crm_cp_recycle_basis", "last_activity_at");
        Map<String, Object> legacyCustomer = legacy.getById("crm_account_common", "customer-1");
        legacyCustomer.put("crm_acc_last_pool_id", "pool-1");
        legacyCustomer.put("crm_acc_claimed_at", "2026-08-01T00:00:00Z");
        legacy.put("crm_activity_common", "activity-direct", map(
                "crm_act_related_model", "crm_account_common",
                "crm_act_related_id", "customer-1",
                "crm_act_date", "2026-08-13T20:00:00Z"));

        CustomerPoolCommandHandler.RecycleResult legacyResult = CustomerPoolCommandHandler.recycleDetailed(
                legacy, new FakeShares(), 1L, "system", now, Duration.ofMinutes(15));

        assertEquals(0, legacyResult.recycled());
        assertEquals("owned", legacyCustomer.get("crm_acc_pool_state"));
    }

    @Test
    void fixedRecycleIntervalsAndMissingCordysTimeSemanticsAreExplicit() {
        Instant now = Instant.parse("2026-08-14T00:00:00Z");
        Map<String, Object> customer = map(
                "crm_acc_claimed_at", "2026-08-05T12:00:00Z",
                "crm_acc_last_activity_at", "2026-08-13T12:00:00Z");
        Map<String, Object> item = map("crm_cpi_entered_at", "2026-08-01T00:00:00Z");

        assertTrue(CustomerPoolCommandHandler.matchesRecycleRule(map(
                "crm_cprr_time_source", "claimed_at",
                "crm_cprr_operator", "fixed_between",
                "crm_cprr_start_at", "2026-08-05T00:00:00Z",
                "crm_cprr_end_at", "2026-08-06T00:00:00Z"), customer, item, now));
        assertFalse(CustomerPoolCommandHandler.matchesRecycleRule(map(
                "crm_cprr_time_source", "last_activity_at",
                "crm_cprr_operator", "older_than_days",
                "crm_cprr_days", 5), customer, item, now));
        assertTrue(CustomerPoolCommandHandler.matchesRecycleRule(map(
                "crm_cprr_time_source", "last_activity_at",
                "crm_cprr_operator", "older_than_days",
                "crm_cprr_days", 5), Map.of(), item, now),
                "Cordys treats a missing selected time as satisfying the condition");
        assertThrows(IllegalArgumentException.class, () -> CustomerPoolCommandHandler.matchesRecycleRule(map(
                "crm_cprr_time_source", "claimed_at",
                "crm_cprr_operator", "fixed_between",
                "crm_cprr_start_at", "2026-08-07T00:00:00Z",
                "crm_cprr_end_at", "2026-08-06T00:00:00Z"), customer, item, now));
    }

    @Test
    void concurrentSchedulersProduceOneRecycleAndOneHistoryRecord() throws Exception {
        FakeDb db = baseline();
        db.getById("crm_customer_pool_common", "pool-1").put("crm_cp_auto_recycle", true);
        db.getById("crm_customer_pool_common", "pool-1").put("crm_cp_recycle_after_days", 5);
        db.getById("crm_account_common", "customer-1").put("crm_acc_last_pool_id", "pool-1");
        db.getById("crm_account_common", "customer-1").put("crm_acc_claimed_at", "2026-08-01T00:00:00Z");
        Instant now = Instant.now();

        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Callable<CustomerPoolCommandHandler.RecycleResult>> attempts = List.of(
                    () -> CustomerPoolCommandHandler.recycleDetailed(db, new FakeShares(), 1L,
                            "system", now, Duration.ofMinutes(15)),
                    () -> CustomerPoolCommandHandler.recycleDetailed(db, new FakeShares(), 1L,
                            "system", now, Duration.ofMinutes(15)));
            int recycled = executor.invokeAll(attempts).stream().mapToInt(future -> {
                try { return future.get().recycled(); } catch (Exception error) { throw new RuntimeException(error); }
            }).sum();
            assertEquals(1, recycled);
        }

        assertEquals(1, db.query("crm_customer_owner_history_common", Map.of(
                "crm_coh_event", "auto_recycled")).size());
        assertEquals("available", db.query("crm_customer_pool_item_common", Map.of()).getFirst()
                .get("crm_cpi_status"));
        assertNull(db.query("crm_customer_pool_item_common", Map.of()).getFirst()
                .get("crm_cpi_recycle_token"));
        assertEquals("in_pool", db.getById("crm_account_common", "customer-1")
                .get("crm_acc_pool_state"));
    }

    @Test
    void staleRecycleLeaseResumesWithoutDuplicatingHistory() {
        FakeDb db = baseline();
        db.getById("crm_customer_pool_common", "pool-1").put("crm_cp_auto_recycle", true);
        db.getById("crm_customer_pool_common", "pool-1").put("crm_cp_recycle_after_days", 5);
        db.getById("crm_account_common", "customer-1").put("crm_acc_last_pool_id", "pool-1");
        db.getById("crm_account_common", "customer-1").put("crm_acc_pool_state", "in_pool");
        db.getById("crm_account_common", "customer-1").put("crm_acc_owner", null);
        db.put("crm_customer_pool_item_common", "item-1", map(
                "crm_cpi_account_key", "customer-1", "crm_cpi_account_id", "customer-1",
                "crm_cpi_pool_id", "pool-1", "crm_cpi_status", "recycling",
                "crm_cpi_previous_owner", "member-a", "crm_cpi_recycle_token", "recycle-op-1",
                "updated_at", "2026-08-01T00:00:00Z"));
        db.put("crm_customer_owner_history_common", "history-1", map(
                "crm_coh_operation_key", "recycle-op-1", "crm_coh_event", "auto_recycled",
                "crm_coh_customer_id", "customer-1", "crm_coh_pool_id", "pool-1"));

        CustomerPoolCommandHandler.RecycleResult result = CustomerPoolCommandHandler.recycleDetailed(
                db, new FakeShares(), 1L, "system", Instant.parse("2026-08-14T00:00:00Z"),
                Duration.ofMinutes(15));

        assertEquals(1, result.recycled());
        assertEquals(1, result.recovered());
        assertEquals(0, result.failed());
        assertEquals("available", db.getById("crm_customer_pool_item_common", "item-1").get("crm_cpi_status"));
        assertNull(db.getById("crm_customer_pool_item_common", "item-1").get("crm_cpi_recycle_token"));
        assertEquals(1, db.query("crm_customer_owner_history_common", Map.of(
                "crm_coh_operation_key", "recycle-op-1")).size());
    }

    @Test
    void completedRecycleClearsTokenSoLaterOwnershipCycleGetsNewHistory() {
        FakeDb db = baseline();
        db.getById("crm_customer_pool_common", "pool-1").put("crm_cp_auto_recycle", true);
        db.getById("crm_customer_pool_common", "pool-1").put("crm_cp_recycle_after_days", 5);
        Map<String, Object> customer = db.getById("crm_account_common", "customer-1");
        customer.put("crm_acc_last_pool_id", "pool-1");
        customer.put("crm_acc_claimed_at", "2026-08-01T00:00:00Z");

        assertEquals(1, CustomerPoolCommandHandler.recycleDetailed(
                db, new FakeShares(), 1L, "system", Instant.parse("2026-08-14T00:00:00Z"),
                Duration.ofMinutes(15)).recycled());

        Map<String, Object> item = db.query("crm_customer_pool_item_common", Map.of()).getFirst();
        String firstOperation = java.util.Objects.toString(db.query("crm_customer_owner_history_common", Map.of()).getFirst()
                .get("crm_coh_operation_key"), null);
        item.put("crm_cpi_status", "assigned");
        item.put("crm_cpi_previous_owner", "member-b");
        customer.put("crm_acc_pool_state", "owned");
        customer.put("crm_acc_owner", "member-b");
        customer.put("crm_acc_claimed_at", "2026-08-15T00:00:00Z");

        assertEquals(1, CustomerPoolCommandHandler.recycleDetailed(
                db, new FakeShares(), 1L, "system", Instant.parse("2026-08-22T00:00:00Z"),
                Duration.ofMinutes(15)).recycled());

        List<Map<String, Object>> histories = db.query("crm_customer_owner_history_common", Map.of(
                "crm_coh_event", "auto_recycled"));
        assertNotNull(firstOperation);
        assertEquals(2, histories.size());
        assertEquals(2, histories.stream().map(row -> row.get("crm_coh_operation_key")).distinct().count());
        assertNull(item.get("crm_cpi_recycle_token"));
    }

    @Test
    void freshRecycleLeaseIsNotStolenByAnotherScheduler() {
        FakeDb db = baseline();
        db.getById("crm_customer_pool_common", "pool-1").put("crm_cp_auto_recycle", true);
        db.put("crm_customer_pool_item_common", "item-1", map(
                "crm_cpi_account_key", "customer-1", "crm_cpi_account_id", "customer-1",
                "crm_cpi_pool_id", "pool-1", "crm_cpi_status", "recycling",
                "crm_cpi_recycle_token", "recycle-op-1", "updated_at", "2026-08-14T00:00:00Z"));

        CustomerPoolCommandHandler.RecycleResult result = CustomerPoolCommandHandler.recycleDetailed(
                db, new FakeShares(), 1L, "system", Instant.parse("2026-08-14T00:05:00Z"),
                Duration.ofMinutes(15));

        assertEquals(0, result.recycled());
        assertEquals(1, result.activeLeases());
        assertEquals("recycling", db.getById("crm_customer_pool_item_common", "item-1").get("crm_cpi_status"));
    }

    @Test
    void staleWorkerCannotCommitAfterItsLeaseTokenWasFenced() {
        FakeDb db = baseline();
        db.put("crm_customer_pool_item_common", "item-1", map(
                "crm_cpi_account_key", "customer-1", "crm_cpi_account_id", "customer-1",
                "crm_cpi_pool_id", "pool-1", "crm_cpi_status", "recycling",
                "crm_cpi_previous_owner", "member-a",
                "crm_cpi_recycle_token", "operation-1:old-lease",
                "updated_at", "2026-08-01T00:00:00Z"));
        Map<String, Object> staleSnapshot = new HashMap<>(
                db.getById("crm_customer_pool_item_common", "item-1"));
        db.getById("crm_customer_pool_item_common", "item-1")
                .put("crm_cpi_recycle_token", "operation-1:new-lease");
        FakeShares shares = new FakeShares();

        boolean completed = CustomerPoolCommandHandler.completeRecycle(
                db, shares, 1L, db.getById("crm_customer_pool_common", "pool-1"), staleSnapshot,
                "system", 5, Instant.parse("2026-08-14T00:00:00Z"));

        assertFalse(completed);
        assertEquals("owned", db.getById("crm_account_common", "customer-1").get("crm_acc_pool_state"));
        assertTrue(db.query("crm_customer_owner_history_common", Map.of()).isEmpty());
        assertTrue(shares.calls.isEmpty());
        assertEquals("operation-1:new-lease",
                db.getById("crm_customer_pool_item_common", "item-1").get("crm_cpi_recycle_token"));
    }

    private Map<String, Object> execute(FakeDb db, String command, String recordId, String actor,
                                        Map<String, Object> payload) {
        return execute(db, new FakeShares(), command, recordId, actor, payload);
    }

    private Map<String, Object> execute(FakeDb db, FakeShares shares, String command,
                                        String recordId, String actor, Map<String, Object> payload) {
        CommandContext context = new CommandContext(1L, "com.auraboot.crm", "crm", command,
                command.contains("pool_customer") ? "crm_customer_pool_item_common" : "crm_account_common",
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
        db.put("crm_customer_pool_common", "pool-1", map(
                "crm_cp_status", "enabled", "crm_cp_member_user_ids", "member-a,rep-b",
                "crm_cp_admin_user_ids", "manager", "crm_cp_daily_pick_limit", 20,
                "crm_cp_new_cooldown_days", 0, "crm_cp_previous_owner_cooldown_days", 0,
                "crm_cp_auto_recycle", false, "crm_cp_recycle_after_days", 30,
                "crm_cp_recycle_basis", "claimed_at"));
        db.put("crm_account_common", "customer-1", customer("customer-1", "member-a"));
        return db;
    }

    private static FakeDb configuredRuleBaseline() {
        FakeDb db = baseline();
        Map<String, Object> pool = db.getById("crm_customer_pool_common", "pool-1");
        pool.put("crm_cp_auto_recycle", true);
        pool.put("crm_cp_recycle_after_days", 365);
        Map<String, Object> customer = db.getById("crm_account_common", "customer-1");
        customer.put("crm_acc_last_pool_id", "pool-1");
        customer.put("crm_acc_claimed_at", "2026-08-01T00:00:00Z");
        customer.put("crm_acc_last_activity_at", "2026-08-13T00:00:00Z");
        db.put("crm_customer_pool_item_common", "item-1", map(
                "crm_cpi_account_key", "customer-1", "crm_cpi_account_id", "customer-1",
                "crm_cpi_pool_id", "pool-1", "crm_cpi_status", "claimed",
                "crm_cpi_entered_at", "2026-07-25T00:00:00Z",
                "crm_cpi_claimed_at", "2026-08-01T00:00:00Z",
                "crm_cpi_claimed_by", "member-a"));
        db.put("crm_customer_pool_recycle_rule_common", "rule-1", map(
                "crm_cprr_pool_id", "pool-1", "crm_cprr_status", "active",
                "crm_cprr_time_source", "claimed_at", "crm_cprr_operator", "older_than_days",
                "crm_cprr_days", 5, "crm_cprr_sort_order", 10));
        db.put("crm_customer_pool_recycle_rule_common", "rule-2", map(
                "crm_cprr_pool_id", "pool-1", "crm_cprr_status", "active",
                "crm_cprr_time_source", "last_activity_at", "crm_cprr_operator", "older_than_days",
                "crm_cprr_days", 5, "crm_cprr_sort_order", 20));
        return db;
    }

    private static FakeDb availableItem(FakeDb db, String itemId, String customerId, String previousOwner, Instant entered) {
        db.put("crm_customer_pool_item_common", itemId, map(
                "crm_cpi_account_key", customerId, "crm_cpi_account_id", customerId, "crm_cpi_pool_id", "pool-1",
                "crm_cpi_status", "available", "crm_cpi_previous_owner", previousOwner,
                "crm_cpi_entered_at", entered.toString()));
        return db;
    }

    private static Map<String, Object> customer(String id, String owner) {
        return map("crm_acc_code", id.toUpperCase(), "crm_acc_name", "Acme " + id,
                "crm_acc_status", "active", "crm_acc_pool_state", "owned",
                "crm_acc_owner", owner, "crm_acc_health_score", 80);
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
                case "crm_customer_pool_item_common" -> "crm_cpi_account_key";
                case "crm_customer_owner_history_common" -> "crm_coh_operation_key";
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
