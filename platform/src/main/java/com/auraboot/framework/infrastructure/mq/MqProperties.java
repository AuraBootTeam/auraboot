package com.auraboot.framework.infrastructure.mq;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the message queue abstraction layer.
 */
@Data
@ConfigurationProperties(prefix = "aura.mq")
public class MqProperties {

    /** MQ provider type: memory | redis | kafka | rabbitmq. */
    private String type = "memory";

    private Redis redis = new Redis();
    private Kafka kafka = new Kafka();
    private RabbitMQ rabbitmq = new RabbitMQ();

    @Data
    public static class Redis {
        /** Consumer group name for this application instance. */
        private String consumerGroup = "aura-group";
    }

    @Data
    public static class Kafka {
        private String bootstrapServers = "localhost:9092";
        private String consumerGroup = "aura-group";
        private DeadLetter deadLetter = new DeadLetter();
        private SchemaRegistry schemaRegistry = new SchemaRegistry();

        @Data
        public static class DeadLetter {
            /** Enable DLQ routing for consumer handler failures. */
            private boolean enabled = true;
            /** Suffix appended to original topic to form the DLT topic. */
            private String topicSuffix = ".DLT";
            /** Number of delivery attempts (including the first) before routing to DLT. */
            private int maxAttempts = 3;
        }

        @Data
        public static class SchemaRegistry {
            /**
             * Schema registry URL (e.g. Apicurio). When blank, plain string mode is used (default).
             * Concrete clients are wired via the {@code KafkaSchemaRegistryClient} SPI.
             */
            private String url = "";
        }
    }

    @Data
    public static class RabbitMQ {
        private String host = "localhost";
        private int port = 5672;
        private String username = "guest";
        private String password = "guest";
        private String exchange = "aura-exchange";
    }
}
