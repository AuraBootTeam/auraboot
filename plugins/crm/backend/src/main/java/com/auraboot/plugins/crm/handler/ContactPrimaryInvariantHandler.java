package com.auraboot.plugins.crm.handler;

import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.DataAccessor;
import org.pf4j.Extension;

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
    private static final String MODEL = "crm_contact_common";
    private static final Set<String> TYPES = Set.of(CREATE, UPDATE, SET_PRIMARY, DISABLE, ENABLE);

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

        String status = string(contact.get("crm_ct_status"));
        boolean active = status.isBlank() || "active".equals(status);
        boolean primary = Boolean.TRUE.equals(contact.get("crm_ct_is_primary"));
        if (SET_PRIMARY.equals(context.commandType())) {
            if (!active) {
                throw new IllegalArgumentException("Only active contacts can be primary");
            }
            if (!primary) {
                db.update(MODEL, contactId, Map.of("crm_ct_is_primary", true));
                primary = true;
            }
        }
        if (!active && primary) {
            db.update(MODEL, contactId, Map.of("crm_ct_is_primary", false));
            return Map.of("primaryContactNormalized", true, "primaryContactId", "");
        }
        if (!primary) {
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
                        || !Boolean.TRUE.equals(sibling.get("crm_ct_is_primary"))) {
                    continue;
                }
                db.update(MODEL, siblingId, Map.of("crm_ct_is_primary", false));
                demoted++;
            }
        }
        return Map.of(
                "primaryContactNormalized", true,
                "primaryContactId", contactId,
                "demotedContactCount", demoted);
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
