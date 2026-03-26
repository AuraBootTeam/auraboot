package com.auraboot.framework.auth;

import com.auraboot.framework.auth.dto.AuthStrategyRequest;
import com.auraboot.framework.auth.dto.AuthenticationRequest;
import com.auraboot.framework.auth.dto.AuthenticationResponse;
import com.auraboot.framework.auth.dto.RegisterRequest;
import com.auraboot.framework.auth.service.AuthService;
import com.auraboot.framework.auth.service.PasswordManagementService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for AuthService — registration, login, channel-based auth.
 */
@SpringBootTest(classes = com.auraboot.framework.application.TestApplication.class)
@ActiveProfiles("integration-test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AuthServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserService userService;

    @Autowired
    private PasswordManagementService passwordManagementService;

    private final String testRunId = String.valueOf(System.currentTimeMillis());

    // Shared across ordered tests
    private String registeredEmail;
    private String registeredPassword;

    // -----------------------------------------------------------------------
    // Test 1: Register with valid data
    // -----------------------------------------------------------------------
    @Test
    @Order(1)
    @DisplayName("register_withValidData_createsUserAndReturnsToken")
    void register_withValidData_createsUserAndReturnsToken() {
        registeredEmail = "auth-" + testRunId + "@example.com";
        registeredPassword = "TestPass456!";

        RegisterRequest request = new RegisterRequest();
        request.setEmail(registeredEmail);
        request.setPassword(registeredPassword);
        request.setDisplayName("Auth Test User");

        AuthenticationResponse response = authService.register(request);

        assertNotNull(response, "Response should not be null");
        assertNotNull(response.getJwt(), "JWT should not be null after registration");
        assertFalse(response.getJwt().isBlank(), "JWT should not be blank");
    }

    // -----------------------------------------------------------------------
    // Test 2: Register with duplicate email throws exception
    // -----------------------------------------------------------------------
    @Test
    @Order(2)
    @DisplayName("register_withDuplicateEmail_throwsException")
    void register_withDuplicateEmail_throwsException() {
        // Ensure test 1 ran first and set registeredEmail
        String email = (registeredEmail != null) ? registeredEmail : "auth-" + testRunId + "@example.com";

        RegisterRequest request = new RegisterRequest();
        request.setEmail(email);
        request.setPassword("TestPass456!");
        request.setDisplayName("Duplicate User");

        assertThrows(Exception.class, () -> authService.register(request),
                "Registering with duplicate email should throw an exception");
    }

    // -----------------------------------------------------------------------
    // Test 3: Authenticate with correct credentials returns JWT
    // -----------------------------------------------------------------------
    @Test
    @Order(3)
    @DisplayName("authenticate_withCorrectCredentials_returnsJwtToken")
    void authenticate_withCorrectCredentials_returnsJwtToken() {
        // Ensure we have a registered user from test 1; if not, create one
        if (registeredEmail == null) {
            registeredEmail = "auth-" + testRunId + "@example.com";
            registeredPassword = "TestPass456!";
            RegisterRequest reg = new RegisterRequest();
            reg.setEmail(registeredEmail);
            reg.setPassword(registeredPassword);
            reg.setDisplayName("Auth Test User");
            authService.register(reg);
        }

        AuthenticationRequest request = new AuthenticationRequest();
        request.setEmail(registeredEmail);
        request.setPassword(registeredPassword);

        AuthenticationResponse response = authService.authenticate(request);

        assertNotNull(response, "Response should not be null");
        assertNotNull(response.getJwt(), "JWT should not be null on successful login");
        assertFalse(response.getJwt().isBlank(), "JWT should not be blank");
    }

    // -----------------------------------------------------------------------
    // Test 4: Authenticate with wrong password throws exception
    // -----------------------------------------------------------------------
    @Test
    @Order(4)
    @DisplayName("authenticate_withWrongPassword_throwsAuthException")
    void authenticate_withWrongPassword_throwsAuthException() {
        if (registeredEmail == null) {
            registeredEmail = "auth-" + testRunId + "@example.com";
            registeredPassword = "TestPass456!";
            RegisterRequest reg = new RegisterRequest();
            reg.setEmail(registeredEmail);
            reg.setPassword(registeredPassword);
            reg.setDisplayName("Auth Test User");
            authService.register(reg);
        }

        AuthenticationRequest request = new AuthenticationRequest();
        request.setEmail(registeredEmail);
        request.setPassword("WrongPassword999!");

        assertThrows(Exception.class, () -> authService.authenticate(request),
                "Authenticating with wrong password should throw an exception");
    }

    // -----------------------------------------------------------------------
    // Test 5: Authenticate with locked account throws exception
    // -----------------------------------------------------------------------
    @Test
    @Order(5)
    @DisplayName("authenticate_withLockedAccount_throwsLockedException")
    void authenticate_withLockedAccount_throwsLockedException() {
        String lockEmail = "lock-" + testRunId + "@example.com";
        String lockPassword = "LockPass789!";

        RegisterRequest reg = new RegisterRequest();
        reg.setEmail(lockEmail);
        reg.setPassword(lockPassword);
        reg.setDisplayName("Lock Test User");
        authService.register(reg);

        // Record 5 failed attempts (reload user each time so entity state is fresh)
        for (int i = 0; i < 5; i++) {
            User freshUser = userService.findByEmail(lockEmail);
            assertNotNull(freshUser, "User should exist for lockout test");
            passwordManagementService.recordLoginFailure(freshUser);
        }

        User lockedUser = userService.findByEmail(lockEmail);
        assertNotNull(lockedUser, "User should still exist after lockout");
        assertTrue(passwordManagementService.isAccountLocked(lockedUser),
                "Account should be locked after 5 failed attempts");

        AuthenticationRequest request = new AuthenticationRequest();
        request.setEmail(lockEmail);
        request.setPassword(lockPassword);

        assertThrows(Exception.class, () -> authService.authenticate(request),
                "Authenticating with a locked account should throw an exception");
    }

    // -----------------------------------------------------------------------
    // Test 6: Authenticate by EMAIL_PASSWORD channel succeeds
    // -----------------------------------------------------------------------
    @Test
    @Order(6)
    @DisplayName("authenticateByChannel_withPasswordChannel_succeeds")
    void authenticateByChannel_withPasswordChannel_succeeds() {
        if (registeredEmail == null) {
            registeredEmail = "auth-" + testRunId + "@example.com";
            registeredPassword = "TestPass456!";
            RegisterRequest reg = new RegisterRequest();
            reg.setEmail(registeredEmail);
            reg.setPassword(registeredPassword);
            reg.setDisplayName("Auth Test User");
            authService.register(reg);
        }

        AuthStrategyRequest request = new AuthStrategyRequest();
        request.setEmail(registeredEmail);
        request.setPassword(registeredPassword);
        request.setChannelCode("email_password");

        AuthenticationResponse response = authService.authenticateByChannel(request);

        assertNotNull(response, "Response should not be null");
        assertNotNull(response.getJwt(), "JWT should not be null for EMAIL_PASSWORD channel");
        assertFalse(response.getJwt().isBlank(), "JWT should not be blank");
    }

    // -----------------------------------------------------------------------
    // Test 7: JWT has 3 parts (valid structure)
    // -----------------------------------------------------------------------
    @Test
    @Order(7)
    @DisplayName("authenticate_returnsToken_withValidSecurityVersion")
    void authenticate_returnsToken_withValidSecurityVersion() {
        if (registeredEmail == null) {
            registeredEmail = "auth-" + testRunId + "@example.com";
            registeredPassword = "TestPass456!";
            RegisterRequest reg = new RegisterRequest();
            reg.setEmail(registeredEmail);
            reg.setPassword(registeredPassword);
            reg.setDisplayName("Auth Test User");
            authService.register(reg);
        }

        AuthenticationRequest request = new AuthenticationRequest();
        request.setEmail(registeredEmail);
        request.setPassword(registeredPassword);

        AuthenticationResponse response = authService.authenticate(request);

        assertNotNull(response.getJwt(), "JWT should not be null");
        String[] parts = response.getJwt().split("\\.");
        assertEquals(3, parts.length,
                "JWT should have 3 parts separated by '.' (header.payload.signature)");
    }

    // -----------------------------------------------------------------------
    // Test 8: Full register-then-login flow
    // -----------------------------------------------------------------------
    @Test
    @Order(8)
    @DisplayName("register_thenLogin_fullFlowSucceeds")
    void register_thenLogin_fullFlowSucceeds() {
        String fullEmail = "full-" + testRunId + "@example.com";
        String fullPassword = "FullFlow456!";

        RegisterRequest reg = new RegisterRequest();
        reg.setEmail(fullEmail);
        reg.setPassword(fullPassword);
        reg.setDisplayName("Full Flow User");

        AuthenticationResponse regResponse = authService.register(reg);
        assertNotNull(regResponse.getJwt(), "Registration should return a JWT");

        AuthenticationRequest loginReq = new AuthenticationRequest();
        loginReq.setEmail(fullEmail);
        loginReq.setPassword(fullPassword);

        AuthenticationResponse loginResponse = authService.authenticate(loginReq);
        assertNotNull(loginResponse.getJwt(), "Login after registration should return a JWT");
        assertFalse(loginResponse.getJwt().isBlank(), "Login JWT should not be blank");
    }
}
