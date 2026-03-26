package com.auraboot.framework.meta.controller.config;

import com.auraboot.framework.meta.dto.FilterPresetCreateRequest;
import com.auraboot.framework.meta.entity.FilterPreset;
import com.auraboot.framework.meta.service.FilterPresetService;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for filter preset management.
 *
 * @since 3.4.0
 */
@RestController
@RequestMapping("/api/meta/filter-presets")
@RequiredArgsConstructor
@RequirePermission(MetaPermission.META_FILTER_MANAGE)
public class FilterPresetController {

    private final FilterPresetService filterPresetService;

    /**
     * Create a new filter preset.
     * POST /api/meta/filter-presets
     */
    @PostMapping
    public ResponseEntity<FilterPreset> create(@Valid @RequestBody FilterPresetCreateRequest request) {
        FilterPreset preset = filterPresetService.create(request);
        return ResponseEntity.ok(preset);
    }

    /**
     * List presets for a page (global + current user).
     * GET /api/meta/filter-presets?pageCode=xxx
     */
    @GetMapping
    public ResponseEntity<List<FilterPreset>> list(@RequestParam String pageCode) {
        List<FilterPreset> presets = filterPresetService.listByPageCode(pageCode);
        return ResponseEntity.ok(presets);
    }

    /**
     * Update an existing preset.
     * PUT /api/meta/filter-presets/{id}
     */
    @PutMapping("/{id}")
    public ResponseEntity<FilterPreset> update(@PathVariable Long id,
                                               @Valid @RequestBody FilterPresetCreateRequest request) {
        FilterPreset preset = filterPresetService.update(id, request);
        return ResponseEntity.ok(preset);
    }

    /**
     * Delete a preset.
     * DELETE /api/meta/filter-presets/{id}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        filterPresetService.delete(id);
        return ResponseEntity.ok(Map.of("success", true, "id", id));
    }

    /**
     * Set a preset as default.
     * PUT /api/meta/filter-presets/{id}/default
     */
    @PutMapping("/{id}/default")
    public ResponseEntity<Map<String, Object>> setDefault(@PathVariable Long id) {
        filterPresetService.setDefault(id);
        return ResponseEntity.ok(Map.of("success", true, "id", id));
    }
}
