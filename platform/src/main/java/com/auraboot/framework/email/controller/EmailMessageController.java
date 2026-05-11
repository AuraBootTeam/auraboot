package com.auraboot.framework.email.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.common.util.PaginationSafetyUtils;
import com.auraboot.framework.email.mapper.EmailAccountMapper;
import com.auraboot.framework.email.mapper.EmailMessageMapper;
import com.auraboot.framework.email.mapper.EmailRecordLinkMapper;
import com.auraboot.framework.email.model.EmailAccount;
import com.auraboot.framework.email.model.EmailMessage;
import com.auraboot.framework.email.model.EmailRecordLink;
import com.auraboot.framework.email.service.EmailRecordLinkService;
import com.auraboot.framework.email.service.EmailSendService;
import com.auraboot.framework.email.service.EmailTrackingService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for email message operations.
 *
 * <p>Exposes list, detail, send, read-mark, CRM-link and tracking endpoints.
 * All mutations require the caller to be the owning tenant (enforced via MetaContext).
 *
 * @since 6.5.0
 */
@Slf4j
@RestController
@RequestMapping("/api/email/messages")
@RequiredArgsConstructor
@Tag(name = "Email Messages", description = "Inbox, send, and CRM linking for email messages")
public class EmailMessageController {

    private final EmailMessageMapper     emailMessageMapper;
    private final EmailRecordLinkMapper  emailRecordLinkMapper;
    private final EmailRecordLinkService emailRecordLinkService;
    private final EmailSendService       emailSendService;
    private final EmailTrackingService   emailTrackingService;
    private final EmailAccountMapper     emailAccountMapper;

