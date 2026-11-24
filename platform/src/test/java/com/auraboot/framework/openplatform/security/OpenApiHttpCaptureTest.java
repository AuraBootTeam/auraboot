package com.auraboot.framework.openplatform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

class OpenApiHttpCaptureTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @Test void captureRedactsNestedSecretsHeadersQueryAndEchoWithoutChangingTransport() throws Exception {
        var original = new MockHttpServletRequest("POST", "/api/open/v1/resources/orders");
        original.addHeader("Authorization", "Bearer raw-token-must-never-persist");
        original.addHeader("Cookie", "session=private-cookie");
        original.setQueryString("access_token=query-private-token&order=PO-001");
        original.setContentType("application/json");
        original.setContent("{\"password\":\"shared-secret-value\",\"order\":\"PO-001\",\"nested\":[{\"phone\":\"13800138000\"}]}".getBytes(StandardCharsets.UTF_8));
        var request = new OpenApiHttpCapture.Request(original); request.getInputStream().readAllBytes();
        var transport = new MockHttpServletResponse(); var response = new OpenApiHttpCapture.Response(transport);
        response.setContentType("application/json");
        String body="{\"message\":\"shared-secret-value raw-token-must-never-persist\",\"email\":\"private@example.com\",\"order\":\"PO-001\"}";
        response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        var captured = new OpenApiHttpCapture(mapper).snapshot(request,response,true);
        assertThat(transport.getContentAsString()).isEqualTo(body);
        assertThat(captured.toString()).doesNotContain("shared-secret-value","raw-token-must-never-persist","private@example.com","13800138000","query-private-token","private-cookie");
        assertThat(captured.path("request").path("body").path("order").asText()).isEqualTo("PO-001");
        assertThat(captured.path("response").path("body").path("order").asText()).isEqualTo("PO-001");
        assertThat(captured.path("query").path("access_token").asText()).isEqualTo("[REDACTED]");
    }
    @Test void oversizeBodiesNeverPersistUnredactablePartialJsonAndResponseIsUnchanged() throws Exception {
        var original=new MockHttpServletRequest("POST","/api/open/v1/test");original.setContentType("application/json");
        String body="{\"value\":\""+"x".repeat(OpenApiHttpCapture.LIMIT)+"\",\"password\":\"tail-secret\"}";
        original.setContent(body.getBytes(StandardCharsets.UTF_8));
        var request=new OpenApiHttpCapture.Request(original);request.getInputStream().readAllBytes();
        var transport=new MockHttpServletResponse();var response=new OpenApiHttpCapture.Response(transport);response.setContentType("application/json");response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        var captured=new OpenApiHttpCapture(mapper).snapshot(request,response,true);
        assertThat(captured.path("request").path("state").asText()).isEqualTo("truncated");
        assertThat(captured.path("response").path("state").asText()).isEqualTo("truncated");
        assertThat(captured.path("response").has("body")).isFalse();assertThat(captured.toString()).doesNotContain("tail-secret");
        assertThat(transport.getContentAsString()).isEqualTo(body);
        assertThat(request.getContentAsByteArray()).hasSize(OpenApiHttpCapture.LIMIT);
    }
    @Test void emptyGetUnreadRequestMalformedJsonAndBinaryAreDistinct() throws Exception {
        var request=new OpenApiHttpCapture.Request(new MockHttpServletRequest("GET","/api/open/v1/test"));
        var response=new OpenApiHttpCapture.Response(new MockHttpServletResponse());response.setContentType("application/octet-stream");response.getOutputStream().write(new byte[]{1,2,3});
        var result=new OpenApiHttpCapture(mapper).snapshot(request,response,true);
        assertThat(result.path("request").path("state").asText()).isEqualTo("empty");
        assertThat(result.path("response").path("reason").asText()).isEqualTo("unsupported_content_type");
        var post=new MockHttpServletRequest("POST","/api/open/v1/test");post.setContentType("application/json");post.setContent("{\"password\":\"do-not-read\"}".getBytes(StandardCharsets.UTF_8));
        request=new OpenApiHttpCapture.Request(post);
        assertThat(new OpenApiHttpCapture(mapper).snapshot(request,response,true).path("request").path("reason").asText()).isEqualTo("request_body_not_read");
        response=new OpenApiHttpCapture.Response(new MockHttpServletResponse());response.setContentType("application/json");response.getOutputStream().write("{\"password\":\"incomplete-secret".getBytes(StandardCharsets.UTF_8));
        result=new OpenApiHttpCapture(mapper).snapshot(request,response,true);
        assertThat(result.path("response").path("reason").asText()).isEqualTo("invalid_json");
        assertThat(result.toString()).doesNotContain("incomplete-secret");
    }
    @Test void unicodeWriterAndResetCaptureOnlyTheFinalBody() throws Exception {
        var request=new OpenApiHttpCapture.Request(new MockHttpServletRequest("GET","/api/open/v1/test"));
        var transport=new MockHttpServletResponse();transport.setCharacterEncoding("UTF-8");
        var response=new OpenApiHttpCapture.Response(transport);response.setContentType("application/json");
        response.getOutputStream().write("old-secret".getBytes(StandardCharsets.UTF_8));response.resetBuffer();
        response.getWriter().write("{\"message\":\"采购请求成功\"}");response.finishCapture();
        var result=new OpenApiHttpCapture(mapper).snapshot(request,response,true);
        assertThat(result.path("response").path("body").path("message").asText()).isEqualTo("采购请求成功");
        assertThat(transport.getContentAsString()).isEqualTo("{\"message\":\"采购请求成功\"}");
        assertThat(result.toString()).doesNotContain("old-secret");
    }
    @Test void exceptionDispatchDoesNotInventACompletedResponse() throws Exception {
        var request=new OpenApiHttpCapture.Request(new MockHttpServletRequest("GET","/api/open/v1/test"));
        var response=new OpenApiHttpCapture.Response(new MockHttpServletResponse());
        assertThat(new OpenApiHttpCapture(mapper).snapshot(request,response,false).path("response").path("reason").asText()).isEqualTo("incomplete_response");
    }
    @Test void unsafeRequestShapesCannotLeakThroughAnErrorEcho() throws Exception {
        for (String raw : new String[]{"{\"password\":\"unsafe-secret", "\"opaque-secret\""}) {
            var original = new MockHttpServletRequest("POST", "/api/open/v1/test");
            original.setContentType("application/json"); original.setContent(raw.getBytes(StandardCharsets.UTF_8));
            var request = new OpenApiHttpCapture.Request(original); request.getInputStream().readAllBytes();
            var transport = new MockHttpServletResponse(); var response = new OpenApiHttpCapture.Response(transport);
            response.setContentType("application/json");
            String echo = mapper.writeValueAsString(java.util.Map.of("message", raw));
            response.getOutputStream().write(echo.getBytes(StandardCharsets.UTF_8));
            var captured = new OpenApiHttpCapture(mapper).snapshot(request, response, true);
            assertThat(captured.path("request").path("state").asText()).isEqualTo("not_captured");
            assertThat(captured.path("response").path("reason").asText()).isEqualTo("unsafe_request_echo");
            assertThat(captured.toString()).doesNotContain("unsafe-secret", "opaque-secret");
            assertThat(transport.getContentAsString()).isEqualTo(echo);
        }
    }

}
