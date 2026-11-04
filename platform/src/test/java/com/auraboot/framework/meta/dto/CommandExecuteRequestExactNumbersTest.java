package com.auraboot.framework.meta.dto;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CommandExecuteRequestExactNumbersTest {
    @Test void nestedDecimalTokensReachCommandPayloadWithoutBinaryFloatingPointRounding() throws Exception {
        var request=new ObjectMapper().readValue("{\"payload\":{\"attributes\":{\"cost\":1234567890123456.78},\"items\":[0.10000000000000001],\"count\":2,\"active\":true}}",CommandExecuteRequest.class);
        assertThat(((Map<?,?>)request.getPayload().get("attributes")).get("cost")).isEqualTo(new BigDecimal("1234567890123456.78"));
        assertThat(((List<?>)request.getPayload().get("items")).getFirst()).isEqualTo(new BigDecimal("0.10000000000000001"));
        assertThat(request.getPayload().get("count")).isEqualTo(2);
        assertThat(request.getPayload().get("active")).isEqualTo(true);
    }
}
