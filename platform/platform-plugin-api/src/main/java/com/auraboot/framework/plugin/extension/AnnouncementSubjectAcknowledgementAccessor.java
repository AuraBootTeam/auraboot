package com.auraboot.framework.plugin.extension;

import java.util.List;
import java.util.Map;

/** Host-issued current applicant proof in the unchanged operator's original native transaction. */
@FunctionalInterface
public interface AnnouncementSubjectAcknowledgementAccessor {
    String SETTINGS_KEY = "__announcementSubjectAcknowledgementAccessor";
    /** No caller-selected subject, version or source. Never acknowledges or expands an audience. */
    SubjectChecks checkSubjectForCommand();
    record SubjectChecks(CommandSubjectAcknowledgementSource.SubjectSource source,
                         List<AnnouncementAcknowledgementAccessor.Check> checks,
                         Map<String, Integer> purposeIndexes) {
        public SubjectChecks { checks = List.copyOf(checks); purposeIndexes = Map.copyOf(purposeIndexes); }
    }
}
