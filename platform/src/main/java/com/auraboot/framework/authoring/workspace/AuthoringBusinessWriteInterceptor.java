package com.auraboot.framework.authoring.workspace;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

import static org.springframework.http.HttpStatus.CONFLICT;

/** Rejects real business writes that carry a validated contextual-authoring session. */
@Component
@RequiredArgsConstructor
public class AuthoringBusinessWriteInterceptor implements HandlerInterceptor {

    public static final String SESSION_HEADER = "X-Aura-Authoring-Session";
    public static final String DENIAL_REASON = "authoring.preview.business-write-denied";

    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final String AUTHORING_API_PREFIX = "/api/authoring/";

    private final AuthoringWorkspaceService workspaceService;

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) {
        String sessionPid = request.getHeader(SESSION_HEADER);
        if (sessionPid == null || sessionPid.isBlank()
                || READ_METHODS.contains(request.getMethod())
                || request.getRequestURI().startsWith(AUTHORING_API_PREFIX)) {
            return true;
        }

        workspaceService.get(sessionPid);
        throw new ResponseStatusException(CONFLICT, DENIAL_REASON);
    }
}
