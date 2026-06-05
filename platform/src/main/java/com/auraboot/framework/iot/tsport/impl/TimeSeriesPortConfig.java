package com.auraboot.framework.iot.tsport.impl;

import com.auraboot.framework.plugin.extension.iot.TimeSeriesPort;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Spring config for the TDengine {@link TimeSeriesPort} impl.
 *
 * <p>Activated by {@code iot.tdengine.enabled=true}. When activated,
 * {@code iot.tdengine.url} MUST be supplied — its absence is a fail-fast
 * boot error (Spring will fail to resolve the {@code @Value} placeholder
 * and the application context will not start). This is intentional: any
 * deploy that opts into IoT but forgets to point at TDengine should crash
 * loudly rather than silently miss every device sample.
 *
 * <p>Plugins that need TSDB write/read use
 * {@code @Autowired(required = false) TimeSeriesPort port;}. On deploys
 * where {@code iot.tdengine.enabled=false} (default) no bean is registered,
 * the field stays {@code null}, and the plugin degrades per its documented
 * fallback (DLQ batch / skip telemetry / metric only).
 *
 * <p>Bears its own HikariCP pool instead of riding on Spring Boot's primary
 * DataSource — that one is PostgreSQL (the platform metadata store) and
 * cross-vendor JDBC URL contention would otherwise force the wrong driver
 * to load. Pool size defaults are conservative (10) and tunable.
 *
 * @since 2.6.1
 */
@Configuration
@ConditionalOnProperty(name = "iot.tdengine.enabled", havingValue = "true")
public class TimeSeriesPortConfig {

    /**
     * Explicit @Primary PostgreSQL DataSource (the platform metadata store).
     *
     * <p><b>Why this is required:</b> declaring {@link #tdengineDataSource} as a
     * {@code DataSource} bean makes Spring Boot's {@code DataSourceAutoConfiguration}
     * back off ({@code @ConditionalOnMissingBean(DataSource.class)}) — so the platform
     * would otherwise be left with ONLY the TDengine DataSource and every MyBatis
     * metadata query would hit TDengine and fail. We therefore pin the PG DataSource
     * here, primary, from the standard {@code spring.datasource.*} properties, so it
     * stays the default for all platform persistence while TDengine is used only via
     * the {@code @Qualifier("tdengineDataSource")} injection point. (Inactive unless
     * {@code iot.tdengine.enabled=true}, so OSS/community deploys keep auto-config.)
     */
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties primaryDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(
            @Qualifier("primaryDataSourceProperties") DataSourceProperties props) {
        return props.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean(name = "tdengineDataSource", destroyMethod = "close")
    @ConditionalOnMissingBean(name = "tdengineDataSource")
    public DataSource tdengineDataSource(
            @Value("${iot.tdengine.url}") String url,
            @Value("${iot.tdengine.username:root}") String username,
            @Value("${iot.tdengine.password:taosdata}") String password,
            @Value("${iot.tdengine.maxPoolSize:10}") int maxPoolSize) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(username);
        cfg.setPassword(password);
        cfg.setMaximumPoolSize(maxPoolSize);
        cfg.setPoolName("iot-tdengine-pool");
        // taos-jdbcdriver does not tolerate auto-commit toggling.
        cfg.setAutoCommit(true);
        return new HikariDataSource(cfg);
    }

    @Bean
    @ConditionalOnMissingBean(TimeSeriesPort.class)
    public TimeSeriesPort tdengineTimeSeriesPort(
            @Qualifier("tdengineDataSource") DataSource ds) {
        TDengineTimeSeriesPort port = new TDengineTimeSeriesPort(ds);
        port.ensureSuperTable();
        return port;
    }
}
