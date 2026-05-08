package com.auraboot.framework.meta.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.dto.AddFieldRequest;
import com.auraboot.framework.meta.dto.AddFieldResult;
import com.auraboot.framework.meta.dto.RemoveFieldRequest;
import com.auraboot.framework.meta.service.MetaFieldService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints exposing {@link MetaFieldService#addToModel} /
 * {@link MetaFieldService#removeFromModel} (Spec §4).
 *
 * <p>Wire shape only — the controller delegates straight to the service and
 * holds no business logic. Permission gating is handled centrally by the
 * platform's {@code PermissionInterceptor}; we deliberately do not stack
 * controller-level {@code @PreAuthorize} here (Plan §Task 5).
 *
 * <p><b>Path note (deviation from Spec §4 / Plan §Task 5):</b> the spec calls
 * for {@code /api/meta/models/{modelCode}/fields}, but
 * {@code ModelFieldBindingController} already owns that exact pattern (keyed
 * by {@code modelPid}/{@code fieldPid}, for the existing field-binding flow).
 * Spring fails startup with an "Ambiguous handler methods" error when both
 * controllers expose the same template. The orchestrator therefore lives
 * under the dedicated namespace {@code /api/meta/orchestrator/models/...} —
 * this is the canonical "field add/remove" REST surface for the C-4 skill
 * and any future CLI consumer.
 *
 * <p>The {@code modelCode} path variable always overrides any value carried
 * in the request body to prevent body/path drift.
 *
 * @author AuraBoot Framework
 * @since C-4
 */
@Slf4j
@Tag(name = "Meta Field Orchestrator",
        description = "Add/remove a single field on a published model — Spec §4")
@RestController
@RequestMapping("/api/meta/orchestrator/models/{modelCode}/fields")
@RequiredArgsConstructor
public class MetaFieldOrchestratorController {

    private final MetaFieldService metaFieldService;

    /**
     * Add a field to an existing published model.
     *
     * <p>Path-supplied {@code modelCode} overrides any value carried by the
     * request body to prevent client-side drift.
     */
    @PostMapping
    @Operation(summary = "Add a single field to a published model (Spec §4 / §3.1)")
    public ApiResponse<AddFieldResult> addField(@PathVariable("modelCode") String modelCode,
                                                @RequestBody AddFieldRequest request) {
        // Path is the source of truth for modelCode; do not trust body.
        request.setModelCode(modelCode);
        AddFieldResult result = metaFieldService.addToModel(request);
        return ApiResponse.ok(result);
    }

    /**
     * Remove a previously-added field from a published model.
     *
     * @param modelCode           target model (path)
     * @param storageCode         storage code returned by the original add
     * @param refuseIfDataExists  when {@code true} the call fails with 422
     *                            {@code COLUMN_HAS_DATA} if any non-null
     *                            row remains; defaults to {@code true}
     */
    @DeleteMapping("/{storageCode}")
    @Operation(summary = "Remove a field from a published model (Spec §4 / §3.6)")
    public ApiResponse<Void> removeField(
            @PathVariable("modelCode") String modelCode,
            @PathVariable("storageCode") String storageCode,
            @RequestParam(value = "refuseIfDataExists", defaultValue = "true")
                    boolean refuseIfDataExists) {
        RemoveFieldRequest request = RemoveFieldRequest.builder()
                .modelCode(modelCode)
                .storageCode(storageCode)
                .refuseIfDataExists(refuseIfDataExists)
                .build();
        metaFieldService.removeFromModel(request);
        return ApiResponse.ok();
    }
}
