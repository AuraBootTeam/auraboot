package com.auraboot.plugins.crm.handler;

import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.DataAccessor;
import com.auraboot.framework.plugin.extension.FileAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.pf4j.Extension;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Releases an immutable QDP revision under the CRM Customer Request aggregate.
 *
 * <p>File bytes remain owned by the platform file runtime. This handler verifies
 * every file reference through {@link FileAccessor}, freezes a reference-only
 * manifest plus the released request snapshot, and persists one append-only CRM
 * revision. This first slice requires authoritative PCBA route facts, discovers
 * and validates that RFQ sidecar server-side, and writes back only the resulting
 * QDP revision reference, never a copy of the release aggregate.</p>
 *
 * <p>Every source file is resolved through tenant-scoped platform metadata. A
 * file is accepted only when its multipart upload is finalized with status
 * {@code success} and it is owned by the authenticated actor.
 * File relations are not accepted as ownership evidence because the platform
 * relation-write endpoint does not yet prove file or target-object authority. Client-provided
 * file names, sizes, content types, storage keys, and internal ids are never
 * trusted.</p>
 */
@Extension
public class ReleaseQdpHandler implements CommandHandlerExtension {

    public static final String COMMAND_TYPE = "crm:release_qdp";
    static final String CUSTOMER_REQUEST_MODEL = "crm_customer_request_common";
    static final String QDP_REVISION_MODEL = "crm_qdp_revision_common";
    static final String PCBA_RFQ_MODEL = "crm_customer_request_pcba_rfq";
    static final String PCBA_QDP_REFERENCE_FIELD = "crm_crq_qdp_revision_id";
    static final int QDP_SCHEMA_VERSION = 1;

    private static final Set<String> RELEASABLE_REQUEST_STATES =
            Set.of("routed", "in_progress");
    private static final Set<String> QUALIFIED_PCBA_STATES = Set.of("passed", "conditional");
    private static final Set<String> READABLE_FILE_STATUSES = Set.of("success");
    private static final int MAX_CLIENT_REQUEST_ID_LENGTH = 128;
    private static final int MAX_RELEASE_NOTE_LENGTH = 2_000;
    private static final int MAX_MANIFEST_FILE_COUNT = 20;
    private static final int MAX_PURPOSE_LENGTH = 64;
    private static final int MAX_QUALIFICATION_EVIDENCE_COUNT = 100;
    private static final int MAX_PUBLIC_REFERENCE_LENGTH = 128;
    private static final long MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024;
    private static final long MAX_TOTAL_FILE_BYTES = 100L * 1024 * 1024;
    private static final Pattern PUBLIC_REFERENCE_PATTERN =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    @Override
    public String getCommandType() {
        return COMMAND_TYPE;
    }

    @Override
    public Set<String> getSupportedCommandTypes() {
        return Set.of(COMMAND_TYPE);
    }

    @Override
    public boolean supports(String commandType) {
        return COMMAND_TYPE.equals(commandType);
    }

    @Override
    public boolean supportsDryRun() {
        return true;
    }

