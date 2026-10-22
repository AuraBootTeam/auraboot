package com.auraboot.framework.aurabot.skill.builtin;

import com.auraboot.framework.aurabot.skill.AuraBotSkill;
import com.auraboot.framework.aurabot.skill.SkillRunRepository;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.service.MetaFieldService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("AuraBot built-in skill permission contract")
class AuraBotSkillPermissionContractTest {

    private static final Pattern CANONICAL_PERMISSION =
            Pattern.compile("^[a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*$");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("built-in skill permissions are canonical and registered in default bootstrap")
    void builtinSkillPermissionsAreCanonicalAndRegistered() throws Exception {
        Set<String> registered = loadDefaultBootstrapPermissions();

        for (AuraBotSkill skill : builtInSkills()) {
            for (String permission : skill.requiredPermissions()) {
                assertThat(permission)
                        .as("%s permission code must use canonical module.resource.action format", skill.name())
                        .matches(CANONICAL_PERMISSION);
                assertThat(registered)
                        .as("%s permission %s must be registered in default-bootstrap.json",
                                skill.name(), permission)
                        .contains(permission);
            }
        }
    }

    private List<AuraBotSkill> builtInSkills() {
        MetaModelService metaModelService = mock(MetaModelService.class);
        MetaFieldService metaFieldService = mock(MetaFieldService.class);
        SkillRunRepository skillRunRepository = mock(SkillRunRepository.class);

        return List.of(
                new EchoSkill(objectMapper, "low"),
                new ModelQuerySkill(metaModelService, objectMapper),
                new ModelCreateSkill(
                        metaModelService,
                        metaFieldService,
                        mock(MetaModelFieldBindingMapper.class),
                        mock(DynamicDataMapper.class),
                        objectMapper,
                        skillRunRepository),
                new FieldAddSkill(
                        metaFieldService,
                        metaModelService,
                        objectMapper,
                        skillRunRepository)
        );
    }

    private Set<String> loadDefaultBootstrapPermissions() throws Exception {
        JsonNode root = objectMapper.readTree(Path.of(
                "src/main/resources/tenant-templates/default-bootstrap.json").toFile());
        Set<String> codes = new LinkedHashSet<>();
        for (JsonNode permission : root.path("permissions")) {
            if (permission.hasNonNull("code")) {
                codes.add(permission.get("code").asText());
            }
        }
        return codes;
    }
}
