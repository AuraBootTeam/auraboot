package com.auraboot.framework.integration.security;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.application.web.filter.TestUserSpoofFilter;
import com.auraboot.framework.integration.BaseIntegrationTest;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link TestUserSpoofFilter}.
 *
 * <p>Asserts three behaviours:
 * <ol>
 *   <li>Header present + valid numeric value → {@link MetaContext#getCurrentUserId()}
 *       is overridden for the downstream chain.</li>
 *   <li>Header absent → {@link MetaContext} passes through untouched.</li>
 *   <li>Header present but not numeric → filter rejects with HTTP 400 and
 *       does not invoke the rest of the chain.</li>
 * </ol>
 *
 * <p>Profile handling — the filter bean is only registered under the
 * {@code test} profile, so we activate both {@code integration-test}
 * (for {@link BaseIntegrationTest} infrastructure) and {@code test}
 * (for the filter bean). The null-bean (non-test profile) case is
 * covered structurally by {@code @Profile("test")} + the guarded
 * registration in {@code SecurityConfig}; asserting it at runtime
 * requires booting a second context without {@code test} active,
 * which doubles test time for no additional signal.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles({"integration-test", "test"})
@DisplayName("TestUserSpoofFilter Integration Tests")
class TestUserSpoofFilterIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TestUserSpoofFilter filter;

    @Test
    @DisplayName("SPOOF-01: bean is registered under test profile")
    void spoofFilter_isPresentUnderTestProfile() {
        assertThat(filter)
                .as("TestUserSpoofFilter must be wired when test profile is active")
                .isNotNull();
    }

    @Test
    @DisplayName("SPOOF-02: valid numeric header overrides MetaContext user id")
    void spoofFilter_overridesUserId() throws Exception {
        // Arrange — establish a "pre-JWT-filter" MetaContext so the spoof
        // filter has something to mutate.
        MetaContext.setContext(1001L, 9999L, "admin_pid", "admin");
        try {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/user/soul-profile");
            request.addHeader(TestUserSpoofFilter.HEADER, "42424242");
            MockHttpServletResponse response = new MockHttpServletResponse();

            AtomicLong observedUserId = new AtomicLong(-1);
            FilterChain chain = (req, res) -> observedUserId.set(MetaContext.getCurrentUserId());

            // Act
            filter.doFilter(request, response, chain);

            // Assert — chain observed the spoofed id; tenant untouched.
            assertThat(observedUserId.get()).isEqualTo(42424242L);
            assertThat(MetaContext.getCurrentTenantId()).isEqualTo(1001L);
            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        } finally {
            MetaContext.clear();
        }
    }

    @Test
    @DisplayName("SPOOF-03: absent header leaves MetaContext untouched")
    void spoofFilter_passthroughWithoutHeader() throws Exception {
        MetaContext.setContext(1001L, 9999L, "admin_pid", "admin");
        try {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/user/soul-profile");
            // no header
            MockHttpServletResponse response = new MockHttpServletResponse();

            AtomicLong observedUserId = new AtomicLong(-1);
            FilterChain chain = (req, res) -> observedUserId.set(MetaContext.getCurrentUserId());

            filter.doFilter(request, response, chain);

            assertThat(observedUserId.get()).isEqualTo(9999L);
            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        } finally {
            MetaContext.clear();
        }
    }

    @Test
    @DisplayName("SPOOF-04: non-numeric header is rejected with 400")
    void spoofFilter_rejectsNonNumericHeader() throws Exception {
        MetaContext.setContext(1001L, 9999L, "admin_pid", "admin");
        try {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/user/soul-profile");
            request.addHeader(TestUserSpoofFilter.HEADER, "not-a-long");
            MockHttpServletResponse response = new MockHttpServletResponse();

            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, response, chain);

            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
            assertThat(response.getContentAsString()).contains("Invalid " + TestUserSpoofFilter.HEADER);
            // Chain must not have been invoked.
            assertThat(chain.getRequest()).isNull();
        } finally {
            MetaContext.clear();
        }
    }

    @Test
    @DisplayName("SPOOF-05: header without MetaContext (unauthenticated) passes through — no crash")
    void spoofFilter_noContext_passesThrough() throws Exception {
        MetaContext.clear(); // simulate pre-auth path (whitelisted endpoint)

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/login");
        request.addHeader(TestUserSpoofFilter.HEADER, "99");
        MockHttpServletResponse response = new MockHttpServletResponse();

        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(chain.getRequest()).isNotNull();
    }
}
