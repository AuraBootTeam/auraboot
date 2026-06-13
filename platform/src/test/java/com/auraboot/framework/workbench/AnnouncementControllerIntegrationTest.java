package com.auraboot.framework.workbench;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.integration.BaseIntegrationTest;
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
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AnnouncementControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AnnouncementMapper announcementMapper;

    private final List<String> createdTitles = new ArrayList<>();

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
    void cleanupCreatedAnnouncements() {
        if (createdTitles.isEmpty()) {
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

            announcementMapper.delete(new LambdaQueryWrapper<Announcement>()
                    .eq(Announcement::getTenantId, getTestTenant().getId())
                    .in(Announcement::getTitle, createdTitles));
        } finally {
            createdTitles.clear();
            MetaContext.clear();
        }
    }

    @Test
    @DisplayName("POST /api/announcements returns JS-safe string ids")
    void create_returnsJsSafeStringIds() throws Exception {
        String title = uniqueTitle("js-safe");
        createdTitles.add(title);

        MvcResult result = mockMvc.perform(post("/api/announcements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(announcementPayload(title))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.id").exists())
                .andExpect(jsonPath("$.data.publishedBy").exists())
                .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");

        assertTrue(data.path("id").isTextual(), "ASSIGN_ID Long ids must be serialized as strings");
        assertTrue(data.path("id").asText().matches("\\d+"), "Serialized id should keep the numeric value");
        assertTrue(data.path("publishedBy").isTextual(), "Long publishedBy must be serialized as a string");
        assertEquals(getTestUser().getId().toString(), data.path("publishedBy").asText());
    }

    @Test
    @DisplayName("POST then GET /api/announcements returns the active announcement")
    void createThenList_returnsCreatedActiveAnnouncement() throws Exception {
        String title = uniqueTitle("read-after-write");
        createdTitles.add(title);

        mockMvc.perform(post("/api/announcements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(announcementPayload(title))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.title").value(title));

        MvcResult listResult = mockMvc.perform(get("/api/announcements")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andReturn();

        JsonNode matched = null;
        for (JsonNode item : objectMapper.readTree(listResult.getResponse().getContentAsString()).path("data")) {
            if (title.equals(item.path("title").asText())) {
                matched = item;
                break;
            }
        }

        assertNotNull(matched, "active announcement list should include the record created by the preceding POST");
        assertEquals("urgent", matched.path("priority").asText());
        assertTrue(matched.path("pinned").asBoolean());
        assertEquals("Runtime body " + title, matched.path("content").asText());
    }

    private String uniqueTitle(String prefix) {
        return "Runtime Announcement " + prefix + " " + System.nanoTime();
    }

    private Map<String, Object> announcementPayload(String title) {
        return Map.of(
                "title", title,
                "content", "Runtime body " + title,
                "priority", "urgent",
                "status", "active",
                "pinned", true
        );
    }
}
