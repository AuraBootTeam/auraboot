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
import io.swagger.v3.oas.models.servers.Server;
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

    /** Internal transport catalog; frontend consumers select explicit operations from this group. */
    @Bean
    public GroupedOpenApi internalWebAdminApi() {
        return GroupedOpenApi.builder()
                .group("internal-web-admin")
                .packagesToScan("com.auraboot.framework", "com.auraboot.module")
                .pathsToMatch("/api/**")
                .pathsToExclude("/api/open/v1/**", "/api/test/**", "/api/mobile/**")
                .addOpenApiCustomizer(api -> {
                    api.setServers(java.util.List.of(new Server().url("/")));
                    api.setInfo(new Info().title("AuraBoot Internal Web Admin API")
                            .version("1.0.0")
                            .description("Internal session-authenticated transport catalog. "
                                    + "Operation authorization is enforced by the platform; "
                                    + "this document does not grant external integration access."));
                })
                .build();
    }

    /** Separate developer contract; internal controllers never enter this document by default. */
    @Bean
    public GroupedOpenApi openPlatformApi() {
        return GroupedOpenApi.builder()
                .group("open-platform")
                .pathsToMatch("/oauth2/token", "/api/open/v1/**")
                .addOpenApiCustomizer(api -> {
                    api.setServers(java.util.List.of(new Server().url("/")));
                    api.setInfo(new Info()
                            .title("AuraBoot Open Platform API")
                            .version("v1")
                            .description("Explicitly published machine-to-machine APIs."));
                    api.setSecurity(java.util.List.of(new SecurityRequirement().addList("integrationToken"),
                            new SecurityRequirement().addList("oauth2ClientCredentials")));
                    api.getComponents().addSecuritySchemes("integrationToken", new SecurityScheme().type(SecurityScheme.Type.HTTP)
                            .scheme("bearer").bearerFormat("opaque").description("Direct integration token; current installation authority applies"));
                    api.getComponents().addSecuritySchemes("oauth2ClientCredentials",
                            new SecurityScheme()
                                    .type(SecurityScheme.Type.OAUTH2)
                                    .flows(new OAuthFlows().clientCredentials(new OAuthFlow()
                                            .tokenUrl("/oauth2/token"))));
                })
                .build();
    }
}
