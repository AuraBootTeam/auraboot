package com.auraboot.framework.application.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3.0 configuration for Swagger UI.
 * Security whitelist for /swagger-ui/** and /v3/api-docs/** is managed in SecurityConfig.
 *
 * Usage: POST /api/auth/login → copy data.jwt → click Authorize → paste as Bearer token.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI auraBootOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("AuraBoot Platform API")
                        .version("1.0.0")
                        .description("AuraBoot low-code / ERP / CRM platform REST API. "
                                + "Authenticate via POST /api/auth/login, then click **Authorize** and paste the JWT token.")
                        .contact(new Contact()
                                .name("AuraBoot Team")
                                .url("https://auraboot.com"))
                        .license(new License().name("Proprietary")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("jwt")
                                        .description("JWT token obtained from POST /api/auth/login → data.jwt")));
    }

    /** Separate developer contract; internal controllers never enter this document by default. */
    @Bean
    public GroupedOpenApi openPlatformApi() {
        return GroupedOpenApi.builder()
                .group("open-platform")
                .pathsToMatch("/oauth2/token", "/api/open/v1/**")
                .addOpenApiCustomizer(api -> {
                    api.setInfo(new Info()
                            .title("AuraBoot Open Platform API")
                            .version("v1")
                            .description("Explicitly published machine-to-machine APIs."));
                    api.setSecurity(java.util.List.of(new SecurityRequirement().addList("oauth2ClientCredentials")));
                    api.getComponents().addSecuritySchemes("oauth2ClientCredentials",
                            new SecurityScheme()
                                    .type(SecurityScheme.Type.OAUTH2)
                                    .flows(new OAuthFlows().clientCredentials(new OAuthFlow()
                                            .tokenUrl("/oauth2/token"))));
                })
                .build();
    }
}
