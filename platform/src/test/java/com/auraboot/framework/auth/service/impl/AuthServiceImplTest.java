package com.auraboot.framework.auth.service.impl;

import com.auraboot.framework.auth.dto.AuthStrategyRequest;
import com.auraboot.framework.auth.dto.AuthenticationRequest;
import com.auraboot.framework.auth.dto.AuthenticationResponse;
import com.auraboot.framework.auth.dto.RegisterRequest;
import com.auraboot.framework.auth.service.SessionManagementService;
import com.auraboot.framework.auth.strategy.AuthStrategy;
import com.auraboot.framework.auth.util.JwtUtil;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.User.UserBuilder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthServiceImpl")
class AuthServiceImplTest {

    @Mock
    private AuthStrategy emailPasswordStrategy;
    @Mock
    private UserService userService;
    @Mock
    private JwtUtil jwtUtil;
    @Mock
    private UserDetailsService userDetailsService;
    @Mock
    private SessionManagementService sessionManagementService;

    private AuthServiceImpl service;

    @BeforeEach
    void setUp() {
        when(emailPasswordStrategy.getChannelCode()).thenReturn("email_password");
        service = new AuthServiceImpl(
                List.of(emailPasswordStrategy),
                userService,
                jwtUtil,
                userDetailsService,
                sessionManagementService);
    }

    @Test
    @DisplayName("authenticate delegates to email_password strategy")
    void authenticateDelegatesToEmailPassword() throws Exception {
        AuthenticationResponse expected = new AuthenticationResponse("jwt", 1L, "pid", "name");
        when(emailPasswordStrategy.authenticate(any(AuthStrategyRequest.class))).thenReturn(expected);

        AuthenticationRequest req = new AuthenticationRequest();
        req.setEmail("a@b.com");
        req.setPassword("pw");

        AuthenticationResponse actual = service.authenticate(req);
        assertEquals(expected, actual);
    }

    @Test
    @DisplayName("authenticateByChannel throws on missing channel code")
    void authenticateByChannelMissingCode() {
        AuthStrategyRequest req = new AuthStrategyRequest();
        req.setChannelCode(null);
        assertThrows(BusinessException.class, () -> service.authenticateByChannel(req));

        req.setChannelCode("");
        assertThrows(BusinessException.class, () -> service.authenticateByChannel(req));
    }

    @Test
    @DisplayName("authenticateByChannel throws on unsupported channel")
    void authenticateByChannelUnsupported() {
        AuthStrategyRequest req = new AuthStrategyRequest();
        req.setChannelCode("unknown_channel");
        BusinessException ex = assertThrows(BusinessException.class, () -> service.authenticateByChannel(req));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("unknown_channel"));
    }

    @Test
    @DisplayName("register creates user, generates jwt, creates session")
    void registerHappyPath() throws Exception {
        User user = new User();
        user.setId(7L);
        user.setPid("u-pid");
        user.setNickName("Nick");

        UserDetails ud = org.springframework.security.core.userdetails.User
                .withUsername("a@b.com").password("pw").authorities("USER").build();

        when(userService.signUp("a@b.com", "Pw1!secret", "Nick")).thenReturn(user);
        when(userDetailsService.loadUserByUsername("a@b.com")).thenReturn(ud);
        when(jwtUtil.generateTokenWithTenantId(eq(ud), eq("u-pid"), eq(null))).thenReturn("token-x");

        RegisterRequest rr = new RegisterRequest();
        rr.setEmail("a@b.com");
        rr.setPassword("Pw1!secret");
        rr.setDisplayName("Nick");

        AuthenticationResponse resp = service.register(rr);
        assertEquals("token-x", resp.getJwt());
        assertEquals(7L, resp.getUserId());
        verify(sessionManagementService).createSession(7L, "token-x", null, null);
    }
}
