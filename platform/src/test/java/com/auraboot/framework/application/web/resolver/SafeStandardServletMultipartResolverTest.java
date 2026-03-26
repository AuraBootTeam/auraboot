package com.auraboot.framework.application.web.resolver;

import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.multipart.MultipartException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeStandardServletMultipartResolverTest {

    private final SafeStandardServletMultipartResolver resolver = new SafeStandardServletMultipartResolver();

    @Test
    void shouldSkipMultipartCheckForErrorDispatch() {
        MockHttpServletRequest request = multipartRequest();
        request.setDispatcherType(DispatcherType.ERROR);

        assertFalse(resolver.isMultipart(request));
    }

    @Test
    void shouldSkipMultipartCheckAfterFailedParsing() {
        MockHttpServletRequest request = multipartRequest();
        request.setAttribute(SafeStandardServletMultipartResolver.MULTIPART_PARSE_FAILED_ATTRIBUTE, Boolean.TRUE);

        assertFalse(resolver.isMultipart(request));
    }

    @Test
    void shouldDelegateWhenRequestIsMultipart() {
        assertTrue(resolver.isMultipart(multipartRequest()));
    }

    @Test
    void shouldMarkRequestWhenParsingFails() {
        MockHttpServletRequest request = failingMultipartRequest();

        assertThrows(MultipartException.class, () -> resolver.resolveMultipart(request));
        assertTrue((Boolean) request.getAttribute(
                SafeStandardServletMultipartResolver.MULTIPART_PARSE_FAILED_ATTRIBUTE));
    }

    private MockHttpServletRequest multipartRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContentType("multipart/form-data; boundary=test");
        return request;
    }

    private MockHttpServletRequest failingMultipartRequest() {
        return new MockHttpServletRequest() {
            {
                setContentType("multipart/form-data; boundary=test");
            }

            @Override
            public java.util.Collection<jakarta.servlet.http.Part> getParts() {
                throw new IllegalStateException("Simulated read failure");
            }
        };
    }
}
