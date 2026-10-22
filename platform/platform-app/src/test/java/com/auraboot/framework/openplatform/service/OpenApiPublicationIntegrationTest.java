package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelCapabilities;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.CreateApplicationRequest;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.InstallApplicationRequest;
import com.auraboot.framework.openplatform.mapper.ApplicationInstallationMapper;
import com.auraboot.framework.openplatform.mapper.OpenApiPublicationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static com.auraboot.framework.openplatform.service.OpenApiResourceContract.*;
import static org.assertj.core.api.Assertions.*;

/** Exercises production publication services and mapper JSONB contracts against PostgreSQL. */
class OpenApiPublicationIntegrationTest extends BaseIntegrationTest {
    @Autowired private OpenApiPublicationService publications;
    @Autowired private OpenApiPublicationMapper mapper;
    @Autowired private MetaModelService models;
    @Autowired private OpenPlatformManagementService management;
    @Autowired private ApplicationInstallationMapper installations;
    @Autowired private com.fasterxml.jackson.databind.ObjectMapper json;

    @Autowired private com.auraboot.framework.openplatform.controller.OpenApiPublicationController documents;

    @BeforeEach void authorizeAdministrator() {
        grantCommittedPermissionToTestRole("sys.connector.update", "system", "connector", "update", "Manage Open Platform");
    }

    private OpenApiResourceContract fixture() {
        String suffix = UniqueIdGenerator.generate().toLowerCase();
        String model = "open_pub_" + suffix;
        models.saveDefinition(ModelDefinition.builder().code(model).tableName("mt_" + model).displayName("Public resource test")
                .status("published").sourceType("physical").primaryKey("id")
                .fields(List.of(FieldDefinition.builder().code("name").displayName("Name").dataType("string").build()))
                .capabilities(ModelCapabilities.fullPhysical()).build());
        return new OpenApiResourceContract("fixture." + suffix, model, 1, Set.of(Operation.GET),
                Map.of(Operation.GET, "openapi.profile.read"), DataPolicy.TENANT_WIDE,
                Map.of("pid", new Field("pid", Type.PID, true, false, false, false, Set.of(), false, null, Map.of(), null),
                       "name", new Field("name", Type.STRING, true, false, false, true, Set.of(), false, null, Map.of(), null)),
                50, 8, 5000, Set.of());
    }

    @Test void draftRoundTripPublicationAndWithdrawalRetainImmutableSnapshot() {
        var definition = fixture();
        var draft = publications.saveDraft(definition.code(), new OpenApiPublicationService.DraftRequest(0, definition));
        assertThat(draft.draft()).isEqualTo(definition);
        assertThat(draft.draftRevision()).isEqualTo(1);
        assertThat(publications.active(getTestTenant().getId(), definition.code())).isEmpty();
        assertThatThrownBy(() -> publications.publish(definition.code(), 99))
                .isInstanceOf(OpenApiPublicationService.PublicationConflictException.class);
        var published = publications.publish(definition.code(), 1);
        assertThat(published.status()).isEqualTo("published");
        assertThat(publications.active(getTestTenant().getId(), definition.code())).contains(definition);
        assertThat(publications.active(getTestTenant().getId() + 1000000, definition.code())).isEmpty();
        String snapshot = mapper.snapshot(getTestTenant().getId(), definition.code(), 1);
        publications.withdraw(definition.code(), 1);
        assertThat(publications.active(getTestTenant().getId(), definition.code())).isEmpty();
        assertThat(mapper.snapshot(getTestTenant().getId(), definition.code(), 1)).isEqualTo(snapshot);
    }

