package com.auraboot.plugins.crm.handler;

import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.DataAccessor;
import org.pf4j.Extension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Merges one owned CRM account into another while preserving every open-core child record. */
@Extension
public class MergeAccountHandler implements CommandHandlerExtension {

    public static final String COMMAND = "crm:merge_account";
    private static final String ACCOUNT = "crm_account_common";
    private static final String RELATION = "crm_account_relation_common";
    private static final List<AccountReference> REFERENCES = List.of(
            new AccountReference("crm_contact_common", "crm_ct_account_id"),
            new AccountReference("crm_opportunity_common", "crm_opp_account_id"),
            new AccountReference("crm_complaint", "crm_cmp_account_id"),
            new AccountReference("crm_customer_request_common", "crm_cr_account_id"),
            new AccountReference("crm_quote_summary_common", "crm_qs_account_id"),
            new AccountReference("crm_approval_case_common", "crm_apc_account_id"),
            new AccountReference("crm_clarification_common", "crm_cl_account_id"),
            new AccountReference("crm_review_common", "crm_rv_account_id"),
            new AccountReference("crm_risk_common", "crm_rk_account_id"),
            new AccountReference("crm_subscription", "crm_sub_account_id"),
            new AccountReference("crm_customer_owner_history_common", "crm_coh_customer_id"),
            new AccountReference("crm_lead_common", "crm_lead_converted_account_id"),
            new AccountReference("crm_omni_session", "crm_oss_customer"));

    @Override
    public String getCommandType() {
        return COMMAND;
    }

    @Override
    public Object execute(CommandContext context) {
        DataAccessor db = context.dataAccessor();
        if (db == null) throw new IllegalStateException("Account merge DataAccessor unavailable");
        String sourceId = required(context.recordId(), "Source account id is required");
        Map<String, Object> payload = context.payload() == null ? Map.of() : context.payload();
        String targetId = required(payload.get("targetAccountId"), "Target account is required");
        if (sourceId.equals(targetId)) {
            throw new IllegalArgumentException("客户不能合并到自身 / An account cannot be merged into itself");
        }

        Map<String, Object> source = requireAccount(db, sourceId, "Source account");
        Map<String, Object> target = requireAccount(db, targetId, "Target account");
        requireOwned(db, sourceId, "Source account");
        requireOwned(db, targetId, "Target account");

        Map<String, Integer> movedByModel = new HashMap<>();
        mergeBlankTargetProfile(db, targetId, source, target);
        boolean targetHasPrimary = !safeQuery(db, "crm_contact_common",
                Map.of("crm_ct_account_id", targetId, "crm_ct_is_primary", true)).isEmpty();
        for (AccountReference reference : REFERENCES) {
            List<Map<String, Object>> records = safeQuery(
                    db, reference.model(), Map.of(reference.field(), sourceId));
            for (Map<String, Object> record : records) {
                String recordId = required(record.get("pid"),
                        "Referenced record id is required for " + reference.model());
                Map<String, Object> update = new HashMap<>();
                update.put(reference.field(), targetId);
                if (targetHasPrimary && "crm_contact_common".equals(reference.model())
                        && truthy(record.get("crm_ct_is_primary"))) {
                    update.put("crm_ct_is_primary", false);
                }
                db.update(reference.model(), recordId, update);
            }
            if (!records.isEmpty()) movedByModel.put(reference.model(), records.size());
        }
        moveDirectActivities(db, sourceId, targetId, movedByModel);
        mergeActivityEdges(db, sourceId, targetId, movedByModel);
        mergeAccountRelations(db, sourceId, targetId, movedByModel);
        db.delete(ACCOUNT, sourceId);

        return Map.of(
                "success", true,
                "sourceAccountId", sourceId,
                "targetAccountId", targetId,
                "movedRecordCount", movedByModel.values().stream().mapToInt(Integer::intValue).sum(),
                "movedByModel", movedByModel);
    }

    private static void requireOwned(DataAccessor db, String accountId, String label) {
        if (!safeQuery(db, "crm_customer_pool_item_common", Map.of("crm_cpi_account_id", accountId)).isEmpty()) {
            throw new IllegalArgumentException(label
                    + " is in a customer pool; claim or assign it before merging");
        }
    }

