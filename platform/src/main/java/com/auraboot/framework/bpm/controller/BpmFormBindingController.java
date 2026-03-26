package com.auraboot.framework.bpm.controller;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.PageSchemaDTO;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.PageSchemaService;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Design-time API for BPM form bindings.
 * Provides endpoints for the BPMN designer to list form-type pages
 * and retrieve field metadata for variable mapping and permission matrix editors.
 */
@Slf4j
@RestController
@RequestMapping("/api/bpm/form-bindings")
@RequiredArgsConstructor
@RequirePermission(MetaPermission.BPM_FORM_MANAGE)
public class BpmFormBindingController {

    private static final String PAGE_TYPE_FORM = "form";

    private final PageSchemaService pageSchemaService;
    private final MetaModelService metaModelService;

    /**
     * List published form-type pages for the page picker in BPMN designer.
     * Returns a lightweight list (no dslSchema) suitable for dropdown/selector UI.
     */
    @GetMapping("/pages")
    public ApiResponse<List<Map<String, Object>>> listFormPages() {
        List<PageSchemaDTO> published = pageSchemaService.findPublishedSchemas();

        List<Map<String, Object>> formPages = published.stream()
                .filter(p -> PAGE_TYPE_FORM.equalsIgnoreCase(p.getPageType()))
                .map(p -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("pageKey", p.getPageKey());
                    item.put("name", p.getName());
                    item.put("modelCode", p.getModelCode());
                    item.put("pageType", p.getPageType());
                    return item;
                })
                .collect(Collectors.toList());

        log.debug("Listed {} published form pages for BPM binding", formPages.size());
        return ApiResponse.success(formPages);
    }

    /**
     * Get field list for a specific page, used for variable mapping
     * and permission matrix editors in BPMN designer.
     * Resolves fields via the page's associated model.
     */
    @GetMapping("/pages/{pageKey}/fields")
    public ApiResponse<List<Map<String, Object>>> getPageFields(@PathVariable String pageKey) {
        PageSchemaDTO page = pageSchemaService.findByPageKey(pageKey);
        if (page == null) {
            return ApiResponse.error(ResponseCode.NOT_FOUND, "Page not found: " + pageKey, null);
        }

        String modelCode = page.getModelCode();
        if (modelCode == null || modelCode.isBlank()) {
            return ApiResponse.success(List.of());
        }

        List<FieldDefinition> fields = metaModelService.getModelFields(modelCode);
        if (fields == null || fields.isEmpty()) {
            return ApiResponse.success(List.of());
        }

        List<Map<String, Object>> fieldList = fields.stream()
                .map(f -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("code", f.getCode());
                    item.put("name", f.getName());
                    item.put("displayName", f.getDisplayName());
                    item.put("dataType", f.getDataType());
                    item.put("required", f.isRequired());
                    return item;
                })
                .collect(Collectors.toList());

        log.debug("Retrieved {} fields for page {} (model={})", fieldList.size(), pageKey, modelCode);
        return ApiResponse.success(fieldList);
    }
}
