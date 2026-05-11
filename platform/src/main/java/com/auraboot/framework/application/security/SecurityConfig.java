package com.auraboot.framework.application.security;

import com.auraboot.framework.application.web.filter.JwtAuthenticationFilter;
import com.auraboot.framework.application.web.filter.TestUserSpoofFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;


@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final UserDetailsService userDetailsService;

    /**
     * Test-only filter that allows E2E specs to override MetaContext user id
     * via {@code X-Test-Spoof-User-Id}. Bean is only registered under the
     * {@code test} Spring profile; absent in prod / dev / integration-test.
     */
    @Autowired(required = false)
    private TestUserSpoofFilter testUserSpoofFilter;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @org.springframework.beans.factory.annotation.Value("${spring.profiles.active:default}")
    private String activeProfile;

    @org.springframework.beans.factory.annotation.Value("${cors.allowed-origins:}")
    private String corsAllowedOrigins;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter, UserDetailsService userDetailsService) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.userDetailsService = userDetailsService;
    }

    @Bean
    @SuppressWarnings("java/spring-disabled-csrf-protection")
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        boolean isDevOrTest = activeProfile.contains("dev") || activeProfile.contains("local")
                || activeProfile.contains("test") || "default".equals(activeProfile);

        http
                // codeql[java/csrf-unprotected-request-type] AuraBoot API
                // authentication is stateless and requires an Authorization:
                // Bearer token; browser cookies are not an authentication
                // channel here.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeHttpRequests(auth -> {
                        auth.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(WhiteList.whiteList).permitAll();
                        // Swagger UI only accessible in dev/test environments
                        if (isDevOrTest) {
                            auth.requestMatchers(WhiteList.swaggerWhiteList).permitAll();
                        }
                        // Test seed endpoints only accessible in test profile
                        if (activeProfile.contains("test")) {
                            auth.requestMatchers(WhiteList.testWhiteList).permitAll();
                        }
                        auth.dispatcherTypeMatchers(
                                jakarta.servlet.DispatcherType.ASYNC,
                                jakarta.servlet.DispatcherType.ERROR
                        ).permitAll()
                        .anyRequest().authenticated();
                })
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        // Test-only: honor X-Test-Spoof-User-Id AFTER JwtAuthenticationFilter
        // populates MetaContext. Bean is only present when the "test" profile
        // is active — no chance of spoofing leaking into prod.
        if (testUserSpoofFilter != null) {
            http.addFilterAfter(testUserSpoofFilter, JwtAuthenticationFilter.class);
        }

        return http.build();
    }


    @Bean
    public AuthenticationManager authenticationManager() {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider(userDetailsService);
        authProvider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(authProvider);
    }

    /**
     * CORS configuration source
     *
     * Security considerations:
     * - Production: Set CORS_ALLOWED_ORIGINS env var with specific domains
     * - Development: Allow localhost for dev convenience
     * - Never use "*" with credentials in production
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // Dev origins always allowed
        List<String> origins = new java.util.ArrayList<>(Arrays.asList(
            "http://localhost:5173",      // Vite dev server
            "http://localhost:5174",      // Enterprise staged Vite dev server
            "http://localhost:3000",      // Alternative dev port
            "http://127.0.0.1:5173",
            "http://127.0.0.1:5174",
            "http://127.0.0.1:3000"
        ));

        // Production origins from environment variable (comma-separated)
        if (corsAllowedOrigins != null && !corsAllowedOrigins.isBlank()) {
            origins.addAll(Arrays.stream(corsAllowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList());
        }

        configuration.setAllowedOriginPatterns(origins);

        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        configuration.setAllowedHeaders(Arrays.asList(
            "Authorization",
            "Content-Type",
            "X-Requested-With",
            "Accept",
            "Origin",
            "X-Tenant-Id",
            "Stripe-Signature"
        ));
        configuration.setExposedHeaders(Arrays.asList(
            "X-Total-Count",
            "X-Page-Size",
            "X-Current-Page"
        ));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
