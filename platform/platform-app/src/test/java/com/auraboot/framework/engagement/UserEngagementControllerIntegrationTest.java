package com.auraboot.framework.engagement;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.engagement.entity.UserEngagement;
import com.auraboot.framework.engagement.mapper.UserEngagementMapper;
import com.auraboot.framework.integration.BaseIntegrationTest;
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
@DisplayName("UserEngagementController - Integration Tests")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserEngagementControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserEngagementMapper engagementMapper;

    private final List<String> createdTargetIds = new ArrayList<>();

    private MockMvc mockMvc;

    @BeforeEach
    void setupMockMvc() {
        Filter metaContextFilter = (request, response, chain) -> {
            try {
                MetaContext.setContext(
                        getTestTenant().getId(),
                        getTestUser().getId(),
                        getTestUser().getPid(),
                        getTestUser().getUserName()
                );
                MetaContext.setMemberId(getTestTenantMember().getId());
                chain.doFilter(request, response);
            } finally {
                MetaContext.clear();
            }
        };

        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .addFilter(metaContextFilter, "/*")
                .build();
    }

    @AfterEach
    void cleanupCreatedEngagements() {
        if (createdTargetIds.isEmpty()) {
            return;
        }

        try {
            MetaContext.setContext(
                    getTestTenant().getId(),
                    getTestUser().getId(),
                    getTestUser().getPid(),
                    getTestUser().getUserName()
            );
            MetaContext.setMemberId(getTestTenantMember().getId());

            engagementMapper.delete(new LambdaQueryWrapper<UserEngagement>()
                    .eq(UserEngagement::getUserId, getTestUser().getId())
                    .eq(UserEngagement::getTenantId, getTestTenant().getId())
                    .in(UserEngagement::getTargetId, createdTargetIds));
        } finally {
            createdTargetIds.clear();
            MetaContext.clear();
        }
    }

    @Test
    @DisplayName("POST /api/user-engagement returns JS-safe string id")
    void upsert_returnsJsSafeStringId() throws Exception {
        String targetId = uniqueTargetId("js-safe");
        createdTargetIds.add(targetId);

        MvcResult result = mockMvc.perform(post("/api/user-engagement")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(recentPayload(targetId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.id").exists())
                .andReturn();

        JsonNode idNode = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data")
                .path("id");

        assertTrue(idNode.isTextual(), "ASSIGN_ID Long ids must be serialized as strings for JavaScript clients");
        assertTrue(idNode.asText().matches("\\d+"), "Serialized id should keep the numeric identifier value");
    }

    @Test
    @DisplayName("POST then GET /api/user-engagement returns the created recent page")
    void upsertThenList_returnsCreatedRecentPage() throws Exception {
        String targetId = uniqueTargetId("read-after-write");
        createdTargetIds.add(targetId);

        mockMvc.perform(post("/api/user-engagement")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(recentPayload(targetId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.targetId").value(targetId));

        MvcResult listResult = mockMvc.perform(get("/api/user-engagement")
                        .param("engagementType", "recent_view")
                        .param("targetType", "page"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andReturn();

        JsonNode matched = null;
        for (JsonNode item : objectMapper.readTree(listResult.getResponse().getContentAsString()).path("data")) {
            if (targetId.equals(item.path("targetId").asText())) {
                matched = item;
                break;
            }
        }

        assertNotNull(matched, "recent_view list should include the record created by the preceding POST");
        assertEquals("Recent " + targetId, matched.path("targetLabel").asText());
        assertEquals("/dashboards/view/" + targetId, matched.path("targetContext").path("path").asText());
    }

    private String uniqueTargetId(String prefix) {
        return "engagement-" + prefix + "-" + System.nanoTime();
    }

    private Map<String, Object> recentPayload(String targetId) {
        return Map.of(
                "targetType", "page",
                "targetId", targetId,
                "targetLabel", "Recent " + targetId,
                "engagementType", "recent_view",
                "targetContext", Map.of(
                        "path", "/dashboards/view/" + targetId,
                        "icon", "layout-dashboard",
                        "modelCode", "dashboard"
                )
        );
    }
}
