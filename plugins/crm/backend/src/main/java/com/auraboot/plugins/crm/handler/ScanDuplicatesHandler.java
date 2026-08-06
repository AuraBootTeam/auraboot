package com.auraboot.plugins.crm.handler;

import com.auraboot.plugins.crm.engine.DuplicateMatchEngine;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.DataAccessor;
import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Scans for duplicate contacts (CRM gap #14).
 *
 * <p>Wired as the {@code handler} of {@code crm:scan_duplicates} (custom). Scores
 * the target contact against every other contact via the pure
 * {@link DuplicateMatchEngine}, and records the best match (code + score) when it
 * reaches at least the {@code possible} band. Fail-loud (red line §8) on a
 * missing record.
 */
@Extension
public class ScanDuplicatesHandler implements CommandHandlerExtension {

    private static final Logger log = LoggerFactory.getLogger(ScanDuplicatesHandler.class);

    public static final String COMMAND_TYPE = "crm:scan_duplicates";

    private static final String MODEL = "crm_contact_common";
    private static final int MIN_SCORE = 20; // 'possible' threshold

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
            throw new IllegalStateException("DataAccessor unavailable; cannot scan duplicates");
        }
        String recordId = context.recordId();
        if (recordId == null || recordId.isBlank()) {
            throw new IllegalStateException("No contact record id on context");
        }
        Map<String, Object> self = db.getById(MODEL, recordId);
        if (self == null) {
            throw new IllegalStateException("Contact not found: " + recordId);
        }

        List<Map<String, Object>> all = db.query(MODEL, Collections.emptyMap());
        String bestCode = null;
        int bestScore = 0;
        String bestVerdict = "none";
        if (all != null) {
            for (Map<String, Object> other : all) {
                if (other == null || Objects.equals(other.get("pid"), self.get("pid"))
                        || Objects.equals(other.get("id"), self.get("id"))) {
                    continue;
                }
                DuplicateMatchEngine.Result r = DuplicateMatchEngine.score(self, other);
                if (r.score() > bestScore) {
                    bestScore = r.score();
                    bestVerdict = r.verdict();
                    Object code = other.get("crm_ct_code");
                    bestCode = code != null ? code.toString() : str(other.get("crm_ct_name"));
                }
            }
        }

        Map<String, Object> update = new HashMap<>();
        if (bestScore >= MIN_SCORE) {
            update.put("crm_ct_dup_of", bestCode);
            update.put("crm_ct_dup_score", bestScore);
        } else {
            update.put("crm_ct_dup_of", null);
            update.put("crm_ct_dup_score", 0);
        }
        db.update(MODEL, recordId, update);

        log.info("Contact {} dedup scan: best match={} score={} verdict={}",
                recordId, bestCode, bestScore, bestVerdict);

        return Map.of("contactId", recordId, "duplicateOf", bestCode == null ? "" : bestCode,
                "score", bestScore, "verdict", bestVerdict);
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}
