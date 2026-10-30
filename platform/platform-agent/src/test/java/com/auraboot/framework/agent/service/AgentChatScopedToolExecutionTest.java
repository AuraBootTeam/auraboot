package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.authorization.RuntimeAuthorizationService;
import com.auraboot.framework.agent.dto.BusinessIntentFrame;
import com.auraboot.framework.agent.provider.ToolDefinition;
import com.auraboot.framework.agent.provider.ToolProviderRegistry;
import com.auraboot.framework.agent.trace.AiTraceService;
import com.auraboot.framework.conversation.TurnContext;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.CommandExecutor;
import com.auraboot.framework.meta.service.NamedQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentChatScopedToolExecutionTest {
    @AfterEach
    void clearIntentFrame() { BifContext.clear(); }

    @Test
    void exactConversationProfileAndChannelReachTheRealToolKernel() {
        assertInvocation("reader-profile", "reader-profile", false);
    }

    @Test
    void aStaleIntentFrameCannotReplaceTheConversationProfile() {
        assertInvocation("other-profile", "reader-profile", true);
    }

    @Test
    void absentProfileUsesTheSameAgentCodeFallbackAsCatalogAuthorization() {
        assertInvocation(null, "reader-agent", false);
    }

    @Test
    void aStaleIntentFrameCannotReplaceTheConversationChannel() {
        assertInvocation("reader-profile", "reader-profile", true, "web");
    }

    private void assertInvocation(String currentProfile, String permittedProfile, boolean staleIntent) {
        assertInvocation(currentProfile, permittedProfile, staleIntent, "feishu:test");
    }

    private void assertInvocation(String currentProfile, String permittedProfile, boolean staleIntent,
                                  String currentChannel) {
        var checker = mock(ToolAclChecker.class);
        when(checker.check(eq(7L), nullable(String.class), nullable(String.class), eq("interactive"), eq("nq_ledger_read")))
                .thenAnswer(inv -> ToolAclChecker.Decision.builder()
                        .allowed(permittedProfile.equals(inv.getArgument(1)) && "feishu:test".equals(inv.getArgument(2)))
                        .matchedPriority(100).reason("exact_test_scope").build());
        var queries = mock(NamedQueryService.class);
        var page = new PaginationResult<Map<String, Object>>();
        page.setRecords(List.of(Map.of("name", "Acceptance ledger")));
        page.setTotal(1L);
        lenient().when(queries.executeQuery(eq("ledger_read"), any())).thenReturn(page);
        var authorization = mock(RuntimeAuthorizationService.class);
        lenient().when(authorization.authorizeIncremental(any()))
                .thenReturn(RuntimeAuthorizationService.IncrementalAuthorization.grant());
        var mapper = new ObjectMapper();
        var kernel = new ToolLoopService(mock(ActionRecorder.class), mock(AgentApprovalGateService.class), checker,
                mock(AiTraceService.class), mock(DynamicDataMapper.class), mock(CommandExecutor.class), queries,
                mapper, mock(ToolProviderRegistry.class), mock(ResultContractEmitter.class), authorization);
        var context = mock(TurnContext.class);
        when(context.tenantId()).thenReturn(7L);
        when(context.turnId()).thenReturn("turn");
        lenient().when(context.profileId()).thenReturn(currentProfile);
        lenient().when(context.channel()).thenReturn(currentChannel);
        if (staleIntent) {
            BifContext.setCurrentBif(BusinessIntentFrame.builder().profileId(permittedProfile).channel("feishu:test").build());
        }
        var definition = ToolDefinition.builder().toolCode("nq_ledger_read").toolType("dsl_query")
                .sourceCode("ledger_read").riskLevel("L0").build();
        var result = new AgentChatToolExecutionAdapter(kernel, mapper)
                .execute(context, "reader-agent", "nq_ledger_read", Map.of(), List.of(definition));
        boolean permitted = (currentProfile == null || permittedProfile.equals(currentProfile))
                && "feishu:test".equals(currentChannel);
        assertThat(result.get("success")).isEqualTo(permitted);
        if (permitted) {
            verify(queries).executeQuery(eq("ledger_read"), any());
        } else {
            verifyNoInteractions(queries);
        }
        verify(checker).check(7L, currentProfile == null ? "reader-agent" : currentProfile,
                currentChannel, "interactive", "nq_ledger_read");
    }
}
