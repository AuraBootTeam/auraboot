package com.auraboot.framework.integration.test;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
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

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for TestSeedController.
 * Verifies seed, reset, and context endpoints for E2E test environment setup.
 * <p>
 * Requires both "integration-test" and "test" profiles since the controller
 * is annotated with @Profile("test").
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles({"integration-test", "test"})
@DisplayName("TestSeedController Integration Tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TestSeedControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    // Cross-test state captured from seed response
    private Long seededTenantId;
    private Long seededUserId;

    @BeforeEach
    void setup() {
        // No auth filter needed — /api/test/** is whitelisted in SecurityConfig
        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .build();
    }

    @Test
    @Order(1)
    @DisplayName("TS-01: POST /api/test/seed creates test tenant and user, returns valid SeedResult")
    void seed_createsTestTenantAndUser() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/test/seed")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").isNumber())
                .andExpect(jsonPath("$.userId").isNumber())
                .andExpect(jsonPath("$.jwt").isString())
                .andExpect(jsonPath("$.jwt", not(emptyString())))
                .andExpect(jsonPath("$.email").value("e2e@auraboot.test"))
                .andExpect(jsonPath("$.tenantName").value("e2e_test"))
                .andReturn();

        // Capture IDs for subsequent tests using Jackson
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        seededTenantId = body.get("tenantId").asLong();
        seededUserId = body.get("userId").asLong();

        log.info("TS-01: seed returned tenantId={}, userId={}", seededTenantId, seededUserId);
    }

    @Test
    @Order(2)
    @DisplayName("TS-02: POST /api/test/seed is idempotent — returns same tenantId and userId")
    void seed_idempotent_returnsSameUser() throws Exception {
        Assertions.assertNotNull(seededTenantId, "seededTenantId must be set by TS-01");
        Assertions.assertNotNull(seededUserId, "seededUserId must be set by TS-01");

        MvcResult result = mockMvc.perform(post("/api/test/seed")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("e2e@auraboot.test"))
                .andReturn();

        // Use Jackson's asLong() to avoid Long vs Integer type mismatch in jsonPath().value()
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        Assertions.assertEquals(seededTenantId, body.get("tenantId").asLong(),
                "Second seed must return same tenantId");
        Assertions.assertEquals(seededUserId, body.get("userId").asLong(),
                "Second seed must return same userId");

        log.info("TS-02: second seed returned same tenantId={}, userId={}", seededTenantId, seededUserId);
    }

    @Test
    @Order(3)
    @DisplayName("TS-03: GET /api/test/context returns seeded=true with matching IDs and a valid JWT")
    void context_returnsCurrentState() throws Exception {
        Assertions.assertNotNull(seededTenantId, "seededTenantId must be set by TS-01");

        MvcResult result = mockMvc.perform(get("/api/test/context"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seeded").value(true))
                .andExpect(jsonPath("$.email").value("e2e@auraboot.test"))
                .andExpect(jsonPath("$.jwt").isString())
                .andExpect(jsonPath("$.jwt", not(emptyString())))
                .andReturn();

        // Use Jackson's asLong() to avoid Long vs Integer type mismatch in jsonPath().value()
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        Assertions.assertEquals(seededTenantId, body.get("tenantId").asLong(),
                "context must return same tenantId as seed");
        Assertions.assertEquals(seededUserId, body.get("userId").asLong(),
                "context must return same userId as seed");

        log.info("TS-03: context returned seeded=true, tenantId={}", seededTenantId);
    }

    @Test
    @Order(4)
    @DisplayName("TS-04: POST /api/test/reset destroys and recreates, returns valid SeedResult with JWT")
    void reset_clearsAndRecreates() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/test/reset")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").isNumber())
                .andExpect(jsonPath("$.userId").isNumber())
                .andExpect(jsonPath("$.jwt").isString())
                .andExpect(jsonPath("$.jwt", not(emptyString())))
                .andExpect(jsonPath("$.email").value("e2e@auraboot.test"))
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        Long newTenantId = body.get("tenantId").asLong();

        // After reset, tenantId may differ (old tenant deleted, new one created)
        Assertions.assertNotNull(newTenantId);
        log.info("TS-04: reset returned tenantId={} (previous was {})", newTenantId, seededTenantId);
    }

    @Test
    @Order(5)
    @DisplayName("TS-05: GET /api/test/context after reset still shows seeded=true")
    void context_afterReset_stillSeeded() throws Exception {
        mockMvc.perform(get("/api/test/context"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seeded").value(true))
                .andExpect(jsonPath("$.jwt").isString())
                .andExpect(jsonPath("$.jwt", not(emptyString())));

        log.info("TS-05: context after reset shows seeded=true");
    }
}
