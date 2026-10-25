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
        assertThat(sql).contains("WHERE o.tenant_id = ? AND o.event_id = ?", "WHERE o.id = c.id AND o.tenant_id = ? AND o.event_id = ?", "LIMIT 1)", "RETURNING o.*");
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
        assertThat(sql).contains("UPDATE ab_outbox o SET status = 'processing', lease_owner = ?, lease_token = ?, lease_until = ?, claimed_at = NOW()");
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
    @Test void globalAndExactClaimsUseTheSameInstallationNamespaceWithoutChangingLeaseOrPayload() {
        Configuration configuration=new Configuration();configuration.addMapper(OutboxEventMapper.class);
        String global=configuration.getMappedStatement(OutboxEventMapper.class.getName()+".claimReadyEvents")
            .getBoundSql(Map.of("limit",50,"leaseOwner","worker","leaseToken","lease","leaseUntil",Instant.EPOCH)).getSql();
        for(String sql:List.of(global,known(parameters()).getSql())) {
            assertThat(sql).contains(OutboxEventMapper.ORDERING_NAMESPACE_MATCH.trim());
            assertThat(sql).contains("earlier.tenant_id = o.tenant_id", "earlier.ordering_key = o.ordering_key", "earlier.event_sequence < o.event_sequence");
            assertThat(sql).doesNotContain("SET event_source", "SET ordering_key", "SET payload", "DELETE FROM");
        }
    }
    @Test void globalClaimContractIsUnchangedAndExactClaimDoesNotFallBackToIt() {
        Configuration configuration=new Configuration();configuration.addMapper(OutboxEventMapper.class);
        String global=configuration.getMappedStatement(OutboxEventMapper.class.getName()+".claimReadyEvents")
            .getBoundSql(Map.of("limit",50,"leaseOwner","worker","leaseToken","lease","leaseUntil",Instant.EPOCH)).getSql();
        assertThat(global).contains("WITH candidates AS", "LIMIT ?").doesNotContain("o.event_id = ?");
        assertThat(known(parameters()).getSql()).contains("WITH candidate AS").doesNotContain("LIMIT ?");
    }
}
