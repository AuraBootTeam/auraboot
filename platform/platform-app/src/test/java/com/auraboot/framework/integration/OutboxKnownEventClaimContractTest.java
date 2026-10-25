package com.auraboot.framework.integration;

import com.auraboot.framework.meta.mapper.OutboxEventMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Real MyBatis annotation parser/BoundSql; does not execute PostgreSQL lease/ordering semantics. */
class OutboxKnownEventClaimContractTest {
    private static BoundSql known(Map<String,Object> parameters) {
        Configuration configuration = new Configuration();
        configuration.addMapper(OutboxEventMapper.class);
        return configuration.getMappedStatement(OutboxEventMapper.class.getName()+".claimKnownEvent").getBoundSql(parameters);
    }
    private static Map<String,Object> parameters() {
        return Map.of("tenantId",31L,"eventId","owned-event", "leaseOwner","owned-attempt", "leaseToken","lease", "leaseUntil",Instant.EPOCH);
    }
    @Test void candidateAndUpdateBothRequireExactTenantAndEventAndOnlyOneRow() {
        String sql=known(parameters()).getSql();
        assertThat(sql).contains("WHERE o.tenant_id = ? AND o.event_id = ?", "WHERE o.id = c.id AND o.tenant_id = ? AND o.event_id = ?", "LIMIT 1),", "RETURNING o.*");
        assertThat(known(parameters()).getParameterMappings().stream().map(p->p.getProperty()).toList())
            .containsExactly("tenantId","eventId","leaseOwner","leaseToken","leaseUntil","tenantId","eventId");
    }
    @Test void pendingMustBeDueAndProcessingMustHaveExpiredItsExistingLease() {
        String sql=known(parameters()).getSql();
        assertThat(sql).contains("((o.status = 'pending' AND o.next_retry_at <= NOW()) OR (o.status = 'processing' AND o.lease_until < NOW()))");
        assertThat(sql).doesNotContain("status IN ('pending', 'processing') WHERE");
    }
    @Test void tenantLocalOrderingPredecessorAndLockingArePreserved() {
        String sql=known(parameters()).getSql();
        assertThat(sql).contains("o.ordering_key IS NULL OR NOT EXISTS", "earlier.tenant_id = o.tenant_id",
            "earlier.ordering_key = o.ordering_key", "earlier.event_sequence < o.event_sequence",
            "earlier.status IN ('pending', 'processing')", "FOR UPDATE SKIP LOCKED LIMIT 1");
    }
    @Test void leaseAndProcessingStateAreEstablishedInSameUpdateNotForgedByCallerBody() {
        String sql=known(parameters()).getSql();
        assertThat(sql).contains("UPDATE ab_outbox o SET retry_count = CASE WHEN o.status = 'processing'",
                "THEN LEAST(o.retry_count::bigint + 1, 2147483647)::integer ELSE o.retry_count END",
                "THEN 'failed' ELSE 'processing' END", "THEN NULL ELSE ? END", "THEN NULL ELSE ?::timestamptz END", "claimed_at = NOW()");
        assertThat(sql).contains("FROM claimed WHERE status = 'failed' ON CONFLICT (outbox_id)",
                "INSERT INTO ab_integration_dead_letter", "SELECT * FROM claimed");
        assertThat(sql).doesNotContain("payload =", "headers =", "command_code =", "event_source =");
    }
    @Test void hostileIdentityValuesRemainBoundParametersAndDoNotChangeSqlOrScope() {
        var hostile=new java.util.HashMap<>(parameters());hostile.put("eventId","x' OR true --");hostile.put("tenantId",32L);
        BoundSql actual=known(hostile);
        assertThat(actual.getSql()).isEqualTo(known(parameters()).getSql()).doesNotContain("x' OR true --");
        assertThat(actual.getParameterObject()).isSameAs(hostile);
        assertThat(actual.getParameterMappings().stream().map(p->p.getProperty()).toList()).doesNotContain("limit", "model", "command", "payload");
    }
    @Test void trustedCounterLockBindsExactActiveLeaseAndRetainsItForTransaction() {
        Configuration configuration=new Configuration();configuration.addMapper(OutboxEventMapper.class);
        BoundSql lock=configuration.getMappedStatement(OutboxEventMapper.class.getName()+".lockActiveDeliveryLease").getBoundSql(parameters());
        assertThat(lock.getSql()).contains("tenant_id = ? AND event_id = ?", "status = 'processing' AND lease_token = ? AND lease_until > clock_timestamp() FOR UPDATE");
        assertThat(lock.getParameterMappings().stream().map(p->p.getProperty()).toList()).containsExactly("tenantId","eventId","leaseToken");
        assertThat(lock.getSql()).doesNotContain("SKIP LOCKED", "UPDATE ab_outbox", "headers =", "payload =");
    }
    @Test void currentLeasePostCheckUsesWallClockAfterTheTransactionStart() {
        Configuration configuration=new Configuration();configuration.addMapper(OutboxEventMapper.class);
        BoundSql count=configuration.getMappedStatement(OutboxEventMapper.class.getName()+".countActiveDeliveryLease").getBoundSql(parameters());
        assertThat(count.getSql()).contains("tenant_id = ? AND event_id = ?", "status = 'processing' AND lease_token = ? AND lease_until > clock_timestamp()");
        assertThat(count.getSql()).doesNotContain("NOW()", "CURRENT_TIMESTAMP", "statement_timestamp()", "FOR UPDATE", "UPDATE ab_outbox");
        assertThat(count.getParameterMappings().stream().map(p->p.getProperty()).toList()).containsExactly("tenantId","eventId","leaseToken");
        var hostile=new java.util.HashMap<>(parameters());hostile.put("leaseToken","x' OR TRUE --");
        assertThat(configuration.getMappedStatement(OutboxEventMapper.class.getName()+".countActiveDeliveryLease").getBoundSql(hostile).getSql()).isEqualTo(count.getSql()).doesNotContain("x' OR TRUE --");
    }
    @Test void globalAndExactClaimsRetainTheirScopesAndShareTheCrashBudget() {
        Configuration configuration=new Configuration();configuration.addMapper(OutboxEventMapper.class);
        String global=configuration.getMappedStatement(OutboxEventMapper.class.getName()+".claimReadyEvents")
            .getBoundSql(Map.of("limit",50,"leaseOwner","worker","leaseToken","lease","leaseUntil",Instant.EPOCH)).getSql();
        assertThat(global).contains("WITH candidates AS", "LIMIT ?").doesNotContain("o.event_id = ?");
        assertThat(known(parameters()).getSql()).contains("WITH candidate AS").doesNotContain("LIMIT ?");
        for (String sql : List.of(global, known(parameters()).getSql())) {
            assertThat(sql).contains("o.retry_count::bigint + CASE WHEN o.status = 'processing' THEN 1 ELSE 0 END >= o.max_retries",
                    "FROM claimed WHERE status = 'failed' ON CONFLICT (outbox_id)");
        }
    }

