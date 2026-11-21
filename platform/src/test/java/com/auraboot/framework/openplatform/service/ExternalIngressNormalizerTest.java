package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.openplatform.mapper.ExternalIngressDefaultsMapper;
import com.auraboot.framework.plugin.extension.integration.ExternalEventIngressExtension.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Pure identity projection checks only; mapper mocks do not prove persistence or transaction behavior. */
class ExternalIngressNormalizerTest {
    private final InstalledIntegrationRegistry registry=mock(InstalledIntegrationRegistry.class);
    private final ExternalIngressDefaultsMapper records=mock(ExternalIngressDefaultsMapper.class);
    private final ObjectMapper json=new ObjectMapper();
    private final ExternalIngressNormalizer normalizer=new ExternalIngressNormalizer(registry,records,new OpenPlatformSecretCodec(),json);
    private RequestNormalizer policy(boolean ignore) {
        return new RequestNormalizer() {
            public PreparedRequest normalize(Map<String,Object> request,RequestDefaults defaults) {
                return new PreparedRequest(fingerprintInput(request),Map.of("event","stable-id"),List.of());
            }
            public Map<String,Object> fingerprintInput(Map<String,Object> request) {
                var copy=new LinkedHashMap<>(request);if(ignore)copy.remove("ignored");return copy;
            }
        };
    }
    private List<String> hashes(RequestNormalizer policy,String... bodies) {
        when(registry.requestNormalizer("example")).thenReturn(Optional.of(policy));
        when(records.claim(anyLong(),anyLong(),anyString(),anyString(),anyString(),anyString(),anyString())).thenReturn(1);
        when(records.align(anyLong(),anyLong(),anyString(),anyString(),anyString(),anyString(),anyString())).thenReturn(1);
        for(String body:bodies)normalizer.normalize(1,2,"example",null,body);
        var hash=ArgumentCaptor.forClass(String.class);
        verify(records,times(bodies.length)).claim(eq(1L),eq(2L),eq("example"),eq("event"),anyString(),hash.capture(),anyString());
        return hash.getAllValues();
    }
    @Test void explicitlyIgnoredFieldsDoNotChangeTheRetryFingerprint() {
        var values=hashes(policy(true),"{\"value\":12}","{\"ignored\":null,\"value\":12}","{\"value\":12,\"ignored\":{\"anything\":42}}");
        assertEquals(1,new HashSet<>(values).size());
    }
    @Test void realBusinessChangesStillHaveDifferentRetryFingerprints() {
        var values=hashes(policy(true),"{\"value\":12}","{\"value\":13}");
        assertNotEquals(values.get(0),values.get(1));
    }
    @Test void defaultProviderPolicyRetainsAllCallerFields() {
        RequestNormalizer strict=(request,defaults)->new PreparedRequest(request,Map.of("event","stable-id"),List.of());
        var values=hashes(strict,"{\"value\":12}","{\"value\":12,\"ignored\":true}");
        assertNotEquals(values.get(0),values.get(1));
    }
}
