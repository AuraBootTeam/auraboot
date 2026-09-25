package com.auraboot.framework.application.release;

import com.auraboot.framework.common.util.UlidGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/** Immutable platform content registration only; never deployment or admission authority. */
@Service
public class PlatformReleaseRegistrationService {
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private com.zaxxer.hikari.HikariDataSource ownedPool;

    public PlatformReleaseRegistrationService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
        this.transactions.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PlatformReleaseRegistrationService(org.springframework.core.env.Environment environment, ObjectMapper mapper) {
        this.mapper = mapper;
        if (!environment.getProperty("aura.registry.registration.enabled", Boolean.class, false)) {
            jdbc = null;
            transactions = null;
            return;
        }
        ownedPool = RegistryRegistrationConnection.open(environment);
        jdbc = new JdbcTemplate(ownedPool);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(ownedPool));
        transactions.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @jakarta.annotation.PreDestroy public void close() {
        if (ownedPool != null) ownedPool.close();
    }

    public record Source(String repository, String commit) {}
    public record Artifact(String type, String id, String version, String uri, String digest, Source source) {}
    public record Content(String version, String sourceLockIdentity, ObjectNode platformContracts, List<Artifact> artifacts) {}
    public record Registration(String releaseId, String platform, String version, String digest, String manifestText, String registeredBy) {}

