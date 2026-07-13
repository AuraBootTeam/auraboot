package com.auraboot.framework.faq;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * Manual trigger for the conversation → FAQ loop.
 *
 * <p>M1 ships this one trigger on purpose. The automatic one (distil when a conversation
 * closes) waits for S1 to give conversations a closed state — ab_im_conversation has no
 * status column today — and scoring which conversations are worth distilling waits for real
 * data. Get the loop running first; tune what feeds it second.
 *
 * <p>The path takes a conversation <em>pid</em>, not the internal id the rest of the IM
 * controllers still use. New public surface is pid-only per the dual-id contract; migrating
 * the existing IM boundary is S1's, since it owns framework/im.
 */
@RestController
@RequestMapping("/api/faq")
@RequiredArgsConstructor
public class FaqExtractionController {

    private final FaqCandidateService faqCandidateService;

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
