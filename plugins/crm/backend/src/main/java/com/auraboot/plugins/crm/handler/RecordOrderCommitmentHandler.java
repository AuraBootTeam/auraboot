package com.auraboot.plugins.crm.handler;

import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.DataAccessor;
import org.pf4j.Extension;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Records the one authoritative order commitment on a Quote Summary.
 *
 * <p>The command deliberately creates no order shadow model and copies no QDP content. It stores
 * only references to the exact released QDP and Customer Confirmation, plus the actor/time audit
 * stamps, on the already-authoritative Quote Summary. Every business and evidence fact is reread
 * through the tenant-scoped {@link DataAccessor}; payload values are used only as public record
 * identifiers and must match the command target.</p>
 */
@Extension
public class RecordOrderCommitmentHandler implements CommandHandlerExtension {

    public static final String COMMAND_TYPE = "crm:record_order_commitment";
    static final String QUOTE_MODEL = "crm_quote_summary_common";
    static final String QDP_MODEL = "crm_qdp_revision_common";
    static final String CONFIRMATION_MODEL = "crm_customer_confirmation_common";

    @Override
    public String getCommandType() {
        return COMMAND_TYPE;
    }

    @Override
    public Set<String> getSupportedCommandTypes() {
        return Set.of(COMMAND_TYPE);
    }

    @Override
    public boolean supportsDryRun() {
        return true;
    }

    @Override
    public Object execute(CommandContext context) {
        DataAccessor db = context.dataAccessor();
        if (db == null) {
            throw new IllegalStateException("DataAccessor unavailable; cannot record order commitment");
        }
        if (context.tenantId() == null || context.tenantId() <= 0) {
            throw new IllegalStateException("Authenticated tenant context is required");
        }
        String actor = required(setting(context, "__currentUser"),
                "Authenticated actor context is required");
        Map<String, Object> payload = context.payload() == null ? Map.of() : context.payload();
        String quotePid = requireExactTarget(
                context.recordId(), payload.get("crm_quote_summary_id"), "Quote Summary");
        String qdpPid = required(payload.get("crm_qdp_revision_id"),
                "Released QDP revision pid is required");

        Map<String, Object> quote = requiredRecord(db, QUOTE_MODEL, quotePid, "Quote Summary");
        requireRecordIdentity(quote, quotePid, "Quote Summary");
        Long quoteVersion = positiveLong(quote.get("row_version"));
        Long expectedVersion = context.expectedVersion();
        if (quoteVersion == null || expectedVersion == null || !quoteVersion.equals(expectedVersion)) {
            throw new IllegalStateException("Quote Summary version is stale or unavailable");
        }
        requireQuoteReady(quote);

        String requestPid = required(quote.get("crm_qs_customer_request_id"),
                "Quote Summary must link a Customer Request");
        Map<String, Object> qdp = requiredRecord(db, QDP_MODEL, qdpPid, "QDP revision");
        requireRecordIdentity(qdp, qdpPid, "QDP revision");
        if (!"released".equals(text(qdp.get("crm_qdp_status")))) {
            throw new IllegalArgumentException("QDP revision must be released before order commitment");
        }
        if (!requestPid.equals(text(qdp.get("crm_qdp_customer_request_id")))) {
            throw new IllegalArgumentException(
                    "QDP revision belongs to a different Customer Request");
        }
        if (!Set.of("ready", "ready_with_approved_exception")
                .contains(text(qdp.get("crm_qdp_gate_verdict")))) {
            throw new IllegalStateException("Released QDP has no accepted release-gate verdict");
        }
        required(qdp.get("crm_qdp_released_at"), "Released QDP audit time is required");
        required(qdp.get("crm_qdp_released_by"), "Released QDP audit actor is required");

        List<Map<String, Object>> released = safeList(db.query(QDP_MODEL, Map.of(
                "crm_qdp_customer_request_id", requestPid,
                "crm_qdp_status", "released")));
        if (released.size() != 1 || !qdpPid.equals(resolvePid(released.getFirst()))) {
            throw new IllegalStateException(
                    "Customer Request must have exactly one current released QDP revision");
        }

        String confirmationPid = required(qdp.get("crm_qdp_customer_confirmation_id"),
                "Released QDP must link Customer Confirmation evidence");
        String requirementVersionPid = required(qdp.get("crm_qdp_requirement_version_id"),
                "Released QDP must link a Requirement Version");
        String filePackagePid = required(qdp.get("crm_qdp_file_package_id"),
                "Released QDP must link a File Package");
        String packageHash = required(qdp.get("crm_qdp_file_package_hash"),
                "Released QDP must carry the File Package hash");
        if (!packageHash.equals(required(qdp.get("crm_qdp_customer_confirmed_hash"),
                "Released QDP must carry the customer-confirmed hash"))) {
            throw new IllegalStateException("Released QDP confirmation hash does not match its File Package");
        }

        Map<String, Object> confirmation = requiredRecord(
                db, CONFIRMATION_MODEL, confirmationPid, "Customer Confirmation");
        requireRecordIdentity(confirmation, confirmationPid, "Customer Confirmation");
        if (!"confirmed".equals(text(confirmation.get("crm_cc_status")))
                || !requestPid.equals(text(confirmation.get("crm_cc_customer_request_id")))
                || !requirementVersionPid.equals(text(confirmation.get("crm_cc_requirement_version_id")))
                || !filePackagePid.equals(text(confirmation.get("crm_cc_file_package_id")))
                || !packageHash.equals(text(confirmation.get("crm_cc_file_package_hash")))) {
            throw new IllegalStateException(
                    "Customer Confirmation does not match the released QDP evidence chain");
        }

        String now = Instant.now().toString();
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("crm_qs_status", "ordered");
        patch.put("crm_qs_committed_qdp_revision_id", qdpPid);
        patch.put("crm_qs_customer_confirmation_id", confirmationPid);
        patch.put("crm_qs_order_committed_at", now);
        patch.put("crm_qs_order_committed_by", actor);
        patch.put("_expectedVersion", expectedVersion);

        if (context.dryRun()) {
            return Map.of(
                    "success", true,
                    "dryRun", true,
                    "status", "order_commitment_validated",
                    "quoteSummaryId", quotePid,
                    "qdpRevisionId", qdpPid,
                    "customerConfirmationId", confirmationPid);
        }
        Map<String, Object> updated = db.update(QUOTE_MODEL, quotePid, patch);
        if (updated == null) {
            throw new IllegalStateException("Quote Summary update returned no record");
        }
        return Map.of(
                "success", true,
                "status", "ordered",
                "quoteSummaryId", quotePid,
                "qdpRevisionId", qdpPid,
                "customerConfirmationId", confirmationPid,
                "committedAt", now,
                "committedBy", actor);
    }

