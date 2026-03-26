package com.auraboot.framework.dsl.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.dsl.dto.ComponentSchemaDTO;
import com.auraboot.framework.dsl.service.ComponentSchemaService;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST controller exposing component property schemas.
 *
 * <ul>
 *   <li>GET /api/dsl/components              — list all (optional ?dataType= or ?category= filter)</li>
 *   <li>GET /api/dsl/components/{name}        — single component by type</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/dsl/components")
public class ComponentSchemaController {

    private final ComponentSchemaService componentSchemaService;

    public ComponentSchemaController(ComponentSchemaService componentSchemaService) {
        this.componentSchemaService = componentSchemaService;
    }

    /**
     * List all component schemas, optionally filtered by dataType or category.
     */
    @GetMapping
    public ApiResponse<Map<String, Object>> listComponents(
            @RequestParam(required = false) String dataType,
            @RequestParam(required = false) String category) {

        Collection<ComponentSchemaDTO> result;

        if (dataType != null && !dataType.isBlank()) {
            result = componentSchemaService.getComponentsByDataType(dataType);
        } else if (category != null && !category.isBlank()) {
            result = componentSchemaService.getComponentsByCategory(category);
        } else {
            result = componentSchemaService.getAllComponents();
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", componentSchemaService.getVersion());
        data.put("total", result.size());
        data.put("components", result);

        return ApiResponse.success(data);
    }

    /**
     * Get a single component schema by type name.
     */
    @GetMapping("/{name}")
    public ApiResponse<ComponentSchemaDTO> getComponent(@PathVariable String name) {
        ComponentSchemaDTO dto = componentSchemaService.getComponent(name);
        if (dto == null) {
            return ApiResponse.error("Component not found: " + name);
        }
        return ApiResponse.success(dto);
    }
}