    @Override
    public Object execute(CommandContext context) {
        DataAccessor db = context.dataAccessor();
        if (db == null) {
            throw new IllegalStateException("DataAccessor unavailable; cannot release QDP");
        }
        FileAccessor files = context.fileAccessor();
        if (files == null) {
            throw new IllegalStateException("FileAccessor unavailable; cannot verify QDP source files");
        }
        Long tenantId = context.tenantId();
        if (tenantId == null || tenantId <= 0) {
            throw new IllegalStateException("Authenticated tenant context is required to release QDP");
        }
        String actor = required(setting(context, "__currentUser"),
                "Authenticated actor context is required to release QDP");
        Map<String, Object> payload = context.payload() == null ? Map.of() : context.payload();

        String targetRequestId = canonicalPid(context.recordId(), "Command target Customer Request pid");
        String payloadRequestId = canonicalPid(payload.get("crm_qdp_customer_request_id"),
                "Payload Customer Request pid");
        if (targetRequestId == null || payloadRequestId == null) {
            throw new IllegalArgumentException(
                    "Customer Request pid is required in both command target and release payload");
        }
        if (!targetRequestId.equals(payloadRequestId)) {
            throw new IllegalArgumentException("QDP Customer Request does not match the command target");
        }
        String customerRequestId = targetRequestId;

        Map<String, Object> request = db.getById(CUSTOMER_REQUEST_MODEL, customerRequestId);
        if (request == null) {
            throw new IllegalArgumentException("Customer Request not found: " + customerRequestId);
        }
        String requestPid = firstNonBlank(trimToNull(request.get("pid")), customerRequestId);
        if (!customerRequestId.equals(requestPid)) {
            throw new IllegalStateException("Customer Request identity mismatch: " + customerRequestId);
        }
        String requestState = trimToNull(request.get("crm_cr_status"));
        if (!RELEASABLE_REQUEST_STATES.contains(requestState)) {
            throw new IllegalArgumentException("Customer Request state '" + requestState
                    + "' cannot release QDP; expected one of " + RELEASABLE_REQUEST_STATES);
        }
        Long currentRequestVersion = positiveLong(request.get("row_version"));
        if (currentRequestVersion == null) {
            throw new IllegalStateException(
                    "Customer Request row version is unavailable; QDP release is fail-closed");
        }
        Long expectedRequestVersion = context.expectedVersion();
        if (expectedRequestVersion == null || expectedRequestVersion <= 0) {
            throw new IllegalStateException(
                    "Trusted expected Customer Request version is required to release QDP");
        }
        if (!expectedRequestVersion.equals(currentRequestVersion)) {
            throw new IllegalStateException("Customer Request version is stale: expected "
                    + expectedRequestVersion + " but current is " + currentRequestVersion);
        }
        String requestVersion = Long.toString(expectedRequestVersion);

        String clientRequestId = required(context.clientRequestId(),
                "Trusted client request identity is required to release QDP");
        if (clientRequestId.length() > MAX_CLIENT_REQUEST_ID_LENGTH) {
            throw new IllegalArgumentException("Client request identity must not exceed "
                    + MAX_CLIENT_REQUEST_ID_LENGTH + " characters");
        }
        String releaseNote = limitedText(payload.get("crm_qdp_release_note"), MAX_RELEASE_NOTE_LENGTH,
                "QDP release note");
        String ownerScope = "tenant:" + tenantId + "/customer-request:" + customerRequestId;

        SidecarContext sidecar = validateRequiredPcbaSidecar(db, payload, request, customerRequestId);
        String sourceRevision = requestVersion;
        Map<String, Object> manifest = materializeFileManifest(files, payload, actor, ownerScope);
        List<Map<String, Object>> manifestFiles = manifestFiles(manifest);
        Map<String, Object> primaryFile = selectPrimaryFile(payload, manifestFiles);
        String primaryFilePid = required(primaryFile.get("filePid"),
                "Materialized primary QDP file pid is required");
        Map<String, Object> requestSnapshot = requestSnapshot(request, customerRequestId,
                requestVersion, sourceRevision, sidecar.snapshot());
        String contentHash = contentHash(customerRequestId, sourceRevision, requestSnapshot,
                manifest, primaryFilePid, releaseNote);

        List<Map<String, Object>> sameKeyMatches = safeList(db.query(QDP_REVISION_MODEL,
                Map.of(
                        "crm_qdp_customer_request_id", customerRequestId,
                        "crm_qdp_client_request_id", clientRequestId)));
        if (sameKeyMatches.size() > 1) {
            throw new IllegalStateException(
                    "QDP idempotency invariant is broken: more than one revision uses client request identity '"
                            + clientRequestId + "'");
        }
        Map<String, Object> sameKey = sameKeyMatches.isEmpty()
                ? null
                : sameKeyMatches.getFirst();
        if (sameKey != null) {
            if (!contentHash.equals(trimToNull(sameKey.get("crm_qdp_content_hash")))) {
                throw new IllegalStateException("QDP idempotency conflict: client request identity '"
                        + clientRequestId
                        + "' was already used for different content");
            }
            if (!context.dryRun()) {
                // The host retention operation is monotonic and idempotent. Reassert it for a
                // handler-level replay so a revision created during a rolling upgrade cannot keep
                // referencing bytes that remain deletable.
                retainSourceFiles(files, manifestFiles);
            }
            // The initial create and sidecar link share the command transaction. A replay
            // therefore never "repairs" sidecar state: without an atomic compare-and-set,
            // doing so from the pre-hash snapshot could overwrite a newer release that
            // linked while this replay was hashing a slow file.
            return result(sameKey, contentHash, true, customerRequestId);
        }

        List<Map<String, Object>> revisions = safeList(db.query(QDP_REVISION_MODEL,
                Map.of("crm_qdp_customer_request_id", customerRequestId)));
        int revisionNo = revisions.stream()
                .mapToInt(revision -> positiveInt(revision.get("crm_qdp_revision_no")))
                .max()
                .orElse(0) + 1;
        String requestCode = firstNonBlank(trimToNull(request.get("crm_cr_code")), customerRequestId);
        String fileNames = String.join(", ", manifestFiles.stream()
                .map(file -> required(file.get("fileName"), "Materialized QDP file name is required"))
                .sorted()
                .toList());
        if (fileNames.length() > 2_000) {
            throw new IllegalArgumentException("Materialized QDP file names must not exceed 2000 characters");
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("crm_qdp_code", revisionCode(requestCode, revisionNo));
        row.put("crm_qdp_customer_request_id", customerRequestId);
        row.put("crm_qdp_revision_no", revisionNo);
        row.put("crm_qdp_schema_version", QDP_SCHEMA_VERSION);
        row.put("crm_qdp_expected_request_version", requestVersion);
        if (sourceRevision != null) row.put("crm_qdp_source_revision", sourceRevision);
        row.put("crm_qdp_qualification_verdict", sidecar.qualificationVerdict());
        if (!sidecar.qualificationEvidenceRefs().isEmpty()) {
            row.put("crm_qdp_qualification_evidence_refs", sidecar.qualificationEvidenceRefs());
        }
        row.put("crm_qdp_content_hash", contentHash);
        row.put("crm_qdp_request_snapshot", requestSnapshot);
        row.put("crm_qdp_file_manifest", manifest);
        row.put("crm_qdp_primary_file_id", primaryFilePid);
        row.put("crm_qdp_primary_filename", primaryFile.get("fileName"));
        row.put("crm_qdp_file_names", fileNames);
        row.put("crm_qdp_client_request_id", clientRequestId);
        row.put("crm_qdp_owner_scope", ownerScope);
        row.put("crm_qdp_status", "released");
        if (releaseNote != null) row.put("crm_qdp_release_note", releaseNote);
        row.put("crm_qdp_released_at", Instant.now().toString());
        row.put("crm_qdp_released_by", actor);

        if (context.dryRun()) {
            Map<String, Object> dryRun = new LinkedHashMap<>();
            dryRun.put("success", true);
            dryRun.put("dryRun", true);
            dryRun.put("customerRequestId", customerRequestId);
            dryRun.put("plannedRevision", revisionNo);
            dryRun.put("contentHash", contentHash);
            dryRun.put("status", "release_validated");
            dryRun.put("plannedRecord", Map.copyOf(row));
            return dryRun;
        }

        // Retention is part of the same command transaction and must succeed for every canonical
        // manifest file before either the immutable revision or its sidecar reference is written.
        retainSourceFiles(files, manifestFiles);
        Map<String, Object> created = db.create(QDP_REVISION_MODEL, row);
        String revisionPid = resolvePid(created);
        if (revisionPid == null) {
            throw new IllegalStateException("QDP revision was created without a pid");
        }
        linkPcbaSidecar(db, sidecar.pid(), revisionPid);
        return result(created, contentHash, false, customerRequestId);
    }

    private static SidecarContext validateRequiredPcbaSidecar(DataAccessor db,
                                                               Map<String, Object> payload,
                                                               Map<String, Object> request,
                                                               String customerRequestId) {
        String requestedSidecarPid = trimToNull(payload.get("crm_qdp_pcba_rfq_id"));
        String routeStatus = trimToNull(request.get("crm_cr_route_status"));
        String routedPackage = trimToNull(request.get("crm_cr_routed_package"));
        String routedObjectType = trimToNull(request.get("crm_cr_routed_object_type"));
        String routedObjectId = trimToNull(request.get("crm_cr_routed_object_id"));
        if (!"routed".equals(routeStatus) || !"pcba-crm".equals(routedPackage)
                || !PCBA_RFQ_MODEL.equals(routedObjectType) || routedObjectId == null) {
            throw new IllegalStateException(
                    "An authoritative PCBA route is required before QDP release; "
                            + "unrouted, incomplete, and non-PCBA requests are not eligible in this slice");
        }
        if (requestedSidecarPid != null && !requestedSidecarPid.equals(routedObjectId)) {
            throw new IllegalArgumentException(
                    "Requested PCBA RFQ sidecar does not match the server-routed object");
        }

        String sidecarPid = routedObjectId;
        Map<String, Object> sidecar = db.getById(PCBA_RFQ_MODEL, sidecarPid);
        if (sidecar == null) {
            throw new IllegalArgumentException("Server-routed PCBA RFQ sidecar not found: " + sidecarPid);
        }
        if (!sidecarPid.equals(resolvePid(sidecar))) {
            throw new IllegalStateException("PCBA RFQ sidecar identity mismatch");
        }
        String linkedRequest = trimToNull(sidecar.get("crm_customer_request_id"));
        if (!customerRequestId.equals(linkedRequest)) {
            throw new IllegalArgumentException("PCBA RFQ sidecar belongs to another Customer Request");
        }
        String qualification = trimToNull(sidecar.get("crm_crq_dfm_status"));
        if (qualification == null || !QUALIFIED_PCBA_STATES.contains(qualification)) {
            throw new IllegalArgumentException("PCBA qualification must be passed or conditional before QDP release");
        }
        List<String> qualificationEvidenceRefs = publicReferenceList(
                sidecar.get("crm_crq_qualification_evidence_refs"),
                "PCBA qualification evidence references");
        if ("conditional".equals(qualification) && qualificationEvidenceRefs.isEmpty()) {
            throw new IllegalArgumentException(
                    "Conditional PCBA qualification requires at least one qualification evidence reference");
        }

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("sourceContextModel", PCBA_RFQ_MODEL);
        snapshot.put("sourceContextId", sidecarPid);
        snapshot.put("qualificationStatus", qualification);
        if (!qualificationEvidenceRefs.isEmpty()) {
            snapshot.put("qualificationEvidenceRefs", qualificationEvidenceRefs);
        }
        copyIfPresent(sidecar, snapshot, "crm_crq_product_model", "productModel");
        copyIfPresent(sidecar, snapshot, "crm_crq_assembly_type", "assemblyType");
        copyIfPresent(sidecar, snapshot, "crm_crq_quality_class", "qualityClass");
        copyIfPresent(sidecar, snapshot, "crm_crq_quality_grade", "qualityGrade");
        copyIfPresent(sidecar, snapshot, "crm_crq_trace_level", "traceLevel");
        copyIfPresent(sidecar, snapshot, "crm_crq_supply_mode", "supplyMode");
        return new SidecarContext(sidecarPid, qualification,
                qualificationEvidenceRefs, snapshot);
    }

    private static Map<String, Object> requestSnapshot(Map<String, Object> request,
                                                       String customerRequestId,
                                                       String requestVersion,
                                                       String sourceRevision,
                                                       Map<String, Object> sidecarSnapshot) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schemaVersion", QDP_SCHEMA_VERSION);
        snapshot.put("customerRequestId", customerRequestId);
        snapshot.put("requestVersion", requestVersion);
        copyIfPresent(request, snapshot, "crm_cr_code", "requestCode");
        copyIfPresent(request, snapshot, "crm_cr_title", "title");
        copyIfPresent(request, snapshot, "crm_cr_account_id", "accountId");
        copyIfPresent(request, snapshot, "crm_cr_contact_id", "contactId");
        copyIfPresent(request, snapshot, "crm_cr_opportunity_id", "opportunityId");
        copyIfPresent(request, snapshot, "crm_cr_type", "requestType");
        copyIfPresent(request, snapshot, "crm_cr_priority", "priority");
        copyIfPresent(request, snapshot, "crm_cr_status", "status");
        copyIfPresent(request, snapshot, "crm_cr_route_status", "routeStatus");
        copyIfPresent(request, snapshot, "crm_cr_expected_date", "expectedDate");
        copyIfPresent(request, snapshot, "crm_cr_source_channel", "sourceChannel");
        copyIfPresent(request, snapshot, "crm_cr_summary", "summary");
        copyIfPresent(request, snapshot, "crm_cr_due_at", "quoteDueAt");
        putIfPresent(snapshot, "sourceRevision", sourceRevision);
        if (sidecarSnapshot != null && !sidecarSnapshot.isEmpty()) {
            snapshot.putAll(sidecarSnapshot);
        }
        return snapshot;
    }

