package com.auraboot.framework.plugin.extension.integration;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class IntegrationEventEnvelopeBoundaryTest {
    @Test
    void everyRequiredIdentityIsValidatedIndependently() {
        for (int index : new int[]{0, 1, 2, 3, 4, 5, 6}) {
            for (String invalid : Arrays.asList(null, "", " ")) {
                String[] fields = {"1.0", "event", "domain.created.v1", "source", "subject", "correlation", "order"};
                fields[index] = invalid;
                assertThatThrownBy(() -> envelope(fields, Instant.EPOCH, 7L, 0L, Map.of(), null))
                        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must not be blank");
            }
        }
    }

    @Test
    void invalidTimeTenantSequenceAndPayloadAreRejectedSeparately() {
        String[] fields = fields();
        assertThatThrownBy(() -> envelope(fields, null, 7L, 0L, Map.of(), null)).isInstanceOf(NullPointerException.class).hasMessage("occurredAt");
        for (long tenant : new long[]{0L, -1L}) {
            assertThatThrownBy(() -> envelope(fields, Instant.EPOCH, tenant, 0L, Map.of(), null)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tenantId");
        }
        assertThatThrownBy(() -> envelope(fields, Instant.EPOCH, 7L, -1L, Map.of(), null)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sequence");
        assertThatThrownBy(() -> envelope(fields, Instant.EPOCH, 7L, 0L, null, null)).isInstanceOf(NullPointerException.class).hasMessage("payload");
        assertThat(envelope(fields, Instant.EPOCH, 7L, 0L, Map.of(), null).headers()).isEmpty();
        assertThat(envelope(fields, Instant.EPOCH, 7L, 0L, Map.of(), Map.of()).headers()).isEmpty();
    }

    @Test
    void acceptsEverySupportedJsonScalarWithoutLosingNumericTypeOrNull() {
        List<Object> scalars = Arrays.asList(null, "text", true, (byte) 1, (short) 2, 3, 4L, 1.5F, 2.5D,
                new BigInteger("12345678901234567890"), new BigDecimal("1.2300"));
        var envelope = envelope(fields(), Instant.EPOCH, 7L, 0L, Map.of("values", scalars), Map.of());
        assertThat(envelope.payload().get("values")).isEqualTo(scalars);
    }

    @Test
    void rejectsNonJsonObjectsAndNonStringKeysWithPreciseNestedPath() {
        assertThatThrownBy(() -> envelope(fields(), Instant.EPOCH, 7L, 0L, Map.of("items", List.of(new Object())), Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("payload.items[0]");
        assertThatThrownBy(() -> envelope(fields(), Instant.EPOCH, 7L, 0L, Map.of("nested", Map.of(42, "value")), Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("payload.nested").hasMessageContaining("string keys");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(null, "value");
        assertThatThrownBy(() -> envelope(fields(), Instant.EPOCH, 7L, 0L, Map.of(), headers))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("headers");
    }

    private static String[] fields() { return new String[]{"1.0", "event", "domain.created.v1", "source", "subject", "correlation", "order"}; }
    private static IntegrationEventEnvelope envelope(String[] f, Instant at, long tenant, long sequence, Map<String,Object> payload, Map<String,String> headers) {
        return new IntegrationEventEnvelope(f[0], f[1], f[2], f[3], f[4], at, tenant, f[5], null, f[6], sequence, payload, headers);
    }
}
