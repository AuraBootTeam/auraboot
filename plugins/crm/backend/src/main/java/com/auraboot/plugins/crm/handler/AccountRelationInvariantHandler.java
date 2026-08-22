package com.auraboot.plugins.crm.handler;

import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.DataAccessor;
import org.pf4j.Extension;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;

/**
 * Normalizes and validates customer relationship edges after declarative persistence.
 *
 * <p>The primary create/update command and this extension run in one transaction. A
 * rejected self-link, missing endpoint, invalid validity window or duplicate edge
 * therefore rolls the original write back. The unique pair key is also the final
 * arbiter for concurrent duplicate submissions.</p>
 */
@Extension
public class AccountRelationInvariantHandler implements CommandHandlerExtension {

    static final String CREATE = "crm:create_account_relation";
    static final String UPDATE = "crm:update_account_relation";
    private static final String MODEL = "crm_account_relation_common";
    private static final String ACCOUNT_MODEL = "crm_account_common";
    private static final Set<String> TYPES = Set.of(CREATE, UPDATE);

    @Override
    public String getCommandType() {
        return CREATE;
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
    public boolean chainsAfterPrimary() {
        return true;
    }

    @Override
    public boolean supportsDryRun() {
        return true;
    }

    @Override
    public Object execute(CommandContext context) {
        DataAccessor db = context.dataAccessor();
        if (db == null) throw new IllegalStateException("Account relationship DataAccessor unavailable");
        String relationId = required(context.recordId(), "Account relationship id is required after persistence");
        Map<String, Object> relation = db.getById(MODEL, relationId);
        if (relation == null) {
            throw new IllegalArgumentException("Account relationship not found after persistence: " + relationId);
        }

        String sourceId = required(relation.get("crm_acr_source_account_id"), "Source account is required");
        String targetId = required(relation.get("crm_acr_target_account_id"), "Related account is required");
        String relationType = required(relation.get("crm_acr_relation_type"), "Relationship type is required");
        if (sourceId.equals(targetId)) {
            throw new IllegalArgumentException(
                    "客户不能与自身建立关系 / An account cannot relate to itself");
        }
        requireAccount(db, sourceId, "Source account");
        requireAccount(db, targetId, "Related account");
        validateDateWindow(relation.get("crm_acr_effective_from"), relation.get("crm_acr_effective_to"));

        String pairKey = sourceId + "|" + targetId + "|" + relationType;
        try {
            db.update(MODEL, relationId, Map.of("crm_acr_pair_key", pairKey));
        } catch (RuntimeException error) {
            if (isDuplicateKey(error)) {
                throw new IllegalArgumentException(
                        "相同客户与关系类型已存在，请编辑原关系 / "
                                + "This account relationship already exists; edit the existing relationship",
                        error);
            }
            throw error;
        }
        return Map.of(
                "relationshipValidated", true,
                "relationshipId", relationId,
                "sourceAccountId", sourceId,
                "targetAccountId", targetId);
    }

    private static void validateDateWindow(Object fromValue, Object toValue) {
        String fromText = string(fromValue);
        String toText = string(toValue);
        if (fromText.isBlank() || toText.isBlank()) return;
        try {
            LocalDate from = LocalDate.parse(fromText.substring(0, Math.min(10, fromText.length())));
            LocalDate to = LocalDate.parse(toText.substring(0, Math.min(10, toText.length())));
            if (to.isBefore(from)) {
                throw new IllegalArgumentException(
                        "失效日期不能早于生效日期 / Effective-to date cannot precede effective-from date");
            }
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException(
                    "客户关系日期格式无效 / Invalid account relationship date", error);
        }
    }

    private static void requireAccount(DataAccessor db, String accountId, String label) {
        if (db.getById(ACCOUNT_MODEL, accountId) == null) {
            throw new IllegalArgumentException(label + " not found: " + accountId);
        }
    }

    private static boolean isDuplicateKey(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = string(current.getMessage()).toLowerCase();
            if (message.contains("duplicate key")
                    && (message.contains("crm_acr_pair_key") || message.contains("pair_key"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String required(Object value, String message) {
        String text = string(value);
        if (text.isBlank()) throw new IllegalArgumentException(message);
        return text;
    }

    private static String string(Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
