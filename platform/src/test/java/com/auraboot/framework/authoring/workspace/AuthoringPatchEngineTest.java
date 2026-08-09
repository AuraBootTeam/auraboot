package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.authoring.policy.AuthoringBoundaryPolicyService;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.PatchOperation;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.ResourceScope;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.Route;
import com.auraboot.framework.authoring.policy.CoreAuthoringCapabilityRegistry;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.mapper.CommandDefinitionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthoringPatchEngineTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private CoreAuthoringCapabilityRegistry registry;
    private CommandDefinitionMapper commandDefinitionMapper;
    private AuthoringPatchEngine engine;

    @BeforeEach
    void setUp() {
        registry = new CoreAuthoringCapabilityRegistry();
        commandDefinitionMapper = mock(CommandDefinitionMapper.class);
        engine = new AuthoringPatchEngine(
                registry,
                new AuthoringBoundaryPolicyService(registry),
                new AuthoringContentSanitizer(),
                new AuthoringProtectedSemanticValidator(commandDefinitionMapper),
                new AuthoringSnapshotTargetResolver(),
                new AuthoringJsonObjectPatchApplier(),
                new AuthoringStableBlockTreeEditor());
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