    private static void mergeBlankTargetProfile(DataAccessor db, String targetId,
            Map<String, Object> source, Map<String, Object> target) {
        Map<String, Object> update = new HashMap<>();
        for (String field : List.of("crm_acc_industry", "crm_acc_website", "crm_acc_phone",
                "crm_acc_address", "crm_acc_rating", "crm_acc_remark")) {
            if (blank(target.get(field)) && !blank(source.get(field))) update.put(field, source.get(field));
        }
        if (!update.isEmpty()) db.update(ACCOUNT, targetId, update);
    }

    private static void moveDirectActivities(DataAccessor db, String sourceId, String targetId,
            Map<String, Integer> movedByModel) {
        List<Map<String, Object>> records = safeQuery(db, "crm_activity_common",
                Map.of("crm_act_related_model", ACCOUNT, "crm_act_related_id", sourceId));
        for (Map<String, Object> record : records) {
            db.update("crm_activity_common", required(record.get("pid"), "Activity id is required"),
                    Map.of("crm_act_related_id", targetId));
        }
        if (!records.isEmpty()) movedByModel.put("crm_activity_common", records.size());
    }

    private static void mergeActivityEdges(DataAccessor db, String sourceId, String targetId,
            Map<String, Integer> movedByModel) {
        List<Map<String, Object>> records = safeQuery(db, "crm_activity_relation_common",
                Map.of("crm_ar_object_type", "account", "crm_ar_object_id", sourceId));
        int moved = 0;
        for (Map<String, Object> record : records) {
            String id = required(record.get("pid"), "Activity relation id is required");
            String activityId = required(record.get("crm_ar_activity_id"), "Activity id is required");
            if (safeQuery(db, "crm_activity_relation_common", Map.of(
                    "crm_ar_activity_id", activityId,
                    "crm_ar_object_type", "account",
                    "crm_ar_object_id", targetId)).isEmpty()) {
                db.update("crm_activity_relation_common", id, Map.of("crm_ar_object_id", targetId));
                moved++;
            } else {
                db.delete("crm_activity_relation_common", id);
            }
        }
        if (moved > 0) movedByModel.put("crm_activity_relation_common", moved);
    }

    private static void mergeAccountRelations(DataAccessor db, String sourceId, String targetId,
            Map<String, Integer> movedByModel) {
        List<Map<String, Object>> records = new ArrayList<>();
        records.addAll(safeQuery(db, RELATION, Map.of("crm_acr_source_account_id", sourceId)));
        records.addAll(safeQuery(db, RELATION, Map.of("crm_acr_target_account_id", sourceId)));
        int moved = 0;
        for (Map<String, Object> record : records) {
            String id = required(record.get("pid"), "Account relationship id is required");
            String newSource = sourceId.equals(text(record.get("crm_acr_source_account_id")))
                    ? targetId : text(record.get("crm_acr_source_account_id"));
            String newTarget = sourceId.equals(text(record.get("crm_acr_target_account_id")))
                    ? targetId : text(record.get("crm_acr_target_account_id"));
            if (newSource.equals(newTarget)) {
                db.delete(RELATION, id);
                continue;
            }
            String type = required(record.get("crm_acr_relation_type"), "Relationship type is required");
            String pairKey = newSource + "|" + newTarget + "|" + type;
            List<Map<String, Object>> duplicate = safeQuery(db, RELATION,
                    Map.of("crm_acr_pair_key", pairKey));
            if (duplicate.stream().anyMatch(item -> !id.equals(text(item.get("pid"))))) {
                db.delete(RELATION, id);
                continue;
            }
            db.update(RELATION, id, Map.of(
                    "crm_acr_source_account_id", newSource,
                    "crm_acr_target_account_id", newTarget,
                    "crm_acr_pair_key", pairKey));
            moved++;
        }
        if (moved > 0) movedByModel.put(RELATION, moved);
    }

    private static Map<String, Object> requireAccount(DataAccessor db, String id, String label) {
        Map<String, Object> account = db.getById(ACCOUNT, id);
        if (account == null) throw new IllegalArgumentException(label + " not found: " + id);
        return account;
    }

    private static List<Map<String, Object>> safeQuery(DataAccessor db, String model,
            Map<String, Object> filters) {
        List<Map<String, Object>> records = db.query(model, filters);
        return records == null ? List.of() : records;
    }

    private static boolean truthy(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(text(value));
    }

    private static boolean blank(Object value) {
        return text(value).isBlank();
    }

    private static String required(Object value, String message) {
        String text = text(value);
        if (text.isBlank()) throw new IllegalArgumentException(message);
        return text;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private record AccountReference(String model, String field) {}
}
