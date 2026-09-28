package com.auraboot.framework.application.release;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

/** Minimal publication, stable-channel, and initial active-binding control plane. */
@Service
public final class ApplicationReleaseControlService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ApplicationBindingCapabilityVerifier bindingCapabilities;

    public ApplicationReleaseControlService(JdbcTemplate jdbc,
                                            ApplicationBindingCapabilityVerifier bindingCapabilities) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.bindingCapabilities = Objects.requireNonNull(bindingCapabilities);
        var manager = new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource()));
        transactions = new TransactionTemplate(manager);
        // Keep the database default for standalone control calls and inherit an existing
        // transaction when binding is part of tenant creation. Declaring an isolation level
        // here makes Spring reject participation in an outer transaction whose definition
        // did not explicitly name that same isolation level.
    }

    public record Publication(long applicationId, String releaseId, String publishedBy, String operationId) {}
    public record ChannelTarget(long applicationId, String channel, String releaseId, long version,
                                String updatedBy, String operationId) {}
    public record Binding(long tenantId, long applicationId, String releaseId, String releaseDigest,
                          int compatibilityEpoch, String status, long version) {}

    public Publication publish(String applicationCode, String releaseId, String actor, String operationId) {
        validate(applicationCode, releaseId, actor, operationId);
        return transactions.execute(status -> {
            var release = release(applicationCode, releaseId);
            var replay = publicationByOperation(operationId);
            if (!replay.isEmpty()) {
                var previous = replay.getFirst();
                if (previous.applicationId() != release.applicationId() || !previous.releaseId().equals(releaseId)) {
                    throw new IllegalStateException("Publication operation was already used for a different release");
                }
                return previous;
            }
            jdbc.update("""
                    INSERT INTO ab_application_release_publication
                        (release_id,application_id,published_by,operation_id)
                    VALUES (?,?,?,?) ON CONFLICT (release_id) DO NOTHING
                    """, releaseId, release.applicationId(), actor, operationId);
            return jdbc.queryForObject("""
                    SELECT application_id,release_id,published_by,operation_id
                    FROM ab_application_release_publication WHERE release_id=?
                    """, (row, index) -> new Publication(row.getLong(1), row.getString(2), row.getString(3), row.getString(4)),
                    releaseId);
        });
    }

    public ChannelTarget promoteStable(String applicationCode, String releaseId, Long expectedVersion,
                                       String actor, String operationId) {
        validate(applicationCode, releaseId, actor, operationId);
        return transactions.execute(status -> {
            var release = release(applicationCode, releaseId);
            var replay = channelTargetByOperation(operationId);
            if (!replay.isEmpty()) {
                var previous = replay.getFirst();
                if (previous.applicationId() != release.applicationId() || !previous.releaseId().equals(releaseId)) {
                    throw new IllegalStateException("Stable operation was already used for a different release");
                }
                return previous;
            }
            require(jdbc.queryForObject("""
                    SELECT count(*) FROM ab_application_release_publication
                    WHERE application_id=? AND release_id=?
                    """, Integer.class, release.applicationId(), releaseId) == 1,
                    "Only a published release can become stable");
            int changed;
            if (expectedVersion == null) {
                changed = jdbc.update("""
                        INSERT INTO ab_application_channel_target
                            (application_id,channel,release_id,updated_by,operation_id)
                        VALUES (?,'stable',?,?,?) ON CONFLICT (application_id,channel) DO NOTHING
                        """, release.applicationId(), releaseId, actor, operationId);
            } else {
                require(expectedVersion > 0, "Positive expected channel version required");
                changed = jdbc.update("""
                        UPDATE ab_application_channel_target
                        SET release_id=?,target_version=target_version+1,updated_by=?,operation_id=?
                        WHERE application_id=? AND channel='stable' AND target_version=?
                        """, releaseId, actor, operationId, release.applicationId(), expectedVersion);
            }
            var target = readStable(release.applicationId());
            if (changed == 0 && (!target.releaseId().equals(releaseId)
                    || expectedVersion != null)) throw new IllegalStateException("Stable channel version conflict");
            return target;
        });
    }

    public Binding bindStable(long tenantId, String applicationCode, String actor, String operationId) {
        require(tenantId > 0, "Positive tenant ID required");
        require(applicationCode != null && applicationCode.matches("[a-z][a-z0-9-]{1,99}"), "Invalid application code");
        validateActor(actor, operationId);
        return transactions.execute(status -> audited(actor, operationId, () -> {
            var target = publishedStable(applicationCode);
            bindingCapabilities.requireDeployed(applicationCode, target.releaseId(), target.digest());
            jdbc.update("""
                    INSERT INTO ab_tenant_application_binding
                        (tenant_id,application_id,current_release_id,status)
                    VALUES (?,?,?,'active') ON CONFLICT (tenant_id,application_id) DO NOTHING
                    """, tenantId, target.applicationId(), target.releaseId());
            var binding = readBinding(tenantId, target.applicationId());
            if (!binding.releaseId().equals(target.releaseId()) || !binding.status().equals("active")) {
                throw new IllegalStateException("Tenant already has a different application binding");
            }
            return binding;
        }));
    }

    /** Package-local CAS invoked after the controller proves an exact stable shadow match. */
    Binding activateStableShadow(long tenantId, String applicationCode, String expectedReleaseId,
                                 Long expectedVersion, String actor, String operationId) {
        require(tenantId > 0, "Positive tenant ID required");
        validate(applicationCode, expectedReleaseId, actor, operationId);
        require(expectedVersion != null && expectedVersion > 0, "Positive expected binding version required");
        return transactions.execute(status -> audited(actor, operationId, () -> {
            var target = publishedStable(applicationCode);
            bindingCapabilities.requireDeployed(applicationCode, target.releaseId(), target.digest());
            require(target.releaseId().equals(expectedReleaseId), "Expected release is no longer published stable");
            var replay = bindingActivationByOperation(tenantId, target.applicationId(), operationId);
            if (!replay.isEmpty()) {
                var previous = replay.getFirst();
                if (!previous.previousReleaseId().equals(expectedReleaseId)
                        || !"shadow".equals(previous.previousStatus())
                        || !previous.binding().releaseId().equals(expectedReleaseId)
                        || !"active".equals(previous.binding().status())
                        || previous.binding().version() != expectedVersion + 1) {
                    throw new IllegalStateException("Binding operation was already used for a different activation");
                }
                return previous.binding();
            }
            int changed = jdbc.update("""
                    UPDATE ab_tenant_application_binding
                    SET status='active',binding_version=binding_version+1
                    WHERE tenant_id=? AND application_id=? AND current_release_id=?
                      AND status='shadow' AND binding_version=?
                    """, tenantId, target.applicationId(), expectedReleaseId, expectedVersion);
            if (changed != 1) throw new IllegalStateException("Tenant binding version or shadow state conflict");
            return readBinding(tenantId, target.applicationId());
        }));
    }

    private <T> T audited(String actor, String operationId, java.util.function.Supplier<T> work) {
        String previousActor = jdbc.queryForObject("SELECT current_setting('aura.binding.actor',true)", String.class);
        String previousOperation = jdbc.queryForObject("SELECT current_setting('aura.binding.operation',true)", String.class);
        try {
            jdbc.queryForObject("SELECT set_config('aura.binding.actor',?,true)", String.class, actor);
            jdbc.queryForObject("SELECT set_config('aura.binding.operation',?,true)", String.class, operationId);
            return work.get();
        } finally {
            jdbc.queryForObject("SELECT set_config('aura.binding.actor',?,true)", String.class,
                    previousActor == null ? "" : previousActor);
            jdbc.queryForObject("SELECT set_config('aura.binding.operation',?,true)", String.class,
                    previousOperation == null ? "" : previousOperation);
        }
    }

    private record Release(long applicationId, String releaseId, String digest, int compatibilityEpoch) {}
    private record BindingActivation(Binding binding, String previousReleaseId, String previousStatus) {}

    private Release release(String applicationCode, String releaseId) {
        var releases = jdbc.query("""
                SELECT r.application_id,r.release_id,r.digest,r.compatibility_epoch
                FROM ab_application_release r JOIN ab_application a ON a.id=r.application_id
                WHERE a.code=? AND r.release_id=?
                """, (row, index) -> new Release(row.getLong(1), row.getString(2), row.getString(3), row.getInt(4)),
                applicationCode, releaseId);
        require(releases.size() == 1, "Registered application release is required");
        return releases.getFirst();
    }

    private Release publishedStable(String applicationCode) {
        var targets = jdbc.query("""
                SELECT a.id,t.release_id,r.digest,r.compatibility_epoch
                FROM ab_application a
                JOIN ab_application_channel_target t ON t.application_id=a.id AND t.channel='stable'
                JOIN ab_application_release r ON r.application_id=a.id AND r.release_id=t.release_id
                JOIN ab_application_release_publication p ON p.application_id=a.id AND p.release_id=t.release_id
                WHERE a.code=? FOR SHARE OF t
                """, (row, index) -> new Release(row.getLong(1), row.getString(2), row.getString(3), row.getInt(4)),
                applicationCode);
        require(targets.size() == 1, "Published stable release is required");
        return targets.getFirst();
    }

    private ChannelTarget readStable(long applicationId) {
        return jdbc.queryForObject("""
                SELECT application_id,channel,release_id,target_version,updated_by,operation_id
                FROM ab_application_channel_target WHERE application_id=? AND channel='stable'
                """, (row, index) -> new ChannelTarget(row.getLong(1), row.getString(2), row.getString(3),
                        row.getLong(4), row.getString(5), row.getString(6)), applicationId);
    }

    private java.util.List<Publication> publicationByOperation(String operationId) {
        return jdbc.query("""
                SELECT application_id,release_id,published_by,operation_id
                FROM ab_application_release_publication WHERE operation_id=?
                """, (row, index) -> new Publication(row.getLong(1), row.getString(2), row.getString(3), row.getString(4)),
                operationId);
    }

    private java.util.List<ChannelTarget> channelTargetByOperation(String operationId) {
        return jdbc.query("""
                SELECT application_id,channel,release_id,target_version,changed_by,operation_id
                FROM ab_application_channel_target_history WHERE operation_id=?
                """, (row, index) -> new ChannelTarget(row.getLong(1), row.getString(2), row.getString(3),
                        row.getLong(4), row.getString(5), row.getString(6)), operationId);
    }

    private java.util.List<BindingActivation> bindingActivationByOperation(
            long tenantId, long applicationId, String operationId) {
        return jdbc.query("""
                SELECT h.tenant_id,h.application_id,h.current_release_id,r.digest,r.compatibility_epoch,
                       h.status,h.binding_version,h.previous_release_id,h.previous_status
                FROM ab_tenant_application_binding_history h
                JOIN ab_application_release r
                  ON r.application_id=h.application_id AND r.release_id=h.current_release_id
                WHERE h.tenant_id=? AND h.application_id=? AND h.operation_id=?
                """, (row, index) -> new BindingActivation(
                        new Binding(row.getLong(1), row.getLong(2), row.getString(3), row.getString(4),
                                row.getInt(5), row.getString(6), row.getLong(7)),
                        row.getString(8), row.getString(9)), tenantId, applicationId, operationId);
    }

    private Binding readBinding(long tenantId, long applicationId) {
        return jdbc.queryForObject("""
                SELECT b.tenant_id,b.application_id,b.current_release_id,r.digest,r.compatibility_epoch,
                       b.status,b.binding_version
                FROM ab_tenant_application_binding b
                JOIN ab_application_release r ON r.application_id=b.application_id AND r.release_id=b.current_release_id
                WHERE b.tenant_id=? AND b.application_id=?
                """, (row, index) -> new Binding(row.getLong(1), row.getLong(2), row.getString(3), row.getString(4),
                        row.getInt(5), row.getString(6), row.getLong(7)), tenantId, applicationId);
    }

    private static void validate(String applicationCode, String releaseId, String actor, String operationId) {
        require(applicationCode != null && applicationCode.matches("[a-z][a-z0-9-]{1,99}"), "Invalid application code");
        require(releaseId != null && releaseId.matches("[0-9A-HJKMNP-TV-Z]{26}"), "Exact release ID required");
        validateActor(actor, operationId);
    }

    private static void validateActor(String actor, String operationId) {
        require(actor != null && actor.matches("(user|ci|system|test):[A-Za-z0-9._:-]{1,200}"),
                "Authenticated control actor required");
        require(operationId != null && operationId.matches("[0-9A-HJKMNP-TV-Z]{26}"),
                "Exact control operation ID required");
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalArgumentException(message);
    }
}
