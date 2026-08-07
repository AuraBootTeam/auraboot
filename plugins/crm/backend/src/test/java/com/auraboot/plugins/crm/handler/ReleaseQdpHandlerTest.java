package com.auraboot.plugins.crm.handler;

import com.auraboot.framework.plugin.extension.CommandHandlerExtension.CommandContext;
import com.auraboot.framework.plugin.extension.DataAccessor;
import com.auraboot.framework.plugin.extension.FileAccessor;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseQdpHandlerTest {

    private static final String TEST_CLIENT_REQUEST_ID = "__testClientRequestId";
    private static final String TEST_EXPECTED_VERSION = "__testExpectedVersion";
    private final ReleaseQdpHandler handler = new ReleaseQdpHandler();

    @Test
    void releasesImmutableRevisionAndLinksOnlyItsReferenceToQualifiedPcbaSidecar() throws Exception {
        FakeDataAccessor db = eligibleRequestWithPcbaSidecar();
        FakeFileAccessor files = new FakeFileAccessor()
                .put("file-bom", "bom-v1")
                .put("file-gerber", "gerber-v1");
        Map<String, Object> payload = payload("release-1", "file-bom", "customer-bom.xlsx");
        payload.put("crm_qdp_file_manifest", List.of(
                fileRef("file-gerber", "controller.gbr", "gerber"),
                fileRef("file-bom", "customer-bom.xlsx", "bom")));
        payload.put("crm_qdp_primary_file_id", null);

        Map<String, Object> result = execute(db, files, 101L, "user-501", payload);

        assertEquals(false, result.get("idempotent"));
        assertEquals(1, result.get("revision"));
        assertEquals("QDP-PID1", result.get("qdpRevisionId"));
        assertEquals(64, String.valueOf(result.get("contentHash")).length());

        Map<String, Object> revision = db.only("crm_qdp_revision_common");
        assertEquals("request-1", revision.get("crm_qdp_customer_request_id"));
        assertEquals(1, revision.get("crm_qdp_revision_no"));
        assertEquals(1, revision.get("crm_qdp_schema_version"));
        assertEquals("QDP-CR-0001-R0001", revision.get("crm_qdp_code"));
        assertEquals("released", revision.get("crm_qdp_status"));
        assertEquals("tenant:101/customer-request:request-1", revision.get("crm_qdp_owner_scope"));
        assertEquals("user-501", revision.get("crm_qdp_released_by"));
        assertNotNull(revision.get("crm_qdp_released_at"));
        assertEquals(result.get("contentHash"), revision.get("crm_qdp_content_hash"));

        @SuppressWarnings("unchecked")
        Map<String, Object> snapshot = (Map<String, Object>) revision.get("crm_qdp_request_snapshot");
        assertEquals("request-1", snapshot.get("customerRequestId"));
        assertEquals("7", snapshot.get("requestVersion"));
        assertEquals("Controller RFQ", snapshot.get("title"));
        assertEquals("rfq-1", snapshot.get("sourceContextId"));
        assertEquals("passed", snapshot.get("qualificationStatus"));

        @SuppressWarnings("unchecked")
        Map<String, Object> manifest = (Map<String, Object>) revision.get("crm_qdp_file_manifest");
        assertEquals("tenant:101/customer-request:request-1", manifest.get("ownerScope"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> manifestFiles = (List<Map<String, Object>>) manifest.get("files");
        assertEquals(List.of("file-bom", "file-gerber"),
                manifestFiles.stream().map(file -> String.valueOf(file.get("filePid"))).toList());
        assertTrue(manifestFiles.stream().allMatch(file -> String.valueOf(file.get("sha256")).length() == 64));
        assertEquals("file-bom.bin, file-gerber.bin", revision.get("crm_qdp_file_names"));

        Map<String, Object> sidecar = db.getById("crm_customer_request_pcba_rfq", "rfq-1");
        assertEquals("QDP-PID1", sidecar.get("crm_crq_qdp_revision_id"));
        assertFalse(sidecar.containsKey("crm_qdp_content_hash"));
        assertFalse(sidecar.containsKey("crm_qdp_file_manifest"));
        assertEquals(List.of("file-bom", "file-gerber"), files.retainAttempts(),
                "every canonical manifest file must be retained before the release is committed");
    }

    @Test
    void canonicalizesFileOrderAndScopesIdempotencyToTheSuppliedKey() throws Exception {
        FakeDataAccessor db = eligibleRequestWithPcbaSidecar();
        FakeFileAccessor files = new FakeFileAccessor()
                .put("file-a", "A")
                .put("file-b", "B")
                .put("file-c", "C");
        Map<String, Object> first = payload("same-release", null, null);
        first.put("crm_qdp_file_manifest", List.of(
                fileRef("file-b", "b.zip", "design"),
                fileRef("file-a", "a.xlsx", "bom")));
        Map<String, Object> reordered = payload("same-release", null, null);
        reordered.put("crm_qdp_file_manifest", List.of(
                fileRef("file-a", "a.xlsx", "bom"),
                fileRef("file-b", "b.zip", "design")));

        Map<String, Object> released = execute(db, files, 101L, "user-501", first);
        Map<String, Object> replayed = execute(db, files, 101L, "user-501", reordered);
        reordered.put(TEST_CLIENT_REQUEST_ID, "different-key-same-content");
        Map<String, Object> sameContent = execute(db, files, 101L, "user-501", reordered);

        assertEquals(released.get("qdpRevisionId"), replayed.get("qdpRevisionId"));
        assertEquals(released.get("contentHash"), replayed.get("contentHash"));
        assertEquals(true, replayed.get("idempotent"));
        assertNotEquals(released.get("qdpRevisionId"), sameContent.get("qdpRevisionId"));
        assertEquals(false, sameContent.get("idempotent"));
        assertEquals(2, db.rows("crm_qdp_revision_common").size());

        Map<String, Object> changedReuse = payload("different-key-same-content", "file-c", "c.zip");
        IllegalStateException reusedKey = assertThrows(IllegalStateException.class,
                () -> execute(db, files, 101L, "user-501", changedReuse));
        assertTrue(reusedKey.getMessage().contains("idempotency conflict"));
    }

    @Test
    void persistentSameKeyReplayUsesAnExactLookupBeyondTheRuntimeQueryPageCap() throws Exception {
        FakeDataAccessor db = eligibleRequestWithPcbaSidecar();
        for (int revision = 1; revision <= 10_000; revision++) {
            db.seed("crm_qdp_revision_common", row(
                    "pid", "historical-qdp-" + revision,
                    "crm_qdp_customer_request_id", "request-1",
                    "crm_qdp_revision_no", revision,
                    "crm_qdp_client_request_id", "historical-key-" + revision,
                    "crm_qdp_content_hash", "historical-hash-" + revision));
        }
        FakeFileAccessor files = new FakeFileAccessor().put("file-1", "v1");
        Map<String, Object> release = payload(
                "replay-beyond-query-cap", "file-1", "qdp.zip");

        Map<String, Object> created = execute(db, files, 101L, "user-501", release);
        assertEquals(10_001, created.get("revision"));
        db.queryLimit(10_000);

        Map<String, Object> replay = execute(db, files, 101L, "user-501", release);

        assertEquals(created.get("qdpRevisionId"), replay.get("qdpRevisionId"));
        assertEquals(true, replay.get("idempotent"));
        assertEquals(10_001, db.rows("crm_qdp_revision_common").size(),
                "a page-capped aggregate scan must not hide an existing idempotency key");
    }

    @Test
    void sameKeyCannotSwitchThePrimaryFileInsideAnOtherwiseIdenticalManifest() throws Exception {
        FakeDataAccessor db = eligibleRequestWithPcbaSidecar();
        FakeFileAccessor files = new FakeFileAccessor().put("file-a", "A").put("file-b", "B");
        List<Map<String, Object>> manifest = List.of(
                fileRef("file-a", "a.xlsx", "bom"),
                fileRef("file-b", "b.gbr", "gerber"));

        Map<String, Object> first = payload("same-key-primary-switch", "file-a", null);
        first.put("crm_qdp_file_manifest", manifest);
        execute(db, files, 101L, "user-501", first);

        Map<String, Object> switched = payload("same-key-primary-switch", "file-b", null);
        switched.put("crm_qdp_file_manifest", manifest);
        IllegalStateException conflict = assertThrows(IllegalStateException.class,
                () -> execute(db, files, 101L, "user-501", switched));

        assertTrue(conflict.getMessage().contains("idempotency conflict"));
        assertEquals("file-a", db.only("crm_qdp_revision_common")
                .get("crm_qdp_primary_file_id"));
    }

    @Test
    void replayingAnOlderIdempotencyKeyNeverRegressesThePcbaCurrentRevisionReference() throws Exception {
        FakeDataAccessor db = eligibleRequestWithPcbaSidecar();
        FakeFileAccessor files = new FakeFileAccessor().put("file-a", "A").put("file-b", "B");

        Map<String, Object> first = execute(db, files, 101L, "user-501",
                payload("release-a", "file-a", "a.zip"));
        Map<String, Object> second = execute(db, files, 101L, "user-501",
                payload("release-b", "file-b", "b.zip"));
        Map<String, Object> replay = execute(db, files, 101L, "user-501",
                payload("release-a", "file-a", "a.zip"));

        assertEquals(first.get("qdpRevisionId"), replay.get("qdpRevisionId"));
        assertEquals(true, replay.get("idempotent"));
        assertEquals(second.get("qdpRevisionId"), db.getById("crm_customer_request_pcba_rfq", "rfq-1")
                .get("crm_crq_qdp_revision_id"));
    }

    @Test
    void replayCannotRegressANewerSidecarReferenceWrittenWhileItsFileIsHashed() throws Exception {
        FakeDataAccessor db = eligibleRequestWithPcbaSidecar();
        Map<String, Object> release = payload("release-race", "file-a", "a.zip");
        execute(db, new FakeFileAccessor().put("file-a", "A"),
                101L, "user-501", release);

        Map<String, Object> sidecar = db.getById("crm_customer_request_pcba_rfq", "rfq-1");
        sidecar.put("crm_crq_qdp_revision_id", null);
        FakeFileAccessor replayFiles = new FakeFileAccessor()
                .put("file-a", "A")
                .onOpen(() -> sidecar.put("crm_crq_qdp_revision_id", "QDP-NEWER"));

        Map<String, Object> replay = execute(db, replayFiles, 101L, "user-501", release);

        assertEquals(true, replay.get("idempotent"));
        assertEquals("QDP-NEWER", sidecar.get("crm_crq_qdp_revision_id"),
                "an idempotent replay must never mutate monotonic sidecar state");
    }

    @Test
    void sameKeyWithChangedContentConflictsButNewKeyCreatesNextRevision() throws Exception {
        FakeDataAccessor db = eligibleRequestWithPcbaSidecar();
        FakeFileAccessor files = new FakeFileAccessor().put("file-v1", "v1").put("file-v2", "v2");

        Map<String, Object> first = execute(db, files, 101L, "user-501",
                payload("release-key", "file-v1", "qdp-v1.zip"));

        IllegalStateException conflict = assertThrows(IllegalStateException.class,
                () -> execute(db, files, 101L, "user-501",
                        payload("release-key", "file-v2", "qdp-v2.zip")));
        assertTrue(conflict.getMessage().contains("idempotency conflict"));
        assertEquals(1, db.rows("crm_qdp_revision_common").size());

        Map<String, Object> next = execute(db, files, 101L, "user-501",
                payload("release-key-2", "file-v2", "qdp-v2.zip"));
        assertEquals(2, next.get("revision"));
        assertNotEquals(first.get("qdpRevisionId"), next.get("qdpRevisionId"));
        assertNotEquals(first.get("contentHash"), next.get("contentHash"));
        assertEquals(2, db.rows("crm_qdp_revision_common").size());
    }

    @Test
    void rejectsMissingOrInaccessibleSourceFile() {
        FakeDataAccessor db = eligibleRequestWithPcbaSidecar();
        FakeFileAccessor files = new FakeFileAccessor();

        IllegalArgumentException missingReference = assertThrows(IllegalArgumentException.class,
                () -> execute(db, files, 101L, "user-501", payload("missing-ref", null, null)));
        assertTrue(missingReference.getMessage().contains("source file"));

        IllegalArgumentException inaccessible = assertThrows(IllegalArgumentException.class,
                () -> execute(db, files, 101L, "user-501",
                        payload("missing-file", "not-found", "missing.zip")));
        assertTrue(inaccessible.getMessage().contains("metadata"));
        assertTrue(db.rows("crm_qdp_revision_common").isEmpty());
    }

    @Test
    void rejectsManifestCountPurposeDuplicateAndByteLimitAbuseBeforeHashing() {
        FakeFileAccessor files = new FakeFileAccessor().put("file-1", "v1");

        Map<String, Object> tooMany = payload("too-many-files", null, null);
        List<Map<String, Object>> excessiveRefs = new ArrayList<>();
        for (int index = 0; index < 21; index++) {
            excessiveRefs.add(fileRef("file-" + index, "ignored.bin", "source"));
        }
        tooMany.put("crm_qdp_file_manifest", excessiveRefs);
        IllegalArgumentException countFailure = assertThrows(IllegalArgumentException.class,
                () -> execute(eligibleRequestWithPcbaSidecar(), files, 101L, "user-501", tooMany));
        assertTrue(countFailure.getMessage().toLowerCase().contains("20"));

        Map<String, Object> longPurpose = payload("long-purpose", null, null);
        longPurpose.put("crm_qdp_file_manifest", List.of(
                fileRef("file-1", "ignored.bin", "x".repeat(65))));
        IllegalArgumentException purposeFailure = assertThrows(IllegalArgumentException.class,
                () -> execute(eligibleRequestWithPcbaSidecar(), files, 101L, "user-501", longPurpose));
        assertTrue(purposeFailure.getMessage().toLowerCase().contains("purpose"));

        Map<String, Object> duplicate = payload("duplicate-pid", null, null);
        duplicate.put("crm_qdp_file_manifest", List.of(
                fileRef("file-1", "ignored.bin", "bom"),
                fileRef("file-1", "ignored.bin", "design")));
        IllegalArgumentException duplicateFailure = assertThrows(IllegalArgumentException.class,
                () -> execute(eligibleRequestWithPcbaSidecar(), files, 101L, "user-501", duplicate));
        assertTrue(duplicateFailure.getMessage().toLowerCase().contains("duplicate"));

        FakeFileAccessor oversized = new FakeFileAccessor()
                .put("file-large", "large.zip", "application/zip", "user-501", "success", "x")
                .metadata("file-large", new FileAccessor.FileMetadata(
                        "file-large", "large.zip", 50L * 1024 * 1024 + 1,
                        "application/zip", "user-501", "success"));
        IllegalArgumentException fileLimit = assertThrows(IllegalArgumentException.class,
                () -> execute(eligibleRequestWithPcbaSidecar(), oversized, 101L, "user-501",
                        payload("oversized", "file-large", "ignored.zip")));
        assertTrue(fileLimit.getMessage().toLowerCase().contains("50 mib"));

        FakeFileAccessor total = new FakeFileAccessor();
        List<Map<String, Object>> totalRefs = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            String filePid = "file-total-" + index;
            total.put(filePid, filePid + ".zip", "application/zip", "user-501", "success", "x")
                    .metadata(filePid, new FileAccessor.FileMetadata(
                            filePid, filePid + ".zip", 40L * 1024 * 1024,
                            "application/zip", "user-501", "success"));
            totalRefs.add(fileRef(filePid, "ignored.zip", "source"));
        }
        Map<String, Object> overTotal = payload("over-total", null, null);
        overTotal.put("crm_qdp_file_manifest", totalRefs);
        IllegalArgumentException totalLimit = assertThrows(IllegalArgumentException.class,
                () -> execute(eligibleRequestWithPcbaSidecar(), total, 101L, "user-501", overTotal));
        assertTrue(totalLimit.getMessage().toLowerCase().contains("100 mib"));
    }

    @Test
    void freezesServerMetadataAndIgnoresSpoofedPayloadFilename() throws Exception {
        FakeDataAccessor db = eligibleRequestWithPcbaSidecar();
        FakeFileAccessor files = new FakeFileAccessor().put(
                "file-1",
                "server-approved-qdp.zip",
                "application/zip",
                "user-501",
                "success",
                "trusted-bytes");

        execute(db, files, 101L, "user-501",
                payload("server-metadata", "file-1", "spoofed-customer-name.exe"));

        Map<String, Object> revision = db.only("crm_qdp_revision_common");
        assertEquals("server-approved-qdp.zip", revision.get("crm_qdp_primary_filename"));
        @SuppressWarnings("unchecked")
        Map<String, Object> manifest = (Map<String, Object>) revision.get("crm_qdp_file_manifest");
        @SuppressWarnings("unchecked")
        Map<String, Object> frozen = ((List<Map<String, Object>>) manifest.get("files")).getFirst();
        assertEquals("server-approved-qdp.zip", frozen.get("fileName"));
        assertEquals("application/zip", frozen.get("contentType"));
        assertEquals((long) "trusted-bytes".getBytes(StandardCharsets.UTF_8).length, frozen.get("size"));
        assertFalse(frozen.containsValue("spoofed-customer-name.exe"));
    }

    @Test
    void acceptsActorOwnedFilesAndRejectsForgedRequestRelationsOrUnownedFiles() throws Exception {
        FakeDataAccessor actorOwnedDb = eligibleRequestWithPcbaSidecar();
        FakeFileAccessor actorOwned = new FakeFileAccessor().put(
                "file-owned", "owned.zip", "application/zip", "user-501", "success", "owned");
        assertDoesNotThrow(() -> execute(actorOwnedDb, actorOwned, 101L, "user-501",
                payload("actor-owned", "file-owned", "ignored.zip")));

        FakeDataAccessor linkedDb = eligibleRequestWithPcbaSidecar();
        FakeFileAccessor requestLinked = new FakeFileAccessor()
                .put("file-linked", "linked.zip", "application/zip", "another-user", "success", "linked")
                .link("file-linked", "crm_customer_request_common", "request-1",
                        "crm_qdp_primary_file_id");
        IllegalArgumentException forgedRelation = assertThrows(IllegalArgumentException.class,
                () -> execute(linkedDb, requestLinked, 101L, "user-501",
                        payload("request-linked", "file-linked", "ignored.zip")));
        assertTrue(forgedRelation.getMessage().toLowerCase().contains("owner"));
        assertTrue(linkedDb.rows("crm_qdp_revision_common").isEmpty());

        FakeDataAccessor unownedDb = eligibleRequestWithPcbaSidecar();
        FakeFileAccessor unowned = new FakeFileAccessor().put(
                "file-unowned", "unowned.zip", "application/zip", "another-user", "success", "unowned");
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> execute(unownedDb, unowned, 101L, "user-501",
                        payload("unowned", "file-unowned", "ignored.zip")));
        assertTrue(rejected.getMessage().toLowerCase().contains("owner"));
        assertTrue(unownedDb.rows("crm_qdp_revision_common").isEmpty());
    }

    @Test
    void failsClosedWhenMetadataIsMissingUnfinalizedAliasedOrErrors() {
        FakeDataAccessor missingDb = eligibleRequestWithPcbaSidecar();
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> execute(missingDb, new FakeFileAccessor(), 101L, "user-501",
                        payload("missing-metadata", "file-missing", "ignored.zip")));
        assertTrue(missing.getMessage().toLowerCase().contains("metadata"));

        for (String status : List.of("uploading", "failed", "deleted", "inactive")) {
            FakeDataAccessor rejectedDb = eligibleRequestWithPcbaSidecar();
            FakeFileAccessor rejectedFile = new FakeFileAccessor().put(
                    "file-" + status, status + ".zip", "application/zip", "user-501", status, "bytes");
            IllegalArgumentException statusError = assertThrows(IllegalArgumentException.class,
                    () -> execute(rejectedDb, rejectedFile, 101L, "user-501",
                            payload("status-" + status, "file-" + status, "ignored.zip")));
            assertTrue(statusError.getMessage().toLowerCase().contains("finalized multipart"));
        }
        FakeDataAccessor nullStatusDb = eligibleRequestWithPcbaSidecar();
        FakeFileAccessor nullStatus = new FakeFileAccessor().put(
                "file-null-status", "null.zip", "application/zip", "user-501", null, "bytes");
        IllegalArgumentException nullStatusError = assertThrows(IllegalArgumentException.class,
                () -> execute(nullStatusDb, nullStatus, 101L, "user-501",
                        payload("null-status", "file-null-status", "ignored.zip")));
        assertTrue(nullStatusError.getMessage().toLowerCase().contains("finalized multipart"));

        FakeDataAccessor aliasDb = eligibleRequestWithPcbaSidecar();
        FakeFileAccessor alias = new FakeFileAccessor()
                .put("internal-id-42", "alias.zip", "application/zip", "user-501", "success", "bytes")
                .failDescribe("internal-id-42", new IllegalArgumentException("stable public pid required"));
        IllegalArgumentException aliasError = assertThrows(IllegalArgumentException.class,
                () -> execute(aliasDb, alias, 101L, "user-501",
                        payload("alias", "internal-id-42", "ignored.zip")));
        assertTrue(aliasError.getMessage().toLowerCase().contains("metadata"));

        FakeDataAccessor errorDb = eligibleRequestWithPcbaSidecar();
        FakeFileAccessor error = new FakeFileAccessor()
                .put("file-error", "error.zip", "application/zip", "user-501", "success", "bytes")
                .failDescribe("file-error", new IllegalStateException("metadata backend unavailable"));
        IllegalArgumentException serviceError = assertThrows(IllegalArgumentException.class,
                () -> execute(errorDb, error, 101L, "user-501",
                        payload("metadata-error", "file-error", "ignored.zip")));
        assertTrue(serviceError.getMessage().toLowerCase().contains("metadata"));

        FakeDataAccessor identityDb = eligibleRequestWithPcbaSidecar();
        FakeFileAccessor identityMismatch = new FakeFileAccessor()
                .put("file-public", "identity.zip", "application/zip", "user-501", "success", "bytes")
                .metadata("file-public", new FileAccessor.FileMetadata(
                        "internal-id-99", "identity.zip", 5L, "application/zip", "user-501", "success"));
        IllegalArgumentException identityError = assertThrows(IllegalArgumentException.class,
                () -> execute(identityDb, identityMismatch, 101L, "user-501",
                        payload("identity-mismatch", "file-public", "ignored.zip")));
        assertTrue(identityError.getMessage().toLowerCase().contains("identity"));
    }

    @Test
    void acceptsOnlyFinalizedMultipartUploadsAndRejectsMetadataOnlyActiveRows() throws Exception {
        FakeDataAccessor finalizedDb = eligibleRequestWithPcbaSidecar();
        FakeFileAccessor finalized = new FakeFileAccessor().put(
                "file-success", "success.zip", "application/zip", "user-501", "success", "bytes");
        assertDoesNotThrow(() -> execute(finalizedDb, finalized, 101L, "user-501",
                payload("readable-success", "file-success", "ignored.zip")));

        FakeDataAccessor forgedMetadataDb = eligibleRequestWithPcbaSidecar();
        FakeFileAccessor metadataOnly = new FakeFileAccessor().put(
                "file-active", "active.zip", "application/zip", "user-501", "active", "bytes");
        IllegalArgumentException active = assertThrows(IllegalArgumentException.class,
                () -> execute(forgedMetadataDb, metadataOnly, 101L, "user-501",
                        payload("metadata-only-active", "file-active", "ignored.zip")));
        assertTrue(active.getMessage().toLowerCase().contains("finalized multipart"));
        assertTrue(forgedMetadataDb.rows("crm_qdp_revision_common").isEmpty());
    }

    @Test
    void failsClosedWhenMetadataSizeDoesNotMatchBytes() {
        FakeDataAccessor sizeDb = eligibleRequestWithPcbaSidecar();
        FakeFileAccessor sizeMismatch = new FakeFileAccessor()
                .put("file-size", "size.zip", "application/zip", "user-501", "success", "bytes")
                .metadata("file-size", new FileAccessor.FileMetadata(
                        "file-size", "size.zip", 99L, "application/zip", "user-501", "success"));
        IllegalArgumentException sizeFailure = assertThrows(IllegalArgumentException.class,
                () -> execute(sizeDb, sizeMismatch, 101L, "user-501",
                        payload("size-mismatch", "file-size", "ignored.zip")));
        assertTrue(sizeFailure.getMessage().toLowerCase().contains("size"));
        assertTrue(sizeMismatch.retainAttempts().isEmpty());

        String oversizedBytes = "x".repeat(64 * 1024);
        FakeDataAccessor oversizedDb = eligibleRequestWithPcbaSidecar();
        FakeFileAccessor oversizedStream = new FakeFileAccessor()
                .put("file-oversized-stream", "oversized.zip", "application/zip",
                        "user-501", "success", oversizedBytes)
                .metadata("file-oversized-stream", new FileAccessor.FileMetadata(
                        "file-oversized-stream", "oversized.zip", 1L,
                        "application/zip", "user-501", "success"));
        IllegalArgumentException oversizedFailure = assertThrows(IllegalArgumentException.class,
                () -> execute(oversizedDb, oversizedStream, 101L, "user-501",
                        payload("oversized-stream", "file-oversized-stream", "ignored.zip")));
        assertTrue(oversizedFailure.getMessage().toLowerCase().contains("size"));
        assertTrue(oversizedStream.bytesRead("file-oversized-stream") < oversizedBytes.length(),
                "a stream that exceeds trusted metadata must fail before reading the whole object");
        assertTrue(oversizedStream.retainAttempts().isEmpty());
        assertTrue(oversizedDb.rows("crm_qdp_revision_common").isEmpty());
        assertFalse(oversizedDb.getById("crm_customer_request_pcba_rfq", "rfq-1")
                .containsKey("crm_crq_qdp_revision_id"));
    }

    @Test
    void rejectsInvalidRequestStateAndUnqualifiedPcbaSidecarWithoutTrustingItsRevision() throws Exception {
        FakeFileAccessor files = new FakeFileAccessor().put("file-1", "v1");

        FakeDataAccessor draft = eligibleRequestWithPcbaSidecar();
        draft.getById("crm_customer_request_common", "request-1").put("crm_cr_status", "draft");
        IllegalArgumentException invalidState = assertThrows(IllegalArgumentException.class,
                () -> execute(draft, files, 101L, "user-501", payload("draft", "file-1", "qdp.zip")));
        assertTrue(invalidState.getMessage().contains("cannot release QDP"));

        FakeDataAccessor submitted = eligibleRequestWithPcbaSidecar();
        submitted.getById("crm_customer_request_common", "request-1")
                .put("crm_cr_status", "submitted");
        IllegalArgumentException unroutedState = assertThrows(IllegalArgumentException.class,
                () -> execute(submitted, files, 101L, "user-501",
                        payload("submitted", "file-1", "qdp.zip")));
        assertTrue(unroutedState.getMessage().contains("cannot release QDP"));

        FakeDataAccessor unqualified = eligibleRequestWithPcbaSidecar();
        unqualified.getById("crm_customer_request_pcba_rfq", "rfq-1")
                .put("crm_crq_dfm_status", "in_review");
        IllegalArgumentException qualification = assertThrows(IllegalArgumentException.class,
                () -> execute(unqualified, files, 101L, "user-501", payload("unqualified", "file-1", "qdp.zip")));
        assertTrue(qualification.getMessage().contains("qualification"));

        FakeDataAccessor missingSourceRevision = eligibleRequestWithPcbaSidecar();
        missingSourceRevision.getById("crm_customer_request_pcba_rfq", "rfq-1")
                .put("crm_crq_revision", null);
        Map<String, Object> spoofedSourceRevision = payload(
                "missing-source-version", "file-1", "qdp.zip");
        spoofedSourceRevision.put("crm_qdp_source_revision", "attacker-controlled-revision");
        assertDoesNotThrow(() -> execute(missingSourceRevision, files, 101L, "user-501",
                spoofedSourceRevision));
        assertEquals("7", missingSourceRevision.only("crm_qdp_revision_common")
                .get("crm_qdp_source_revision"));
    }

    @Test
    void discoversPcbaSidecarServerSideAndUsesPayloadPidOnlyToDisambiguate() throws Exception {
        FakeFileAccessor files = new FakeFileAccessor().put("file-1", "v1");

        FakeDataAccessor unqualified = eligibleRequestWithPcbaSidecar();
        unqualified.getById("crm_customer_request_pcba_rfq", "rfq-1")
                .put("crm_crq_dfm_status", "in_review");
        Map<String, Object> omittedUnqualified = payload("omitted-unqualified", "file-1", "ignored.zip");
        omittedUnqualified.remove("crm_qdp_pcba_rfq_id");
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> execute(unqualified, files, 101L, "user-501", omittedUnqualified));
        assertTrue(rejected.getMessage().contains("qualification"));

        FakeDataAccessor qualified = eligibleRequestWithPcbaSidecar();
        Map<String, Object> omittedQualified = payload("omitted-qualified", "file-1", "ignored.zip");
        omittedQualified.remove("crm_qdp_pcba_rfq_id");
        Map<String, Object> released = execute(qualified, files, 101L, "user-501", omittedQualified);
        assertEquals("QDP-PID1", released.get("qdpRevisionId"));
        assertEquals("QDP-PID1", qualified.getById("crm_customer_request_pcba_rfq", "rfq-1")
                .get("crm_crq_qdp_revision_id"));

        FakeDataAccessor mismatchDb = eligibleRequestWithPcbaSidecar();
        mismatchDb.seed("crm_customer_request_pcba_rfq", row(
                "pid", "rfq-2",
                "crm_customer_request_id", "request-1",
                "crm_crq_revision", "A",
                "crm_crq_dfm_status", "passed",
                "crm_crq_qualification_evidence_refs", List.of("evidence-public-pid-2")));
        Map<String, Object> mismatchedPayload = payload("mismatched-route", "file-1", "ignored.zip");
        mismatchedPayload.put("crm_qdp_pcba_rfq_id", "rfq-2");
        IllegalArgumentException mismatch = assertThrows(IllegalArgumentException.class,
                () -> execute(mismatchDb, files, 101L, "user-501", mismatchedPayload));
        assertTrue(mismatch.getMessage().toLowerCase().contains("routed"));

        Map<String, Object> disambiguated = payload("disambiguated", "file-1", "ignored.zip");
        disambiguated.put("crm_qdp_pcba_rfq_id", "rfq-1");
        assertDoesNotThrow(() -> execute(mismatchDb, files, 101L, "user-501", disambiguated));
    }

    @Test
    void rejectsAbsentFailedOrWrongPcbaRouteBeforeQueryingAnUninstalledSidecarModel() {
        FakeDataAccessor db = eligibleRequestWithPcbaSidecar();
        db.removeModel("crm_customer_request_pcba_rfq");
        Map<String, Object> unroutedRequest = db.getById("crm_customer_request_common", "request-1");
        unroutedRequest.remove("crm_cr_routed_package");
        unroutedRequest.remove("crm_cr_routed_object_type");
        unroutedRequest.remove("crm_cr_routed_object_id");
        db.failQuery("crm_customer_request_pcba_rfq");

        Map<String, Object> commonPayload = payload("common-only", "file-1", "ignored.zip");
        commonPayload.remove("crm_qdp_pcba_rfq_id");

        IllegalStateException absentRoute = assertThrows(IllegalStateException.class,
                () -> execute(db, new FakeFileAccessor().put("file-1", "v1"),
                        101L, "user-501", commonPayload));
        assertTrue(absentRoute.getMessage().contains("authoritative PCBA route"));
        assertTrue(db.rows("crm_qdp_revision_common").isEmpty());

        FakeDataAccessor wrongRoute = eligibleRequestWithPcbaSidecar();
        Map<String, Object> wrongRequest = wrongRoute.getById(
                "crm_customer_request_common", "request-1");
        wrongRequest.put("crm_cr_routed_package", "other-industry");
        wrongRequest.put("crm_cr_routed_object_type", "other_request_sidecar");
        wrongRequest.put("crm_cr_routed_object_id", "other-1");
        IllegalStateException wrongIndustry = assertThrows(IllegalStateException.class,
                () -> execute(wrongRoute, new FakeFileAccessor().put("file-1", "v1"),
                        101L, "user-501", commonPayload));
        assertTrue(wrongIndustry.getMessage().contains("authoritative PCBA route"));
        assertTrue(wrongRoute.rows("crm_qdp_revision_common").isEmpty());

        for (String routeStatus : List.of("unrouted", "failed", "closed")) {
            FakeDataAccessor staleRoute = eligibleRequestWithPcbaSidecar();
            staleRoute.getById("crm_customer_request_common", "request-1")
                    .put("crm_cr_route_status", routeStatus);
            IllegalStateException staleRouteFacts = assertThrows(IllegalStateException.class,
                    () -> execute(staleRoute, new FakeFileAccessor().put("file-1", "v1"),
                            101L, "user-501",
                            payload(routeStatus + "-route", "file-1", "ignored.zip")));
            assertTrue(staleRouteFacts.getMessage().contains("authoritative PCBA route"));
            assertTrue(staleRoute.rows("crm_qdp_revision_common").isEmpty());
        }

        for (String missingFact : List.of(
                "crm_cr_route_status",
                "crm_cr_routed_package",
                "crm_cr_routed_object_type",
                "crm_cr_routed_object_id")) {
            FakeDataAccessor incompleteRoute = eligibleRequestWithPcbaSidecar();
            incompleteRoute.getById("crm_customer_request_common", "request-1")
                    .remove(missingFact);
            IllegalStateException incomplete = assertThrows(IllegalStateException.class,
                    () -> execute(incompleteRoute, new FakeFileAccessor().put("file-1", "v1"),
                            101L, "user-501",
                            payload("missing-" + missingFact, "file-1", "ignored.zip")));
            assertTrue(incomplete.getMessage().contains("authoritative PCBA route"));
            assertTrue(incompleteRoute.rows("crm_qdp_revision_common").isEmpty());
        }

        FakeDataAccessor blankRouteObject = eligibleRequestWithPcbaSidecar();
        blankRouteObject.getById("crm_customer_request_common", "request-1")
                .put("crm_cr_routed_object_id", "   ");
        IllegalStateException blankObject = assertThrows(IllegalStateException.class,
                () -> execute(blankRouteObject, new FakeFileAccessor().put("file-1", "v1"),
                        101L, "user-501", payload("blank-route-object", "file-1", "ignored.zip")));
        assertTrue(blankObject.getMessage().contains("authoritative PCBA route"));
        assertTrue(blankRouteObject.rows("crm_qdp_revision_common").isEmpty());
    }

    @Test
    void rejectsMissingCrossRequestOrMalformedPcbaQualificationAndAllowsPassedWithoutEvidence()
            throws Exception {
        FakeFileAccessor files = new FakeFileAccessor().put("file-1", "v1");

        FakeDataAccessor missingSidecar = eligibleRequestWithPcbaSidecar();
        missingSidecar.removeModel("crm_customer_request_pcba_rfq");
        IllegalArgumentException notFound = assertThrows(IllegalArgumentException.class,
                () -> execute(missingSidecar, files, 101L, "user-501",
                        payload("missing-sidecar", "file-1", "ignored.zip")));
        assertTrue(notFound.getMessage().contains("sidecar not found"));
        assertTrue(missingSidecar.rows("crm_qdp_revision_common").isEmpty());

        FakeDataAccessor crossRequest = eligibleRequestWithPcbaSidecar();
        crossRequest.getById("crm_customer_request_pcba_rfq", "rfq-1")
                .put("crm_customer_request_id", "request-2");
        IllegalArgumentException wrongParent = assertThrows(IllegalArgumentException.class,
                () -> execute(crossRequest, files, 101L, "user-501",
                        payload("cross-request-sidecar", "file-1", "ignored.zip")));
        assertTrue(wrongParent.getMessage().contains("another Customer Request"));
        assertTrue(crossRequest.rows("crm_qdp_revision_common").isEmpty());

        for (String invalidStatus : List.of("pending", "in_review", "failed", "unknown")) {
            FakeDataAccessor unqualified = eligibleRequestWithPcbaSidecar();
            unqualified.getById("crm_customer_request_pcba_rfq", "rfq-1")
                    .put("crm_crq_dfm_status", invalidStatus);
            IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                    () -> execute(unqualified, files, 101L, "user-501",
                            payload("dfm-" + invalidStatus, "file-1", "ignored.zip")));
            assertTrue(rejected.getMessage().contains("qualification"));
            assertTrue(unqualified.rows("crm_qdp_revision_common").isEmpty());
        }

        FakeDataAccessor missingStatus = eligibleRequestWithPcbaSidecar();
        missingStatus.getById("crm_customer_request_pcba_rfq", "rfq-1")
                .remove("crm_crq_dfm_status");
        IllegalArgumentException noStatus = assertThrows(IllegalArgumentException.class,
                () -> execute(missingStatus, files, 101L, "user-501",
                        payload("dfm-missing", "file-1", "ignored.zip")));
        assertTrue(noStatus.getMessage().contains("qualification"));

        for (Object malformedEvidence : List.of(
                List.of(" "),
                List.of("evidence-1", "evidence-1"),
                List.of(Map.of("pid", "evidence-1")),
                List.of(1),
                List.of(true),
                List.of("../evidence-1"),
                java.util.Collections.nCopies(101, "evidence-over-limit"),
                "not-json",
                Map.of("pid", "evidence-1"))) {
            FakeDataAccessor malformed = eligibleRequestWithPcbaSidecar();
            Map<String, Object> sidecar = malformed.getById(
                    "crm_customer_request_pcba_rfq", "rfq-1");
            sidecar.put("crm_crq_dfm_status", "conditional");
            sidecar.put("crm_crq_qualification_evidence_refs", malformedEvidence);
            assertThrows(IllegalArgumentException.class,
                    () -> execute(malformed, files, 101L, "user-501",
                            payload("malformed-evidence-" + malformedEvidence.hashCode(),
                                    "file-1", "ignored.zip")));
            assertTrue(malformed.rows("crm_qdp_revision_common").isEmpty());
        }

        FakeDataAccessor passedWithoutEvidence = eligibleRequestWithPcbaSidecar();
        passedWithoutEvidence.getById("crm_customer_request_pcba_rfq", "rfq-1")
                .remove("crm_crq_qualification_evidence_refs");
        assertDoesNotThrow(() -> execute(passedWithoutEvidence, files, 101L, "user-501",
                payload("passed-without-evidence", "file-1", "ignored.zip")));
    }

    @Test
    void requiresTrustedCustomerRequestVersionIgnoresPayloadSpoofingAndRequiresConditionalEvidence()
            throws Exception {
        FakeFileAccessor files = new FakeFileAccessor().put("file-1", "v1");

        FakeDataAccessor missingVersion = eligibleRequestWithPcbaSidecar();
        Map<String, Object> noExpectedVersion = payload("no-request-version", "file-1", "qdp.zip");
        noExpectedVersion.remove(TEST_EXPECTED_VERSION);
        IllegalStateException requiredVersion = assertThrows(IllegalStateException.class,
                () -> execute(missingVersion, files, 101L, "user-501", noExpectedVersion));
        assertTrue(requiredVersion.getMessage().toLowerCase().contains("request version"));

        FakeDataAccessor missingServerVersion = eligibleRequestWithPcbaSidecar();
        missingServerVersion.getById("crm_customer_request_common", "request-1")
                .remove("row_version");
        IllegalStateException unavailable = assertThrows(IllegalStateException.class,
                () -> execute(missingServerVersion, files, 101L, "user-501",
                        payload("missing-server-version", "file-1", "qdp.zip")));
        assertTrue(unavailable.getMessage().toLowerCase().contains("row version"));

        FakeDataAccessor staleRequest = eligibleRequestWithPcbaSidecar();
        Map<String, Object> staleVersion = payload("stale-request-version", "file-1", "qdp.zip");
        staleVersion.put(TEST_EXPECTED_VERSION, 6L);
        IllegalStateException stale = assertThrows(IllegalStateException.class,
                () -> execute(staleRequest, files, 101L, "user-501", staleVersion));
        assertTrue(stale.getMessage().contains("Customer Request version is stale"));

        FakeDataAccessor payloadSpoof = eligibleRequestWithPcbaSidecar();
        Map<String, Object> spoofed = payload("payload-version-spoof", "file-1", "qdp.zip");
        spoofed.put("crm_qdp_expected_request_version", "999999");
        spoofed.put("crm_qdp_source_revision", "attacker-controlled");
        execute(payloadSpoof, files, 101L, "user-501", spoofed);
        Map<String, Object> frozen = payloadSpoof.only("crm_qdp_revision_common");
        assertEquals("7", frozen.get("crm_qdp_expected_request_version"));
        assertEquals("7", frozen.get("crm_qdp_source_revision"));

        FakeDataAccessor conditional = eligibleRequestWithPcbaSidecar();
        Map<String, Object> sidecar = conditional.getById("crm_customer_request_pcba_rfq", "rfq-1");
        sidecar.put("crm_crq_dfm_status", "conditional");
        sidecar.remove("crm_crq_qualification_evidence_refs");
        IllegalArgumentException missingEvidence = assertThrows(IllegalArgumentException.class,
                () -> execute(conditional, files, 101L, "user-501",
                        payload("conditional-no-evidence", "file-1", "qdp.zip")));
        assertTrue(missingEvidence.getMessage().contains("qualification evidence"));

        sidecar.put("crm_crq_qualification_evidence_refs", List.of("evidence-public-pid-1"));
        Map<String, Object> released = assertDoesNotThrow(() -> execute(conditional, files, 101L,
                "user-501", payload("conditional-with-evidence", "file-1", "qdp.zip")));
        assertEquals(false, released.get("idempotent"));
    }

    @Test
    void rejectsGenericFileIdAlias() {
        FakeDataAccessor db = eligibleRequestWithPcbaSidecar();
        FakeFileAccessor files = new FakeFileAccessor().put("file-1", "v1");

        Map<String, Object> alias = payload("generic-id", null, null);
        alias.put("crm_qdp_file_manifest", List.of(row("id", "file-1", "fileName", "qdp.zip")));
        IllegalArgumentException genericId = assertThrows(IllegalArgumentException.class,
                () -> execute(db, files, 101L, "user-501", alias));
        assertTrue(genericId.getMessage().contains("public file pid"));
        assertTrue(db.rows("crm_qdp_revision_common").isEmpty());
    }

    @Test
    void dryRunValidatesButDoesNotCreateRevisionOrLinkSidecar() throws Exception {
        FakeDataAccessor db = eligibleRequestWithPcbaSidecar();
        FakeFileAccessor files = new FakeFileAccessor().put("file-1", "v1");
        Map<String, Object> settings = new HashMap<>();
        settings.put("__dataAccessor", db);
        settings.put("__fileAccessor", files);
        settings.put("__currentUser", "user-501");
        settings.put("__clientRequestId", "dry-run");
        settings.put("__expectedVersion", 7L);
        Map<String, Object> dryRunPayload = payload("dry-run", "file-1", "qdp.zip");
        dryRunPayload.remove(TEST_CLIENT_REQUEST_ID);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) handler.execute(CommandContext.builder()
                .tenantId(101L)
                .pluginId("com.auraboot.crm")
                .namespace("crm")
                .commandType("crm:release_qdp")
                .modelCode("crm_customer_request_common")
                .recordId("request-1")
                .payload(dryRunPayload)
                .settings(settings)
                .dryRun(true)
                .build());

        assertEquals(true, result.get("dryRun"));
        assertEquals("release_validated", result.get("status"));
        assertTrue(db.rows("crm_qdp_revision_common").isEmpty());
        assertFalse(db.getById("crm_customer_request_pcba_rfq", "rfq-1")
                .containsKey("crm_crq_qdp_revision_id"));
        assertTrue(files.retainAttempts().isEmpty(), "dry-run must not mutate file retention state");
    }

    @Test
    void retentionFailureOrExceptionAbortsBeforeRevisionCreateAndSidecarLink() {
        FakeDataAccessor deniedDb = eligibleRequestWithPcbaSidecar();
        FakeFileAccessor deniedFiles = new FakeFileAccessor()
                .put("file-a", "A")
                .put("file-b", "B")
                .denyRetain("file-b");
        Map<String, Object> deniedPayload = payload("retain-denied", "file-a", null);
        deniedPayload.put("crm_qdp_file_manifest", List.of(
                fileRef("file-a", "a.zip", "primary"),
                fileRef("file-b", "b.gbr", "gerber")));

        IllegalStateException denied = assertThrows(IllegalStateException.class,
                () -> execute(deniedDb, deniedFiles, 101L, "user-501", deniedPayload));

        assertTrue(denied.getMessage().contains("retention"));
        assertEquals(List.of("file-a", "file-b"), deniedFiles.retainAttempts());
        assertTrue(deniedDb.rows("crm_qdp_revision_common").isEmpty());
        assertFalse(deniedDb.getById("crm_customer_request_pcba_rfq", "rfq-1")
                .containsKey("crm_crq_qdp_revision_id"));

        FakeDataAccessor errorDb = eligibleRequestWithPcbaSidecar();
        FakeFileAccessor errorFiles = new FakeFileAccessor()
                .put("file-a", "A")
                .failRetain("file-a", new IllegalStateException("retention backend unavailable"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> execute(errorDb, errorFiles, 101L, "user-501",
                        payload("retain-error", "file-a", "a.zip")));

        assertTrue(error.getMessage().contains("retention"));
        assertEquals(List.of("file-a"), errorFiles.retainAttempts());
        assertTrue(errorDb.rows("crm_qdp_revision_common").isEmpty());
        assertFalse(errorDb.getById("crm_customer_request_pcba_rfq", "rfq-1")
                .containsKey("crm_crq_qdp_revision_id"));
    }

    @Test
    void sameKeyHandlerReplayReassertsIdempotentRetentionWithoutCreatingOrRelinking() throws Exception {
        FakeDataAccessor db = eligibleRequestWithPcbaSidecar();
        FakeFileAccessor files = new FakeFileAccessor().put("file-1", "v1");
        Map<String, Object> release = payload("retain-replay", "file-1", "qdp.zip");

        Map<String, Object> first = execute(db, files, 101L, "user-501", release);
        Map<String, Object> replay = execute(db, files, 101L, "user-501", release);

        assertEquals(first.get("qdpRevisionId"), replay.get("qdpRevisionId"));
        assertEquals(true, replay.get("idempotent"));
        assertEquals(List.of("file-1", "file-1"), files.retainAttempts());
        assertEquals(1, db.rows("crm_qdp_revision_common").size());
    }

    @Test
    void requiresTenantAndAuthenticatedActorAndNeverTrustsPayloadAuditIdentity() throws Exception {
        FakeDataAccessor db = eligibleRequestWithPcbaSidecar();
        FakeFileAccessor files = new FakeFileAccessor().put(
                "file-1", "qdp.zip", "application/zip", "server-user", "success", "v1");
        Map<String, Object> payload = payload("audit", "file-1", "qdp.zip");
        payload.put("crm_qdp_released_by", "spoofed-user");

        assertThrows(IllegalStateException.class,
                () -> execute(db, files, null, "user-501", payload));
        assertThrows(IllegalStateException.class,
                () -> execute(db, files, 101L, null, payload));

        execute(db, files, 101L, "server-user", payload);
        assertEquals("server-user", db.only("crm_qdp_revision_common").get("crm_qdp_released_by"));
    }

    @Test
    void usesTrustedClientRequestIdentityAndIgnoresPayloadSpoofing() throws Exception {
        FakeDataAccessor db = eligibleRequestWithPcbaSidecar();
        Map<String, Object> payload = payload("trusted-client-request", "file-1", "qdp.zip");
        payload.put("crm_qdp_idempotency_key", "spoofed-payload-key");

        execute(db, new FakeFileAccessor().put("file-1", "v1"), 101L, "user-501", payload);

        Map<String, Object> revision = db.only("crm_qdp_revision_common");
        assertEquals("trusted-client-request", revision.get("crm_qdp_client_request_id"));
        assertFalse(revision.containsKey("crm_qdp_idempotency_key"));
    }

    @Test
    void failsClosedWithoutTrustedClientRequestIdentity() {
        FakeDataAccessor db = eligibleRequestWithPcbaSidecar();
        Map<String, Object> payload = payload("missing-context", "file-1", "qdp.zip");
        payload.remove(TEST_CLIENT_REQUEST_ID);

        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> execute(db, new FakeFileAccessor().put("file-1", "v1"),
                        101L, "user-501", payload));
        assertTrue(missing.getMessage().toLowerCase().contains("client request"));
        assertTrue(db.rows("crm_qdp_revision_common").isEmpty());
    }

    @Test
    void rejectsMissingMismatchedOrNonCanonicalRequestPidsBeforeMutation() {
        FakeFileAccessor files = new FakeFileAccessor().put("file-1", "v1");

        FakeDataAccessor missingDb = eligibleRequestWithPcbaSidecar();
        Map<String, Object> missingPayloadPid = payload("missing-payload-pid", "file-1", "qdp.zip");
        missingPayloadPid.remove("crm_qdp_customer_request_id");
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> execute(missingDb, files, 101L, "user-501", missingPayloadPid));
        assertTrue(missing.getMessage().contains("both command target and release payload"));
        assertTrue(missingDb.rows("crm_qdp_revision_common").isEmpty());

        FakeDataAccessor mismatchDb = eligibleRequestWithPcbaSidecar();
        Map<String, Object> mismatchPayload = payload("mismatched-payload-pid", "file-1", "qdp.zip");
        mismatchPayload.put("crm_qdp_customer_request_id", "request-2");
        IllegalArgumentException mismatch = assertThrows(IllegalArgumentException.class,
                () -> execute(mismatchDb, files, 101L, "user-501", mismatchPayload));
        assertTrue(mismatch.getMessage().contains("does not match"));
        assertTrue(mismatchDb.rows("crm_qdp_revision_common").isEmpty());

        FakeDataAccessor whitespacePayloadDb = eligibleRequestWithPcbaSidecar();
        Map<String, Object> whitespacePayload = payload("whitespace-payload-pid", "file-1", "qdp.zip");
        whitespacePayload.put("crm_qdp_customer_request_id", " request-1 ");
        IllegalArgumentException payloadWhitespace = assertThrows(IllegalArgumentException.class,
                () -> execute(whitespacePayloadDb, files, 101L, "user-501", whitespacePayload));
        assertTrue(payloadWhitespace.getMessage().contains("leading or trailing whitespace"));
        assertTrue(whitespacePayloadDb.rows("crm_qdp_revision_common").isEmpty());

        FakeDataAccessor whitespaceTargetDb = eligibleRequestWithPcbaSidecar();
        IllegalArgumentException targetWhitespace = assertThrows(IllegalArgumentException.class,
                () -> execute(whitespaceTargetDb, files, 101L, "user-501",
                        payload("whitespace-target-pid", "file-1", "qdp.zip"), " request-1 "));
        assertTrue(targetWhitespace.getMessage().contains("leading or trailing whitespace"));
        assertTrue(whitespaceTargetDb.rows("crm_qdp_revision_common").isEmpty());
    }

    private Map<String, Object> execute(FakeDataAccessor db, FakeFileAccessor files, Long tenantId,
                                        String currentUser, Map<String, Object> payload) throws Exception {
        return execute(db, files, tenantId, currentUser, payload, "request-1");
    }

    private Map<String, Object> execute(FakeDataAccessor db, FakeFileAccessor files, Long tenantId,
                                        String currentUser, Map<String, Object> payload,
                                        String targetRecordId) throws Exception {
        Map<String, Object> settings = new HashMap<>();
        settings.put("__dataAccessor", db);
        settings.put("__fileAccessor", files);
        if (currentUser != null) settings.put("__currentUser", currentUser);
        Object clientRequestId = payload.get(TEST_CLIENT_REQUEST_ID);
        if (clientRequestId != null) settings.put("__clientRequestId", clientRequestId);
        Object expectedVersion = payload.get(TEST_EXPECTED_VERSION);
        if (expectedVersion != null) settings.put("__expectedVersion", expectedVersion);
        Map<String, Object> commandPayload = new HashMap<>(payload);
        commandPayload.remove(TEST_CLIENT_REQUEST_ID);
        commandPayload.remove(TEST_EXPECTED_VERSION);
        Object result = handler.execute(CommandContext.builder()
                .tenantId(tenantId)
                .pluginId("com.auraboot.crm")
                .namespace("crm")
                .commandType("crm:release_qdp")
                .modelCode("crm_customer_request_common")
                .recordId(targetRecordId)
                .payload(commandPayload)
                .settings(settings)
                .dryRun(false)
                .build());
        assertTrue(result instanceof Map<?, ?>);
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) result;
        return typed;
    }

    private static FakeDataAccessor eligibleRequestWithPcbaSidecar() {
        FakeDataAccessor db = new FakeDataAccessor();
        db.seed("crm_customer_request_common", row(
                "pid", "request-1",
                "crm_cr_code", "CR-0001",
                "crm_cr_title", "Controller RFQ",
                "crm_cr_account_id", "account-1",
                "crm_cr_type", "rfq",
                "crm_cr_priority", "high",
                "crm_cr_status", "in_progress",
                "updated_at", "request-v7",
                "row_version", 7L,
                "crm_cr_route_status", "routed",
                "crm_cr_routed_package", "pcba-crm",
                "crm_cr_routed_object_type", "crm_customer_request_pcba_rfq",
                "crm_cr_routed_object_id", "rfq-1",
                "crm_cr_source_channel", "portal",
                "crm_cr_summary", "Need controller PCBA quote"));
        db.seed("crm_customer_request_pcba_rfq", row(
                "pid", "rfq-1",
                "crm_customer_request_id", "request-1",
                "crm_crq_revision", "A",
                "crm_crq_dfm_status", "passed",
                "crm_crq_qualification_evidence_refs", List.of("evidence-public-pid-1"),
                "crm_crq_product_model", "CTRL-100",
                "crm_crq_assembly_type", "smt"));
        return db;
    }

    private static Map<String, Object> payload(String clientRequestId, String fileId, String filename) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("crm_qdp_customer_request_id", "request-1");
        payload.put("crm_qdp_pcba_rfq_id", "rfq-1");
        payload.put(TEST_CLIENT_REQUEST_ID, clientRequestId);
        payload.put(TEST_EXPECTED_VERSION, 7L);
        payload.put("crm_qdp_release_note", "Release from PCBA RFQ");
        if (fileId != null) payload.put("crm_qdp_primary_file_id", fileId);
        if (filename != null) payload.put("crm_qdp_primary_filename", filename);
        return payload;
    }

    private static Map<String, Object> fileRef(String fileId, String filename, String purpose) {
        return row("filePid", fileId, "fileName", filename, "purpose", purpose);
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> data = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) data.put((String) kv[i], kv[i + 1]);
        return data;
    }

    private static final class FakeFileAccessor implements FileAccessor {
        private final Map<String, TestFile> files = new HashMap<>();
        private final Map<String, RuntimeException> describeFailures = new HashMap<>();
        private final Map<String, RuntimeException> retainFailures = new HashMap<>();
        private final List<String> deniedRetains = new ArrayList<>();
        private final List<String> retainAttempts = new ArrayList<>();
        private final Map<String, Long> readCounts = new HashMap<>();
        private final List<FileLink> links = new ArrayList<>();
        private Runnable openHook;

        FakeFileAccessor put(String fileId, String content) {
            return put(fileId, fileId + ".bin", "application/octet-stream",
                    "user-501", "success", content);
        }

        FakeFileAccessor put(String fileId,
                             String originalName,
                             String contentType,
                             String ownerUserId,
                             String status,
                             String content) {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            files.put(fileId, new TestFile(bytes, new FileMetadata(
                    fileId, originalName, bytes.length, contentType, ownerUserId, status)));
            return this;
        }

        FakeFileAccessor link(String fileId, String entityType, String entityId, String fieldName) {
            links.add(new FileLink(fileId, entityType, entityId, fieldName));
            return this;
        }

        FakeFileAccessor failDescribe(String fileId, RuntimeException failure) {
            describeFailures.put(fileId, failure);
            return this;
        }

        FakeFileAccessor denyRetain(String fileId) {
            deniedRetains.add(fileId);
            return this;
        }

        FakeFileAccessor failRetain(String fileId, RuntimeException failure) {
            retainFailures.put(fileId, failure);
            return this;
        }

        List<String> retainAttempts() {
            return List.copyOf(retainAttempts);
        }

        FakeFileAccessor metadata(String fileId, FileMetadata metadata) {
            TestFile file = files.get(fileId);
            if (file == null) throw new IllegalArgumentException("test file is missing: " + fileId);
            files.put(fileId, new TestFile(file.bytes(), metadata));
            return this;
        }

        FakeFileAccessor onOpen(Runnable hook) {
            this.openHook = hook;
            return this;
        }

        long bytesRead(String fileId) {
            return readCounts.getOrDefault(fileId, 0L);
        }

        @Override
        public InputStream open(String fileId) {
            if (openHook != null) openHook.run();
            TestFile file = files.get(fileId);
            if (file == null) return null;
            ByteArrayInputStream delegate = new ByteArrayInputStream(file.bytes());
            return new InputStream() {
                @Override
                public int read() {
                    int value = delegate.read();
                    if (value >= 0) readCounts.merge(fileId, 1L, Long::sum);
                    return value;
                }

                @Override
                public int read(byte[] target, int offset, int length) {
                    int read = delegate.read(target, offset, length);
                    if (read > 0) readCounts.merge(fileId, (long) read, Long::sum);
                    return read;
                }

                @Override
                public void close() throws java.io.IOException {
                    delegate.close();
                }
            };
        }

        @Override
        public FileMetadata describe(String fileId) {
            RuntimeException failure = describeFailures.get(fileId);
            if (failure != null) throw failure;
            TestFile file = files.get(fileId);
            return file == null ? null : file.metadata();
        }

        @Override
        public boolean isLinkedTo(String fileId, String entityType, String entityId, String fieldName) {
            return links.contains(new FileLink(fileId, entityType, entityId, fieldName));
        }

        @Override
        public boolean retain(String fileId) {
            retainAttempts.add(fileId);
            RuntimeException failure = retainFailures.get(fileId);
            if (failure != null) throw failure;
            return files.containsKey(fileId) && !deniedRetains.contains(fileId);
        }

        @Override
        public SavedFile save(String originalName, String contentType, byte[] bytes) {
            throw new UnsupportedOperationException("test accessor is read-only");
        }

        private record TestFile(byte[] bytes, FileMetadata metadata) {}

        private record FileLink(String fileId, String entityType, String entityId, String fieldName) {}
    }

    private static final class FakeDataAccessor implements DataAccessor {
        private final Map<String, List<Map<String, Object>>> store = new HashMap<>();
        private int seq;
        private int queryLimit = Integer.MAX_VALUE;
        private String queryFailureModel;

        void seed(String model, Map<String, Object> record) {
            store.computeIfAbsent(model, ignored -> new ArrayList<>()).add(new HashMap<>(record));
        }

        void removeModel(String model) {
            store.remove(model);
        }

        void failQuery(String model) {
            queryFailureModel = model;
        }

        void queryLimit(int limit) {
            queryLimit = limit;
        }

        List<Map<String, Object>> rows(String model) {
            return store.getOrDefault(model, List.of());
        }

        Map<String, Object> only(String model) {
            assertEquals(1, rows(model).size(), model + " should contain exactly one row");
            return rows(model).getFirst();
        }

        @Override
        public Map<String, Object> getById(String modelCode, String recordId) {
            return rows(modelCode).stream()
                    .filter(record -> recordId.equals(String.valueOf(record.get("pid"))))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public List<Map<String, Object>> query(String modelCode, Map<String, Object> filters) {
            if (modelCode.equals(queryFailureModel)) {
                throw new IllegalStateException("Model is not installed: " + modelCode);
            }
            return rows(modelCode).stream()
                    .filter(record -> filters.entrySet().stream().allMatch(entry ->
                            String.valueOf(entry.getValue()).equals(String.valueOf(record.get(entry.getKey())))))
                    .limit(queryLimit)
                    .toList();
        }

        @Override
        public Map<String, Object> create(String modelCode, Map<String, Object> data) {
            Map<String, Object> record = new HashMap<>(data);
            record.put("pid", "QDP-PID" + (++seq));
            store.computeIfAbsent(modelCode, ignored -> new ArrayList<>()).add(record);
            return record;
        }

        @Override
        public Map<String, Object> update(String modelCode, String recordId, Map<String, Object> data) {
            Map<String, Object> record = getById(modelCode, recordId);
            if (record != null) record.putAll(data);
            return record;
        }

        @Override
        public List<Map<String, Object>> batchCreate(String modelCode, List<Map<String, Object>> dataList) {
            return dataList.stream().map(data -> create(modelCode, data)).toList();
        }

        @Override
        public void delete(String modelCode, String recordId) {
            rows(modelCode).removeIf(record -> recordId.equals(String.valueOf(record.get("pid"))));
        }
    }
}
