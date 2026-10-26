package com.auraboot.framework.meta.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DomainExportPreparedTest {
    @Test void precisePersistedEvidenceIsNotCoercedByBeanConversion() throws Exception {
        var precise=new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
        var stored=precise.readTree("""
            {"reference":"snapshot","memberPid":"original","locale":"en-US","fileName":"statistics.csv","format":"csv",
             "rowCount":1,"evidence":{"readRate":0.0,"acknowledgementRate":100.0,"unknown":null,"digest":"fixed"}}
            """);
        // The default bean conversion changes a precise DecimalNode even though the JSONB handler preserved it.
        var converted=new ObjectMapper().convertValue(stored,DomainExportSource.Prepared.class);
        assertThat(converted.evidence().toString()).isNotEqualTo(stored.required("evidence").toString());
        var prepared=DomainExportSource.Prepared.fromJson(stored);
        assertThat(prepared.evidence()).isSameAs(stored.required("evidence"));
        assertThat(prepared.evidence().toString()).contains("\"readRate\":0.0","\"acknowledgementRate\":100.0","\"unknown\":null");
        assertThat(prepared.rowCount()).isEqualTo(1);
    }
    @Test void persistedDescriptorRejectsWrongFieldTypesAndOutOfRangeCounts() throws Exception {
        var json=new ObjectMapper();
        assertThatThrownBy(()->DomainExportSource.Prepared.fromJson(json.readTree("{\"rowCount\":1.5}")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->DomainExportSource.Prepared.fromJson(json.readTree("{\"rowCount\":9223372036854775808}")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->DomainExportSource.Prepared.fromJson(json.readTree("{\"rowCount\":1,\"reference\":123}")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reference");
    }
}
