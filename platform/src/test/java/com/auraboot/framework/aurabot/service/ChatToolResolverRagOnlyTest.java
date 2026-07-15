package com.auraboot.framework.aurabot.service;

import com.auraboot.framework.agent.port.GroundingPort;
import com.auraboot.framework.agent.port.ToolDiscoveryPort;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The embeddable customer-service widget ({@code cs_widget}) answers purely from retrieved knowledge.
 * It must carry NO tools — otherwise {@code ensurePlatformTools} re-adds {@code execute_sql}, and a
 * customer-facing bot ends up running SQL / looping on tools / talking about "querying the database".
 */
class ChatToolResolverRagOnlyTest {

    @Test
    void csWidgetChannelExposesNoToolsAndNeverReachesDiscovery() {
        GroundingPort grounding = Mockito.mock(GroundingPort.class);
        ToolDiscoveryPort discovery = Mockito.mock(ToolDiscoveryPort.class);
        ChatToolResolver resolver = new ChatToolResolver(grounding, discovery, null);

        ChatToolResolver.ResolvedTools resolved =
                resolver.resolveTools("你们退货期是几天?", null, null, "cs_widget");

        assertThat(resolved.tools()).isEmpty();
        // the crux: a RAG-only channel short-circuits BEFORE grounding + tool discovery, so nothing
        // (including the execute_sql fallback in ensurePlatformTools) can put a tool on the surface.
        verifyNoInteractions(grounding, discovery);
    }
}
