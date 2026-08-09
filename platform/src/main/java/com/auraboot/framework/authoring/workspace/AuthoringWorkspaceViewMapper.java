package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.SessionView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceRepository.WorkspaceRow;
import org.springframework.stereotype.Component;

/** Maps the persisted aggregate to the client-safe session view. */
@Component
public class AuthoringWorkspaceViewMapper {

    public SessionView toView(WorkspaceRow row) {
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
                row.expiresAt());
    }
}
