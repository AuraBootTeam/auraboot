package com.auraboot.framework.plugin.rest;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.extension.RestPublicationPublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class RestPublicationStore {
    private static final int MAX_DOCUMENT_BYTES = 65_536;
    private static final TypeReference<Map<String, Object>> DOCUMENT = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public RestPublicationStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    record Published(long tenantId, Map<String, Object> document) {}

    Optional<Published> find(String namespace, String kind, String id) {
        if (!validKey(namespace, kind, id)) return Optional.empty();
        return jdbc.query("SELECT p.tenant_id, p.document::text " + VISIBLE_PUBLICATION,
                (rs, index) -> new Published(rs.getLong(1), parseDocument(rs.getString(2))),
                namespace, kind, id).stream().findFirst();
    }

    boolean allowsOrigin(String namespace, String kind, String id, String origin) {
        if (!validKey(namespace, kind, id) || origin == null || origin.length() > 512
                || !origin.matches("https?://[^/\\s?#@*]+")) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 " + VISIBLE_PUBLICATION
                        + " AND jsonb_exists(p.allowed_origins, ?))",
                Boolean.class, namespace, kind, id, origin));
    }

    private static final String VISIBLE_PUBLICATION = """
            FROM ab_plugin_rest_publication p
            WHERE p.namespace = ? AND p.kind = ? AND p.public_id = ?
            AND (p.parent_kind IS NULL OR EXISTS (
                SELECT 1 FROM ab_plugin_rest_publication parent
                WHERE parent.namespace = p.namespace AND parent.kind = p.parent_kind
                AND parent.public_id = p.parent_id AND parent.tenant_id = p.tenant_id
                AND parent.parent_kind IS NULL
            ))
            """;

    RestPublicationPublisher publisher(String namespace, long tenantId, long userId) {
        return new RestPublicationPublisher() {
            @Override
            public void publish(String kind, String id, String parentKind, String parentId, Map<String, Object> document) {
                publishWithOrigins(kind, id, parentKind, parentId, document, List.of());
            }

            @Override
            public void publishWithOrigins(String kind, String id, String parentKind, String parentId,
                                           Map<String, Object> document, List<String> origins) {
                requireWrite(namespace, kind, id, tenantId, userId);
                if (origins == null) throw new IllegalArgumentException("Publication origins are required");
                if ((parentKind != null || parentId != null) && !validKey(namespace, parentKind, parentId)) {
                    throw new IllegalArgumentException("Invalid publication parent");
                }
                int rows = jdbc.update("""
                        INSERT INTO ab_plugin_rest_publication
                        (namespace, kind, public_id, tenant_id, parent_kind, parent_id, document, allowed_origins)
                        VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb))
                        ON CONFLICT (namespace, kind, public_id) DO UPDATE
                        SET parent_kind = EXCLUDED.parent_kind, parent_id = EXCLUDED.parent_id,
                            document = EXCLUDED.document, allowed_origins = EXCLUDED.allowed_origins
                        WHERE ab_plugin_rest_publication.tenant_id = EXCLUDED.tenant_id
                        """, namespace, kind, id, tenantId, parentKind, parentId, serialize(document), serialize(origins));
                if (rows != 1) throw new AccessDeniedException("Publication belongs to a different tenant");
            }

            @Override
            public void withdraw(String kind, String id) {
                requireWrite(namespace, kind, id, tenantId, userId);
                jdbc.update("""
                        DELETE FROM ab_plugin_rest_publication
                        WHERE namespace = ? AND kind = ? AND public_id = ? AND tenant_id = ?
                        """, namespace, kind, id, tenantId);
            }
        };
    }

    private static void requireWrite(String namespace, String kind, String id, long tenantId, long userId) {
        if (tenantId <= 0 || userId <= 0 || !validKey(namespace, kind, id)
                || !Long.valueOf(tenantId).equals(MetaContext.getCurrentTenantId())
                || !Long.valueOf(userId).equals(MetaContext.getCurrentUserId())
                || !TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new AccessDeniedException("An authenticated publication write transaction is required");
        }
    }

    private static boolean validKey(String namespace, String kind, String id) {
        return namespace != null && namespace.matches("[a-zA-Z0-9_][a-zA-Z0-9_.-]{0,127}")
                && kind != null && kind.matches("[a-z0-9][a-z0-9-]{0,63}")
                && id != null && id.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,127}");
    }

    private String serialize(Object document) {
        if (document == null) throw new IllegalArgumentException("Publication document required");
        try {
            String serialized = json.writeValueAsString(document);
            if (serialized.getBytes(StandardCharsets.UTF_8).length > MAX_DOCUMENT_BYTES) {
                throw new IllegalArgumentException("Publication document is too large");
            }
            return serialized;
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Invalid publication document", error);
        }
    }

    private Map<String, Object> parseDocument(String value) {
        try {
            return json.readValue(value, DOCUMENT);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Invalid stored publication document", error);
        }
    }
}