    private static Map<String, Object> materializeFileManifest(FileAccessor files,
                                                               Map<String, Object> payload,
                                                               String actor,
                                                               String ownerScope) {
        List<Map<String, Object>> requested = requestedFileReferences(payload);
        if (requested.isEmpty()) {
            throw new IllegalArgumentException("At least one QDP source file reference is required");
        }
        if (requested.size() > MAX_MANIFEST_FILE_COUNT) {
            throw new IllegalArgumentException("QDP file manifest must not contain more than "
                    + MAX_MANIFEST_FILE_COUNT + " files");
        }

        List<VerifiedRequest> verifiedRequests = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        long totalSize = 0L;
        for (Map<String, Object> requestedFile : requested) {
            String filePid = trimToNull(requestedFile.get("filePid"));
            if (filePid == null) {
                throw new IllegalArgumentException(
                        "Every QDP source file reference requires a public file pid in filePid");
            }
            String purpose = firstNonBlank(trimToNull(requestedFile.get("purpose")), "source");
            if (purpose.length() > MAX_PURPOSE_LENGTH) {
                throw new IllegalArgumentException("QDP source file purpose must not exceed "
                        + MAX_PURPOSE_LENGTH + " characters");
            }
            if (!identities.add(filePid)) {
                throw new IllegalArgumentException("Duplicate QDP source file pid: " + filePid);
            }
            VerifiedFile verified = verifyFileMetadata(files, filePid, actor, ownerScope);
            long fileSize = verified.metadata().size();
            if (fileSize > MAX_FILE_SIZE_BYTES) {
                throw new IllegalArgumentException("QDP source file must not exceed 50 MiB: " + filePid);
            }
            if (fileSize > MAX_TOTAL_FILE_BYTES - totalSize) {
                throw new IllegalArgumentException("QDP source files must not exceed 100 MiB in total");
            }
            totalSize += fileSize;
            verifiedRequests.add(new VerifiedRequest(filePid, purpose, verified));
        }

        List<Map<String, Object>> materialized = new ArrayList<>();
        long materializedTotalSize = 0L;
        for (VerifiedRequest requestedFile : verifiedRequests) {
            String filePid = requestedFile.filePid();
            String purpose = requestedFile.purpose();
            VerifiedFile verified = requestedFile.verifiedFile();
            FileDigest digest = digestFile(files, filePid, verified.metadata().size(),
                    MAX_TOTAL_FILE_BYTES - materializedTotalSize);
            if (digest.size() != verified.metadata().size()) {
                throw new IllegalArgumentException("QDP source file metadata size does not match stored bytes: "
                        + filePid);
            }
            materializedTotalSize += digest.size();
            Map<String, Object> frozen = new LinkedHashMap<>();
            frozen.put("filePid", filePid);
            frozen.put("fileName", verified.metadata().originalName());
            frozen.put("purpose", purpose);
            frozen.put("ownerScope", ownerScope);
            frozen.put("ownershipVerified", true);
            frozen.put("ownershipBasis", verified.ownershipBasis());
            frozen.put("size", verified.metadata().size());
            String contentType = trimToNull(verified.metadata().contentType());
            if (contentType != null) frozen.put("contentType", contentType);
            frozen.put("sha256", digest.sha256());
            materialized.add(frozen);
        }
        materialized.sort(Comparator
                .comparing((Map<String, Object> file) -> String.valueOf(file.get("filePid")))
                .thenComparing(file -> String.valueOf(file.get("purpose")))
                .thenComparing(file -> String.valueOf(file.get("fileName"))));

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", QDP_SCHEMA_VERSION);
        manifest.put("ownerScope", ownerScope);
        manifest.put("files", List.copyOf(materialized));
        return manifest;
    }

