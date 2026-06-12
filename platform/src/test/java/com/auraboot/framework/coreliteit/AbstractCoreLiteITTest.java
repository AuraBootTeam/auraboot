package com.auraboot.framework.coreliteit;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AbstractCoreLiteITTest {

    @Test
    void extractsJwtFromNestedLoginBody() {
        String body = "{\"code\":\"0\",\"data\":{\"jwt\":\"abc.def.ghi\",\"tenantId\":\"42\"}}";
        assertThat(AbstractCoreLiteIT.parseJwt(body)).isEqualTo("abc.def.ghi");
    }

    @Test
    void parsesQuotedTenantIdFromLoginBody() {
        String body = "{\"data\":{\"jwt\":\"x\",\"tenantId\":\"319688643885273088\"}}";
        assertThat(AbstractCoreLiteIT.parseTenantId(body)).isEqualTo("319688643885273088");
    }

    @Test
    void buildsDynamicListPath() {
        assertThat(AbstractCoreLiteIT.dynamicListPath("bom_conversion_task_pcba"))
                .isEqualTo("/api/dynamic/bom_conversion_task_pcba/list");
    }
}
