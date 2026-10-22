package com.auraboot.framework.agent.tool;

import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.notification.service.EmailSender;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link SendCustomerReplyToolHandler}.
 *
 * <p>Contract after the boundary fix (design
 * docs/superpowers/specs/2026-06-21-cs-reply-tool-boundary-design.md): the tool is a pure
 * platform notification capability — it sends the reply email and records a send-log row.
 * It carries <b>no</b> dependency on {@code CommandExecutor} and issues no business command
 * code from OSS core; logging a CRM activity is the agent's responsibility (via
 * {@code cmd:crm:create_activity}).
 */
class SendCustomerReplyToolHandlerTest {

    private final EmailSender emailSender = mock(EmailSender.class);
    private final DynamicDataMapper dynamicDataMapper = mock(DynamicDataMapper.class);

    // Two collaborators only — no CommandExecutor. This constructor shape IS the contract.
    private final SendCustomerReplyToolHandler handler =
            new SendCustomerReplyToolHandler(emailSender, dynamicDataMapper);

    private static Map<String, Object> validParams() {
        Map<String, Object> p = new HashMap<>();
        p.put("recipient_email", "jane@example.com");
        p.put("reply_subject", "Re: defective widget");
        p.put("reply_body", "Hello Jane, we will arrange a replacement.");
        p.put("complaint_id", "12345"); // present, but core must not act on it
        return p;
    }

    @Test
    void execute_validParams_sendsEmail_logsSent_andReturnsSuccess() {
        Map<String, Object> result = handler.execute(validParams(), 7L);

        verify(emailSender).send("jane@example.com", "Re: defective widget",
                "Hello Jane, we will arrange a replacement.");

        // Records exactly the send-log; core writes nothing else to the DB.
        verify(dynamicDataMapper).insert(eq("ab_notification_send_log"), any());

        assertThat(result).containsEntry("success", true);
    }

    @Test
    void execute_emailSendFails_logsFailed_andReturnsFailure() {
        doThrow(new RuntimeException("smtp down"))
                .when(emailSender).send(any(), any(), any());

        Map<String, Object> result = handler.execute(validParams(), 7L);

        verify(dynamicDataMapper).insert(eq("ab_notification_send_log"), any());
        assertThat(result).containsEntry("success", false);
        assertThat((String) result.get("error")).contains("smtp down");
    }

    @Test
    void execute_missingRequiredParam_returnsError_withoutSending() {
        Map<String, Object> p = validParams();
        p.remove("reply_body");

        Map<String, Object> result = handler.execute(p, 7L);

        verify(emailSender, never()).send(any(), any(), any());
        assertThat(result).containsEntry("success", false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_sendLog_doesNotSetAutoIdentityIdColumn() {
        // ab_notification_send_log.id is `bigint GENERATED ... AS IDENTITY` (auto-assigned, no pid
        // column). Setting it to a ULID string makes the INSERT fail with
        // "column id is of type bigint but expression is of type character varying" at runtime,
        // which breaks the tool. Mocked-mapper tests can't see column types, so this asserts the
        // log row never carries an "id" key — letting the identity column auto-generate.
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);

        handler.execute(validParams(), 7L);

        verify(dynamicDataMapper).insert(eq("ab_notification_send_log"), captor.capture());
        assertThat(captor.getValue()).doesNotContainKey("id");
        // sanity: the row still carries the real send-log fields
        assertThat(captor.getValue()).containsKeys("tenant_id", "template_code", "recipient", "status");
    }
}
