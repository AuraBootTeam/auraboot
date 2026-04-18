package com.auraboot.framework.meta;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelCapabilities;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.dto.NamedQueryCreateRequest;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.NamedQueryService;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.rbac.entity.RolePermission;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P1 end-to-end smoke test — validates the full virtual-model path:
 * model registration via {@link MetaModelService#saveDefinition} → capabilities
 * API returning normalized whitelist → list dispatch through ExecutorRegistry
 * into {@code NamedQueryModelExecutor} → write operation rejected by the
 * virtual-model guard (T10).
 *
 * <p>Single coherent scenario that composes tasks T1–T11 of the P1 virtual
 * model backend plan. No separate unit assertions — the whole path is the test.
 */
@DisplayName("P1 Virtual Model - End-to-End Smoke Test (T12)")
class P1VirtualModelSmokeTest extends BaseIntegrationTest {

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private MetaModelService metaModelService;
    @Autowired private NamedQueryService namedQueryService;
    @Autowired private PermissionMapper permissionMapper;
    @Autowired private RolePermissionMapper rolePermissionMapper;
    @Autowired private UserPermissionService userPermissionService;
    @Autowired private PlatformTransactionManager txManager;

    private MockMvc mvc;
    private String modelCode;

    @BeforeEach
    void setupMvc() {
        // Unique model code per run — grants below are keyed off this code.
        modelCode = "p1_t12_smoke_" + System.currentTimeMillis();

        // ModelCapabilitiesController requires system.model.read.
        // DynamicController enforces @RequirePermission("model.{pageKey}.read") and ".create"
        // — we grant both so the security layer lets the request reach the service layer
        // where the virtual-model guard (T10) runs for the write assertion.
        // Permission rows must commit so the PermissionInterceptor (which runs on a
        // fresh request thread / possibly different connection) can see them.
        // BaseIntegrationTest uses @Transactional(rollback=true), so we escape via
        // TransactionTemplate with NOT_SUPPORTED to auto-commit the grants.
        TransactionTemplate tt = new TransactionTemplate(txManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        tt.executeWithoutResult(status -> {
            grantPermissionToTestRole("system.model.read",
                    "system", "model", "read", "System Model Read");
            grantPermissionToTestRole("model." + modelCode + ".read",
                    "model", modelCode, "read", "Model " + modelCode + " Read");
            grantPermissionToTestRole("model." + modelCode + ".create",
                    "model", modelCode, "create", "Model " + modelCode + " Create");
        });
        userPermissionService.evictUserPermissions(getTestUser().getId());

        Filter contextFilter = (request, response, chain) -> {
            try {
                MetaContext.setContext(
                        getTestTenant().getId(),
                        getTestUser().getId(),
                        getTestUser().getPid(),
                        getTestUser().getUserName()
                );
                MetaContext.setMemberId(getTestTenantMember().getId());
                CustomUserDetails userDetails = new CustomUserDetails(
                        getTestUser().getUserName(),
                        "test-password",
                        getTestUser().getId(),
                        getTestUser().getPid(),
                        AuthorityUtils.createAuthorityList("role_admin"),
                        true, true, true, true
                );
                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                SecurityContextHolder.getContext().setAuthentication(auth);
                chain.doFilter(request, response);
            } finally {
                MetaContext.clear();
                SecurityContextHolder.clearContext();
            }
        };

        mvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .addFilter(contextFilter, "/*")
                .build();
    }

    @Test
    @DisplayName("namedQuery virtual model: capabilities → list → write-guard end-to-end")
    void end_to_end_virtual_namedQuery_model() throws Exception {
        // 1) NamedQuery fixture — reads from ab_user (seeded by BaseIntegrationTest).
        String queryCode = "p1t12_nq_" + System.currentTimeMillis();
        NamedQueryCreateRequest nqReq = new NamedQueryCreateRequest();
        nqReq.setCode(queryCode);
        nqReq.setTitle("P1 T12 smoke namedQuery");
        nqReq.setFromSql("SELECT pid, user_name, email FROM ab_user");
        nqReq.setStatus("published");
        namedQueryService.create(nqReq);

        // 2) Virtual model bound to the namedQuery, read-only capabilities.
        //    detailKeyField set explicitly (workaround for primaryKey not round-tripped).
        ModelCapabilities caps = ModelCapabilities.virtualReadOnly().toBuilder()
                .detailKeyField("pid")
                .build();
        metaModelService.saveDefinition(ModelDefinition.builder()
                .code(modelCode)
                .displayName("P1 T12 Smoke Virtual Model")
                .modelType("virtual")
                .sourceType("namedQuery")
                .sourceRef(queryCode)
                .primaryKey("pid")
                .fields(List.of(
                        FieldDefinition.builder().code("pid").dataType("bigint")
                                .sortable(true).build(),
                        FieldDefinition.builder().code("user_name").dataType("string")
                                .sortable(true).filterable(true).build(),
                        FieldDefinition.builder().code("email").dataType("string")
                                .filterable(true).build()
                ))
                .capabilities(caps)
                .status("published")
                .build());

        // 3) Capabilities API — normalized virtual read-only whitelist.
        mvc.perform(get("/api/meta/models/{code}/capabilities", modelCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.list").value(true))
                .andExpect(jsonPath("$.data.detail").value(true))
                .andExpect(jsonPath("$.data.create").value(false))
                .andExpect(jsonPath("$.data.update").value(false))
                .andExpect(jsonPath("$.data.delete").value(false))
                .andExpect(jsonPath("$.data.sortableFields", hasItem("user_name")))
                .andExpect(jsonPath("$.data.filterableFields", hasItem("user_name")))
                .andExpect(jsonPath("$.data.filterableFields", hasItem("email")));

        // 4) List API — dispatches ExecutorRegistry → NamedQueryModelExecutor →
        //    NamedQueryService execution. ab_user is seeded so records is non-empty.
        mvc.perform(get("/api/dynamic/{code}/list", modelCode)
                        .param("pageNum", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.records").isArray());

        // 5) Write rejected — T10 guard throws, surfaces as HTTP 4xx.
        mvc.perform(post("/api/dynamic/{code}/create", modelCode)
                        .contentType("application/json")
                        .content("{\"user_name\":\"nope\"}"))
                .andExpect(status().is4xxClientError());

        // 6) Audit trail — deferred. The list call above invokes
        //    NamedQueryService which has its own audit logging; asserting on
        //    async audit entries would add cross-thread timing complexity
        //    without materially strengthening this path test. Tracked as a
        //    P1 followup alongside T13.
    }

    private void grantPermissionToTestRole(String code, String resourceType,
                                           String resourceCode, String action, String name) {
        Permission permission = permissionMapper.findByCode(code);
        if (permission == null) {
            permission = new Permission();
            permission.setPid(UniqueIdGenerator.generate());
            permission.setCode(code);
            permission.setName(name);
            permission.setResourceType(resourceType);
            permission.setResourceCode(resourceCode);
            permission.setAction(action);
            permission.setSource("manual");
            permission.setStatus("active");
            permission.setDeletedFlag(false);
            permission.setTenantId(getTestTenant().getId());
            permission.setCreatedAt(Instant.now());
            permission.setUpdatedAt(Instant.now());
            permissionMapper.insert(permission);
        }

        boolean notAssigned = rolePermissionMapper.selectList(
                new LambdaQueryWrapper<RolePermission>()
                        .eq(RolePermission::getRoleId, getTestRole().getId())
                        .eq(RolePermission::getPermissionId, permission.getId())
                        .eq(RolePermission::getDeletedFlag, false)
        ).isEmpty();

        if (notAssigned) {
            RolePermission rp = new RolePermission();
            rp.setPid(UniqueIdGenerator.generate());
            rp.setRoleId(getTestRole().getId());
            rp.setPermissionId(permission.getId());
            rp.setGrantType("grant");
            rp.setStatus("active");
            rp.setDeletedFlag(false);
            rp.setTenantId(getTestTenant().getId());
            rp.setCreatedAt(Instant.now());
            rp.setUpdatedAt(Instant.now());
            rolePermissionMapper.insert(rp);
        }
    }
}
