package com.auraboot.framework.test.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.StatusConstants;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.test.dto.FixtureRequest;
import com.auraboot.framework.test.dto.FixtureResult;
import com.auraboot.framework.user.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test fixture controller — creates deterministic test data for E2E tests.
 * <p>
 * Only active when the "test" Spring profile is enabled.
 * Supports cross-platform coordination via testRunId.
 * <p>
 * InboxService is loaded via ApplicationContext to avoid compile-time dependency
 * on platform-enterprise-core module.
 */
@Slf4j
@RestController
@RequestMapping("/api/test/fixture")
@Profile("test")
public class TestFixtureController {

    private static final Map<String, FixtureResult> activeFixtures = new ConcurrentHashMap<>();

    @Autowired
    private DynamicDataService dynamicDataService;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private UserService userService;

    @Autowired
    private ApplicationContext applicationContext;

    /**
     * POST /api/test/fixture
     * Create fixture data by name. Returns created record IDs and metadata.
     * <p>
     * /api/test/** is on the security whitelist (permitAll), so JwtAuthFilter never runs.
     * We manually set MetaContext using the known test tenant/user before calling
     * DynamicDataService, and clear it in a finally block.
     */
    @PostMapping
    public ResponseEntity<FixtureResult> createFixture(
            @RequestBody FixtureRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        String runId = request.getTestRunId() != null
                ? request.getTestRunId()
                : UniqueIdGenerator.generate().substring(0, 8);

        log.info("Creating fixture: name={}, testRunId={}", request.getName(), runId);

        // Set MetaContext manually — test endpoints bypass JWT filter so tenant context
        // is never populated automatically.
        boolean contextSet = false;
        try {
            var tenant = tenantService.findByName("e2e_test");
            var user   = userService.findByEmail("e2e@test.local");
            if (tenant != null && user != null) {
                MetaContext.setContext(
                        tenant.getId(),
                        user.getId(),
                        user.getPid(),
                        user.getEmail()
                );
                contextSet = true;
            }
        } catch (Exception e) {
            log.warn("Failed to set MetaContext for fixture: {}", e.getMessage());
        }

        try {
            FixtureResult result = switch (request.getName()) {
                case "records" -> createRecordsFixture(runId, request.getParams());
                case "crossplatform" -> createCrossPlatformFixture(runId, request.getParams());
                case "dashboard" -> createDashboardFixture(runId, request.getParams());
                case "approval" -> createInboxItemsWithType(runId, request.getParams(), authHeader, "approval", "high", "E2E Approval Request");
                case "inbox_items" -> createInboxItemsFixture(runId, request.getParams(), authHeader);
                case "inbox_alert" -> createInboxItemsWithType(runId, request.getParams(), authHeader, "alert", "medium", "E2E Alert Item");
                case "inbox_mention" -> createInboxItemsWithType(runId, request.getParams(), authHeader, "mention", "low", "E2E Mention Item");
                case "inbox_assignment" -> createInboxItemsWithType(runId, request.getParams(), authHeader, "assignment", "high", "E2E Assignment Item");
                case "customers" -> createCustomersFixture(runId, request.getParams());
                default -> FixtureResult.builder()
                        .success(false)
                        .fixtureName(request.getName())
                        .testRunId(runId)
                        .recordsCreated(0)
                        .recordIds(List.of())
                        .metadata(Map.of("error", "Unknown fixture: " + request.getName()))
                        .build();
            };

            if (result.isSuccess()) {
                activeFixtures.put(runId, result);
            }
            return ResponseEntity.ok(result);
        } finally {
            if (contextSet) {
                MetaContext.clear();
            }
        }
    }

    /**
     * GET /api/test/fixture/{testRunId}
     * Retrieve fixture metadata by testRunId.
     */
    @GetMapping("/{testRunId}")
    public ResponseEntity<FixtureResult> getFixture(@PathVariable String testRunId) {
        FixtureResult result = activeFixtures.get(testRunId);
        if (result == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/test/fixture/run-id
     * Returns a new unique test run ID for cross-platform coordination.
     * Format per Test Session Contract: xp_{unixSeconds}_{4-hex-random}
     */
    @GetMapping("/run-id")
    public ResponseEntity<Map<String, String>> generateRunId() {
        String runId = TestSeedController.generateTestRunId("xp");
        return ResponseEntity.ok(Map.of("testRunId", runId));
    }

    // ── Private fixture builders ────────────────────────────────────────────

    private FixtureResult createRecordsFixture(String runId, Map<String, Object> params) {
        int count = params != null && params.containsKey("count")
                ? ((Number) params.get("count")).intValue()
                : 5;
        String modelCode = params != null && params.containsKey("modelCode")
                ? (String) params.get("modelCode")
                : "e2et_order";

        List<String> recordIds = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                Map<String, Object> record = new HashMap<>();
                record.put("e2et_order_title", "xp_" + runId + "_record_" + (i + 1));
                record.put("e2et_order_status", "draft");
                Map<String, Object> created = dynamicDataService.create(modelCode, record);
                Object pid = created.get("pid");
                if (pid != null) {
                    recordIds.add(pid.toString());
                }
            }
            log.info("Records fixture created: runId={}, count={}, model={}", runId, count, modelCode);
            return FixtureResult.builder()
                    .success(true)
                    .fixtureName("records")
                    .testRunId(runId)
                    .recordsCreated(count)
                    .recordIds(recordIds)
                    .metadata(Map.of("modelCode", modelCode))
                    .build();
        } catch (Exception e) {
            log.error("Failed to create records fixture: {}", e.getMessage(), e);
            return FixtureResult.builder()
                    .success(false)
                    .fixtureName("records")
                    .testRunId(runId)
                    .recordsCreated(recordIds.size())
                    .recordIds(recordIds)
                    .metadata(Map.of("error", e.getMessage()))
                    .build();
        }
    }

