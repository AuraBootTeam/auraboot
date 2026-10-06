package com.auraboot.framework.openplatform.controller;

import com.auraboot.framework.openplatform.service.OpenPlatformTokenService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class OpenPlatformTokenControllerTest {
    private final OpenPlatformTokenService service = mock(OpenPlatformTokenService.class);
    private final OpenPlatformTokenController controller = new OpenPlatformTokenController(service);

    private static String basic(String credentials) {
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void acceptsSwaggerClientCredentialsHeader() {
        controller.token("client_credentials", null, null, "openapi.profile.read", basic("client:fixture"));
        verify(service).issue("client_credentials", "client", "fixture", "openapi.profile.read");
    }

    @Test
    void preservesFormClientCredentials() {
        controller.token("client_credentials", "client", "fixture", null, null);
        verify(service).issue("client_credentials", "client", "fixture", null);
    }

    @Test
    void decodesEncodedCredentialsAndKeepsPasswordColons() {
        controller.token("client_credentials", null, null, null, basic("client%2Bname:fixture%3Avalue:part"));
        verify(service).issue("client_credentials", "client+name", "fixture:value:part", null);
    }

    @Test
    void rejectsCompetingAuthenticationMethodsBeforeIssuance() {
        assertThrows(OpenPlatformTokenService.InvalidClientException.class,
                () -> controller.token("client_credentials", "client", "fixture", null, basic("client:fixture")));
        verifyNoInteractions(service);
    }

    @Test
    void rejectsMalformedCredentialsBeforeIssuance() {
        for (String header : new String[]{"Bearer fixture", "Basic !invalid!", basic("missing-separator"),
                basic(":fixture"), basic("client:"), basic("client:%invalid")}) {
            assertThrows(OpenPlatformTokenService.InvalidClientException.class,
                    () -> controller.token("client_credentials", null, null, null, header));
        }
        verifyNoInteractions(service);
    }
}
