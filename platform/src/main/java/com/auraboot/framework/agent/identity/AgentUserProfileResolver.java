package com.auraboot.framework.agent.identity;

import java.util.Optional;

/**
 * Resolves the per-user agent profile identity used by channel/session and
 * execution-policy gates.
 */
public interface AgentUserProfileResolver {

    Optional<String> resolveProfileId(long tenantId, long userId);
}