    private FixtureResult createCrossPlatformFixture(String runId, Map<String, Object> params) {
        // Cross-platform fixture: creates records in e2et_order model with xp_ prefix
        Map<String, Object> crossPlatformParams = new HashMap<>();
        crossPlatformParams.put("count", 3);
        crossPlatformParams.put("modelCode", "e2et_order");
        return createRecordsFixture(runId, crossPlatformParams);
    }

    /**
     * Fixture: "inbox_items"
     * Creates N pending inbox items assigned to the authenticated user.
     * Supports optional "type" param (default: "task") to create different item types.
     */
    private FixtureResult createInboxItemsFixture(String runId, Map<String, Object> params, String authHeader) {
        String type = params != null && params.containsKey("type")
                ? (String) params.get("type")
                : "task";
        String priority = "task".equals(type) || "assignment".equals(type) ? "normal" : "low";
        String titlePrefix = "E2E " + type.substring(0, 1).toUpperCase() + type.substring(1) + " Item";
        return createInboxItemsWithType(runId, params, authHeader, type, priority, titlePrefix);
    }

    /**
     * Creates inbox items via reflection to avoid compile-time dependency on enterprise-core.
     * Uses ApplicationContext to look up InboxService bean at runtime.
     */
    private FixtureResult createInboxItemsWithType(String runId, Map<String, Object> params,
                                                    String authHeader, String itemType,
                                                    String priority, String titlePrefix) {
        // Resolve InboxService via ApplicationContext (avoids compile-time dependency)
        Object inboxService;
        try {
            inboxService = applicationContext.getBean("inboxService");
        } catch (Exception e) {
            return FixtureResult.builder()
                    .success(false).fixtureName(itemType.toLowerCase()).testRunId(runId)
                    .recordsCreated(0).recordIds(List.of())
                    .metadata(Map.of("error", "Inbox module is not available (enterprise-core not loaded)"))
                    .build();
        }

        int count = params != null && params.containsKey("count")
                ? ((Number) params.get("count")).intValue()
                : 3;

        // Resolve tenantId from params (passed by APIHelper)
        Long tenantId = null;
        Long userId = null;
        if (params != null) {
            if (params.containsKey("tenantId")) {
                tenantId = Long.parseLong(params.get("tenantId").toString());
            }
        }

        // Fall back to the test tenant if not provided
        if (tenantId == null) {
            var tenant = tenantService.findByName("e2e_test");
            if (tenant != null) {
                tenantId = tenant.getId();
            }
        }
        if (tenantId == null) {
            return FixtureResult.builder()
                    .success(false).fixtureName(itemType.toLowerCase()).testRunId(runId)
                    .recordsCreated(0).recordIds(List.of())
                    .metadata(Map.of("error", "Cannot resolve tenantId — call POST /api/test/seed first"))
                    .build();
        }

        // Resolve userId via test user email
        var user = userService.findByEmail("e2e@test.local");
        if (user != null) {
            userId = user.getId();
        }
        if (userId == null) {
            return FixtureResult.builder()
                    .success(false).fixtureName(itemType.toLowerCase()).testRunId(runId)
                    .recordsCreated(0).recordIds(List.of())
                    .metadata(Map.of("error", "Cannot resolve userId — call POST /api/test/seed first"))
                    .build();
        }

        List<String> itemIds = new ArrayList<>();
        try {
            // Use reflection to call InboxService.createItem(InboxItem)
            Class<?> inboxItemClass = Class.forName("com.auraboot.framework.inbox.model.InboxItem");
            Method createItemMethod = inboxService.getClass().getMethod("createItem", inboxItemClass);

            for (int i = 0; i < count; i++) {
                Object item = inboxItemClass.getDeclaredConstructor().newInstance();

                // Set fields via reflection
                inboxItemClass.getMethod("setTenantId", Long.class).invoke(item, tenantId);
                inboxItemClass.getMethod("setUserId", Long.class).invoke(item, userId);
                inboxItemClass.getMethod("setItemType", String.class).invoke(item, itemType);
                inboxItemClass.getMethod("setTitle", String.class).invoke(item, titlePrefix + " [" + runId + "-" + (i + 1) + "]");
                inboxItemClass.getMethod("setSubtitle", String.class).invoke(item, "Seeded by E2E test fixture");
                inboxItemClass.getMethod("setPriority", String.class).invoke(item, priority);
                inboxItemClass.getMethod("setStatus", String.class).invoke(item, StatusConstants.PENDING);
                inboxItemClass.getMethod("setSourceType", String.class).invoke(item, "test");
                inboxItemClass.getMethod("setSourceId", String.class).invoke(item, runId + "-" + (i + 1));

                Object created = createItemMethod.invoke(inboxService, item);
                Object id = inboxItemClass.getMethod("getId").invoke(created);
                itemIds.add(String.valueOf(id));
            }
            log.info("{} fixture created: runId={}, count={}, tenantId={}, userId={}",
                    itemType, runId, count, tenantId, userId);
            return FixtureResult.builder()
                    .success(true)
                    .fixtureName(itemType.toLowerCase())
                    .testRunId(runId)
                    .recordsCreated(count)
                    .recordIds(itemIds)
                    .metadata(Map.of("itemType", itemType, "tenantId", tenantId, "userId", userId))
                    .build();
        } catch (Exception e) {
            log.error("Failed to create {} fixture: {}", itemType, e.getMessage(), e);
            return FixtureResult.builder()
                    .success(false)
                    .fixtureName(itemType.toLowerCase())
                    .testRunId(runId)
                    .recordsCreated(itemIds.size())
                    .recordIds(itemIds)
                    .metadata(Map.of("error", e.getMessage()))
                    .build();
        }
    }