    private static VerifiedFile verifyFileMetadata(FileAccessor files,
                                                   String filePid,
                                                   String actor,
                                                   String ownerScope) {
        FileAccessor.FileMetadata metadata;
        try {
            metadata = files.describe(filePid);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "QDP source file metadata could not be verified for public pid: " + filePid, e);
        }
        if (metadata == null) {
            throw new IllegalArgumentException(
                    "QDP source file metadata is unavailable for public pid: " + filePid);
        }
        if (!filePid.equals(trimToNull(metadata.fileId()))) {
            throw new IllegalArgumentException(
                    "QDP source file metadata identity does not match the requested public pid: " + filePid);
        }
        String fileStatus = trimToNull(metadata.status());
        if (fileStatus == null || !READABLE_FILE_STATUSES.contains(fileStatus.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(
                    "QDP source file must have the finalized multipart upload status success: " + filePid);
        }
        String originalName = trimToNull(metadata.originalName());
        if (originalName == null) {
            throw new IllegalArgumentException("QDP source file server filename is unavailable: " + filePid);
        }
        if (metadata.size() < 0) {
            throw new IllegalArgumentException("QDP source file server size is invalid: " + filePid);
        }
        if (actor.equals(trimToNull(metadata.ownerUserId()))) {
            return new VerifiedFile(metadata, "actor_owner");
        }
        throw new IllegalArgumentException("QDP source file owner does not match the authenticated actor for scope: "
                + ownerScope);
    }

