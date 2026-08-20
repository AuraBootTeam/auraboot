package com.auraboot.framework.authoring.workspace;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Authoritative clock for persisted authoring sessions and writer leases.
 *
 * <p>Lease decisions must not depend on an application node's wall clock because
 * multiple nodes may have different offsets or experience a clock step. PostgreSQL
 * is already the compare-and-set authority for lease writes, so it is also the
 * single time authority for deadlines and in-process validation.</p>
 */
@Component
public class AuthoringDatabaseClock {

    private final JdbcTemplate jdbcTemplate;

    public AuthoringDatabaseClock(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Instant now() {
        Timestamp timestamp = jdbcTemplate.queryForObject(
                "SELECT clock_timestamp()", Timestamp.class);
        if (timestamp == null) {
            throw new IllegalStateException("authoring.database-clock.unavailable");
        }
        return timestamp.toInstant();
    }
}
