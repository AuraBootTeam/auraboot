package com.auraboot.framework.category.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(CategoryProperties.class)
public class CategoryConfiguration {
}
