package com.auraboot.plugins.crm.handler;

import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.DataAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.pf4j.Extension;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Creates the sole CRM Customer Request from one canonical connector delivery.
 *
 * <p>The platform command boundary owns concurrent request idempotency. This handler adds the
 * durable business fence: {@code source channel + source system + source message reference}. An
 * exact delivery replay returns the existing Customer Request; a changed delivery under the same
 * source identity fails closed. The received facts, provenance and field evidence are immutable
 * writer-owned fields, so later qualification never overwrites what the customer actually sent.</p>
 */
@Extension
public class IntakeCustomerRequestHandler implements CommandHandlerExtension {

    public static final String COMMAND_TYPE = "crm:intake_customer_request";
    static final String MODEL = "crm_customer_request_common";
    static final int INTAKE_SCHEMA_VERSION = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    private static final Set<String> SOURCE_CHANNELS = Set.of("email", "portal", "import", "form");
    private static final LinkedHashMap<String, Integer> SOURCE_FACT_LIMITS = sourceFactLimits();
    private static final Set<String> ENVELOPE_FIELDS = Set.of(
            "crm_cr_source_channel",
            "crm_cr_source_system",
            "crm_cr_source_message_ref",
            "crm_cr_source_received_at",
            "crm_cr_field_evidence");

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
            throw new IllegalStateException("DataAccessor unavailable; cannot intake Customer Request");
        }
        if (context.tenantId() == null || context.tenantId() <= 0) {
            throw new IllegalStateException("Authenticated tenant context is required");
        }
        required(setting(context, "__currentUser"), 100,
                "Authenticated actor context is required");
        String clientRequestId = required(setting(context, CLIENT_REQUEST_ID_KEY), 128,
                "clientRequestId is required for Customer Request intake");
        Map<String, Object> payload = context.payload() == null ? Map.of() : context.payload();
        rejectUnownedCrmFields(payload);

        String sourceChannel = required(payload.get("crm_cr_source_channel"), 32,
                "Source channel is required").toLowerCase();
        if (!SOURCE_CHANNELS.contains(sourceChannel)) {
            throw new IllegalArgumentException(
                    "Source channel must be one of email, portal, import or form");
        }
        String sourceSystem = required(payload.get("crm_cr_source_system"), 80,
                "Source system is required").toLowerCase();
        if (!sourceSystem.matches("^[a-z0-9][a-z0-9._-]{1,79}$")) {
            throw new IllegalArgumentException(
                    "Source system must be a stable lowercase connector code");
        }
        String sourceMessageRef = required(payload.get("crm_cr_source_message_ref"), 256,
                "Source message reference is required");
        String sourceReceivedAt = instant(payload.get("crm_cr_source_received_at"),
                "Source received time must be an ISO-8601 timestamp with offset");

        Map<String, Object> snapshot = sourceSnapshot(payload);
        List<Map<String, Object>> fieldEvidence = fieldEvidence(
                payload.get("crm_cr_field_evidence"), snapshot, sourceMessageRef);
        String sourceBusinessKey = hash(Map.of(
                "tenantId", context.tenantId(),
                "sourceChannel", sourceChannel,
                "sourceSystem", sourceSystem,
                "sourceMessageRef", sourceMessageRef));

        Map<String, Object> canonicalContent = new LinkedHashMap<>();
        canonicalContent.put("schemaVersion", INTAKE_SCHEMA_VERSION);
        canonicalContent.put("sourceChannel", sourceChannel);
        canonicalContent.put("sourceSystem", sourceSystem);
        canonicalContent.put("sourceMessageRef", sourceMessageRef);
        canonicalContent.put("sourceReceivedAt", sourceReceivedAt);
        canonicalContent.put("receivedFields", snapshot);
        canonicalContent.put("fieldEvidence", fieldEvidence);
        String contentHash = hash(canonicalContent);

        List<Map<String, Object>> existing = safeList(db.query(MODEL,
                Map.of("crm_cr_source_business_key", sourceBusinessKey)));
        if (existing.size() > 1) {
            throw new IllegalStateException(
                    "Source business identity resolved to more than one Customer Request");
        }
        if (!existing.isEmpty()) {
            Map<String, Object> replay = existing.getFirst();
            if (!contentHash.equals(text(replay.get("crm_cr_source_content_hash")))) {
                throw new IllegalStateException(
                        "Source business identity was reused with changed Customer Request content");
            }
            if (context.dryRun()) {
                return dryRunResult(replay, contentHash, true);
            }
            return result(replay, contentHash, true);
        }

        if (context.dryRun()) {
            return dryRunResult(Map.of(
                    "crm_cr_code", "CR-INT-" + sourceBusinessKey.substring(0, 20).toUpperCase(),
                    "crm_cr_status", "submitted"), contentHash, false);
        }

        String ingestedAt = Instant.now().toString();
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("schemaVersion", INTAKE_SCHEMA_VERSION);
        provenance.put("sourceChannel", sourceChannel);
        provenance.put("sourceSystem", sourceSystem);
        provenance.put("sourceMessageRef", sourceMessageRef);
        provenance.put("sourceReceivedAt", sourceReceivedAt);
        provenance.put("ingestedAt", ingestedAt);
        provenance.put("clientRequestId", clientRequestId);
        provenance.put("contentHash", contentHash);

        Map<String, Object> row = new LinkedHashMap<>(snapshot);
        row.putIfAbsent("crm_cr_type", "inquiry");
        row.putIfAbsent("crm_cr_priority", "medium");
        row.put("crm_cr_code", "CR-INT-" + sourceBusinessKey.substring(0, 20).toUpperCase());
        row.put("crm_cr_status", "submitted");
        row.put("crm_cr_route_status", "unrouted");
        row.put("crm_cr_source_channel", sourceChannel);
        row.put("crm_cr_source_business_key", sourceBusinessKey);
        row.put("crm_cr_source_system", sourceSystem);
        row.put("crm_cr_source_message_ref", sourceMessageRef);
        row.put("crm_cr_source_received_at", sourceReceivedAt);
        row.put("crm_cr_source_content_hash", contentHash);
        row.put("crm_cr_intake_snapshot", canonicalContent);
        row.put("crm_cr_source_provenance", provenance);
        row.put("crm_cr_field_evidence", fieldEvidence);
        row.put("crm_cr_field_evidence_count", Math.toIntExact(
                fieldEvidence.stream().map(evidence -> evidence.get("field")).distinct().count()));
        row.put("crm_cr_intake_client_request_id", clientRequestId);
        row.put("crm_cr_ingested_at", ingestedAt);
        row.put("crm_cr_submitted_at", ingestedAt);

        Map<String, Object> created = db.create(MODEL, row);
        if (created == null || text(created.get("pid")) == null) {
            throw new IllegalStateException("Customer Request intake returned no public record pid");
        }
        return result(created, contentHash, false);
    }

    private static Map<String, Object> dryRunResult(
            Map<String, Object> record, String contentHash, boolean idempotent) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("dryRun", true);
        String pid = text(record.get("pid"));
        if (pid != null) result.put("customerRequestId", pid);
        result.put("requestCode", record.get("crm_cr_code"));
        result.put("plannedStatus", record.get("crm_cr_status"));
        result.put("contentHash", contentHash);
        result.put("idempotent", idempotent);
        result.put("status", "intake_validated");
        return result;
    }

    private static void rejectUnownedCrmFields(Map<String, Object> payload) {
        Set<String> allowed = new HashSet<>(SOURCE_FACT_LIMITS.keySet());
        allowed.addAll(ENVELOPE_FIELDS);
        List<String> rejected = payload.keySet().stream()
                .filter(key -> key != null && key.startsWith("crm_cr_") && !allowed.contains(key))
                .sorted()
                .toList();
        if (!rejected.isEmpty()) {
            throw new IllegalArgumentException(
                    "Customer Request intake cannot accept server-owned fields: "
                            + String.join(", ", rejected));
        }
    }

    private static Map<String, Object> sourceSnapshot(Map<String, Object> payload) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> sourceField : SOURCE_FACT_LIMITS.entrySet()) {
            Object raw = payload.get(sourceField.getKey());
            String value = text(raw);
            if (value == null) continue;
            Object normalized = switch (sourceField.getKey()) {
                case "crm_cr_expected_date" -> localDate(value,
                        "Expected date must be an ISO-8601 date");
                case "crm_cr_due_at" -> instant(value,
                        "Request due time must be an ISO-8601 timestamp with offset");
                default -> limited(value, sourceField.getValue(), sourceField.getKey());
            };
            snapshot.put(sourceField.getKey(), normalized);
        }
        required(snapshot.get("crm_cr_title"), 200, "Customer Request title is required");
        return snapshot;
    }

    private static List<Map<String, Object>> fieldEvidence(
            Object raw, Map<String, Object> snapshot, String sourceMessageRef) {
        Object value = parseJson(raw, "Field evidence must be a JSON array");
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException(
                    "Field evidence must contain at least one field reference");
        }
        if (list.size() > 64) {
            throw new IllegalArgumentException("Field evidence cannot exceed 64 references");
        }

        List<Map<String, Object>> normalized = new ArrayList<>();
        Set<String> coveredFields = new HashSet<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> evidence)) {
                throw new IllegalArgumentException("Each field evidence reference must be an object");
            }
            Set<String> unknown = new HashSet<>();
            for (Object key : evidence.keySet()) {
                if (key != null && !Set.of("field", "locator", "evidenceRef")
                        .contains(String.valueOf(key))) {
                    unknown.add(String.valueOf(key));
                }
            }
            if (!unknown.isEmpty()) {
                throw new IllegalArgumentException(
                        "Field evidence contains unsupported keys: " + String.join(", ", unknown));
            }
            String field = required(evidence.get("field"), 64,
                    "Field evidence field is required");
            if (!snapshot.containsKey(field)) {
                throw new IllegalArgumentException(
                        "Field evidence must reference a supplied Customer Request field: " + field);
            }
            String locator = required(evidence.get("locator"), 512,
                    "Field evidence locator is required");
            String evidenceRef = optional(evidence.get("evidenceRef"), 256);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("field", field);
            entry.put("sourceMessageRef", sourceMessageRef);
            if (evidenceRef != null) entry.put("evidenceRef", evidenceRef);
            entry.put("locator", locator);
            entry.put("valueHash", hash(snapshot.get(field)));
            normalized.add(entry);
            coveredFields.add(field);
        }
        Set<String> missing = new HashSet<>(snapshot.keySet());
        missing.removeAll(coveredFields);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "Every supplied Customer Request field requires evidence: "
                            + String.join(", ", missing.stream().sorted().toList()));
        }
        normalized.sort(Comparator
                .comparing((Map<String, Object> item) -> String.valueOf(item.get("field")))
                .thenComparing(item -> String.valueOf(item.get("locator")))
                .thenComparing(item -> String.valueOf(item.getOrDefault("evidenceRef", ""))));
        return List.copyOf(normalized);
    }

    private static Map<String, Object> result(
            Map<String, Object> record, String contentHash, boolean idempotent) {
        String pid = text(record.get("pid"));
        if (pid == null) throw new IllegalStateException("Customer Request has no public record pid");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("customerRequestId", pid);
        result.put("requestCode", record.get("crm_cr_code"));
        result.put("status", record.get("crm_cr_status"));
        result.put("contentHash", contentHash);
        result.put("idempotent", idempotent);
        return result;
    }

    private static LinkedHashMap<String, Integer> sourceFactLimits() {
        LinkedHashMap<String, Integer> fields = new LinkedHashMap<>();
        fields.put("crm_cr_title", 200);
        fields.put("crm_cr_account_id", 80);
        fields.put("crm_cr_contact_id", 80);
        fields.put("crm_cr_opportunity_id", 80);
        fields.put("crm_cr_type", 32);
        fields.put("crm_cr_priority", 32);
        fields.put("crm_cr_expected_date", 32);
        fields.put("crm_cr_summary", 10_000);
        fields.put("crm_cr_due_at", 64);
        return fields;
    }

    private static Object parseJson(Object raw, String message) {
        if (!(raw instanceof String text)) return raw;
        if (text.isBlank()) return null;
        try {
            return MAPPER.readValue(text, new TypeReference<Object>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(message, e);
        }
    }

    private static String hash(Object value) {
        try {
            byte[] canonical = MAPPER.writeValueAsBytes(value);
            return hex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to canonicalize Customer Request intake", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM does not provide SHA-256", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value));
        return result.toString();
    }

    private static String localDate(String raw, String message) {
        try {
            return LocalDate.parse(raw).toString();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(message, e);
        }
    }

    private static String instant(Object raw, String message) {
        String value = text(raw);
        if (value == null) throw new IllegalArgumentException(message);
        try {
            return OffsetDateTime.parse(value).toInstant().toString();
        } catch (DateTimeParseException ignored) {
            try {
                return Instant.parse(value).toString();
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException(message, e);
            }
        }
    }

    private static Object setting(CommandContext context, String key) {
        return context.settings() == null ? null : context.settings().get(key);
    }

    private static List<Map<String, Object>> safeList(List<Map<String, Object>> rows) {
        return rows == null ? List.of() : rows;
    }

    private static String required(Object value, int maxLength, String message) {
        String normalized = text(value);
        if (normalized == null) throw new IllegalArgumentException(message);
        return limited(normalized, maxLength, message);
    }

    private static String optional(Object value, int maxLength) {
        String normalized = text(value);
        return normalized == null ? null : limited(normalized, maxLength, "Value");
    }

    private static String limited(String value, int maxLength, String label) {
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(label + " exceeds " + maxLength + " characters");
        }
        return value;
    }

    private static String text(Object value) {
        if (value == null) return null;
        String normalized = String.valueOf(value).trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
