package com.auraboot.plugins.crm.handler;

import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.DataAccessor;
import org.pf4j.Extension;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Owns the final opportunity close mutation and fails closed on quote-approval conflicts.
 *
 * <p>Generic command preconditions still validate stage, amount, close date and loss-reason
 * input before this handler runs. The handler rereads the authoritative opportunity and related
 * quote summaries inside the command transaction so a pending or rejected approval cannot race
 * into a won opportunity.</p>
 */
@Extension
public class OpportunityCloseHandler implements CommandHandlerExtension {

    public static final String COMMAND_TYPE = "crm:close_opportunity";
    static final String OPPORTUNITY_MODEL = "crm_opportunity_common";
    static final String QUOTE_MODEL = "crm_quote_summary_common";
    static final String WIN_COMMAND = "crm:win_opportunity";
    static final String LOSE_COMMAND = "crm:lose_opportunity";
    private static final Set<String> OPEN_STAGES = Set.of(
            "discovery", "qualification", "proposal", "negotiation");

    @Override
    public String getCommandType() {
        return COMMAND_TYPE;
    }

    @Override
    public boolean supportsDryRun() {
        return true;
    }

    @Override
    public Object execute(CommandContext context) {
        DataAccessor db = context.dataAccessor();
        if (db == null) {
            throw new IllegalStateException("数据服务不可用，暂时无法关闭商机");
        }
        String opportunityPid = required(context.recordId(), "缺少待处理的商机");
        String commandCode = required(setting(context, "__commandCode"), "缺少商机关闭命令");
        Map<String, Object> opportunity = db.getById(OPPORTUNITY_MODEL, opportunityPid);
        if (opportunity == null) {
            throw new IllegalArgumentException("未找到商机：" + opportunityPid);
        }
        String stage = text(opportunity.get("crm_opp_stage"));
        if (!OPEN_STAGES.contains(stage)) {
            throw new IllegalStateException("商机已关闭，不能重复变更");
        }

        Map<String, Object> patch = new LinkedHashMap<>();
        if (WIN_COMMAND.equals(commandCode)) {
            requireWinReady(opportunity);
            requireQuoteApprovalsReady(db, opportunityPid);
            patch.put("crm_opp_stage", "closed_won");
            patch.put("crm_opp_probability", 100);
        } else if (LOSE_COMMAND.equals(commandCode)) {
            Map<String, Object> payload = context.payload() == null ? Map.of() : context.payload();
            patch.put("crm_opp_stage", "closed_lost");
            patch.put("crm_opp_probability", 0);
            patch.put("crm_opp_lost_reason_code", required(
                    payload.get("crm_opp_lost_reason_code"), "丢单前必须选择失败原因"));
            copyIfPresent(payload, patch, "crm_opp_lost_reason");
            copyIfPresent(payload, patch, "crm_opp_competitor");
        } else {
            throw new IllegalArgumentException("不支持的商机关闭命令：" + commandCode);
        }

        if (context.dryRun()) {
            return Map.of(
                    "success", true,
                    "dryRun", true,
                    "recordId", opportunityPid,
                    "stage", patch.get("crm_opp_stage"));
        }
        Map<String, Object> updated = db.update(OPPORTUNITY_MODEL, opportunityPid, patch);
        if (updated == null) {
            throw new IllegalStateException("商机关闭失败，未返回更新后的记录");
        }
        return Map.of(
                "success", true,
                "recordId", opportunityPid,
                "stage", patch.get("crm_opp_stage"));
    }

    private static void requireWinReady(Map<String, Object> opportunity) {
        if (!"negotiation".equals(text(opportunity.get("crm_opp_stage")))) {
            throw new IllegalStateException("只有商务谈判阶段的商机才能赢单");
        }
        if (decimal(opportunity.get("crm_opp_expected_amount")).compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("赢单前预计金额必须大于零");
        }
        required(opportunity.get("crm_opp_expected_close_date"),
                "赢单前必须填写预计成交日期");
    }

    private static void requireQuoteApprovalsReady(DataAccessor db, String opportunityPid) {
        List<Map<String, Object>> quotes = db.query(
                QUOTE_MODEL, Map.of("crm_qs_opportunity_id", opportunityPid));
        if (quotes == null) {
            throw new IllegalStateException("无法核验关联报价的审批状态，请稍后重试");
        }
        for (Map<String, Object> quote : quotes) {
            String approval = text(quote.get("crm_qs_approval_status"));
            if (!Set.of("none", "approved").contains(approval)) {
                throw new IllegalStateException(
                        "报价审批冲突：所有关联报价必须已审批通过或无需审批，才能赢单");
            }
        }
    }

    private static void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String field) {
        if (source.containsKey(field) && text(source.get(field)) != null) {
            target.put(field, source.get(field));
        }
    }

    private static Object setting(CommandContext context, String key) {
        return context.settings() == null ? null : context.settings().get(key);
    }

    private static BigDecimal decimal(Object value) {
        try {
            return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
        } catch (NumberFormatException ignored) {
            return BigDecimal.ZERO;
        }
    }

    private static String required(Object value, String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
        String normalized = value.toString().trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }

    private static String text(Object value) {
        if (value == null) return null;
        String normalized = value.toString().trim().toLowerCase();
        return normalized.isEmpty() ? null : normalized;
    }
}
