package com.auraboot.framework.agent.integration.observability;

import com.auraboot.framework.agent.trace.entity.GenAiUsageRecord;
import com.auraboot.framework.agent.trace.mapper.GenAiUsageMapper;
import com.auraboot.framework.test.util.OwnedTestDatabase;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.assertThat;

/** Real Postgres happy path and isolation for the usage adapter; no application mocks. */
class LlmUsageBoundaryPostgresIT {
    @Test
    void actualUsageRowsAreProjectedOnlyForTheRequestedTenantAndTrace() throws Exception {
        String url = System.getenv("TEST_DATABASE_URL");
        OwnedTestDatabase.require(url, OwnedTestDatabase.AURABOOT_SLOT_PATTERN,
                "TEST_DATABASE_URL must identify an owned migrated auraboot slot");
        var source = new DriverManagerDataSource(url,
                System.getenv("TEST_DATABASE_USERNAME"), System.getenv("TEST_DATABASE_PASSWORD"));
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(config);
        var sessions = factory.getObject();
        sessions.getConfiguration().addMapper(GenAiUsageMapper.class);
        try (var session = sessions.openSession(false)) {
            var mapper = session.getMapper(GenAiUsageMapper.class);
            String trace = UUID.randomUUID().toString();
            var own = row(7001L, trace, "owned-model");
            mapper.insert(own);
            mapper.insert(row(7002L, trace, "other-tenant"));
            mapper.insert(row(7001L, UUID.randomUUID().toString(), "other-trace"));
            var result = new AgentLlmUsageQuery(mapper).findByTrace(7001L, trace);
            assertThat(result).singleElement().satisfies(value -> {
                assertThat(value.getId()).isEqualTo(own.getId());
                assertThat(value.getTenantId()).isEqualTo(7001L);
                assertThat(value.getTraceId()).isEqualTo(trace);
                assertThat(value.getRequestModel()).isEqualTo("owned-model");
                assertThat(value.getInputTokens()).isEqualTo(11);
                assertThat(value.getAmount()).isEqualByComparingTo("0.012300");
                assertThat(value.getCreatedAt()).isNotNull();
            });
            session.rollback();
        }
    }

    private static GenAiUsageRecord row(long tenant, String trace, String model) {
        var row = new GenAiUsageRecord();
        row.setTenantId(tenant); row.setTraceId(trace);
        row.setProvider("boundary-fixture"); row.setRequestModel(model);
        row.setInputTokens(11); row.setOutputTokens(3);
        row.setAmount(new BigDecimal("0.012300")); row.setCurrency("USD");
        return row;
    }
}
