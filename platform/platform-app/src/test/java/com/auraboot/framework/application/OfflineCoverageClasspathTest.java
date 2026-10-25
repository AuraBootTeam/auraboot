package com.auraboot.framework.application;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class OfflineCoverageClasspathTest {

    @Test
    void applicationMetadataHasOneResourceOrigin() throws IOException {
        assertSingleResource("com/auraboot/framework/application/MetaApplication.class");
    }

    @Test
    void mapperMetadataHasOneResourceOrigin() throws IOException {
        assertSingleResource("com/auraboot/framework/scheduler/mapper/ScheduledTaskLogMapper.class");
        assertSingleResource("com/auraboot/framework/connector/airflow/mapper/AirflowWebhookLogMapper.class");
    }

    @Test
    void offlineProbesAndSqlParserRemainAvailable() throws ClassNotFoundException {
        assertThat(Arrays.stream(MetaApplication.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals("$jacocoInit"))).isTrue();
        assertThat(Class.forName("net.sf.jsqlparser.parser.CCJSqlParserTokenManager", false,
                getClass().getClassLoader())).isNotNull();
    }

    private void assertSingleResource(String resource) throws IOException {
        var origins = Collections.list(getClass().getClassLoader().getResources(resource));
        assertThat(origins).as("Classpath origins for %s", resource).hasSize(1);
    }
}
