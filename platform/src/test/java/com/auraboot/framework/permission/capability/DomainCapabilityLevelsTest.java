package com.auraboot.framework.permission.capability;

import com.auraboot.framework.plugin.dto.imports.CapabilityDefinitionDTO;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DomainCapabilityLevelsTest {
    private CapabilityDefinitionDTO cap(String code, String tier, String... includes) {
        return CapabilityDefinitionDTO.builder().code(code).tier(tier).includes(List.of(includes)).build();
    }

    @Test void retiresLegacyDomainEntriesButPreservesDataControlsAndUnrelatedDomains() {
        var read = cap("bom.cap.read", "viewer", "bom.project.read");
        var write = cap("bom.cap.write", "editor", "bom.project.read", "bom.convert.execute");
        var manage = cap("bom.cap.manage", "admin", "bom.project.read", "bom.convert.execute", "bom.structure.release");
        var legacy = cap("bom.cap.convert", "editor", "bom.convert.execute");
        var unrelated = cap("fin.cap.post", "editor", "fin.post");
        var uncovered = cap("bom.cap.custom", "editor", "custom.other");
        var data = cap("bom.cap.data", "data", "protected.data.read");
        var declarations = List.of(read, write, manage, legacy, unrelated, uncovered, data);
        assertThat(DomainCapabilityLevels.currentDeclarations(declarations))
                .containsExactly(read, write, manage, unrelated, data);
        assertThat(legacy.getIncludes()).containsExactly("bom.convert.execute");
    }

    @Test void incompleteOrNonNestedDeclarationsNeverRetireLegacyCapabilities() {
        var read = cap("bom.cap.read", "viewer", "bom.project.read");
        var write = cap("bom.cap.write", "editor", "bom.convert.execute");
        var manage = cap("bom.cap.manage", "admin", "bom.project.read", "bom.convert.execute", "bom.structure.release");
        var legacy = cap("bom.cap.convert", "editor", "bom.convert.execute");
        assertThat(DomainCapabilityLevels.currentDeclarations(List.of(read, write, manage, legacy)))
                .containsExactly(read, write, manage, legacy);
        assertThat(DomainCapabilityLevels.currentDeclarations(List.of(read, manage, legacy)))
                .containsExactly(read, manage, legacy);
    }
}
