package com.auraboot.framework.email.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.email.model.EmailSequence;
import com.auraboot.framework.email.model.EmailSequenceEnrollment;
import com.auraboot.framework.email.model.EmailSequenceStep;
import com.auraboot.framework.email.service.EmailSequenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for email sequence (drip campaign) management.
 *
 * <p>Provides CRUD for sequences, step management, and enrollment operations.
 * All endpoints require an authenticated tenant context.
 *
 * @since 6.5.0
 */
@Slf4j
@RestController
@RequestMapping("/api/email/sequences")
@RequiredArgsConstructor
@Tag(name = "Email Sequences", description = "Drip campaign sequences, steps, and enrollment management")
public class EmailSequenceController {

    private final EmailSequenceService emailSequenceService;

    // ──────────────────────────────────────────────────────────────────────────
    // Sequence CRUD
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Lists all sequences for the current tenant.
     */
    @GetMapping
    @Operation(summary = "List all email sequences for current tenant")
    public ApiResponse<List<EmailSequence>> listSequences() {
        Long tenantId = MetaContext.getCurrentTenantId();
        return ApiResponse.ok(emailSequenceService.listSequences(tenantId));
    }

    /**
     * Creates a new sequence in draft status.
     *
     * <p>Request body: {@code {name, description?}}.
     */
    @PostMapping
    @Operation(summary = "Create a new email sequence (starts as draft)")
    public ApiResponse<EmailSequence> createSequence(@RequestBody Map<String, String> body) {
        Long   tenantId    = MetaContext.getCurrentTenantId();
        Long   userId      = MetaContext.getCurrentUserId();
        String name        = body.get("name");
        String description = body.get("description");

        if (name == null || name.isBlank()) {
            return ApiResponse.error("Sequence name is required");
        }

        EmailSequence sequence = emailSequenceService.createSequence(tenantId, userId, name, description);
        return ApiResponse.ok(sequence);
    }

    /**
     * Returns a sequence with its steps.
     *
     * @param id sequence database ID
     */
    @GetMapping("/{id}")
    @Operation(summary = "Get a sequence with its steps")
    public ApiResponse<Map<String, Object>> getSequence(@PathVariable Long id) {
        EmailSequence sequence = emailSequenceService.getSequence(id);
        if (sequence == null) {
            return ApiResponse.error("Sequence not found");
        }
        List<EmailSequenceStep> steps = emailSequenceService.getSteps(id);
        return ApiResponse.ok(Map.of("sequence", sequence, "steps", steps));
    }

    /**
     * Updates sequence name and description.
     *
     * <p>Request body: {@code {name?, description?}}.
     *
     * @param id sequence database ID
     */
    @PutMapping("/{id}")
    @Operation(summary = "Update sequence name / description")
    public ApiResponse<Void> updateSequence(@PathVariable Long id,
                                            @RequestBody Map<String, String> body) {
        emailSequenceService.updateSequence(id, body.get("name"), body.get("description"));
        return ApiResponse.ok();
    }

