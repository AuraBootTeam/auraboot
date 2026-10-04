package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.plugin.dto.imports.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PageContributionValidatorBoundaryTest {
    @Test
    void invalidKindsDuplicateIdsAndNullDefinitionsHaveExactPaths() {
        var manifest = new PluginManifestExtended();
        manifest.setPageContributions(Arrays.asList(null, contribution("duplicate", "block"), contribution("duplicate", "unsupported")));
        var messages = new PageContributionValidator().validate(PluginValidationContext.builder().manifest(manifest).build());
        assertEquals(List.of("S-PAGE-CONTRIBUTION", "S-PAGE-CONTRIBUTION-DUPLICATE", "S-PAGE-CONTRIBUTION-KIND"),
            messages.stream().map(PluginValidationMessage::getCode).toList());
        assertEquals(List.of("pageContributions[0]", "pageContributions[2].id", "pageContributions[2].kind"),
            messages.stream().map(PluginValidationMessage::getPath).toList());
        assertTrue(messages.stream().allMatch(PluginValidationMessage::isError));
    }
    private PageContributionDefinitionDTO contribution(String id, String kind) {
        return PageContributionDefinitionDTO.builder().id(id).kind(kind).targetPageKey("test_page").slotId("actions")
            .payload(Map.of("label", "Test")).build();
    }
}