    // ──────────────────────────────────────────────────────────────────────────
    // List / thread / detail
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Lists email messages with optional filters.
     *
     * @param accountId filter by account (optional)
     * @param folder    {@code inbox}, {@code sent}, or {@code all} (default all)
     * @param q         keyword search across subject and body_text
     * @param isRead    filter by read status (optional)
     * @param pageNum   1-based page number (default 1)
     * @param pageSize  page size (default 20, max 200)
     */
    @GetMapping
    @Operation(summary = "List email messages with filters")
    public ApiResponse<IPage<EmailMessage>> listMessages(
            @RequestParam(required = false) Long accountId,
            @RequestParam(defaultValue = "all") String folder,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean isRead,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {

        Long tenantId = MetaContext.getCurrentTenantId();
        if (pageSize > 200) {
            pageSize = 200;
        }

        LambdaQueryWrapper<EmailMessage> wrapper = new LambdaQueryWrapper<EmailMessage>()
                .eq(EmailMessage::getTenantId, tenantId);

        if (accountId != null) {
            wrapper.eq(EmailMessage::getAccountId, accountId);
        }

        if ("inbox".equalsIgnoreCase(folder)) {
            wrapper.eq(EmailMessage::getDirection, "inbound");
        } else if ("sent".equalsIgnoreCase(folder)) {
            wrapper.eq(EmailMessage::getDirection, "outbound");
        }

        if (isRead != null) {
            wrapper.eq(EmailMessage::getIsRead, isRead);
        }

        if (q != null && !q.isBlank()) {
            String like = "%" + q.trim() + "%";
            wrapper.and(w -> w.like(EmailMessage::getSubject, like)
                              .or()
                              .like(EmailMessage::getBodyText, like));
        }

        wrapper.orderByDesc(EmailMessage::getGmailDate);

        IPage<EmailMessage> page = emailMessageMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return ApiResponse.ok(page);
    }

    /**
     * Returns all messages in a Gmail thread, newest first.
     *
     * @param threadId Gmail thread ID
     */
    @GetMapping("/threads/{threadId}")
    @Operation(summary = "Get all messages in a Gmail thread")
    public ApiResponse<List<EmailMessage>> getThread(@PathVariable String threadId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<EmailMessage> messages = emailMessageMapper.findByThread(tenantId, threadId);
        return ApiResponse.ok(messages);
    }

    /**
     * Returns a single message with its tracking stats.
     *
     * @param id message database ID
     */
    @GetMapping("/{id}")
    @Operation(summary = "Get a single email message with tracking stats")
    public ApiResponse<Map<String, Object>> getMessage(@PathVariable Long id) {
        EmailMessage message = emailMessageMapper.selectById(id);
        if (message == null) {
            return ApiResponse.error("Message not found");
        }

        Map<String, Integer> stats = emailTrackingService.getStats(id);
        return ApiResponse.ok(Map.of("message", message, "tracking", stats));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Send
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Sends an email and auto-links it to CRM records.
     *
     * <p>Request body: {@code {accountId, to[], cc[], bcc[], subject, bodyHtml, threadId?,
     * trackingEnabled?}}.
     */
    @PostMapping("/send")
    @Operation(summary = "Send an email and auto-link to CRM records")
    public ApiResponse<EmailMessage> sendMessage(@RequestBody Map<String, Object> body) {
        try {
            Long accountId = toLong(body.get("accountId"));
            EmailAccount account = emailAccountMapper.selectById(accountId);
            if (account == null) {
                return ApiResponse.error("Email account not found");
            }

            @SuppressWarnings("unchecked")
            List<String> to  = (List<String>) body.getOrDefault("to", List.of());
            @SuppressWarnings("unchecked")
            List<String> cc  = (List<String>) body.getOrDefault("cc", List.of());
            @SuppressWarnings("unchecked")
            List<String> bcc = (List<String>) body.getOrDefault("bcc", List.of());

            String subject          = (String) body.get("subject");
            String bodyHtml         = (String) body.get("bodyHtml");
            String threadId         = (String) body.get("threadId");
            boolean trackingEnabled = Boolean.TRUE.equals(body.get("trackingEnabled"));

            EmailMessage sent = emailSendService.send(account, to, cc, bcc, subject,
                    bodyHtml, threadId, trackingEnabled);

            // Auto-link to CRM after sending
            emailRecordLinkService.autoLink(sent);

            return ApiResponse.ok(sent);
        } catch (Exception e) {
            log.error("Failed to send email", e);
            return ApiResponse.error("Failed to send email: " + e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Mark read
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Marks a message as read.
     *
     * @param id message database ID
     */
    @PutMapping("/{id}/read")
    @Operation(summary = "Mark a message as read")
    public ApiResponse<Void> markRead(@PathVariable Long id) {
        EmailMessage message = new EmailMessage();
        message.setId(id);
        message.setIsRead(true);
        emailMessageMapper.updateById(message);
        return ApiResponse.ok();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // CRM links
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Creates a manual CRM record link for a message.
     *
     * <p>Request body: {@code {modelCode, recordId, threadId?}}.
     *
     * @param id message database ID
     */
    @PostMapping("/{id}/link")
    @Operation(summary = "Manually link a message to a CRM record")
    public ApiResponse<EmailRecordLink> linkRecord(@PathVariable Long id,
                                                   @RequestBody Map<String, String> body) {
        Long   tenantId  = MetaContext.getCurrentTenantId();
        String modelCode = body.get("modelCode");
        String recordId  = body.get("recordId");
        String threadId  = body.get("threadId");

        EmailMessage message = emailMessageMapper.selectById(id);
        if (message == null) {
            return ApiResponse.error("Message not found");
        }

        String effectiveThreadId = threadId != null ? threadId : message.getGmailThreadId();
        EmailRecordLink link = emailRecordLinkService.manualLink(tenantId, id,
                effectiveThreadId, modelCode, recordId);
        return ApiResponse.ok(link);
    }

    /**
     * Removes a CRM record link.
     *
     * @param id     message database ID
     * @param linkId link database ID
     */
    @DeleteMapping("/{id}/link/{linkId}")
    @Operation(summary = "Remove a CRM record link from a message")
    public ApiResponse<Void> removeLink(@PathVariable Long id, @PathVariable Long linkId) {
        emailRecordLinkService.removeLink(linkId);
        return ApiResponse.ok();
    }

    /**
     * Returns emails linked to a CRM record (for the CRM Timeline view).
     *
     * @param modelCode DSL model code of the CRM record
     * @param recordId  primary key of the CRM record
     * @param pageNum   1-based page number (default 1)
     * @param pageSize  page size (default 10, max 100)
     */
    @GetMapping("/by-record")
    @Operation(summary = "Get emails linked to a CRM record (for Timeline)")
    public ApiResponse<Map<String, Object>> getByRecord(
            @RequestParam String modelCode,
            @RequestParam String recordId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {

        Long tenantId = MetaContext.getCurrentTenantId();
        pageNum = PaginationSafetyUtils.pageNumber(pageNum);
        pageSize = PaginationSafetyUtils.pageSize(pageSize, 100);

        int offset = PaginationSafetyUtils.offset(pageNum, pageSize, 100);
        List<EmailMessage> messages = emailRecordLinkMapper.findMessagesByRecord(
                tenantId, modelCode, recordId, pageSize, offset);

        return ApiResponse.ok(Map.of(
                "records", messages,
                "pageNum", pageNum,
                "pageSize", pageSize
        ));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Tracking stats
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Returns open and click tracking statistics for a message.
     *
     * @param id message database ID
     */
    @GetMapping("/{id}/tracking")
    @Operation(summary = "Get open/click tracking stats for a message")
    public ApiResponse<Map<String, Integer>> getTracking(@PathVariable Long id) {
        Map<String, Integer> stats = emailTrackingService.getStats(id);
        return ApiResponse.ok(stats);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────────────────

    private Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        try { return Long.parseLong(value.toString()); } catch (NumberFormatException e) { return null; }
    }
}
