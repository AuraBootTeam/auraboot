package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.SessionView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ValidationIssueView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ValidationSummaryView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.WriterLeaseView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceRepository.ValidationRunSummary;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceRepository.WorkspaceRow;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.stream.StreamSupport;

/** Maps the persisted aggregate to the client-safe session view. */
@Component
public class AuthoringWorkspaceViewMapper {

    public SessionView toView(WorkspaceRow row, long currentUserId) {
        Instant now = Instant.now();
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

    private String nullableText(JsonNode value) {
        return value == null || value.isNull() ? null : value.asText();
    }
}
