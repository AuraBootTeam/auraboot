package com.auraboot.framework.behavior.controller;

import com.auraboot.framework.behavior.dto.CollectRequest;
import com.auraboot.framework.behavior.service.BehaviorCollectService;
import com.auraboot.framework.permission.annotation.AuthenticatedAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Behavior analytics ingestion endpoint (M1; SoT §5.5). Accepts a batch of client
 * events; the server enriches tenant/user from the auth context and persists them.
 */
@RestController
@RequestMapping("/api/collect")
@RequiredArgsConstructor
@AuthenticatedAccess("authenticated self-scope behavior ingestion; tenant/user derived "
        + "from the auth context. The anonymous site-key variant is /api/collect/keyed.")
public class BehaviorCollectController {

    private final BehaviorCollectService behaviorCollectService;

    @PostMapping
    public Map<String, Object> collect(@RequestBody CollectRequest request) {
        int accepted = behaviorCollectService.record(request.getEvents());
        return Map.of("accepted", accepted);
    }
}
