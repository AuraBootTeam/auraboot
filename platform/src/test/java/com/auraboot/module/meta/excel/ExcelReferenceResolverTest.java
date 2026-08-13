package com.auraboot.module.meta.excel;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExcelReferenceResolverTest {

    @Mock
    private DynamicDataService dynamicDataService;

    @Mock
    private MetaModelService metaModelService;

    private ExcelReferenceResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ExcelReferenceResolver(dynamicDataService, metaModelService);
    }

    @Test
    void resolve_keepsAccessiblePidCompatible() {
        FieldDefinition field = accountReference(List.of("crm_acc_code"));
        when(dynamicDataService.getById("crm_account_common", "01KZACCOUNT"))
                .thenReturn(Map.of("pid", "01KZACCOUNT", "crm_acc_code", "ACC-001"));

        assertThat(resolver.resolve(field, "01KZACCOUNT")).isEqualTo("01KZACCOUNT");
    }

    @Test
    void resolve_mapsUniqueBusinessCodeToStoredPid() {
        FieldDefinition field = accountReference(List.of("crm_acc_code"));
        when(dynamicDataService.getById("crm_account_common", "ACC-001"))
                .thenThrow(new RuntimeException("not a pid"));
        when(dynamicDataService.list(eq("crm_account_common"), any(DynamicQueryRequest.class)))
                .thenReturn(PaginationResult.of(
                        List.of(Map.of("pid", "01KZACCOUNT", "crm_acc_code", "ACC-001")),
                        1L, 1, 2));

        assertThat(resolver.resolve(field, "ACC-001")).isEqualTo("01KZACCOUNT");

        ArgumentCaptor<DynamicQueryRequest> request = ArgumentCaptor.forClass(DynamicQueryRequest.class);
        verify(dynamicDataService).list(eq("crm_account_common"), request.capture());
        assertThat(request.getValue().getConditions()).singleElement().satisfies(condition -> {
            assertThat(condition.getFieldName()).isEqualTo("crm_acc_code");
            assertThat(condition.getValue()).isEqualTo("ACC-001");
        });
    }

    @Test
    void resolve_rejectsMissingBusinessValue() {
        FieldDefinition field = accountReference(List.of("crm_acc_code"));
        when(dynamicDataService.getById("crm_account_common", "ACC-MISSING"))
                .thenThrow(new RuntimeException("not a pid"));
        when(dynamicDataService.list(eq("crm_account_common"), any(DynamicQueryRequest.class)))
                .thenReturn(PaginationResult.empty(1, 2));

        assertThatThrownBy(() -> resolver.resolve(field, "ACC-MISSING"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("does not exist or is not accessible")
                .hasMessageContaining("crm_acc_code");
    }

    @Test
    void resolve_hidesLookupInfrastructureDetails() {
        FieldDefinition field = accountReference(List.of("crm_acc_code"));
        when(dynamicDataService.getById("crm_account_common", "ACC-FAIL"))
                .thenThrow(new RuntimeException("not a pid"));
        when(dynamicDataService.list(eq("crm_account_common"), any(DynamicQueryRequest.class)))
                .thenThrow(new RuntimeException("### MyBatis org.postgresql SQLState 22003"));

        assertThatThrownBy(() -> resolver.resolve(field, "ACC-FAIL"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("does not exist or is not accessible")
                .hasMessageNotContaining("MyBatis")
                .hasMessageNotContaining("postgresql")
                .hasMessageNotContaining("SQLState");
    }

    @Test
    void resolve_rejectsAmbiguousBusinessValue() {
        FieldDefinition field = accountReference(List.of("crm_acc_name"));
        when(dynamicDataService.getById("crm_account_common", "Acme"))
                .thenThrow(new RuntimeException("not a pid"));
        when(dynamicDataService.list(eq("crm_account_common"), any(DynamicQueryRequest.class)))
                .thenReturn(PaginationResult.of(
                        List.of(Map.of("pid", "01A"), Map.of("pid", "01B")), 2L, 1, 2));

        assertThatThrownBy(() -> resolver.resolve(field, "Acme"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ambiguous")
                .hasMessageContaining("crm_acc_name");
    }

    @Test
    void resolve_rejectsDifferentRecordsAcrossConfiguredFields() {
        FieldDefinition field = accountReference(List.of("crm_acc_code", "crm_acc_name"));
        when(dynamicDataService.getById("crm_account_common", "Shared"))
                .thenThrow(new RuntimeException("not a pid"));
        when(dynamicDataService.list(eq("crm_account_common"), any(DynamicQueryRequest.class)))
                .thenAnswer(invocation -> {
                    DynamicQueryRequest request = invocation.getArgument(1);
                    String matchField = request.getConditions().get(0).getFieldName();
                    String pid = "crm_acc_code".equals(matchField) ? "01A" : "01B";
                    return PaginationResult.of(List.of(Map.of("pid", pid)), 1L, 1, 2);
                });

        assertThatThrownBy(() -> resolver.resolve(field, "Shared"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ambiguous")
                .hasMessageContaining("crm_acc_code, crm_acc_name");
    }

    @Test
    void importHint_namesAcceptedBusinessFieldsAndPidCompatibility() {
        FieldDefinition field = accountReference(List.of("crm_acc_code", "crm_acc_name"));
        when(metaModelService.getModelFields("crm_account_common")).thenReturn(List.of(
                FieldDefinition.builder().code("crm_acc_code").displayName("客户编号").build(),
                FieldDefinition.builder().code("crm_acc_name").displayName("客户名称").build()));

        assertThat(resolver.importHint(field))
                .contains("客户编号 (crm_acc_code)")
                .contains("客户名称 (crm_acc_name)")
                .contains("PID")
                .contains("必须唯一");
    }

    private FieldDefinition accountReference(List<String> importMatchFields) {
        return FieldDefinition.builder()
                .code("crm_ct_account_id")
                .displayName("所属客户")
                .dataType("reference")
                .refTarget(FieldDefinition.RefTarget.builder()
                        .targetEntity("crm_account_common")
                        .valueField("pid")
                        .displayField("crm_acc_name")
                        .importMatchFields(importMatchFields)
                        .build())
                .build();
    }
}
