package com.auraboot.framework.meta.service;

import java.util.List;
import java.util.Map;

/**
 * Model Export Service
 *
 * Exports model definitions in plugin-compatible JSON format.
 *
 * @author AuraBoot Team
 * @since 3.1.0
 */
public interface ModelExportService {

    /**
     * Export model definitions by model codes.
     *
     * @param modelCodes list of model codes to export
     * @return export result containing models, fields, bindings, commands arrays
     */
    Map<String, Object> exportByModelCodes(List<String> modelCodes);
}
