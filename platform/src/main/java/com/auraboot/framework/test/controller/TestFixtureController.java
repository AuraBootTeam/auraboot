package com.auraboot.framework.test.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.StatusConstants;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.CommandExecuteResult;
import com.auraboot.framework.meta.mapper.CommandDefinitionMapper;
import com.auraboot.framework.meta.service.CommandExecutor;
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
    private CommandExecutor commandExecutor;

    @Autowired
    private CommandDefinitionMapper commandDefinitionMapper;

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
                case "multiview" -> createMultiviewFixture(runId, request.getParams());
                case "chat" -> createChatFixture(runId, request.getParams());
                case "native_fields" -> createNativeFieldsFixture(runId, request.getParams());
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

    // ── Private helpers ─────────────────────────────────────────────────────

    private String executeCreateCommand(String modelCode, Map<String, Object> payload) {
        String commandCode = resolveCreateCommandCode(modelCode);
        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setPayload(payload);
        request.setOperationType("CREATE");
        CommandExecuteResult result = commandExecutor.execute(commandCode, request);
        if (result.getData() != null) {
            Object id = result.getData().get("recordId");
            if (id == null) {
                id = result.getData().get("pid");
            }
            if (id != null) {
                return id.toString();
            }
        }
        return null;
    }

    /**
     * Resolve the CREATE command code for a model by looking up the command_definition table.
     * Plugins use namespace:create_model naming (e.g. "e2et:create_order" for model "e2et_order"),
     * not the old "modelCode.create" format.
     */
    private String resolveCreateCommandCode(String modelCode) {
        List<CommandDefinition> commands = commandDefinitionMapper.findByModelCode(modelCode);
        // Find a command whose code contains "create" (case-insensitive)
        for (CommandDefinition cmd : commands) {
            String code = cmd.getCode().toLowerCase();
            if (code.contains("create") && !code.contains("update")) {
                log.debug("Resolved create command for model {}: {}", modelCode, cmd.getCode());
                return cmd.getCode();
            }
        }
        // Fallback to legacy format
        log.warn("No create command found by DB lookup for model {}, falling back to {}.create", modelCode, modelCode);
        return modelCode + ".create";
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
                String pid = executeCreateCommand(modelCode, record);
                if (pid != null) {
                    recordIds.add(pid);
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
                String pid = executeCreateCommand("e2et_customer", record);
                if (pid != null) {
                    recordIds.add(pid);
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

    /**
     * Fixture: "multiview"
     * Creates 6 records in e2et_order with mixed statuses so the iOS multiview
     * test can exercise list / kanban / calendar / gallery view switching.
     * The model must already exist (shipped with the e2e test plugin).
     * <p>
     * viewConfigs expected in the page schema (set up by plugin):
     *   list     — default
     *   kanban   — statusField: e2et_order_status
     *   calendar — dateField: e2et_order_due_date  (may be null if field absent)
     *   gallery  — imageField: e2et_order_image, titleField: e2et_order_title, columns: 3
     */
    private FixtureResult createMultiviewFixture(String runId, Map<String, Object> params) {
        String modelCode = params != null && params.containsKey("modelCode")
                ? (String) params.get("modelCode")
                : "e2et_order";
        int count = params != null && params.containsKey("count")
                ? ((Number) params.get("count")).intValue()
                : 6;

        String[] statuses = {"draft", "submitted", "approved", "draft", "submitted", "approved"};
        List<String> recordIds = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                Map<String, Object> record = new HashMap<>();
                record.put("e2et_order_title", "mv_" + runId + "_" + (i + 1));
                record.put("e2et_order_status", statuses[i % statuses.length]);
                String pid = executeCreateCommand(modelCode, record);
                if (pid != null) {
                    recordIds.add(pid);
                }
            }
            log.info("Multiview fixture created: runId={}, count={}, model={}", runId, count, modelCode);
            return FixtureResult.builder()
                    .success(true)
                    .fixtureName("multiview")
                    .testRunId(runId)
                    .recordsCreated(count)
                    .recordIds(recordIds)
                    .metadata(Map.of(
                            "modelCode", modelCode,
                            "viewTypes", List.of("list", "kanban", "calendar", "gallery"),
                            "statusField", "e2et_order_status",
                            "titleField", "e2et_order_title"
                    ))
                    .build();
        } catch (Exception e) {
            log.error("Failed to create multiview fixture: {}", e.getMessage(), e);
            return FixtureResult.builder()
                    .success(false)
                    .fixtureName("multiview")
                    .testRunId(runId)
                    .recordsCreated(recordIds.size())
                    .recordIds(recordIds)
                    .metadata(Map.of("error", e.getMessage()))
                    .build();
        }
    }

    /**
     * Fixture: "chat"
     * Creates 2 conversations (1 direct message, 1 group) with a few messages each.
     * Uses ApplicationContext to look up ConversationService / MessageService beans
     * to avoid a compile-time dependency on the enterprise IM module.
     * <p>
     * Expected bean names (enterprise-im module):
     *   "conversationService"  — createConversation(tenantId, type, name, creatorId, memberIds)
     *   "chatMessageService"   — sendMessage(tenantId, conversationId, senderId, content, type)
     * <p>
     * If the IM module is not loaded, returns success=false with an explanatory error.
     */
    private FixtureResult createChatFixture(String runId, Map<String, Object> params) {
        // Resolve IM service via ApplicationContext — avoids compile-time dependency
        Object conversationService;
        try {
            conversationService = applicationContext.getBean("conversationService");
        } catch (Exception e) {
            return FixtureResult.builder()
                    .success(false).fixtureName("chat").testRunId(runId)
                    .recordsCreated(0).recordIds(List.of())
                    .metadata(Map.of("error", "IM module is not available (enterprise-im not loaded): " + e.getMessage()))
                    .build();
        }

        // Resolve tenant and user
        var tenant = tenantService.findByName("e2e_test");
        var user   = userService.findByEmail("e2e@test.local");
        if (tenant == null || user == null) {
            return FixtureResult.builder()
                    .success(false).fixtureName("chat").testRunId(runId)
                    .recordsCreated(0).recordIds(List.of())
                    .metadata(Map.of("error", "Cannot resolve tenant/user — call POST /api/test/seed first"))
                    .build();
        }
        Long tenantId = tenant.getId();
        Long userId   = user.getId();

        // Create or resolve second chat member for group conversation
        String chatMemberEmail = "e2e_chat_member_" + System.currentTimeMillis() + "@test.local";
        var chatUser = userService.findByEmail(chatMemberEmail);
        if (chatUser == null) {
            // User doesn't exist, create a new one (simplified creation)
            // This would need userService.createUser() or equivalent
            // For now, try to reuse or skip if creation is not available
            log.warn("Chat member user not found, using primary user for group members");
        }
        Long chatUserId = chatUser != null ? chatUser.getId() : userId;

        List<String> conversationIds = new ArrayList<>();
        int totalMessages = 0;
        try {
            // Attempt to find createConversation method — signature may vary by enterprise version.
            // We try (tenantId, type, name, creatorId) first, then fall through.
            Method createConv = null;
            for (Method m : conversationService.getClass().getMethods()) {
                if ("createConversation".equals(m.getName())) {
                    createConv = m;
                    break;
                }
            }
            if (createConv == null) {
                return FixtureResult.builder()
                        .success(false).fixtureName("chat").testRunId(runId)
                        .recordsCreated(0).recordIds(List.of())
                        .metadata(Map.of("error", "conversationService.createConversation method not found"))
                        .build();
            }

            // Determine whether chatUserId is a distinct second user.
            // Guard: if chatUserId is null or same as userId, treat as "no second user" so we
            // skip alternate-sender messages (iOS would see 0 unread — still valid, just no badge).
            boolean hasDistinctChatUser = chatUserId != null && !chatUserId.equals(userId);

            // Create direct-message conversation.
            // Send 2 messages from the primary user, then 1 message from chatUserId (if distinct).
            // The 1 message from chatUserId will appear as "unread" for the test user.
            Object dmConv = invokeCreateConversation(createConv, conversationService,
                    tenantId, "direct", "dm_" + runId, userId, List.of(userId));
            String dmId = extractStringId(dmConv);
            int dmUnreadCount = 0;
            if (dmId != null) {
                conversationIds.add(dmId);
                totalMessages += sendChatMessages(conversationService, tenantId, dmId, userId, runId, 2);
                if (hasDistinctChatUser) {
                    dmUnreadCount = sendChatMessages(conversationService, tenantId, dmId, chatUserId, runId + "-other", 1);
                    totalMessages += dmUnreadCount;
                }
            }

            // Create group conversation with multiple members.
            // Send 2 messages from the primary user, then 2 messages from chatUserId (if distinct).
            // The 2 messages from chatUserId will appear as "unread" for the test user.
            List<Long> groupMembers = Arrays.asList(userId, chatUserId);
            Object groupConv = invokeCreateConversation(createConv, conversationService,
                    tenantId, "group", "grp_" + runId, userId, groupMembers);
            String groupId = extractStringId(groupConv);
            int groupUnreadCount = 0;
            if (groupId != null) {
                conversationIds.add(groupId);
                totalMessages += sendChatMessages(conversationService, tenantId, groupId, userId, runId, 2);
                if (hasDistinctChatUser) {
                    groupUnreadCount = sendChatMessages(conversationService, tenantId, groupId, chatUserId, runId + "-other", 2);
                    totalMessages += groupUnreadCount;
                }
            }

            log.info("Chat fixture created: runId={}, conversations={}, messages={}, dmUnread={}, groupUnread={}",
                    runId, conversationIds.size(), totalMessages, dmUnreadCount, groupUnreadCount);
            return FixtureResult.builder()
                    .success(true)
                    .fixtureName("chat")
                    .testRunId(runId)
                    .recordsCreated(conversationIds.size())
                    .recordIds(conversationIds)
                    .metadata(new java.util.HashMap<>(Map.of(
                            "conversationIds", conversationIds,
                            "directConvId", dmId != null ? dmId : "",
                            "groupConvId", groupId != null ? groupId : "",
                            "chatUserId", chatUserId != null ? chatUserId : userId,
                            "totalMessages", totalMessages,
                            "tenantId", tenantId,
                            "userId", userId,
                            "dmUnreadCount", dmUnreadCount,
                            "groupUnreadCount", groupUnreadCount
                    )))
                    .build();
        } catch (Exception e) {
            log.error("Failed to create chat fixture: {}", e.getMessage(), e);
            return FixtureResult.builder()
                    .success(false)
                    .fixtureName("chat")
                    .testRunId(runId)
                    .recordsCreated(conversationIds.size())
                    .recordIds(conversationIds)
                    .metadata(Map.of("error", e.getMessage()))
                    .build();
        }
    }

    /** Invoke createConversation reflectively, trying multiple overload signatures. */
    private Object invokeCreateConversation(Method method, Object service,
                                             Long tenantId, String type, String name,
                                             Long creatorId, List<Long> memberIds) throws Exception {
        Class<?>[] paramTypes = method.getParameterTypes();
        if (paramTypes.length == 4) {
            // (tenantId, type, name, creatorId)
            return method.invoke(service, tenantId, type, name, creatorId);
        } else if (paramTypes.length == 5) {
            // (tenantId, type, name, creatorId, memberIds)
            return method.invoke(service, tenantId, type, name, creatorId, memberIds);
        } else {
            // Generic fallback — try with all 5 args and hope for the best
            return method.invoke(service, tenantId, type, name, creatorId, memberIds);
        }
    }

    /** Send N test messages to a conversation via reflection; returns count sent. */
    private int sendChatMessages(Object conversationService, Long tenantId, String conversationId,
                                  Long senderId, String runId, int count) {
        Object messageService;
        try {
            messageService = applicationContext.getBean("chatMessageService");
        } catch (Exception e) {
            log.warn("chatMessageService bean not found, skipping message creation: {}", e.getMessage());
            return 0;
        }

        Method sendMethod = null;
        for (Method m : messageService.getClass().getMethods()) {
            if ("sendMessage".equals(m.getName())) {
                sendMethod = m;
                break;
            }
        }
        if (sendMethod == null) {
            log.warn("chatMessageService.sendMessage method not found, skipping message creation");
            return 0;
        }

        int sent = 0;
        for (int i = 0; i < count; i++) {
            try {
                String content = "E2E test message [" + runId + "-" + (i + 1) + "]";
                Class<?>[] p = sendMethod.getParameterTypes();
                if (p.length == 4) {
                    sendMethod.invoke(messageService, tenantId, conversationId, senderId, content);
                } else if (p.length == 5) {
                    sendMethod.invoke(messageService, tenantId, conversationId, senderId, content, "text");
                } else {
                    sendMethod.invoke(messageService, tenantId, conversationId, senderId, content, "text");
                }
                sent++;
            } catch (Exception e) {
                log.warn("Failed to send message {}/{}: {}", i + 1, count, e.getMessage());
            }
        }
        return sent;
    }

    /** Extract string ID from a reflectively-created entity object. Tries getId(), then id field. */
    private String extractStringId(Object entity) {
        if (entity == null) return null;
        try {
            Object id = entity.getClass().getMethod("getId").invoke(entity);
            return id != null ? String.valueOf(id) : null;
        } catch (Exception e) {
            try {
                var field = entity.getClass().getDeclaredField("id");
                field.setAccessible(true);
                Object id = field.get(entity);
                return id != null ? String.valueOf(id) : null;
            } catch (Exception ex) {
                log.warn("Cannot extract id from {}: {}", entity.getClass().getSimpleName(), ex.getMessage());
                return null;
            }
        }
    }

    /**
     * Fixture: "native_fields"
     * Creates records in e2et_order with simulated camera-photo URLs and
     * base64-encoded signature data — used by iOS NativeCapabilityFlowTests
     * to verify that camera and signature fields render correctly.
     * <p>
     * Field mapping (configured in the e2e test plugin's e2et_order model):
     *   e2et_order_photo     — extension.renderComponent = "camera"  (stores image URL)
     *   e2et_order_signature — extension.renderComponent = "signature" (stores base64 SVG)
     */
    private FixtureResult createNativeFieldsFixture(String runId, Map<String, Object> params) {
        String modelCode = params != null && params.containsKey("modelCode")
                ? (String) params.get("modelCode")
                : "e2et_order";
        int count = params != null && params.containsKey("count")
                ? ((Number) params.get("count")).intValue()
                : 3;

        // Minimal inline SVG signature encoded as base64 data URI
        String signatureDataUri = "data:image/svg+xml;base64,"
                + "PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIyMDAiIGhlaWdodD0iNTAiPjxwYXRoIGQ9Ik0xMCAzMCBRNTAgNSAxMDAgMzAgUTE1MCA1NSAxOTAgMzAiIHN0cm9rZT0iIzMzMyIgZmlsbD0ibm9uZSIvPjwvc3ZnPg==";
        // Placeholder camera photo URL (PNG 1x1 pixel data URI)
        String photoDataUri = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";

        List<String> recordIds = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                Map<String, Object> record = new HashMap<>();
                record.put("e2et_order_title", "nf_" + runId + "_" + (i + 1));
                record.put("e2et_order_status", "draft");
                // Store simulated native field values using the field codes expected by the plugin
                record.put("e2et_order_photo", photoDataUri);
                record.put("e2et_order_signature", signatureDataUri);
                String pid = executeCreateCommand(modelCode, record);
                if (pid != null) {
                    recordIds.add(pid);
                }
            }
            log.info("Native fields fixture created: runId={}, count={}, model={}", runId, count, modelCode);
            return FixtureResult.builder()
                    .success(true)
                    .fixtureName("native_fields")
                    .testRunId(runId)
                    .recordsCreated(count)
                    .recordIds(recordIds)
                    .metadata(Map.of(
                            "modelCode", modelCode,
                            "cameraField", "e2et_order_photo",
                            "signatureField", "e2et_order_signature"
                    ))
                    .build();
        } catch (Exception e) {
            log.error("Failed to create native_fields fixture: {}", e.getMessage(), e);
            return FixtureResult.builder()
                    .success(false)
                    .fixtureName("native_fields")
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
                String pid = executeCreateCommand("e2et_order", record);
                if (pid != null) {
                    recordIds.add(pid);
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
