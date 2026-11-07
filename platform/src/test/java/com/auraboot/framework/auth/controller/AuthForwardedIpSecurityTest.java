package com.auraboot.framework.auth.controller;

import com.auraboot.framework.auth.dto.AuthenticationResponse;
import com.auraboot.framework.auth.dto.SendCodeRequest;
import com.auraboot.framework.auth.service.*;
import com.auraboot.framework.auth.wechat.WechatInvitationJoinService;
import com.auraboot.framework.auth.wechat.WechatMiniIdentityService;
import com.auraboot.framework.common.constant.ResponseCode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthForwardedIpSecurityTest {

    @Test
    void changingUntrustedForwardedHeadersCannotEscapeTheLoginClientBudget() throws Exception {
        AuthService auth = mock(AuthService.class);
        when(auth.authenticate(any())).thenReturn(new AuthenticationResponse("fixture-jwt", 1L, "fixture-user", "fixture"));
        var controller = new AuthController(auth, mock(UserInfoService.class), mock(PasswordManagementService.class),
                new LoginRateLimiter(), mock(SessionRenewalService.class), mock(WechatMiniIdentityService.class),
                mock(WechatInvitationJoinService.class));
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper())).build();
        for (int i = 0; i <= 60; i++) {
            mvc.perform(post("/api/auth/login").contentType("application/json")
                            .with(request -> { request.setRemoteAddr("198.51.100.9"); return request; })
                            .header("X-Forwarded-For", "203.0.113." + i)
                            .header("X-Real-IP", "203.0.113." + i)
                            .content("{\"identifier\":\"user-" + i + "\",\"password\":\"fixture-password\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(i < 60 ? "0" : ResponseCode.BadParam.getCode()));
        }
        verify(auth, times(60)).authenticate(any());
    }

    @Test
    void verificationCodeDeliveryUsesContainerResolvedClientIdentity() {
        VerificationCodeService codes = mock(VerificationCodeService.class);
        var controller = new VerifyCodeController(codes);
        var request = new SendCodeRequest();
        request.setTarget("fixture@example.com");
        request.setType("LOGIN");
        var http = new MockHttpServletRequest();
        http.setRemoteAddr("198.51.100.9");
        http.addHeader("X-Forwarded-For", "203.0.113.1");
        http.addHeader("X-Real-IP", "203.0.113.2");
        controller.sendCode(request, http);
        verify(codes).sendCode("fixture@example.com", "LOGIN", "198.51.100.9");
    }
}
