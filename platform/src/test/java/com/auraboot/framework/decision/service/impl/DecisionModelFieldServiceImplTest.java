package com.auraboot.framework.decision.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.decision.dto.DecisionFactCatalogDTO;
import com.auraboot.framework.decision.dto.DecisionFactDTO;
import com.auraboot.framework.decision.dto.DecisionModelFieldDTO;
import com.auraboot.framework.decision.mapper.DrtVersionMapper;
import com.auraboot.framework.meta.dto.MetaFieldDTO;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.mapper.DictItemMapper;
import com.auraboot.framework.meta.mapper.DictMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.ModelFieldBindingService;
import com.auraboot.framework.permission.engine.model.FieldPermissionSet;
import com.auraboot.framework.permission.service.FieldPermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DecisionModelFieldServiceImplTest {

    @Mock
    private DrtVersionMapper versionMapper;

    @Mock
    private MetaModelMapper metaModelMapper;

    @Mock
    private ModelFieldBindingService modelFieldBindingService;

    @Mock
    private DictMapper dictMapper;

    @Mock
    private DictItemMapper dictItemMapper;

    @Mock
    private FieldPermissionService fieldPermissionService;

    private DecisionModelFieldServiceImpl service;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(7L, 9L, "user-pid", "tester");
        service = new DecisionModelFieldServiceImpl(
                versionMapper,
                metaModelMapper,
                modelFieldBindingService,
                dictMapper,
                dictItemMapper,
                fieldPermissionService);
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void listFieldsPreservesMetaModelOwnershipForFieldCatalogFiltering() {
        Model leaveRequest = model("m-leave", "wd_leave_request", "请假申请");
        Model agentMemory = model("m-agent", "agent_memory", "Agent 记忆");

        when(metaModelMapper.findCurrentByTenant()).thenReturn(List.of(leaveRequest, agentMemory));
        when(modelFieldBindingService.getModelFields("m-leave"))
                .thenReturn(List.of(field("wd_req_days", "integer", "请假天数")));
        when(modelFieldBindingService.getModelFields("m-agent"))
                .thenReturn(List.of(field("access_count", "integer", "访问次数")));
        when(versionMapper.findWithFieldRefs(7L)).thenReturn(List.of());

        List<DecisionModelFieldDTO> fields = service.listFields();

        assertThat(fields)
                .filteredOn(field -> "data.wd_req_days".equals(field.getPath()))
                .singleElement()
                .satisfies(field -> {
                    assertThat(field.getModelCode()).isEqualTo("wd_leave_request");
                    assertThat(field.getModelName()).isEqualTo("请假申请");
                    assertThat(field.getLabel()).isEqualTo("请假申请 / 请假天数");
                });
        assertThat(fields)
                .filteredOn(field -> "data.access_count".equals(field.getPath()))
                .singleElement()
                .satisfies(field -> {
                    assertThat(field.getModelCode()).isEqualTo("agent_memory");
                    assertThat(field.getModelName()).isEqualTo("Agent 记忆");
                    assertThat(field.getLabel()).isEqualTo("Agent 记忆 / 访问次数");
                });
    }

    @Test
    void getFactCatalogCarriesMaskingAndPermissionMetadataForRulePickers() {
        Model leaveRequest = model("m-leave", "wd_leave_request", "请假申请");

        when(metaModelMapper.findCurrentByTenant()).thenReturn(List.of(leaveRequest));
        when(modelFieldBindingService.getModelFields("m-leave"))
                .thenReturn(List.of(
                        field("wd_req_days", "decimal", "请假天数"),
                        field("wd_req_salary", "decimal", "敏感工资", true,
                                Map.of("masked", true, "permissionCode", "wd.leave.salary.read")),
                        field("wd_req_reason", "text", "请假原因", false, Map.of())));

        DecisionFactCatalogDTO catalog = service.getFactCatalog("wd_leave_request");

        DecisionFactDTO salary = catalog.getEntities().stream()
                .filter(entity -> "wd_leave_request".equals(entity.getModelCode()))
                .flatMap(entity -> entity.getFacts().stream())
                .filter(fact -> "data.wd_req_salary".equals(fact.getPath()))
                .findFirst()
                .orElseThrow();
        assertThat(salary.getMasked()).isTrue();
        assertThat(salary.getPermission()).isEqualTo("wd.leave.salary.read");
        assertThat(catalog.getEntities().stream()
                .flatMap(entity -> entity.getFacts().stream())
                .map(DecisionFactDTO::getPath))
                .contains("data.wd_req_days", "data.wd_req_salary")
                .doesNotContain("data.wd_req_reason");
    }

    @Test
    void getFactCatalogAppliesCurrentMemberFieldPermissionsForLowPrivilegePickers() {
        MetaContext.setMemberId(42L);
        Model leaveRequest = model("m-leave", "wd_leave_request", "请假申请");

        when(metaModelMapper.findCurrentByTenant()).thenReturn(List.of(leaveRequest));
        when(modelFieldBindingService.getModelFields("m-leave"))
                .thenReturn(List.of(
                        field("wd_req_days", "decimal", "请假天数"),
                        field("wd_req_note", "text", "备注"),
                        field("wd_req_salary", "decimal", "敏感工资")));
        when(fieldPermissionService.getFieldPermissions(42L, "wd_leave_request"))
                .thenReturn(new FieldPermissionSet(
                        Set.of("wd_req_days", "wd_req_note"),
                        Set.of("wd_req_days"),
                        Set.of("wd_req_salary")));

        DecisionFactCatalogDTO catalog = service.getFactCatalog("wd_leave_request");

        List<DecisionFactDTO> facts = catalog.getEntities().stream()
                .filter(entity -> "wd_leave_request".equals(entity.getModelCode()))
                .flatMap(entity -> entity.getFacts().stream())
                .toList();
        assertThat(facts)
                .extracting(DecisionFactDTO::getPath)
                .containsExactly("data.wd_req_days", "data.wd_req_note")
                .doesNotContain("data.wd_req_salary");
        assertThat(facts)
                .filteredOn(fact -> "data.wd_req_days".equals(fact.getPath()))
                .singleElement()
                .extracting(DecisionFactDTO::getEditable)
                .isEqualTo(Boolean.TRUE);
        assertThat(facts)
                .filteredOn(fact -> "data.wd_req_note".equals(fact.getPath()))
                .singleElement()
                .extracting(DecisionFactDTO::getEditable)
                .isEqualTo(Boolean.FALSE);
    }

    private Model model(String pid, String code, String displayName) {
        Model model = new Model();
        model.setPid(pid);
        model.setCode(code);
        model.setStatus("published");
        ExtensionBean extension = new ExtensionBean();
        extension.setExtension(Map.of("displayName", displayName));
        model.setExtension(extension);
        return model;
    }

    private MetaFieldDTO field(String code, String dataType, String displayName) {
        return field(code, dataType, displayName, true, Map.of());
    }

    private MetaFieldDTO field(
            String code,
            String dataType,
            String displayName,
            boolean visible,
            Map<String, Object> feature) {
        return MetaFieldDTO.builder()
                .code(code)
                .dataType(dataType)
                .visible(visible)
                .feature(feature)
                .extension(Map.of("displayName", displayName))
                .build();
    }
}
