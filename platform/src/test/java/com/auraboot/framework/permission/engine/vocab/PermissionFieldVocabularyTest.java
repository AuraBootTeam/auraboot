package com.auraboot.framework.permission.engine.vocab;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.rbac.service.RoleService;
import com.auraboot.framework.rbac.service.UserRoleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PermissionFieldVocabularyTest {

    @Mock
    private UserRoleService userRoleService;

    @Mock
    private RoleService roleService;

    private PermissionFieldVocabulary vocabulary;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(21L, 99L, "user-99", "tester");
        vocabulary = new PermissionFieldVocabulary(userRoleService, roleService);
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void buildScopesPromotesMetaVirtualSourcesOutsideRecordData() {
        when(userRoleService.getRoleIdsByMemberIdAndTenantId(1001L, 21L)).thenReturn(List.of());
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("pid", "REQ-1");
        record.put("wd_req_days", 5);
        record.put("meta", Map.of(
                "virtualSources", List.of(Map.of(
                        "sourceRef", "wd_leave_request.days",
                        "field", "wd_req_days"))));

        Map<Scope, Map<String, Object>> scopes = vocabulary.buildScopes(1001L, record);

        assertThat(scopes).containsKeys(Scope.RECORD, Scope.ACTOR, Scope.META);
        assertThat(scopes.get(Scope.RECORD).get("data"))
                .isInstanceOf(Map.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("pid", "REQ-1")
                .containsEntry("wd_req_days", 5)
                .doesNotContainKeys("meta", "_meta", "ruleMeta");
        assertThat(scopes.get(Scope.META))
                .containsKey("virtualSources");
        assertThat(vocabulary.buildContext(1001L, record)
                .resolve(Scope.META, "virtualSources")
                .present()).isTrue();
        assertThat(vocabulary.buildContext(1001L, record)
                .resolve(Scope.RECORD, "data.meta")
                .present()).isFalse();
    }

    @Test
    void buildScopesAcceptsUnderscoreMetaAlias() {
        when(userRoleService.getRoleIdsByMemberIdAndTenantId(1002L, 21L)).thenReturn(List.of());
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("amount", 1200);
        record.put("_meta", Map.of("sourceStrategy", "MODEL_FIELD_SELECTOR"));

        Map<Scope, Map<String, Object>> scopes = vocabulary.buildScopes(1002L, record);

        assertThat(scopes.get(Scope.META)).containsEntry("sourceStrategy", "MODEL_FIELD_SELECTOR");
        assertThat(vocabulary.buildContext(1002L, record).resolve(Scope.RECORD, "data._meta")
                .present()).isFalse();
    }

    @Test
    void buildScopesCarriesModelCodeForLowCodePermissionResource() {
        when(userRoleService.getRoleIdsByMemberIdAndTenantId(1003L, 21L)).thenReturn(List.of());
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("pid", "REQ-2");
        record.put("wd_req_applicant", "01USERPID");
        record.put("modelCode", "legacy_wrong_model");

        Map<Scope, Map<String, Object>> scopes = vocabulary.buildScopes(
                1003L,
                "wd_leave_request",
                record);

        assertThat(scopes.get(Scope.RECORD))
                .containsEntry("modelCode", "legacy_wrong_model")
                .containsEntry("entityCode", "legacy_wrong_model");
        assertThat(scopes.get(Scope.RECORD).get("data"))
                .isInstanceOf(Map.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("pid", "REQ-2")
                .containsEntry("wd_req_applicant", "01USERPID")
                .doesNotContainKeys("modelCode", "entityCode");
    }

    @Test
    void buildScopesUsesResourceAsModelCodeWhenRecordHasNoExplicitModelCode() {
        when(userRoleService.getRoleIdsByMemberIdAndTenantId(1004L, 21L)).thenReturn(List.of());

        Map<Scope, Map<String, Object>> scopes = vocabulary.buildScopes(
                1004L,
                "wd_leave_request",
                Map.of("wd_req_applicant", "01USERPID"));

        assertThat(scopes.get(Scope.RECORD))
                .containsEntry("modelCode", "wd_leave_request")
                .containsEntry("entityCode", "wd_leave_request");
        assertThat(vocabulary.buildContext(1004L, "wd_leave_request", Map.of("wd_req_applicant", "01USERPID"))
                .resolve(Scope.RECORD, "modelCode")
                .value()).isEqualTo("wd_leave_request");
    }
}
