package com.auraboot.framework.plugin.extension;

import java.time.Instant;

/** Publisher-only proof of official exact sealed content in the original command transaction. */
public interface AnnouncementSealedContentAccessor {
    String SETTINGS_KEY = "__announcementSealedContentAccessor";

    record ExactVersion(String announcementPid, String versionPid, String expectedSnapshotHash, String projectionSchemaId) {
        public ExactVersion {
            AnnouncementDocumentCanonicalizer.requirePid(announcementPid);
            AnnouncementDocumentCanonicalizer.requirePid(versionPid);
            AnnouncementDocumentCanonicalizer.requireHash(expectedSnapshotHash);
            if (!AnnouncementDocumentCanonicalizer.SCHEMA.equals(projectionSchemaId))
                throw new IllegalArgumentException("Unsupported sealed document schema");
        }
    }
    record ApprovedBinding(String submissionPid, String terminalResultPid, String definitionPid,
                           int definitionVersion, String definitionHash) {}
    record PublishedContent(ExactVersion exactVersion, String snapshotSchema, String snapshotHash,
                            AnnouncementDocumentCanonicalizer.Document document,
                            AnnouncementDocumentCanonicalizer.Encoded encoded,
                            ApprovedBinding approvedBinding, String sealIdentity, String actorMemberPid,
                            Instant checkedAt, Instant validUntil) {}

    /**
     * Requires publisher functionality and owner/manage scope; recipient acknowledgement is separate.
     * The host never backfills missing historical seal evidence or opens an independent transaction.
     */
    PublishedContent requirePublishedContentForCommand(ExactVersion expected);
}
