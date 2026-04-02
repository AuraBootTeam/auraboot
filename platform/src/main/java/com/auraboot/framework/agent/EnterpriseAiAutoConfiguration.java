package com.auraboot.framework.agent;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Legacy compatibility auto-configuration for the AI runtime now hosted in core.
 * Retained so external consumers can still import a single configuration class.
 */
@Configuration
@ComponentScan(basePackages = {
    "com.auraboot.framework.agent.controller",
    "com.auraboot.framework.agent.service",
    "com.auraboot.framework.rag.service",
    "com.auraboot.framework.rag.controller",
})
@org.mybatis.spring.annotation.MapperScan("com.auraboot.framework.rag.mapper")
public class EnterpriseAiAutoConfiguration {
}
