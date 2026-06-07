package com.auraboot.framework.decision;

import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.rbac.entity.RolePermission;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP golden for the Decision Runtime controller: drives the full lifecycle over MockMvc
 * (validate → create definition → create draft → validate version → publish → evaluate),
 * verifying routing, JSON request/response binding, and the {@code @RequirePermission} guard
 * (perms granted to the test role). Complements the service-layer real-stack IT.
 */
class DecisionRuntimeControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private PermissionMapper permissionMapper;
    @Autowired private RolePermissionMapper rolePermissionMapper;
    @Autowired private UserPermissionService userPermissionService;

    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mockMvc;

    private static final String AST = """
        { "type": "compare",
          "left": { "type": "path", "scope": "record", "path": "data.amount", "dataType": "decimal" },
          "operator": "GT",
          "right": { "type": "literal", "value": 10000, "dataType": "decimal" } }
        """;

    @BeforeEach
    void setupAuthAndMockMvc() {
        grant("decision.definition.read", "decision", "definition", "read", "Decision Definition Read");
        grant("decision.definition.manage", "decision", "definition", "manage", "Decision Definition Manage");
        grant("decision.definition.publish", "decision", "definition", "publish", "Decision Definition Publish");
        grant("decision.runtime.evaluate", "decision", "runtime", "evaluate", "Decision Runtime Evaluate");
        userPermissionService.evictUserPermissions(getTestUser().getId());

        Filter contextFilter = (request, response, chain) -> {
            try {
                applyTestMetaContext();
                CustomUserDetails ud = new CustomUserDetails(
                        getTestUser().getUserName(), "test-password",
                        getTestUser().getId(), getTestUser().getPid(),
                        AuthorityUtils.createAuthorityList("role_admin"), true, true, true, true);
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(ud, null, ud.getAuthorities()));
                chain.doFilter(request, response);
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilter(contextFilter, "/*").build();
    }

    @Test
    void httpLifecycle_validate_create_publish_evaluate() throws Exception {
        String code = "it_http_" + System.nanoTime();

        // 1. validate a draft AST (no persistence) → valid
        mockMvc.perform(post("/api/decision/validate").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "kind", "SIMPLE_CONDITION",
                                "runtimeAdapter", "AST_EVALUATOR",
                                "contentJson", json.readTree(AST)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(true))
                .andExpect(jsonPath("$.data.fieldRefs[0]").value("record.data.amount"));

        // 2. create definition
        mockMvc.perform(post("/api/decision/definitions").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "decisionCode", code, "decisionName", "HTTP IT",
                                "scopeType", "AUTOMATION", "ownerModule", "decision"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decisionCode").value(code));

        // 3. create draft version → capture pid
        String draftBody = mockMvc.perform(
                        post("/api/decision/definitions/" + code + "/versions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of(
                                        "kind", "SIMPLE_CONDITION",
                                        "runtimeAdapter", "AST_EVALUATOR",
                                        "contentJson", json.readTree(AST)))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String pid = json.readTree(draftBody).path("data").path("pid").asText();

        // 4. validate version → VALIDATED
        mockMvc.perform(post("/api/decision/versions/" + pid + "/validate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(true));

        // 5. publish version → PUBLISHED
        mockMvc.perform(post("/api/decision/versions/" + pid + "/publish"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.version").value(1));

        // 6. evaluate via HTTP → MATCHED for amount > 10000
        mockMvc.perform(post("/api/decision/evaluate").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "decisionCode", code,
                                "binding", "LATEST",
                                "callerType", "API",
                                "context", Map.of("record", Map.of("data", Map.of("amount", 20000)))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("MATCHED"))
                .andExpect(jsonPath("$.data.matched").value(true))
                .andExpect(jsonPath("$.data.traceId").isNotEmpty());

        // 7. evaluate not-matched
        mockMvc.perform(post("/api/decision/evaluate").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "decisionCode", code, "binding", "LATEST", "callerType", "API",
                                "context", Map.of("record", Map.of("data", Map.of("amount", 500)))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NOT_MATCHED"));
    }

    private void grant(String code, String resourceType, String resourceCode, String action, String name) {
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
