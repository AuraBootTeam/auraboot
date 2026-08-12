package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.SessionView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceRepository.WorkspaceRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthoringWorkspaceViewMapperTest {

    private static final long CURRENT_USER_ID = 42L;

    private final AuthoringDatabaseClock databaseClock = mock(AuthoringDatabaseClock.class);
    private final AuthoringWorkspaceViewMapper mapper =
            new AuthoringWorkspaceViewMapper(databaseClock);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void databaseClockAheadOfTheNodeExpiresOnlyTheLeaseItHasPassed() {
        when(databaseClock.now()).thenReturn(Instant.parse("2600-01-01T00:00:00Z"));

        SessionView view = mapper.toView(row(
                Instant.parse("2700-01-01T00:00:00Z"),
                Instant.parse("2500-01-01T00:00:00Z")), CURRENT_USER_ID);

        assertThat(view.state()).isEqualTo("ACTIVE");
        assertThat(view.writerLease().status()).isEqualTo("EXPIRED");
    }

    @Test
    void databaseClockBehindTheNodeKeepsItsAuthoritativeLeaseActive() {
        when(databaseClock.now()).thenReturn(Instant.parse("2000-01-01T00:00:00Z"));

        SessionView view = mapper.toView(row(
                Instant.parse("2001-01-01T00:00:00Z"),
                Instant.parse("2001-01-01T00:00:00Z")), CURRENT_USER_ID);

        assertThat(view.state()).isEqualTo("ACTIVE");
        assertThat(view.writerLease().status()).isEqualTo("OWNED");
    }

    @Test
    void serializesSnowflakeOwnerIdentityWithoutJavaScriptPrecisionLoss() throws Exception {
        when(databaseClock.now()).thenReturn(Instant.parse("2000-01-01T00:00:00Z"));
        long snowflakeUserId = 345780496019623936L;

        SessionView view = mapper.toView(row(
                Instant.parse("2001-01-01T00:00:00Z"),
                Instant.parse("2001-01-01T00:00:00Z"),
                snowflakeUserId), snowflakeUserId);

        assertThat(objectMapper.writeValueAsString(view))
                .contains("\"ownerUserId\":\"345780496019623936\"");
    }

    private WorkspaceRow row(Instant expiresAt, Instant leasedUntil) {
        return row(expiresAt, leasedUntil, CURRENT_USER_ID);
    }

    private WorkspaceRow row(Instant expiresAt, Instant leasedUntil, long ownerUserId) {
        return new WorkspaceRow(
                11L,
                "session-1",
                1L,
                2L,
                ownerUserId,
                "page-1",
                "ACTIVE",
                "AUTHOR",
                JsonNodeFactory.instance.objectNode(),
                expiresAt,
                1L,
                21L,
                "change-set-1",
                ownerUserId,
                "DRAFT",
                "CONTEXTUAL",
                1L,
                "L0",
                "INLINE",
                "DIRECT",
                "VALID",
                null,
                "READY",
                null,
                "NOT_REQUIRED",
                "UNPUBLISHED",
                "manifest-1",
                31L,
                "resource-1",
                1L,
                JsonNodeFactory.instance.objectNode(),
                "TENANT",
                "TENANT",
                "page-1",
                null,
                41L,
                11L,
                CURRENT_USER_ID,
                1L,
                leasedUntil);
    }
}
