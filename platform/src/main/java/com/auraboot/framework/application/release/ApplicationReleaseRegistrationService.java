package com.auraboot.framework.application.release;

import com.auraboot.framework.common.util.UlidGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Internal content registration only. It does not publish, qualify, or bind a release. */
@Service
public class ApplicationReleaseRegistrationService {
    private static final Set<String> COMPONENT_TYPES = Set.of(
            "definition", "backend_plugin", "frontend_contribution", "asset", "migration");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ApplicationReleaseRegistrationService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public record Component(String key, String type, String version, String digest, ObjectNode compatibilityContract) {}
    public record Content(int compatibilityEpoch, String displayVersion, String sourceLockIdentity,
                          ObjectNode platformCompatibility, List<Component> components) {}
    public record Registration(String releaseId, long sequence, String digest, String manifestText) {}

    @Transactional
    public Registration register(String applicationCode, String registrationKey, Content content) {
        require(applicationCode != null && applicationCode.matches("[a-z][a-z0-9-]{1,99}"), "Invalid application code");
        require(registrationKey != null && registrationKey.matches("[A-Za-z0-9._:-]{1,128}"), "Invalid registration key");
        ObjectNode request = request(applicationCode, content);
        String requestDigest = digest(canonical(request).toString());
        var applications = jdbc.query("SELECT id,next_release_sequence FROM ab_application WHERE code=? FOR UPDATE",
                (row, index) -> new long[]{row.getLong(1), row.getLong(2)}, applicationCode);
        require(applications.size() == 1, "Application must be registered before its releases");
        long appId = applications.getFirst()[0];
        long sequence = applications.getFirst()[1];
        var previous = jdbc.query("""
                SELECT release_id,release_sequence,digest,manifest_text,registration_request_digest
                FROM ab_application_release WHERE application_id=? AND registration_key=?
                """, (row, index) -> {
                    if (!requestDigest.equals(row.getString(5))) {
                        throw new IllegalStateException("Registration key was already used for different content");
                    }
                    return new Registration(row.getString(1), row.getLong(2), row.getString(3), row.getString(4));
                }, appId, registrationKey);
        if (!previous.isEmpty()) return previous.getFirst();
        String releaseId = UlidGenerator.generate();
        request.put("schemaVersion", 1).put("releaseId", releaseId).put("releaseSequence", sequence);
        String manifestText = canonical(request).toString();
        String digest = digest(manifestText);
        jdbc.update("""
                INSERT INTO ab_application_release(release_id,application_id,release_sequence,compatibility_epoch,
                  source_lock_identity,manifest_text,digest,registration_key,registration_request_digest)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, releaseId, appId, sequence, content.compatibilityEpoch(), content.sourceLockIdentity(),
                manifestText, digest, registrationKey, requestDigest);
        return new Registration(releaseId, sequence, digest, manifestText);
    }

    private ObjectNode request(String application, Content content) {
        require(content != null && content.compatibilityEpoch() > 0, "Positive compatibility epoch required");
        require(isDigest(content.sourceLockIdentity()), "Source lock SHA256 identity required");
        require(content.platformCompatibility() != null && !content.platformCompatibility().isEmpty(), "Platform compatibility required");
        require(content.components() != null && !content.components().isEmpty(), "Components required");
        require(content.components().stream().noneMatch(java.util.Objects::isNull), "Null component");
        var request = mapper.createObjectNode();
        request.put("application", application).put("compatibilityEpoch", content.compatibilityEpoch())
                .put("sourceLockIdentity", content.sourceLockIdentity());
        if (content.displayVersion() != null) {
            require(!content.displayVersion().isBlank(), "Display version must not be blank");
            request.put("displayVersion", content.displayVersion());
        }
        request.set("platformCompatibility", content.platformCompatibility().deepCopy());
        var components = request.putArray("components");
        var keys = new TreeSet<String>();
        // Validate keys before sorting, so malformed requests fail without database access.
        for (Component component : content.components()) {
            require(component.key() != null && !component.key().isBlank() && keys.add(component.key()), "Invalid or duplicate component key");
        }
        for (Component component : content.components().stream().sorted(Comparator.comparing(Component::key)).toList()) {
            require(component.type() != null && COMPONENT_TYPES.contains(component.type()), "Invalid component type");
            require(component.version() != null && !component.version().isBlank()
                    && !component.version().matches("(?i).*(snapshot|latest|workspace|branch).*"), "Immutable component version required");
            require(isDigest(component.digest()), "Component SHA256 digest required");
            var item = components.addObject();
            item.put("key", component.key()).put("type", component.type()).put("version", component.version()).put("digest", component.digest());
            item.set("compatibilityContract", component.compatibilityContract() == null
                    ? mapper.createObjectNode() : component.compatibilityContract().deepCopy());
        }
        return request;
    }

    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            var result = mapper.createObjectNode();
            var names = new TreeSet<String>();
            value.fieldNames().forEachRemaining(names::add);
            names.forEach(name -> result.set(name, canonical(value.get(name))));
            return result;
        }
        if (value.isArray()) {
            var result = mapper.createArrayNode();
            value.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return value.deepCopy();
    }

    private static boolean isDigest(String value) {
        return value != null && value.matches("sha256:[0-9a-f]{64}");
    }
    private static String digest(String text) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }
    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalArgumentException(message);
    }
}
