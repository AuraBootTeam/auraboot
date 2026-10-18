package com.auraboot.framework.notification.sourcebinding;

import com.auraboot.framework.plugin.extension.BackgroundDataAccessor;
import com.auraboot.framework.plugin.extension.DataPageQuery;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Hermetic boundary checks only; these do not claim that a production tick or database write occurred. */
class ProcurementReminderTickContractTest {
    private final List<String> forwarded = new ArrayList<>();
    private final BackgroundDataAccessor delegate = (BackgroundDataAccessor) Proxy.newProxyInstance(
            BackgroundDataAccessor.class.getClassLoader(), new Class<?>[]{BackgroundDataAccessor.class}, (proxy, method, args) -> {
                forwarded.add(method.getName());
                if (method.getName().equals("executeInTransaction")) ((Runnable) args[0]).run();
                return null;
            });
    private final ProcurementReminderTickNativeIT.TenantBoundBackground frame =
            new ProcurementReminderTickNativeIT.TenantBoundBackground(123, "supplier-owned", "quote-owned", delegate);

    @Test
    void exactBoundedSupplierAndOwnedQuoteReadsForwardWithoutReplacingTheHost() {
        frame.proxy().queryPage(123, "pr_supplier_common", Map.of("pr_supplier_status", "enabled"), null, 100);
        frame.proxy().queryPage(123, "s2p_bid", query("supplier-owned"));
        assertThat(forwarded).containsExactly("queryPage", "queryPage");
        assertThat(frame.actual).isSameAs(delegate);
    }

    @Test
    void foreignTenantAndWrongSupplierAreRejectedBeforeDelegation() {
        assertThatThrownBy(() -> frame.proxy().queryPage(124, "pr_supplier_common", Map.of("pr_supplier_status", "enabled"), null, 100))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> frame.proxy().queryPage(123, "s2p_bid", query("foreign-supplier"))).isInstanceOf(SecurityException.class);
        assertThat(forwarded).isEmpty();
    }

    @Test
    void arbitraryWritesQueriesAndBroaderPaginationAreRejected() {
        assertThatThrownBy(() -> frame.proxy().update(123, "s2p_bid", "quote-owned", Map.of("s2p_bid_status", "submitted")))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> frame.proxy().create(123, "qo_procurement_reminder_common", reminder())).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> frame.proxy().query(123, "pr_supplier_common", Map.of())).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> frame.proxy().queryPage(123, "pr_supplier_common", Map.of("pr_supplier_status", "enabled"), null, 1000))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> frame.proxy().queryPage(123, "s2p_bid", new DataPageQuery(Map.of(), Map.of(), List.of(), 1, 1000)))
                .isInstanceOf(SecurityException.class);
        assertThat(forwarded).isEmpty();
    }

    @Test
    void onlyTheExactSameSlotReminderCanReachRealIdempotentCreate() {
        frame.proxy().tryCreate(123, "qo_procurement_reminder_common", reminder());
        assertThat(forwarded).containsExactly("tryCreate");
        for (String field : List.of("qo_pr_reminder_key", "qo_pr_reminder_supplier_pid", "qo_pr_reminder_kind",
                "qo_pr_reminder_slot_at", "qo_pr_reminder_state", "qo_pr_reminder_revision", "qo_pr_reminder_targets")) {
            Map<String, Object> bad = new LinkedHashMap<>(reminder());
            bad.put(field, field.equals("qo_pr_reminder_targets") ? List.of("foreign-quote") : "forged");
            assertThatThrownBy(() -> frame.proxy().tryCreate(123, "qo_procurement_reminder_common", bad)).isInstanceOf(SecurityException.class);
        }
        Map<String, Object> extra = new LinkedHashMap<>(reminder());
        extra.put("tenant_id", 124L);
        assertThatThrownBy(() -> frame.proxy().tryCreate(123, "qo_procurement_reminder_common", extra)).isInstanceOf(SecurityException.class);
        assertThat(forwarded).containsExactly("tryCreate");
    }

    @Test
    void actualHostTransactionRunsWithTheSameRestrictedAccessorForNestedWork() {
        frame.proxy().executeInTransaction(() -> frame.proxy().tryCreate(123, "qo_procurement_reminder_common", reminder()));
        assertThat(forwarded).containsExactly("executeInTransaction", "tryCreate");
        assertThatThrownBy(() -> frame.proxy().executeInTransaction(() -> frame.proxy().delete(124, "s2p_bid", "foreign")))
                .isInstanceOf(SecurityException.class);
        assertThat(forwarded).containsExactly("executeInTransaction", "tryCreate", "executeInTransaction");
    }

    @Test
    void allExistingFacetSourceRemainsByteIdenticalAfterRemovingOnlyTheAddedFactory() throws Exception {
        Path source = Path.of("src/test/java/com/auraboot/framework/notification/sourcebinding/ProductQuoteReminderNativeFacet.java");
        if (!Files.exists(source)) source = Path.of("platform").resolve(source);
        String current = Files.readString(source);
        String start = "    // BEGIN additive controlled-tick setup. Existing facet methods remain unchanged.\n";
        String end = "    // END additive controlled-tick setup.\n\n";
        int first = current.indexOf(start);
        int last = current.indexOf(end, first);
        assertThat(first).isNotNegative();
        assertThat(current.indexOf(start, first + 1)).isEqualTo(-1);
        assertThat(last).isGreaterThan(first);
        String original = current.substring(0, first) + current.substring(last + end.length());
        assertThat(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(original.getBytes(java.nio.charset.StandardCharsets.UTF_8)))).isEqualTo("4958d64035d321914b2357caece3fcc47b6adc5941a156cd45b9b7b159ee7e28");
        assertThat(original).contains("\"nativeTickExecuted\", false");
    }

    @Test
    void clockAndPhysicalIdentifiersCannotExpandTheNativeBoundary() {
        var clock = new ProcurementReminderTickNativeIT.ControlledClock();
        assertThat(clock.instant()).isEqualTo(ProcurementReminderTickNativeIT.SLOT);
        assertThatThrownBy(() -> clock.withZone(java.time.ZoneId.of("Asia/Shanghai"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProcurementReminderTickNativeIT.identifier("mt_safe;delete from ab_user"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ProcurementReminderTickNativeIT.BUSINESS).hasSize(19).doesNotHaveDuplicates();
    }

    private static DataPageQuery query(String supplier) {
        return new DataPageQuery(Map.of("s2p_bid_supplier_id", supplier, "s2p_bid_collaboration_managed", true), Map.of(),
                List.of(new DataPageQuery.Sort("pid", false)), 1, DataPageQuery.MAX_SIZE);
    }
    private static Map<String, Object> reminder() {
        return Map.of("qo_pr_reminder_key", ProcurementReminderTickNativeIT.TenantBoundBackground.reminderKey("supplier-owned"),
                "qo_pr_reminder_supplier_pid", "supplier-owned", "qo_pr_reminder_kind", "quote",
                "qo_pr_reminder_slot_at", ProcurementReminderTickNativeIT.SLOT.toString(), "qo_pr_reminder_state", "pending",
                "qo_pr_reminder_revision", 0, "qo_pr_reminder_targets", List.of("quote-owned"));
    }
}
