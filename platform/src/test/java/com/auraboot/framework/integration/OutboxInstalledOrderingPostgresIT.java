package com.auraboot.framework.integration;

import com.auraboot.framework.meta.mapper.OutboxEventMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Exact production MyBatis lease SQL against owned PostgreSQL; fixtures and claims always roll back. */
@EnabledIfEnvironmentVariable(named="OUTBOX_INSTALLED_ORDERING_NATIVE", matches="true")
class OutboxInstalledOrderingPostgresIT {
    @Test void differentInstallationsDoNotBlockTheSameExternalDocumentIdentity() throws Exception {
        assertClaim("open-platform/install-a/orders", "open-platform/install-b/orders", true);
    }
    @Test void sameInstallationRetainsOrderingAcrossSyncAndDeletionSources() throws Exception {
        assertClaim("open-platform/install-a/orders", "open-platform/install-a/deletions", false);
    }
    @Test void sameInstallationAndSourceRetainOrdering() throws Exception {
        assertClaim("open-platform/install-a/orders", "open-platform/install-a/orders", false);
    }
    @Test void domainPublishersRetainTheirSharedAggregateOrdering() throws Exception {
        assertClaim("inventory", "procurement", false);
    }
    @Test void legacyEnvelopesRetainTheirSharedAggregateOrdering() throws Exception {
        assertClaim(null, null, false);
    }
    @Test void externalInstallationDoesNotShareTheLocalDomainNamespace() throws Exception {
        assertClaim("open-platform/install-a/orders", "procurement", true);
    }
    private void assertClaim(String predecessorSource, String candidateSource, boolean expectedClaim) throws Exception {
        String url=System.getenv("TEST_DATABASE_URL");
        assertNotNull(url);assertTrue(url.startsWith("jdbc:postgresql://"));
        var address=java.net.URI.create(url.substring(5));assertEquals("127.0.0.1",address.getHost());
        assertEquals("/enterprise_22",address.getPath());assertTrue(address.getPort()>0);assertNull(address.getUserInfo());
        String prefix="installed-ordering-"+UUID.randomUUID();
        try(Connection connection=DriverManager.getConnection(url,System.getenv("TEST_DATABASE_USERNAME"),System.getenv("TEST_DATABASE_PASSWORD"))) {
            connection.setAutoCommit(false);
            try {
                long tenant=370910593529548800L;String ordering=prefix+"/purchase-order/700000001";
                insert(connection,tenant,prefix+"-earlier",predecessorSource,ordering,1,false);
                insert(connection,tenant,prefix+"-candidate",candidateSource,ordering,2,true);
                Configuration configuration=new Configuration();configuration.addMapper(OutboxEventMapper.class);
                Map<String,Object> parameters=Map.of("tenantId",tenant,"eventId",prefix+"-candidate","leaseOwner",prefix,"leaseToken",prefix,"leaseUntil",Instant.now().plusSeconds(30));
                var bound=configuration.getMappedStatement(OutboxEventMapper.class.getName()+".claimKnownEvent").getBoundSql(parameters);
                try(var statement=connection.prepareStatement(bound.getSql())) {
                    int index=1;
                    for(var mapping:bound.getParameterMappings()) {
                        Object value=parameters.get(mapping.getProperty());
                        if(value instanceof Instant instant)value=Timestamp.from(instant);
                        statement.setObject(index++,value);
                    }
                    try(var result=statement.executeQuery()) {
                        assertEquals(expectedClaim,result.next(),"Installation namespace must preserve only its own predecessor");
                        if(expectedClaim) {
                            assertEquals(prefix+"-candidate",result.getString("event_id"));assertEquals("processing",result.getString("status"));
                            assertEquals(prefix,result.getString("lease_owner"));assertEquals(prefix,result.getString("lease_token"));assertFalse(result.next());
                        }
                    }
                }
                try(var statement=connection.prepareStatement("SELECT status,lease_token FROM ab_outbox WHERE tenant_id=? AND event_id=?")) {
                    statement.setLong(1,tenant);statement.setString(2,prefix+"-earlier");
                    try(var result=statement.executeQuery()){assertTrue(result.next());assertEquals("pending",result.getString(1));assertNull(result.getString(2));assertFalse(result.next());}
                }
            } finally {connection.rollback();}
        }
    }
    private void insert(Connection connection,long tenant,String event,String source,String ordering,long sequence,boolean due) throws Exception {
        try(var statement=connection.prepareStatement("INSERT INTO ab_outbox (tenant_id,event_id,event_type,event_source,ordering_key,event_sequence,payload,status,retry_count,max_retries,next_retry_at,created_at) VALUES (?,?,?, ?,?,?, '{}'::jsonb,'pending',0,10,?,NOW())")) {
            statement.setLong(1,tenant);statement.setString(2,event);statement.setString(3,"native.installed-ordering.test");statement.setString(4,source);statement.setString(5,ordering);statement.setLong(6,sequence);statement.setTimestamp(7,Timestamp.from(Instant.now().plusSeconds(due?-60:3600)));assertEquals(1,statement.executeUpdate());
        }
    }
}
