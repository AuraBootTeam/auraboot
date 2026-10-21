package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.impl.RecordCommandWriterGuard;
import com.auraboot.framework.plugin.exception.PluginException;
import java.util.Collection;

/** Validates composed model/field metadata after bindings and before publication. */
public final class PluginModelOwnershipImportGate {
    private PluginModelOwnershipImportGate() {}

    public static void validate(MetaModelService models, Collection<String> importedModelCodes) {
        for (String code : new java.util.TreeSet<>(importedModelCodes)) {
            var model = models.getModelDefinitionFromDb(code)
                .orElseThrow(() -> new PluginException("Imported model metadata is missing: " + code));
            RecordCommandWriterGuard.validatePolicy(model);
        }
    }
}