    private static void retainSourceFiles(FileAccessor files,
                                          List<Map<String, Object>> manifestFiles) {
        for (Map<String, Object> manifestFile : manifestFiles) {
            String filePid = required(manifestFile.get("filePid"),
                    "Materialized QDP file pid is required for retention");
            final boolean retained;
            try {
                retained = files.retain(filePid);
            } catch (RuntimeException e) {
                throw new IllegalStateException(
                        "QDP source file retention could not be established: " + filePid, e);
            }
            if (!retained) {
                throw new IllegalStateException(
                        "QDP source file retention was rejected by the host: " + filePid);
            }
        }
    }

    private static List<Map<String, Object>> requestedFileReferences(Map<String, Object> payload) {
        Object raw = payload.get("crm_qdp_file_manifest");
        if (raw instanceof String text && !text.isBlank()) {
            try {
                raw = MAPPER.readValue(text, Object.class);
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("QDP file manifest must be valid JSON", e);
            }
        }
        if (raw instanceof Map<?, ?> manifest) {
            raw = manifest.get("files");
        }

        List<Map<String, Object>> references = new ArrayList<>();
        if (raw instanceof List<?> values) {
            for (Object value : values) {
                if (!(value instanceof Map<?, ?> map)) {
                    throw new IllegalArgumentException("QDP file manifest entries must be objects");
                }
                Map<String, Object> typed = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getKey() != null) typed.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                references.add(typed);
            }
        } else if (raw != null) {
            throw new IllegalArgumentException("QDP file manifest must contain a files array");
        }

