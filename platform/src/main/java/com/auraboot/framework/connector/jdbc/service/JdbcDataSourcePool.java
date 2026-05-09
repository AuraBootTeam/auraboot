package com.auraboot.framework.connector.jdbc.service;

import com.auraboot.framework.connector.jdbc.entity.JdbcConnector;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * HikariCP pool cache keyed by connector pid. The {@code connector} argument
 * MUST carry an already-DECRYPTED password — callers (the service layer) are
 * responsible for decrypting before invoking {@link #acquire(JdbcConnector)}.
 */
@Slf4j
@Component
public class JdbcDataSourcePool {

    private final ConcurrentHashMap<String, HikariDataSource> pools = new ConcurrentHashMap<>();

    public HikariDataSource acquire(JdbcConnector connector) {
        return pools.computeIfAbsent(connector.getPid(), pid -> build(connector));
    }

    public void evict(String pid) {
        HikariDataSource ds = pools.remove(pid);
        if (ds != null) {
            ds.close();
            log.info("JdbcDataSourcePool evicted pool for pid={}", pid);
        }
    }

    @PreDestroy
    public void shutdown() {
        pools.values().forEach(HikariDataSource::close);
        pools.clear();
        log.info("JdbcDataSourcePool shutdown");
    }

    private HikariDataSource build(JdbcConnector c) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(c.getJdbcUrl());
        cfg.setUsername(c.getUsername());
        cfg.setPassword(c.getPassword());
        int max = c.getMaxPoolSize() == null ? 5 : Math.max(1, Math.min(50, c.getMaxPoolSize()));
        cfg.setMaximumPoolSize(max);
        cfg.setMinimumIdle(1);
        cfg.setConnectionTimeout(c.getConnectionTimeoutMs() == null ? 30000 : c.getConnectionTimeoutMs());
        cfg.setValidationTimeout(5000);
        cfg.setIdleTimeout(600000);
        cfg.setMaxLifetime(1800000);
        cfg.setPoolName("jdbc-connector-" + c.getPid());
        return new HikariDataSource(cfg);
    }
}
