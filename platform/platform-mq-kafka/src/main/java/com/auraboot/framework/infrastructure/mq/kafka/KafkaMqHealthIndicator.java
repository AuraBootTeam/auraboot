package com.auraboot.framework.infrastructure.mq.kafka;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.auraboot.framework.infrastructure.mq.MqProperties;

import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Kafka-specific {@link org.springframework.boot.actuate.health.HealthIndicator}.
 * <p>
 * Probes the broker via an {@link AdminClient#describeCluster} call with a 5s timeout.
 * Activated only when {@code aura.mq.type=kafka}. Reports {@code DOWN} on any
 * exception or timeout (the platform {@code MqHealthIndicator} is intentionally
 * coarse and stays {@code UP} based on bean presence; this indicator is the
 * real liveness probe for production Kafka deployments).
 * </p>
 */
@Slf4j
@Component("kafkaMq")
@ConditionalOnProperty(name = "aura.mq.type", havingValue = "kafka")
public class KafkaMqHealthIndicator extends AbstractHealthIndicator {

    private static final long PROBE_TIMEOUT_SECONDS = 5L;

    private final String bootstrapServers;

    public KafkaMqHealthIndicator(MqProperties properties) {
        super("Kafka MQ health check failed");
        this.bootstrapServers = properties.getKafka().getBootstrapServers();
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        Properties adminProps = new Properties();
        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        adminProps.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG,
                (int) Duration.ofSeconds(PROBE_TIMEOUT_SECONDS).toMillis());
        adminProps.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG,
                (int) Duration.ofSeconds(PROBE_TIMEOUT_SECONDS).toMillis());

        try (AdminClient admin = AdminClient.create(adminProps)) {
            DescribeClusterResult cluster = admin.describeCluster(
                    new DescribeClusterOptions().timeoutMs((int) Duration.ofSeconds(PROBE_TIMEOUT_SECONDS).toMillis()));
            String clusterId = cluster.clusterId().get(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            int nodeCount = cluster.nodes().get(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS).size();

            builder.up()
                    .withDetail("provider", "KafkaMqProvider")
                    .withDetail("bootstrapServers", bootstrapServers)
                    .withDetail("clusterId", clusterId == null ? "<unknown>" : clusterId)
                    .withDetail("nodeCount", nodeCount);
        } catch (Exception ex) {
            log.warn("Kafka health probe failed: {}", ex.getMessage());
            builder.down(ex)
                    .withDetail("provider", "KafkaMqProvider")
                    .withDetail("bootstrapServers", bootstrapServers);
        }
    }
}
