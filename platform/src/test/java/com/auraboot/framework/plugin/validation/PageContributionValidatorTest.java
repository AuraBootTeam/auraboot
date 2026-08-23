package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.plugin.dto.imports.PageContributionDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PageContributionValidatorTest {

    private final PageContributionValidator validator = new PageContributionValidator();

    @Test
    void acceptsACompleteSupportedDefinition() {
        PluginManifestExtended manifest = new PluginManifestExtended();
        manifest.setPageContributions(List.of(definition("select-product", "action")));

        assertThat(validator.validate(context(manifest))).isEmpty();
    }

    @Test
    void reportsIncompleteDuplicateAndUnsupportedDefinitions() {
        PluginManifestExtended manifest = new PluginManifestExtended();
        PageContributionDefinitionDTO incomplete = new PageContributionDefinitionDTO();
        PageContributionDefinitionDTO duplicate = definition("same", "block");
        PageContributionDefinitionDTO unsupported = definition("same", "widget");
        manifest.setPageContributions(List.of(incomplete, duplicate, unsupported));

        assertThat(validator.validate(context(manifest)))
                .extracting(PluginValidationMessage::getCode)
                .containsExactly(
                        "S-PAGE-CONTRIBUTION",
                        "S-PAGE-CONTRIBUTION-DUPLICATE",
                        "S-PAGE-CONTRIBUTION-KIND");
    }

    private PluginValidationContext context(PluginManifestExtended manifest) {
        return PluginValidationContext.builder().manifest(manifest).build();
    }

    private PageContributionDefinitionDTO definition(String id, String kind) {
        return PageContributionDefinitionDTO.builder()
                .id(id)
                .targetPageKey("crm-opportunity-detail")
                .slotId("product-actions")
                .kind(kind)
                .payload("block".equals(kind)
                        ? Map.of("id", id + "-block", "blockType", "sub-table")
                        : Map.of("code", id))
                .build();
    }
}
