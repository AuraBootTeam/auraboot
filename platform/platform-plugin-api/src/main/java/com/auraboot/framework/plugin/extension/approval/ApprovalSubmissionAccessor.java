package com.auraboot.framework.plugin.extension.approval;

import java.util.Optional;

/** Host-owned persisted submission lookup; a caller-supplied business reference is never authority. */
public interface ApprovalSubmissionAccessor {
    boolean supports(String businessType);
    Optional<ApprovalContract.SubmittedApproval> findSubmitted(long tenantId,long actorUserId,String submissionId);
    default boolean canStart(long tenantId,long actorUserId,String submissionId) { return false; }
    default boolean canWithdraw(long tenantId,long actorUserId,String submissionId) { return false; }
    default boolean canExecute(long tenantId,long actorUserId,String submissionId) { return false; }
    default Optional<ApprovalContract.BusinessPreview> preview(long tenantId,long actorUserId,String submissionId) { return Optional.empty(); }
}
