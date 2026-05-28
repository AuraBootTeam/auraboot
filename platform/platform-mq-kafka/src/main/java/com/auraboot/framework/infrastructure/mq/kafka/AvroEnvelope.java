package com.auraboot.framework.infrastructure.mq.kafka;

import java.time.Instant;
import java.util.Objects;

/**
 * Event envelope for schema-registry backed messages.
 * <p>
 * Carries metadata that lets consumers locate the correct schema before
 * deserializing the {@code payload}. The envelope itself is intentionally
 * format-agnostic — the {@code payload} field may hold an Avro JSON encoding,
 * a JSON-Schema document, or any other serialization the host application
 * agrees on.
 * </p>
 *
 * <p>Wire format suggestion (JSON):</p>
 * <pre>
 * {
 *   "schemaId": 42,
 *   "schemaVersion": 3,
 *   "eventType": "ida.data.row-changed",
 *   "occurredAt": "2026-05-28T10:15:30Z",
 *   "payload": "<encoded body>"
 * }
 * </pre>
 *
 * <p>Plain immutable POJO so it can be (de)serialized by any JSON library
 * the host application already brings (Jackson, Gson, ...). No Jackson
 * annotations are added to avoid forcing the dependency on this module.</p>
 */
public final class AvroEnvelope {

    private final long schemaId;
    private final int schemaVersion;
    private final String eventType;
    private final Instant occurredAt;
    private final String payload;

    public AvroEnvelope(long schemaId,
                        int schemaVersion,
                        String eventType,
                        Instant occurredAt,
                        String payload) {
        this.schemaId = schemaId;
        this.schemaVersion = schemaVersion;
        this.eventType = eventType;
        this.occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        this.payload = payload;
    }

    public long getSchemaId() { return schemaId; }
    public int getSchemaVersion() { return schemaVersion; }
    public String getEventType() { return eventType; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getPayload() { return payload; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AvroEnvelope that)) return false;
        return schemaId == that.schemaId
                && schemaVersion == that.schemaVersion
                && Objects.equals(eventType, that.eventType)
                && Objects.equals(occurredAt, that.occurredAt)
                && Objects.equals(payload, that.payload);
    }

    @Override
    public int hashCode() {
        return Objects.hash(schemaId, schemaVersion, eventType, occurredAt, payload);
    }

    @Override
    public String toString() {
        return "AvroEnvelope{schemaId=" + schemaId
                + ", schemaVersion=" + schemaVersion
                + ", eventType='" + eventType + '\''
                + ", occurredAt=" + occurredAt
                + ", payload.length=" + (payload == null ? 0 : payload.length())
                + '}';
    }
}
