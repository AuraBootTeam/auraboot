package com.auraboot.framework.scheduler.xxl;

import com.auraboot.framework.scheduler.mapper.ScheduledTaskMapper;
import com.auraboot.framework.scheduler.service.SchedulerEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(XxlJobProperties.class)
public class XxlJobSchedulerConfiguration {

    @Bean
    @ConditionalOnProperty(name = "aura.scheduler.engine", havingValue = "xxl")
    @ConditionalOnMissingBean(XxlJobAdminClient.class)
    public XxlJobAdminClient xxlJobAdminClient(XxlJobProperties properties,
                                               ObjectProvider<RestTemplateBuilder> restTemplateBuilderProvider,
                                               ObjectProvider<ObjectMapper> objectMapperProvider) {
        if (properties.getAdminAddresses() == null || properties.getAdminAddresses().isBlank()) {
            return new UnavailableXxlJobAdminClient();
        }

        RestTemplateBuilder builder = restTemplateBuilderProvider.getIfAvailable(RestTemplateBuilder::new)
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMillis()))
                .readTimeout(Duration.ofMillis(properties.getReadTimeoutMillis()));
        ObjectMapper objectMapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
        return new XxlJobAdminHttpClient(properties, builder.build(), objectMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "aura.scheduler.engine", havingValue = "xxl")
    @ConditionalOnMissingBean(SchedulerEngine.class)
    public XxlJobSchedulerEngine xxlJobSchedulerEngine(ScheduledTaskMapper taskMapper,
                                                       XxlJobAdminClient adminClient,
                                                       XxlJobProperties properties,
                                                       ObjectProvider<ObjectMapper> objectMapperProvider) {
        return new XxlJobSchedulerEngine(taskMapper, adminClient, properties,
                objectMapperProvider.getIfAvailable(ObjectMapper::new));
    }
}
