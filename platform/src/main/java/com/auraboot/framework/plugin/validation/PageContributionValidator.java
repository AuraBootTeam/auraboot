package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.meta.contribution.PageSchemaContributionKind;
import com.auraboot.framework.plugin.dto.imports.PageContributionDefinitionDTO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.auraboot.framework.plugin.validation.PluginValidationMessage.error;

/** Structural validation for config/page-contributions resources. */
@Component
public class PageContributionValidator implements PluginValidator {

    @Override
    public List<PluginValidationMessage> validate(PluginValidationContext context) {
        List<PageContributionDefinitionDTO> definitions = context.getManifest().getPageContributions();
        if (definitions == null) {
            return List.of();
        }
        List<PluginValidationMessage> messages = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < definitions.size(); index++) {
            PageContributionDefinitionDTO definition = definitions.get(index);
            String path = "pageContributions[" + index + "]";
            if (definition == null || !definition.isValid()) {
                messages.add(error("S-PAGE-CONTRIBUTION", category(), path,
                        "id, targetPageKey, slotId, kind and payload are required"));
                continue;
            }
            if (!ids.add(definition.getId())) {
                messages.add(error("S-PAGE-CONTRIBUTION-DUPLICATE", category(), path + ".id",
                        "Duplicate page contribution id: " + definition.getId()));
            }
            try {
                PageSchemaContributionKind.valueOf(definition.getKind().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                messages.add(error("S-PAGE-CONTRIBUTION-KIND", category(), path + ".kind",
                        "Unsupported page contribution kind: " + definition.getKind()));
            }
        }
        return messages;
    }

    @Override
    public String category() {
        return "semantic";
    }
}
