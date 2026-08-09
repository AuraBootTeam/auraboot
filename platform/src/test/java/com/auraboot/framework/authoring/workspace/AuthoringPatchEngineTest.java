package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.authoring.policy.AuthoringBoundaryPolicyService;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.PatchOperation;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.ResourceScope;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.Route;
import com.auraboot.framework.authoring.policy.CoreAuthoringCapabilityRegistry;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.mapper.CommandDefinitionMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthoringPatchEngineTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private CoreAuthoringCapabilityRegistry registry;
    private CommandDefinitionMapper commandDefinitionMapper;
    private MetaModelService metaModelService;
    private AuthoringPatchEngine engine;

    @BeforeEach
    void setUp() {
        registry = new CoreAuthoringCapabilityRegistry();
        commandDefinitionMapper = mock(CommandDefinitionMapper.class);
        metaModelService = mock(MetaModelService.class);
        engine = new AuthoringPatchEngine(
                registry,
                new AuthoringBoundaryPolicyService(registry),
                new AuthoringContentSanitizer(),
                new AuthoringProtectedSemanticValidator(commandDefinitionMapper, metaModelService),
                new AuthoringSnapshotTargetResolver(),
                new AuthoringJsonObjectPatchApplier(),
                new AuthoringStableBlockTreeEditor(new CoreAuthoringStructurePolicy()));
    }

    @Test
    void allowedPresentationPatchMutatesOnlyTheDraftCopy() throws Exception {
        ObjectNode source = snapshot("""
                {"id":"table-1","blockType":"table","props":{"density":"normal"}}
                """);

        AuthoringPatchEngine.PreparedPatch patch = engine.prepareInline(
                source,
                "table-1",
                "/props/density",
                PatchOperation.REPLACE,
                objectMapper.getNodeFactory().textNode("compact"),
                checksum("table"), ResourceScope.CURRENT_PAGE);

        assertThat(patch.decision().route()).isEqualTo(Route.INLINE);
        assertThat(patch.previousValue().asText()).isEqualTo("normal");
        assertThat(patch.snapshot().at("/blocks/0/props/density").asText()).isEqualTo("compact");
        assertThat(source.at("/blocks/0/props/density").asText()).isEqualTo("normal");
    }

    @Test
    void studioOnlyDataBindingCannotModifyDraft() throws Exception {
        ObjectNode source = snapshot("""
                {"id":"table-1","blockType":"table","dataSource":"orders"}
                """);

        assertThatThrownBy(() -> engine.prepareInline(
                source,
                "table-1",
                "/dataSource",
                PatchOperation.REPLACE,
                objectMapper.getNodeFactory().textNode("payments"),
                checksum("table"), ResourceScope.CURRENT_PAGE))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("authoring.handoff.business_semantic");
        assertThat(source.at("/blocks/0/dataSource").asText()).isEqualTo("orders");
    }

    @Test
    void studioRouteCanPersistDeclaredDataBindingIntoIsolatedDraft() throws Exception {
        ObjectNode source = snapshot("""
                {"id":"table-1","blockType":"table","dataSource":{"model":"orders"}}
                """);

        AuthoringPatchEngine.PreparedPatch patch = engine.prepareStudio(
                source,
                "table-1",
                "/dataSource",
                PatchOperation.REPLACE,
                objectMapper.readTree("{\"model\":\"payments\"}"),
                checksum("table"), ResourceScope.CURRENT_PAGE);

        assertThat(patch.decision().route()).isEqualTo(Route.HANDOFF_STUDIO);
        assertThat(patch.snapshot().at("/blocks/0/dataSource/model").asText())
                .isEqualTo("payments");
        assertThat(source.at("/blocks/0/dataSource/model").asText()).isEqualTo("orders");
    }

    @Test
    void studioMoveReordersStableSiblingsWithoutMutatingTheSource() throws Exception {
        ObjectNode source = (ObjectNode) objectMapper.readTree("""
                {"blocks":[{"id":"form-1","blockType":"form","blocks":[
                  {"id":"field-a","blockType":"field"},
                  {"id":"field-b","blockType":"field"},
                  {"id":"field-c","blockType":"field"}
                ]}]}
                """);

        AuthoringPatchEngine.PreparedPatch patch = engine.prepareStudioMove(
                source,
                "field-b",
                "field-a",
                checksum("field"),
                ResourceScope.CURRENT_PAGE);

        assertThat(patch.decision().route()).isEqualTo(Route.GUIDED_INLINE);
        assertThat(patch.previousValue().path("beforeBlockId").asText()).isEqualTo("field-c");
        assertThat(patch.savedValue().path("beforeBlockId").asText()).isEqualTo("field-a");
        assertThat(patch.snapshot().at("/blocks/0/blocks/0/id").asText()).isEqualTo("field-b");
        assertThat(source.at("/blocks/0/blocks/0/id").asText()).isEqualTo("field-a");
    }

    @Test
    void studioMoveRejectsCrossParentTargetsAndNoOps() throws Exception {
        ObjectNode source = (ObjectNode) objectMapper.readTree("""
                {"blocks":[
                  {"id":"left","blockType":"form-section","blocks":[
                    {"id":"field-a","blockType":"field"},
                    {"id":"field-b","blockType":"field"}
                  ]},
                  {"id":"right","blockType":"form-section","blocks":[
                    {"id":"field-c","blockType":"field"}
                  ]}
                ]}
                """);

        assertThatThrownBy(() -> engine.prepareStudioMove(
                source,
                "field-a",
                "field-c",
                checksum("field"),
                ResourceScope.CURRENT_PAGE))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("target-not-sibling");
        assertThatThrownBy(() -> engine.prepareStudioMove(
                source,
                "field-a",
                "field-b",
                checksum("field"),
                ResourceScope.CURRENT_PAGE))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no-op");
    }

    @Test
    void studioStructureAdaptersCreateRemoveAndRelocateOnlyServerOwnedCoreNodes() throws Exception {
        ObjectNode source = (ObjectNode) objectMapper.readTree("""
                {"kind":"form","blocks":[{"id":"form-root","blockType":"form","blocks":[
                  {"id":"left","blockType":"form-section","blocks":[
                    {"id":"field-a","blockType":"field"}
                  ]},
                  {"id":"right","blockType":"form-section","blocks":[]}
                ]}]}
                """);

        AuthoringPatchEngine.PreparedPatch created = engine.prepareStudioCreate(
                source, "middle", "form-section", "form-root", null,
                checksum("form-section"), ResourceScope.CURRENT_PAGE);
        assertThat(created.decision().route()).isEqualTo(Route.HANDOFF_STUDIO);
        assertThat(created.snapshot().at("/blocks/0/blocks/2/id").asText()).isEqualTo("middle");
        assertThat(created.snapshot().at("/blocks/0/blocks/2/blocks").isEmpty()).isTrue();

        AuthoringPatchEngine.PreparedPatch relocated = engine.prepareStudioRelocate(
                created.snapshot(), "field-a", "right", null,
                checksum("field"), ResourceScope.CURRENT_PAGE);
        assertThat(relocated.snapshot().at("/blocks/0/blocks/0/blocks").isEmpty()).isTrue();
        assertThat(relocated.snapshot().at("/blocks/0/blocks/1/blocks/0/id").asText())
                .isEqualTo("field-a");
        assertThat(relocated.previousValue().path("parentBlockId").asText()).isEqualTo("left");
        assertThat(relocated.savedValue().path("parentBlockId").asText()).isEqualTo("right");

        AuthoringPatchEngine.PreparedPatch removed = engine.prepareStudioRemove(
                relocated.snapshot(), "middle", checksum("form-section"), ResourceScope.CURRENT_PAGE);
        assertThat(removed.snapshot().at("/blocks/0/blocks").size()).isEqualTo(2);
        assertThat(removed.previousValue().path("blockId").asText()).isEqualTo("middle");
        assertThat(source.at("/blocks/0/blocks/0/blocks/0/id").asText()).isEqualTo("field-a");
    }

    @Test
    void studioCanCreateAColumnOnlyWhenItsFieldBelongsToThePageModel() throws Exception {
        when(metaModelService.getModelFields("production_exception"))
                .thenReturn(List.of(FieldDefinition.builder().code("exception_no").build()));
        ObjectNode source = (ObjectNode) objectMapper.readTree("""
                {"modelCode":"production_exception","kind":"list","blocks":[
                  {"id":"list-root","blockType":"list","blocks":[
                    {"id":"table-1","blockType":"table","blocks":[]}
                  ]}
                ]}
                """);

        AuthoringPatchEngine.PreparedPatch created = engine.prepareStudioCreate(
                source, "column-exception-no", "column", "table-1", null,
                checksum("column"), ResourceScope.CURRENT_PAGE);
        AuthoringPatchEngine.PreparedPatch bound = engine.prepareStudio(
                created.snapshot(), "column-exception-no", "/field", PatchOperation.ADD,
                objectMapper.getNodeFactory().textNode("exception_no"),
                checksum("column"), ResourceScope.CURRENT_PAGE);

        assertThat(bound.decision().route()).isEqualTo(Route.HANDOFF_STUDIO);
        assertThat(bound.snapshot().at("/blocks/0/blocks/0/blocks/0/field").asText())
                .isEqualTo("exception_no");
        assertThatThrownBy(() -> engine.prepareStudio(
                created.snapshot(), "column-exception-no", "/field", PatchOperation.ADD,
                objectMapper.getNodeFactory().textNode("foreign_secret"),
                checksum("column"), ResourceScope.CURRENT_PAGE))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("protected_semantic_invalid");
    }

    @Test
    void studioStructureAdaptersRejectUnknownContainmentRootsCyclesAndDuplicateIds() throws Exception {
        ObjectNode source = (ObjectNode) objectMapper.readTree("""
                {"kind":"form","blocks":[{"id":"form-root","blockType":"form","blocks":[
                  {"id":"outer","blockType":"form-section","blocks":[
                    {"id":"inner","blockType":"form-section","blocks":[]},
                    {"id":"field-a","blockType":"field"}
                  ]}
                ]}]}
                """);

        assertThatThrownBy(() -> engine.prepareStudioCreate(
                source, "outer", "form-section", "form-root", null,
                checksum("form-section"), ResourceScope.CURRENT_PAGE))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("block-id-exists");
        assertThatThrownBy(() -> engine.prepareStudioCreate(
                source, "chart-a", "chart", "outer", null,
                checksum("chart"), ResourceScope.CURRENT_PAGE))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("containment-denied");
        assertThatThrownBy(() -> engine.prepareStudioRelocate(
                source, "outer", "inner", null,
                checksum("form-section"), ResourceScope.CURRENT_PAGE))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("cycle-denied");
        assertThatThrownBy(() -> engine.prepareStudioRemove(
                source, "form-root", checksum("form"), ResourceScope.CURRENT_PAGE))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("root-delete-denied");
        assertThatThrownBy(() -> engine.prepareStudioCreate(
                source, "plugin-a", "plugin-secret", "outer", null,
                "untrusted", ResourceScope.CURRENT_PAGE))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("capability_unknown");
    }

    @Test
    void sharedResourceCannotUseInlineRouteEvenForPresentationPatch() throws Exception {
        ObjectNode source = snapshot("""
                {"id":"table-1","blockType":"table","props":{"density":"normal"}}
                """);

        assertThatThrownBy(() -> engine.prepareInline(
                source,
                "table-1",
                "/props/density",
                PatchOperation.REPLACE,
                objectMapper.getNodeFactory().textNode("compact"),
                checksum("table"), ResourceScope.SHARED_PAGE))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("authoring.handoff.shared_resource");
        assertThat(source.at("/blocks/0/props/density").asText()).isEqualTo("normal");
    }

    @Test
    void unknownAndIdentityPathsFailClosed() throws Exception {
        ObjectNode source = snapshot("""
                {"id":"field-1","blockType":"field","props":{"label":"Name"}}
                """);

        assertThatThrownBy(() -> engine.prepareInline(
                source,
                "field-1",
                "/props/permission",
                PatchOperation.ADD,
                objectMapper.getNodeFactory().textNode("admin"),
                checksum("field"), ResourceScope.CURRENT_PAGE))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("capability_unknown");
        assertThatThrownBy(() -> engine.prepareInline(
                source,
                "field-1",
                "/id",
                PatchOperation.REPLACE,
                objectMapper.getNodeFactory().textNode("stolen"),
                checksum("field"), ResourceScope.CURRENT_PAGE))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("capability_unknown");
    }

    @Test
    void staleManifestFailsBeforeMutation() throws Exception {
        ObjectNode source = snapshot("""
                {"id":"field-1","blockType":"field","props":{"label":"Name"}}
                """);

        assertThatThrownBy(() -> engine.prepareInline(
                source,
                "field-1",
                "/props/label",
                PatchOperation.REPLACE,
                objectMapper.getNodeFactory().textNode("Customer"),
                "stale", ResourceScope.CURRENT_PAGE))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("manifest_stale");
        assertThat(source.at("/blocks/0/props/label").asText()).isEqualTo("Name");
    }

    @Test
    void richTextIsSanitizedAtSaveTime() throws Exception {
        ObjectNode source = snapshot("""
                {"id":"desc-1","blockType":"description","props":{"content":"old"}}
                """);

        AuthoringPatchEngine.PreparedPatch patch = engine.prepareInline(
                source,
                "desc-1",
                "/props/content",
                PatchOperation.REPLACE,
                objectMapper.getNodeFactory().textNode(
                        "<b>safe</b><script>alert(1)</script><a href=\"javascript:alert(2)\">x</a>"),
                checksum("description"), ResourceScope.CURRENT_PAGE);

        String saved = patch.savedValue().asText();
        assertThat(saved).contains("<b>safe</b>");
        assertThat(saved).doesNotContain("script", "javascript:");
    }

    @Test
    void unsafeNavigationTargetIsRejected() throws Exception {
        ObjectNode source = snapshot("""
                {"id":"action-1","blockType":"action","props":{"targetPage":"/orders"}}
                """);

        assertThatThrownBy(() -> engine.prepareInline(
                source,
                "action-1",
                "/props/targetPage",
                PatchOperation.REPLACE,
                objectMapper.getNodeFactory().textNode("javascript:alert(1)"),
                checksum("action"), ResourceScope.CURRENT_PAGE))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("unsafe-target");
    }

    @Test
    void destructiveActionCannotBecomeMisleadingOrLoseDangerStyle() throws Exception {
        CommandDefinition deleteCommand = new CommandDefinition();
        deleteCommand.setCode("order.delete");
        deleteCommand.setCmdRiskLevel("L4");
        when(commandDefinitionMapper.findCurrentByCode("order.delete")).thenReturn(deleteCommand);
        ObjectNode source = snapshot("""
                {"id":"action-1","blockType":"action","props":{
                  "commandCode":"order.delete","label":"Delete order","variant":"danger"
                }}
                """);

        assertThatThrownBy(() -> engine.prepareInline(
                source,
                "action-1",
                "/props/label",
                PatchOperation.REPLACE,
                objectMapper.getNodeFactory().textNode("Save"),
                checksum("action"), ResourceScope.CURRENT_PAGE))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("protected_semantic_invalid");
        assertThatThrownBy(() -> engine.prepareInline(
                source,
                "action-1",
                "/props/variant",
                PatchOperation.REPLACE,
                objectMapper.getNodeFactory().textNode("primary"),
                checksum("action"), ResourceScope.CURRENT_PAGE))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("protected_semantic_invalid");
    }

    private ObjectNode snapshot(String block) throws Exception {
        return (ObjectNode) objectMapper.readTree("{\"blocks\":[" + block + "]}");
    }

    private String checksum(String blockType) {
        return registry.find(blockType).orElseThrow().checksum();
    }
}