        if (references.isEmpty()) {
            String primaryFileId = trimToNull(payload.get("crm_qdp_primary_file_id"));
            if (primaryFileId != null) {
                Map<String, Object> primary = new LinkedHashMap<>();
                primary.put("filePid", primaryFileId);
                primary.put("purpose", "primary");
                references.add(primary);
            }
        }
        return references;
    }

    private static FileDigest digestFile(FileAccessor files,
                                         String fileId,
                                         long expectedSize,
                                         long remainingTotalBytes) {
        MessageDigest digest = sha256Digest();
        long size = 0;
        long byteLimit = Math.min(expectedSize,
                Math.min(MAX_FILE_SIZE_BYTES, remainingTotalBytes));
        if (byteLimit < 0) {
            throw new IllegalArgumentException("QDP source file size limit is invalid: " + fileId);
        }
        try (InputStream input = files.open(fileId)) {
            if (input == null) {
                throw new IllegalArgumentException("QDP source file not found or inaccessible: " + fileId);
            }
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                if (read > byteLimit - size) {
                    throw new IllegalArgumentException(
                            "QDP source file metadata size does not match stored bytes or exceeds limits: "
                                    + fileId);
                }
                digest.update(buffer, 0, read);
                size += read;
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("QDP source file not found or inaccessible: " + fileId, e);
        }
        return new FileDigest(size, hex(digest.digest()));
    }

    private static String contentHash(String customerRequestId,
                                      String sourceRevision,
                                      Map<String, Object> requestSnapshot,
                                      Map<String, Object> manifest,
                                      String primaryFilePid,
                                      String releaseNote) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("schemaVersion", QDP_SCHEMA_VERSION);
        canonical.put("customerRequestId", customerRequestId);
        putIfPresent(canonical, "sourceRevision", sourceRevision);
        canonical.put("requestSnapshot", requestSnapshot);
        canonical.put("fileManifest", manifest);
        canonical.put("primaryFilePid", primaryFilePid);
        putIfPresent(canonical, "releaseNote", releaseNote);
        try {
            byte[] bytes = MAPPER.writeValueAsBytes(canonical);
            MessageDigest digest = sha256Digest();
            return hex(digest.digest(bytes));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to canonicalize QDP release content", e);
        }
    }

    private static Map<String, Object> selectPrimaryFile(Map<String, Object> payload,
                                                         List<Map<String, Object>> manifestFiles) {
        String requestedPrimary = trimToNull(payload.get("crm_qdp_primary_file_id"));
        if (requestedPrimary != null) {
            for (Map<String, Object> file : manifestFiles) {
                if (requestedPrimary.equals(trimToNull(file.get("filePid")))) return file;
            }
            throw new IllegalArgumentException("Requested primary public file pid is not present in the manifest");
        }
        return manifestFiles.getFirst();
    }

    private static List<Map<String, Object>> manifestFiles(Map<String, Object> manifest) {
        Object raw = manifest.get("files");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalStateException("Materialized QDP manifest has no files");
        }
        return MAPPER.convertValue(list, new TypeReference<>() {});
    }

    private static void linkPcbaSidecar(DataAccessor db, String sidecarPid, String revisionPid) {
        if (sidecarPid == null) return;
        if (revisionPid == null) {
            throw new IllegalStateException("Cannot link PCBA RFQ without a QDP revision pid");
        }
        Map<String, Object> updated = db.update(PCBA_RFQ_MODEL, sidecarPid,
                Map.of(PCBA_QDP_REFERENCE_FIELD, revisionPid));
        if (updated == null) {
            throw new IllegalStateException("PCBA RFQ disappeared before QDP reference could be linked");
        }
    }

    private static Map<String, Object> result(Map<String, Object> revision,
                                              String contentHash,
                                              boolean idempotent,
                                              String customerRequestId) {
        String pid = resolvePid(revision);
        if (pid == null) throw new IllegalStateException("QDP revision has no pid");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("qdpRevisionId", pid);
        result.put("customerRequestId", customerRequestId);
        result.put("revision", positiveInt(revision.get("crm_qdp_revision_no")));
        result.put("contentHash", contentHash);
        result.put("status", "released");
        result.put("idempotent", idempotent);
        return result;
    }

    private static String revisionCode(String requestCode, int revisionNo) {
        String normalized = requestCode.replaceAll("[^A-Za-z0-9-]", "-");
        if (normalized.length() > 48) normalized = normalized.substring(0, 48);
        return "QDP-" + normalized + "-R" + String.format("%04d", revisionNo);
    }

    private static Object setting(CommandContext context, String key) {
        return context.settings() == null ? null : context.settings().get(key);
    }

    private static List<String> publicReferenceList(Object raw, String label) {
        if (raw == null) return List.of();
        Object value = raw;
        if (raw instanceof String text) {
            if (text.isBlank()) return List.of();
            try {
                value = MAPPER.readValue(text, Object.class);
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException(label + " must be a JSON array of public pids", e);
            }
        }
        if (!(value instanceof List<?> values)) {
            throw new IllegalArgumentException(label + " must be an array of public pids");
        }
        if (values.size() > MAX_QUALIFICATION_EVIDENCE_COUNT) {
            throw new IllegalArgumentException(label + " must not contain more than "
                    + MAX_QUALIFICATION_EVIDENCE_COUNT + " entries");
        }
        List<String> refs = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (Object item : values) {
            if (!(item instanceof String rawRef)) {
                throw new IllegalArgumentException(label + " must contain string public pids only");
            }
            String ref = canonicalPid(rawRef, label + " item");
            if (ref == null) throw new IllegalArgumentException(label + " must not contain blank pids");
            if (ref.length() > MAX_PUBLIC_REFERENCE_LENGTH || !PUBLIC_REFERENCE_PATTERN.matcher(ref).matches()) {
                throw new IllegalArgumentException(label + " contains an invalid public pid: " + ref);
            }
            if (!unique.add(ref)) throw new IllegalArgumentException(label + " must not contain duplicates");
            refs.add(ref);
        }
        refs.sort(String::compareTo);
        return List.copyOf(refs);
    }

    private static String resolvePid(Map<String, Object> record) {
        return record == null ? null : trimToNull(record.get("pid"));
    }

    private static int positiveInt(Object value) {
        if (value instanceof Number number) return Math.max(0, number.intValue());
        if (value != null) {
            try {
                return Math.max(0, Integer.parseInt(String.valueOf(value)));
            } catch (NumberFormatException ignored) {
                // Invalid historical revisions sort before a valid revision 1.
            }
        }
        return 0;
    }

    private static Long positiveLong(Object value) {
        if (value instanceof Number number) {
            long parsed = number.longValue();
            return parsed > 0 ? parsed : null;
        }
        if (value != null) {
            try {
                long parsed = Long.parseLong(String.valueOf(value).trim());
                return parsed > 0 ? parsed : null;
            } catch (NumberFormatException ignored) {
                // Invalid versions fail closed at the caller.
            }
        }
        return null;
    }

    private static List<Map<String, Object>> safeList(List<Map<String, Object>> values) {
        return values == null ? List.of() : values;
    }

    private static String required(Object value, String message) {
        String text = trimToNull(value);
        if (text == null) throw new IllegalStateException(message);
        return text;
    }

    private static String limitedText(Object value, int maxLength, String label) {
        String text = trimToNull(value);
        if (text != null && text.length() > maxLength) {
            throw new IllegalArgumentException(label + " must not exceed " + maxLength + " characters");
        }
        return text;
    }

    private static String trimToNull(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static String canonicalPid(Object value, String label) {
        if (value == null) return null;
        String raw = String.valueOf(value);
        String canonical = raw.trim();
        if (canonical.isEmpty()) return null;
        if (!raw.equals(canonical)) {
            throw new IllegalArgumentException(label + " must not contain leading or trailing whitespace");
        }
        return canonical;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    private static void copyIfPresent(Map<String, Object> source, Map<String, Object> target,
                                      String sourceKey, String targetKey) {
        Object value = source.get(sourceKey);
        if (value != null && !String.valueOf(value).isBlank()) target.put(targetKey, value);
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) target.put(key, value);
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM does not provide SHA-256", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format("%02x", value & 0xff));
        return out.toString();
    }

    private record FileDigest(long size, String sha256) {}

    private record VerifiedFile(FileAccessor.FileMetadata metadata, String ownershipBasis) {}

    private record VerifiedRequest(String filePid, String purpose, VerifiedFile verifiedFile) {}

    private record SidecarContext(String pid,
                                  String qualificationVerdict,
                                  List<String> qualificationEvidenceRefs,
                                  Map<String, Object> snapshot) {}
}