    @Test void editorSourceUsesDeclaredRuntimeCapabilitiesAndRejectsMachineAdministration() {
        String code = "open_editor_" + UniqueIdGenerator.generate().toLowerCase();
        models.saveDefinition(ModelDefinition.builder().code(code).tableName("mt_" + code).displayName("Restricted source")
                .status("published").sourceType("physical").immutable(true).commandOnlyCreate(true)
                .capabilities(ModelCapabilities.fullPhysical())
                .fields(List.of(FieldDefinition.builder().code("name").displayName("Part name").dataType("string").build(),
                        FieldDefinition.builder().code("tenant_id").displayName("Private tenant").dataType("integer").build()))
                .build());
        var source = publications.sourceForAdministration(code);
        assertThat(source.name()).isEqualTo("Restricted source");
        assertThat(source.operations()).containsExactlyInAnyOrder(Operation.LIST, Operation.GET);
        assertThat(source.fields()).extracting(OpenApiPublicationValidator.SourceField::code)
                .contains("name", "created_at", "updated_at").doesNotContain("id", "pid", "tenant_id", "created_by", "updated_by", "row_version");
        var name = source.fields().stream().filter(field -> "name".equals(field.code())).findFirst().orElseThrow();
        assertThat(name.filterable()).isFalse();
        assertThat(name.sortable()).isFalse();
        assertThat(name.types()).contains(Type.STRING);
        assertThat(source.fields().stream().filter(field -> Set.of("created_at", "updated_at").contains(field.code())))
                .allMatch(field -> !field.writable());
        var context = MetaContext.snapshot();
        try {
            MetaContext.setContext(getTestTenant().getId(), null, "machine-token", "machine");
            assertThatThrownBy(() -> publications.sourceForAdministration(code)).isInstanceOf(AccessDeniedException.class);
        } finally { MetaContext.restore(context); }
    }

