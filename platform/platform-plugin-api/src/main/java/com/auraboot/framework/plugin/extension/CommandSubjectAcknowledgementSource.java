package com.auraboot.framework.plugin.extension;

import java.util.List;
import java.util.Objects;

/** Optional source contract on the actual primary handler, never a client-selectable subject resolver. */
public interface CommandSubjectAcknowledgementSource {
    /** Null means this exact action has no subject gate; unsupported required actions must throw. */
    SubjectSource resolveSubjectAcknowledgementSource(SubjectSourceContext original);

    record SubjectSourceContext(Long tenantId, String operatorUserPid, Long operatorMemberId,
                                String commandCode, String modelCode, String targetRecordPid,
                                TenantProjectionAccessor records, RecordActionScopeAccessor recordScopes) {}

    /** Exact server-owned immutable lineage. Current mutable business state is not a source identity. */
    record SubjectSource(String commandCode, String modelCode, String targetRecordPid,
                         String applicationPid, String revisionPid, String revisionHash,
                         String subjectUserPid, String subjectMemberPid,
                         List<CommandMembershipLockAccessor.MembershipAssertion> participants,
                         List<PurposeRequirement> requirements) {
        public SubjectSource {
            for (String value : List.of(commandCode, modelCode, targetRecordPid, applicationPid,
                    revisionPid, subjectUserPid, subjectMemberPid)) exact(value);
            if (revisionHash == null || !revisionHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Exact revision hash required");
            participants = List.copyOf(Objects.requireNonNull(participants));
            requirements = List.copyOf(Objects.requireNonNull(requirements));
            if (participants.isEmpty() || participants.size() > 32 || requirements.isEmpty() || requirements.size() > 32)
                throw new IllegalArgumentException("Bounded participant and requirement sets required");
        }
    }
    record PurposeRequirement(String purpose, String versionPid, String snapshotHash) {
        public PurposeRequirement {
            exact(purpose);
            new AnnouncementAcknowledgementAccessor.Requirement(versionPid, snapshotHash, "source-member");
        }
    }
    private static void exact(String value) {
        if (value == null || value.isBlank() || value.length() > 160 || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Exact source identity required");
    }
}
