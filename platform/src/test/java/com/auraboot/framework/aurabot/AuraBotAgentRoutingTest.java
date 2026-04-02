package com.auraboot.framework.aurabot;

import com.auraboot.framework.agent.port.AgentChatPort;
import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.aurabot.dto.ChatRequest;
import com.auraboot.framework.aurabot.service.AuraBotChatService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for agentCode-based routing in AuraBotChatService.
 * <p>
 * Verifies:
 * 1. agentCode=null            → normal AuraBot path (AgentChatPort never called)
 * 2. agentCode="aurabot"       → normal AuraBot path (AgentChatPort never called)
 * 3. agentCode="nonexistent"   → graceful error via SSE (agentExists=false)
 * 4. agentCode="test_agent"    → delegates to AgentChatPort.streamAgentChat
 * <p>
 * LLM providers are mocked so tests run without real API keys.
 * AgentChatPort is mocked to isolate the routing logic.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@Transactional
@Rollback(true)
@DisplayName("AuraBotChatService - agentCode routing")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class AuraBotAgentRoutingTest extends BaseIntegrationTest {

    @Autowired
    private AuraBotChatService auraBotChatService;

    /**
     * Mock the AgentChatPort so we can control its behaviour without the
     * full AI execution stack (which needs real agent DB rows, LLM keys, etc.).
     */
    @MockBean
    private AgentChatPort agentChatPort;

    // =========================================================================
    // Helper
    // =========================================================================

    /**
     * Wait for the async task submitted by streamChat to complete.
     * We use the mock interactions on AgentChatPort as the signal that the routing
     * logic has run. Since the task is submitted to a thread pool, we poll with
     * short sleeps up to a max wait.
     */
    private void awaitAsyncRouting(int maxWaitMs) throws InterruptedException {
        Thread.sleep(maxWaitMs);
    }

    private ChatRequest buildRequest(String message, String agentCode) {
        ChatRequest req = new ChatRequest();
        req.setMessage(message);
        req.setSessionId("test-session-" + System.currentTimeMillis());
        req.setAgentCode(agentCode);
        return req;
    }

    // =========================================================================
    // Tests
    // =========================================================================

    /**
     * Test 1: agentCode=null → AuraBot path.
     * AgentChatPort must NOT be called at all.
     */
    @Test
    @Order(1)
    @DisplayName("agentCode=null routes to AuraBot (AgentChatPort not called)")
    void nullAgentCode_usesAuraBot() throws Exception {
        Long tenantId = getTestTenant().getId();
        Long userId = getTestUser().getId();
        String userPid = getTestUser().getPid();
        String username = getTestUser().getUserName();
        Long memberId = getTestTenantMember().getId();
        MetaContext.setContext(tenantId, userId, userPid, username);
        MetaContext.setMemberId(memberId);
        try {
            ChatRequest request = buildRequest("Hello AuraBot", null);

            // AgentChatPort should never be invoked for null agentCode
            // We capture the emitter but do not block — just verify mock interactions
            SseEmitter emitter = new SseEmitter(1_000L);

            // This will attempt to call LLM (which has no real key) and will send an error via SSE.
            // What matters is that agentChatPort is never called.
            auraBotChatService.streamChat(tenantId, userId, userPid, username, memberId, request, emitter);

            // Give the async task a moment to start
            Thread.sleep(200);

            verify(agentChatPort, never()).agentExists(any(), any());
            verify(agentChatPort, never()).streamAgentChat(any(), any(), any(), any());
        } finally {
            MetaContext.clear();
        }
    }

    /**
     * Test 2: agentCode="aurabot" → AuraBot path (explicit default code).
     * AgentChatPort must NOT be called.
     */
    @Test
    @Order(2)
    @DisplayName("agentCode='aurabot' routes to AuraBot (AgentChatPort not called)")
    void aurabotAgentCode_usesAuraBot() throws Exception {
        Long tenantId = getTestTenant().getId();
        Long userId = getTestUser().getId();
        String userPid = getTestUser().getPid();
        String username = getTestUser().getUserName();
        Long memberId = getTestTenantMember().getId();
        MetaContext.setContext(tenantId, userId, userPid, username);
        MetaContext.setMemberId(memberId);
        try {
            ChatRequest request = buildRequest("Hello AuraBot", "aurabot");

            SseEmitter emitter = new SseEmitter(1_000L);
            auraBotChatService.streamChat(tenantId, userId, userPid, username, memberId, request, emitter);

            Thread.sleep(200);

            verify(agentChatPort, never()).agentExists(any(), any());
            verify(agentChatPort, never()).streamAgentChat(any(), any(), any(), any());
        } finally {
            MetaContext.clear();
        }
    }

    /**
     * Test 3: agentCode="nonexistent" and agentExists returns false → graceful error path.
     * AgentChatPort.agentExists is called; streamAgentChat is NOT called.
     */
    @Test
    @Order(3)
    @DisplayName("agentCode='nonexistent' with missing agent → agentExists checked, streamAgentChat not called")
    void nonexistentAgentCode_sendsErrorAndDoesNotDelegate() throws Exception {
        Long tenantId = getTestTenant().getId();
        Long userId = getTestUser().getId();
        String userPid = getTestUser().getPid();
        String username = getTestUser().getUserName();
        Long memberId = getTestTenantMember().getId();
        MetaContext.setContext(tenantId, userId, userPid, username);
        MetaContext.setMemberId(memberId);
        try {
            // Configure mock: agent does NOT exist
            when(agentChatPort.agentExists(eq(tenantId), eq("nonexistent"))).thenReturn(false);

            SseEmitter emitter = new SseEmitter(1_000L);
            ChatRequest request = buildRequest("Hello", "nonexistent");
            auraBotChatService.streamChat(tenantId, userId, userPid, username, memberId, request, emitter);

            // Wait for async routing to execute
            awaitAsyncRouting(1000);

            // agentExists must have been checked
            verify(agentChatPort, times(1)).agentExists(eq(tenantId), eq("nonexistent"));

            // streamAgentChat must NOT be called when agent does not exist
            verify(agentChatPort, never()).streamAgentChat(any(), any(), any(), any());
        } finally {
            MetaContext.clear();
        }
    }

    /**
     * Test 4: agentCode="test_agent" and agentExists returns true → delegates to AgentChatPort.
     * streamAgentChat must be called exactly once with the correct arguments.
     */
    @Test
    @Order(4)
    @DisplayName("agentCode='test_agent' with existing agent → delegates to AgentChatPort.streamAgentChat")
    void existingAgentCode_delegatesToAgentChatPort() throws Exception {
        Long tenantId = getTestTenant().getId();
        Long userId = getTestUser().getId();
        String userPid = getTestUser().getPid();
        String username = getTestUser().getUserName();
        Long memberId = getTestTenantMember().getId();
        MetaContext.setContext(tenantId, userId, userPid, username);
        MetaContext.setMemberId(memberId);
        try {
            String agentCode = "test_agent";

            // Configure mock: agent exists, streamAgentChat is a no-op
            when(agentChatPort.agentExists(eq(tenantId), eq(agentCode))).thenReturn(true);
            doNothing().when(agentChatPort).streamAgentChat(eq(tenantId), eq(agentCode), any(), any());

            SseEmitter emitter = new SseEmitter(1_000L);
            ChatRequest request = buildRequest("Hello agent", agentCode);
            auraBotChatService.streamChat(tenantId, userId, userPid, username, memberId, request, emitter);

            // Wait for async routing to execute
            awaitAsyncRouting(1000);

            // Verify routing: agentExists checked, then streamAgentChat called
            verify(agentChatPort, times(1)).agentExists(eq(tenantId), eq(agentCode));
            verify(agentChatPort, times(1)).streamAgentChat(
                    eq(tenantId),
                    eq(agentCode),
                    argThat(req -> "Hello agent".equals(req.getMessage()) && agentCode.equals(req.getAgentCode())),
                    notNull()
            );
        } finally {
            MetaContext.clear();
        }
    }

    /**
     * Test 5: Blank agentCode → AuraBot path (blank is treated the same as null).
     */
    @Test
    @Order(5)
    @DisplayName("agentCode='' (blank) routes to AuraBot (AgentChatPort not called)")
    void blankAgentCode_usesAuraBot() throws Exception {
        Long tenantId = getTestTenant().getId();
        Long userId = getTestUser().getId();
        String userPid = getTestUser().getPid();
        String username = getTestUser().getUserName();
        Long memberId = getTestTenantMember().getId();
        MetaContext.setContext(tenantId, userId, userPid, username);
        MetaContext.setMemberId(memberId);
        try {
            ChatRequest request = buildRequest("Hello", "   ");

            SseEmitter emitter = new SseEmitter(1_000L);
            auraBotChatService.streamChat(tenantId, userId, userPid, username, memberId, request, emitter);

            Thread.sleep(200);

            verify(agentChatPort, never()).agentExists(any(), any());
            verify(agentChatPort, never()).streamAgentChat(any(), any(), any(), any());
        } finally {
            MetaContext.clear();
        }
    }
}
