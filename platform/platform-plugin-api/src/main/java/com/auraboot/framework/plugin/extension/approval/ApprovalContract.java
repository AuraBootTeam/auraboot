package com.auraboot.framework.plugin.extension.approval;

import java.time.Instant;
import java.util.*;

/** Engine-neutral approval.v1 binding identities. No business entity or engine type crosses this port. */
public final class ApprovalContract {
    private ApprovalContract() {}
    public static final String READINESS="approval.readiness.v1";
    public static final String START="approval.start.v1";
    public static final String STATUS="approval.status.v1";
    public static final String WITHDRAW="approval.withdraw.v1";
    public static final String VIEWER="approval.viewer.v1";
    public static final String TERMINAL_EVENT="workflow.approval.terminal.v1";
    public static final Set<String> OPERATIONS=Set.of(READINESS,START,STATUS,WITHDRAW,VIEWER);
    public static final Set<String> TERMINAL_STATES=Set.of("approved","rejected","withdrawn","failed");

    public record BusinessRef(String type,String recordPid,String versionPid) {
        public BusinessRef {text(type);text(recordPid);text(versionPid);}
    }
    public record DefinitionRef(String pid,int version,String hash) {
        public DefinitionRef {text(pid);if(version<1)throw new IllegalArgumentException("Approval definition version must be positive");digest(hash);}
    }
    public record StrictPolicy(boolean excludeApplicant,String emptyAssignee,String resolutionFailure,boolean rejectTerminates) {
        public StrictPolicy {
            if(!excludeApplicant || !"block".equals(emptyAssignee) || !"block".equals(resolutionFailure) || !rejectTerminates)
                throw new IllegalArgumentException("Strict approval policy cannot be weakened");
        }
        public static StrictPolicy required(){return new StrictPolicy(true,"block","block",true);}
    }
    public record SubmittedApproval(String submissionId,String idempotencyKey,BusinessRef businessRef,
            String snapshotRef,String snapshotHash,String snapshotSchema,DefinitionRef definition,
            StrictPolicy strictPolicy,String applicantUserPid,String businessCaption,Instant acceptedAt) {
        public SubmittedApproval {
            text(submissionId);text(idempotencyKey);Objects.requireNonNull(businessRef);text(snapshotRef);digest(snapshotHash);
            text(snapshotSchema);Objects.requireNonNull(definition);Objects.requireNonNull(strictPolicy);
            text(applicantUserPid);text(businessCaption);Objects.requireNonNull(acceptedAt);
        }
    }
    /** An owner-authored read-only DSL projection of the immutable submitted version. */
    public record BusinessPreview(String submissionId,String snapshotHash,Map<String,Object> schema,
            Map<String,Object> values,List<Map<String,Object>> fieldMetadata) {
        public BusinessPreview {
            text(submissionId);digest(snapshotHash);schema=Map.copyOf(schema);
            values=Collections.unmodifiableMap(new LinkedHashMap<>(values));fieldMetadata=List.copyOf(fieldMetadata);
        }
    }
    public record TerminalResult(String submissionId,BusinessRef businessRef,String snapshotHash,
            String instancePid,DefinitionRef definition,String terminalState,String resultId,
            long stateRevision,Instant decidedAt,String decisionReasonCode) {
        public TerminalResult {
            text(submissionId);Objects.requireNonNull(businessRef);digest(snapshotHash);Objects.requireNonNull(definition);
            if(instancePid==null && !("withdrawn".equals(terminalState) && "APPLICANT_WITHDRAWN_BEFORE_START".equals(decisionReasonCode)))
                throw new IllegalArgumentException("Approval instance is required after engine start");
            if(instancePid!=null) text(instancePid);
            if(!TERMINAL_STATES.contains(terminalState))throw new IllegalArgumentException("Explicit approval terminal state required");
            text(resultId);if(stateRevision<1)throw new IllegalArgumentException("Approval result revision must be positive");
            Objects.requireNonNull(decidedAt);text(decisionReasonCode);
        }
    }
    private static void text(String value){if(value==null || value.isBlank())throw new IllegalArgumentException("Approval binding identity is required");}
    private static void digest(String value){if(value==null || !value.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Approval binding SHA-256 is required");}
}
