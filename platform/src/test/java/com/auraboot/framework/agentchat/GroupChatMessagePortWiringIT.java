package com.auraboot.framework.agentchat;

import static org.assertj.core.api.Assertions.assertThat;

import com.auraboot.framework.agentchat.spi.GroupChatMessagePort;
import com.auraboot.framework.agentchat.spi.NoOpGroupChatMessagePort;
import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.im.integration.GroupChatMessageAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;

/**
 * Guards that a group-chat turn always reaches the real IM-backed {@link GroupChatMessageAdapter},
 * never the silent {@link NoOpGroupChatMessagePort} fallback.
 *
 * <p>The three consumers (GroupChatAgentRouter, AgentReplyTask, GroupChatTurnContextAssembler)
 * resolve the port with {@code ObjectProvider.getIfAvailable(NoOpGroupChatMessagePort::new)}, which
 * throws {@code NoUniqueBeanDefinitionException} when more than one {@link GroupChatMessagePort}
 * bean exists. The NoOp is {@code @ConditionalOnMissingBean} on a {@code @Component} — a scan-order
 * dependent (unreliable) way to keep it excluded. If it ever failed to exclude, group chat would
 * throw at construction. {@code @Primary} on the adapter removes that fragility.
 *
 * <p>This test forces the exact failure mode: it registers a <b>second</b> port bean so two
 * candidates exist. Without {@code @Primary} on the adapter, both the direct injection and the
 * consumers' {@code getIfAvailable} throw here — so this test is red without the fix and green with
 * it. With {@code @Primary}, the real adapter wins deterministically.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@DisplayName("The real IM-backed group-chat port wins over the NoOp fallback, even under ambiguity")
class GroupChatMessagePortWiringIT {

    /** A second GroupChatMessagePort bean, so the context has two candidates (adapter + this). */
    @TestConfiguration
    static class SecondPortConfig {
        @Bean
        GroupChatMessagePort extraGroupChatMessagePort() {
            return new NoOpGroupChatMessagePort();
        }
    }

    @Autowired private ObjectProvider<GroupChatMessagePort> portProvider;
    @Autowired private GroupChatMessagePort injectedPort;

    @Test
    @DisplayName("consumers' getIfAvailable resolution returns the real adapter, not NoOp, with no ambiguity throw")
    void primaryAdapterWinsEvenWithASecondPortBean() {
        // Exactly what GroupChatAgentRouter / AgentReplyTask / GroupChatTurnContextAssembler do.
        GroupChatMessagePort resolved = portProvider.getIfAvailable(NoOpGroupChatMessagePort::new);
        assertThat(resolved)
                .as("a group-chat turn must reach real IM persistence, not the silent NoOp")
                .isInstanceOf(GroupChatMessageAdapter.class);

        assertThat(injectedPort)
                .as("direct @Autowired injection must also resolve the @Primary real adapter")
                .isInstanceOf(GroupChatMessageAdapter.class);
    }
}