    /**
     * Fixture: "customers"
     * Creates N e2et_customer records for REFERENCE field tests.
     * Customer names are prefixed with "e2e_cust_" so tests can search for them.
     */
    private FixtureResult createCustomersFixture(String runId, Map<String, Object> params) {
        int count = params != null && params.containsKey("count")
                ? ((Number) params.get("count")).intValue()
                : 3;
        String[] regions = {"east", "south", "north"};
        List<String> recordIds = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                Map<String, Object> record = new HashMap<>();
                record.put("e2et_cust_code", "e2e_" + runId + "_" + (i + 1));
                record.put("e2et_cust_name", "e2e_cust_" + runId + "_" + (i + 1));
                record.put("e2et_cust_region", regions[i % regions.length]);
                Map<String, Object> created = dynamicDataService.create("e2et_customer", record);
                Object pid = created.get("pid");
                if (pid != null) {
                    recordIds.add(pid.toString());
                }
            }
            log.info("Customers fixture created: runId={}, count={}", runId, count);
            return FixtureResult.builder()
                    .success(true)
                    .fixtureName("customers")
                    .testRunId(runId)
                    .recordsCreated(count)
                    .recordIds(recordIds)
                    .metadata(Map.of("modelCode", "e2et_customer"))
                    .build();
        } catch (Exception e) {
            log.error("Failed to create customers fixture: {}", e.getMessage(), e);
            return FixtureResult.builder()
                    .success(false)
                    .fixtureName("customers")
                    .testRunId(runId)
                    .recordsCreated(recordIds.size())
                    .recordIds(recordIds)
                    .metadata(Map.of("error", e.getMessage()))
                    .build();
        }
    }

    private FixtureResult createDashboardFixture(String runId, Map<String, Object> params) {
        // Dashboard fixture: creates diverse records to populate dashboard stat cards
        int baseCount = params != null && params.containsKey("count")
                ? ((Number) params.get("count")).intValue()
                : 10;
        List<String> recordIds = new ArrayList<>();

        try {
            for (int i = 0; i < baseCount; i++) {
                Map<String, Object> record = new HashMap<>();
                record.put("e2et_order_title", "dash_" + runId + "_" + (i + 1));
                // Use valid status values from e2et_order_status dict
                String[] statuses = {"draft", "submitted", "approved"};
                record.put("e2et_order_status", statuses[i % 3]);
                Map<String, Object> created = dynamicDataService.create("e2et_order", record);
                Object pid = created.get("pid");
                if (pid != null) {
                    recordIds.add(pid.toString());
                }
            }
            return FixtureResult.builder()
                    .success(true)
                    .fixtureName("dashboard")
                    .testRunId(runId)
                    .recordsCreated(baseCount)
                    .recordIds(recordIds)
                    .metadata(Map.of("totalAmount", baseCount * 550.0))
                    .build();
        } catch (Exception e) {
            log.error("Failed to create dashboard fixture: {}", e.getMessage(), e);
            return FixtureResult.builder()
                    .success(false)
                    .fixtureName("dashboard")
                    .testRunId(runId)
                    .recordsCreated(recordIds.size())
                    .recordIds(recordIds)
                    .metadata(Map.of("error", e.getMessage()))
                    .build();
        }
    }
}
