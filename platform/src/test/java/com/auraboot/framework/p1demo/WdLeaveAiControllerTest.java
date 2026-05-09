package com.auraboot.framework.p1demo;

import com.auraboot.framework.application.tenant.MetaContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WdLeaveAiControllerTest {

    @Mock
    private WdLeaveAiFillService aiFillService;
    @Mock
    private AcpAiAnnotationRepository annotationRepository;

    private WdLeaveAiController controller;
    private MockedStatic<MetaContext> metaContextMock;

    @BeforeEach
    void setUp() {
        controller = new WdLeaveAiController(aiFillService, annotationRepository);
        metaContextMock = Mockito.mockStatic(MetaContext.class);
    }

    @AfterEach
    void tearDown() {
        metaContextMock.close();
    }

    @Test
    void aiFill_nullRequest_returnsBadRequest() {
        ResponseEntity<WdLeaveAiController.AiFillResponse> resp = controller.aiFill(null);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().errorKey()).isEqualTo("ai.fill.nl_input_required");
    }

    @Test
    void aiFill_blankNlInput_returnsBadRequest() {
        ResponseEntity<WdLeaveAiController.AiFillResponse> resp = controller.aiFill(
                new WdLeaveAiController.AiFillRequest("  ", null, null));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aiFill_noTenant_returns401() {
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(null);
        ResponseEntity<WdLeaveAiController.AiFillResponse> resp = controller.aiFill(
                new WdLeaveAiController.AiFillRequest("一周年假", null, null));
        assertThat(resp.getStatusCodeValue()).isEqualTo(401);
        assertThat(resp.getBody().errorKey()).isEqualTo("ai.fill.tenant_required");
    }

    @Test
    void aiFill_happyPath_persistsAndReturnsResult() {
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(7L);
        WdLeaveAiFillService.AiFillResult fillResult =
                new WdLeaveAiFillService.AiFillResult("turn-1", Map.of("k", "v"), 100L, 0.001, "raw");
        when(aiFillService.extractFields(eq("我请假"), anyString(), eq(7L))).thenReturn(fillResult);
        when(annotationRepository.insertGrounding(eq(7L), eq("wd_leave_request"), eq(99L),
                eq("turn-1"), eq("我请假"), eq(Map.of("k", "v")))).thenReturn(123L);

        ResponseEntity<WdLeaveAiController.AiFillResponse> resp = controller.aiFill(
                new WdLeaveAiController.AiFillRequest("我请假", "2026-05-01", 99L));

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody().turnId()).isEqualTo("turn-1");
        assertThat(resp.getBody().annotationId()).isEqualTo(123L);
    }

    @Test
    void aiFill_targetIdDefaultsToMinusOne_whenMissing() {
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(7L);
        WdLeaveAiFillService.AiFillResult fillResult =
                new WdLeaveAiFillService.AiFillResult("turn-2", Map.of(), 50L, 0.0001, "raw");
        when(aiFillService.extractFields(anyString(), anyString(), anyLong())).thenReturn(fillResult);

        controller.aiFill(new WdLeaveAiController.AiFillRequest("hi", null, null));
        verify(annotationRepository).insertGrounding(eq(7L), anyString(), eq(-1L),
                anyString(), anyString(), any());
    }

    @Test
    void safetyCheck_underThreshold_noEscalation() {
        ResponseEntity<WdLeaveAiController.SafetyCheckResponse> resp = controller.safetyCheck(
                new WdLeaveAiController.SafetyCheckRequest(3, null, null));
        assertThat(resp.getBody().requiresEscalation()).isFalse();
        assertThat(resp.getBody().triggers()).isEmpty();
        assertThat(resp.getBody().message()).isNull();
    }

    @Test
    void safetyCheck_overThreshold_triggersAndMessage() {
        ResponseEntity<WdLeaveAiController.SafetyCheckResponse> resp = controller.safetyCheck(
                new WdLeaveAiController.SafetyCheckRequest(10, null, null));
        assertThat(resp.getBody().requiresEscalation()).isTrue();
        assertThat(resp.getBody().triggers()).contains("wd_days_over_5");
        assertThat(resp.getBody().message().key()).isEqualTo("ai.safety.days_over_threshold");
    }

    @Test
    void safetyCheck_recordsSafetyTriggerWhenAnnotationExists() {
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(7L);
        when(annotationRepository.findByTarget(7L, "wd_leave_request", 42L))
                .thenReturn(Map.of("id", 100L));

        controller.safetyCheck(new WdLeaveAiController.SafetyCheckRequest(8, 42L, "turn"));

        verify(annotationRepository).recordSafetyTrigger(eq(100L), any());
    }

    @Test
    void safetyCheck_skipsRecordWhenNoAnnotation() {
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(7L);
        when(annotationRepository.findByTarget(7L, "wd_leave_request", 42L)).thenReturn(null);

        controller.safetyCheck(new WdLeaveAiController.SafetyCheckRequest(2, 42L, "turn"));
        verify(annotationRepository, never()).recordSafetyTrigger(anyLong(), any());
    }

    @Test
    void safetyCheck_nullRequest_emptyResponse() {
        ResponseEntity<WdLeaveAiController.SafetyCheckResponse> resp = controller.safetyCheck(null);
        assertThat(resp.getBody().triggers()).isEmpty();
        assertThat(resp.getBody().requiresEscalation()).isFalse();
    }

    @Test
    void getAnnotation_noTenant_returns401() {
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(null);
        ResponseEntity<Map<String, Object>> resp = controller.getAnnotation(1L);
        assertThat(resp.getStatusCodeValue()).isEqualTo(401);
    }

    @Test
    void getAnnotation_notFound_returns404() {
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(7L);
        when(annotationRepository.findByTarget(7L, "wd_leave_request", 1L)).thenReturn(null);
        ResponseEntity<Map<String, Object>> resp = controller.getAnnotation(1L);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getAnnotation_found_returnsRow() {
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(7L);
        Map<String, Object> row = Map.of("id", 1L);
        when(annotationRepository.findByTarget(7L, "wd_leave_request", 1L)).thenReturn(row);
        ResponseEntity<Map<String, Object>> resp = controller.getAnnotation(1L);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody()).isEqualTo(row);
    }

    @Test
    void aiFillResponse_errorFactory_buildsCleanResponse() {
        WdLeaveAiController.AiFillResponse err = WdLeaveAiController.AiFillResponse.error("k");
        assertThat(err.errorKey()).isEqualTo("k");
        assertThat(err.fields()).isEmpty();
        assertThat(err.totalTokens()).isZero();
        assertThat(err.totalDollars()).isZero();
    }
}
