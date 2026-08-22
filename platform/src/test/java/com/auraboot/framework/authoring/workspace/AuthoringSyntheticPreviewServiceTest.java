package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.SessionView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.SyntheticPreviewView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthoringSyntheticPreviewServiceTest {

    @Mock
    private AuthoringWorkspaceService workspaceService;

    private AuthoringSyntheticPreviewService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new AuthoringSyntheticPreviewService(workspaceService);
    }

    @Test
    void generatesDeterministicFixtureWithoutReplayingEmbeddedRows() throws Exception {
        when(workspaceService.get("session-1")).thenReturn(session("""
                {
                  "schemaVersion":3,
                  "blocks":[
                    {"id":"form","blockType":"form","blocks":[
                      {"id":"name","blockType":"field","field":"name",
                       "props":{"component":"input"}},
                      {"id":"phone","blockType":"field","field":"phone",
                       "props":{"component":"input"}},
                      {"id":"active","blockType":"field","field":"active",
                       "props":{"component":"switch"}}
                    ]},
                    {"id":"table","blockType":"table",
                     "props":{"rows":[{"name":"REAL-TENANT-SECRET"}]},
                     "blocks":[{"id":"amount","blockType":"column","field":"amount"}]},
                    {"id":"chart","blockType":"widget","widgetType":"line-chart"}
                  ]
                }
                """));

        SyntheticPreviewView preview = service.preview("session-1");

        assertThat(preview.mode()).isEqualTo("SYNTHETIC");
        assertThat(preview.source()).isEqualTo("GENERATED_IN_MEMORY");
        assertThat(preview.isolatedFromTenantData()).isTrue();
        assertThat(preview.persisted()).isFalse();
        assertThat(preview.exportAllowed()).isFalse();
        assertThat(preview.businessActionsAllowed()).isFalse();
        assertThat(preview.fixtureRevision()).isEqualTo(7L);
        assertThat(preview.records()).hasSize(3);
        assertThat(preview.formValues())
                .containsEntry("name", "Sample name 01")
                .containsEntry("phone", "13800000001")
                .containsEntry("active", true)
                .containsEntry("amount", 101)
                .containsEntry("pid", "synthetic-001");
        assertThat(preview.widgets()).containsKey("chart");
        assertThat(preview.widgets().get("chart").series()).hasSize(3);
        assertThat(objectMapper.writeValueAsString(preview))
                .doesNotContain("REAL-TENANT-SECRET");
        verify(workspaceService).get("session-1");
        verifyNoMoreInteractions(workspaceService);
    }

    private SessionView session(String snapshot) throws Exception {
        return new SessionView(
                "session-1",
                "changes-1",
                "page-1",
                null,
                11L,
                "DRAFT",
                "AUTHORING",
                "ACTIVE",
                7L,
                "LOW",
                "HANDOFF_STUDIO",
                "DRAFT",
                "UNKNOWN",
                null,
                "UNKNOWN",
                null,
                "NOT_REQUIRED",
                "NOT_PUBLISHED",
                "manifest",
                objectMapper.readTree(snapshot),
                objectMapper.createObjectNode(),
                null,
                Instant.now().plusSeconds(60));
    }
}
