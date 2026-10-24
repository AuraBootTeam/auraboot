package com.auraboot.framework.plugin.extension.approval;

import org.pf4j.ExtensionPoint;
import java.util.Optional;

/** A business fact owner resolves only its own persisted, authorized approval submissions. */
public interface ApprovalSubmissionSource extends ExtensionPoint {
    String businessType();
    Optional<ApprovalContract.SubmittedApproval> findSubmitted(long tenantId,long actorUserId,String submissionId);
    default boolean canStart(long tenantId,long actorUserId,String submissionId) { return false; }
    default boolean canWithdraw(long tenantId,long actorUserId,String submissionId) { return false; }
    default boolean canExecute(long tenantId,long actorUserId,String submissionId) { return false; }
    default Optional<ApprovalContract.BusinessPreview> preview(long tenantId,long actorUserId,String submissionId) { return Optional.empty(); }
}
