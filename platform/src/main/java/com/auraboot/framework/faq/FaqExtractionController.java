package com.auraboot.framework.faq;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * The conversation side of the FAQ loop: browse what there is to distil, distil it, and read back
 * what was actually said.
 *
 * <p>Everything here is keyed by conversation <b>pid</b>. It deliberately does not live in
 * {@code framework/im} — that module belongs to the embeddable-channel track and is being
 * reworked, and its public boundary still speaks internal ids. These endpoints only read
 * {@code ab_im_*}; they never write to it.
 *
 * <p>The automatic trigger (distil when a conversation closes) is still absent, and honestly so:
 * {@code ab_im_conversation} has no status column, so there is no closing event to hook. That
 * waits on S1 defining what closing a conversation means.
 */
@RestController
@RequestMapping("/api/faq")
@RequiredArgsConstructor
public class FaqExtractionController {

    private final FaqCandidateService faqCandidateService;
    private final FaqConversationQueryService conversationQueryService;

    /**
     * Conversations available to distil, newest activity first.
     *
     * <p>This is what turns the manual trigger into something a human can actually use — without
     * it the only way to start the loop is to already know a conversation's pid.
     */
    @GetMapping("/conversations")
    @RequirePermission("faq.candidate.extract")
    public ApiResponse<FaqConversationView.Page> listConversations(
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "50") int pageSize) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return ApiResponse.success(
                conversationQueryService.listConversations(tenantId, type, pageNum, pageSize));
    }

    /** The transcript behind a candidate — what the reviewer needs to judge whether the FAQ is faithful. */
    @GetMapping("/conversations/{conversationPid}/messages")
    @RequirePermission("faq.candidate.read")
    public ApiResponse<List<FaqConversationView.Message>> listMessages(@PathVariable String conversationPid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return ApiResponse.success(conversationQueryService.listMessages(tenantId, conversationPid));
    }

    @PostMapping("/conversations/{conversationPid}/extract")
    @RequirePermission("faq.candidate.extract")
    public ApiResponse<List<Map<String, Object>>> extract(@PathVariable String conversationPid,
                                                          @Valid @RequestBody ExtractRequest request) throws Exception {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<Map<String, Object>> created =
                faqCandidateService.extractFromConversation(tenantId, conversationPid, request.getTargetKbPid());
        return ApiResponse.success(created);
    }

    @Data
    public static class ExtractRequest {
        /** Knowledge base the resulting candidates will publish into once approved. */
        @NotBlank
        private String targetKbPid;
    }
}
