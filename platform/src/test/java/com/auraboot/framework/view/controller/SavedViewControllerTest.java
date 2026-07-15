package com.auraboot.framework.view.controller;

import com.auraboot.framework.tenant.service.CurrentUserTeamResolver;
import com.auraboot.framework.view.dto.SavedViewAuditEventDTO;
import com.auraboot.framework.view.dto.SavedViewCapabilityCheckRequest;
import com.auraboot.framework.view.dto.SavedViewCapabilityCheckResponse;
import com.auraboot.framework.view.service.SavedViewChipPinService;
import com.auraboot.framework.view.service.SavedViewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SavedViewControllerTest {

    @Mock
    private SavedViewService savedViewService;

    @Mock
    private SavedViewChipPinService chipPinService;

    @Mock
    private CurrentUserTeamResolver currentUserTeamResolver;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SavedViewController controller =
                new SavedViewController(savedViewService, chipPinService, currentUserTeamResolver);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void capabilityCheck_returnsStructuredBlockedReasons() throws Exception {
        SavedViewCapabilityCheckResponse response = new SavedViewCapabilityCheckResponse();
        response.setViewType("gallery");
        response.setStatus("blocked");
        response.setMissingFields(List.of("galleryImageField"));
        response.setReasons(List.of(new SavedViewCapabilityCheckResponse.Reason(
                "MISSING_REQUIRED_FIELD",
                "galleryImageField",
                "Missing required gallery viewConfig field: galleryImageField"
        )));
        when(savedViewService.checkCapability(any(SavedViewCapabilityCheckRequest.class))).thenReturn(response);

        SavedViewCapabilityCheckRequest request = new SavedViewCapabilityCheckRequest();
        request.setViewType("gallery");

        mockMvc.perform(post("/api/views/capability-check")
                        .accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.viewType").value("gallery"))
                .andExpect(jsonPath("$.data.status").value("blocked"))
                .andExpect(jsonPath("$.data.missingFields[0]").value("galleryImageField"))
                .andExpect(jsonPath("$.data.reasons[0].code").value("MISSING_REQUIRED_FIELD"));

        verify(savedViewService).checkCapability(any(SavedViewCapabilityCheckRequest.class));
    }

    @Test
    void auditEvents_returnsPidOnlyPublicContract() throws Exception {
        SavedViewAuditEventDTO event = new SavedViewAuditEventDTO();
        event.setEntityPid("view-pid");
        event.setEntityType("saved_view");
        event.setOperationType("UPDATE");
        event.setActorName("Alice");
        event.setChangedFields(List.of("viewConfig"));
        when(savedViewService.getAuditEvents("view-pid")).thenReturn(List.of(event));

        mockMvc.perform(get("/api/views/view-pid/audit-events").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].entityPid").value("view-pid"))
                .andExpect(jsonPath("$.data[0].operationType").value("UPDATE"))
                .andExpect(jsonPath("$.data[0].id").doesNotExist())
                .andExpect(jsonPath("$.data[0].tenantId").doesNotExist())
                .andExpect(jsonPath("$.data[0].entityId").doesNotExist())
                .andExpect(jsonPath("$.data[0].actorId").doesNotExist())
                .andExpect(jsonPath("$.data[0].recordHash").doesNotExist());

        verify(savedViewService).getAuditEvents("view-pid");
    }
}
