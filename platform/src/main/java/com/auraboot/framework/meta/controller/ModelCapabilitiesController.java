package com.auraboot.framework.meta.controller;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.meta.dto.ModelCapabilities;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes the runtime-truth capabilities block for a model so the designer UI
 * can drive capability-based feature gating (toolbar buttons, sort/filter
 * controls, form editability) instead of hard-coding assumptions.
 *
 * <p>See design §3.3 principle 4 — capabilities is the runtime truth; per-field
 * sortable/filterable flags on the editor get normalized into the
 * {@code sortableFields} / {@code filterableFields} whitelist at save time,
 * and this endpoint returns the already-normalized snapshot.
 *
 * <p>Part of P1 virtual model backend plan (Task 5/12).
 *
 * @author AuraBoot Team
 * @since 3.1.0
 */
@Slf4j
@RestController
@RequestMapping("/api/meta/models")
@RequiredArgsConstructor
@Tag(name = "Model Capabilities", description = "Runtime-truth capability whitelist for designer UI gating")
public class ModelCapabilitiesController {

    private final MetaModelService metaModelService;

    /**
     * Returns the capabilities block for the model identified by {@code code}.
     *
     * @param code model code (business identifier, unique within tenant)
     * @return normalized {@link ModelCapabilities} snapshot
     * @throws RootUnCheckedException with {@link ResponseCode#NOT_FOUND} (HTTP 404)
     *                                 if the model does not exist
     */
    @GetMapping("/{code}/capabilities")
    @Operation(
            summary = "Get model capabilities",
            description = "Returns the runtime-truth capability flags and sortable/filterable field "
                    + "whitelist for a model. Used by the designer UI to gate feature toggles."
    )
    @RequirePermission(MetaPermission.MODEL_READ)
    public ApiResponse<ModelCapabilities> getCapabilities(
            @Parameter(description = "Model code, e.g. crm_opportunity")
            @PathVariable String code) {

        log.debug("Model capabilities request: code={}", code);

        ModelDefinition def = metaModelService.getDefinitionByCode(code);
        if (def == null) {
            throw new RootUnCheckedException(ResponseCode.NOT_FOUND, "Model not found: " + code);
        }

        ModelCapabilities caps = def.getCapabilities() != null
                ? def.getCapabilities()
                : ModelCapabilities.empty();

        return ApiResponse.ok(caps);
    }
}
