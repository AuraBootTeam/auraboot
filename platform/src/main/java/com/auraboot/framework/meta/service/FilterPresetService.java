package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.dto.FilterPresetCreateRequest;
import com.auraboot.framework.meta.entity.FilterPreset;

import java.util.List;

/**
 * Service interface for filter preset management.
 *
 * @since 3.4.0
 */
public interface FilterPresetService {

    /**
     * Create a new filter preset.
     */
    FilterPreset create(FilterPresetCreateRequest request);

    /**
     * List presets for a page (global + current user's personal presets).
     */
    List<FilterPreset> listByPageCode(String pageCode);

    /**
     * Update an existing preset.
     */
    FilterPreset update(Long id, FilterPresetCreateRequest request);

    /**
     * Delete a preset.
     */
    void delete(Long id);

    /**
     * Set a preset as the default for its page.
     */
    void setDefault(Long id);
}
