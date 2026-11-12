package com.auraboot.framework.notification.wechat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** MVC/bean-registration unit tests; not a deployed callback or authenticated provider execution. */
class WechatSubscriptionCallbackControllerTest {
    private static final String PATH = "/api/public/wechat-mini/subscription-events";

    @Test void providerRouteUsesExistingPublicMvcExclusionsWithoutWeakeningBusinessInterceptors() {
        var registry = new org.springframework.web.servlet.config.annotation.InterceptorRegistry() {
            java.util.List<Object> captured() { return getInterceptors(); }
        };
        var configuration = new com.auraboot.framework.application.config.WebMvcConfig(
                mock(com.auraboot.framework.permission.interceptor.PermissionInterceptor.class),
                mock(com.auraboot.framework.application.security.AdminRoleInterceptor.class),
                mock(com.auraboot.framework.environment.web.EnvironmentResolverInterceptor.class),
                mock(com.auraboot.framework.authoring.workspace.AuthoringBusinessWriteInterceptor.class));
        configuration.addInterceptors(registry);
        String route = WechatSubscriptionCallbackController.class
                .getAnnotation(org.springframework.web.bind.annotation.RequestMapping.class).value()[0];
        var provider = new org.springframework.mock.web.MockHttpServletRequest("GET", route);
        org.springframework.web.util.ServletRequestPathUtils.parseAndCache(provider);
        var business = new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/notification-templates");
        org.springframework.web.util.ServletRequestPathUtils.parseAndCache(business);
        int protectedBusinessInterceptors = 0;
        for (Object item : registry.captured()) {
            var mapped = (org.springframework.web.servlet.handler.MappedInterceptor) item;
            assertThat(mapped.matches(provider)).as("provider callback must use existing public exclusions").isFalse();
            if (mapped.matches(business)) protectedBusinessInterceptors++;
        }
        assertThat(protectedBusinessInterceptors).isEqualTo(3);
    }

    @Test void defaultDisabledDoesNotRegisterReceiverOrController() {
        new ApplicationContextRunner().withUserConfiguration(WechatSubscriptionConsentReceiver.class,
                WechatSubscriptionCallbackController.class).run(context -> {
                    assertThat(context).doesNotHaveBean(WechatSubscriptionConsentReceiver.class);
                    assertThat(context).doesNotHaveBean(WechatSubscriptionCallbackController.class);
                });
    }

    @Test void successfulUrlVerificationAndCommittedReceiptUsePlainText() throws Exception {
        var receiver = mock(WechatSubscriptionConsentReceiver.class);
        when(receiver.verifyUrl("signature", "timestamp", "nonce", "echo")).thenReturn("echo");
        when(receiver.receive(eq("aes"), eq("message-signature"), eq("timestamp"), eq("nonce"), any()))
                .thenReturn(new WechatSubscriptionConsentReceiver.Receipt(WechatSubscriptionConsentReceiver.Status.RECORDED, 1));
        var mvc = MockMvcBuilders.standaloneSetup(new WechatSubscriptionCallbackController(receiver)).build();
        mvc.perform(get(PATH).param("signature", "signature").param("timestamp", "timestamp")
                .param("nonce", "nonce").param("echostr", "echo"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("echo"));
        byte[] body = "synthetic ciphertext envelope".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        mvc.perform(post(PATH).param("encrypt_type", "aes").param("msg_signature", "message-signature")
                .param("timestamp", "timestamp").param("nonce", "nonce").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(content().string("success"));
        verify(receiver).receive("aes", "message-signature", "timestamp", "nonce", body);
    }

    @Test void invalidSignatureIsGenericAndDoesNotLeakProviderDetails() throws Exception {
        var receiver = mock(WechatSubscriptionConsentReceiver.class);
        when(receiver.receive(any(), any(), any(), any(), any()))
                .thenThrow(WechatSubscriptionCallbackVerifier.rejected("CALLBACK_SIGNATURE_INVALID"));
        var mvc = MockMvcBuilders.standaloneSetup(new WechatSubscriptionCallbackController(receiver)).build();
        mvc.perform(post(PATH).param("timestamp", "timestamp").param("nonce", "nonce").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(content().string("invalid request"));
    }

    @Test void oversizedRequestIsRejectedBeforeReceiverAndMissingFieldsDoNotAcknowledge() throws Exception {
        var receiver = mock(WechatSubscriptionConsentReceiver.class);
        var mvc = MockMvcBuilders.standaloneSetup(new WechatSubscriptionCallbackController(receiver)).build();
        mvc.perform(post(PATH).param("timestamp", "timestamp").param("nonce", "nonce")
                .content(new byte[WechatSubscriptionCallbackVerifier.MAX_BODY_BYTES + 1]))
                .andExpect(status().isBadRequest());
        mvc.perform(get(PATH).param("signature", "signature"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(receiver);
    }

    @Test void persistenceFailureIsNotReturnedAsSuccess() {
        var receiver = mock(WechatSubscriptionConsentReceiver.class);
        when(receiver.receive(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("synthetic database failure"));
        var mvc = MockMvcBuilders.standaloneSetup(new WechatSubscriptionCallbackController(receiver)).build();
        assertThatThrownBy(() -> mvc.perform(post(PATH).param("timestamp", "timestamp").param("nonce", "nonce").content("{}")))
                .hasRootCauseMessage("synthetic database failure");
    }
}
