package com.auraboot.framework.connector.airflow;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * POST endpoint for Airflow webhooks. PRD 18-C §C.3.3.
 *
 * <p>Delegates to {@link AirflowWebhookService} and maps
 * {@link AirflowWebhookException} into a structured error response so
 * callers can branch on the {@code errorCode} field instead of parsing
 * the message.
 */
@Slf4j
@RestController
@RequestMapping("/api/webhooks/airflow")
@RequiredArgsConstructor
public class AirflowWebhookController {

    private final AirflowWebhookService service;

    @PostMapping(value = "/trigger", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> trigger(
            @RequestHeader(name = AirflowWebhookService.SIGNATURE_HEADER, required = false) String signature,
            @RequestHeader(name = AirflowWebhookService.WEBHOOK_ID_HEADER, required = false) String webhookId,
            @RequestBody(required = false) byte[] body) {
        byte[] safeBody = body == null ? new byte[0] : body;
        try {
            AirflowWebhookEvent event = service.verifyAndDispatch(signature, webhookId, safeBody);
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("status", "ACCEPTED");
            ok.put("webhookId", event.getWebhookId());
            ok.put("event", event.getEvent());
            ok.put("dagId", event.getDagId());
            ok.put("taskId", event.getTaskId());
            return ResponseEntity.accepted().body(ok);
        } catch (AirflowWebhookException e) {
            log.info("Airflow webhook rejected: status={} code={} reason={}",
                    e.httpStatus(), e.errorCode(), e.getMessage());
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("status", "REJECTED");
            err.put("errorCode", e.errorCode());
            err.put("message", e.getMessage());
            return ResponseEntity.status(e.httpStatus()).body(err);
        }
    }
}
