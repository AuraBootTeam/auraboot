package com.auraboot.framework.authoring.workspace;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthoringDatabaseClockTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final AuthoringDatabaseClock databaseClock = new AuthoringDatabaseClock(jdbcTemplate);

    @Test
    void readsTheLivePostgresClockInsteadOfTheApplicationNodeClock() {
        Instant databaseNow = Instant.parse("2042-03-04T05:06:07.123Z");
        when(jdbcTemplate.queryForObject("SELECT clock_timestamp()", Timestamp.class))
                .thenReturn(Timestamp.from(databaseNow));

        assertThat(databaseClock.now()).isEqualTo(databaseNow);
        verify(jdbcTemplate).queryForObject("SELECT clock_timestamp()", Timestamp.class);
    }

    @Test
    void failsClosedWhenTheDatabaseDoesNotReturnTime() {
        when(jdbcTemplate.queryForObject("SELECT clock_timestamp()", Timestamp.class))
                .thenReturn(null);

        assertThatThrownBy(databaseClock::now)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("authoring.database-clock.unavailable");
    }
}
