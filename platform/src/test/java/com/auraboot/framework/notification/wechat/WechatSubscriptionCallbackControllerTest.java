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
    private static final String PATH = "/api/wechat-mini/subscription-events";

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