    public Registration register(String platform, Content content, String actor) {
        if (transactions == null) throw new ApplicationReleaseRegistrationService.RegistrationUnavailableException();
        require(platform != null && platform.matches("[a-z][a-z0-9-]{1,99}"), "Invalid platform code");
        require(actor != null && actor.matches("(user|ci|system|test):[A-Za-z0-9._:-]{1,200}"), "Registration actor required");
        require(content != null && content.version() != null && !content.version().isBlank(), "Platform version required");
        require(content.platformContracts() != null && content.artifacts() != null && !content.artifacts().isEmpty(), "Explicit contracts and artifacts required");
        require(content.artifacts().stream().allMatch(item -> item != null && item.type() != null && item.id() != null), "Artifact type and ID required");
        validateContent(content);
        var request = mapper.createObjectNode().put("schemaVersion", 1).put("platform", platform)
                .put("version", content.version()).put("sourceLockIdentity", content.sourceLockIdentity());
        var contracts = content.platformContracts().deepCopy();
        for (String dimension : List.of("runtime", "pluginApi", "dslSchema")) {
            var values = contracts.get(dimension);
            require(values != null && values.isArray(), "Explicit platform contract arrays required");
            var sorted = mapper.createArrayNode();
            var items = new java.util.ArrayList<JsonNode>();
            values.forEach(items::add);
            items.stream().sorted(Comparator.comparing(JsonNode::toString)).forEach(sorted::add);
            contracts.set(dimension, sorted);
        }
        request.set("platformContracts", contracts);
        var artifacts = request.putArray("artifacts");
        content.artifacts().stream().sorted(Comparator.comparing(Artifact::type).thenComparing(Artifact::id))
                .forEach(artifact -> artifacts.add(mapper.<JsonNode>valueToTree(artifact)));
        String requestText = canonical(request).toString();
        return transactions.execute(status -> {
            // Serialize the coordinate before reading; the unique constraint remains the DB backstop.
            jdbc.execute(connection -> {
                var statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))");
                statement.setString(1, "platform-registry:" + platform + ":" + content.version());
                return statement;
            }, (org.springframework.jdbc.core.PreparedStatementCallback<Void>) statement -> { statement.execute(); return null; });
            var previous = jdbc.query("SELECT release_id,digest,manifest_text,registered_by FROM ab_platform_release_registry WHERE platform_code=? AND version=?",
                    (row, index) -> new Registration(row.getString(1), platform, content.version(), row.getString(2), row.getString(3), row.getString(4)),
                    platform, content.version());
            if (!previous.isEmpty()) {
                var registered = previous.getFirst();
                ObjectNode original;
                try { original = (ObjectNode) mapper.readTree(registered.manifestText()); }
                catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalStateException("Invalid registered platform manifest", invalid); }
                original.remove("releaseId");
                if (!canonical(original).toString().equals(requestText)) throw new IllegalStateException("Platform version already registered with different content");
                return registered;
            }
            String id = UlidGenerator.generate();
            request.put("releaseId", id);
            String raw = canonical(request).toString();
            String digest = digest(raw);
            try {
                jdbc.update("INSERT INTO ab_platform_release_registry(release_id,platform_code,version,source_lock_identity,manifest_text,digest,registered_by) VALUES (?,?,?,?,?,?,?)",
                        id, platform, content.version(), content.sourceLockIdentity(), raw, digest, actor);
            } catch (org.springframework.dao.DataIntegrityViolationException failure) {
                Throwable cause = failure.getMostSpecificCause();
                if (cause instanceof org.postgresql.util.PSQLException postgres
                        && "23514".equals(postgres.getSQLState())
                        && postgres.getServerErrorMessage() != null
                        && "platform_artifact_coordinate_immutable".equals(postgres.getServerErrorMessage().getConstraint())) {
                    throw new ArtifactCoordinateConflictException();
                }
                throw failure;
            }
            return new Registration(id, platform, content.version(), digest, raw, actor);
        });
    }

    public static final class ArtifactCoordinateConflictException extends IllegalStateException {
        public ArtifactCoordinateConflictException() {
            super("Platform artifact coordinate already registered with different content");
        }
    }

    private static void validateContent(Content content) {
        require(immutableVersion(content.version()), "Immutable platform version required");
        require(sha256(content.sourceLockIdentity()), "Valid source lock identity required");
        var contracts = content.platformContracts();
        var dimensions = java.util.Set.of("runtime", "pluginApi", "dslSchema");
        contracts.fieldNames().forEachRemaining(name -> require(dimensions.contains(name), "Unknown platform contract dimension"));
        for (String dimension : dimensions) {
            var values = contracts.get(dimension);
            require(values != null && values.isArray() && !values.isEmpty(), "Nonempty platform contract arrays required");
            var seen = new java.util.HashSet<JsonNode>();
            for (var value : values) {
                boolean valid = dimension.equals("dslSchema")
                        ? value.isIntegralNumber() && value.bigIntegerValue().signum() > 0
                        : value.isTextual() && !value.textValue().isBlank() && value.textValue().trim().length() <= 200;
                require(valid && seen.add(value), "Invalid or duplicate platform contract");
            }
        }
        var coordinates = new java.util.HashSet<List<String>>();
        var types = java.util.Set.of("runtime", "maven", "npm", "plugin", "config", "migration", "oci");
        for (var artifact : content.artifacts()) {
            require(types.contains(artifact.type()) && !artifact.id().isBlank(), "Valid artifact type and ID required");
            require(coordinates.add(List.of(artifact.type(), artifact.id())), "Duplicate platform artifact coordinate");
            require(immutableVersion(artifact.version()) && sha256(artifact.digest()), "Immutable artifact version and digest required");
            require(artifact.uri() != null && artifact.uri().matches("(?s)^(maven|npm|oci|artifact):.+$"), "Artifact URI required");
            var source = artifact.source();
            require(source != null && source.repository() != null && !source.repository().isBlank()
                    && source.commit() != null && source.commit().matches("[0-9a-f]{40}"), "Artifact source repository and commit required");
        }
    }

    private static boolean immutableVersion(String value) {
        return value != null && !value.isBlank() && !java.util.regex.Pattern.compile("snapshot|latest|workspace|branch",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(value).find();
    }

    private static boolean sha256(String value) {
        return value != null && value.matches("sha256:[0-9a-f]{64}");
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

    private static String digest(String value) {
        try { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 unavailable", unavailable); }
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalArgumentException(message);
    }
}