    @Test void publicReadTimestampIsAllowedButForgedWriteTimestampIsRejected() {
        var definition = fixture();
        var fields = new LinkedHashMap<>(definition.fields());
        fields.put("createdAt", new Field("created_at", Type.DATETIME, true, true, false, false,
                Set.of(), false, null, Map.of(), null));
        var forged = new OpenApiResourceContract(definition.code(), definition.modelCode(), 1,
                definition.operations(), definition.scopes(), definition.dataPolicy(), fields, 50, 8, 5000, Set.of());
        assertThatThrownBy(() -> publications.saveDraft(forged.code(), new OpenApiPublicationService.DraftRequest(0, forged)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("timestamps are read-only");
        assertThat(publications.list()).noneMatch(view -> view.code().equals(forged.code()));
        fields.put("createdAt", new Field("created_at", Type.DATETIME, true, false, false, false,
                Set.of(), false, null, Map.of(), null));
        var readable = new OpenApiResourceContract(definition.code(), definition.modelCode(), 1,
                definition.operations(), definition.scopes(), definition.dataPolicy(), fields, 50, 8, 5000, Set.of());
        assertThat(publications.saveDraft(readable.code(), new OpenApiPublicationService.DraftRequest(0, readable)).draft())
                .isEqualTo(readable);
    }

    @Test void optimisticDraftEditsAndBreakingRevisionsLeavePublishedContractIntact() {
        var definition = fixture();
        publications.saveDraft(definition.code(), new OpenApiPublicationService.DraftRequest(0, definition));
        publications.publish(definition.code(), 1);
        String originalSnapshot = mapper.snapshot(getTestTenant().getId(), definition.code(), 1);
        var fields = new LinkedHashMap<>(definition.fields());
        fields.remove("name");
        var breaking = new OpenApiResourceContract(definition.code(), definition.modelCode(), 2,
                definition.operations(), definition.scopes(), definition.dataPolicy(), fields, 50, 8, 5000, Set.of());
        publications.saveDraft(definition.code(), new OpenApiPublicationService.DraftRequest(1, breaking));
        assertThatThrownBy(() -> publications.saveDraft(definition.code(), new OpenApiPublicationService.DraftRequest(1, definition)))
                .isInstanceOf(OpenApiPublicationService.PublicationConflictException.class);
        assertThatThrownBy(() -> publications.publish(definition.code(), 2)).hasMessageContaining("cannot change");
        assertThat(publications.active(getTestTenant().getId(), definition.code())).contains(definition);
        assertThat(mapper.snapshot(getTestTenant().getId(), definition.code(), 2)).isNull();
        assertThat(mapper.snapshot(getTestTenant().getId(), definition.code(), 1)).isEqualTo(originalSnapshot);

        var application = management.createApplication(new CreateApplicationRequest("Major publication fixture", "Independent grants"));
        var installation = management.install(application.pid(), new InstallApplicationRequest("staging", definition.scopes().values().stream()
                .collect(java.util.stream.Collectors.toSet()), 100));
        Long installationId = installations.findByTenantAndPid(getTestTenant().getId(), installation.pid()).getId();
        var originalPolicy = new OpenApiInstallationPolicy(Set.of(Operation.GET), Set.of("pid"), Set.of(), List.of());
        publications.grant(installation.pid(), definition.code(), originalPolicy);
        String originalStoredPolicy = mapper.policy(getTestTenant().getId(), installationId, definition.code());
        assertThat(originalStoredPolicy).isNotBlank();

        var majorFields = new LinkedHashMap<>(fields);
        majorFields.put("displayName", definition.fields().get("name"));
        var major = new OpenApiResourceContract(definition.code() + ".v2", definition.modelCode(), 1,
                definition.operations(), definition.scopes(), definition.dataPolicy(), majorFields, 50, 8, 5000, Set.of());
        publications.saveDraft(major.code(), new OpenApiPublicationService.DraftRequest(0, major));
        assertThat(publications.publish(major.code(), 1).activeVersion()).isEqualTo(1);
        assertThat(publications.active(getTestTenant().getId(), major.code())).contains(major);
        assertThat(mapper.snapshot(getTestTenant().getId(), major.code(), 1)).isNotBlank().isNotEqualTo(originalSnapshot);
        assertThat(mapper.policy(getTestTenant().getId(), installationId, major.code())).isNull();
        assertThatThrownBy(() -> publications.requirePolicy(getTestTenant().getId(), installationId, major))
                .isInstanceOf(AccessDeniedException.class);

        var majorPolicy = new OpenApiInstallationPolicy(Set.of(Operation.GET), Set.of("pid", "displayName"), Set.of(), List.of());
        publications.grant(installation.pid(), major.code(), majorPolicy);
        assertThat(publications.requirePolicy(getTestTenant().getId(), installationId, major)).isEqualTo(majorPolicy);
        assertThat(publications.requirePolicy(getTestTenant().getId(), installationId, definition)).isEqualTo(originalPolicy);
        assertThat(mapper.policy(getTestTenant().getId(), installationId, definition.code())).isEqualTo(originalStoredPolicy);
        assertThat(publications.active(getTestTenant().getId(), definition.code())).contains(definition);
        assertThat(mapper.snapshot(getTestTenant().getId(), definition.code(), 1)).isEqualTo(originalSnapshot);
        assertThat(mapper.snapshot(getTestTenant().getId(), definition.code(), 2)).isNull();
    }

    @Test void referencePublicationRejectsAResourceBoundToAnotherModel() {
        var target = fixture();
        publications.saveDraft(target.code(), new OpenApiPublicationService.DraftRequest(0, target));
        publications.publish(target.code(), 1);
        String suffix = UniqueIdGenerator.generate().toLowerCase();
        String modelCode = "open_ref_" + suffix;
        // Deliberately bind the model field to the owner while the public alias names the other resource.
        models.saveDefinition(ModelDefinition.builder().code(modelCode).tableName("mt_" + modelCode)
                .displayName("Reference binding fixture").status("published").sourceType("physical").primaryKey("id")
                .capabilities(ModelCapabilities.fullPhysical()).fields(List.of(FieldDefinition.builder()
                        .code("name").displayName("Reference").dataType("REFERENCE")
                        .refTarget(FieldDefinition.RefTarget.builder().targetEntity(modelCode).valueField("pid").build()).build())).build());
        var fields = new LinkedHashMap<>(target.fields());
        fields.put("name", new Field("name", Type.PID, true, false, false, true, Set.of(), false,
                target.code(), Map.of(), null));
        var reference = new OpenApiResourceContract("fixture." + suffix, modelCode, 1, target.operations(), target.scopes(),
                target.dataPolicy(), fields, 50, 8, 5000, Set.of());
        publications.saveDraft(reference.code(), new OpenApiPublicationService.DraftRequest(0, reference));
        assertThatThrownBy(() -> publications.publish(reference.code(), 1)).hasMessageContaining("declared model");
        assertThat(mapper.snapshot(getTestTenant().getId(), reference.code(), 1)).isNull();
    }

    @Test void installationGrantIsExplicitAndRevocationAndMachineAdministrationFailClosed() {
        var definition = fixture();
        publications.saveDraft(definition.code(), new OpenApiPublicationService.DraftRequest(0, definition));
        publications.publish(definition.code(), 1);
        var application = management.createApplication(new CreateApplicationRequest("Publication fixture", "Explicit grant test"));
        var installation = management.install(application.pid(), new InstallApplicationRequest("staging", Set.of("openapi.profile.read"), 100));
        Long installationId = installations.findByTenantAndPid(getTestTenant().getId(), installation.pid()).getId();
        assertThatThrownBy(() -> publications.requirePolicy(getTestTenant().getId(), installationId, definition))
                .isInstanceOf(AccessDeniedException.class);
        var policy = new OpenApiInstallationPolicy(Set.of(Operation.GET), Set.of("pid"), Set.of(), List.of());
        publications.grant(installation.pid(), definition.code(), policy);
        assertThat(publications.requirePolicy(getTestTenant().getId(), installationId, definition)).isEqualTo(policy);
        publications.revoke(installation.pid(), definition.code());
        assertThatThrownBy(() -> publications.requirePolicy(getTestTenant().getId(), installationId, definition))
                .isInstanceOf(AccessDeniedException.class);
        MetaContext.setContext(getTestTenant().getId(), null, "machine-token", "machine");
        try { assertThatThrownBy(publications::list).isInstanceOf(AccessDeniedException.class); }
        finally { applyTestMetaContext(); }
    }
    @Test void downloadableArtifactsUseActiveSnapshotAndRejectDraftWithdrawnMachineAndOtherTenant() {
        var definition = fixture();
        publications.saveDraft(definition.code(), new OpenApiPublicationService.DraftRequest(0, definition));
        assertThatThrownBy(() -> documents.document(definition.code())).isInstanceOf(IllegalArgumentException.class);
        publications.publish(definition.code(), 1);
        assertThat(documents.document(definition.code())).containsEntry("x-schema-version", 1);
        var types = documents.types(definition.code());
        assertThat(types.getStatusCode().value()).isEqualTo(200);
        assertThat(types.getHeaders().getFirst("Content-Disposition")).contains(definition.code(), ".d.ts");
        assertThat(types.getBody()).contains("schema 1", "export type Resource").doesNotContain(definition.modelCode());
        var next = new OpenApiResourceContract(definition.code(), definition.modelCode(), 2, definition.operations(), definition.scopes(),
                definition.dataPolicy(), definition.fields(), 50, 8, 5000, Set.of());
        publications.saveDraft(definition.code(), new OpenApiPublicationService.DraftRequest(1, next));
        assertThat(documents.document(definition.code())).containsEntry("x-schema-version", 1);
        assertThat(documents.types(definition.code()).getBody()).contains("schema 1").doesNotContain("schema 2");
        var context = MetaContext.snapshot();
        try {
            MetaContext.setCurrentTenantId(getTestTenant().getId() + 1000000L);
            assertThatThrownBy(() -> documents.document(definition.code())).isInstanceOfAny(IllegalArgumentException.class, AccessDeniedException.class);
            MetaContext.setContext(getTestTenant().getId(), null, "machine-token", "machine");
            assertThatThrownBy(() -> documents.types(definition.code())).isInstanceOf(AccessDeniedException.class);
        } finally { MetaContext.restore(context); }
        publications.publish(definition.code(), 2);
        assertThat(documents.document(definition.code())).containsEntry("x-schema-version", 2);
        publications.withdraw(definition.code(), 2);
        assertThatThrownBy(() -> documents.document(definition.code())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void immutableSourceBindingSurvivesCompatibleRevisionAndRejectsTableRebinding() {
        var definition = fixture();
        publications.saveDraft(definition.code(), new OpenApiPublicationService.DraftRequest(0, definition));
        publications.publish(definition.code(), 1);
        String binding = mapper.sourceBinding(getTestTenant().getId(), definition.code(), 1);
        assertThat(binding).contains("mt_" + definition.modelCode(), "columns", "name");
        var model = models.getModelDefinition(definition.modelCode()).orElseThrow();
        var originalFields = List.copyOf(model.getFields());
        try {
            model.setFields(List.of(FieldDefinition.builder().code("private_replacement").dataType("STRING").build()));
            models.saveDefinition(model);
            var missing = models.getModelDefinition(definition.modelCode()).orElseThrow();
            assertThat(missing.getFields()).extracting(FieldDefinition::getCode).doesNotContain("name");
            assertThatThrownBy(() -> publications.requireSourceBinding(getTestTenant().getId(), definition, missing))
                    .isInstanceOf(OpenApiPublicationService.SourceBindingConflictException.class)
                    .hasCauseInstanceOf(IllegalArgumentException.class);
            assertThat(mapper.sourceBinding(getTestTenant().getId(), definition.code(), 1)).isEqualTo(binding);
            assertThat(publications.active(getTestTenant().getId(), definition.code())).contains(definition);
        } finally {
            model.setFields(originalFields);
            models.saveDefinition(model);
        }
        assertThat(publications.requireSourceBinding(getTestTenant().getId(), definition,
                models.getModelDefinition(definition.modelCode()).orElseThrow())).isNotNull();
        var expanded = new java.util.ArrayList<>(model.getFields());
        expanded.add(FieldDefinition.builder().code("extra").dataType("STRING").build());
        model.setFields(expanded);
        models.saveDefinition(model);
        var fields = new LinkedHashMap<>(definition.fields());
        fields.put("extra", new Field("extra", Type.STRING, true, false, false, true, Set.of(), false, null, Map.of(), null));
        var next = new OpenApiResourceContract(definition.code(), definition.modelCode(), 2,
                definition.operations(), definition.scopes(), definition.dataPolicy(), fields, 50, 8, 5000, Set.of());
        publications.saveDraft(definition.code(), new OpenApiPublicationService.DraftRequest(1, next));
        assertThat(publications.publish(definition.code(), 2).activeVersion()).isEqualTo(2);
        assertThat(mapper.sourceBinding(getTestTenant().getId(), definition.code(), 1)).isEqualTo(binding);
        assertThat(mapper.sourceBinding(getTestTenant().getId(), definition.code(), 2)).contains("extra");
        model = models.getModelDefinition(definition.modelCode()).orElseThrow();
        model.setTableName("replacement_" + definition.modelCode());
        models.saveDefinition(model);
        var rebound = new OpenApiResourceContract(definition.code(), definition.modelCode(), 3,
                next.operations(), next.scopes(), next.dataPolicy(), next.fields(), 50, 8, 5000, Set.of());
        publications.saveDraft(definition.code(), new OpenApiPublicationService.DraftRequest(2, rebound));
        assertThatThrownBy(() -> publications.publish(definition.code(), 3))
                .isInstanceOf(OpenApiPublicationService.PublicationConflictException.class).hasMessageContaining("source binding");
        assertThat(mapper.snapshot(getTestTenant().getId(), definition.code(), 3)).isNull();
        assertThat(publications.active(getTestTenant().getId(), definition.code())).contains(next);
    }

    @Test void historicalSnapshotWithoutProvenanceCannotBeReboundFromTodaysCatalog() throws Exception {
        var definition = fixture();
        publications.saveDraft(definition.code(), new OpenApiPublicationService.DraftRequest(0, definition));
        // Reproduce the previous production mapper contract without inferring historical storage.
        mapper.insertSnapshot(getTestTenant().getId(), definition.code(), 1, json.writeValueAsString(definition),
                MetaContext.getCurrentUserPid());
        mapper.activate(getTestTenant().getId(), definition.code(), 1, "published", MetaContext.getCurrentUserPid());
        assertThat(documents.document(definition.code())).containsEntry("x-schema-version", 1);
        var model = models.getModelDefinition(definition.modelCode()).orElseThrow();
        assertThatThrownBy(() -> publications.requireSourceBinding(getTestTenant().getId(), definition, model))
                .isInstanceOf(OpenApiPublicationService.SourceBindingConflictException.class);
        var next = new OpenApiResourceContract(definition.code(), definition.modelCode(), 2, definition.operations(),
                definition.scopes(), definition.dataPolicy(), definition.fields(), 50, 8, 5000, Set.of());
        publications.saveDraft(definition.code(), new OpenApiPublicationService.DraftRequest(1, next));
        assertThatThrownBy(() -> publications.publish(definition.code(), 2))
                .isInstanceOf(OpenApiPublicationService.SourceBindingConflictException.class);
        assertThat(mapper.sourceBinding(getTestTenant().getId(), definition.code(), 1)).isNull();
        assertThat(mapper.snapshot(getTestTenant().getId(), definition.code(), 2)).isNull();
    }

}