    @Test void tenantScopedBatchBindsSourceTypesAndTenantWithoutLegacyFallback() {
        Configuration configuration = new Configuration(); configuration.addMapper(OutboxEventMapper.class);
        var parameters = Map.<String,Object>of("tenantId",31L,"source","commerce","eventTypes",List.of("commerce.a","commerce.b"),
                "limit",2,"leaseOwner","owner","leaseToken","token","leaseUntil",Instant.EPOCH);
        var statement = configuration.getMappedStatement(OutboxEventMapper.class.getName()+".claimReadyEventsForTenant");
        BoundSql bound = statement.getBoundSql(parameters);
        assertThat(bound.getSql()).contains("o.tenant_id = ? AND o.event_source = ? AND o.schema_version IS NOT NULL",
                "o.event_type IN", "WHERE o.id = c.id AND o.tenant_id = ? AND o.event_source = ?", "FOR UPDATE SKIP LOCKED LIMIT ?");
        assertThat(bound.getSql()).contains("THEN LEAST(o.retry_count::bigint + 1, 2147483647)::integer ELSE o.retry_count END",
                "FROM claimed WHERE status = 'failed' ON CONFLICT (outbox_id)");
        var hostile = new java.util.HashMap<>(parameters);
        hostile.put("source", "x' OR true --"); hostile.put("eventTypes", List.of("x' OR true --", "other"));
        assertThat(statement.getBoundSql(hostile).getSql()).isEqualTo(bound.getSql()).doesNotContain("x' OR true --");
        assertThat(bound.getParameterMappings().stream().map(value -> value.getProperty()).toList())
                .contains("tenantId", "source", "limit", "leaseOwner", "leaseToken", "leaseUntil");
    }

    @Test void terminalAndRetryWritesRequireTheUnexpiredCurrentLease() {
        Configuration configuration = new Configuration(); configuration.addMapper(OutboxEventMapper.class);
        var values = Map.<String,Object>of("id",1L,"leaseToken","token","nextRetryAt",Instant.EPOCH,"lastError","error");
        for (String method : List.of("markDeliveredFenced", "recordFailureFenced")) {
            String sql = configuration.getMappedStatement(OutboxEventMapper.class.getName()+"."+method).getBoundSql(values).getSql();
            assertThat(sql).contains("WHERE id = ? AND status = 'processing' AND lease_token = ?", "lease_until > clock_timestamp()");
        }
    }
}
