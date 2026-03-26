package com.auraboot.framework.agent.controller;

import com.auraboot.framework.agent.service.AgentCardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A2A Agent Card discovery endpoints.
 * <p>
 * Exposes publicly accessible metadata about AuraBoot agents following the
 * Agent-to-Agent (A2A) protocol (https://google.github.io/A2A/).
 * <p>
 * These endpoints are intentionally NOT protected by authentication — A2A
 * discovery metadata is public by design (RFC 8615 /.well-known/ convention).
 * Only non-sensitive information (name, description, skills) is exposed; internal
 * fields (system_prompt, soul_profile, API keys) are never included.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AgentDiscoveryController {

    private final AgentCardService agentCardService;

    /**
     * Discovery index — lists all active agents with links to their individual cards.
     *
     * <pre>GET /.well-known/agent.json</pre>
     *
     * @return discovery document: {@code { platform, version, agents: [{name,code,description,cardUrl}] }}
     */
    @GetMapping(value = "/.well-known/agent.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> agentDiscovery() {
        log.debug("A2A discovery document requested");
        Map<String, Object> document = agentCardService.generateDiscoveryDocument();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePublic())
                .body(document);
    }

    /**
     * Per-agent A2A card with full capability and skill declarations.
     *
     * <pre>GET /.well-known/agent/{agentCode}.json</pre>
     *
     * @param agentCode the agent's unique code (e.g. {@code tpl_aurabot_internal})
     * @return A2A-compliant agent card, or 404 if the agent is not found / not active
     */
    @GetMapping(value = "/.well-known/agent/{agentCode}.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> agentCard(@PathVariable String agentCode) {
        log.debug("A2A agent card requested: agentCode={}", agentCode);
        Map<String, Object> card = agentCardService.generateAgentCard(agentCode);
        if (card == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePublic())
                .body(card);
    }
}
