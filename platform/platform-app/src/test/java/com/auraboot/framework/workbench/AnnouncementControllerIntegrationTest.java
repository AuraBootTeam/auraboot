package com.auraboot.framework.workbench;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.rbac.entity.RolePermission;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.auraboot.framework.workbench.entity.Announcement;
import com.auraboot.framework.workbench.mapper.AnnouncementMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@DisplayName("AnnouncementController - Integration Tests")
@Transactional
class AnnouncementControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AnnouncementMapper announcementMapper;

    @Autowired
    private PermissionMapper permissionMapper;

    @Autowired
    private RolePermissionMapper rolePermissionMapper;

    @Autowired
    private UserPermissionService userPermissionService;

    private MockMvc mockMvc;

    @BeforeEach
    void setupMockMvc() {
        grantAnnouncementManageToTestRole();
        Filter metaContextFilter = (request, response, chain) -> {
            try {
                MetaContext.setContext(
                        getTestTenant().getId(),
                        getTestUser().getId(),
                        getTestUser().getPid(),
                        getTestUser().getUserName()
                );
                MetaContext.setMemberId(getTestTenantMember().getId());
                CustomUserDetails userDetails = new CustomUserDetails(
                        getTestUser().getUserName(), "test-password",
                        getTestUser().getId(), getTestUser().getPid(),
                        AuthorityUtils.createAuthorityList("role_admin"),
                        true, true, true, true);
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));
                chain.doFilter(request, response);
            } finally {
                MetaContext.clear();
                SecurityContextHolder.clearContext();
            }
        };

        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .addFilter(metaContextFilter, "/*")
                .build();
    }

    private void grantAnnouncementManageToTestRole() {
        for(String action : List.of("edit","delete","withdraw")) {
            grantCommittedPermissionToTestRole("announcement.announcement."+action,
                "announcement","announcement:announcement",action,"Announcement "+action);
        }
    }


    @Autowired private com.auraboot.framework.workbench.service.AnnouncementLifecycleService lifecycle;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private void identity() {
        MetaContext.setContext(getTestTenant().getId(),getTestUser().getId(),
            getTestUser().getPid(),getTestUser().getUserName());
        MetaContext.setMemberId(getTestTenantMember().getId());
    }

    @Test
    void controlledDraftReplayConflictAndDeletePersistAtomically() throws Exception {
        identity();
        String operation=UniqueIdGenerator.generate();
        Map<String,Object> input=Map.of("title","Draft "+operation,"pinned",true);
        Map<String,Object> created=lifecycle.execute("announcement:create_announcement",null,input,operation,null);
        String pid=(String)created.get("pid");
        assertEquals(created,lifecycle.execute("announcement:create_announcement",null,input,operation,null));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM ab_announcement_audit WHERE tenant_id=? AND announcement_pid=?",
            Integer.class,getTestTenant().getId(),pid));
        org.junit.jupiter.api.Assertions.assertThrows(com.auraboot.framework.exception.ConflictException.class,
            ()->lifecycle.execute("announcement:create_announcement",null,Map.of("title","Changed"),operation,null));
        Map<String,Object> saved=lifecycle.execute("announcement:update_announcement",pid,
            Map.of("title","Edited"),UniqueIdGenerator.generate(),1);
        assertEquals(2,saved.get("row_version"));
        org.junit.jupiter.api.Assertions.assertThrows(com.auraboot.framework.exception.ConflictException.class,
            ()->lifecycle.execute("announcement:update_announcement",pid,Map.of("title","Stale"),UniqueIdGenerator.generate(),1));
        assertEquals("Edited",jdbc.queryForObject("SELECT title FROM ab_announcement WHERE tenant_id=? AND pid=?",
            String.class,getTestTenant().getId(),pid));
        org.junit.jupiter.api.Assertions.assertThrows(com.auraboot.framework.exception.BusinessException.class,
            ()->lifecycle.execute("announcement:update_announcement",pid,Map.of("status","published"),UniqueIdGenerator.generate(),2));
        org.junit.jupiter.api.Assertions.assertThrows(com.auraboot.framework.exception.BusinessException.class,
            ()->lifecycle.execute("announcement:publish",pid,Map.of(),UniqueIdGenerator.generate(),2));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ab_announcement_version WHERE tenant_id=? AND announcement_pid=?",
            Integer.class,getTestTenant().getId(),pid));
        String deletion=UniqueIdGenerator.generate();
        Map<String,Object> deleted=lifecycle.execute("announcement:delete_announcement",pid,Map.of(),deletion,2);
        assertEquals(deleted,lifecycle.execute("announcement:delete_announcement",pid,Map.of(),deletion,2));
        assertTrue(jdbc.queryForObject("SELECT deleted_flag FROM ab_announcement WHERE tenant_id=? AND pid=?",
            Boolean.class,getTestTenant().getId(),pid));
        assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM ab_announcement_audit WHERE tenant_id=? AND announcement_pid=?",
            Integer.class,getTestTenant().getId(),pid));
    }

    @Test
    void draftsNeverAppearInRecipientRestList() throws Exception {
        identity();
        String title="Private draft "+UniqueIdGenerator.generate();
        lifecycle.execute("announcement:create_announcement",null,Map.of("title",title),UniqueIdGenerator.generate(),null);
        MvcResult result=mockMvc.perform(get("/api/announcements").param("limit","100"))
            .andExpect(status().isOk()).andReturn();
        for(JsonNode record:objectMapper.readTree(result.getResponse().getContentAsString()).path("data")) {
            org.junit.jupiter.api.Assertions.assertNotEquals(title,record.path("title").asText());
        }
    }
}
