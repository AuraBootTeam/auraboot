package com.auraboot.framework.agent.runtime.policy;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AgentRuntimePolicyConfiguration {

    @Bean
    public AgentProfileResolver agentProfileResolver() {
        return DefaultAgentProfileResolver.INSTANCE;
    }
}
