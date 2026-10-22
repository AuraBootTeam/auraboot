package com.auraboot.framework.agent;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration entry point for the core AI runtime.
 */
@Configuration
@ComponentScan(basePackages = {
    "com.auraboot.framework.agent.controller",
    "com.auraboot.framework.agent.service",
    "com.auraboot.framework.rag.service",
    "com.auraboot.framework.rag.controller",
})
@org.mybatis.spring.annotation.MapperScan("com.auraboot.framework.rag.mapper")
public class AiRuntimeAutoConfiguration {
}
