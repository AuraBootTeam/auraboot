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

    @Test void optimisticDraftEditsAndBreakingRevisionsLeavePublishedContractIntact() {
        var definition = fixture();
        publications.saveDraft(definition.code(), new OpenApiPublicationService.DraftRequest(0, definition));
        publications.publish(definition.code(), 1);
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
}
