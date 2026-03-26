package com.auraboot.framework.meta.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * ARCH-001: Record-context capability response.
 * <p>
 * Returns the available actions and tabs for a specific record, filtered by
 * the current user's permissions, the record's state, platform, and context.
 * Mobile Action Bar renders the top 2 (by priority) as primary buttons.
 * </p>
 *
 * @author AuraBoot Team
 * @since 3.1.0
 * @see <a href="docs/system-reference/subsystems/50-Capability动作能力接口.md">Capability API spec</a>
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RecordCapabilities {

    /** The model code this response belongs to. */
    private String modelCode;

    /** The record ID this response belongs to. */
    private String recordId;

    /** Current record state (normalized to lowercase), e.g. "draft", "active", "proposal". */
    private String recordState;

    /** Actions available for this record given current user + record state. */
    private List<ActionCapability> capabilities;

    /** Tabs available on the detail page for this model, with visibility and badge info. */
    private List<TabCapability> tabs;

    /** ETag for conditional requests. Format: W/"cap-{recordId}-{timestamp}". */
    private String etag;

    /**
     * Single action capability descriptor.
     * Represents a command that the current user can execute on the current record.
     */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ActionCapability {

        /** Command code, e.g. "crm_opp_advance". */
        private String code;

        /** Human-readable label (i18n resolved by backend), e.g. "Advance Stage". */
        private String label;

        /**
         * Action type: state_transition, create_related, edit_field, inline_edit,
         * navigate, external, ai_trigger, workflow_trigger, bulk, destructive.
         */
        private String type;

        /** Material Icon name, e.g. "arrow_forward", "edit", "delete". */
        private String icon;

        /** Visual style: primary, secondary, tertiary, danger. */
        private String style;

        /**
         * How the action should be executed on the client:
         * immediate, confirm_dialog, form_dialog, form_page, navigate, external_intent, bottom_sheet_select.
         */
        private String executionMode;

        /** Sort priority (lower = higher priority, i.e. more prominent). */
        private int priority;

        /** Whether this action should appear in the Sticky Action Bar (true) or "More" menu (false). */
        private boolean showInActionBar;

        /** Whether this action is destructive. iOS uses this to force into "More" sheet with danger styling. */
        private boolean destructive;

        /** Confirm message for confirm_dialog execution mode. */
        private String confirmMessage;

        /** Target state for state_transition actions. */
        private String targetState;

        /** Form schema for form_dialog/form_page execution modes. */
        private FormSchema formSchema;

        /** Navigation target URL for navigate execution mode. */
        private String navigateTo;

        /** Whether this action requires network connectivity. Defaults to true. */
        @Builder.Default
        private boolean requiresNetwork = true;

        /** Offline behavior: "queue" (enqueue for sync) or "disabled" (grey out). */
        private String offlineFallback;

        /** Full command code for action execution via Command API. */
        private String commandCode;
    }

    /**
     * Tab capability for detail page.
     */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TabCapability {

        /** Tab code, e.g. "overview", "activity", "related", "discussion". */
        private String code;

        /** Display label (i18n resolved). */
        private String label;

        /** Whether this tab is visible to the current user. */
        private boolean visible;

        /** Badge count (e.g. number of pending items). 0 = no badge. */
        @Builder.Default
        private int badge = 0;
    }

    /**
     * Form schema configuration for form_dialog / form_page execution modes.
     */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FormSchema {

        /** Target model code for creating related records. */
        private String modelCode;

        /** Default field values to pre-fill. */
        private Map<String, Object> defaultValues;

        /** If non-null, only show these fields in the form. */
        private List<String> fields;
    }
}
