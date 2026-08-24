package com.auraboot.plugins.crm.handler;

import com.auraboot.framework.plugin.extension.CommandHandlerExtension.CommandContext;
import com.auraboot.framework.plugin.extension.DataAccessor;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpportunityCloseHandlerTest {

    private final OpportunityCloseHandler handler = new OpportunityCloseHandler();

    @Test
    void winsOnlyWhenEveryRelatedQuoteApprovalIsReady() {
        DataAccessor db = mock(DataAccessor.class);
        when(db.getById("crm_opportunity_common", "opp-1")).thenReturn(readyOpportunity());
        when(db.query("crm_quote_summary_common", Map.of("crm_qs_opportunity_id", "opp-1")))
                .thenReturn(List.of(
                        Map.of("crm_qs_approval_status", "none"),
                        Map.of("crm_qs_approval_status", "approved")));
        when(db.update(eq("crm_opportunity_common"), eq("opp-1"), argThat(values ->
                "closed_won".equals(values.get("crm_opp_stage"))
                        && Integer.valueOf(100).equals(values.get("crm_opp_probability")))))
                .thenReturn(Map.of("pid", "opp-1", "crm_opp_stage", "closed_won"));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) handler.execute(
                context(db, OpportunityCloseHandler.WIN_COMMAND, Map.of(), false));

        assertEquals("closed_won", result.get("stage"));
        verify(db).update(eq("crm_opportunity_common"), eq("opp-1"), argThat(values ->
                "closed_won".equals(values.get("crm_opp_stage"))));
    }

    @Test
    void pendingOrRejectedQuoteApprovalBlocksWinWithoutMutation() {
        for (String approval : List.of("pending", "rejected")) {
            DataAccessor db = mock(DataAccessor.class);
            when(db.getById("crm_opportunity_common", "opp-1")).thenReturn(readyOpportunity());
            when(db.query("crm_quote_summary_common", Map.of("crm_qs_opportunity_id", "opp-1")))
                    .thenReturn(List.of(Map.of("crm_qs_approval_status", approval)));

            IllegalStateException conflict = assertThrows(IllegalStateException.class,
                    () -> handler.execute(context(
                            db, OpportunityCloseHandler.WIN_COMMAND, Map.of(), false)));
            assertTrue(conflict.getMessage().contains("报价审批冲突"));
            verify(db, never()).update(eq("crm_opportunity_common"), eq("opp-1"),
                    org.mockito.ArgumentMatchers.anyMap());
        }
    }

    @Test
    void lossRequiresReasonAndPersistsReviewFacts() {
        DataAccessor db = mock(DataAccessor.class);
        when(db.getById("crm_opportunity_common", "opp-1")).thenReturn(readyOpportunity());
        when(db.update(eq("crm_opportunity_common"), eq("opp-1"), argThat(values ->
                "closed_lost".equals(values.get("crm_opp_stage"))
                        && "competitor".equals(values.get("crm_opp_lost_reason_code"))
                        && "Cordys".equals(values.get("crm_opp_competitor")))))
                .thenReturn(Map.of("pid", "opp-1", "crm_opp_stage", "closed_lost"));

        assertThrows(IllegalArgumentException.class, () -> handler.execute(
                context(db, OpportunityCloseHandler.LOSE_COMMAND, Map.of(), false)));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) handler.execute(context(
                db,
                OpportunityCloseHandler.LOSE_COMMAND,
                Map.of(
                        "crm_opp_lost_reason_code", "competitor",
                        "crm_opp_competitor", "Cordys",
                        "crm_opp_lost_reason", "Customer selected competitor"),
                false));
        assertEquals("closed_lost", result.get("stage"));
    }

    @Test
    void terminalOpportunityAndUnknownQuoteStateFailClosed() {
        DataAccessor terminalDb = mock(DataAccessor.class);
        when(terminalDb.getById("crm_opportunity_common", "opp-1"))
                .thenReturn(Map.of("crm_opp_stage", "closed_won"));
        assertThrows(IllegalStateException.class, () -> handler.execute(
                context(terminalDb, OpportunityCloseHandler.WIN_COMMAND, Map.of(), false)));

        DataAccessor unavailableDb = mock(DataAccessor.class);
        when(unavailableDb.getById("crm_opportunity_common", "opp-1")).thenReturn(readyOpportunity());
        when(unavailableDb.query("crm_quote_summary_common", Map.of("crm_qs_opportunity_id", "opp-1")))
                .thenReturn(null);
        assertThrows(IllegalStateException.class, () -> handler.execute(
                context(unavailableDb, OpportunityCloseHandler.WIN_COMMAND, Map.of(), false)));
    }

    private static Map<String, Object> readyOpportunity() {
        return Map.of(
                "pid", "opp-1",
                "crm_opp_stage", "negotiation",
                "crm_opp_expected_amount", 120000,
                "crm_opp_expected_close_date", "2026-12-31");
    }

    private static CommandContext context(
            DataAccessor db, String commandCode, Map<String, Object> payload, boolean dryRun) {
        Map<String, Object> settings = new HashMap<>();
        settings.put("__dataAccessor", db);
        settings.put("__commandCode", commandCode);
        return CommandContext.builder()
                .tenantId(101L)
                .pluginId("com.auraboot.crm")
                .namespace("crm")
                .commandType(OpportunityCloseHandler.COMMAND_TYPE)
                .modelCode("crm_opportunity_common")
                .recordId("opp-1")
                .payload(payload)
                .settings(settings)
                .dryRun(dryRun)
                .build();
    }
}
