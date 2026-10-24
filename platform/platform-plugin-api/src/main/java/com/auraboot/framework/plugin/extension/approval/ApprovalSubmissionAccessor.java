package com.auraboot.framework.plugin.extension.approval;

import java.util.Optional;

/** Host-owned persisted submission lookup; a caller-supplied business reference is never authority. */
public interface ApprovalSubmissionAccessor {
    boolean supports(String businessType);
    Optional<ApprovalContract.SubmittedApproval> findSubmitted(long tenantId,long actorUserId,String submissionId);
}
