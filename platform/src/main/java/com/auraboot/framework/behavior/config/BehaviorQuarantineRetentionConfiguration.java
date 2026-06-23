package com.auraboot.framework.behavior.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(BehaviorQuarantineRetentionProperties.class)
public class BehaviorQuarantineRetentionConfiguration {
}
