package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.entity.BindingRule;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.permission.engine.model.FieldPermissionSet;
import com.auraboot.framework.permission.service.FieldPermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommandFieldMapExecutorFieldPermissionTest {

    private static final String MODEL = "crm.quote";
    private static final Long TENANT_ID = 100L;
    private static final Long MEMBER_ID = 200L;

    @Mock
    private DynamicDataMapper dynamicDataMapper;

    @Mock
    private MetaModelService metaModelService;

    @Mock
    private FieldPermissionService fieldPermissionService;

    @InjectMocks
    private CommandFieldMapExecutor executor;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(TENANT_ID, 10L, "user-pid", "tester");
        MetaContext.setMemberId(MEMBER_ID);
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void explicitFieldMapRejectsPayloadFieldWhenMemberCannotEditTargetField() {
        when(metaModelService.getModelDefinition(MODEL)).thenReturn(Optional.of(modelDefinition()));
        when(fieldPermissionService.getFieldPermissions(MEMBER_ID, MODEL))
                .thenReturn(new FieldPermissionSet(
                        Set.of("name", "gross_margin"),
                        Set.of("name"),
                        Set.of()));

        BindingRule nameRule = bindingRule("name", "name");
        BindingRule marginRule = bindingRule("margin", "gross_margin");
        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setOperationType("create");

        assertThatThrownBy(() -> executor.executeFieldMapPhase(
                List.of(nameRule, marginRule),
                Map.of("name", "Quote A", "margin", "9.5"),
                TENANT_ID,
                request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getResponseCode()).isEqualTo(ResponseCode.FORBIDDEN);
                    assertThat(be.getMessage()).contains("gross_margin").contains(MODEL);
                });

        verify(dynamicDataMapper, never()).insert(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyMap());
        verify(dynamicDataMapper, never()).update(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void implicitFieldMapRejectsPayloadFieldWhenMemberCannotEditInputField() {
        when(metaModelService.getModelDefinition(MODEL)).thenReturn(Optional.of(modelDefinition()));
        when(fieldPermissionService.getFieldPermissions(MEMBER_ID, MODEL))
                .thenReturn(new FieldPermissionSet(
                        Set.of("name", "gross_margin"),
                        Set.of("name"),
                        Set.of()));

        CommandDefinition command = new CommandDefinition();
        command.setCode("crm.quote:create");
        command.setModelCode(MODEL);

        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setOperationType("create");

        assertThatThrownBy(() -> executor.executeImplicitFieldMapPhase(
                Map.of("type", "create", "inputFields", List.of("name", "gross_margin")),
                Map.of("name", "Quote A", "gross_margin", "9.5"),
                TENANT_ID,
                request,
                command))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getResponseCode()).isEqualTo(ResponseCode.FORBIDDEN);
                    assertThat(be.getMessage()).contains("gross_margin").contains(MODEL);
                });

        verify(dynamicDataMapper, never()).insert(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyMap());
        verify(dynamicDataMapper, never()).update(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.anyMap());
    }

    private BindingRule bindingRule(String sourceField, String targetField) {
        BindingRule rule = new BindingRule();
        rule.setRuleType("FIELD_MAP");
        rule.setTargetModel(MODEL);
        rule.setSourceField(sourceField);
        rule.setTargetField(targetField);
        return rule;
    }

    private ModelDefinition modelDefinition() {
        return ModelDefinition.builder()
                .code(MODEL)
                .tableName("crm_quote")
                .fields(List.of(
                        field("name", "name"),
                        field("gross_margin", "gross_margin")))
                .build();
    }

    private FieldDefinition field(String code, String columnName) {
        FieldDefinition field = new FieldDefinition();
        field.setCode(code);
        field.setColumnName(columnName);
        field.setDataType("STRING");
        return field;
    }
}
