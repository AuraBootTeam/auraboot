package com.auraboot.framework.permission.service;

import com.auraboot.framework.meta.exception.MetaServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

class BusinessGrantFieldScopeTest {
    private final ObjectMapper json=new ObjectMapper();

    @Test void sqlNullAloneRetainsLegacySemantics() {
        assertThat(BusinessGrantFieldScope.parse(json,null,"receipt").unrestricted()).isTrue();
        var fields=BusinessGrantFieldScope.parse(json,"{\"modelCode\":\"receipt\",\"readFields\":[],\"writeFields\":[]}","receipt");
        assertThat(fields.unrestricted()).isFalse();assertThat(fields.writable()).isEmpty();
        assertThatThrownBy(()->fields.writable().add("cost")).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings={"null","[]","{}","{broken}",
            "{\"modelCode\":\"other\",\"readFields\":[],\"writeFields\":[]}",
            "{\"modelCode\":\"receipt\",\"readFields\":[],\"writeFields\":[\"cost\",\"cost\"]}",
            "{\"modelCode\":\"receipt\",\"readFields\":[],\"writeFields\":[\"*\"]}",
            "{\"modelCode\":\"receipt\",\"readFields\":[],\"writeFields\":[],\"unknown\":true}",
            "{\"modelCode\":\"receipt\",\"readFields\":[],\"writeFields\":null}"})
    void malformedOrWrongModelNeverBecomesUnrestricted(String raw) {
        assertThatThrownBy(()->BusinessGrantFieldScope.parse(json,raw,"receipt")).isInstanceOf(MetaServiceException.class);
    }
}
