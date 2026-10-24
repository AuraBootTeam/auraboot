package com.auraboot.framework.workbench.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.permission.engine.model.DataScopeCondition;
import com.auraboot.framework.permission.service.*;
import com.auraboot.framework.workbench.mapper.AnnouncementAudienceMapper;
import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;
import java.util.*;

class AnnouncementAudienceServiceTest {
    private final AnnouncementAudienceMapper directory=mock(AnnouncementAudienceMapper.class);
    private final DataScopeService scopes=mock(DataScopeService.class);
    private final UserPermissionService permissions=mock(UserPermissionService.class);
    private final AnnouncementAudienceService service=new AnnouncementAudienceService(directory,scopes,permissions);
    @BeforeEach void setup() {
        MetaContext.setContext(1L,7L,"author","Author"); MetaContext.setMemberId(70L);
        when(permissions.hasPermission(7L,"announcement.announcement.publish")).thenReturn(true);
        when(directory.members(1L)).thenReturn(List.of(member("author","m1","dept"),member("employee","m2","child"),member("no-employee","m3",null),member("outside","m4","other")));
        when(directory.departments(1L)).thenReturn(List.of(Map.of("pid","dept"),Map.of("pid","child","parent_pid","dept"),Map.of("pid","other")));
        when(directory.roles(1L)).thenReturn(List.of(Map.of("role_pid","operators","user_pid","employee"),Map.of("role_pid","operators","user_pid","outside")));
        when(scopes.resolveAudienceScope(70L,"ab_announcement","publish"))
            .thenReturn(new DataScopeCondition("dept_and_sub","created_by",7L,null,List.of("dept","child"),List.of()));
    }
    @AfterEach void clear(){MetaContext.clear();}
    private Map<String,Object> member(String user,String member,String dept) {
        Map<String,Object> value=new HashMap<>(Map.of("user_pid",user,"member_pid",member,"display_name",user));
        value.put("department_pid",dept);return value;
    }
    @Test void allIncludesMembersWithoutEmployeeAndDeduplicatesMatches() {
        when(permissions.hasPermission(7L,"announcement.announcement.publish_all")).thenReturn(true);
        var result=service.resolve(new AnnouncementAudienceService.Rule("all",null,null,null));
        assertThat(result.recipients()).extracting(AnnouncementAudienceService.Recipient::userPid)
            .containsExactly("author","employee","no-employee","outside");
    }
    @Test void departmentExpandsChildrenAndRetainsMatchedRules() {
        var result=service.resolve(new AnnouncementAudienceService.Rule("selected",List.of("employee"),List.of("dept"),List.of("operators")));
        assertThat(result.recipients()).hasSize(2);
        assertThat(result.recipients().get(1).matchedRuleRefs()).containsExactly("department:child","person:employee","role:operators");
    }
    @Test void rolesAreExplicitlyLimitedToManagementScope() {
        var result=service.resolve(new AnnouncementAudienceService.Rule("selected",null,null,List.of("operators")));
        assertThat(result.roleLimitedToManagementScope()).isTrue();
        assertThat(result.recipients()).extracting(AnnouncementAudienceService.Recipient::userPid).containsExactly("employee");
    }
    @Test void partialDepartmentAndForgedAllNeverSilentlyClip() {
        when(scopes.resolveAudienceScope(any(),any(),any())).thenReturn(new DataScopeCondition("dept","created_by",7L,null,List.of("dept"),List.of()));
        assertThatThrownBy(()->service.resolve(new AnnouncementAudienceService.Rule("selected",null,List.of("dept"),null))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->service.resolve(new AnnouncementAudienceService.Rule("all",null,null,null))).isInstanceOf(AccessDeniedException.class);
    }
    @Test void outsidePersonAndMissingConfigurationFailClosed() {
        assertThatThrownBy(()->service.resolve(new AnnouncementAudienceService.Rule("selected",List.of("outside"),null,null))).isInstanceOf(AccessDeniedException.class);
        when(scopes.resolveAudienceScope(any(),any(),any())).thenReturn(DataScopeCondition.notConfigured());
        assertThatThrownBy(()->service.resolve(new AnnouncementAudienceService.Rule("selected",List.of("author"),null,null))).isInstanceOf(AccessDeniedException.class);
    }
    @Test void invalidActorCannotPreviewAnotherTenantsDirectory() {
        MetaContext.setContext(2L,7L,"author","Author");
        assertThatThrownBy(()->service.resolve(new AnnouncementAudienceService.Rule("selected",List.of("author"),null,null))).isInstanceOf(AccessDeniedException.class);
        verify(directory,never()).departments(2L);
    }
}