    /**
     * Updates the lifecycle status of a sequence.
     *
     * <p>Request body: {@code {status}}.  Valid values: draft, active, paused, archived.
     *
     * @param id sequence database ID
     */
    @PutMapping("/{id}/status")
    @Operation(summary = "Update sequence lifecycle status (draft/active/paused/archived)")
    public ApiResponse<Void> updateStatus(@PathVariable Long id,
                                          @RequestBody Map<String, String> body) {
        String status = body.get("status");
        if (status == null || status.isBlank()) {
            return ApiResponse.error("status is required");
        }
        emailSequenceService.updateStatus(id, status);
        return ApiResponse.ok();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Step management
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Adds a new step to a sequence.
     *
     * <p>Request body: {@code {stepOrder, delayDays, subjectTemplate, bodyTemplate}}.
     *
     * @param id sequence database ID
     */
    @PostMapping("/{id}/steps")
    @Operation(summary = "Add a step to a sequence")
    public ApiResponse<EmailSequenceStep> addStep(@PathVariable Long id,
                                                  @RequestBody Map<String, Object> body) {
        int    stepOrder       = toInt(body.get("stepOrder"), 1);
        int    delayDays       = toInt(body.get("delayDays"), 0);
        String subjectTemplate = (String) body.get("subjectTemplate");
        String bodyTemplate    = (String) body.get("bodyTemplate");

        EmailSequenceStep step = emailSequenceService.addStep(
                id, stepOrder, delayDays, subjectTemplate, bodyTemplate);
        return ApiResponse.ok(step);
    }

    /**
     * Updates an existing step.
     *
     * <p>Request body: {@code {stepOrder?, delayDays?, subjectTemplate?, bodyTemplate?}}.
     *
     * @param id     sequence database ID (unused for lookup but kept for RESTful path)
     * @param stepId step database ID
     */
    @PutMapping("/{id}/steps/{stepId}")
    @Operation(summary = "Update a sequence step")
    public ApiResponse<Void> updateStep(@PathVariable Long id,
                                        @PathVariable Long stepId,
                                        @RequestBody Map<String, Object> body) {
        Integer stepOrder       = body.containsKey("stepOrder")       ? toInt(body.get("stepOrder"), 0)   : null;
        Integer delayDays       = body.containsKey("delayDays")       ? toInt(body.get("delayDays"), 0)   : null;
        String  subjectTemplate = (String) body.get("subjectTemplate");
        String  bodyTemplate    = (String) body.get("bodyTemplate");

        emailSequenceService.updateStep(stepId, stepOrder, delayDays, subjectTemplate, bodyTemplate);
        return ApiResponse.ok();
    }

    /**
     * Deletes a step from a sequence.
     *
     * @param id     sequence database ID
     * @param stepId step database ID
     */
    @DeleteMapping("/{id}/steps/{stepId}")
    @Operation(summary = "Delete a step from a sequence")
    public ApiResponse<Void> deleteStep(@PathVariable Long id, @PathVariable Long stepId) {
        emailSequenceService.deleteStep(stepId);
        return ApiResponse.ok();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Enrollment
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Enrolls one or more contacts into a sequence.
     *
     * <p>Request body:
     * <pre>{@code
     * {
     *   "accountId": 1,
     *   "contacts": [
     *     { "email": "alice@example.com", "modelCode": "crm_contact", "recordId": "42" },
     *     ...
     *   ]
     * }
     * }</pre>
     *
     * @param id sequence database ID
     */
    @PostMapping("/{id}/enroll")
    @Operation(summary = "Enroll contacts into a sequence")
    public ApiResponse<List<EmailSequenceEnrollment>> enroll(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {

        Long tenantId = MetaContext.getCurrentTenantId();
        Long accountId = toLong(body.get("accountId"));

        @SuppressWarnings("unchecked")
        List<Map<String, String>> contacts =
                (List<Map<String, String>>) body.getOrDefault("contacts", List.of());

        List<EmailSequenceEnrollment> enrollments = contacts.stream()
                .map(c -> emailSequenceService.enroll(
                        tenantId,
                        id,
                        accountId,
                        c.get("email"),
                        c.get("modelCode"),
                        c.get("recordId")))
                .toList();

        return ApiResponse.ok(enrollments);
    }

    /**
     * Lists all enrollments for a sequence.
     *
     * @param id sequence database ID
     */
    @GetMapping("/{id}/enrollments")
    @Operation(summary = "List enrollments for a sequence")
    public ApiResponse<List<EmailSequenceEnrollment>> listEnrollments(@PathVariable Long id) {
        return ApiResponse.ok(emailSequenceService.listEnrollments(id));
    }

    /**
     * Pauses an active enrollment.
     *
     * @param id  sequence database ID
     * @param eid enrollment database ID
     */
    @PutMapping("/{id}/enrollments/{eid}/pause")
    @Operation(summary = "Pause an active enrollment")
    public ApiResponse<Void> pauseEnrollment(@PathVariable Long id, @PathVariable Long eid) {
        emailSequenceService.updateEnrollmentStatus(eid, "paused");
        return ApiResponse.ok();
    }

    /**
     * Resumes a paused enrollment.
     *
     * @param id  sequence database ID
     * @param eid enrollment database ID
     */
    @PutMapping("/{id}/enrollments/{eid}/resume")
    @Operation(summary = "Resume a paused enrollment")
    public ApiResponse<Void> resumeEnrollment(@PathVariable Long id, @PathVariable Long eid) {
        emailSequenceService.updateEnrollmentStatus(eid, "active");
        return ApiResponse.ok();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────────────────

    private int toInt(Object value, int defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number n) return n.intValue();
        try { return Integer.parseInt(value.toString()); } catch (NumberFormatException e) { return defaultValue; }
    }

    private Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        try { return Long.parseLong(value.toString()); } catch (NumberFormatException e) { return null; }
    }
}
