package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.SessionView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.WriterLeaseView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceRepository.WorkspaceRow;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Maps the persisted aggregate to the client-safe session view. */
@Component
public class AuthoringWorkspaceViewMapper {

    public SessionView toView(WorkspaceRow row, long currentUserId) {
        String leaseStatus;
        if (!row.leasedUntil().isAfter(Instant.now())) {
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
                row.sessionState(),
                row.changeSetRevision(),
                row.riskLevel(),
                row.route(),
                row.publishPolicy(),
                row.validationState(),
                row.approvalState(),
                row.publishState(),
                row.manifestChecksum(),
                row.snapshot(),
                row.interactionContext(),
                new WriterLeaseView(leaseStatus, row.leaseRevision(), row.leasedUntil()),
                row.expiresAt());
    }
}
