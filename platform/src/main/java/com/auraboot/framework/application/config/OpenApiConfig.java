package com.auraboot.framework.application.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
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
}
