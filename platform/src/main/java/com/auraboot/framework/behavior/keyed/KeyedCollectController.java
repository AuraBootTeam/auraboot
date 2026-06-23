package com.auraboot.framework.behavior.keyed;

import com.auraboot.framework.behavior.dto.CollectRequest;
import com.auraboot.framework.behavior.service.BehaviorCollectService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Public, unauthenticated anonymous ingestion (SP2; SoT §5.3 (a)). A published low-code app
 * embeds a public {@code abk_} site key; the visitor's browser posts events here with no JWT.
 * The server — never the client — resolves the owning tenant from the key, runs the
 * abuse-protection {@link KeyedCollectGuard}, then ingests as anonymous (user null, client
 * anonId only). The authenticated {@code /api/collect} is unchanged.
 */
@RestController
@RequestMapping("/api/collect/keyed")
@RequiredArgsConstructor
public class KeyedCollectController {

    private final KeyedCollectGuard guard;
    private final BehaviorCollectService behaviorCollectService;

    @PostMapping
    public Map<String, Object> collect(@RequestHeader(value = "X-Site-Key", required = false) String siteKey,
                                       @RequestBody CollectRequest request,
                                       HttpServletRequest http) {
        long tenantId = guard.check(siteKey, originOf(http), clientIpOf(http), request.getEvents());
        int accepted = behaviorCollectService.recordAnonymous(request.getEvents(), tenantId);
        return Map.of("accepted", accepted);
    }

    private static String originOf(HttpServletRequest http) {
        String origin = http.getHeader("Origin");
        return origin != null ? origin : http.getHeader("Referer");
    }

    private static String clientIpOf(HttpServletRequest http) {
        String xff = http.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return http.getRemoteAddr();
    }
}
