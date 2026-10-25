package com.auraboot.framework.integration;

import com.auraboot.framework.meta.entity.OutboxEvent;
import com.auraboot.framework.meta.mapper.OutboxEventMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** Commit delivery ownership before independent consumer transactions lock the same lease row. */
@Service
@RequiredArgsConstructor
public class ReliableIntegrationClaimService {
    private final OutboxEventMapper mapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<OutboxEvent> claimGlobal(int limit, String owner, String token, Instant until) {
        return mapper.claimReadyEvents(limit, owner, token, until);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<OutboxEvent> claimTenant(long tenantId, String source, List<String> types, int limit,
                                        String owner, String token, Instant until) {
        return mapper.claimReadyEventsForTenant(tenantId, source, types, limit, owner, token, until);
    }
}
