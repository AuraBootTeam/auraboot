package com.auraboot.framework.bpm.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One task action declared on a user task node's {@code data.taskActions[*]} entry
 * in the designerJson authored by plugin processes.json. Surfaced to the frontend
 * via {@link TaskFormResponse#taskActions} so the UI can forward the action's
 * {@code resultVariable}/{@code resultValue} as process variables when completing
 * the task, allowing downstream exclusiveGateway conditions (e.g. MVEL
 * {@code ${taskResult == 'approved'}}) to resolve.
 *
 * <p>Only fields the UI needs are modelled here; unknown designer-side fields
 * are ignored on deserialization (the source is a Map&lt;String,Object&gt;).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TaskActionDef {

    /**
     * Logical key of the action ({@code approve} / {@code reject} / custom).
     * The UI matches this against the clicked button.
     */
    private String key;

    /**
     * Action category. Currently only {@code complete} is consumed by the UI
     * path; other values (e.g. {@code claim}) bypass the variable-forwarding
     * logic. Matches designerJson {@code type}.
     */
    private String type;

    /**
     * Name of the process variable to set when the action fires. Together with
     * {@link #resultValue} this becomes a single-entry {@code variables} map on
     * the approve/reject request.
     */
    private String resultVariable;

    /**
     * Literal value assigned to {@link #resultVariable} when the action fires.
     */
    private String resultValue;

    /**
     * When true the UI must require a non-empty comment before submission.
     * (Frontend already enforces this for reject by convention; this flag
     * generalises the rule to arbitrary actions.)
     */
    private Boolean requireComment;
}