    private static void requireQuoteReady(Map<String, Object> quote) {
        if (!"accepted".equals(text(quote.get("crm_qs_status")))) {
            throw new IllegalArgumentException("Quote Summary must be accepted before order commitment");
        }
        if (!"accepted".equals(text(quote.get("crm_qs_customer_feedback_status")))) {
            throw new IllegalArgumentException("Customer feedback must record acceptance");
        }
        if (!"won".equals(text(quote.get("crm_qs_won_lost_result")))) {
            throw new IllegalArgumentException("Quote result must be won before order commitment");
        }
        if (!Set.of("none", "approved").contains(text(quote.get("crm_qs_approval_status")))) {
            throw new IllegalArgumentException("Quote approval must be complete before order commitment");
        }
        for (String immutableField : List.of(
                "crm_qs_committed_qdp_revision_id",
                "crm_qs_customer_confirmation_id",
                "crm_qs_order_committed_at",
                "crm_qs_order_committed_by")) {
            if (text(quote.get(immutableField)) != null) {
                throw new IllegalStateException("Quote Summary already has an order commitment");
            }
        }
    }

    private static String requireExactTarget(Object target, Object payloadValue, String label) {
        String targetPid = required(target, label + " command target is required");
        String payloadPid = required(payloadValue, label + " payload pid is required");
        if (!targetPid.equals(payloadPid)) {
            throw new IllegalArgumentException(label + " payload pid does not match command target");
        }
        return targetPid;
    }

    private static Map<String, Object> requiredRecord(
            DataAccessor db, String model, String pid, String label) {
        Map<String, Object> record = db.getById(model, pid);
        if (record == null) {
            throw new IllegalArgumentException(label + " not found: " + pid);
        }
        return record;
    }

    private static void requireRecordIdentity(Map<String, Object> record, String expectedPid, String label) {
        String actualPid = resolvePid(record);
        if (!expectedPid.equals(actualPid)) {
            throw new IllegalStateException(label + " identity mismatch");
        }
    }

    private static String resolvePid(Map<String, Object> record) {
        if (record == null) return null;
        return text(record.get("pid"));
    }

    private static List<Map<String, Object>> safeList(List<Map<String, Object>> rows) {
        return rows == null ? List.of() : rows;
    }

    private static Object setting(CommandContext context, String key) {
        return context.settings() == null ? null : context.settings().get(key);
    }

    private static Long positiveLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue() > 0 ? number.longValue() : null;
        }
        try {
            long parsed = Long.parseLong(String.valueOf(value).trim());
            return parsed > 0 ? parsed : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String required(Object value, String message) {
        String normalized = text(value);
        if (normalized == null) throw new IllegalArgumentException(message);
        return normalized;
    }

    private static String text(Object value) {
        if (value == null) return null;
        String normalized = String.valueOf(value).trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
