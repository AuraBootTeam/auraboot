//package com.auraboot.framework.tenant.controller;
//
//import com.auraboot.framework.tenant.dao.entity.Invitation;
//import com.auraboot.framework.tenant.service.InvitationService;
//import com.auraboot.framework.common.result.Result;
//import com.auraboot.framework.common.result.PageResult;
//import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
//import com.fasterxml.jackson.databind.ObjectMapper;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.mockito.InjectMocks;
//import org.mockito.Mock;
//import org.mockito.junit.jupiter.MockitoExtension;
//import org.springframework.http.MediaType;
//import org.springframework.test.web.servlet.MockMvc;
//import org.springframework.test.web.servlet.setup.MockMvcBuilders;
//import org.springframework.web.multipart.MultipartFile;
//
//import java.time.LocalDateTime;
//import java.util.*;
//
//import static org.mockito.ArgumentMatchers.*;
//import static org.mockito.Mockito.*;
//import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
//import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
//
///**
// * 邀请管理控制器测试类
// */
//@ExtendWith(MockitoExtension.class)
//class InvitationControllerTest {
//
//    @Mock
//    private InvitationService invitationService;
//
//    @InjectMocks
//    private InvitationController invitationController;
//
//    private MockMvc mockMvc;
//    private ObjectMapper objectMapper;
//    private Invitation testInvitation;
//
//    @BeforeEach
//    void setUp() {
//        mockMvc = MockMvcBuilders.standaloneSetup(invitationController).build();
//        objectMapper = new ObjectMapper();
//
//        // 创建测试邀请对象
//        testInvitation = new Invitation();
//        testInvitation.setId(1L);
//        testInvitation.setTenantId(-1L);
//        testInvitation.setInviterId(1L);
//        testInvitation.setInviteeEmail("test@example.com");
//        testInvitation.setInviteePhone("13800138000");
//        testInvitation.setInvitationCode("inv123456");
//        testInvitation.setInvitationToken("token123456");
//        testInvitation.setStatus("pending");
//        testInvitation.setExpiryTime(LocalDateTime.now().plusDays(7));
//        testInvitation.setCreateTime(LocalDateTime.now());
//    }
//
//    @Test
//    void testCreateInvitation() throws Exception {
//        // Given
//        when(invitationService.createInvitation(any(Invitation.class))).thenReturn(testInvitation);
//
//        // When & Then
//        mockMvc.perform(post("/api/invitation")
//                .contentType(MediaType.APPLICATION_JSON)
//                .content(objectMapper.writeValueAsString(testInvitation)))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true))
//                .andExpect(jsonPath("$.data.id").value(1L))
//                .andExpect(jsonPath("$.data.inviteeEmail").value("test@example.com"));
//
//        verify(invitationService).createInvitation(any(Invitation.class));
//    }
//
//    @Test
//    void testSendInvitation() throws Exception {
//        // Given
//        when(invitationService.sendInvitation(1L)).thenReturn(true);
//
//        // When & Then
//        mockMvc.perform(post("/api/invitation/send")
//                .param("invitationId", "1"))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true))
//                .andExpect(jsonPath("$.data").value(true));
//
//        verify(invitationService).sendInvitation(1L);
//    }
//
//    @Test
//    void testAcceptInvitation() throws Exception {
//        // Given
//        Map<String, Object> acceptData = new HashMap<>();
//        acceptData.put("userId", 1L);
//        when(invitationService.acceptInvitation(eq("inv123456"), any(Map.class))).thenReturn(true);
//
//        // When & Then
//        mockMvc.perform(post("/api/invitation/accept")
//                .param("invitationCode", "inv123456")
//                .contentType(MediaType.APPLICATION_JSON)
//                .content(objectMapper.writeValueAsString(acceptData)))
//                .andExpect(status().isOk())
//                .andExpected(jsonPath("$.success").value(true))
//                .andExpect(jsonPath("$.data").value(true));
//
//        verify(invitationService).acceptInvitation(eq("inv123456"), any(Map.class));
//    }
//
//    @Test
//    void testRejectInvitation() throws Exception {
//        // Given
//        when(invitationService.rejectInvitation("inv123456", "Not interested")).thenReturn(true);
//
//        // When & Then
//        mockMvc.perform(post("/api/invitation/reject")
//                .param("invitationCode", "inv123456")
//                .param("reason", "Not interested"))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true))
//                .andExpect(jsonPath("$.data").value(true));
//
//        verify(invitationService).rejectInvitation("inv123456", "Not interested");
//    }
//
//    @Test
//    void testRevokeInvitation() throws Exception {
//        // Given
//        when(invitationService.revokeInvitation(1L)).thenReturn(true);
//
//        // When & Then
//        mockMvc.perform(post("/api/invitation/revoke")
//                .param("invitationId", "1"))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true))
//                .andExpect(jsonPath("$.data").value(true));
//
//        verify(invitationService).revokeInvitation(1L);
//    }
//
//    @Test
//    void testResendInvitation() throws Exception {
//        // Given
//        when(invitationService.resendInvitation(1L)).thenReturn(true);
//
//        // When & Then
//        mockMvc.perform(post("/api/invitation/resend")
//                .param("invitationId", "1"))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true))
//                .andExpect(jsonPath("$.data").value(true));
//
//        verify(invitationService).resendInvitation(1L);
//    }
//
//    @Test
//    void testGetInvitationByCode() throws Exception {
//        // Given
//        when(invitationService.getInvitationByCode("inv123456")).thenReturn(testInvitation);
//
//        // When & Then
//        mockMvc.perform(get("/api/invitation/code/INV123456"))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true))
//                .andExpect(jsonPath("$.data.id").value(1L))
//                .andExpect(jsonPath("$.data.invitationCode").value("inv123456"));
//
//        verify(invitationService).getInvitationByCode("inv123456");
//    }
//
//    @Test
//    void testGetInvitationByToken() throws Exception {
//        // Given
//        when(invitationService.getInvitationByToken("token123456")).thenReturn(testInvitation);
//
//        // When & Then
//        mockMvc.perform(get("/api/invitation/token/token123456"))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true))
//                .andExpect(jsonPath("$.data.id").value(1L))
//                .andExpect(jsonPath("$.data.invitationToken").value("token123456"));
//
//        verify(invitationService).getInvitationByToken("token123456");
//    }
//
//    @Test
//    void testGetInvitationsByTenantId() throws Exception {
//        // Given
//        List<Invitation> invitations = Arrays.asList(testInvitation);
//        when(invitationService.getInvitationsByTenantId(1L)).thenReturn(invitations);
//
//        // When & Then
//        mockMvc.perform(get("/api/invitation/tenant/1"))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true))
//                .andExpect(jsonPath("$.data").isArray())
//                .andExpect(jsonPath("$.data[0].id").value(1L));
//
//        verify(invitationService).getInvitationsByTenantId(1L);
//    }
//
//    @Test
//    void testGetInvitationsByEmail() throws Exception {
//        // Given
//        List<Invitation> invitations = Arrays.asList(testInvitation);
//        when(invitationService.getInvitationsByEmail("test@example.com")).thenReturn(invitations);
//
//        // When & Then
//        mockMvc.perform(get("/api/invitation/email/test@example.com"))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true))
//                .andExpect(jsonPath("$.data").isArray())
//                .andExpect(jsonPath("$.data[0].inviteeEmail").value("test@example.com"));
//
//        verify(invitationService).getInvitationsByEmail("test@example.com");
//    }
//
//    @Test
//    void testGetInvitations() throws Exception {
//        // Given
//        Page<Invitation> page = new Page<>(1, 10);
//        page.setRecords(Arrays.asList(testInvitation));
//        page.setTotal(1);
//        when(invitationService.findInvitations(anyInt(), anyInt(), any(), any(), any(), any(), any(), any()))
//                .thenReturn(page);
//
//        // When & Then
//        mockMvc.perform(get("/api/invitation")
//                .param("pageNum", "1")
//                .param("pageSize", "10")
//                .param("tenantId", "1")
//                .param("status", "pending"))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true))
//                .andExpect(jsonPath("$.data.records").isArray())
//                .andExpect(jsonPath("$.data.total").value(1));
//
//        verify(invitationService).findInvitations(1, 10, 1L, null, null, null, "pending", null);
//    }
//
//    @Test
//    void testGetPendingInvitations() throws Exception {
//        // Given
//        List<Invitation> invitations = Arrays.asList(testInvitation);
//        when(invitationService.getPendingInvitations(1L)).thenReturn(invitations);
//
//        // When & Then
//        mockMvc.perform(get("/api/invitation/pending")
//                .param("tenantId", "1"))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true))
//                .andExpect(jsonPath("$.data").isArray());
//
//        verify(invitationService).getPendingInvitations(1L);
//    }
//
//    @Test
//    void testGetExpiredInvitations() throws Exception {
//        // Given
//        List<Invitation> invitations = Arrays.asList(testInvitation);
//        when(invitationService.getExpiredInvitations(1L)).thenReturn(invitations);
//
//        // When & Then
//        mockMvc.perform(get("/api/invitation/expired")
//                .param("tenantId", "1"))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true))
//                .andExpect(jsonPath("$.data").isArray());
//
//        verify(invitationService).getExpiredInvitations(1L);
//    }
//
//    @Test
//    void testCountInvitations() throws Exception {
//        // Given
//        when(invitationService.countInvitations(1L, "pending")).thenReturn(5L);
//
//        // When & Then
//        mockMvc.perform(get("/api/invitation/count")
//                .param("tenantId", "1")
//                .param("status", "pending"))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true))
//                .andExpect(jsonPath("$.data").value(5));
//
//        verify(invitationService).countInvitations(1L, "pending");
//    }
//
//    @Test
//    void testIsEmailInvited() throws Exception {
//        // Given
//        when(invitationService.isEmailInvited("test@example.com", 1L)).thenReturn(true);
//
//        // When & Then
//        mockMvc.perform(get("/api/invitation/check-email")
//                .param("email", "test@example.com")
//                .param("tenantId", "1"))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true))
//                .andExpect(jsonPath("$.data").value(true));
//
//        verify(invitationService).isEmailInvited("test@example.com", 1L);
//    }
//
//    @Test
//    void testIsPhoneInvited() throws Exception {
//        // Given
//        when(invitationService.isPhoneInvited("13800138000", 1L)).thenReturn(false);
//
//        // When & Then
//        mockMvc.perform(get("/api/invitation/check-phone")
//                .param("phone", "13800138000")
//                .param("tenantId", "1"))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true))
//                .andExpect(jsonPath("$.data").value(false));
//
//        verify(invitationService).isPhoneInvited("13800138000", 1L);
//    }
//
//    @Test
//    void testGetInvitationDetails() throws Exception {
//        // Given
//        Map<String, Object> details = new HashMap<>();
//        details.put("id", 1L);
//        details.put("status", "pending");
//        details.put("inviterName", "John Doe");
//        when(invitationService.getInvitationDetails(1L)).thenReturn(details);
//
//        // When & Then
//        mockMvc.perform(get("/api/invitation/1/details"))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true))
//                .andExpect(jsonPath("$.data.id").value(1))
//                .andExpect(jsonPath("$.data.status").value("pending"));
//
//        verify(invitationService).getInvitationDetails(1L);
//    }
//
//    @Test
//    void testValidateInvitationCode() throws Exception {
//        // Given
//        Map<String, Object> validation = new HashMap<>();
//        validation.put("valid", true);
//        validation.put("expired", false);
//        when(invitationService.validateInvitationCode("inv123456")).thenReturn(validation);
//
//        // When & Then
//        mockMvc.perform(get("/api/invitation/validate-code")
//                .param("invitationCode", "inv123456"))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true))
//                .andExpect(jsonPath("$.data.valid").value(true))
//                .andExpect(jsonPath("$.data.expired").value(false));
//
//        verify(invitationService).validateInvitationCode("inv123456");
//    }
//
//    @Test
//    void testGenerateInvitationCode() throws Exception {
//        // Given
//        when(invitationService.generateInvitationCode(1L)).thenReturn("inv789012");
//
//        // When & Then
//        mockMvc.perform(post("/api/invitation/generate-code")
//                .param("invitationId", "1"))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true))
//                .andExpect(jsonPath("$.data").value("inv789012"));
//
//        verify(invitationService).generateInvitationCode(1L);
//    }
//
//    @Test
//    void testGetInvitationStatistics() throws Exception {
//        // Given
//        Map<String, Object> statistics = new HashMap<>();
//        statistics.put("totalInvitations", 100);
//        statistics.put("pendingInvitations", 20);
//        statistics.put("acceptedInvitations", 70);
//        statistics.put("rejectedInvitations", 10);
//        when(invitationService.getInvitationStatistics(1L, 30)).thenReturn(statistics);
//
//        // When & Then
//        mockMvc.perform(get("/api/invitation/statistics")
//                .param("tenantId", "1")
//                .param("days", "30"))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true))
//                .andExpect(jsonPath("$.data.totalInvitations").value(100))
//                .andExpect(jsonPath("$.data.pendingInvitations").value(20));
//
//        verify(invitationService).getInvitationStatistics(1L, 30);
//    }
//
//    @Test
//    void testBatchCreateInvitations() throws Exception {
//        // Given
//        List<Invitation> invitations = Arrays.asList(testInvitation);
//        when(invitationService.batchCreateInvitations(any(List.class))).thenReturn(invitations);
//
//        // When & Then
//        mockMvc.perform(post("/api/invitation/batch-create")
//                .contentType(MediaType.APPLICATION_JSON)
//                .content(objectMapper.writeValueAsString(invitations)))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true))
//                .andExpect(jsonPath("$.data").isArray());
//
//        verify(invitationService).batchCreateInvitations(any(List.class));
//    }
//
//    @Test
//    void testDeleteInvitation() throws Exception {
//        // Given
//        when(invitationService.deleteInvitation(1L)).thenReturn(true);
//
//        // When & Then
//        mockMvc.perform(delete("/api/invitation/1"))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true))
//                .andExpect(jsonPath("$.data").value(true));
//
//        verify(invitationService).deleteInvitation(1L);
//    }
//
//    @Test
//    void testBatchDeleteInvitations() throws Exception {
//        // Given
//        List<Long> invitationIds = Arrays.asList(1L, 2L, 3L);
//        when(invitationService.batchDeleteInvitations(invitationIds)).thenReturn(3);
//
//        // When & Then
//        mockMvc.perform(delete("/api/invitation/batch")
//                .contentType(MediaType.APPLICATION_JSON)
//                .content(objectMapper.writeValueAsString(invitationIds)))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true))
//                .andExpect(jsonPath("$.data").value(3));
//
//        verify(invitationService).batchDeleteInvitations(invitationIds);
//    }
//
//    @Test
//    void testGetInvitationLink() throws Exception {
//        // Given
//        String link = "https://example.com/invitation/accept?code=INV123456";
//        when(invitationService.getInvitationLink(1L)).thenReturn(link);
//
//        // When & Then
//        mockMvc.perform(get("/api/invitation/1/link"))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true))
//                .andExpect(jsonPath("$.data").value(link));
//
//        verify(invitationService).getInvitationLink(1L);
//    }
//
//    @Test
//    void testSendInvitationEmail() throws Exception {
//        // Given
//        Map<String, Object> emailConfig = new HashMap<>();
//        emailConfig.put("template", "default");
//        emailConfig.put("subject", "You're invited!");
//        when(invitationService.sendInvitationEmail(eq(1L), any(Map.class))).thenReturn(true);
//
//        // When & Then
//        mockMvc.perform(post("/api/invitation/send-email")
//                .param("invitationId", "1")
//                .contentType(MediaType.APPLICATION_JSON)
//                .content(objectMapper.writeValueAsString(emailConfig)))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true))
//                .andExpect(jsonPath("$.data").value(true));
//
//        verify(invitationService).sendInvitationEmail(eq(1L), any(Map.class));
//    }
//
//    @Test
//    void testUpdateInvitation() throws Exception {
//        // Given
//        Invitation updatedInvitation = new Invitation();
//        updatedInvitation.setId(1L);
//        updatedInvitation.setInviteeEmail("updated@example.com");
//        when(invitationService.updateInvitation(any(Invitation.class))).thenReturn(updatedInvitation);
//
//        // When & Then
//        mockMvc.perform(put("/api/invitation/1")
//                .contentType(MediaType.APPLICATION_JSON)
//                .content(objectMapper.writeValueAsString(updatedInvitation)))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true))
//                .andExpect(jsonPath("$.data.id").value(1L));
//
//        verify(invitationService).updateInvitation(any(Invitation.class));
//    }
//
//    @Test
//    void testExtendInvitationExpiry() throws Exception {
//        // Given
//        when(invitationService.extendInvitationExpiry(1L, 7)).thenReturn(true);
//
//        // When & Then
//        mockMvc.perform(post("/api/invitation/1/extend")
//                .param("days", "7"))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true))
//                .andExpect(jsonPath("$.data").value(true));
//
//        verify(invitationService).extendInvitationExpiry(1L, 7);
//    }
//
//    @Test
//    void testGetInvitationHistory() throws Exception {
//        // Given
//        List<Map<String, Object>> history = new ArrayList<>();
//        Map<String, Object> historyItem = new HashMap<>();
//        historyItem.put("action", "created");
//        historyItem.put("timestamp", "2023-01-01T10:00:00");
//        history.add(historyItem);
//        when(invitationService.getInvitationHistory(1L)).thenReturn(history);
//
//        // When & Then
//        mockMvc.perform(get("/api/invitation/1/history"))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true))
//                .andExpect(jsonPath("$.data").isArray())
//                .andExpect(jsonPath("$.data[0].action").value("created"));
//
//        verify(invitationService).getInvitationHistory(1L);
//    }
//
//    @Test
//    void testSearchInvitations() throws Exception {
//        // Given
//        List<Invitation> invitations = Arrays.asList(testInvitation);
//        when(invitationService.searchInvitations("test", 1L, 20)).thenReturn(invitations);
//
//        // When & Then
//        mockMvc.perform(get("/api/invitation/search")
//                .param("keyword", "test")
//                .param("tenantId", "1")
//                .param("limit", "20"))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true))
//                .andExpect(jsonPath("$.data").isArray());
//
//        verify(invitationService).searchInvitations("test", 1L, 20);
//    }
//
//    @Test
//    void testGetInvitationConfig() throws Exception {
//        // Given
//        Map<String, Object> config = new HashMap<>();
//        config.put("maxInvitations", 100);
//        config.put("expiryDays", 7);
//        when(invitationService.getInvitationConfig(1L)).thenReturn(config);
//
//        // When & Then
//        mockMvc.perform(get("/api/invitation/config")
//                .param("tenantId", "1"))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true))
//                .andExpect(jsonPath("$.data.maxInvitations").value(100));
//
//        verify(invitationService).getInvitationConfig(1L);
//    }
//
//    @Test
//    void testUpdateInvitationConfig() throws Exception {
//        // Given
//        Map<String, Object> config = new HashMap<>();
//        config.put("maxInvitations", 200);
//        config.put("expiryDays", 14);
//        when(invitationService.updateInvitationConfig(eq(1L), any(Map.class))).thenReturn(true);
//
//        // When & Then
//        mockMvc.perform(put("/api/invitation/config")
//                .param("tenantId", "1")
//                .contentType(MediaType.APPLICATION_JSON)
//                .content(objectMapper.writeValueAsString(config)))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true))
//                .andExpect(jsonPath("$.data").value(true));
//
//        verify(invitationService).updateInvitationConfig(eq(1L), any(Map.class));
//    }
//}