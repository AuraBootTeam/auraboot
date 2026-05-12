package com.auraboot.framework.meta.controller;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.common.util.LogSanitizer;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelCapabilities;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.service.MetaModelService;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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

    private static String logSafe(Object value) {
        return LogSanitizer.safe(value);
    }

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

        log.debug("Model capabilities request: code={}", logSafe(code));

        ModelDefinition def = metaModelService.getDefinitionByCode(code);
        if (def == null) {
            throw new RootUnCheckedException(ResponseCode.NOT_FOUND, "Model not found: " + code);
        }

        ModelCapabilities caps = def.getCapabilities();
        boolean isEmpty = caps == null
                || (!caps.isList() && !caps.isDetail() && !caps.isCreate() && !caps.isUpdate()
                    && !caps.isDelete() && !caps.isBulkDelete() && !caps.isExport()
                    && !caps.isSort() && !caps.isFilter() && !caps.isPaginate());
        if (isEmpty) {
            String st = def.getSourceType();
            caps = (st == null || "physical".equals(st))
                    ? ModelCapabilities.fullPhysical()
                    : ModelCapabilities.empty();
        }

        // If sortableFields / filterableFields are empty (typical for the
        // fullPhysical fallback path or freshly created models that haven't
        // been re-saved through MetaModelService), infer from the model's
        // fields so the designer's ColumnsTab / FiltersTab have something to
        // pick from. Per-field `sortable` / `filterable` flags are honored
        // when explicitly set; otherwise fields with sortable/filterable
        // primitive data types default to true.
        boolean needSort = caps.isSort()
                && (caps.getSortableFields() == null || caps.getSortableFields().isEmpty());
        boolean needFilter = caps.isFilter()
                && (caps.getFilterableFields() == null || caps.getFilterableFields().isEmpty());
        if ((needSort || needFilter) && def.getFields() != null) {
            List<String> inferredSortable = new ArrayList<>();
            List<String> inferredFilterable = new ArrayList<>();
            for (FieldDefinition f : def.getFields()) {
                if (f.getCode() == null) continue;
                String type = f.getDataType() == null ? "" : f.getDataType().toLowerCase(Locale.ROOT);
                boolean sortableByDefault = SORT_FILTER_DEFAULT_TYPES.contains(type);
                boolean filterableByDefault = SORT_FILTER_DEFAULT_TYPES.contains(type);
                // Honor explicit per-field flag; otherwise fall back to data-type default.
                boolean sortable = f.getSortable() != null ? Boolean.TRUE.equals(f.getSortable()) : sortableByDefault;
                boolean filterable = f.getFilterable() != null ? Boolean.TRUE.equals(f.getFilterable()) : filterableByDefault;
                if (sortable) inferredSortable.add(f.getCode());
                if (filterable) inferredFilterable.add(f.getCode());
            }
            ModelCapabilities.ModelCapabilitiesBuilder builder = caps.toBuilder();
            if (needSort) builder.sortableFields(inferredSortable);
            if (needFilter) builder.filterableFields(inferredFilterable);
            caps = builder.build();
        }

        return ApiResponse.ok(caps);
    }

    /**
     * Primitive scalar data types that are sortable / filterable by default
     * when a field doesn't carry an explicit per-field flag. Long-text /
     * blob / json types are intentionally excluded.
     */
    private static final Set<String> SORT_FILTER_DEFAULT_TYPES = Set.of(
            "string", "integer", "int", "long", "bigint",
            "decimal", "numeric", "float", "double",
            "date", "datetime", "timestamp", "time",
            "enum", "dict", "boolean", "bool"
    );
}
