package com.auraboot.framework.aurabot.skill;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Wire-level response envelope returned by every {@link AuraBotSkill}.
 *
 * <p>Construct via the {@link #success()}, {@link #needsConfirm()}, and
 * {@link #error(String, String)} static helpers — direct mutation is allowed
 * but not idiomatic.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class SkillResult {

    public enum Status {
        SUCCESS,
        NEEDS_CONFIRM,
        ERROR
    }

    private Status status;
    private String skillName;
    private String traceId;
    private Object payload;
    private Object preview;
    private String previewToken;
    private RiskLevel riskLevel;
    private String requireTextConfirm;
    private String undoToken;
    private String batchId;
    @Builder.Default
    private List<Suggestion> suggestions = new ArrayList<>();
    private String streamUrl;
    @Builder.Default
    private List<ErrorEntry> errors = new ArrayList<>();

    public static SkillResult success() {
        return SkillResult.builder().status(Status.SUCCESS).build();
    }

    public static SkillResult needsConfirm() {
        return SkillResult.builder().status(Status.NEEDS_CONFIRM).build();
    }

    public static SkillResult error(String code, String message) {
        SkillResult r = SkillResult.builder().status(Status.ERROR).build();
        r.getErrors().add(new ErrorEntry(code, message, null));
        return r;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Suggestion {
        private String label;
        private String skillName;
        private Object paramsHint;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ErrorEntry {
        private String code;
        private String message;
        private String fieldPath;
    }
}
