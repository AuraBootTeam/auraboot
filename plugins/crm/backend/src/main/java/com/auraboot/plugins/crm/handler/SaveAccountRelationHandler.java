package com.auraboot.plugins.crm.handler;

import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.DataAccessor;
import org.pf4j.Extension;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Idempotently saves one directional customer relationship by its business pair key. */
@Extension
public class SaveAccountRelationHandler implements CommandHandlerExtension {

    static final String COMMAND = "crm:save_account_relation";
    private static final String MODEL = "crm_account_relation_common";
    private static final String ACCOUNT_MODEL = "crm_account_common";

    @Override
    public String getCommandType() {
        return COMMAND;
    }

    @Override
    public boolean supportsDryRun() {
        return true;
    }

    @Override
    public Object execute(CommandContext context) {
        DataAccessor db = context.dataAccessor();
        if (db == null) throw new IllegalStateException("Account relationship DataAccessor unavailable");
        Map<String, Object> payload = context.payload() == null ? Map.of() : context.payload();
        String sourceId = required(payload.get("crm_acr_source_account_id"), "Source account is required");
        String targetId = required(payload.get("crm_acr_target_account_id"), "Related account is required");
        String relationType = required(payload.get("crm_acr_relation_type"), "Relationship type is required");
        if (sourceId.equals(targetId)) {
            throw new IllegalArgumentException("客户不能与自身建立关系 / An account cannot relate to itself");
        }
        requireAccount(db, sourceId, "Source account");
        requireAccount(db, targetId, "Related account");
        validateDateWindow(payload.get("crm_acr_effective_from"), payload.get("crm_acr_effective_to"));

        String pairKey = sourceId + "|" + targetId + "|" + relationType;
        Map<String, Object> values = writableValues(payload);
        values.put("crm_acr_pair_key", pairKey);
        List<Map<String, Object>> existing = db.query(MODEL, Map.of("crm_acr_pair_key", pairKey));
        if (!existing.isEmpty()) {
            String relationId = required(existing.get(0).get("pid"), "Existing relationship id is required");
            db.update(MODEL, relationId, values);
            return result(relationId, "updated", sourceId, targetId);
        }

        values.putIfAbsent("crm_acr_strength", "standard");
        values.putIfAbsent("crm_acr_status", "active");
        values.put("crm_acr_owner", required(context.currentUserPid(), "Authenticated user PID is required"));
        return db.tryCreate(MODEL, values)
                .map(created -> result(required(created.get("pid"), "Created relationship id is required"),
                        "created", sourceId, targetId))
                .orElseGet(() -> {
                    List<Map<String, Object>> concurrent = db.query(MODEL, Map.of("crm_acr_pair_key", pairKey));
                    if (concurrent.size() != 1) {
                        throw new IllegalStateException("Concurrent relationship save did not resolve to one fact");
                    }
                    String relationId = required(concurrent.get(0).get("pid"), "Concurrent relationship id is required");
                    db.update(MODEL, relationId, values);
                    return result(relationId, "updated", sourceId, targetId);
                });
    }

    private static Map<String, Object> writableValues(Map<String, Object> payload) {
        Map<String, Object> values = new HashMap<>();
        for (String field : List.of("crm_acr_source_account_id", "crm_acr_target_account_id",
                "crm_acr_relation_type", "crm_acr_strength", "crm_acr_status",
                "crm_acr_effective_from", "crm_acr_effective_to", "crm_acr_notes")) {
            if (payload.containsKey(field)) values.put(field, payload.get(field));
        }
        return values;
    }

    private static Map<String, Object> result(String id, String operation, String source, String target) {
        return Map.of("relationshipId", id, "operation", operation,
                "sourceAccountId", source, "targetAccountId", target);
    }

    private static void requireAccount(DataAccessor db, String accountId, String label) {
        if (db.getById(ACCOUNT_MODEL, accountId) == null) {
            throw new IllegalArgumentException(label + " not found: " + accountId);
        }
    }

    private static void validateDateWindow(Object fromValue, Object toValue) {
        String fromText = text(fromValue);
        String toText = text(toValue);
        if (fromText.isBlank() || toText.isBlank()) return;
        try {
            LocalDate from = LocalDate.parse(fromText.substring(0, Math.min(10, fromText.length())));
            LocalDate to = LocalDate.parse(toText.substring(0, Math.min(10, toText.length())));
            if (to.isBefore(from)) {
                throw new IllegalArgumentException("失效日期不能早于生效日期 / Effective-to date cannot precede effective-from date");
            }
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException("客户关系日期格式无效 / Invalid account relationship date", error);
        }
    }

    private static String required(Object value, String message) {
        String text = text(value);
        if (text.isBlank()) throw new IllegalArgumentException(message);
        return text;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
