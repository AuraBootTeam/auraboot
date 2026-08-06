package com.auraboot.plugins.crm.handler;

import com.auraboot.plugins.crm.engine.ConsentValidationEngine;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.DataAccessor;
import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Validates and grants a contact consent (CRM gap #12).
 *
 * <p>Wired as the {@code handler} of {@code crm:grant_consent} (state transition
 * pending -&gt; opt_in). Reads the consent record, runs the pure
 * {@link ConsentValidationEngine}, and on success stamps granted_at. Fail-loud
 * (red line §8): granting without a legal basis / source aborts rather than
 * silently opting the contact in.
 */
@Extension
public class GrantConsentHandler implements CommandHandlerExtension {

    private static final Logger log = LoggerFactory.getLogger(GrantConsentHandler.class);

    public static final String COMMAND_TYPE = "crm:grant_consent";

    private static final String MODEL = "crm_consent";

    @Override
    public String getCommandType() {
        return COMMAND_TYPE;
    }

    @Override
    public Set<String> getSupportedCommandTypes() {
        return Set.of(COMMAND_TYPE);
    }

    @Override
    public boolean supports(String commandType) {
        return COMMAND_TYPE.equals(commandType);
    }

    @Override
    public Object execute(CommandContext context) throws Exception {
        DataAccessor db = context.dataAccessor();
        if (db == null) {
            throw new IllegalStateException("DataAccessor unavailable; cannot grant consent");
        }
        String recordId = context.recordId();
        if (recordId == null || recordId.isBlank()) {
            throw new IllegalStateException("No consent record id on context");
        }
        Map<String, Object> rec = db.getById(MODEL, recordId);
        if (rec == null) {
            throw new IllegalStateException("Consent record not found: " + recordId);
        }

        // The state transition has already set status -> opt_in; validate that grant is legitimate.
        ConsentValidationEngine.Consent consent = new ConsentValidationEngine.Consent(
                str(rec.get("crm_cns_channel")),
                "opt_in",
                str(rec.get("crm_cns_source")),
                str(rec.get("crm_cns_legal_basis")));

        ConsentValidationEngine.Result result = ConsentValidationEngine.validate(consent);
        if (!result.valid()) {
            throw new IllegalStateException(
                    "Consent " + recordId + " cannot be granted (red line §8 — no silent opt-in): "
                            + String.join("; ", result.violations()));
        }

        Map<String, Object> update = new HashMap<>();
        update.put("crm_cns_granted_at", Instant.now().toString());
        db.update(MODEL, recordId, update);

        log.info("Consent {} granted: channel={} source={}", recordId,
                consent.channel(), consent.source());

        return Map.of("consentId", recordId, "status", "opt_in");
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}
