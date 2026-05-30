package com.auraboot.framework.connector.airflow.secret;

import com.auraboot.framework.common.crypto.FieldEncryptionService;
import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.connector.airflow.secret.mapper.ConnectorWebhookSecretMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves the Airflow webhook shared secret for a given
 * {@code (tenant, connection_name)} tuple. PRD 18-C §C.3.3 W5-FU-5.
 *
 * <p>Replaces the single application-property fallback with a per-tenant
 * per-connection row. Supports rotation: during the
 * {@value #ROTATION_GRACE_SECONDS}-second grace window the previous secret
 * is also accepted so in-flight Airflow tasks signed by the old secret
 * still land cleanly.
 *
 * <p>Caller contract: {@link #candidateSecrets} returns all secrets the
 * verifier should attempt against the incoming HMAC, in priority order
 * (active first, then most-recently-rotated grace-window secrets). An
 * empty list means "no secret configured" — the verifier should respond
 * 404 {@code UNKNOWN_CONNECTION}.
 */
@Slf4j
@Service
public class WebhookSecretService {

    public static final long ROTATION_GRACE_SECONDS = 300L;

    private final ConnectorWebhookSecretMapper mapper;
    private final FieldEncryptionService encryption;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public WebhookSecretService(ConnectorWebhookSecretMapper mapper,
                                FieldEncryptionService encryption) {
        this(mapper, encryption, Clock.systemUTC());
    }

    /** Test seam. */
    WebhookSecretService(ConnectorWebhookSecretMapper mapper,
                         FieldEncryptionService encryption,
                         Clock clock) {
        this.mapper = Objects.requireNonNull(mapper);
        this.encryption = Objects.requireNonNull(encryption);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Returns the plaintext secrets the verifier should try, in priority
     * order. Empty list = no configured secret for this connection.
     */
    /**
     * Returns the plaintext secrets the verifier should try, in priority
     * order, along with the {@code tenant_id} of the matched connection.
     *
     * <p>The webhook URL only carries {@code connection_name}; the
     * {@code tenant_id} of the row that matches is the source of truth for
     * which tenant owns this in-flight webhook.
     *
     * <p>{@link Resolution#tenantId} is null when no row matches.
     */
    public Resolution candidateSecrets(String connectionName) {
        if (connectionName == null || connectionName.isBlank()) {
            return Resolution.empty();
        }
        Instant graceCutoff = clock.instant().minus(Duration.ofSeconds(ROTATION_GRACE_SECONDS));
        List<ConnectorWebhookSecret> rows = mapper.findActiveOrGracePeriod(
                connectionName, graceCutoff);
        if (rows == null || rows.isEmpty()) {
            return Resolution.empty();
        }
        // All rows for a given connection_name share the same tenant_id
        // (enforced by the unique partial index on connection_name + by the
        // upsertActiveSecret path that always reuses the existing tenant).
        Long tenantId = rows.get(0).getTenantId();
        List<String> out = new ArrayList<>(rows.size());
        for (ConnectorWebhookSecret row : rows) {
            try {
                out.add(encryption.decrypt(row.getSharedSecret()));
            } catch (Exception e) {
                // Decryption failure is operational — a corrupted row should not
                // tear down the whole verifier (other rows may still be intact).
                // The verifier treats this candidate as "did not match" and
                // tries the next; ops sees the row id in the log.
                log.warn("Failed to decrypt webhook secret row id={} for connection={}: {}",
                        row.getId(), connectionName, e.getMessage());
            }
        }
        return new Resolution(tenantId, List.copyOf(out));
    }

    /** Candidate secret resolution: tenantId + ordered plaintext secrets. */
    public record Resolution(Long tenantId, List<String> secrets) {
        public static Resolution empty() {
            return new Resolution(null, List.of());
        }
        public boolean hasMatch() { return tenantId != null && !secrets.isEmpty(); }
    }

    /**
     * Create or update the ACTIVE secret for a connection. If an active row
     * already exists it is demoted (active=false, rotated_at=NOW) and the new
     * secret is inserted active=true. The old row stays for the grace window.
     */
    @Transactional
    public ConnectorWebhookSecret upsertActiveSecret(Long tenantId,
                                                     String connectionName,
                                                     String plaintextSecret) {
        Objects.requireNonNull(tenantId, "tenantId");
        if (connectionName == null || connectionName.isBlank()) {
            throw new IllegalArgumentException("connectionName required");
        }
        if (plaintextSecret == null || plaintextSecret.isBlank()) {
            throw new IllegalArgumentException("plaintextSecret required");
        }

        ConnectorWebhookSecret existing = mapper.findActive(connectionName);
        if (existing != null && !existing.getTenantId().equals(tenantId)) {
            throw new IllegalArgumentException(
                    "connection_name '" + connectionName + "' is already owned by another tenant");
        }
        if (existing != null) {
            mapper.deactivate(existing.getId());
        }
        ConnectorWebhookSecret row = new ConnectorWebhookSecret();
        row.setPid(UlidGenerator.generate());
        row.setTenantId(tenantId);
        row.setConnectionName(connectionName);
        row.setSharedSecret(encryption.encrypt(plaintextSecret));
        row.setAlgorithm("HMAC-SHA256");
        row.setActive(Boolean.TRUE);
        mapper.insert(row);
        return row;
    }

    @Transactional
    public boolean revoke(String connectionName) {
        return mapper.deleteByConnection(connectionName) > 0;
    }

    public Optional<ConnectorWebhookSecret> findActive(String connectionName) {
        return Optional.ofNullable(mapper.findActive(connectionName));
    }
}
