package com.auraboot.framework.meta.controller.config;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.dto.AuditTrailPublicDTO;
import com.auraboot.framework.meta.entity.AuditTrail;
import com.auraboot.framework.meta.service.impl.AuditTrailService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditTrailControllerTest {

    @Mock
    private AuditTrailService auditTrailService;

    private AuditTrailController controller;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() {
        controller = new AuditTrailController(auditTrailService);
        MetaContext.setContext(100L, 700L, "user-pid", "operator");
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void getAuditTrail_returnsPublicDtoWithoutInternalIdsSnapshotsOrHashes() throws Exception {
        AuditTrail trail = new AuditTrail();
        trail.setId(11L);
        trail.setTenantId(100L);
        trail.setSequenceNo(3L);
        trail.setEventType("SAVED_VIEW");
        trail.setEntityType("saved_view");
        trail.setEntityId(22L);
        trail.setEntityPid("view_pid");
        trail.setCommandCode("saved_view:update");
        trail.setOperationType("UPDATE");
        trail.setActorId(700L);
        trail.setActorName("operator");
        trail.setActorIp("127.0.0.1");
        trail.setTimestamp(Instant.parse("2026-06-22T00:00:00Z"));
        trail.setBeforeSnapshot(JsonNodeFactory.instance.objectNode().put("name", "old"));
        trail.setAfterSnapshot(JsonNodeFactory.instance.objectNode().put("name", "new"));
        trail.setChangedFields(new String[] {"name"});
        trail.setMetadata(JsonNodeFactory.instance.objectNode().put("summary", "Updated"));
        trail.setPreviousHash("prev");
        trail.setRecordHash("hash");
        when(auditTrailService.getAuditTrailByPid(100L, "saved_view", "view_pid"))
                .thenReturn(List.of(trail));

        ApiResponse<List<AuditTrailPublicDTO>> response = controller.getAuditTrail(
                "saved_view", null, "view_pid");

        assertTrue(response.isSuccess());
        assertEquals("view_pid", response.getData().get(0).getEntityPid());

        JsonNode first = objectMapper.readTree(objectMapper.writeValueAsString(response))
                .path("data")
                .path(0);
        assertEquals("view_pid", first.path("entityPid").asText());
        assertFalse(first.has("id"));
        assertFalse(first.has("tenantId"));
        assertFalse(first.has("entityId"));
        assertFalse(first.has("actorId"));
        assertFalse(first.has("actorIp"));
        assertFalse(first.has("beforeSnapshot"));
        assertFalse(first.has("afterSnapshot"));
        assertFalse(first.has("previousHash"));
        assertFalse(first.has("recordHash"));
    }
}
