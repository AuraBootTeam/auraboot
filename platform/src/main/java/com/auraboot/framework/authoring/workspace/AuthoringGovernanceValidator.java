package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.authoring.policy.AuthoringCapabilityRegistry;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.PublishPolicy;
import com.auraboot.framework.authoring.workspace.AuthoringGovernanceRepository.GovernanceRow;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.FORBIDDEN;

/** Validates workflow, manifest and base-version invariants for authoring transitions. */
@Component
public class AuthoringGovernanceValidator {

    private final AuthoringCapabilityRegistry capabilityRegistry;
    private final AuthoringActiveReleaseResolver activeReleaseResolver;
    private final AuthoringGovernanceRepository governanceRepository;
    private final AuthoringImpactAnalyzer impactAnalyzer;
    private final AuthoringPageSnapshotFactory snapshotFactory;
    private final PageSchemaMapper pageSchemaMapper;

    public AuthoringGovernanceValidator(
            AuthoringCapabilityRegistry capabilityRegistry,
            AuthoringActiveReleaseResolver activeReleaseResolver,
            AuthoringGovernanceRepository governanceRepository,
            AuthoringImpactAnalyzer impactAnalyzer,
            AuthoringPageSnapshotFactory snapshotFactory,
            PageSchemaMapper pageSchemaMapper) {
        this.capabilityRegistry = capabilityRegistry;
        this.activeReleaseResolver = activeReleaseResolver;
        this.governanceRepository = governanceRepository;
        this.impactAnalyzer = impactAnalyzer;
        this.snapshotFactory = snapshotFactory;
        this.pageSchemaMapper = pageSchemaMapper;
    }

    public void requireFresh(GovernanceRow row) {
        try {
            requireCurrentManifest(row);
            AuthoringActiveReleaseResolver.ActiveRelease active =
                    activeReleaseResolver.findByResource(
                            row.tenantId(), row.envId(), "PAGE_SCHEMA", row.resourcePid());
            if (row.baseReleasePid() != null) {
                requireCurrentReleaseBase(row, active);
            } else {
                requireCurrentLegacyBase(row, active);
            }
            requireCurrentDependencies(row);
        } catch (AuthoringStaleStateException exception) {
            throw exception;
        } catch (ResponseStatusException exception) {
            throw stale(row, exception.getReason());
        }
    }

    public void requirePrepared(GovernanceRow row) {
        if (!"VALID".equals(row.validationState()) || !"KNOWN".equals(row.impactState())) {
            throw conflict("authoring.submit.not-ready");
        }
    }

    public void requirePublishable(GovernanceRow row) {
        if (!"VALID".equals(row.validationState())
                || !"KNOWN".equals(row.impactState())
                || !"READY".equals(row.publishState())) {
            throw conflict("authoring.publish.not-ready");
        }
        if (approvalRequired(row) && !governanceRepository.hasApprovedRevision(row)) {
            throw conflict("authoring.publish.approval-stale");
        }
    }

    public boolean approvalRequired(GovernanceRow row) {
        return PublishPolicy.valueOf(row.publishPolicy()) != PublishPolicy.DIRECT_ALLOWED;
    }

    public void requireFourEyes(GovernanceRow row, long reviewerUserId) {
        if (row.ownerUserId() == reviewerUserId) {
            throw new ResponseStatusException(FORBIDDEN, "authoring.approval.four-eyes-required");
        }
    }

    public void requireRevision(GovernanceRow row, long expectedRevision) {
        if (row.revision() != expectedRevision) {
            throw conflict("authoring.revision.conflict");
        }
    }

    public void requireStatus(GovernanceRow row, String... statuses) {
        for (String status : statuses) {
            if (status.equals(row.status())) {
                return;
            }
        }
        throw conflict("authoring.workflow.invalid-state");
    }

    private void requireCurrentManifest(GovernanceRow row) {
        String currentChecksum = capabilityRegistry.checksum();
        if (!currentChecksum.equals(row.manifestChecksum())
                || !currentChecksum.equals(row.draftManifestChecksum())) {
            throw conflict("authoring.validation.manifest-stale");
        }
    }

    private void requireCurrentReleaseBase(
            GovernanceRow row,
            AuthoringActiveReleaseResolver.ActiveRelease active) {
        if (active == null
                || !row.baseReleasePid().equals(active.releasePid())
                || row.baseVersion() != active.channelVersion()
                || !row.baseChecksum().equals(active.snapshotChecksum())) {
            throw conflict("authoring.validation.base-release-stale");
        }
    }

    private void requireCurrentLegacyBase(
            GovernanceRow row,
            AuthoringActiveReleaseResolver.ActiveRelease active) {
        if (active != null) {
            throw conflict("authoring.validation.base-release-stale");
        }
        PageSchema page = pageSchemaMapper.selectByPid(row.resourcePid());
        if (page == null) {
            throw conflict("authoring.validation.base-missing");
        }
        long currentVersion = snapshotFactory.baseVersion(page);
        String currentChecksum = snapshotFactory.checksum(snapshotFactory.create(page));
        if (currentVersion != row.baseVersion() || !currentChecksum.equals(row.baseChecksum())) {
            throw conflict("authoring.validation.base-stale");
        }
    }

    private void requireCurrentDependencies(GovernanceRow row) {
        if (!"KNOWN".equals(row.impactState())) {
            return;
        }
        AuthoringImpactAnalyzer.ImpactResult current = impactAnalyzer.analyze(
                row.tenantId(), row.snapshot());
        if (!current.known()) {
            throw stale(row, "authoring.impact." + current.failureCode().toLowerCase());
        }
        if (row.impactDependencyChecksum() == null
                || !row.impactDependencyChecksum().equals(current.dependencyChecksum())) {
            throw stale(row, "authoring.validation.dependency-stale");
        }
    }

    private AuthoringStaleStateException stale(GovernanceRow row, String reason) {
        governanceRepository.markStale(row, reason);
        return new AuthoringStaleStateException(reason);
    }

    private ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(CONFLICT, reason);
    }
}
