package com.auraboot.framework.plugin.extension;

import java.time.Instant;
import java.util.List;

/**
 * Host-owned exact-version recipient proof for an authenticated business command.
 * Tenant and actor come only from the current host context. This bridge neither
 * marks a version read nor records acknowledgement and never grants an audience.
 */
public interface AnnouncementAcknowledgementAccessor {
    String SETTINGS_KEY = "__announcementAcknowledgementAccessor";

    /**
     * Requires the caller's existing writable Core transaction. The host locks the
     * current member/user and announcement roots until that transaction ends,
     * orders aggregate locks consistently and revalidates satisfied facts before commit.
     * A result is proof only when status is SATISFIED; ordinary reading is not
     * acknowledgement, and NOT_REQUIRED must not satisfy an explicit-ack gate.
     * The caller persists these exact identities with its business revision.
     */
    List<Check> checkForCommand(List<Requirement> requirements);

    record Requirement(String versionPid, String snapshotHash, String memberPid) {
        public Requirement {
            if (versionPid == null || versionPid.isBlank() || versionPid.length() > 64
                    || memberPid == null || memberPid.isBlank() || memberPid.length() > 64
                    || snapshotHash == null || !snapshotHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Exact public version, snapshot and member identities are required");
            }
        }
    }

    enum Status {
        SATISFIED, MISSING, EXPIRED, WITHDRAWN, SUPERSEDED, MEMBER_CHANGED,
        NOT_VISIBLE, NOT_ACKNOWLEDGED, NOT_REQUIRED, SNAPSHOT_CHANGED
    }

    record Check(String requestedVersionPid, String activeVersionPid, String snapshotHash,
                 Status status, boolean effective, boolean visible, boolean memberIncarnationMatch,
                 boolean acknowledgementRequired, Instant acknowledgedAt, String acknowledgedHash,
                 Instant checkedAt, Instant validUntil) {
        public Check {
            if (requestedVersionPid == null || requestedVersionPid.isBlank() || status == null || checkedAt == null) {
                throw new IllegalArgumentException("Incomplete acknowledgement proof");
            }
            if (status == Status.SATISFIED && (!effective || !visible || !memberIncarnationMatch
                    || !acknowledgementRequired || acknowledgedAt == null || snapshotHash == null
                    || !snapshotHash.matches("[0-9a-f]{64}") || acknowledgedAt.isAfter(checkedAt)
                    || !snapshotHash.equals(acknowledgedHash) || !requestedVersionPid.equals(activeVersionPid)
                    || validUntil != null && !checkedAt.isBefore(validUntil))) {
                throw new IllegalArgumentException("Satisfied acknowledgement proof is inconsistent");
            }
        }
    }
}
