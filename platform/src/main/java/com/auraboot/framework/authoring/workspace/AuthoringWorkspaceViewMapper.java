package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.SessionView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ImpactDependencyView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ImpactSummaryView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.OwnershipView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ValidationIssueView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ValidationSummaryView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.WriterLeaseView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceRepository.ValidationRunSummary;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceRepository.ImpactRunSummary;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceRepository.WorkspaceRow;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.stream.StreamSupport;

/** Maps the persisted aggregate to the client-safe session view. */
@Component
public class AuthoringWorkspaceViewMapper {

    private final AuthoringDatabaseClock databaseClock;

    public AuthoringWorkspaceViewMapper(AuthoringDatabaseClock databaseClock) {
        this.databaseClock = databaseClock;
    }

    public SessionView toView(WorkspaceRow row, long currentUserId) {
        Instant now = databaseClock.now();
        String sessionState = row.expiresAt().isAfter(now) ? row.sessionState() : "EXPIRED";
        String leaseStatus;
        if (!row.leasedUntil().isAfter(now)) {
            leaseStatus = "EXPIRED";
        } else if (row.leaseSessionId() == row.sessionId()
                && row.leaseHolderUserId() == currentUserId) {
            leaseStatus = "OWNED";
        } else if (row.leaseHolderUserId() == currentUserId) {
            leaseStatus = "HELD_BY_OTHER_SESSION";
        } else {
            leaseStatus = "HELD_BY_OTHER";
        }
        return new SessionView(
                row.sessionPid(),
                row.changeSetPid(),
                row.pagePid(),
                new OwnershipView(
                        row.ownershipScope(),
                        row.sourceOwnershipScope(),
                        row.sourceResourcePid(),
                        row.overridePid(),
                        row.changeSetOrigin(),
                        row.overridePid() != null,
                        !"PLATFORM".equals(row.sourceOwnershipScope())
                                && !"APPLICATION".equals(row.sourceOwnershipScope()),
                        row.sourceOwnershipScope()),
                row.changeSetOwnerUserId(),
                row.changeSetStatus(),
                row.workspaceMode(),
                sessionState,
                row.changeSetRevision(),
                row.riskLevel(),
                row.route(),
                row.publishPolicy(),
                row.validationState(),
                validation(row.validation()),
                row.impactState(),
                impact(row.impact()),
                row.approvalState(),
                row.publishState(),
                row.manifestChecksum(),
                row.snapshot(),
                row.interactionContext(),
                new WriterLeaseView(leaseStatus, row.leaseRevision(), row.leasedUntil()),
                row.expiresAt());
    }

    private ValidationSummaryView validation(ValidationRunSummary summary) {
        if (summary == null) {
            return null;
        }
        return new ValidationSummaryView(
                summary.validationRunPid(),
                summary.revision(),
                summary.status(),
                summary.errorCount(),
                StreamSupport.stream(summary.issues().spliterator(), false)
                        .map(issue -> new ValidationIssueView(
                                issue.path("code").asText(),
                                issue.path("severity").asText(),
                                nullableText(issue.get("changeItemPid")),
                                nullableText(issue.get("blockId")),
                                issue.path("propertyPath").asText(),
                                issue.path("messageKey").asText()))
                        .toList(),
                summary.validatedAt());
    }

    private ImpactSummaryView impact(ImpactRunSummary summary) {
        if (summary == null) {
            return null;
        }
        return new ImpactSummaryView(
                summary.impactRunPid(),
                summary.revision(),
                summary.status(),
                summary.dependencyChecksum(),
                StreamSupport.stream(summary.dependencies().spliterator(), false)
                        .map(dependency -> new ImpactDependencyView(
                                dependency.path("resourceType").asText(),
                                dependency.path("resourceCode").asText(),
                                dependency.path("resourcePid").asText(),
                                dependency.path("version").asInt(),
                                dependency.path("rowVersion").asInt()))
                        .toList(),
                summary.failureCode(),
                summary.analyzedAt());
    }

    private String nullableText(JsonNode value) {
        return value == null || value.isNull() ? null : value.asText();
    }
}
