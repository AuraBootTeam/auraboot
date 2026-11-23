package com.auraboot.framework.openplatform.controller;

import com.auraboot.framework.auth.service.ApiRateLimiter;
import com.auraboot.framework.openplatform.service.SignedIntegrationCallbackService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Bounded transport and error propagation only; no database or commit claim. */
class SignedIntegrationCallbackControllerTest {
    final SignedIntegrationCallbackService service = mock(SignedIntegrationCallbackService.class);
    final ApiRateLimiter limiter = mock(ApiRateLimiter.class);
    final SignedIntegrationCallbackController controller = new SignedIntegrationCallbackController(service, limiter);

    MockHttpServletRequest request() {
        when(limiter.isAllowed(anyString(), eq(120))).thenReturn(true);
        var request = new MockHttpServletRequest(); request.setRemoteAddr("127.0.0.8");
        request.setContentType("application/x-www-form-urlencoded;charset=UTF-8"); request.setContent(new byte[]{1, 2, 3});
        return request;
    }
    @Test void rawWireIsPassedWithoutFormReencoding() throws Exception {
        var request = request(); var ack = Map.<String,Object>of("result", true);
        when(service.receive(eq(42L), eq("installation"), eq("sample-fees"), eq(request.getContentType()), any())).thenReturn(ack);
        assertEquals(ack, controller.receive(42L, "installation", "sample-fees", request));
        verify(service).receive(42L, "installation", "sample-fees", request.getContentType(), new byte[]{1, 2, 3});
    }
    @Test void oversizedEmptyCompressedAndQueryRequestsNeverReachAdmission() {
        for (String mode : new String[]{"oversized", "empty", "compressed", "query"}) {
            var request = request();
            switch (mode) {
                case "oversized" -> request.setContent(new byte[65537]);
                case "empty" -> request.setContent(new byte[0]);
                case "compressed" -> request.addHeader("Content-Encoding", "gzip");
                case "query" -> request.setQueryString("tenantId=99");
            }
            assertThrows(ResponseStatusException.class, () -> controller.receive(42L, "installation", "sample-fees", request), mode);
        }
        verifyNoInteractions(service);
    }
    @Test void rateLimitUsesPeerAddressInsteadOfCallerSelectedForwardedIdentity() {
        var request = request(); request.addHeader("X-Forwarded-For", "spoofed");
        when(limiter.isAllowed("signed-callback:peer:127.0.0.8", 120)).thenReturn(false);
        var rejected = assertThrows(ResponseStatusException.class, () -> controller.receive(42L, "installation", "sample-fees", request));
        assertEquals(429, rejected.getStatusCode().value()); verifyNoInteractions(service);
    }
    @Test void transactionFailureIsPropagatedWithoutASuccessBody() {
        var request = request();
        when(service.receive(any(), anyString(), anyString(), any(), any())).thenThrow(new IllegalStateException("commit-failed"));
        assertThrows(IllegalStateException.class, () -> controller.receive(42L, "installation", "sample-fees", request));
    }
    @Test void signatureAndConfigErrorsShareARedactedResponse() {
        assertEquals(400, controller.rejected().getStatusCode().value());
        assertEquals(Map.of("error", "CALLBACK_REJECTED"), controller.rejected().getBody());
    }
}
