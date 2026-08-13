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
import java.util.function.BiConsumer;
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
    static final String PREPARE_COMMAND = "crm:prepare_qdp_draft";
    static final String COMPILE_COMMAND = "crm:compile_qdp_revision";
    static final String REVIEW_COMMAND = "crm:submit_qdp_review";
    static final String PUBLISH_COMMAND = "crm:publish_qdp_revision";
    static final String CUSTOMER_REQUEST_MODEL = "crm_customer_request_common";
    static final String QDP_REVISION_MODEL = "crm_qdp_revision_common";
    static final String REQUIREMENT_VERSION_MODEL = "crm_requirement_version_common";
    static final String FILE_PACKAGE_MODEL = "crm_file_package_common";
    static final String CUSTOMER_CONFIRMATION_MODEL = "crm_customer_confirmation_common";
    static final String PCBA_RFQ_MODEL = "crm_customer_request_pcba_rfq";
    static final String PCBA_QDP_REFERENCE_FIELD = "crm_crq_qdp_revision_id";
    static final int QDP_SCHEMA_VERSION = 1;

    private static final Set<String> RELEASABLE_REQUEST_STATES =
            Set.of("routed", "in_progress");
    private static final Set<String> QUALIFIED_PCBA_STATES = Set.of("passed", "conditional");
    private static final Set<String> READABLE_FILE_STATUSES = Set.of("success");
    private static final int MAX_CLIENT_REQUEST_ID_LENGTH = 128;
    private static final int MAX_RELEASE_NOTE_LENGTH = 2_000;
    private static final int MAX_REQUIREMENT_VERSION_LENGTH = 64;
    private static final int MAX_CONFIRMATION_REFERENCE_LENGTH = 128;
    private static final int MAX_STRUCTURED_ITEMS = 100;
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
        String commandCode = firstNonBlank(trimToNull(setting(context, "__commandCode")), COMMAND_TYPE);
        if (PREPARE_COMMAND.equals(commandCode)) {
            return prepareDraft(context);
        }
        if (COMPILE_COMMAND.equals(commandCode)) {
            return compileRevision(context);
        }
        if (REVIEW_COMMAND.equals(commandCode)) {
            return submitForReview(context);
        }
        if (PUBLISH_COMMAND.equals(commandCode)) {
            return releasePreparedRevision(context);
        }
        // Compatibility bridge for the already-published first slice. crm:release_qdp keeps its
        // original Customer Request target and payload contract. The additive lifecycle command
        // crm:publish_qdp_revision reuses this handler but targets one prepared QDP revision.
        return executeLegacyRelease(context);
    }

    private Object executeLegacyRelease(CommandContext context) {
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

    private Object prepareDraft(CommandContext context) {
        DataAccessor db = requireDataAccessor(context, "prepare QDP draft");
        FileAccessor files = requireFileAccessor(context, "prepare QDP draft");
        long tenantId = requireTenant(context, "prepare QDP draft");
        String actor = requireActor(context, "prepare QDP draft");
        Map<String, Object> payload = payload(context);

        String customerRequestId = requireExactTarget(
                context.recordId(), payload.get("crm_qdp_customer_request_id"), "Customer Request");
        Map<String, Object> request = requiredRecord(db, CUSTOMER_REQUEST_MODEL, customerRequestId,
                "Customer Request");
        validateRequestIdentityAndState(request, customerRequestId);
        Long requestVersion = requireExpectedVersion(context, request, "Customer Request");
        SidecarContext sidecar = validateRequiredPcbaSidecar(db, payload, request, customerRequestId);

        String clientRequestId = requireClientRequestId(context, "prepare QDP draft");
        String ownerScope = "tenant:" + tenantId + "/customer-request:" + customerRequestId;
        Map<String, Object> manifest = materializeFileManifest(files, payload, actor, ownerScope);
        List<Map<String, Object>> manifestFiles = manifestFiles(manifest);
        Map<String, Object> primaryFile = selectPrimaryFile(payload, manifestFiles);
        String primaryFilePid = required(primaryFile.get("filePid"),
                "Materialized primary QDP file pid is required");
        String packageHash = canonicalHash(manifest, "QDP file package");

        String requirementVersion = requiredLimited(payload.get("crm_qdp_requirement_version"),
                MAX_REQUIREMENT_VERSION_LENGTH, "Requirement Version");
        String confirmationReference = requiredLimited(payload.get("crm_qdp_customer_confirmation_ref"),
                MAX_CONFIRMATION_REFERENCE_LENGTH, "Customer confirmation reference");
        String customerConfirmedBy = requiredLimited(payload.get("crm_qdp_customer_confirmed_by"),
                128, "Customer confirmation actor");
        String customerConfirmedAt = requiredInstant(payload.get("crm_qdp_customer_confirmed_at"),
                "Customer confirmation time");
        List<Map<String, Object>> packSet = structuredObjectList(payload.get("crm_qdp_pack_set"),
                "Pack Set", true);
        validatePackSet(packSet);
        List<Map<String, Object>> downstreamImpact = structuredObjectList(
                payload.get("crm_qdp_downstream_impact"), "Downstream impact", true);
        validateDownstreamImpact(downstreamImpact);
        List<String> assumptions = structuredStringList(payload, "crm_qdp_assumptions", "Assumptions");
        List<String> exceptions = structuredStringList(
                payload, "crm_qdp_approved_exceptions", "Approved exceptions");
        String releaseNote = limitedText(payload.get("crm_qdp_release_note"),
                MAX_RELEASE_NOTE_LENGTH, "QDP release note");

        Map<String, Object> snapshot = requestSnapshot(request, customerRequestId,
                String.valueOf(requestVersion), String.valueOf(requestVersion), sidecar.snapshot());
        Map<String, Object> canonicalContent = new LinkedHashMap<>();
        canonicalContent.put("schemaVersion", QDP_SCHEMA_VERSION);
        canonicalContent.put("customerRequestId", customerRequestId);
        canonicalContent.put("requirementVersion", requirementVersion);
        canonicalContent.put("filePackageHash", packageHash);
        canonicalContent.put("customerConfirmationReference", confirmationReference);
        canonicalContent.put("customerConfirmedBy", customerConfirmedBy);
        canonicalContent.put("customerConfirmedAt", customerConfirmedAt);
        canonicalContent.put("requestSnapshot", snapshot);
        canonicalContent.put("fileManifest", manifest);
        canonicalContent.put("primaryFilePid", primaryFilePid);
        canonicalContent.put("packSet", packSet);
        canonicalContent.put("downstreamImpact", downstreamImpact);
        canonicalContent.put("assumptions", assumptions);
        canonicalContent.put("approvedExceptions", exceptions);
        putIfPresent(canonicalContent, "releaseNote", releaseNote);
        String contentHash = canonicalHash(canonicalContent, "QDP draft content");

        Map<String, Object> replay = exactIdempotencyMatch(db, customerRequestId, clientRequestId);
        if (replay != null) {
            if (!contentHash.equals(trimToNull(replay.get("crm_qdp_content_hash")))) {
                throw new IllegalStateException("QDP idempotency conflict: client request identity '"
                        + clientRequestId + "' was already used for different content");
            }
            if (!context.dryRun()) retainSourceFiles(files, manifestFiles);
            return lifecycleResult(replay, true, null);
        }

        List<Map<String, Object>> revisions = safeList(db.query(QDP_REVISION_MODEL,
                Map.of("crm_qdp_customer_request_id", customerRequestId)));
        int revisionNo = nextRevision(revisions, "crm_qdp_revision_no");
        int requirementVersionNo = nextRevision(safeList(db.query(REQUIREMENT_VERSION_MODEL,
                Map.of("crm_reqv_customer_request_id", customerRequestId))), "crm_reqv_version_no");
        String requestCode = firstNonBlank(trimToNull(request.get("crm_cr_code")), customerRequestId);
        Map<String, Object> base = latestReleaseBaseline(revisions);
        Map<String, Object> diff = versionDiff(base, requirementVersion, packageHash, packSet);
        String now = Instant.now().toString();

        Map<String, Object> filePackageRow = new LinkedHashMap<>();
        filePackageRow.put("crm_fp_code", "FP-" + safeCode(requestCode) + "-V" + String.format("%04d", requirementVersionNo));
        filePackageRow.put("crm_fp_customer_request_id", customerRequestId);
        filePackageRow.put("crm_fp_package_hash", packageHash);
        filePackageRow.put("crm_fp_manifest", manifest);
        filePackageRow.put("crm_fp_file_count", manifestFiles.size());
        filePackageRow.put("crm_fp_status", "confirmed");
        filePackageRow.put("crm_fp_created_at", now);
        filePackageRow.put("crm_fp_created_by", actor);

        Map<String, Object> requirementRow = new LinkedHashMap<>();
        requirementRow.put("crm_reqv_code", "RV-" + safeCode(requestCode) + "-V" + String.format("%04d", requirementVersionNo));
        requirementRow.put("crm_reqv_customer_request_id", customerRequestId);
        requirementRow.put("crm_reqv_version_no", requirementVersionNo);
        requirementRow.put("crm_reqv_version_label", requirementVersion);
        requirementRow.put("crm_reqv_request_row_version", String.valueOf(requestVersion));
        requirementRow.put("crm_reqv_request_snapshot", snapshot);
        requirementRow.put("crm_reqv_file_package_hash", packageHash);
        requirementRow.put("crm_reqv_status", "customer_confirmed");
        requirementRow.put("crm_reqv_created_at", now);
        requirementRow.put("crm_reqv_created_by", actor);

        if (context.dryRun()) {
            return Map.of(
                    "success", true,
                    "dryRun", true,
                    "status", "draft_validated",
                    "customerRequestId", customerRequestId,
                    "plannedRevision", revisionNo,
                    "contentHash", contentHash,
                    "filePackageHash", packageHash,
                    "versionDiff", diff);
        }

        retainSourceFiles(files, manifestFiles);
        Map<String, Object> filePackage = db.create(FILE_PACKAGE_MODEL, filePackageRow);
        String filePackagePid = required(resolvePid(filePackage), "File Package was created without a pid");
        requirementRow.put("crm_reqv_file_package_id", filePackagePid);
        Map<String, Object> requirement = db.create(REQUIREMENT_VERSION_MODEL, requirementRow);
        String requirementPid = required(resolvePid(requirement), "Requirement Version was created without a pid");

        Map<String, Object> confirmationRow = new LinkedHashMap<>();
        confirmationRow.put("crm_cc_code", "CC-" + safeCode(requestCode) + "-V" + String.format("%04d", requirementVersionNo));
        confirmationRow.put("crm_cc_customer_request_id", customerRequestId);
        confirmationRow.put("crm_cc_requirement_version_id", requirementPid);
        confirmationRow.put("crm_cc_requirement_version", requirementVersion);
        confirmationRow.put("crm_cc_file_package_id", filePackagePid);
        confirmationRow.put("crm_cc_file_package_hash", packageHash);
        confirmationRow.put("crm_cc_confirmation_ref", confirmationReference);
        confirmationRow.put("crm_cc_confirmed_by", customerConfirmedBy);
        confirmationRow.put("crm_cc_confirmed_at", customerConfirmedAt);
        confirmationRow.put("crm_cc_status", "confirmed");
        confirmationRow.put("crm_cc_recorded_by", actor);
        Map<String, Object> confirmation = db.create(CUSTOMER_CONFIRMATION_MODEL, confirmationRow);
        String confirmationPid = required(resolvePid(confirmation), "Customer Confirmation was created without a pid");

        String fileNames = safeFileNames(manifestFiles);
        Map<String, Object> qdpRow = new LinkedHashMap<>();
        qdpRow.put("crm_qdp_code", revisionCode(requestCode, revisionNo));
        qdpRow.put("crm_qdp_customer_request_id", customerRequestId);
        qdpRow.put("crm_qdp_revision_no", revisionNo);
        qdpRow.put("crm_qdp_schema_version", QDP_SCHEMA_VERSION);
        qdpRow.put("crm_qdp_expected_request_version", String.valueOf(requestVersion));
        qdpRow.put("crm_qdp_source_revision", String.valueOf(requestVersion));
        qdpRow.put("crm_qdp_requirement_version_id", requirementPid);
        qdpRow.put("crm_qdp_requirement_version", requirementVersion);
        qdpRow.put("crm_qdp_file_package_id", filePackagePid);
        qdpRow.put("crm_qdp_file_package_hash", packageHash);
        qdpRow.put("crm_qdp_customer_confirmation_id", confirmationPid);
        qdpRow.put("crm_qdp_customer_confirmation_ref", confirmationReference);
        qdpRow.put("crm_qdp_customer_confirmed_hash", packageHash);
        qdpRow.put("crm_qdp_customer_confirmed_at", customerConfirmedAt);
        qdpRow.put("crm_qdp_customer_confirmed_by", customerConfirmedBy);
        qdpRow.put("crm_qdp_qualification_verdict", sidecar.qualificationVerdict());
        if (!sidecar.qualificationEvidenceRefs().isEmpty()) {
            qdpRow.put("crm_qdp_qualification_evidence_refs", sidecar.qualificationEvidenceRefs());
        }
        qdpRow.put("crm_qdp_content_hash", contentHash);
        qdpRow.put("crm_qdp_request_snapshot", snapshot);
        qdpRow.put("crm_qdp_file_manifest", manifest);
        qdpRow.put("crm_qdp_primary_file_id", primaryFilePid);
        qdpRow.put("crm_qdp_primary_filename", primaryFile.get("fileName"));
        qdpRow.put("crm_qdp_file_names", fileNames);
        qdpRow.put("crm_qdp_pack_set", packSet);
        qdpRow.put("crm_qdp_pack_set_summary", packSetSummary(packSet));
        qdpRow.put("crm_qdp_downstream_impact", downstreamImpact);
        qdpRow.put("crm_qdp_downstream_impact_summary", downstreamSummary(downstreamImpact));
        qdpRow.put("crm_qdp_assumptions", assumptions);
        qdpRow.put("crm_qdp_approved_exceptions", exceptions);
        qdpRow.put("crm_qdp_version_diff", diff);
        qdpRow.put("crm_qdp_version_diff_summary", diff.get("summary"));
        qdpRow.put("crm_qdp_client_request_id", clientRequestId);
        qdpRow.put("crm_qdp_owner_scope", ownerScope);
        qdpRow.put("crm_qdp_status", "draft");
        qdpRow.put("crm_qdp_gate_verdict", "pending_review");
        if (releaseNote != null) qdpRow.put("crm_qdp_release_note", releaseNote);
        qdpRow.put("crm_qdp_prepared_at", now);
        qdpRow.put("crm_qdp_prepared_by", actor);
        Map<String, Object> created = db.create(QDP_REVISION_MODEL, qdpRow);
        return lifecycleResult(created, false, null);
    }

    private Object compileRevision(CommandContext context) {
        DataAccessor db = requireDataAccessor(context, "compile QDP revision");
        requireTenant(context, "compile QDP revision");
        String actor = requireActor(context, "compile QDP revision");
        Map<String, Object> commandPayload = payload(context);
        String qdpPid = requireExactTarget(
                context.recordId(), commandPayload.get("crm_qdp_revision_id"), "QDP revision");
        Map<String, Object> qdp = requiredRecord(db, QDP_REVISION_MODEL, qdpPid, "QDP revision");
        requireExactCustomerRequest(qdp, commandPayload);
        Long currentVersion = requireExpectedVersion(context, qdp, "QDP revision");
        String state = trimToNull(qdp.get("crm_qdp_status"));
        if (!Set.of("draft", "validation_failed").contains(state)) {
            throw new IllegalArgumentException("QDP revision state '" + state
                    + "' cannot compile; expected draft or validation_failed");
        }

        if (context.dryRun()) {
            validateConfirmationBinding(db, qdp);
            validateGateCompleteness(qdp);
            return lifecycleDryRun(qdp, "compilation_validated", Map.of(
                    "crm_qdp_status", "ready_for_review",
                    "crm_qdp_compilation_stage", "completed",
                    "crm_qdp_compilation_progress", 100));
        }

        String startedAt = Instant.now().toString();
        Map<String, Object> compilingPatch = new LinkedHashMap<>();
        compilingPatch.put("_expectedVersion", currentVersion);
        compilingPatch.put("crm_qdp_status", "compiling");
        compilingPatch.put("crm_qdp_gate_verdict", "pending_review");
        compilingPatch.put("crm_qdp_compilation_stage", "validating_confirmation");
        compilingPatch.put("crm_qdp_compilation_progress", 10);
        compilingPatch.put("crm_qdp_compilation_outcome", "running");
        compilingPatch.put("crm_qdp_compilation_summary", "QDP compilation is validating GT-D04 inputs.");
        compilingPatch.put("crm_qdp_validation_failure_summary", "");
        compilingPatch.put("crm_qdp_compilation_started_at", startedAt);
        compilingPatch.put("crm_qdp_compilation_started_by", actor);
        Map<String, Object> compiling = db.update(QDP_REVISION_MODEL, qdpPid, compilingPatch);
        if (compiling == null) {
            throw new IllegalStateException("QDP revision disappeared before compilation could start");
        }
        reportCompilationProgress(context, 10, 0, 3, 0, 0, 0);

        try {
            validateConfirmationBinding(db, compiling);
            checkpointCompilation(db, qdpPid, compiling, "validating_gate", 45,
                    "Customer confirmation binding passed; validating GT-D04 completeness.");
            reportCompilationProgress(context, 45, 1, 3, 1, 0, 0);

            validateGateCompleteness(compiling);
            checkpointCompilation(db, qdpPid, compiling, "assembling_result", 75,
                    "GT-D04 content is complete; assembling the review result.");
            reportCompilationProgress(context, 75, 2, 3, 2, 0, 0);

            int warningCount = compilationWarningCount(compiling);
            String outcome = warningCount > 0 ? "partial_success" : "success";
            String verdict = warningCount > 0 ? "ready_with_approved_exception" : "ready";
            String summary = warningCount > 0
                    ? "Compiled 3 GT-D04 checks with " + warningCount
                            + " approved exception warning(s); review is required before release."
                    : "Compiled 3 GT-D04 checks with no warnings; ready for review.";
            String completedAt = Instant.now().toString();
            Map<String, Object> completedPatch = new LinkedHashMap<>();
            completedPatch.put("_expectedVersion", positiveLong(compiling.get("row_version")));
            completedPatch.put("crm_qdp_status", "ready_for_review");
            completedPatch.put("crm_qdp_gate_verdict", verdict);
            completedPatch.put("crm_qdp_compilation_stage", "completed");
            completedPatch.put("crm_qdp_compilation_progress", 100);
            completedPatch.put("crm_qdp_compilation_outcome", outcome);
            completedPatch.put("crm_qdp_compilation_summary", summary);
            completedPatch.put("crm_qdp_validation_failure_summary", "");
            completedPatch.put("crm_qdp_compiled_at", completedAt);
            completedPatch.put("crm_qdp_compiled_by", actor);
            Map<String, Object> completed = db.update(QDP_REVISION_MODEL, qdpPid, completedPatch);
            if (completed == null) {
                throw new IllegalStateException("QDP revision disappeared before compilation could complete");
            }
            reportCompilationProgress(context, 100, 3, 3, 3, 0, warningCount);
            Map<String, Object> result = lifecycleResult(completed, false, null);
            result.put("outcome", outcome);
            result.put("outcomeLabel", warningCount > 0
                    ? "部分成功 / Partial Success"
                    : "成功 / Success");
            result.put("passedChecks", 3);
            result.put("warningCount", warningCount);
            result.put("failedChecks", 0);
            result.put("message", summary);
            return result;
        } catch (IllegalArgumentException | IllegalStateException validationFailure) {
            String message = limitedFailureSummary(validationFailure);
            Map<String, Object> failurePatch = new LinkedHashMap<>();
            failurePatch.put("_expectedVersion", positiveLong(compiling.get("row_version")));
            failurePatch.put("crm_qdp_status", "validation_failed");
            failurePatch.put("crm_qdp_gate_verdict", "blocked");
            failurePatch.put("crm_qdp_compilation_stage", "validation_failed");
            failurePatch.put("crm_qdp_compilation_progress", 100);
            failurePatch.put("crm_qdp_compilation_outcome", "validation_failed");
            failurePatch.put("crm_qdp_compilation_summary",
                    "Compilation stopped because one or more GT-D04 checks failed.");
            failurePatch.put("crm_qdp_validation_failure_summary", message);
            failurePatch.put("crm_qdp_compiled_at", Instant.now().toString());
            failurePatch.put("crm_qdp_compiled_by", actor);
            db.update(QDP_REVISION_MODEL, qdpPid, failurePatch);
            reportCompilationProgress(context, 100, 3, 3, 2, 1, 0);
            throw validationFailure;
        }
    }

    private static void checkpointCompilation(DataAccessor db, String qdpPid,
                                              Map<String, Object> compiling, String stage,
                                              int progress, String summary) {
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("_expectedVersion", positiveLong(compiling.get("row_version")));
        patch.put("crm_qdp_compilation_stage", stage);
        patch.put("crm_qdp_compilation_progress", progress);
        patch.put("crm_qdp_compilation_summary", summary);
        Map<String, Object> updated = db.update(QDP_REVISION_MODEL, qdpPid, patch);
        if (updated == null) {
            throw new IllegalStateException("QDP revision disappeared during compilation stage " + stage);
        }
        Map<String, Object> snapshot = new LinkedHashMap<>(updated);
        compiling.clear();
        compiling.putAll(snapshot);
    }

    private static int compilationWarningCount(Map<String, Object> qdp) {
        Object exceptions = parseJsonValue(qdp.get("crm_qdp_approved_exceptions"), "Approved exceptions");
        int count = exceptions instanceof List<?> list ? list.size() : 0;
        if ("conditional".equals(trimToNull(qdp.get("crm_qdp_qualification_verdict")))) {
            count++;
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private static void reportCompilationProgress(CommandContext context, int percent,
                                                  int processed, int total, int ok,
                                                  int failed, int skipped) {
        Object reporter = setting(context, "__progressReporter");
        if (reporter instanceof BiConsumer<?, ?> rawReporter) {
            BiConsumer<Integer, String> typed = (BiConsumer<Integer, String>) rawReporter;
            typed.accept(percent, "{\"processed\":" + processed
                    + ",\"total\":" + total
                    + ",\"ok\":" + ok
                    + ",\"failed\":" + failed
                    + ",\"skipped\":" + skipped + "}");
        }
    }

    private static String limitedFailureSummary(RuntimeException failure) {
        String message = firstNonBlank(trimToNull(failure.getMessage()), failure.getClass().getSimpleName());
        return message.length() <= 2_000 ? message : message.substring(0, 2_000);
    }

    private Object submitForReview(CommandContext context) {
        DataAccessor db = requireDataAccessor(context, "submit QDP review");
        requireTenant(context, "submit QDP review");
        String actor = requireActor(context, "submit QDP review");
        Map<String, Object> payload = payload(context);
        String qdpPid = requireExactTarget(context.recordId(), payload.get("crm_qdp_revision_id"), "QDP revision");
        Map<String, Object> qdp = requiredRecord(db, QDP_REVISION_MODEL, qdpPid, "QDP revision");
        requireExactCustomerRequest(qdp, payload);
        requireExpectedVersion(context, qdp, "QDP revision");
        String state = trimToNull(qdp.get("crm_qdp_status"));
        if (!Set.of("draft", "validation_failed").contains(state)) {
            throw new IllegalArgumentException("QDP revision state '" + state
                    + "' cannot enter review; expected draft or validation_failed");
        }
        validateConfirmationBinding(db, qdp);
        validateGateCompleteness(qdp);
        String verdict = structuredListIsEmpty(qdp.get("crm_qdp_approved_exceptions"))
                ? "ready" : "ready_with_approved_exception";
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("crm_qdp_status", "ready_for_review");
        patch.put("crm_qdp_gate_verdict", verdict);
        patch.put("crm_qdp_review_submitted_at", Instant.now().toString());
        patch.put("crm_qdp_review_submitted_by", actor);
        if (context.dryRun()) return lifecycleDryRun(qdp, "review_validated", patch);
        Map<String, Object> updated = db.update(QDP_REVISION_MODEL, qdpPid, patch);
        return lifecycleResult(updated, false, null);
    }

    private Object releasePreparedRevision(CommandContext context) {
        DataAccessor db = requireDataAccessor(context, "release QDP revision");
        FileAccessor files = requireFileAccessor(context, "release QDP revision");
        requireTenant(context, "release QDP revision");
        String actor = requireActor(context, "release QDP revision");
        Map<String, Object> payload = payload(context);
        String qdpPid = requireExactTarget(context.recordId(), payload.get("crm_qdp_revision_id"), "QDP revision");
        Map<String, Object> qdp = requiredRecord(db, QDP_REVISION_MODEL, qdpPid, "QDP revision");
        String customerRequestId = requireExactCustomerRequest(qdp, payload);
        requireExpectedVersion(context, qdp, "QDP revision");
        String state = trimToNull(qdp.get("crm_qdp_status"));
        if (!"ready_for_review".equals(state)) {
            throw new IllegalArgumentException("QDP revision state '" + state
                    + "' cannot be released; expected ready_for_review");
        }
        Map<String, Object> request = requiredRecord(db, CUSTOMER_REQUEST_MODEL, customerRequestId,
                "Customer Request");
        validateRequestIdentityAndState(request, customerRequestId);
        Long currentRequestVersion = positiveLong(request.get("row_version"));
        String frozenRequestVersion = trimToNull(qdp.get("crm_qdp_expected_request_version"));
        if (currentRequestVersion == null || !String.valueOf(currentRequestVersion).equals(frozenRequestVersion)) {
            throw new IllegalStateException("QDP source Customer Request is stale; prepare a new revision");
        }
        SidecarContext sidecar = validateRequiredPcbaSidecar(db, payload, request, customerRequestId);
        if (!sidecar.qualificationVerdict().equals(trimToNull(qdp.get("crm_qdp_qualification_verdict")))) {
            throw new IllegalStateException("QDP qualification facts changed after draft preparation; prepare a new revision");
        }
        validateConfirmationBinding(db, qdp);
        validateGateCompleteness(qdp);
        List<Map<String, Object>> frozenFiles = manifestFiles(requiredMap(
                qdp.get("crm_qdp_file_manifest"), "QDP file manifest"));
        String releaseNote = firstNonBlank(limitedText(payload.get("crm_qdp_release_note"),
                MAX_RELEASE_NOTE_LENGTH, "QDP release note"), trimToNull(qdp.get("crm_qdp_release_note")));
        if (context.dryRun()) {
            return lifecycleDryRun(qdp, "release_validated", Map.of("crm_qdp_status", "released"));
        }
        // Reassert retained-source availability immediately before the formal release. Any host
        // retention failure aborts the command transaction and leaves the revision in review.
        retainSourceFiles(files, frozenFiles);
        String now = Instant.now().toString();
        Map<String, Object> releasePatch = new LinkedHashMap<>();
        releasePatch.put("crm_qdp_status", "released");
        releasePatch.put("crm_qdp_gate_verdict", trimToNull(qdp.get("crm_qdp_gate_verdict")));
        releasePatch.put("crm_qdp_released_at", now);
        releasePatch.put("crm_qdp_released_by", actor);
        if (releaseNote != null) releasePatch.put("crm_qdp_release_note", releaseNote);
        Map<String, Object> released = db.update(QDP_REVISION_MODEL, qdpPid, releasePatch);

        Map<String, Object> previous = latestOtherReleased(db, customerRequestId, qdpPid);
        String supersededPid = null;
        if (previous != null) {
            supersededPid = resolvePid(previous);
            Map<String, Object> supersedePatch = new LinkedHashMap<>();
            supersedePatch.put("crm_qdp_status", "superseded");
            supersedePatch.put("crm_qdp_superseded_at", now);
            supersedePatch.put("crm_qdp_superseded_by", actor);
            supersedePatch.put("crm_qdp_superseded_by_revision_id", qdpPid);
            Long previousVersion = positiveLong(previous.get("row_version"));
            if (previousVersion != null) supersedePatch.put("_expectedVersion", previousVersion);
            db.update(QDP_REVISION_MODEL, supersededPid, supersedePatch);
        }
        linkPcbaSidecar(db, sidecar.pid(), qdpPid);
        return lifecycleResult(released, false, supersededPid);
    }

    private static DataAccessor requireDataAccessor(CommandContext context, String action) {
        DataAccessor db = context.dataAccessor();
        if (db == null) throw new IllegalStateException("DataAccessor unavailable; cannot " + action);
        return db;
    }

    private static FileAccessor requireFileAccessor(CommandContext context, String action) {
        FileAccessor files = context.fileAccessor();
        if (files == null) throw new IllegalStateException("FileAccessor unavailable; cannot " + action);
        return files;
    }

    private static long requireTenant(CommandContext context, String action) {
        Long tenantId = context.tenantId();
        if (tenantId == null || tenantId <= 0) {
            throw new IllegalStateException("Authenticated tenant context is required to " + action);
        }
        return tenantId;
    }

    private static String requireActor(CommandContext context, String action) {
        return required(setting(context, "__currentUser"),
                "Authenticated actor context is required to " + action);
    }

    private static Map<String, Object> payload(CommandContext context) {
        return context.payload() == null ? Map.of() : context.payload();
    }

    private static String requireExactTarget(Object target, Object payloadPid, String label) {
        String targetPid = canonicalPid(target, "Command target " + label + " pid");
        String suppliedPid = canonicalPid(payloadPid, "Payload " + label + " pid");
        if (targetPid == null || suppliedPid == null) {
            throw new IllegalArgumentException(label + " pid is required in both command target and payload");
        }
        if (!targetPid.equals(suppliedPid)) {
            throw new IllegalArgumentException(label + " does not match the command target");
        }
        return targetPid;
    }

    private static Map<String, Object> requiredRecord(DataAccessor db, String model, String pid, String label) {
        Map<String, Object> record = db.getById(model, pid);
        if (record == null) throw new IllegalArgumentException(label + " not found: " + pid);
        if (!pid.equals(resolvePid(record))) throw new IllegalStateException(label + " identity mismatch: " + pid);
        return record;
    }

    private static void validateRequestIdentityAndState(Map<String, Object> request, String requestPid) {
        if (!requestPid.equals(firstNonBlank(trimToNull(request.get("pid")), requestPid))) {
            throw new IllegalStateException("Customer Request identity mismatch: " + requestPid);
        }
        String state = trimToNull(request.get("crm_cr_status"));
        if (!RELEASABLE_REQUEST_STATES.contains(state)) {
            throw new IllegalArgumentException("Customer Request state '" + state
                    + "' cannot prepare or release QDP; expected one of " + RELEASABLE_REQUEST_STATES);
        }
    }

    private static Long requireExpectedVersion(CommandContext context, Map<String, Object> record, String label) {
        Long current = positiveLong(record.get("row_version"));
        if (current == null) throw new IllegalStateException(label + " row version is unavailable; fail-closed");
        Long expected = context.expectedVersion();
        if (expected == null || expected <= 0) {
            throw new IllegalStateException("Trusted expected " + label + " version is required");
        }
        if (!expected.equals(current)) {
            throw new IllegalStateException(label + " version is stale: expected " + expected + " but current is " + current);
        }
        return current;
    }

    private static String requireClientRequestId(CommandContext context, String action) {
        String value = required(context.clientRequestId(),
                "Trusted client request identity is required to " + action);
        if (value.length() > MAX_CLIENT_REQUEST_ID_LENGTH) {
            throw new IllegalArgumentException("Client request identity must not exceed "
                    + MAX_CLIENT_REQUEST_ID_LENGTH + " characters");
        }
        return value;
    }

    private static Map<String, Object> exactIdempotencyMatch(
            DataAccessor db, String customerRequestId, String clientRequestId) {
        List<Map<String, Object>> matches = safeList(db.query(QDP_REVISION_MODEL, Map.of(
                "crm_qdp_customer_request_id", customerRequestId,
                "crm_qdp_client_request_id", clientRequestId)));
        if (matches.size() > 1) {
            throw new IllegalStateException("QDP idempotency invariant is broken for client request identity '"
                    + clientRequestId + "'");
        }
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private static int nextRevision(List<Map<String, Object>> rows, String field) {
        return rows.stream().mapToInt(row -> positiveInt(row.get(field))).max().orElse(0) + 1;
    }

    private static String safeCode(String source) {
        String normalized = source.replaceAll("[^A-Za-z0-9-]", "-");
        return normalized.length() > 48 ? normalized.substring(0, 48) : normalized;
    }

    private static String safeFileNames(List<Map<String, Object>> files) {
        String value = String.join(", ", files.stream()
                .map(file -> required(file.get("fileName"), "Materialized QDP file name is required"))
                .sorted().toList());
        if (value.length() > 2_000) {
            throw new IllegalArgumentException("Materialized QDP file names must not exceed 2000 characters");
        }
        return value;
    }

    private static String canonicalHash(Object value, String label) {
        try {
            return hex(sha256Digest().digest(MAPPER.writeValueAsBytes(value)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to canonicalize " + label, e);
        }
    }

    private static String requiredLimited(Object value, int max, String label) {
        String result = required(value, label + " is required");
        if (result.length() > max) {
            throw new IllegalArgumentException(label + " must not exceed " + max + " characters");
        }
        return result;
    }

    private static String requiredInstant(Object value, String label) {
        String raw = requiredLimited(value, 64, label);
        try {
            return Instant.parse(raw).toString();
        } catch (java.time.format.DateTimeParseException e) {
            try {
                return java.time.OffsetDateTime.parse(raw).toInstant().toString();
            } catch (java.time.format.DateTimeParseException offsetFailure) {
                throw new IllegalArgumentException(label + " must be an ISO-8601 instant with timezone", offsetFailure);
            }
        }
    }

    private static Object parseJsonValue(Object raw, String label) {
        if (!(raw instanceof String text)) return raw;
        if (text.isBlank()) return null;
        try {
            return MAPPER.readValue(text, Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(label + " must be valid JSON", e);
        }
    }

    private static List<Map<String, Object>> structuredObjectList(Object raw, String label, boolean required) {
        Object parsed = parseJsonValue(raw, label);
        if (parsed == null) {
            if (required) throw new IllegalArgumentException(label + " is required");
            return List.of();
        }
        if (!(parsed instanceof List<?> values)) throw new IllegalArgumentException(label + " must be a JSON array");
        if (values.size() > MAX_STRUCTURED_ITEMS) {
            throw new IllegalArgumentException(label + " must not contain more than " + MAX_STRUCTURED_ITEMS + " items");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException(label + " items must be objects");
            Map<String, Object> item = new LinkedHashMap<>();
            map.forEach((key, itemValue) -> {
                if (key != null) item.put(String.valueOf(key), itemValue);
            });
            result.add(Map.copyOf(item));
        }
        if (required && result.isEmpty()) throw new IllegalArgumentException(label + " must contain at least one item");
        return List.copyOf(result);
    }

    private static List<String> structuredStringList(Map<String, Object> payload, String key, String label) {
        if (!payload.containsKey(key)) throw new IllegalArgumentException(label + " must be explicitly supplied");
        Object parsed = parseJsonValue(payload.get(key), label);
        if (!(parsed instanceof List<?> values)) throw new IllegalArgumentException(label + " must be a JSON array");
        if (values.size() > MAX_STRUCTURED_ITEMS) {
            throw new IllegalArgumentException(label + " must not contain more than " + MAX_STRUCTURED_ITEMS + " items");
        }
        List<String> result = new ArrayList<>();
        for (Object value : values) {
            String item = requiredLimited(value, 500, label + " item");
            result.add(item);
        }
        return List.copyOf(result);
    }

    private static void validatePackSet(List<Map<String, Object>> packSet) {
        Set<String> identities = new HashSet<>();
        for (Map<String, Object> pack : packSet) {
            String code = requiredLimited(pack.get("packCode"), 64, "Pack code");
            String version = requiredLimited(pack.get("version"), 64, "Pack version");
            String hash = requiredLimited(pack.get("contentHash"), 64, "Pack content hash");
            if (!hash.matches("^[a-f0-9]{64}$")) throw new IllegalArgumentException("Pack content hash must be SHA-256 hex");
            if (!identities.add(code + "@" + version)) throw new IllegalArgumentException("Duplicate Pack Set identity: " + code + "@" + version);
        }
    }

    private static void validateDownstreamImpact(List<Map<String, Object>> impacts) {
        Set<String> identities = new HashSet<>();
        for (Map<String, Object> impact : impacts) {
            String objectType = requiredLimited(impact.get("objectType"), 64, "Downstream object type");
            String objectPid = requiredLimited(impact.get("objectPid"), 128, "Downstream object pid");
            requiredLimited(impact.get("impact"), 500, "Downstream impact");
            requiredLimited(impact.get("owner"), 128, "Downstream owner");
            requiredLimited(impact.get("disposition"), 128, "Downstream disposition");
            if (!identities.add(objectType + ":" + objectPid)) {
                throw new IllegalArgumentException("Duplicate downstream object: " + objectType + ":" + objectPid);
            }
        }
    }

    private static Map<String, Object> latestReleaseBaseline(List<Map<String, Object>> revisions) {
        return revisions.stream()
                .filter(row -> Set.of("released", "superseded").contains(trimToNull(row.get("crm_qdp_status"))))
                .max(Comparator.comparingInt(row -> positiveInt(row.get("crm_qdp_revision_no"))))
                .orElse(null);
    }

    private static Map<String, Object> versionDiff(Map<String, Object> base, String requirementVersion,
                                                   String packageHash, List<Map<String, Object>> packSet) {
        Map<String, Object> diff = new LinkedHashMap<>();
        if (base == null) {
            diff.put("baseRevisionId", null);
            diff.put("requirementVersionChanged", true);
            diff.put("filePackageChanged", true);
            diff.put("packSetChanged", true);
            diff.put("summary", "Initial QDP revision; no released baseline");
            return diff;
        }
        boolean requirementChanged = !java.util.Objects.equals(
                requirementVersion, trimToNull(base.get("crm_qdp_requirement_version")));
        boolean packageChanged = !java.util.Objects.equals(
                packageHash, trimToNull(base.get("crm_qdp_file_package_hash")));
        boolean packChanged = !canonicalHash(packSet, "Pack Set").equals(
                canonicalHash(parseJsonValue(base.get("crm_qdp_pack_set"), "baseline Pack Set"), "baseline Pack Set"));
        diff.put("baseRevisionId", resolvePid(base));
        diff.put("baseRevision", positiveInt(base.get("crm_qdp_revision_no")));
        diff.put("requirementVersionChanged", requirementChanged);
        diff.put("filePackageChanged", packageChanged);
        diff.put("packSetChanged", packChanged);
        List<String> changes = new ArrayList<>();
        if (requirementChanged) changes.add("Requirement Version");
        if (packageChanged) changes.add("File Package Hash");
        if (packChanged) changes.add("Pack Set");
        diff.put("summary", changes.isEmpty() ? "No baseline content changes" : "Changed: " + String.join(", ", changes));
        return diff;
    }

    private static String packSetSummary(List<Map<String, Object>> packSet) {
        return String.join(", ", packSet.stream()
                .map(pack -> trimToNull(pack.get("packCode")) + "@" + trimToNull(pack.get("version")))
                .toList());
    }

    private static String downstreamSummary(List<Map<String, Object>> impacts) {
        long blocked = impacts.stream().filter(impact -> "blocked".equalsIgnoreCase(trimToNull(impact.get("disposition")))).count();
        return impacts.size() + " downstream object(s), " + blocked + " blocked";
    }

    private static String requireExactCustomerRequest(Map<String, Object> qdp, Map<String, Object> payload) {
        String stored = required(qdp.get("crm_qdp_customer_request_id"), "QDP Customer Request is required");
        String supplied = canonicalPid(payload.get("crm_qdp_customer_request_id"), "Payload Customer Request pid");
        if (supplied == null || !stored.equals(supplied)) {
            throw new IllegalArgumentException("QDP Customer Request does not match the stored revision");
        }
        return stored;
    }

    private static void validateConfirmationBinding(DataAccessor db, Map<String, Object> qdp) {
        String requirementPid = required(qdp.get("crm_qdp_requirement_version_id"), "QDP Requirement Version is required");
        String filePackagePid = required(qdp.get("crm_qdp_file_package_id"), "QDP File Package is required");
        String confirmationPid = required(qdp.get("crm_qdp_customer_confirmation_id"), "QDP Customer Confirmation is required");
        String requirementVersion = required(qdp.get("crm_qdp_requirement_version"), "QDP Requirement Version label is required");
        String packageHash = required(qdp.get("crm_qdp_file_package_hash"), "QDP File Package Hash is required");
        if (!packageHash.equals(required(qdp.get("crm_qdp_customer_confirmed_hash"), "Customer-confirmed hash is required"))) {
            throw new IllegalStateException("Customer Confirmation is not bound to the QDP File Package Hash");
        }
        Map<String, Object> requirement = requiredRecord(db, REQUIREMENT_VERSION_MODEL, requirementPid, "Requirement Version");
        Map<String, Object> filePackage = requiredRecord(db, FILE_PACKAGE_MODEL, filePackagePid, "File Package");
        Map<String, Object> confirmation = requiredRecord(db, CUSTOMER_CONFIRMATION_MODEL, confirmationPid, "Customer Confirmation");
        if (!requirementVersion.equals(trimToNull(requirement.get("crm_reqv_version_label")))
                || !filePackagePid.equals(trimToNull(requirement.get("crm_reqv_file_package_id")))
                || !packageHash.equals(trimToNull(requirement.get("crm_reqv_file_package_hash")))
                || !packageHash.equals(trimToNull(filePackage.get("crm_fp_package_hash")))
                || !requirementPid.equals(trimToNull(confirmation.get("crm_cc_requirement_version_id")))
                || !filePackagePid.equals(trimToNull(confirmation.get("crm_cc_file_package_id")))
                || !packageHash.equals(trimToNull(confirmation.get("crm_cc_file_package_hash")))
                || !"confirmed".equals(trimToNull(confirmation.get("crm_cc_status")))) {
            throw new IllegalStateException(
                    "客户确认与当前需求版本或文件包不一致；请重新确认当前文件后再编制 / "
                            + "Customer Confirmation does not match the current requirement version or file package");
        }
    }

    private static void validateGateCompleteness(Map<String, Object> qdp) {
        required(qdp.get("crm_qdp_content_hash"), "QDP content hash is required");
        required(qdp.get("crm_qdp_customer_confirmation_ref"), "Customer Confirmation reference is required");
        required(qdp.get("crm_qdp_downstream_impact_summary"), "Downstream responsibility is required");
        if (structuredListIsEmpty(qdp.get("crm_qdp_pack_set"))) throw new IllegalStateException("Pack Set is required");
        if (structuredListIsEmpty(qdp.get("crm_qdp_downstream_impact"))) throw new IllegalStateException("Downstream impact is required");
        if (parseJsonValue(qdp.get("crm_qdp_assumptions"), "Assumptions") == null) throw new IllegalStateException("Assumptions are required");
        if (parseJsonValue(qdp.get("crm_qdp_approved_exceptions"), "Approved exceptions") == null) throw new IllegalStateException("Approved exceptions are required");
    }

    private static boolean structuredListIsEmpty(Object raw) {
        Object parsed = parseJsonValue(raw, "structured list");
        return !(parsed instanceof List<?> list) || list.isEmpty();
    }

    private static Map<String, Object> requiredMap(Object raw, String label) {
        Object parsed = parseJsonValue(raw, label);
        if (!(parsed instanceof Map<?, ?> map)) throw new IllegalStateException(label + " is not an object");
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (key != null) result.put(String.valueOf(key), value);
        });
        return result;
    }

    private static Map<String, Object> latestOtherReleased(DataAccessor db, String customerRequestId, String currentPid) {
        return safeList(db.query(QDP_REVISION_MODEL, Map.of(
                        "crm_qdp_customer_request_id", customerRequestId,
                        "crm_qdp_status", "released"))).stream()
                .filter(row -> !currentPid.equals(resolvePid(row)))
                .max(Comparator.comparingInt(row -> positiveInt(row.get("crm_qdp_revision_no"))))
                .orElse(null);
    }

    private static Map<String, Object> lifecycleDryRun(
            Map<String, Object> qdp, String status, Map<String, Object> plannedPatch) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("dryRun", true);
        result.put("status", status);
        result.put("qdpRevisionId", resolvePid(qdp));
        result.put("plannedPatch", Map.copyOf(plannedPatch));
        return result;
    }

    private static Map<String, Object> lifecycleResult(
            Map<String, Object> qdp, boolean idempotent, String supersededPid) {
        String pid = resolvePid(qdp);
        if (pid == null) throw new IllegalStateException("QDP revision has no pid");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("qdpRevisionId", pid);
        result.put("customerRequestId", qdp.get("crm_qdp_customer_request_id"));
        result.put("revision", positiveInt(qdp.get("crm_qdp_revision_no")));
        result.put("contentHash", qdp.get("crm_qdp_content_hash"));
        result.put("filePackageHash", qdp.get("crm_qdp_file_package_hash"));
        result.put("status", qdp.get("crm_qdp_status"));
        result.put("idempotent", idempotent);
        if (supersededPid != null) result.put("supersededRevisionId", supersededPid);
        return result;
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
