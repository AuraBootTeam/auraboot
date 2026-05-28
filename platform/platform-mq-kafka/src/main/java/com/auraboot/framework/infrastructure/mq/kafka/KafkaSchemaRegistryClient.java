package com.auraboot.framework.infrastructure.mq.kafka;

/**
 * SPI for integrating with a Kafka schema registry (e.g. Apicurio, Confluent).
 * <p>
 * The platform ships only a NOOP default implementation to avoid forcing a heavy
 * Apicurio / Confluent client dependency on downstream applications. Concrete
 * implementations are supplied by the host application via Spring beans.
 * </p>
 *
 * <p>When the registry URL is blank (default), {@link Noop} is used and messages
 * are exchanged as plain UTF-8 strings.</p>
 */
public interface KafkaSchemaRegistryClient {

    /**
     * Register (or look up) a schema by subject. Implementations may cache.
     *
     * @param subject       schema subject (typically {@code <topic>-value})
     * @param schemaContent schema definition (Avro JSON, JSON Schema, etc.)
     * @return globally unique schema id
     */
    long registerSchema(String subject, String schemaContent);

    /**
     * Fetch a schema by id (for consumer-side deserialization).
     *
     * @param schemaId schema id from a record envelope
     * @return schema content
     */
    String fetchSchema(long schemaId);

    /**
     * No-op default that allows the Kafka MQ provider to run without a registry.
     */
    final class Noop implements KafkaSchemaRegistryClient {
        @Override
        public long registerSchema(String subject, String schemaContent) {
            return 0L;
        }

        @Override
        public String fetchSchema(long schemaId) {
            return "";
        }
    }
}
