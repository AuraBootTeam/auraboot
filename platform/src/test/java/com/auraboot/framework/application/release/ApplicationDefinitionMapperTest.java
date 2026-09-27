package com.auraboot.framework.application.release;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationDefinitionMapperTest {

    @Test
    void globalReleaseQueriesBypassTenantLineRewriting() throws Exception {
        assertTenantLineIgnored("findPublishedStableRelease", String.class);
        assertTenantLineIgnored("findDefinitionComponents", String.class);
    }

    private static void assertTenantLineIgnored(String method, Class<?>... parameterTypes) throws Exception {
        InterceptorIgnore annotation = ApplicationDefinitionMapper.class
                .getMethod(method, parameterTypes)
                .getAnnotation(InterceptorIgnore.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.tenantLine()).isEqualTo("true");
    }
}
