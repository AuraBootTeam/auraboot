package com.auraboot.framework.behavior.ingest;

import org.apache.avro.Schema;
import org.apache.avro.SchemaCompatibility;
import org.apache.avro.SchemaCompatibility.SchemaCompatibilityType;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.GenericRecordBuilder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BehaviorEventAvroCompatibilityTest {

    private static final Path SCHEMA_DIR = Path.of("src/main/resources/schemas/behavior");

    @Test
    void v2SchemaIsBackwardCompatibleWithV1Payloads() throws Exception {
        Schema v1 = readSchema("behavior-ingest-envelope-v1.avsc");
        Schema v2 = readSchema("behavior-ingest-envelope-v2.avsc");

        assertThat(SchemaCompatibility.checkReaderWriterCompatibility(v2, v1).getType())
                .isEqualTo(SchemaCompatibilityType.COMPATIBLE);

        byte[] oldPayload = encode(v1, envelope(v1, event(v1, "bkf5-old", false)));
        GenericRecord decoded = decode(v1, v2, oldPayload);
        GenericRecord event = firstEvent(decoded);
        assertThat(event.get("eventId").toString()).isEqualTo("bkf5-old");
        assertThat(event.get("traceparent")).isNull();
        assertThat(event.get("partitionKeyKind")).isNull();
    }

    @Test
    void v1ReadersCanIgnoreV2AdditiveFields() throws Exception {
        Schema v1 = readSchema("behavior-ingest-envelope-v1.avsc");
        Schema v2 = readSchema("behavior-ingest-envelope-v2.avsc");

        byte[] newPayload = encode(v2, envelope(v2, event(v2, "bkf5-new", true)));
        GenericRecord decoded = decode(v2, v1, newPayload);
        GenericRecord event = firstEvent(decoded);
        assertThat(event.get("eventId").toString()).isEqualTo("bkf5-new");
        assertThat(event.getSchema().getField("traceparent")).isNull();
    }

    private Schema readSchema(String filename) throws Exception {
        return new Schema.Parser().parse(SCHEMA_DIR.resolve(filename).toFile());
    }

    private GenericRecord envelope(Schema envelopeSchema, GenericRecord event) {
        return new GenericRecordBuilder(envelopeSchema)
                .set("tenantId", 42L)
                .set("userId", 7L)
                .set("events", List.of(event))
                .build();
    }

    private GenericRecord event(Schema envelopeSchema, String eventId, boolean includeV2Fields) {
        Schema eventSchema = envelopeSchema.getField("events").schema().getElementType();
        GenericRecordBuilder builder = new GenericRecordBuilder(eventSchema)
                .set("eventId", eventId)
                .set("schemaVersion", includeV2Fields ? "2" : "1")
                .set("eventName", "page_view")
                .set("eventCategory", "navigation")
                .set("source", "web")
                .set("identityQuality", "anonymous")
                .set("occurredAt", 1_782_144_000_000L)
                .set("anonId", "anon-bkf5")
                .set("clientSessionId", "session-bkf5")
                .set("interactionId", null)
                .set("causedByEventId", null)
                .set("traceId", "trace-bkf5")
                .set("sourceSpanId", "span-bkf5")
                .set("runId", null)
                .set("uiElementId", null)
                .set("appId", "web-admin")
                .set("pageId", "behavior_quarantine_list")
                .set("blockId", null)
                .set("elementCode", null)
                .set("props", Map.of("routeTemplate", "/p/c/behavior_quarantine_list"))
                .set("consentState", "granted")
                .set("consentVersion", "1")
                .set("samplingUnit", "event")
                .set("samplingProbability", "1.00000")
                .set("producerName", "@auraboot/track")
                .set("producerVersion", "1.0.0");
        if (includeV2Fields) {
            builder.set("traceparent", "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01");
            builder.set("partitionKeyKind", "tenant_event");
        }
        return builder.build();
    }

    private byte[] encode(Schema writerSchema, GenericRecord record) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        var writer = new GenericDatumWriter<GenericRecord>(writerSchema);
        var encoder = EncoderFactory.get().binaryEncoder(out, null);
        writer.write(record, encoder);
        encoder.flush();
        return out.toByteArray();
    }

    private GenericRecord decode(Schema writerSchema, Schema readerSchema, byte[] payload) throws Exception {
        var reader = new GenericDatumReader<GenericRecord>(writerSchema, readerSchema);
        return reader.read(null, DecoderFactory.get().binaryDecoder(payload, null));
    }

    @SuppressWarnings("unchecked")
    private GenericRecord firstEvent(GenericRecord envelope) {
        return ((List<GenericRecord>) envelope.get("events")).get(0);
    }
}
