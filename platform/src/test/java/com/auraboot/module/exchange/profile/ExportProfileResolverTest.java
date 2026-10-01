package com.auraboot.module.exchange.profile;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.DataExportRequest;
import com.auraboot.framework.meta.dto.DynamicDataExportRequest;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.service.MetaModelService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExportProfileResolverTest {

    private final MetaModelService models = mock(MetaModelService.class);
    private final ExportProfileResolver resolver = new ExportProfileResolver(models);

    @Test
    void resolvesTheStandardControlledProfileWhenNoNarrowingPolicyIsDeclared() {
        when(models.getModelDefinition("customer")).thenReturn(Optional.of(
                ModelDefinition.builder().code("customer").build()));

        ExportProfile profile = resolver.requireEnabled("customer");

        assertThat(profile.getCode()).isEqualTo("customer:default-export");
        assertThat(profile.getScopes()).containsExactlyInAnyOrder(
                DynamicDataExportRequest.Scope.selected,
                DynamicDataExportRequest.Scope.filtered);
        assertThat(profile.getFormats()).containsExactlyInAnyOrder(
                DataExportRequest.ExportFormat.EXCEL,
                DataExportRequest.ExportFormat.CSV);
        assertThat(profile.getFields()).isEmpty();
    }

    @Test
    void explicitProfileNarrowsScopesFormatsAndFields() {
        when(models.getModelDefinition("customer")).thenReturn(Optional.of(model(Map.of(
                "enabled", true,
                "code", "customer:public-export",
                "scopes", List.of("selected"),
                "formats", List.of("xlsx"),
                "fields", List.of("customer_code", "customer_name")))));
        when(models.getModelFields("customer")).thenReturn(List.of(
                FieldDefinition.builder().code("customer_code").build(),
                FieldDefinition.builder().code("customer_name").build(),
                FieldDefinition.builder().code("credit_limit").build()));

        ExportProfile profile = resolver.requireEnabled("customer");

        assertThat(profile.getCode()).isEqualTo("customer:public-export");
        assertThat(profile.getScopes()).containsExactly(DynamicDataExportRequest.Scope.selected);
        assertThat(profile.getFormats()).containsExactly(DataExportRequest.ExportFormat.EXCEL);
        assertThat(resolver.resolveFields(profile, null))
                .containsExactly("customer_code", "customer_name");
        assertThatThrownBy(() -> resolver.resolveFields(profile, List.of("credit_limit")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not allowed");
    }

    @Test
    void rejectsUnsupportedScopeInsteadOfWidening() {
        when(models.getModelDefinition("customer")).thenReturn(Optional.of(model(Map.of(
                "scopes", List.of("dataset"),
                "fields", List.of("unknown")))));

        assertThatThrownBy(() -> resolver.requireEnabled("customer"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Unsupported export scope");
    }

    @Test
    void rejectsUnknownWhitelistedFields() {
        when(models.getModelDefinition("customer")).thenReturn(Optional.of(model(Map.of(
                "fields", List.of("unknown")))));
        when(models.getModelFields("customer")).thenReturn(List.of(
                FieldDefinition.builder().code("customer_code").build()));

        assertThatThrownBy(() -> resolver.requireEnabled("customer"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Unknown export profile fields");
    }

    private static ModelDefinition model(Map<String, Object> policy) {
        return ModelDefinition.builder()
                .code("customer")
                .extension(Map.of("exportPolicy", policy))
                .build();
    }
}
