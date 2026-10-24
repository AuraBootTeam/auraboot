package com.auraboot.framework.workbench.service;

import com.fasterxml.jackson.databind.*;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AnnouncementCodecTest {
    @Test void reusableWriterPreservesExistingCanonicalBytesWithoutMutatingTheSharedMapper() throws Exception {
        var mapper=new ObjectMapper().findAndRegisterModules();
        var input=Map.of("z",Instant.parse("2026-10-25T09:00:00Z"),"a",Map.of("z",2,"a",1));
        String previous=mapper.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(input);
        var codec=new AnnouncementCodec(mapper);
        assertThat(codec.encode(input)).isEqualTo(previous);
        assertThat(codec.hash(input)).isEqualTo(codec.hash(Map.of("a",Map.of("a",1,"z",2),"z",input.get("z"))));
        assertThat(mapper.isEnabled(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)).isTrue();
        assertThat(mapper.isEnabled(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)).isFalse();
    }
}
