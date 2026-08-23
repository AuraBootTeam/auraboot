package com.auraboot.plugins.crm.handler;

import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.DataAccessor;
import org.pf4j.Extension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Enforces the contact lifecycle invariant after declarative persistence.
 *
 * <p>Only active contacts may be primary and one account may expose at most one
 * primary contact. The command pipeline invokes this extension in the same
 * transaction as create/update/state-transition persistence, so a failure rolls
 * the whole user action back instead of leaving a half-updated contact graph.</p>
 */
@Extension
public class ContactPrimaryInvariantHandler implements CommandHandlerExtension {

    static final String CREATE = "crm:create_contact";
    static final String UPDATE = "crm:update_contact";
    static final String SET_PRIMARY = "crm:set_primary_contact";
    static final String DISABLE = "crm:disable_contact";
    static final String ENABLE = "crm:enable_contact";
    static final String DELETE = "crm:delete_contact";
    private static final String MODEL = "crm_contact_common";
    private static final String OPPORTUNITY_CONTACT_MODEL = "crm_opportunity_contact_common";
    private static final String PRIMARY = "crm_ct_is_primary";
    private static final String PRIMARY_ACCOUNT_KEY = "crm_ct_primary_account_key";
    private static final Set<String> TYPES = Set.of(CREATE, UPDATE, SET_PRIMARY, DISABLE, ENABLE, DELETE);

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
        if (db == null) {
            throw new IllegalStateException("Contact invariant DataAccessor unavailable");
        }
        String contactId = required(context.recordId(), "Contact id is required after persistence");
        Map<String, Object> contact = db.getById(MODEL, contactId);
        if (contact == null) {
            throw new IllegalArgumentException("Contact not found after persistence: " + contactId);
        }

        if (DELETE.equals(context.commandType())) {
            deleteContact(db, contactId, contact);
            return Map.of("deletedContactId", contactId);
        }

        validateAccountAndContactChannels(db, contactId, contact);

        String status = string(contact.get("crm_ct_status"));
        boolean active = status.isBlank() || "active".equals(status);
        boolean primary = Boolean.TRUE.equals(contact.get(PRIMARY));
        if (SET_PRIMARY.equals(context.commandType()) && !active) {
            throw new IllegalArgumentException("Only active contacts can be primary");
        }
        if (SET_PRIMARY.equals(context.commandType())) primary = true;
        if (!active && primary) {
            db.update(MODEL, contactId, primaryState(false, null));
            return Map.of("primaryContactNormalized", true, "primaryContactId", "");
        }
        if (!primary) {
            if (!string(contact.get(PRIMARY_ACCOUNT_KEY)).isBlank()) {
                db.update(MODEL, contactId, primaryState(false, null));
                return Map.of("primaryContactNormalized", true, "primaryContactId", "");
            }
            return Map.of("primaryContactNormalized", false);
        }

        String accountId = required(contact.get("crm_ct_account_id"),
                "Primary contact requires an account id");
        List<Map<String, Object>> siblings = db.query(MODEL, Map.of("crm_ct_account_id", accountId));
        int demoted = 0;
        if (siblings != null) {
            for (Map<String, Object> sibling : siblings) {
                String siblingId = string(sibling.get("pid"));
                if (siblingId.isBlank() || contactId.equals(siblingId)
                        || !Boolean.TRUE.equals(sibling.get(PRIMARY))) {
                    continue;
                }
                db.update(MODEL, siblingId, primaryState(false, null));
                demoted++;
            }
        }
        // The unique (tenant_id, primary_account_key) index is the final
        // concurrency arbiter. Competing promotions for the same account cannot
        // both commit; the losing command rolls back its sibling demotions too.
        try {
            db.update(MODEL, contactId, primaryState(true, accountId));
        } catch (RuntimeException error) {
            if (isPrimaryKeyConflict(error)) {
                throw new IllegalArgumentException(
                        "主联系人已被其他请求更新，请刷新后重试 / "
                                + "Primary contact changed concurrently; refresh and retry",
                        error);
            }
            throw error;
        }
        return Map.of(
                "primaryContactNormalized", true,
                "primaryContactId", contactId,
                "demotedContactCount", demoted);
    }

    private static void deleteContact(DataAccessor db, String contactId, Map<String, Object> contact) {
        if (Boolean.TRUE.equals(contact.get(PRIMARY))) {
            throw new IllegalArgumentException(
                    "主联系人不能直接删除，请先指定其他主联系人 / "
                            + "A primary contact cannot be deleted; assign another primary contact first");
        }
        List<Map<String, Object>> opportunityLinks = db.query(
                OPPORTUNITY_CONTACT_MODEL, Map.of("crm_oc_contact_id", contactId));
        if (opportunityLinks != null && !opportunityLinks.isEmpty()) {
            throw new IllegalArgumentException(
                    "联系人仍关联商机，请先移除商机关联 / "
                            + "The contact is linked to opportunities; remove those associations first");
        }
        db.delete(MODEL, contactId);
    }

    private static void validateAccountAndContactChannels(
            DataAccessor db, String contactId, Map<String, Object> contact) {
        String accountId = required(contact.get("crm_ct_account_id"),
                "联系人必须关联客户 / A contact must belong to an account");
        String email = normalized(contact.get("crm_ct_email"));
        String phone = normalized(contact.get("crm_ct_phone"));
        String mobile = normalized(contact.get("crm_ct_mobile"));
        if (email.isBlank() && phone.isBlank() && mobile.isBlank()) {
            return;
        }
        List<Map<String, Object>> siblings = db.query(MODEL, Map.of("crm_ct_account_id", accountId));
        if (siblings == null) {
            return;
        }
        for (Map<String, Object> sibling : siblings) {
            if (contactId.equals(string(sibling.get("pid")))) {
                continue;
            }
            if (sameNonBlank(email, sibling.get("crm_ct_email"))
                    || sameNonBlank(phone, sibling.get("crm_ct_phone"))
                    || sameNonBlank(mobile, sibling.get("crm_ct_mobile"))) {
                throw new IllegalArgumentException(
                        "同一客户下已存在相同联系方式 / "
                                + "A contact with the same contact detail already exists for this account");
            }
        }
    }

    private static boolean sameNonBlank(String expected, Object actual) {
        return !expected.isBlank() && expected.equals(normalized(actual));
    }

    private static String normalized(Object value) {
        return string(value).toLowerCase();
    }

    private static Map<String, Object> primaryState(boolean primary, String accountKey) {
        Map<String, Object> values = new HashMap<>();
        values.put(PRIMARY, primary);
        values.put(PRIMARY_ACCOUNT_KEY, accountKey);
        return values;
    }

    private static boolean isPrimaryKeyConflict(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = string(current.getMessage()).toLowerCase();
            if (message.contains("duplicate key")
                    && (message.contains(PRIMARY_ACCOUNT_KEY)
                    || message.contains("primary_account_key"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String required(Object value, String message) {
        String text = string(value);
        if (text.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return text;
    }

    private static String string(Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
