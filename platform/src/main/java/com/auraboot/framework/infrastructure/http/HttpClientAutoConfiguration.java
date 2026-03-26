package com.auraboot.framework.infrastructure.http;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * Auto-configuration for a shared, pooled {@link RestTemplate} bean.
 * Replaces scattered {@code new RestTemplate()} anti-pattern across the codebase.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(HttpClientProperties.class)
public class HttpClientAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RestTemplate restTemplate(HttpClientProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getConnectTimeout());
        factory.setReadTimeout(props.getReadTimeout());

        RestTemplate restTemplate = new RestTemplateBuilder()
                .requestFactory(() -> factory)
                .build();

        log.info("Initialized shared RestTemplate: connectTimeout={}, readTimeout={}, maxRetries={}",
                props.getConnectTimeout(), props.getReadTimeout(), props.getRetry().getMaxAttempts());
        return restTemplate;
    }
}
