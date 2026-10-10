package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.meta.dto.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static com.auraboot.framework.openplatform.service.OpenApiResourceContract.*;
import static org.assertj.core.api.Assertions.*;

class OpenApiPublicationValidatorTest {
    private final OpenApiPublicationValidator validator = new OpenApiPublicationValidator(null);

    private OpenApiResourceContract contract(Set<Operation> operations, boolean required, boolean nullable) {
        var scopes = new java.util.LinkedHashMap<Operation, String>();
        operations.forEach(op -> scopes.put(op, "customers." + op.name().toLowerCase()));
        return new OpenApiResourceContract("customers", "crm_customer", 1, operations, scopes,
                DataPolicy.TENANT_WIDE, Map.of(
                "pid", new Field("pid", Type.PID, true, false, false, false, Set.of(), false, null, Map.of(), null),
                "name", new Field("crm_name", Type.STRING, true, true, required, nullable, Set.of("EQ"), true, null, Map.of(), null)),
                50, 8, 5000, Set.of());
    }

    private ModelDefinition model() {
        return ModelDefinition.builder().code("crm_customer").status("published").sourceType("physical")
                .capabilities(ModelCapabilities.fullPhysical().toBuilder()
                        .filterableFields(List.of("crm_name")).sortableFields(List.of("crm_name")).build())
                .fields(List.of(FieldDefinition.builder().code("crm_name").dataType("STRING").required(true).build())).build();
    }

    @Test void validatesAnExplicitPhysicalCreateContract() {
        assertThatCode(() -> validator.validate(contract(Set.of(Operation.CREATE, Operation.GET), true, false), model()))
                .doesNotThrowAnyException();
    }

    @Test void reservedFixedAndContributorRoutesCannotBeShadowed() {
        var base = contract(Set.of(Operation.GET), false, true);
        var reserved = new OpenApiResourceContract("assets", base.modelCode(), 1, base.operations(), base.scopes(),
                base.dataPolicy(), base.fields(), 50, 8, 5000, Set.of());
        assertThatThrownBy(() -> validator.validate(reserved, model())).hasMessageContaining("reserved");
        var contributor = (OpenApiCapabilityContributor) () -> List.of(new OpenApiCapabilityRegistry.Capability(
                "plugin.customer.get", org.springframework.http.HttpMethod.GET,
                "/api/open/v1/resources/customers/{recordPid}", "customers.get", "tenant-wide", 1));
        assertThatThrownBy(() -> new OpenApiPublicationValidator(null, List.of(contributor)).validate(base, model()))
                .hasMessageContaining("reserved");
    }

    @Test void cannotPublishGenericWritesForVirtualImmutableOrCommandOnlyModels() {
        var contract = contract(Set.of(Operation.CREATE, Operation.UPDATE, Operation.DELETE), true, false);
        var model = model();
        model.setSourceType("namedQuery");
        assertThatThrownBy(() -> validator.validate(contract, model)).hasMessageContaining("read-only");
        model.setSourceType("physical");
        model.setImmutable(true);
        assertThatThrownBy(() -> validator.validate(contract, model)).hasMessageContaining("Immutable");
        model.setImmutable(false);
        model.setCommandOnlyCreate(true);
        assertThatThrownBy(() -> validator.validate(contract, model)).hasMessageContaining("Command-only");
        model.setCommandOnlyCreate(false);
        model.setExtension(Map.of("commandOnlyDelete", true));
        assertThatThrownBy(() -> validator.validate(contract, model)).hasMessageContaining("Command-only");
    }

    @Test void modelCapabilitiesAndRequiredInputsCannotBeExpandedByPublication() {
        var contract = contract(Set.of(Operation.CREATE), true, false);
        var model = model();
        model.setCapabilities(ModelCapabilities.virtualReadOnly());
        assertThatThrownBy(() -> validator.validate(contract, model)).hasMessageContaining("capabilities");
        assertThatThrownBy(() -> validator.validate(contract(Set.of(Operation.CREATE), false, true), model()))
                .hasMessageContaining("Required model input");
    }

    @Test void rejectsMissingFieldsAndQueryPermissionsEvenWhenReadAccessExists() {
        var contract = contract(Set.of(Operation.GET), false, true);
        var missingModel = model();
        missingModel.setFields(List.of());
        assertThatThrownBy(() -> validator.validate(contract, missingModel)).hasMessageContaining("does not exist");
        var model = model();
        model.setCapabilities(ModelCapabilities.fullPhysical());
        final var unfilterableModel = model;
        assertThatThrownBy(() -> validator.validate(contract, unfilterableModel)).hasMessageContaining("Sort");
    }
}
