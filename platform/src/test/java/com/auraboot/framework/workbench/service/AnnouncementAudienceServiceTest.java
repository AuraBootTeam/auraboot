package com.auraboot.framework.workbench.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.BusinessException;
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
    private final AnnouncementAudienceService service=new AnnouncementAudienceService(directory,scopes,permissions,new AnnouncementCodec(new com.fasterxml.jackson.databind.ObjectMapper()));
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
        Map<String,Object> value=new HashMap<>(Map.of("user_pid",user,"member_pid",member,"display_name",user,"member_id",user.equals("author") ? 70L : 71L));
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
    @Test void freezesAllEmployeeDepartmentsTeamsAndOnlyMatchedRoleLabelsWithoutExposingPrivateJson() throws Exception {
        when(directory.members(1L)).thenReturn(List.of(member("author","m1","dept"),member("employee","m2","child"),
            member("employee","m2","other"),member("employee","m2","child")));
        when(directory.departments(1L)).thenReturn(List.of(Map.of("pid","dept","label","Sales"),
            Map.of("pid","child","parent_pid","dept","label","East"),Map.of("pid","other","label","Support")));
        when(directory.roles(1L)).thenReturn(List.of(Map.of("role_pid","operators","label","Operators","user_pid","employee"),
            Map.of("role_pid","private-role","label","Private role","user_pid","employee")));
        when(directory.teams(1L)).thenReturn(List.of(Map.of("team_pid","team-a","user_pid","employee"),Map.of("team_pid","team-a","user_pid","employee")));
        var result=service.resolve(new AnnouncementAudienceService.Rule("selected",null,null,List.of("operators")));
        var recipient=result.recipients().getFirst();
        var json=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var captured=json.readTree(recipient.organizationSnapshotJson());
        assertThat(captured.path("captured").asBoolean()).isTrue();
        assertThat(captured.path("departments").toString()).isEqualTo("[{\"label\":\"East\",\"pid\":\"child\"}]");
        assertThat(captured.path("departmentPids").toString()).isEqualTo("[\"child\",\"other\"]");
        assertThat(captured.path("teamPids").toString()).isEqualTo("[\"team-a\"]");
        assertThat(captured.path("matchedRoles").toString()).isEqualTo("[{\"label\":\"Operators\",\"pid\":\"operators\"}]");
        assertThat(json.writeValueAsString(result)).doesNotContain("organizationSnapshotJson","Private role","Support","team-a");
    }
    @Test void statisticsAuthorityDoesNotImplicitlyRequirePublicationPermission() {
        when(permissions.hasPermission(7L,"announcement.announcement.publish")).thenReturn(false);
        when(permissions.hasPermission(7L,"announcement.announcement.stats")).thenReturn(true);
        var scope=service.managementScope("stats");
        assertThat(scope.type()).isEqualTo("dept_and_sub");
        assertThat(scope.currentUserPids()).containsExactlyInAnyOrder("author","employee");
        assertThatThrownBy(()->service.managementScope("remind")).isInstanceOf(AccessDeniedException.class);
    }
    @Test void staleOrMissingMembershipCannotUseAnotherMembershipScope() {
        MetaContext.setMemberId(71L);
        assertThatThrownBy(()->service.resolve(new AnnouncementAudienceService.Rule("selected",List.of("employee"),null,null)))
            .isInstanceOf(AccessDeniedException.class);
        verify(scopes,never()).resolveAudienceScope(any(),any(),any());
    }
    @Test void aConfiguredAllScopeWithoutTenantWideGrantFailsClosed() {
        when(scopes.resolveAudienceScope(any(),any(),any())).thenReturn(DataScopeCondition.all());
        assertThatThrownBy(()->service.resolve(new AnnouncementAudienceService.Rule("selected",List.of("author"),null,null)))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test void theSharedCapacityBoundsUniqueRecipientsBeforeAnyPublicationCanBePrepared() {
        org.springframework.test.util.ReflectionTestUtils.setField(service,"maximumRecipients",2);
        var selected=new AnnouncementAudienceService.Rule("selected",List.of("author","employee"),List.of("dept"),List.of("operators"));
        assertThat(service.resolve(selected).recipients()).hasSize(2);
        when(permissions.hasPermission(7L,"announcement.announcement.publish_all")).thenReturn(true);
        assertThatThrownBy(()->service.resolve(new AnnouncementAudienceService.Rule("all",null,null,null)))
            .isInstanceOf(BusinessException.class).hasMessageContaining("audience_capacity_exceeded");
        org.springframework.test.util.ReflectionTestUtils.setField(service,"maximumRecipients",0);
        assertThatThrownBy(()->service.resolve(selected)).isInstanceOf(BusinessException.class);
    }

    @Test void pagedChoicesSearchAndHydrateOnlyTheCurrentlyAuthorizedDirectory() {
        var first=service.optionPage("person","",1,1,List.of());
        assertThat(first.total()).isEqualTo(2);
        assertThat(first.records()).extracting(AnnouncementAudienceService.Option::value).containsExactly("author");
        assertThat(service.optionPage("person","",2,1,List.of()).records())
            .extracting(AnnouncementAudienceService.Option::value).containsExactly("employee");
        assertThat(service.optionPage("person","EMP",1,25,List.of()).records())
            .extracting(AnnouncementAudienceService.Option::value).containsExactly("employee");
        assertThat(service.optionPage("person","",1,50,List.of("employee","outside")).records())
            .extracting(AnnouncementAudienceService.Option::value).containsExactly("employee");
        when(scopes.resolveAudienceScope(70L,"ab_announcement","publish"))
            .thenReturn(new DataScopeCondition("self","created_by",7L,null,List.of(),List.of()));
        assertThat(service.optionPage("person","",1,50,List.of("employee")).records()).isEmpty();
    }

    @Test void optionPagingRejectsUnboundedRequestsBeforeReadingTheDirectory() {
        clearInvocations(directory);
        assertThatThrownBy(()->service.optionPage("person","",0,25,List.of())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.optionPage("person","",1,51,List.of())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.optionPage("person","a".repeat(101),1,25,List.of())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.optionPage("person","",1,50,Collections.nCopies(51,"employee")))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(directory);
    }

}
