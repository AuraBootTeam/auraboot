package com.auraboot.framework.aurabot.service;

import com.auraboot.framework.agent.port.GroundingPort;
import com.auraboot.framework.agent.port.ToolDiscoveryPort;
import com.auraboot.framework.application.tenant.MetaContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The embeddable customer-service widget ({@code cs_widget}) answers purely from retrieved knowledge.
 *
 * <p>The invariant is "no BUSINESS tools", not "no tools": {@code ensurePlatformTools} must never get
 * the chance to re-add {@code execute_sql}, and grounding-based discovery must never run — but the
 * channel's always-on fallback still has to be offered. Returning an empty list for everything is
 * what left {@code escalate_to_human} unreachable on the widget, so a bot that could not answer also
 * could not hand the visitor to a person.
 */
class ChatToolResolverRagOnlyTest {

    private static final String CHANNEL = "cs_widget";

    @BeforeEach
    void setUp() {
        // A real widget turn runs with tenant context (CsMessageService establishes it).
        MetaContext.setContext(100L, 200L, "visitor-pid", "visitor");
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    private static ToolDiscoveryPort.ToolDef escalateTool() {
        return new ToolDiscoveryPort.ToolDef(
                "cs:escalate_to_human", "Talk to a human", "Hand this conversation to a person",
                Map.of("type", "object"), false, "action", "cs", false, false, "low", null);
    }

    @Test
    void csWidgetOffersTheAlwaysOnFallbackButNeverGroundedDiscovery() {
        GroundingPort grounding = Mockito.mock(GroundingPort.class);
        ToolDiscoveryPort discovery = Mockito.mock(ToolDiscoveryPort.class);
        when(discovery.discoverAlwaysOnTools(100L, CHANNEL)).thenReturn(List.of(escalateTool()));
        ChatToolResolver resolver = new ChatToolResolver(grounding, discovery, null);

        ChatToolResolver.ResolvedTools resolved =
                resolver.resolveTools("你们退货期是几天?", null, null, CHANNEL);

        // the fallback reaches the model...
        assertThat(resolved.tools()).extracting("name").containsExactly("cs_escalate_to_human");

        // ...and the business-tool path is still structurally unreachable, which is the actual
        // safety property: no grounding, and no discoverTools (so ensurePlatformTools' execute_sql
        // fallback can never put SQL on a customer-facing surface).
        verifyNoInteractions(grounding);
        verify(discovery, never()).discoverTools(any(), any(), any(), any(), anyInt(), anyString());
    }

    @Test
    void csWidgetExposesNothingWhenNoProviderDeclaresAnAlwaysOnTool() {
        // Backwards compatible: a deployment with no always-on provider behaves exactly as before.
        GroundingPort grounding = Mockito.mock(GroundingPort.class);
        ToolDiscoveryPort discovery = Mockito.mock(ToolDiscoveryPort.class);
        when(discovery.discoverAlwaysOnTools(100L, CHANNEL)).thenReturn(List.of());
        ChatToolResolver resolver = new ChatToolResolver(grounding, discovery, null);

        ChatToolResolver.ResolvedTools resolved =
                resolver.resolveTools("你们退货期是几天?", null, null, CHANNEL);

        assertThat(resolved.tools()).isEmpty();
        verifyNoInteractions(grounding);
        verify(discovery, never()).discoverTools(any(), any(), any(), any(), anyInt(), anyString());
    }
}
