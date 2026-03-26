package com.auraboot.framework.application.web.resolver;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;

/**
 * Standard servlet multipart resolver that is resilient to client aborts.
 *
 * <p>When multipart parsing fails (for example because the client closed the
 * connection while uploading), Spring will trigger an error dispatch. The
 * default {@link StandardServletMultipartResolver} tries to re-parse the same
 * broken request again during that dispatch which results in noisy log spam and
 * hides the original exception.</p>
 *
 * <p>This resolver remembers failed parsing attempts and skips multipart
 * detection for the subsequent {@code ERROR} dispatch so that the error
 * controller can render a JSON body without tripping over the same exception
 * again.</p>
 */
@Slf4j
public class SafeStandardServletMultipartResolver extends StandardServletMultipartResolver {

    /**
     * Request attribute used to record that multipart parsing already failed.
     */
    public static final String MULTIPART_PARSE_FAILED_ATTRIBUTE =
            SafeStandardServletMultipartResolver.class.getName() + ".PARSE_FAILED";

    @Override
    public boolean isMultipart(HttpServletRequest request) {
        // Skip further parsing for the ERROR dispatch to avoid recursive failures
        if (request.getDispatcherType() == DispatcherType.ERROR) {
            return false;
        }
        if (Boolean.TRUE.equals(request.getAttribute(MULTIPART_PARSE_FAILED_ATTRIBUTE))) {
            return false;
        }
        return super.isMultipart(request);
    }

    @Override
    public MultipartHttpServletRequest resolveMultipart(HttpServletRequest request) throws MultipartException {
        try {
            return super.resolveMultipart(request);
        } catch (MultipartException ex) {
            request.setAttribute(MULTIPART_PARSE_FAILED_ATTRIBUTE, Boolean.TRUE);
            if (log.isDebugEnabled()) {
                log.debug("Multipart parsing failed, marking request to skip error dispatch parsing: {}",
                        ex.getMessage());
            }
            throw ex;
        }
    }
}
