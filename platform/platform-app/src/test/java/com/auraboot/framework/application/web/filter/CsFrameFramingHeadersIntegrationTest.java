package com.auraboot.framework.application.web.filter;

import com.auraboot.framework.integration.BaseIntegrationTest;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

/**
 * Full-chain regression for the CS iframe-embed framing policy.
 *
 * <p>Two filters can stamp {@code X-Frame-Options}: our {@link SecurityHeadersFilter} (which
 * deliberately omits it on {@code /api/public/cs/frame} so the controller can emit a per-site
 * {@code frame-ancestors} allowlist instead), and Spring Security's own {@code HeaderWriterFilter}
 * (which, left at its default, stamps {@code DENY} on <em>every</em> response). The unit test
 * {@link SecurityHeadersFilterTest} exercises only the first filter in isolation and therefore
 * could not see the second re-adding {@code DENY} on the embed path — which is exactly how the
 * gap shipped. This test wires the real {@code springSecurityFilterChain} bean into MockMvc so the
 * assertions run through Spring Security's HeaderWriterFilter, the filter the unit test cannot reach.
 *
 * <p>Falsifiability: on {@code origin/main} (before disabling Security's {@code frameOptions} in
 * {@code SecurityConfig}) the first assertion fails — the embed path carries {@code X-Frame-Options: DENY}.
 * Verified live on the demo backend: pre-fix {@code /frame} returned {@code DENY}; post-fix it returns
 * only the per-site {@code frame-ancestors} CSP.
 */
@DisplayName("CS frame framing headers — full Spring Security chain")
class CsFrameFramingHeadersIntegrationTest extends BaseIntegrationTest {

    /** The deliberate iframe-embed path — must be framable by a site's own registered origins. */
    private static final String FRAME_PATH = SecurityHeadersFilter.CS_FRAME_EMBED_PATH;

    /** A whitelisted public path that is NOT the embed path — must keep the global clickjacking lock. */
    private static final String NON_FRAME_PUBLIC_PATH = "/api/public/cs/framing-probe-not-the-embed-path";

    @Autowired
    private WebApplicationContext webApplicationContext;

    /**
     * MockMvc wired with the production Spring Security filter chain (which contains HeaderWriterFilter)
     * AND the custom SecurityHeadersFilter — the two together are the app's real framing policy. Both
     * probe paths are under {@code /api/public/**} (permitAll), so no authenticated user is needed; there
     * is no OSS controller at these paths, so the request 404s after the filters have already written the
     * framing headers — which is all these assertions inspect.
     */
    private MockMvc mockMvc() {
        Filter springSecurityFilterChain = webApplicationContext.getBean("springSecurityFilterChain", Filter.class);
        SecurityHeadersFilter securityHeadersFilter = webApplicationContext.getBean(SecurityHeadersFilter.class);
        return MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(securityHeadersFilter, springSecurityFilterChain)
                .build();
    }

    @Test
    @DisplayName("embed path carries NO X-Frame-Options (per-site frame-ancestors owns framing there)")
    void framePath_hasNoXFrameOptions_throughFullSecurityChain() throws Exception {
        // MockMvc leaves getServletPath() empty unless set; a real container populates it to the
        // request path (DispatcherServlet mapped to "/"). SecurityHeadersFilter keys the embed
        // exemption off getServletPath(), so set it to mirror production faithfully.
        mockMvc().perform(get(FRAME_PATH).servletPath(FRAME_PATH))
                .andExpect(header().doesNotExist("X-Frame-Options"));
    }

    @Test
    @DisplayName("a non-embed public path still carries X-Frame-Options: DENY (lock intact)")
    void nonFramePublicPath_stillDenies_throughFullSecurityChain() throws Exception {
        mockMvc().perform(get(NON_FRAME_PUBLIC_PATH).servletPath(NON_FRAME_PUBLIC_PATH))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }
}
