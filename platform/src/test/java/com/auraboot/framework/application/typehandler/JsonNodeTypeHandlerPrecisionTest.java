package com.auraboot.framework.application.typehandler;

import org.junit.jupiter.api.Test;
import java.sql.ResultSet;
import java.math.BigDecimal;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JsonNodeTypeHandlerPrecisionTest {
    @Test
    void jsonNodeReadPreservesDatabaseDecimalPrecision() throws Exception {
        ResultSet rows = mock(ResultSet.class);
        when(rows.getString("payload")).thenReturn("{\"value\":1234567890.12345678901234567890}");
        var actual = new JsonNodeTypeHandler().getNullableResult(rows, "payload");
        assertThat(actual.get("value").decimalValue()).isEqualByComparingTo(
                new BigDecimal("1234567890.12345678901234567890"));
    }

    @Test
    void genericMapReadRetainsExistingNumberContract() throws Exception {
        ResultSet rows = mock(ResultSet.class);
        when(rows.getString("payload")).thenReturn("{\"value\":1.25}");
        var handler = new GenericJacksonTypeHandler<Map>(Map.class) {};
        assertThat(handler.getNullableResult(rows, "payload").get("value"))
                .isInstanceOf(Double.class).isEqualTo(1.25);
    }
}
